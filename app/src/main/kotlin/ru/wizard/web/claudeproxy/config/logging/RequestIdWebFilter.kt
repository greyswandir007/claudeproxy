package ru.wizard.web.claudeproxy.config.logging

import java.util.UUID
import org.slf4j.MDC
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * requestId в MDC для файловых JSON-логов: один id на HTTP-запрос — по нему
 * склеиваются строки одного запроса. MDC потоково-локальный: на скачках
 * между потоками реактора поле может отсутствовать — ожидаемое ограничение,
 * id ставится самым первым и живёт до конца обработки запроса.
 */
@Component
@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10)
class RequestIdWebFilter : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val requestId = UUID.randomUUID().toString().replace("-", "").take(16)
        MDC.put(MDC_KEY, requestId)
        return chain.filter(exchange).doFinally { MDC.remove(MDC_KEY) }
    }

    companion object {
        const val MDC_KEY = "requestId"
    }
}
