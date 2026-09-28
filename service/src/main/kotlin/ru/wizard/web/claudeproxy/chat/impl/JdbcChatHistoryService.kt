package ru.wizard.web.claudeproxy.chat.impl

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import ru.wizard.web.claudeproxy.chat.ChatHistoryService
import ru.wizard.web.claudeproxy.db.DatabaseProvider

/**
 * Реализация ChatHistoryService на JdbcTemplate; операции на контексте БД.
 */
@Service
class JdbcChatHistoryService(
    private val databaseProvider: DatabaseProvider,
    private val jdbcTemplate: JdbcTemplate,
) : ChatHistoryService {

    override suspend fun thread(clientKey: String): ChatHistoryService.ThreadInfo =
        databaseProvider.execute {
            ensureThread(clientKey)
            val rows = ArrayList<ChatHistoryService.ThreadInfo>()
            jdbcTemplate.query(
                "SELECT title, updated_at FROM chat_thread WHERE client_key = ?",
                { resultSet ->
                    rows.add(
                        ChatHistoryService.ThreadInfo(
                            clientKey = clientKey,
                            title = resultSet.getString(1),
                            updatedAt = resultSet.getLong(2),
                        ),
                    )
                },
                clientKey,
            )
            rows.first()
        }

    override suspend fun renameThread(clientKey: String, title: String) {
        databaseProvider.execute {
            ensureThread(clientKey)
            jdbcTemplate.update(
                "UPDATE chat_thread SET title = ?, updated_at = ? WHERE client_key = ?",
                title.take(200),
                System.currentTimeMillis(),
                clientKey,
            )
        }
    }

    override suspend fun messages(clientKey: String): List<ChatHistoryService.ChatMessage> =
        databaseProvider.execute {
            jdbcTemplate.query(
                "SELECT id, role, content, created_at FROM chat_message " +
                    "WHERE client_key = ? ORDER BY id",
                { resultSet, _ ->
                    ChatHistoryService.ChatMessage(
                        id = resultSet.getLong(1),
                        role = resultSet.getString(2),
                        content = resultSet.getString(3),
                        createdAt = resultSet.getLong(4),
                    )
                },
                clientKey,
            )
        }

    override suspend fun append(clientKey: String, role: String, content: String) {
        databaseProvider.execute {
            ensureThread(clientKey)
            jdbcTemplate.update(
                "INSERT INTO chat_message (client_key, role, content, created_at) VALUES (?,?,?,?)",
                clientKey,
                role,
                content,
                System.currentTimeMillis(),
            )
            jdbcTemplate.update(
                "UPDATE chat_thread SET updated_at = ? WHERE client_key = ?",
                System.currentTimeMillis(),
                clientKey,
            )
        }
    }

    override suspend fun clear(clientKey: String) {
        databaseProvider.execute {
            jdbcTemplate.update("DELETE FROM chat_message WHERE client_key = ?", clientKey)
            jdbcTemplate.update(
                "UPDATE chat_thread SET updated_at = ? WHERE client_key = ?",
                System.currentTimeMillis(),
                clientKey,
            )
        }
    }

    private fun ensureThread(clientKey: String) {
        val existing = jdbcTemplate.query(
            "SELECT title FROM chat_thread WHERE client_key = ?",
            { resultSet, _ -> resultSet.getString(1) },
            clientKey,
        )
        if (existing.isEmpty()) {
            jdbcTemplate.update(
                "INSERT INTO chat_thread (client_key, title, updated_at) VALUES (?,?,?)",
                clientKey,
                "",
                System.currentTimeMillis(),
            )
        }
    }
}
