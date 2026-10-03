package ru.wizard.web.claudeproxy.proxy.openai.impl

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import ru.wizard.web.claudeproxy.proxy.AnthropicErrors
import ru.wizard.web.claudeproxy.proxy.UpstreamError

/**
 * Перевод ошибок OpenAI (формат {"error":{...}}) в формат ошибок Anthropic.
 * retry-after пробрасывается.
 */
class OpenAiErrorTranslator(private val objectMapper: ObjectMapper) {

    /** Переводит ошибку OpenAI-провайдера в UpstreamError. */
    fun translate(entity: ResponseEntity<String>): UpstreamError {
        val upstreamBody = entity.body ?: ""
        val message = extractMessage(upstreamBody)
            .ifBlank { "Ошибка провайдера: HTTP ${entity.statusCode.value()}" }
        return UpstreamError(
            status = entity.statusCode,
            upstreamBody = AnthropicErrors.json(mapErrorType(entity.statusCode.value()), message),
            contentType = MediaType.APPLICATION_JSON,
            retryAfter = entity.headers.getFirst(HttpHeaders.RETRY_AFTER),
        )
    }

    private fun extractMessage(upstreamBody: String): String = runCatching {
        val node = objectMapper.readTree(upstreamBody)
        node.path("error").path("message").takeIf { it.isTextual }?.asText() ?: ""
    }.getOrDefault("")

    private fun mapErrorType(statusCode: Int): String = when (statusCode) {
        400 -> "invalid_request_error"
        401 -> "authentication_error"
        403 -> "permission_error"
        404 -> "not_found_error"
        413 -> "request_too_large"
        429 -> "rate_limit_error"
        in 500..599 -> "api_error"
        else -> "api_error"
    }
}
