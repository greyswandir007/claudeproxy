package ru.wizard.web.claudeproxy.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import ru.wizard.web.claudeproxy.config.ProxyProperties
import java.security.MessageDigest
import java.util.Base64

/**
 * Basic Auth для дашборда и /api (production): включается заданием
 * claudeproxy.dashboard.auth.username/password. Эндпоинты /v1 не закрываются —
 * там api-ключи прокси. Сравнение учётных данных — constant-time.
 * Без заданных учётных данных фильтр пропускает всё (прокси должен слушать 127.0.0.1).
 */
@Component
class BasicAuthWebFilter(proxyProperties: ProxyProperties) : WebFilter {
    private val logger = KotlinLogging.logger {}

    private val expectedAuthorizationHeader: String? = run {
        val username = proxyProperties.dashboard.auth.username
        val password = proxyProperties.dashboard.auth.password
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            null
        } else {
            "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray())
        }
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val expectedHeader = expectedAuthorizationHeader ?: return chain.filter(exchange)
        val path = exchange.request.path.value()
        if (path.startsWith("/v1/")) return chain.filter(exchange)

        val presentedHeader = exchange.request.headers.getFirst(HttpHeaders.AUTHORIZATION)
        val authorized = presentedHeader != null && MessageDigest.isEqual(
            presentedHeader.toByteArray(),
            expectedHeader.toByteArray(),
        )
        if (authorized) {
            return chain.filter(exchange)
        }
        // сами учётные данные не логируем — только факт, путь и адрес клиента
        val clientHost = exchange.request.remoteAddress?.address?.hostAddress ?: "unknown"
        if (presentedHeader == null) {
            logger.warn { "Dashboard authentication failed: credentials not presented (path=$path, client=$clientHost)" }
        } else {
            logger.warn { "Dashboard authentication failed: wrong credentials (path=$path, client=$clientHost)" }
        }
        val response = exchange.response
        response.statusCode = HttpStatus.UNAUTHORIZED
        response.headers.add(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"claudeproxy\", charset=\"UTF-8\"")
        return response.setComplete()
    }
}
