package ru.wizard.web.claudeproxy.usage

/**
 * Запись usage-событий: fire-and-forget, не блокирует ответ клиенту.
 */
interface UsageRecorder {

    /** Асинхронная запись события использования (без блокировки запроса). */
    fun recordAsync(usageEvent: UsageEvent)
}
