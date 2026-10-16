package ru.wizard.web.claudeproxy.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.mono
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.wizard.web.claudeproxy.auth.ApiKeyAuthFilter
import ru.wizard.web.claudeproxy.chat.ChatHistoryService
import ru.wizard.web.claudeproxy.chat.impl.ClaudeSseToChatStreamTranslator
import ru.wizard.web.claudeproxy.proxy.AnthropicHandler
import ru.wizard.web.claudeproxy.proxy.ApiError
import ru.wizard.web.claudeproxy.proxy.openai.OpenAiHandler
import ru.wizard.web.claudeproxy.routing.ModelRegistry
import java.nio.charset.StandardCharsets.UTF_8

/**
 * Веб-чат дашборда: история на клиентский ключ, отправка через собственные
 * хендлеры (usage пишется на выбранный ключ), ответ — NDJSON-стрим в браузер.
 */
@RestController
@RequestMapping("/api/chat")
class ChatController(
    private val chatHistory: ChatHistoryService,
    private val modelRegistry: ModelRegistry,
    private val anthropicHandler: AnthropicHandler,
    private val openAiHandler: OpenAiHandler,
    private val objectMapper: ObjectMapper,
) {
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Состояние чата ключа: тред + история. */
    @GetMapping("/state")
    suspend fun state(@RequestParam(name = "key") clientKey: String): Map<String, Any?> = mapOf(
        "thread" to chatHistory.thread(clientKey),
        "messages" to chatHistory.messages(clientKey),
    )

    /** Переименование треда чата. */
    @PutMapping("/thread")
    suspend fun renameThread(
        @RequestParam(name = "key") clientKey: String,
        @RequestBody requestBody: String,
    ): Map<String, Any?> {
        val title = runCatching { objectMapper.readTree(requestBody).path("title").asText("") }
            .getOrDefault("")
        if (title.isBlank()) {
            throw ApiError(HttpStatus.BAD_REQUEST, "invalid_request_error", "title: Field required")
        }
        chatHistory.renameThread(clientKey, title.trim())
        return mapOf("title" to title.trim())
    }

    /** Очистка истории чата ключа. */
    @DeleteMapping("/messages")
    suspend fun clear(@RequestParam(name = "key") clientKey: String): Map<String, Boolean> {
        chatHistory.clear(clientKey)
        return mapOf("cleared" to true)
    }

    /**
     * Отправка сообщения: user сохраняется, запрос идёт через обычную маршрутизацию
     * (fallback/usage), Claude-SSE переводится в NDJSON-стрим; текст ассистента
     * сохраняется в историю по завершении.
     */
    @PostMapping("/send", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun send(
        @RequestParam(name = "key") clientKey: String,
        @RequestParam model: String,
        @RequestBody requestBody: String,
        exchange: ServerWebExchange,
    ): Mono<ResponseEntity<Flux<DataBuffer>>> = mono {
        val content = runCatching {
            objectMapper.readTree(requestBody).path("content").asText("")
        }.getOrDefault("")
        if (content.isBlank()) {
            throw ApiError(HttpStatus.BAD_REQUEST, "invalid_request_error", "content: Field required")
        }
        val routes = modelRegistry.find(model)
        if (routes.isEmpty()) {
            throw ApiError(HttpStatus.NOT_FOUND, "invalid_request_error", "The model '$model' does not exist")
        }
        chatHistory.append(clientKey, "user", content)
        // авто-заголовок треда из первого сообщения
        val threadInfo = chatHistory.thread(clientKey)
        if (threadInfo.title.isBlank()) {
            chatHistory.renameThread(clientKey, content.take(60).trim())
        }

        // usage чата атрибутируется выбранному ключу
        exchange.attributes[ApiKeyAuthFilter.CLIENT_KEY_ATTRIBUTE] = clientKey

        val requestRoot: ObjectNode = objectMapper.createObjectNode()
            .put("model", model)
            .put("max_tokens", 8192)
            .put("stream", true)
        requestRoot.set<ObjectNode>(
            "messages",
            objectMapper.valueToTree(toClaudeMessages(chatHistory.messages(clientKey))),
        )

        val claudeEntity = when (routes.first().provider.type) {
            "anthropic" -> anthropicHandler.passThrough(
                exchange, routes, requestRoot, "/v1/messages", recordUsage = true,
            )

            "openai" -> openAiHandler.chatCompletion(exchange, routes, requestRoot, recordUsage = true)

            else -> throw ApiError(
                HttpStatus.NOT_IMPLEMENTED,
                "invalid_request_error",
                "Unsupported provider type '${routes.first().provider.type}'",
            )
        }

        val translator = ClaudeSseToChatStreamTranslator(objectMapper)
        val ndjsonFlux = claudeEntity.body!!
            .concatMap { buffer ->
                val chunkText = buffer.toString(buffer.readPosition(), buffer.readableByteCount(), UTF_8)
                DataBufferUtils.release(buffer)
                Flux.fromIterable(translator.onChunk(chunkText))
            }
            .map { line ->
                exchange.response.bufferFactory().wrap((line + "\n").toByteArray(UTF_8))
            }
            .onErrorResume { error ->
                Flux.just(
                    exchange.response.bufferFactory()
                        .wrap((translator.errorMessage(error.message) + "\n").toByteArray(UTF_8)),
                )
            }
            .doFinally {
                saveScope.launch {
                    if (translator.assistantText.isNotEmpty()) {
                        runCatching {
                            chatHistory.append(clientKey, "assistant", translator.assistantText.toString())
                        }
                    }
                }
            }
        ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/x-ndjson"))
            .header("X-Accel-Buffering", "no")
            .body(ndjsonFlux)
    }

    /** История → сообщения Claude: подряд идущие роли склеиваются (API требует чередование). */
    private fun toClaudeMessages(history: List<ChatHistoryService.ChatMessage>): List<Map<String, String>> {
        val merged = ArrayList<Map<String, String>>()
        for (message in history) {
            val last = merged.lastOrNull()
            if (last != null && last["role"] == message.role) {
                merged[merged.size - 1] = mapOf(
                    "role" to message.role,
                    "content" to last["content"] + "\n\n" + message.content,
                )
            } else {
                merged.add(mapOf("role" to message.role, "content" to message.content))
            }
        }
        return merged
    }
}
