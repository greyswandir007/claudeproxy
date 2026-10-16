package ru.wizard.web.claudeproxy.proxy

/**
 * Полная расшифровка ошибки провайдера для колонки usage_event.error_detail.
 * Краткий текст (error) обрезается до 300 символов и годится только для списка;
 * здесь сохраняется тело ответа провайдера целиком либо стектрейс исключения.
 */
object ProxyErrorDetails {

    /** Верхняя граница размера детальной расшифровки в символах (~64 КБ). */
    const val MAX_DETAIL_LENGTH: Int = 64 * 1024

    /**
     * Развёрнутое описание ошибки: для UpstreamError — полное тело ответа
     * провайдера, для прочих исключений — стектрейс. Возвращает null, если
     * разворачивать нечего (например, пустое тело ответа).
     */
    fun of(error: Throwable): String? {
        val detail = when (error) {
            is UpstreamError -> error.upstreamBody ?: error.stackTraceToString()
            else -> error.stackTraceToString()
        }
        return detail.takeIf { it.isNotBlank() }?.take(MAX_DETAIL_LENGTH)
    }

    /**
     * Ограничение сырого тела ответа провайдера для error_detail: применяется,
     * когда статус известен без исключения (не-2xx ответ прочитан напрямую).
     */
    fun ofBody(body: String?): String? = body?.takeIf { it.isNotBlank() }?.take(MAX_DETAIL_LENGTH)
}
