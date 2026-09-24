package ru.wizard.web.claudeproxy.usage.impl

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import ru.wizard.web.claudeproxy.config.ProxyProperties
import ru.wizard.web.claudeproxy.usage.WindowService

/**
 * Реализация WindowService на JdbcTemplate. Методы выполняются на контексте
 * базы данных (см. DatabaseProvider).
 */
@Service
class JdbcWindowService(
    private val jdbcTemplate: JdbcTemplate,
    private val proxyProperties: ProxyProperties,
) : WindowService {

    override fun ensureWindow(clientKey: String, timestamp: Long) {
        val windowDurationMilliseconds = proxyProperties.windowHours * 3_600_000L
        val activeWindows = jdbcTemplate.query(
            "SELECT id FROM usage_window WHERE client_key = ? AND ends_at > ? " +
                "ORDER BY started_at DESC LIMIT 1",
            { resultSet, _ -> resultSet.getLong(1) },
            clientKey,
            timestamp,
        )
        if (activeWindows.isEmpty()) {
            jdbcTemplate.update(
                "INSERT INTO usage_window (client_key, started_at, ends_at) VALUES (?,?,?)",
                clientKey,
                timestamp,
                timestamp + windowDurationMilliseconds,
            )
        }
    }
}
