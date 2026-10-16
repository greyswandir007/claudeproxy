package ru.wizard.web.claudeproxy.api

import org.springframework.http.CacheControl
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * Запрещает кэширование ответов API (пути, начинающиеся с "/api/"): дашборд
 * опрашивает их каждые 30 секунд, и любой слой кэша (браузер, intermediary)
 * заморозил бы график на старых данных.
 */
@Component
class ApiCacheControlWebFilter : WebFilter {
    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): Mono<Void> {
        if (exchange.request.path.value().startsWith("/api/")) {
            exchange.response.headers.cacheControl = CacheControl.noStore().headerValue
        }
        return chain.filter(exchange)
    }
}
