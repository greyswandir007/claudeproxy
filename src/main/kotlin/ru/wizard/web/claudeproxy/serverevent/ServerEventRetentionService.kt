package ru.wizard.web.claudeproxy.serverevent

/** Очистка журнала событий сервера от устаревших записей. */
interface ServerEventRetentionService {
    /** Удаляет события старше `claudeproxy.server-event.retention-days`. */
    suspend fun removeObsoleteEvents()
}
