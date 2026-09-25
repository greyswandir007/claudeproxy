package ru.wizard.web.claudeproxy.proxy.impl

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.SignalType
import ru.wizard.web.claudeproxy.auth.ApiKeyAuthFilter
import ru.wizard.web.claudeproxy.proxy.AnthropicHandler
import ru.wizard.web.claudeproxy.proxy.ProviderRequestAdjuster
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
    private val webClient: WebClient,
    private val objectMapper: ObjectMapper,
    private val usageRecorder: UsageRecorder,
    private val requestAdjuster: ProviderRequestAdjuster,
) : AnthropicHandler {
    private val logger = KotlinLogging.logger {}

    override suspend fun passThrough(
        exchange: ServerWebExchange,
        routes: List<ModelRegistry.Route>,
        requestRoot: JsonNode,
        upstreamPath: String,
        recordUsage: Boolean,
    ): ResponseEntity<Flux<DataBuffer>> {
        val stream = requestRoot.path("stream").asBoolean(false)
        val clientKey = exchange.getAttribute(ApiKeyAuthFilter.CLIENT_KEY_ATTRIBUTE) ?: "unknown"
        return if (stream) {
            ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(
                    Flux.defer {
                        attemptStream(exchange, routes, 0, requestRoot, upstreamPath, recordUsage, clientKey)
                    },
                )
        } else {
            attemptSequential(exchange, routes, requestRoot, upstreamPath, recordUsage, clientKey)
        }
    }

    private suspend fun attemptSequential(
        exchange: ServerWebExchange,
        routes: List<ModelRegistry.Route>,
        requestRoot: JsonNode,
        upstreamPath: String,
        recordUsage: Boolean,
        clientKey: String,
    ): ResponseEntity<Flux<DataBuffer>> {
        for ((index, route) in routes.withIndex()) {
            val startedAtMilliseconds = System.currentTimeMillis()
            try {
                val responseEntity = buildCall(exchange, route, requestRoot, upstreamPath)
                    .retrieve()
                    .onStatus({ !it.is2xxSuccessful }) { response ->
                        response.toEntity(String::class.java).map { UpstreamError.from(it) }
                    }
                    .toEntity(String::class.java)
                    .awaitSingle()
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
                            error = if (!responseEntity.statusCode.is2xxSuccessful()) {
                                "HTTP ${responseEntity.statusCode.value()}: " +
                                    "${(responseEntity.body ?: "").take(300)}"
                            } else {
                                null
                            },
                        ),
                    )
                }
                return buildClientResponse(exchange, responseEntity)
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
        upstreamPath: String,
        recordUsage: Boolean,
        clientKey: String,
    ): Flux<DataBuffer> {
        val route = routes[index]
        val startedAtMilliseconds = System.currentTimeMillis()
        val usageSniffer = SseUsageSniffer(objectMapper)
        var emittedAnything = false
        return buildCall(exchange, route, requestRoot, upstreamPath)
            .retrieve()
            .onStatus({ !it.is2xxSuccessful }) { response ->
                response.toEntity(String::class.java).map { UpstreamError.from(it) }
            }
            .bodyToFlux(DataBuffer::class.java)
            .doOnNext { buffer ->
                emittedAnything = true
                // peek без потребления: readPosition не двигается
                usageSniffer.onChunk(
                    buffer.toString(buffer.readPosition(), buffer.readableByteCount(), UTF_8),
                )
            }
            .doOnError { error ->
                if (recordUsage) {
                    usageRecorder.recordAsync(
                        usageEvent(
                            clientKey = clientKey,
                            route = route,
                            stream = true,
                            usageAccumulator = usageSniffer.usageAccumulator,
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
                            usageAccumulator = usageSniffer.usageAccumulator,
                            startedAtMilliseconds = startedAtMilliseconds,
                            status = signalStatus(signal),
                            error = null,
                        ),
                    )
                }
            }
            .onErrorResume { error ->
                val canFallback = UpstreamRetryPolicy.isRetryable(error) &&
                    !emittedAnything &&
                    index < routes.lastIndex
                if (canFallback) {
                    logger.warn(error) {
                        "Route '${route.provider.name}/${route.mapping.upstreamName}' failed before " +
                            "first event (${shortError(error)}) - switching to next route"
                    }
                    attemptStream(exchange, routes, index + 1, requestRoot, upstreamPath, recordUsage, clientKey)
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
        exchange: ServerWebExchange,
        route: ModelRegistry.Route,
        requestRoot: JsonNode,
        upstreamPath: String,
    ): WebClient.RequestHeadersSpec<*> {
        val provider = route.provider
        val rewrittenRequest = (requestRoot as ObjectNode).deepCopy()
            .put("model", route.mapping.upstreamName)
        requestAdjuster.adjust(rewrittenRequest, provider)
        val requestSpecification = webClient.post()
            .uri(provider.baseUrl.trimEnd('/') + upstreamPath)
            .contentType(MediaType.APPLICATION_JSON)
            .header("x-api-key", provider.apiKey)
            .header(
                "anthropic-version",
                exchange.request.headers.getFirst("anthropic-version") ?: "2023-06-01",
            )
        exchange.request.headers.getFirst("anthropic-beta")
            ?.let { requestSpecification.header("anthropic-beta", it) }
        provider.extraHeaders.forEach { (name, value) -> requestSpecification.header(name, value) }
        return requestSpecification.bodyValue(objectMapper.writeValueAsBytes(rewrittenRequest))
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
