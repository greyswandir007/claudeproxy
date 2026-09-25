package ru.wizard.web.claudeproxy.usage.impl

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import ru.wizard.web.claudeproxy.config.ProxyProperties
import ru.wizard.web.claudeproxy.usage.WindowService

/**
 * Реализация WindowService на JdbcTemplate — окна клиентских ключей (usage_window)
 * и провайдеров (provider_usage_window). Методы выполняются на контексте БД.
 */
@Service
class JdbcWindowService(
    private val jdbcTemplate: JdbcTemplate,
    private val proxyProperties: ProxyProperties,
) : WindowService {

    override fun ensureWindow(clientKey: String, timestamp: Long) {
        ensureWindowRow(
            table = "usage_window",
            nameColumn = "client_key",
            name = clientKey,
            timestamp = timestamp,
        )
    }

    override fun ensureProviderWindow(providerName: String, timestamp: Long) {
        ensureWindowRow(
            table = "provider_usage_window",
            nameColumn = "provider_name",
            name = providerName,
            timestamp = timestamp,
        )
    }

    override fun currentProviderWindow(providerName: String): WindowService.WindowBounds? {
        val bounds = ArrayList<WindowService.WindowBounds>()
        val now = System.currentTimeMillis()
        jdbcTemplate.query(
            "SELECT started_at, ends_at FROM provider_usage_window " +
                "WHERE provider_name = ? ORDER BY started_at DESC LIMIT 1",
            { resultSet ->
                bounds.add(
                    WindowService.WindowBounds(
                        startedAtMilliseconds = resultSet.getLong(1),
                        endsAtMilliseconds = resultSet.getLong(2),
                        active = resultSet.getLong(2) > now,
                    ),
                )
            },
            providerName,
        )
        return bounds.firstOrNull()
    }

    private fun ensureWindowRow(table: String, nameColumn: String, name: String, timestamp: Long) {
        val windowDurationMilliseconds = proxyProperties.windowHours * 3_600_000L
        val activeWindows = jdbcTemplate.query(
            "SELECT id FROM $table WHERE $nameColumn = ? AND ends_at > ? " +
                "ORDER BY started_at DESC LIMIT 1",
            { resultSet, _ -> resultSet.getLong(1) },
            name,
            timestamp,
        )
        if (activeWindows.isEmpty()) {
            jdbcTemplate.update(
                "INSERT INTO $table ($nameColumn, started_at, ends_at) VALUES (?,?,?)",
                name,
                timestamp,
                timestamp + windowDurationMilliseconds,
            )
        }
    }
}
