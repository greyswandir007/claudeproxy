package ru.wizard.web.claudeproxy.usage

/**
 * Очистка устаревших usage_event (retention-days из конфигурации).
 */
interface UsageRetentionService {

    suspend fun deleteOutdatedEvents()
}
