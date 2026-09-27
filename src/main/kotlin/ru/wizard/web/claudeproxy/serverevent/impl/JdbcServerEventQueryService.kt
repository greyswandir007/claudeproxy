package ru.wizard.web.claudeproxy.serverevent.impl

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import ru.wizard.web.claudeproxy.db.DatabaseProvider
import ru.wizard.web.claudeproxy.serverevent.ServerEvent
import ru.wizard.web.claudeproxy.serverevent.ServerEventFilter
import ru.wizard.web.claudeproxy.serverevent.ServerEventQueryService

/** Чтение журнала событий сервера из SQLite. */
@Component
class JdbcServerEventQueryService(
    private val databaseProvider: DatabaseProvider,
    private val jdbcTemplate: JdbcTemplate,
) : ServerEventQueryService {

    override suspend fun events(filter: ServerEventFilter): List<ServerEvent> =
        databaseProvider.execute {
            val conditions = mutableListOf<String>()
            val parameters = mutableListOf<Any>()
            if (!filter.level.isNullOrBlank()) {
                conditions.add("level = ?")
                parameters.add(filter.level)
            }
            if (!filter.loggerContains.isNullOrBlank()) {
                conditions.add("logger LIKE ?")
                parameters.add("%${filter.loggerContains}%")
            }
            if (!filter.messageContains.isNullOrBlank()) {
                conditions.add("message LIKE ?")
                parameters.add("%${filter.messageContains}%")
            }
            filter.beforeId?.let {
                conditions.add("id < ?")
                parameters.add(it)
            }
            val whereClause = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
            parameters.add(filter.limit)
            jdbcTemplate.query(
                """
                SELECT id, ts, level, logger, message, stack_trace
                FROM server_event
                $whereClause
                ORDER BY id DESC
                LIMIT ?
                """,
                { resultSet, _ ->
                    ServerEvent(
                        id = resultSet.getLong(1),
                        timestampMilliseconds = resultSet.getLong(2),
                        level = resultSet.getString(3),
                        logger = resultSet.getString(4),
                        message = resultSet.getString(5),
                        stackTrace = resultSet.getString(6),
                    )
                },
                *parameters.toTypedArray(),
            )
        }
}
