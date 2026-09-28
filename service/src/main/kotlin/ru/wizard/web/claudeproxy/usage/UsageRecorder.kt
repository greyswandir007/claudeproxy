package ru.wizard.web.claudeproxy.usage

/**
 * Запись usage-событий: fire-and-forget, не блокирует ответ клиенту.
 */
interface UsageRecorder {

    fun recordAsync(usageEvent: UsageEvent)
}
