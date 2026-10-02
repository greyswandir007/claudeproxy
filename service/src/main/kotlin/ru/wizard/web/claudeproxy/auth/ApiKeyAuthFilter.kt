package ru.wizard.web.claudeproxy.auth

import io.github.oshai.kotlinlogging.KotlinLogging
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
    private val logger = KotlinLogging.logger {}

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val path = exchange.request.path.value()
        if (!path.startsWith("/v1/")) {
            return chain.filter(exchange)
        }
        // формат ошибки — по протоколу клиента: OpenAI для /v1/chat/completions
        val openAiFormat = OpenAiCompatibilityErrors.isInboundPath(path)
        val clientHost = exchange.request.remoteAddress?.address?.hostAddress ?: "unknown"
        val presentedKey = extractKey(exchange.request)
            ?: run {
                // сам ключ не логируем — только факт и адрес клиента
                logger.warn { "Client authentication failed: API key not presented (path=$path, client=$clientHost)" }
                return writeError(
                    exchange,
                    openAiFormat,
                    "Отсутствует API-ключ: передайте x-api-key или Authorization: Bearer",
                )
            }
        return mono {
            apiKeyService.authenticate(presentedKey)
                ?: run {
                    logger.warn { "Client authentication failed: invalid API key (path=$path, client=$clientHost)" }
                    throw ApiError(HttpStatus.UNAUTHORIZED, "authentication_error", "invalid x-api-key")
                }
        }.flatMap { authorizedKey ->
            exchange.attributes[CLIENT_KEY_ATTRIBUTE] = authorizedKey.name
            exchange.attributes[AUTHORIZED_KEY_ATTRIBUTE] = authorizedKey
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
        /** Имя авторизованного ключа клиента — атрибут exchange для usage-учёта. */
        const val CLIENT_KEY_ATTRIBUTE = "claudeproxy.clientKey"

        /** Полный объект ключа (квоты/allowlist) — для KeyQuotaService. */
        const val AUTHORIZED_KEY_ATTRIBUTE = "claudeproxy.authorizedKey"

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
