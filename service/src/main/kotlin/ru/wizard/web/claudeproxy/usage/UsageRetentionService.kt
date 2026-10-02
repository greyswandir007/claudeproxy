package ru.wizard.web.claudeproxy.usage

/**
 * Очистка устаревших usage_event (retention-days из конфигурации).
 */
interface UsageRetentionService {

    /** Удаляет usage_event старше claudeproxy.retention-days (0 = не чистить). */
    suspend fun deleteOutdatedEvents()
}
