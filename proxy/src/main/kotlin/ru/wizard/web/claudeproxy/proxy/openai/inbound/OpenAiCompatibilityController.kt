package ru.wizard.web.claudeproxy.proxy.openai.inbound

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.wizard.web.claudeproxy.proxy.AnthropicHandler
import ru.wizard.web.claudeproxy.proxy.ApiError
import ru.wizard.web.claudeproxy.proxy.openai.OpenAiHandler
import ru.wizard.web.claudeproxy.proxy.openai.inbound.impl.ClaudeSseToOpenAiSseTranslator
import ru.wizard.web.claudeproxy.proxy.openai.inbound.impl.ClaudeToOpenAiResponseTranslator
import ru.wizard.web.claudeproxy.proxy.openai.inbound.impl.OpenAiInboundRequestTranslator
import ru.wizard.web.claudeproxy.routing.ModelRegistry
import java.nio.charset.StandardCharsets.UTF_8

/**
 * Inbound-поддержка протокола OpenAI: POST /v1/chat/completions для клиентов
 * с OpenAI SDK. Запрос переводится OpenAI → Claude, маршрутизируется существующими
 * хендлерами (fallback по приоритетам, usage-учёт), ответ переводится обратно
 * Claude → OpenAI (нестрим и SSE с "data: [DONE]").
 */
@RestController
class OpenAiCompatibilityController(
    private val keyQuotaService: ru.wizard.web.claudeproxy.auth.KeyQuotaService,
    private val modelRegistry: ModelRegistry,
    private val objectMapper: ObjectMapper,
    private val anthropicHandler: AnthropicHandler,
    private val openAiHandler: OpenAiHandler,
    private val conversationAffinityService: ru.wizard.web.claudeproxy.routing.ConversationAffinityService,
) {
    private val requestTranslator = OpenAiInboundRequestTranslator(objectMapper)
    private val responseTranslator = ClaudeToOpenAiResponseTranslator(objectMapper)

    /** Входящая OpenAI-совместимость: chat/completions на маршрутах Claude. */
    @PostMapping("/v1/chat/completions", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun chatCompletions(
        @RequestBody requestBody: String,
        exchange: ServerWebExchange,
    ): Mono<ResponseEntity<Flux<DataBuffer>>> = mono {
        val openAiRoot: JsonNode = try {
            objectMapper.readTree(requestBody)
        } catch (exception: Exception) {
            throw ApiError(
                HttpStatus.BAD_REQUEST,
                "invalid_request_error",
                "Could not parse request body: ${exception.message}",
            )
        }
        val model = openAiRoot.path("model").asText("")
        if (model.isEmpty()) {
            throw ApiError(HttpStatus.BAD_REQUEST, "invalid_request_error", "model: Field required")
        }
        val claudeRoot = requestTranslator.translate(openAiRoot)
        keyQuotaService.enforce(exchange, model)
        // ключ разговора sticky-аффинности: считается по Claude-форме (после перевода),
        // одна функция с /v1/messages — растущий хвост разговора ключ не меняет
        val conversationKey = conversationAffinityService.conversationKey(claudeRoot)
        val routes = modelRegistry.find(model, conversationKey = conversationKey)
        if (routes.isEmpty()) {
            throw ApiError(
                HttpStatus.NOT_FOUND,
                "invalid_request_error",
                "The model '$model' does not exist",
            )
        }
        val claudeEntity = when (routes.first().provider.type) {
            "anthropic" -> anthropicHandler.passThrough(
                exchange,
                routes,
                claudeRoot,
                "/v1/messages",
                recordUsage = true,
                conversationKey = conversationKey,
            )

            "openai" -> openAiHandler.chatCompletion(
                exchange, routes, claudeRoot, recordUsage = true, conversationKey = conversationKey,
            )

            else -> throw ApiError(
                HttpStatus.NOT_IMPLEMENTED,
                "invalid_request_error",
                "Unsupported provider type '${routes.first().provider.type}'",
            )
        }
        wrapAsOpenAi(claudeEntity, exchange, model)
    }

    private suspend fun wrapAsOpenAi(
        claudeEntity: ResponseEntity<Flux<DataBuffer>>,
        exchange: ServerWebExchange,
        model: String,
    ): ResponseEntity<Flux<DataBuffer>> {
        val isStream =
            claudeEntity.headers.contentType?.isCompatibleWith(MediaType.TEXT_EVENT_STREAM) == true
        return if (isStream) {
            val sseTranslator = ClaudeSseToOpenAiSseTranslator(objectMapper, model)
            ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(
                    claudeEntity.body!!
                        .concatMap { buffer ->
                            val chunkText = buffer.toString(
                                buffer.readPosition(),
                                buffer.readableByteCount(),
                                UTF_8,
                            )
                            DataBufferUtils.release(buffer)
                            Flux.fromIterable(sseTranslator.onChunk(chunkText))
                        }
                        .map { eventText ->
                            exchange.response.bufferFactory().wrap(eventText.toByteArray(UTF_8))
                        },
                )
        } else {
            val buffers = claudeEntity.body?.collectList()?.awaitSingle() ?: emptyList()
            val claudeBody = buffers.joinToString("") {
                it.toString(it.readPosition(), it.readableByteCount(), UTF_8)
            }
            buffers.forEach(DataBufferUtils::release)
            val openAiResponse = responseTranslator.translate(objectMapper.readTree(claudeBody), model)
            val responseBuilder = ResponseEntity.status(claudeEntity.statusCode)
                .contentType(MediaType.APPLICATION_JSON)
            claudeEntity.headers.getFirst(HttpHeaders.RETRY_AFTER)
                ?.let { responseBuilder.header(HttpHeaders.RETRY_AFTER, it) }
            responseBuilder.body(
                Flux.just(
                    exchange.response.bufferFactory()
                        .wrap(objectMapper.writeValueAsBytes(openAiResponse)),
                ),
            )
        }
    }
}
