package ru.wizard.web.claudeproxy.proxy.openai.impl

import com.fasterxml.jackson.databind.JsonNode
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
import ru.wizard.web.claudeproxy.proxy.openai.OpenAiHandler
import ru.wizard.web.claudeproxy.routing.ModelRegistry
import ru.wizard.web.claudeproxy.usage.UsageEvent
import ru.wizard.web.claudeproxy.usage.UsageRecorder
import java.nio.charset.StandardCharsets.UTF_8

/**
 * Реализация OpenAiHandler на WebClient: перевод запроса → Chat Completions →
 * перевод ответа/SSE обратно в протокол Claude, с записью usage.
 */
@Service
class WebClientOpenAiHandler(
    private val webClient: WebClient,
    private val objectMapper: ObjectMapper,
    private val usageRecorder: UsageRecorder,
) : OpenAiHandler {
    private val logger = KotlinLogging.logger {}

    private val requestTranslator = OpenAiRequestTranslator(objectMapper)
    private val responseTranslator = OpenAiResponseTranslator(objectMapper)
    private val errorTranslator = OpenAiErrorTranslator(objectMapper)
    private val tokenCountEstimator = OpenAiTokenCountEstimator()

    private val serverSentEventTypeReference = object : ParameterizedTypeReference<ServerSentEvent<String>>() {}

    override suspend fun chatCompletion(
        exchange: ServerWebExchange,
        route: ModelRegistry.Route,
        requestRoot: JsonNode,
        recordUsage: Boolean,
    ): ResponseEntity<Flux<DataBuffer>> {
        val provider = route.provider
        val translatedRequest = requestTranslator.translate(requestRoot, route)
        val requestBody = objectMapper.writeValueAsBytes(translatedRequest)
        val stream = requestRoot.path("stream").asBoolean(false)
        val clientKey = exchange.getAttribute(ApiKeyAuthFilter.CLIENT_KEY_ATTRIBUTE) ?: "unknown"
        val startedAtMilliseconds = System.currentTimeMillis()

        val requestSpecification = webClient.post()
            .uri(provider.baseUrl.trimEnd('/') + "/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${provider.apiKey}")
        provider.extraHeaders.forEach { (name, value) -> requestSpecification.header(name, value) }
        val responseSpecification = requestSpecification.bodyValue(requestBody).retrieve()

        return if (stream) {
            val sseTranslator = OpenAiSseTranslator(objectMapper, route.mapping.`public`)
            val responseFlux = responseSpecification
                .onStatus({ !it.is2xxSuccessful }) { response ->
                    response.toEntity(String::class.java).map { errorTranslator.translate(it) }
                }
                .bodyToFlux(serverSentEventTypeReference)
                .concatMap { serverSentEvent ->
                    Flux.fromIterable(sseTranslator.onData(serverSentEvent.data()))
                }
                .doFinally { signal ->
                    if (recordUsage) {
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
                .map { eventText -> exchange.response.bufferFactory().wrap(eventText.toByteArray(UTF_8)) }
                .onErrorResume { exception ->
                    logger.error(exception) { "Обрыв стрима от провайдера '${provider.name}'" }
                    Flux.just(exchange.response.bufferFactory().wrap(serverSentEventErrorBytes(exception.message)))
                }
            ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(responseFlux)
        } else {
            val responseEntity = try {
                responseSpecification
                    .onStatus({ !it.is2xxSuccessful }) { response ->
                        response.toEntity(String::class.java).map { errorTranslator.translate(it) }
                    }
                    .toEntity(String::class.java)
                    .awaitSingle()
            } catch (upstreamError: UpstreamError) {
                if (recordUsage) {
                    usageRecorder.recordAsync(
                        usageEvent(
                            clientKey = clientKey,
                            route = route,
                            stream = false,
                            usageAccumulator = UsageAccumulator(),
                            startedAtMilliseconds = startedAtMilliseconds,
                            status = upstreamError.status.value(),
                            error = "HTTP ${upstreamError.status.value()}: " +
                                "${upstreamError.upstreamBody.take(300)}",
                        ),
                    )
                }
                throw upstreamError
            }
            val translated = responseTranslator.translate(responseEntity.body, route.mapping.`public`)
            if (recordUsage) {
                usageRecorder.recordAsync(
                    usageEvent(
                        clientKey = clientKey,
                        route = route,
                        stream = false,
                        usageAccumulator = translated.usageAccumulator,
                        startedAtMilliseconds = startedAtMilliseconds,
                        status = responseEntity.statusCode.value(),
                        error = if (!responseEntity.statusCode.is2xxSuccessful) {
                            "HTTP ${responseEntity.statusCode.value()}"
                        } else {
                            null
                        },
                    ),
                )
            }
            ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                    Flux.just(
                        exchange.response.bufferFactory().wrap(translated.responseBody.toByteArray(UTF_8)),
                    ),
                )
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
