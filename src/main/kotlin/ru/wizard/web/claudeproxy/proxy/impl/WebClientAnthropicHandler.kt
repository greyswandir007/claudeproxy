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
import ru.wizard.web.claudeproxy.proxy.SseUsageSniffer
import ru.wizard.web.claudeproxy.proxy.UpstreamError
import ru.wizard.web.claudeproxy.proxy.UsageAccumulator
import ru.wizard.web.claudeproxy.routing.ModelRegistry
import ru.wizard.web.claudeproxy.usage.UsageEvent
import ru.wizard.web.claudeproxy.usage.UsageRecorder
import java.nio.charset.StandardCharsets.UTF_8

/**
 * Реализация AnthropicHandler на WebClient (Reactor Netty).
 */
@Service
class WebClientAnthropicHandler(
    private val webClient: WebClient,
    private val objectMapper: ObjectMapper,
    private val usageRecorder: UsageRecorder,
) : AnthropicHandler {
    private val logger = KotlinLogging.logger {}

    override suspend fun passThrough(
        exchange: ServerWebExchange,
        route: ModelRegistry.Route,
        requestRoot: JsonNode,
        upstreamPath: String,
        recordUsage: Boolean,
    ): ResponseEntity<Flux<DataBuffer>> {
        val provider = route.provider
        val rewrittenRequest = (requestRoot as ObjectNode).deepCopy()
            .put("model", route.mapping.upstream)
        val requestBody = objectMapper.writeValueAsBytes(rewrittenRequest)
        val stream = requestRoot.path("stream").asBoolean(false)
        val clientKey = exchange.getAttribute(ApiKeyAuthFilter.CLIENT_KEY_ATTRIBUTE) ?: "unknown"
        val startedAtMilliseconds = System.currentTimeMillis()

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
        val responseSpecification = requestSpecification.bodyValue(requestBody).retrieve()

        return if (stream) {
            val usageSniffer = SseUsageSniffer(objectMapper)
            val responseFlux = responseSpecification
                .onStatus({ !it.is2xxSuccessful }) { response ->
                    response.toEntity(String::class.java).map { UpstreamError.from(it) }
                }
                .bodyToFlux(DataBuffer::class.java)
                .doOnNext { buffer ->
                    // peek без потребления: readPosition не двигается
                    usageSniffer.onChunk(
                        buffer.toString(buffer.readPosition(), buffer.readableByteCount(), UTF_8),
                    )
                }
                .doFinally { signal ->
                    if (recordUsage) {
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
                .onErrorResume { exception ->
                    logger.error(exception) { "Обрыв стрима от провайдера '${provider.name}'" }
                    Flux.just(
                        exchange.response.bufferFactory()
                            .wrap(serverSentEventErrorBytes(exception.message)),
                    )
                }
            ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(responseFlux)
        } else {
            val responseEntity = responseSpecification
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
                        error = if (!responseEntity.statusCode.is2xxSuccessful) {
                            "HTTP ${responseEntity.statusCode.value()}: " +
                                "${(responseEntity.body ?: "").take(300)}"
                        } else {
                            null
                        },
                    ),
                )
            }
            val responseBuilder = ResponseEntity.status(responseEntity.statusCode)
                .contentType(responseEntity.headers.contentType ?: MediaType.APPLICATION_JSON)
            responseEntity.headers.getFirst(HttpHeaders.RETRY_AFTER)
                ?.let { responseBuilder.header(HttpHeaders.RETRY_AFTER, it) }
            responseBuilder.body(
                Flux.just(
                    exchange.response.bufferFactory()
                        .wrap(responseEntity.body?.toByteArray() ?: ByteArray(0)),
                ),
            )
        }
    }

    private fun signalStatus(signal: SignalType): Int = when (signal) {
        SignalType.ON_COMPLETE -> 200
        SignalType.CANCEL -> 0
        else -> 500
    }

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
        model = route.mapping.`public`,
        upstreamModel = route.mapping.upstream,
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
