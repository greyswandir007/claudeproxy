package ru.wizard.web.claudeproxy.proxy

import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Все ошибки клиентам — в формате Anthropic; ошибки провайдеров пробрасываются как есть.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(ApiError::class)
    fun handle(e: ApiError): ResponseEntity<String> {
        if (e is UpstreamError) {
            val builder = ResponseEntity.status(e.status)
            builder.contentType(e.contentType ?: MediaType.APPLICATION_JSON)
            e.retryAfter?.let { builder.header(HttpHeaders.RETRY_AFTER, it) }
            return builder.body(e.upstreamBody)
        }
        return ResponseEntity.status(e.status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(AnthropicErrors.json(e.type, e.message ?: "error"))
    }
}
