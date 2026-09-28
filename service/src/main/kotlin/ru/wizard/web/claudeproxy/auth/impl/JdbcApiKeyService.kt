package ru.wizard.web.claudeproxy.auth.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import ru.wizard.web.claudeproxy.auth.ApiKeyService
import ru.wizard.web.claudeproxy.auth.ApiKeyService.AuthorizedKey
import ru.wizard.web.claudeproxy.config.ProxyProperties
import ru.wizard.web.claudeproxy.db.DatabaseProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * Реализация ApiKeyService на JdbcTemplate (SQLite через DatabaseProvider).
 */
@Service
@org.springframework.context.annotation.DependsOn("databaseMigrationRunner")
class JdbcApiKeyService(
    private val databaseProvider: DatabaseProvider,
    private val jdbcTemplate: JdbcTemplate,
    private val proxyProperties: ProxyProperties,
) : ApiKeyService {
    private val logger = KotlinLogging.logger {}

    private val lastTouchTimestamps = ConcurrentHashMap<Long, Long>()
    private val touchScope = CoroutineScope(SupervisorJob())

    @PostConstruct
    override fun seed() {
        for (seedKey in proxyProperties.apiKeys) {
            if (seedKey.name.isBlank() || seedKey.key.isBlank()) {
                logger.warn { "Skipped seed key with blank name/key" }
                continue
            }
            val keyHash = ApiKeyService.sha256Hex(seedKey.key)
            val existingHashes = jdbcTemplate.query(
                "SELECT key_hash FROM api_key WHERE name = ?",
                { resultSet, _ -> resultSet.getString(1) },
                seedKey.name,
            )
            when {
                existingHashes.isEmpty() -> {
                    jdbcTemplate.update(
                        "INSERT INTO api_key (name, key_hash, key_prefix, created_at) VALUES (?,?,?,?)",
                        seedKey.name,
                        keyHash,
                        seedKey.key.take(KEY_PREFIX_LENGTH),
                        System.currentTimeMillis(),
                    )
                    logger.info { "Seeded client key '${seedKey.name}'" }
                }

                existingHashes.first() != keyHash ->
                    logger.warn {
                        "Seed key '${seedKey.name}' differs from the stored one - keeping the stored key"
                    }
            }
        }
    }

    override suspend fun authenticate(presentedKey: String): AuthorizedKey? =
        databaseProvider.execute {
            jdbcTemplate.query(
                """SELECT id, name, allowed_models, limit_window_tokens, limit_month_tokens
                   FROM api_key WHERE key_hash = ? AND revoked_at IS NULL""",
                { resultSet, _ ->
                    AuthorizedKey(
                        id = resultSet.getLong("id"),
                        name = resultSet.getString("name"),
                        allowedModels = resultSet.getString("allowed_models")
                            .split(',')
                            .map(String::trim)
                            .filter(String::isNotEmpty),
                        limitWindowTokens = resultSet.getLong("limit_window_tokens")
                            .takeIf { !resultSet.wasNull() },
                        limitMonthTokens = resultSet.getLong("limit_month_tokens")
                            .takeIf { !resultSet.wasNull() },
                    )
                },
                ApiKeyService.sha256Hex(presentedKey),
            ).firstOrNull()
        }?.also { touch(it.id) }

    /** last_used_at обновляем не чаще раза в минуту на ключ (троттлинг записей в БД). */
    private fun touch(id: Long) {
        val now = System.currentTimeMillis()
        val previousTouchTimestamp = lastTouchTimestamps.put(id, now) ?: 0L
        if (now - previousTouchTimestamp >= LAST_USED_TOUCH_INTERVAL_MILLISECONDS) {
            touchScope.launch {
                runCatching {
                    databaseProvider.execute {
                        jdbcTemplate.update(
                            "UPDATE api_key SET last_used_at = ? WHERE id = ?",
                            now,
                            id,
                        )
                    }
                }
            }
        }
    }

    private companion object {
        private const val KEY_PREFIX_LENGTH = 10
        private const val LAST_USED_TOUCH_INTERVAL_MILLISECONDS = 60_000L
    }
}
