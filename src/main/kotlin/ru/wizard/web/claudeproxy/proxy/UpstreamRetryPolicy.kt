package ru.wizard.web.claudeproxy.proxy

import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException

/**
 * Политика переключения на следующий маршрут (fallback):
 * повторяемые ошибки — quota/rate limit (429), timeout (408), 5xx (включая
 * service unavailable) и сетевые сбои; конфигурационные 4xx не переключают.
 */
object UpstreamRetryPolicy {

    fun isRetryable(error: Throwable): Boolean = when (error) {
        is UpstreamError -> isRetryableStatus(error.status.value())
        is WebClientRequestException -> true
        is WebClientResponseException -> isRetryableStatus(error.statusCode.value())
        // API_TIMEOUT_MS: провайдер молчит — переключаемся на следующий маршрут
        is java.util.concurrent.TimeoutException -> true
        else -> false
    }

    fun isRetryableStatus(status: Int): Boolean =
        status == 408 || status == 429 || status >= 500
}
