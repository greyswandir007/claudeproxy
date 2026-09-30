package ru.wizard.web.claudeproxy.proxy.impl

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.client.reactive.ClientHttpRequest
import org.springframework.stereotype.Service
import org.springframework.util.MultiValueMap
import org.springframework.web.reactive.function.BodyInserter
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import ru.wizard.web.claudeproxy.providers.UpstreamWebClientFactory
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.SignalType
import ru.wizard.web.claudeproxy.auth.ApiKeyAuthFilter
import ru.wizard.web.claudeproxy.proxy.AnthropicHandler
import ru.wizard.web.claudeproxy.proxy.ProviderRequestAdjuster
import ru.wizard.web.claudeproxy.proxy.ProxyErrorDetails
import ru.wizard.web.claudeproxy.proxy.TokenSavingAdjuster
import ru.wizard.web.claudeproxy.proxy.cache.CachedResponsePresenter
import ru.wizard.web.claudeproxy.proxy.cache.RequestCacheService
import ru.wizard.web.claudeproxy.providers.ProviderOAuthTokenService
import ru.wizard.web.claudeproxy.routing.ConversationAffinityService
import ru.wizard.web.claudeproxy.routing.RouteCircuitBreaker
import ru.wizard.web.claudeproxy.proxy.SseUsageSniffer
import ru.wizard.web.claudeproxy.proxy.UpstreamError
import ru.wizard.web.claudeproxy.proxy.UpstreamRetryPolicy
import ru.wizard.web.claudeproxy.proxy.UsageAccumulator
import ru.wizard.web.claudeproxy.routing.ModelRegistry
import ru.wizard.web.claudeproxy.usage.UsageEvent
import ru.wizard.web.claudeproxy.usage.UsageRecorder
import java.nio.charset.StandardCharsets.UTF_8

/**
 * Реализация AnthropicHandler на WebClient (Reactor Netty) с переключением
 * на следующий маршрут при повторимых ошибках.
 */
@Service
class WebClientAnthropicHandler(
    private val webClientFactory: UpstreamWebClientFactory,
    private val objectMapper: ObjectMapper,
    private val usageRecorder: UsageRecorder,
    private val requestAdjuster: ProviderRequestAdjuster,
    private val tokenSavingAdjuster: TokenSavingAdjuster,
    private val oauthTokenService: ProviderOAuthTokenService,
    private val routeCircuitBreaker: RouteCircuitBreaker,
    private val requestCacheService: RequestCacheService,
    private val conversationAffinityService: ConversationAffinityService,
) : AnthropicHandler {
    private val logger = KotlinLogging.logger {}

    override suspend fun passThrough(
        exchange: ServerWebExchange,
        routes: List<ModelRegistry.Route>,
        requestRoot: JsonNode,
        upstreamPath: String,
        recordUsage: Boolean,
        conversationKey: String?,
    ): ResponseEntity<Flux<DataBuffer>> {
        val stream = requestRoot.path("stream").asBoolean(false)
        val clientKey = exchange.getAttribute(ApiKeyAuthFilter.CLIENT_KEY_ATTRIBUTE) ?: "unknown"
        // кэш повторяющихся запросов: точный повтор отдаётся без похода к провайдеру
        val cacheKey =
            if (recordUsage) requestCacheService.buildCacheKey(upstreamPath, requestRoot) else null
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
        // заголовок авторизации один на запрос: oauth → Bearer-токен, иначе x-api-key
        val authorizationHeader = activeRoutes.firstOrNull()?.let { firstRoute ->
            resolveAuthorizationHeader(firstRoute.provider)
        }
        return if (stream) {
            ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(
                    // attemptStream теперь suspend (сжатие tool_result оптимизатором M30) — мост mono
                    mono {
                        attemptStream(
                            exchange, activeRoutes, 0, requestRoot, upstreamPath,
                            recordUsage, clientKey, authorizationHeader, cacheKey, conversationKey,
                        )
                    }.flatMapMany { streamEvents -> streamEvents }
                        .doFinally {
                            // single-flight: первый проход завершился (записал ответ или нет)
                            if (cacheKey != null) requestCacheService.endFlight(cacheKey)
                        },
                )
        } else {
            try {
                attemptSequential(exchange, activeRoutes, requestRoot, upstreamPath, recordUsage, clientKey, authorizationHeader, cacheKey, conversationKey)
            } finally {
                if (cacheKey != null) requestCacheService.endFlight(cacheKey)
            }
        }
    }

    override suspend fun passThroughRequest(
        exchange: ServerWebExchange,
        route: ModelRegistry.Route,
        method: HttpMethod,
        upstreamPath: String,
        query: MultiValueMap<String, String>?,
        bodyBytes: ByteArray?,
    ): ResponseEntity<Flux<DataBuffer>> {
        val authorizationHeader = resolveAuthorizationHeader(route.provider)
        return buildPassthroughCall(
            exchange,
            route,
            method,
            upstreamPath,
            query,
            MediaType.APPLICATION_JSON,
            bodyBytes?.let { BodyInserters.fromValue(it) },
            authorizationHeader,
        ).awaitSingle()
    }

    override suspend fun passThroughStreamingBody(
        exchange: ServerWebExchange,
        route: ModelRegistry.Route,
        upstreamPath: String,
        contentType: MediaType?,
        body: Flux<DataBuffer>,
    ): ResponseEntity<Flux<DataBuffer>> {
        val authorizationHeader = resolveAuthorizationHeader(route.provider)
        return buildPassthroughCall(
            exchange,
            route,
            HttpMethod.POST,
            upstreamPath,
            query = null,
            contentType = contentType,
            bodyInserter = BodyInserters.fromDataBuffers(body),
            authorizationHeader = authorizationHeader,
        ).awaitSingle()
    }

    /** Запрос в один провайдер без retry: статус и тело upstream уходят клиенту как есть. */
    private suspend fun buildPassthroughCall(
        exchange: ServerWebExchange,
        route: ModelRegistry.Route,
        method: HttpMethod,
        upstreamPath: String,
        query: MultiValueMap<String, String>?,
        contentType: MediaType?,
        bodyInserter: BodyInserter<*, in ClientHttpRequest>?,
        authorizationHeader: Pair<String, String>,
    ): Mono<ResponseEntity<Flux<DataBuffer>>> {
        val provider = route.provider
        val uriBuilder = UriComponentsBuilder.fromUriString(provider.baseUrl.trimEnd('/') + upstreamPath)
        query?.forEach { (name, values) ->
            values.forEach { value -> uriBuilder.queryParam(name, value) }
        }
        val requestSpecification =
            webClientFactory.webClient(provider.proxyName).method(method).uri(uriBuilder.encode().build().toUri())
        if (contentType != null) {
            requestSpecification.contentType(contentType)
        }
        requestSpecification.header(authorizationHeader.first, authorizationHeader.second)
        requestSpecification.header(
            "anthropic-version",
            exchange.request.headers.getFirst("anthropic-version") ?: "2023-06-01",
        )
        exchange.request.headers.getFirst("anthropic-beta")?.let { requestSpecification.header("anthropic-beta", it) }
        provider.extraHeaders.forEach { (name, value) -> requestSpecification.header(name, value) }
        bodyInserter?.let { requestSpecification.body(it) }
        // retrieve + onStatus «всегда, без ошибки»: статус и тело upstream
        // проходят клиенту как есть; toEntityFlux держит соединение до
        // конца потока.
        return requestSpecification
            .retrieve()
            .onStatus({ true }) { Mono.empty() }
            .toEntityFlux(DataBuffer::class.java)
    }

    /** (имя-заголовка, значение): oauth → Authorization Bearer, иначе x-api-key. */
    private suspend fun resolveAuthorizationHeader(provider: ModelRegistry.ProviderInfo): Pair<String, String> =
        if (provider.authType == "oauth") {
            val accessToken = oauthTokenService.accessToken(provider) ?: ""
            HttpHeaders.AUTHORIZATION to "Bearer $accessToken"
        } else {
            "x-api-key" to provider.apiKey
        }

    private suspend fun attemptSequential(
        exchange: ServerWebExchange,
        routes: List<ModelRegistry.Route>,
        requestRoot: JsonNode,
        upstreamPath: String,
        recordUsage: Boolean,
        clientKey: String,
        authorizationHeader: Pair<String, String>?,
        cacheKey: RequestCacheService.RequestCacheKey?,
        conversationKey: String?,
    ): ResponseEntity<Flux<DataBuffer>> {
        for ((index, route) in routes.withIndex()) {
            val startedAtMilliseconds = System.currentTimeMillis()
            try {
                val (callSpecification, savedTokens) =
                    buildCall(exchange, route, requestRoot, upstreamPath, authorizationHeader)
                val responseEntity = callSpecification
                    .retrieve()
                    .onStatus({ !it.is2xxSuccessful }) { response ->
                        response.toEntity(String::class.java).map { UpstreamError.from(it) }
                    }
                    .toEntity(String::class.java)
                    .awaitSingle()
                // не-стриминговый путь: ttft = upstream = момент получения ответа
                val responseAtMilliseconds = System.currentTimeMillis()
                if (recordUsage) {
                    val usageAccumulator = UsageAccumulator()
                    runCatching {
                        usageAccumulator.applyUsage(
                            objectMapper.readTree(responseEntity.body ?: "").path("usage"),
                        )
                    }
                    usageRecorder.recordAsync(
                        usageEvent(
                            clientKey = clientKey,
                            route = route,
                            stream = false,
                            usageAccumulator = usageAccumulator,
                            startedAtMilliseconds = startedAtMilliseconds,
                            status = responseEntity.statusCode.value(),
                            firstChunkAtMilliseconds = responseAtMilliseconds,
                            upstreamEndedAtMilliseconds = responseAtMilliseconds,
                            error = if (!responseEntity.statusCode.is2xxSuccessful()) {
                                "HTTP ${responseEntity.statusCode.value()}: " +
                                    "${(responseEntity.body ?: "").take(300)}"
                            } else {
                                null
                            },
                            errorDetail = if (!responseEntity.statusCode.is2xxSuccessful()) {
                                ProxyErrorDetails.ofBody(responseEntity.body)
                            } else {
                                null
                            },
                            savedTokens = savedTokens,
                        ),
                    )
                        // первый проход успешен — сохраняем ответ в кэш повторов
                        if (cacheKey != null && responseEntity.statusCode.is2xxSuccessful()) {
                            requestCacheService.storeAsync(
                                RequestCacheService.CachedEntry(
                                    cacheKey = cacheKey,
                                    responseBody = responseEntity.body ?: "",
                                    responseFormat = RequestCacheService.ResponseFormat.JSON,
                                    model = route.mapping.publicName,
                                    provider = route.provider.name,
                                    inputTokens = usageAccumulator.inputTokens,
                                    outputTokens = usageAccumulator.outputTokens,
                                    timeToLiveMilliseconds = RequestCacheService.timeToLiveMilliseconds(route.provider),
                                ),
                            )
                        }
                    }
                if (responseEntity.statusCode.is2xxSuccessful()) {
                    routeCircuitBreaker.success(route.provider.name)
                    // sticky-аффинность: успешный ход привязывает разговор к провайдеру
                    // (count_tokens сюда не доходит — он идёт с recordUsage = false)
                    if (recordUsage) {
                        conversationAffinityService.bind(
                            requestRoot.path("model").asText(""), conversationKey, route.provider.name,
                        )
                    }
                }
                return buildClientResponse(exchange, responseEntity)
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

    private suspend fun attemptStream(
        exchange: ServerWebExchange,
        routes: List<ModelRegistry.Route>,
        index: Int,
        requestRoot: JsonNode,
        upstreamPath: String,
        recordUsage: Boolean,
        clientKey: String,
        authorizationHeader: Pair<String, String>?,
        cacheKey: RequestCacheService.RequestCacheKey?,
        conversationKey: String?,
    ): Flux<DataBuffer> {
        val route = routes[index]
        val startedAtMilliseconds = System.currentTimeMillis()
        val usageSniffer = SseUsageSniffer(objectMapper)
        var emittedAnything = false
        // Момент первого чанка от провайдера (0 — чанков не было) для ttft.
        var firstChunkAtMilliseconds: Long = 0
        // Накопление полного SSE-транскрипта для кэша повторов (null — не кэшируем).
        val transcript = StringBuilder()
        val (callSpecification, savedTokens) =
            buildCall(exchange, route, requestRoot, upstreamPath, authorizationHeader)
        return callSpecification
            .retrieve()
            .onStatus({ !it.is2xxSuccessful }) { response ->
                response.toEntity(String::class.java).map { UpstreamError.from(it) }
            }
            .bodyToFlux(DataBuffer::class.java)
            .doOnNext { buffer ->
                emittedAnything = true
                if (firstChunkAtMilliseconds == 0L) {
                    firstChunkAtMilliseconds = System.currentTimeMillis()
                }
                // peek без потребления: readPosition не двигается
                val chunkText = buffer.toString(buffer.readPosition(), buffer.readableByteCount(), UTF_8)
                usageSniffer.onChunk(chunkText)
                if (cacheKey != null) transcript.append(chunkText)
            }
            .doOnError { error ->
                if (recordUsage) {
                    val failedAtMilliseconds = System.currentTimeMillis()
                    usageRecorder.recordAsync(
                        usageEvent(
                            clientKey = clientKey,
                            route = route,
                            stream = true,
                            usageAccumulator = usageSniffer.usageAccumulator,
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
                    // sticky-аффинность: успешный (в т.ч. отменённый клиентом) стрим
                    // привязывает разговор к провайдеру
                    if (recordUsage) {
                        conversationAffinityService.bind(
                            requestRoot.path("model").asText(""), conversationKey, route.provider.name,
                        )
                    }
                }
                if (recordUsage && signal != SignalType.ON_ERROR) {
                    val upstreamEndedAtMilliseconds = System.currentTimeMillis()
                    usageRecorder.recordAsync(
                        usageEvent(
                            clientKey = clientKey,
                            route = route,
                            stream = true,
                            usageAccumulator = usageSniffer.usageAccumulator,
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
                            responseBody = transcript.toString(),
                            responseFormat = RequestCacheService.ResponseFormat.SSE,
                            model = route.mapping.publicName,
                            provider = route.provider.name,
                            inputTokens = usageSniffer.usageAccumulator.inputTokens,
                            outputTokens = usageSniffer.usageAccumulator.outputTokens,
                            timeToLiveMilliseconds = RequestCacheService.timeToLiveMilliseconds(route.provider),
                        ),
                    )
                }
            }
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
                    mono {
                        attemptStream(
                            exchange, routes, index + 1, requestRoot, upstreamPath,
                            recordUsage, clientKey, authorizationHeader, cacheKey, conversationKey,
                        )
                    }.flatMapMany { streamEvents -> streamEvents }
                } else {
                    logger.error(error) { "Stream from provider '${route.provider.name}' aborted" }
                    Flux.just(
                        exchange.response.bufferFactory()
                            .wrap(serverSentEventErrorBytes(error.message)),
                    )
                }
            }
    }

    private suspend fun buildCall(
        exchange: ServerWebExchange,
        route: ModelRegistry.Route,
        requestRoot: JsonNode,
        upstreamPath: String,
        authorizationHeader: Pair<String, String>?,
    ): Pair<WebClient.RequestHeadersSpec<*>, Long> {
        val provider = route.provider
        val rewrittenRequest = (requestRoot as ObjectNode).deepCopy()
            .put("model", route.mapping.upstreamName)
        if (provider.settingOverrides[CONVERT_SYSTEM_MESSAGES_TO_USER] == "true") {
            normalizeMidConversationSystemMessages(rewrittenRequest)
        }
        requestAdjuster.adjust(rewrittenRequest, provider)
        val savedTokens = tokenSavingAdjuster.adjust(rewrittenRequest, provider)
        val requestSpecification = webClientFactory.webClient(provider.proxyName).post()
            .uri(provider.baseUrl.trimEnd('/') + upstreamPath)
            .contentType(MediaType.APPLICATION_JSON)
        if (authorizationHeader != null) {
            requestSpecification.header(authorizationHeader.first, authorizationHeader.second)
        }
        requestSpecification.header(
            "anthropic-version",
            exchange.request.headers.getFirst("anthropic-version") ?: "2023-06-01",
        )
        exchange.request.headers.getFirst("anthropic-beta")
            ?.let { requestSpecification.header("anthropic-beta", it) }
        provider.extraHeaders.forEach { (name, value) -> requestSpecification.header(name, value) }
        return requestSpecification.bodyValue(objectMapper.writeValueAsBytes(rewrittenRequest)) to savedTokens
    }

    /**
     * Роль system внутри messages невалидна по спецификации Anthropic, но клиенты
     * так шлют служебные напоминания (бюджет токенов и т.п.); локальные движки
     * (LM Studio/Qwen) на этом падают («System message must be at the beginning») —
     * конвертируем такие сообщения в user-роль, содержимое не меняется.
     */
    private fun normalizeMidConversationSystemMessages(requestRoot: ObjectNode) {
        val messages = requestRoot.path("messages")
        if (!messages.isArray) return
        var normalizedCount = 0
        for (message in messages) {
            if (message is ObjectNode && message.path("role").asText() == "system") {
                message.put("role", "user")
                normalizedCount++
            }
        }
        if (normalizedCount > 0) {
            logger.debug { "normalized $normalizedCount mid-conversation system messages to user role" }
        }
    }

    private companion object {
        /** Оверрайд провайдера: конвертировать role=system внутри messages в user. */
        const val CONVERT_SYSTEM_MESSAGES_TO_USER = "CONVERT_SYSTEM_MESSAGES_TO_USER"
    }

    private fun buildClientResponse(
        exchange: ServerWebExchange,
        responseEntity: org.springframework.http.ResponseEntity<String>,
    ): ResponseEntity<Flux<DataBuffer>> {
        val responseBuilder = ResponseEntity.status(responseEntity.statusCode)
            .contentType(responseEntity.headers.contentType ?: MediaType.APPLICATION_JSON)
        responseEntity.headers.getFirst(HttpHeaders.RETRY_AFTER)
            ?.let { responseBuilder.header(HttpHeaders.RETRY_AFTER, it) }
        return responseBuilder.body(
            Flux.just(
                exchange.response.bufferFactory()
                    .wrap(responseEntity.body?.toByteArray() ?: ByteArray(0)),
            ),
        )
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
