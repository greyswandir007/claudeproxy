package ru.wizard.web.claudeproxy.proxy

import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException

/**
 * Политика переключения на следующий маршрут (fallback):
 * повторяемые ошибки — quota/rate limit (429), timeout (408), 5xx (включая
 * service unavailable) и сетевые сбои; конфигурационные 4xx не переключают.
 */
object UpstreamRetryPolicy {

    /** Ретраебельна ли ошибка (сеть/таймаут). */
    fun isRetryable(error: Throwable): Boolean = when (error) {
        is UpstreamError -> isRetryableStatus(error.status.value())
        is WebClientRequestException -> true
        is WebClientResponseException -> isRetryableStatus(error.statusCode.value())
        // API_TIMEOUT_MS: провайдер молчит — переключаемся на следующий маршрут
        is java.util.concurrent.TimeoutException -> true
        else -> false
    }

    /** Ретраебелен ли HTTP-статус (429/5xx). */
    fun isRetryableStatus(status: Int): Boolean =
        status == 408 || status == 429 || status >= 500

    /**
     * Длительность кулдауна после повторимой ошибки: из retry-after (секунды),
     * иначе дефолт 30 с. Ограничен потолком в RouteCircuitBreaker.
     */
    /** Кулдаун провайдера после ошибки. */
    fun cooldownMilliseconds(error: Throwable): Long {
        // retryAfter объявлен в другом модуле (model), smart cast напрямую невозможен
        val retryAfter = (error as? UpstreamError)?.retryAfter
        if (!retryAfter.isNullOrBlank()) {
            val seconds = retryAfter.trim().toDoubleOrNull()
            if (seconds != null && seconds > 0) {
                return (seconds * 1000).toLong()
            }
        }
        return ru.wizard.web.claudeproxy.routing.RouteCircuitBreaker.DEFAULT_COOLDOWN_MILLISECONDS
    }
}
