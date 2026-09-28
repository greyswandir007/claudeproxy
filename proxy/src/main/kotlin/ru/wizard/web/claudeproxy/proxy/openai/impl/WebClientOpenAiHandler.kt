package ru.wizard.web.claudeproxy.proxy.openai.impl

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.core.ParameterizedTypeReference
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.SignalType
import ru.wizard.web.claudeproxy.auth.ApiKeyAuthFilter
import ru.wizard.web.claudeproxy.proxy.UsageAccumulator
import ru.wizard.web.claudeproxy.proxy.UpstreamError
import ru.wizard.web.claudeproxy.proxy.UpstreamRetryPolicy
import ru.wizard.web.claudeproxy.proxy.ProviderRequestAdjuster
import ru.wizard.web.claudeproxy.proxy.ProxyErrorDetails
import ru.wizard.web.claudeproxy.proxy.TokenSavingAdjuster
import ru.wizard.web.claudeproxy.proxy.cache.CachedResponsePresenter
import ru.wizard.web.claudeproxy.proxy.cache.RequestCacheService
import ru.wizard.web.claudeproxy.providers.ProviderOAuthTokenService
import ru.wizard.web.claudeproxy.routing.RouteCircuitBreaker
import ru.wizard.web.claudeproxy.proxy.openai.OpenAiHandler
import ru.wizard.web.claudeproxy.routing.ModelRegistry
import ru.wizard.web.claudeproxy.usage.UsageEvent
import ru.wizard.web.claudeproxy.usage.UsageRecorder
import java.nio.charset.StandardCharsets.UTF_8

/**
 * Реализация OpenAiHandler на WebClient: перевод запроса → Chat Completions →
 * перевод ответа/SSE обратно в протокол Claude, с записью usage и переключением
 * на следующий маршрут при повторимых ошибках.
 */
@Service
class WebClientOpenAiHandler(
    private val webClient: WebClient,
    private val objectMapper: ObjectMapper,
    private val usageRecorder: UsageRecorder,
    private val requestAdjuster: ProviderRequestAdjuster,
    private val tokenSavingAdjuster: TokenSavingAdjuster,
    private val oauthTokenService: ProviderOAuthTokenService,
    private val routeCircuitBreaker: RouteCircuitBreaker,
    private val requestCacheService: RequestCacheService,
) : OpenAiHandler {
    private val logger = KotlinLogging.logger {}

    private companion object {
        /** Путь-псевдоним для ключа кэша: общий с anthropic-хендлером — один кэш на оба пути. */
        const val REQUEST_CACHE_UPSTREAM_PATH = "/v1/messages"
    }

    private val requestTranslator = OpenAiRequestTranslator(objectMapper)
    private val responseTranslator = OpenAiResponseTranslator(objectMapper)
    private val errorTranslator = OpenAiErrorTranslator(objectMapper)
    private val tokenCountEstimator = OpenAiTokenCountEstimator()

    private val serverSentEventTypeReference = object : ParameterizedTypeReference<ServerSentEvent<String>>() {}

    override suspend fun chatCompletion(
        exchange: ServerWebExchange,
        routes: List<ModelRegistry.Route>,
        requestRoot: JsonNode,
        recordUsage: Boolean,
    ): ResponseEntity<Flux<DataBuffer>> {
        val stream = requestRoot.path("stream").asBoolean(false)
        val clientKey = exchange.getAttribute(ApiKeyAuthFilter.CLIENT_KEY_ATTRIBUTE) ?: "unknown"
        // кэш повторяющихся запросов: точный повтор отдаётся без похода к провайдеру
        val cacheKey =
            if (recordUsage) requestCacheService.buildCacheKey(REQUEST_CACHE_UPSTREAM_PATH, requestRoot) else null
        if (cacheKey != null) {
            requestCacheService.lookup(cacheKey)?.let { cached ->
                usageRecorder.recordAsync(CachedResponsePresenter.usageEvent(exchange, requestRoot, cached))
                return CachedResponsePresenter.buildResponse(exchange, cached, stream)
            }
            // single-flight: параллельный такой же проход мог записать ответ — ждём его
            while (true) {
                val parallelResult = requestCacheService.awaitParallelFlight(cacheKey)
                if (parallelResult == null) break
                usageRecorder.recordAsync(CachedResponsePresenter.usageEvent(exchange, requestRoot, parallelResult))
                return CachedResponsePresenter.buildResponse(exchange, parallelResult, stream)
            }
        }
        // маршруты в кулдауне пропускаем; если кулдаун у всех — пробуем все
        val activeRoutes = routes.filter { routeCircuitBreaker.isAvailable(it.provider.name) }
            .ifEmpty { routes }
        // токен один на запрос: oauth-провайдеры получают его из token-сервиса
        val bearerToken = activeRoutes.firstOrNull()?.let { firstRoute ->
            if (firstRoute.provider.authType == "oauth") {
                oauthTokenService.accessToken(firstRoute.provider) ?: ""
            } else {
                firstRoute.provider.apiKey
            }
        } ?: ""
        return if (stream) {
            ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(
                    Flux.defer {
                        attemptStream(exchange, activeRoutes, 0, requestRoot, recordUsage, clientKey, bearerToken, cacheKey)
                    }.doFinally {
                        // single-flight: первый проход завершился (записал ответ или нет)
                        if (cacheKey != null) requestCacheService.endFlight(cacheKey)
                    },
                )
        } else {
            try {
                attemptSequential(exchange, activeRoutes, requestRoot, recordUsage, clientKey, bearerToken, cacheKey)
            } finally {
                if (cacheKey != null) requestCacheService.endFlight(cacheKey)
            }
        }
    }

    override suspend fun countTokens(
        exchange: ServerWebExchange,
        route: ModelRegistry.Route,
        requestRoot: JsonNode,
    ): ResponseEntity<Flux<DataBuffer>> {
        val estimatedTokens = tokenCountEstimator.estimate(requestRoot)
        val responseBody = objectMapper.createObjectNode().put("input_tokens", estimatedTokens)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                Flux.just(
                    exchange.response.bufferFactory()
                        .wrap(objectMapper.writeValueAsBytes(responseBody)),
                ),
            )
    }

    private suspend fun attemptSequential(
        exchange: ServerWebExchange,
        routes: List<ModelRegistry.Route>,
        requestRoot: JsonNode,
        recordUsage: Boolean,
        clientKey: String,
        bearerToken: String,
        cacheKey: RequestCacheService.RequestCacheKey?,
    ): ResponseEntity<Flux<DataBuffer>> {
        for ((index, route) in routes.withIndex()) {
            val startedAtMilliseconds = System.currentTimeMillis()
            try {
                val (callSpecification, savedTokens) = buildCall(route, requestRoot, bearerToken)
                val responseEntity = callSpecification
                    .retrieve()
                    .onStatus({ !it.is2xxSuccessful }) { response ->
                        response.toEntity(String::class.java).map { errorTranslator.translate(it) }
                    }
                    .toEntity(String::class.java)
                    .awaitSingle()
                // не-стриминговый путь: ttft = upstream = момент получения ответа
                val responseAtMilliseconds = System.currentTimeMillis()
                if (recordUsage) {
                    val translated = responseTranslator.translate(
                        responseEntity.body,
                        route.mapping.publicName,
                    )
                    usageRecorder.recordAsync(
                        usageEvent(
                            clientKey = clientKey,
                            route = route,
                            stream = false,
                            usageAccumulator = translated.usageAccumulator,
                            startedAtMilliseconds = startedAtMilliseconds,
                            status = responseEntity.statusCode.value(),
                            error = if (!responseEntity.statusCode.is2xxSuccessful()) {
                                "HTTP ${responseEntity.statusCode.value()}"
                            } else {
                                null
                            },
                            savedTokens = savedTokens,
                            firstChunkAtMilliseconds = responseAtMilliseconds,
                            upstreamEndedAtMilliseconds = responseAtMilliseconds,
                        ),
                    )
                    routeCircuitBreaker.success(route.provider.name)
                    // первый проход успешен — сохраняем ответ в кэш повторов
                    if (cacheKey != null && responseEntity.statusCode.is2xxSuccessful()) {
                        requestCacheService.storeAsync(
                            RequestCacheService.CachedEntry(
                                cacheKey = cacheKey,
                                responseBody = translated.responseBody,
                                responseFormat = RequestCacheService.ResponseFormat.JSON,
                                model = route.mapping.publicName,
                                provider = route.provider.name,
                                inputTokens = translated.usageAccumulator.inputTokens,
                                outputTokens = translated.usageAccumulator.outputTokens,
                                timeToLiveMilliseconds = RequestCacheService.timeToLiveMilliseconds(route.provider),
                            ),
                        )
                    }
                    return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(
                            Flux.just(
                                exchange.response.bufferFactory()
                                    .wrap(translated.responseBody.toByteArray(UTF_8)),
                            ),
                        )
                }
                return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(
                        Flux.just(
                            exchange.response.bufferFactory()
                                .wrap(
                                    responseTranslator.translate(
                                        responseEntity.body,
                                        route.mapping.publicName,
                                    ).responseBody.toByteArray(UTF_8),
                                ),
                        ),
                    )
            } catch (error: Throwable) {
                if (recordUsage) {
                    val failedAtMilliseconds = System.currentTimeMillis()
                    usageRecorder.recordAsync(
                        usageEvent(
                            clientKey = clientKey,
                            route = route,
                            stream = false,
                            usageAccumulator = UsageAccumulator(),
                            startedAtMilliseconds = startedAtMilliseconds,
                            status = errorStatus(error),
                            error = shortError(error),
                            errorDetail = ProxyErrorDetails.of(error),
                            savedTokens = 0,
                            firstChunkAtMilliseconds = failedAtMilliseconds,
                            upstreamEndedAtMilliseconds = failedAtMilliseconds,
                        ),
                    )
                }
                if (UpstreamRetryPolicy.isRetryable(error)) {
                    routeCircuitBreaker.trip(
                        route.provider.name,
                        shortError(error),
                        UpstreamRetryPolicy.cooldownMilliseconds(error),
                    )
                }
                if (!UpstreamRetryPolicy.isRetryable(error) || index == routes.lastIndex) {
                    throw error
                }
                logger.warn(error) {
                    "Route '${route.provider.name}/${route.mapping.upstreamName}' failed " +
                        "(${shortError(error)}) - switching to next route"
                }
            }
        }
        throw IllegalStateException("Список маршрутов пуст")
    }

    private fun attemptStream(
        exchange: ServerWebExchange,
        routes: List<ModelRegistry.Route>,
        index: Int,
        requestRoot: JsonNode,
        recordUsage: Boolean,
        clientKey: String,
        bearerToken: String,
        cacheKey: RequestCacheService.RequestCacheKey?,
    ): Flux<DataBuffer> {
        val route = routes[index]
        val startedAtMilliseconds = System.currentTimeMillis()
        val sseTranslator = OpenAiSseTranslator(objectMapper, route.mapping.publicName)
        var emittedAnything = false
        // Момент первого события от провайдера (0 — событий не было) для ttft.
        var firstChunkAtMilliseconds: Long = 0
        // Накопление полного SSE-транскрипта для кэша повторов (null — не кэшируем).
        val transcript = ArrayList<String>()
        val (callSpecification, savedTokens) = buildCall(route, requestRoot, bearerToken)
        return callSpecification
            .retrieve()
            .onStatus({ !it.is2xxSuccessful }) { response ->
                response.toEntity(String::class.java).map { errorTranslator.translate(it) }
            }
            .bodyToFlux(serverSentEventTypeReference)
            .concatMap { serverSentEvent ->
                val events = sseTranslator.onData(serverSentEvent.data())
                if (events.isNotEmpty()) {
                    emittedAnything = true
                    if (firstChunkAtMilliseconds == 0L) {
                        firstChunkAtMilliseconds = System.currentTimeMillis()
                    }
                }
                if (cacheKey != null) transcript.addAll(events)
                Flux.fromIterable(events)
            }
            .doOnError { error ->
                if (recordUsage) {
                    val failedAtMilliseconds = System.currentTimeMillis()
                    usageRecorder.recordAsync(
                        usageEvent(
                            clientKey = clientKey,
                            route = route,
                            stream = true,
                            usageAccumulator = sseTranslator.usageAccumulator,
                            startedAtMilliseconds = startedAtMilliseconds,
                            status = errorStatus(error),
                            error = shortError(error),
                            errorDetail = ProxyErrorDetails.of(error),
                            savedTokens = 0,
                            firstChunkAtMilliseconds = firstChunkAtMilliseconds.takeIf { it > 0 },
                            upstreamEndedAtMilliseconds = failedAtMilliseconds,
                        ),
                    )
                }
            }
            .doFinally { signal ->
                if (signal != SignalType.ON_ERROR) {
                    routeCircuitBreaker.success(route.provider.name)
                }
                if (recordUsage && signal != SignalType.ON_ERROR) {
                    val upstreamEndedAtMilliseconds = System.currentTimeMillis()
                    usageRecorder.recordAsync(
                        usageEvent(
                            clientKey = clientKey,
                            route = route,
                            stream = true,
                            usageAccumulator = sseTranslator.usageAccumulator,
                            startedAtMilliseconds = startedAtMilliseconds,
                            status = signalStatus(signal),
                            error = null,
                            savedTokens = savedTokens,
                            firstChunkAtMilliseconds = firstChunkAtMilliseconds.takeIf { it > 0 },
                            upstreamEndedAtMilliseconds = upstreamEndedAtMilliseconds,
                        ),
                    )
                }
                if (cacheKey != null && signal == SignalType.ON_COMPLETE) {
                    // полный SSE-транскрипт успешного прохода — в кэш повторов
                    requestCacheService.storeAsync(
                        RequestCacheService.CachedEntry(
                            cacheKey = cacheKey,
                            responseBody = transcript.joinToString(separator = ""),
                            responseFormat = RequestCacheService.ResponseFormat.SSE,
                            model = route.mapping.publicName,
                            provider = route.provider.name,
                            inputTokens = sseTranslator.usageAccumulator.inputTokens,
                            outputTokens = sseTranslator.usageAccumulator.outputTokens,
                            timeToLiveMilliseconds = RequestCacheService.timeToLiveMilliseconds(route.provider),
                        ),
                    )
                }
            }
            .map { eventText -> eventText.toByteArray(UTF_8) }
            .map { eventBytes -> exchange.response.bufferFactory().wrap(eventBytes) }
            .onErrorResume { error ->
                if (UpstreamRetryPolicy.isRetryable(error)) {
                    routeCircuitBreaker.trip(
                        route.provider.name,
                        shortError(error),
                        UpstreamRetryPolicy.cooldownMilliseconds(error),
                    )
                }
                val canFallback = UpstreamRetryPolicy.isRetryable(error) &&
                    !emittedAnything &&
                    index < routes.lastIndex
                if (canFallback) {
                    logger.warn(error) {
                        "Route '${route.provider.name}/${route.mapping.upstreamName}' failed before " +
                            "first event (${shortError(error)}) - switching to next route"
                    }
                    attemptStream(exchange, routes, index + 1, requestRoot, recordUsage, clientKey, bearerToken, cacheKey)
                } else {
                    logger.error(error) { "Stream from provider '${route.provider.name}' aborted" }
                    Flux.just(
                        exchange.response.bufferFactory()
                            .wrap(serverSentEventErrorBytes(error.message)),
                    )
                }
            }
    }

    private fun buildCall(
        route: ModelRegistry.Route,
        requestRoot: JsonNode,
        bearerToken: String,
    ): Pair<WebClient.RequestHeadersSpec<*>, Long> {
        val provider = route.provider
        val adjustedRoot = requestRoot as ObjectNode
        requestAdjuster.adjust(adjustedRoot, provider)
        val savedTokens = tokenSavingAdjuster.adjust(adjustedRoot, provider)
        val translatedRequest = requestTranslator.translate(adjustedRoot, route)
        val requestSpecification = webClient.post()
            .uri(provider.baseUrl.trimEnd('/') + "/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $bearerToken")
        provider.extraHeaders.forEach { (name, value) -> requestSpecification.header(name, value) }
        return requestSpecification.bodyValue(objectMapper.writeValueAsBytes(translatedRequest)) to savedTokens
    }

    private fun signalStatus(signal: SignalType): Int = when (signal) {
        SignalType.ON_COMPLETE -> 200
        SignalType.CANCEL -> 0
        else -> 500
    }

    private fun errorStatus(error: Throwable): Int =
        (error as? UpstreamError)?.status?.value() ?: 502

    private fun shortError(error: Throwable): String =
        (error.message ?: error.javaClass.simpleName).take(300)

    private fun usageEvent(
        clientKey: String,
        route: ModelRegistry.Route,
        stream: Boolean,
        usageAccumulator: UsageAccumulator,
        startedAtMilliseconds: Long,
        status: Int,
        error: String?,
        savedTokens: Long = 0,
        errorDetail: String? = null,
        // метки латентности: момент первого чанка и конца ответа провайдера
        firstChunkAtMilliseconds: Long? = null,
        upstreamEndedAtMilliseconds: Long? = null,
    ) = UsageEvent(
        ts = System.currentTimeMillis(),
        clientKey = clientKey,
        provider = route.provider.name,
        model = route.mapping.publicName,
        upstreamModel = route.mapping.upstreamName,
        stream = stream,
        inputTokens = usageAccumulator.inputTokens,
        outputTokens = usageAccumulator.outputTokens,
        cacheCreationTokens = usageAccumulator.cacheCreationTokens,
        cacheReadTokens = usageAccumulator.cacheReadTokens,
        durationMilliseconds = System.currentTimeMillis() - startedAtMilliseconds,
        status = status,
        error = error,
        savedTokens = savedTokens,
        errorDetail = errorDetail,
        ttftMilliseconds = firstChunkAtMilliseconds?.let { (it - startedAtMilliseconds).coerceAtLeast(0) },
        upstreamDurationMilliseconds = upstreamEndedAtMilliseconds
            ?.let { (it - startedAtMilliseconds).coerceAtLeast(0) },
    )

    private fun serverSentEventErrorBytes(message: String?): ByteArray {
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "type" to "error",
                "error" to mapOf(
                    "type" to "api_error",
                    "message" to "Ошибка соединения с провайдером: ${message ?: "unknown"}",
                ),
            ),
        )
        return "event: error\ndata: $payload\n\n".toByteArray(UTF_8)
    }
}
