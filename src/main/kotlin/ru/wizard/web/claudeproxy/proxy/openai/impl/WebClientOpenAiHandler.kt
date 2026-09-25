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
import ru.wizard.web.claudeproxy.providers.ProviderOAuthTokenService
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
    private val oauthTokenService: ProviderOAuthTokenService,
) : OpenAiHandler {
    private val logger = KotlinLogging.logger {}

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
        // токен один на запрос: oauth-провайдеры получают его из token-сервиса
        val bearerToken = routes.firstOrNull()?.let { firstRoute ->
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
                        attemptStream(exchange, routes, 0, requestRoot, recordUsage, clientKey, bearerToken)
                    },
                )
        } else {
            attemptSequential(exchange, routes, requestRoot, recordUsage, clientKey, bearerToken)
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
    ): ResponseEntity<Flux<DataBuffer>> {
        for ((index, route) in routes.withIndex()) {
            val startedAtMilliseconds = System.currentTimeMillis()
            try {
                val responseEntity = buildCall(route, requestRoot, bearerToken)
                    .retrieve()
                    .onStatus({ !it.is2xxSuccessful }) { response ->
                        response.toEntity(String::class.java).map { errorTranslator.translate(it) }
                    }
                    .toEntity(String::class.java)
                    .awaitSingle()
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
                        ),
                    )
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
                    usageRecorder.recordAsync(
                        usageEvent(
                            clientKey = clientKey,
                            route = route,
                            stream = false,
                            usageAccumulator = UsageAccumulator(),
                            startedAtMilliseconds = startedAtMilliseconds,
                            status = errorStatus(error),
                            error = shortError(error),
                        ),
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
    ): Flux<DataBuffer> {
        val route = routes[index]
        val startedAtMilliseconds = System.currentTimeMillis()
        val sseTranslator = OpenAiSseTranslator(objectMapper, route.mapping.publicName)
        var emittedAnything = false
        return buildCall(route, requestRoot, bearerToken)
            .retrieve()
            .onStatus({ !it.is2xxSuccessful }) { response ->
                response.toEntity(String::class.java).map { errorTranslator.translate(it) }
            }
            .bodyToFlux(serverSentEventTypeReference)
            .concatMap { serverSentEvent ->
                val events = sseTranslator.onData(serverSentEvent.data())
                if (events.isNotEmpty()) {
                    emittedAnything = true
                }
                Flux.fromIterable(events)
            }
            .doOnError { error ->
                if (recordUsage) {
                    usageRecorder.recordAsync(
                        usageEvent(
                            clientKey = clientKey,
                            route = route,
                            stream = true,
                            usageAccumulator = sseTranslator.usageAccumulator,
                            startedAtMilliseconds = startedAtMilliseconds,
                            status = errorStatus(error),
                            error = shortError(error),
                        ),
                    )
                }
            }
            .doFinally { signal ->
                if (recordUsage && signal != SignalType.ON_ERROR) {
                    usageRecorder.recordAsync(
                        usageEvent(
                            clientKey = clientKey,
                            route = route,
                            stream = true,
                            usageAccumulator = sseTranslator.usageAccumulator,
                            startedAtMilliseconds = startedAtMilliseconds,
                            status = signalStatus(signal),
                            error = null,
                        ),
                    )
                }
            }
            .map { eventText -> eventText.toByteArray(UTF_8) }
            .map { eventBytes -> exchange.response.bufferFactory().wrap(eventBytes) }
            .onErrorResume { error ->
                val canFallback = UpstreamRetryPolicy.isRetryable(error) &&
                    !emittedAnything &&
                    index < routes.lastIndex
                if (canFallback) {
                    logger.warn(error) {
                        "Route '${route.provider.name}/${route.mapping.upstreamName}' failed before " +
                            "first event (${shortError(error)}) - switching to next route"
                    }
                    attemptStream(exchange, routes, index + 1, requestRoot, recordUsage, clientKey, bearerToken)
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
    ): WebClient.RequestHeadersSpec<*> {
        val provider = route.provider
        val adjustedRoot = requestAdjuster.adjust(requestRoot as ObjectNode, provider)
        val translatedRequest = requestTranslator.translate(adjustedRoot, route)
        val requestSpecification = webClient.post()
            .uri(provider.baseUrl.trimEnd('/') + "/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $bearerToken")
        provider.extraHeaders.forEach { (name, value) -> requestSpecification.header(name, value) }
        return requestSpecification.bodyValue(objectMapper.writeValueAsBytes(translatedRequest))
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
