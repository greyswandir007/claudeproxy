package ru.wizard.web.claudeproxy.proxy

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactor.mono
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.wizard.web.claudeproxy.proxy.openai.OpenAiHandler
import ru.wizard.web.claudeproxy.routing.ModelRegistry

/**
 * POST /v1/messages и /v1/messages/count_tokens: разбор модели → роутинг по провайдеру.
 */
@RestController
class MessagesController(
    private val modelRegistry: ModelRegistry,
    private val objectMapper: ObjectMapper,
    private val anthropicHandler: AnthropicHandler,
    private val openAiHandler: OpenAiHandler,
) {

    @PostMapping("/v1/messages", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun messages(
        @RequestBody requestBody: String,
        exchange: ServerWebExchange,
    ): Mono<ResponseEntity<Flux<DataBuffer>>> =
        handle(requestBody, exchange, "/v1/messages", recordUsage = true)

    @PostMapping("/v1/messages/count_tokens", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun countTokens(
        @RequestBody requestBody: String,
        exchange: ServerWebExchange,
    ): Mono<ResponseEntity<Flux<DataBuffer>>> =
        handle(requestBody, exchange, "/v1/messages/count_tokens", recordUsage = false)

    private fun handle(
        requestBody: String,
        exchange: ServerWebExchange,
        upstreamPath: String,
        recordUsage: Boolean,
    ): Mono<ResponseEntity<Flux<DataBuffer>>> = mono {
        val requestRoot: JsonNode = try {
            objectMapper.readTree(requestBody)
        } catch (exception: Exception) {
            throw ApiError(
                HttpStatus.BAD_REQUEST,
                "invalid_request_error",
                "Некорректное тело запроса: ${exception.message}",
            )
        }
        if (!requestRoot.isObject) {
            throw ApiError(
                HttpStatus.BAD_REQUEST,
                "invalid_request_error",
                "Тело запроса должно быть JSON-объектом",
            )
        }
        val model = requestRoot.path("model").asText("")
        if (model.isEmpty()) {
            throw ApiError(HttpStatus.BAD_REQUEST, "invalid_request_error", "model: Field required")
        }
        val route = modelRegistry.find(model)
            ?: throw ApiError(HttpStatus.NOT_FOUND, "not_found_error", "model: $model not found")
        when {
            route.provider.type == "anthropic" -> anthropicHandler.passThrough(
                exchange,
                route,
                requestRoot,
                upstreamPath,
                recordUsage,
            )

            route.provider.type == "openai" && recordUsage ->
                openAiHandler.chatCompletion(exchange, route, requestRoot, recordUsage)

            route.provider.type == "openai" ->
                openAiHandler.countTokens(exchange, route, requestRoot)

            else -> throw ApiError(
                HttpStatus.NOT_IMPLEMENTED,
                "api_error",
                "Провайдер типа '${route.provider.type}' не поддерживается (anthropic|openai)",
            )
        }
    }
}
