package ru.wizard.web.claudeproxy.auth

import kotlinx.coroutines.reactor.mono
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import ru.wizard.web.claudeproxy.proxy.AnthropicErrors
import ru.wizard.web.claudeproxy.proxy.ApiError
import ru.wizard.web.claudeproxy.proxy.openai.inbound.OpenAiCompatibilityErrors

/**
 * Авторизация запросов к эндпоинтам /v1: x-api-key или Authorization: Bearer →
 * ключ из БД. Имя ключа кладётся в атрибут exchange для usage-учёта.
 */
@Component
class ApiKeyAuthFilter(private val apiKeyService: ApiKeyService) : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.path.value()
        if (!path.startsWith("/v1/")) {
            return chain.filter(exchange)
        }
        // формат ошибки — по протоколу клиента: OpenAI для /v1/chat/completions
        val openAiFormat = OpenAiCompatibilityErrors.isInboundPath(path)
        val presentedKey = extractKey(exchange.request)
            ?: return writeError(
                exchange,
                openAiFormat,
                "Отсутствует API-ключ: передайте x-api-key или Authorization: Bearer",
            )
        return mono {
            apiKeyService.authenticate(presentedKey)
                ?: throw ApiError(HttpStatus.UNAUTHORIZED, "authentication_error", "invalid x-api-key")
        }.flatMap { authorizedKey ->
            exchange.attributes[CLIENT_KEY_ATTRIBUTE] = authorizedKey.name
            chain.filter(exchange)
        }.onErrorResume(ApiError::class.java) { error ->
            writeError(exchange, openAiFormat, error.message ?: "error")
        }
    }

    private fun writeError(
        exchange: ServerWebExchange,
        openAiFormat: Boolean,
        message: String,
    ): Mono<Void> =
        if (openAiFormat) {
            OpenAiCompatibilityErrors.write(
                exchange,
                HttpStatus.UNAUTHORIZED,
                "authentication_error",
                message,
            )
        } else {
            AnthropicErrors.write(
                exchange,
                HttpStatus.UNAUTHORIZED,
                "authentication_error",
                message,
            )
        }

    companion object {
        const val CLIENT_KEY_ATTRIBUTE = "claudeproxy.clientKey"

        private fun extractKey(request: ServerHttpRequest): String? {
            request.headers.getFirst("x-api-key")?.let { apiKey ->
                return apiKey.trim().takeIf(String::isNotEmpty)
            }
            request.headers.getFirst(HttpHeaders.AUTHORIZATION)?.let { authorizationHeader ->
                if (authorizationHeader.startsWith("Bearer ", ignoreCase = true)) {
                    return authorizationHeader.substring(7).trim().takeIf(String::isNotEmpty)
                }
            }
            return null
        }
    }
}
