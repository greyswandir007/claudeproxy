package ru.wizard.web.claudeproxy.proxy

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ServerWebExchange
import ru.wizard.web.claudeproxy.proxy.openai.inbound.OpenAiCompatibilityErrors

/**
 * Все ошибки клиентам — в формате их протокола: Anthropic для /v1/messages,
 * OpenAI для /v1/chat/completions; ошибки провайдеров пробрасываются как есть.
 */
@RestControllerAdvice
class ApiExceptionHandler(private val objectMapper: ObjectMapper) {

    @ExceptionHandler(ApiError::class)
    fun handle(error: ApiError, exchange: ServerWebExchange): ResponseEntity<String> {
        if (OpenAiCompatibilityErrors.isInboundPath(exchange.request.path.value())) {
            return openAiError(error)
        }
        if (error is UpstreamError) {
            val builder = ResponseEntity.status(error.status)
            builder.contentType(error.contentType ?: MediaType.APPLICATION_JSON)
            error.retryAfter?.let { builder.header(HttpHeaders.RETRY_AFTER, it) }
            return builder.body(error.upstreamBody)
        }
        return ResponseEntity.status(error.status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(AnthropicErrors.json(error.type, error.message ?: "error"))
    }

    private fun openAiError(error: ApiError): ResponseEntity<String> {
        if (error is UpstreamError) {
            val builder = ResponseEntity.status(error.status)
                .contentType(MediaType.APPLICATION_JSON)
            error.retryAfter?.let { builder.header(HttpHeaders.RETRY_AFTER, it) }
            return builder.body(
                OpenAiCompatibilityErrors.json(
                    mapErrorType(error.type),
                    upstreamMessage(error.upstreamBody).ifBlank {
                        "Upstream error: HTTP ${error.status.value()}"
                    },
                ),
            )
        }
        return ResponseEntity.status(error.status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(OpenAiCompatibilityErrors.json(mapErrorType(error.type), error.message ?: "error"))
    }

    /** Сообщение из Anthropic-формата проброшенного тела ошибки провайдера. */
    private fun upstreamMessage(upstreamBody: String): String = runCatching {
        objectMapper.readTree(upstreamBody).path("error").path("message").asText("")
    }.getOrDefault("")

    private fun mapErrorType(type: String): String = when (type) {
        "rate_limit_error" -> "rate_limit_exceeded"
        "api_error" -> "server_error"
        else -> type
    }
}
