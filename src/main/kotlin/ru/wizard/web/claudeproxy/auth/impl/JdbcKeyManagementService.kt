package ru.wizard.web.claudeproxy.auth.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import ru.wizard.web.claudeproxy.auth.ApiKeyService
import ru.wizard.web.claudeproxy.auth.KeyManagementService
import ru.wizard.web.claudeproxy.db.DatabaseProvider
import ru.wizard.web.claudeproxy.proxy.ApiError
import java.security.SecureRandom
import java.util.Base64

/**
 * Реализация KeyManagementService на JdbcTemplate. Ключ — cpk_ + 43 символа
 * base64url (256 бит), в БД хранится только SHA-256-хэш и префикс.
 */
@Service
class JdbcKeyManagementService(
    private val databaseProvider: DatabaseProvider,
    private val jdbcTemplate: JdbcTemplate,
) : KeyManagementService {
    private val logger = KotlinLogging.logger {}
    private val secureRandom = SecureRandom()

    override suspend fun list(): List<KeyManagementService.ClientKey> =
        databaseProvider.execute {
            jdbcTemplate.query(
                """SELECT id, name, key_prefix, created_at, last_used_at, revoked_at
                   FROM api_key ORDER BY created_at DESC""",
                { resultSet, _ ->
                    KeyManagementService.ClientKey(
                        id = resultSet.getLong("id"),
                        name = resultSet.getString("name"),
                        keyPrefix = resultSet.getString("key_prefix"),
                        createdAt = resultSet.getLong("created_at"),
                        lastUsedAt = resultSet.getLong("last_used_at").takeIf { !resultSet.wasNull() },
                        revokedAt = resultSet.getLong("revoked_at").takeIf { !resultSet.wasNull() },
                    )
                },
            )
        }

    override suspend fun create(name: String): KeyManagementService.CreatedKey {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty() || trimmedName.length > MAX_NAME_LENGTH) {
            throw ApiError(
                HttpStatus.BAD_REQUEST,
                "invalid_request_error",
                "Имя ключа обязательно и не длиннее $MAX_NAME_LENGTH символов",
            )
        }
        val fullKey = generateFullKey()
        return databaseProvider.execute {
            val existingNames = jdbcTemplate.query(
                "SELECT name FROM api_key WHERE name = ?",
                { resultSet, _ -> resultSet.getString(1) },
                trimmedName,
            )
            if (existingNames.isNotEmpty()) {
                throw ApiError(
                    HttpStatus.CONFLICT,
                    "invalid_request_error",
                    "Ключ с именем '$trimmedName' уже существует",
                )
            }
            jdbcTemplate.update(
                "INSERT INTO api_key (name, key_hash, key_prefix, created_at) VALUES (?,?,?,?)",
                trimmedName,
                ApiKeyService.sha256Hex(fullKey),
                fullKey.take(KEY_PREFIX_LENGTH),
                System.currentTimeMillis(),
            )
            val identifier = jdbcTemplate.queryForObject(
                "SELECT id FROM api_key WHERE name = ?",
                Long::class.java,
                trimmedName,
            )!!
            logger.info { "Создан клиентский ключ '$trimmedName'" }
            KeyManagementService.CreatedKey(
                clientKey = KeyManagementService.ClientKey(
                    id = identifier,
                    name = trimmedName,
                    keyPrefix = fullKey.take(KEY_PREFIX_LENGTH),
                    createdAt = System.currentTimeMillis(),
                    lastUsedAt = null,
                    revokedAt = null,
                ),
                fullKey = fullKey,
            )
        }
    }

    override suspend fun revoke(id: Long): Boolean =
        databaseProvider.execute {
            jdbcTemplate.update(
                "UPDATE api_key SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL",
                System.currentTimeMillis(),
                id,
            ) > 0
        }

    private fun generateFullKey(): String {
        val randomBytes = ByteArray(32)
        secureRandom.nextBytes(randomBytes)
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)
    }

    private companion object {
        const val KEY_PREFIX = "cpk_"
        const val KEY_PREFIX_LENGTH = 10
        const val MAX_NAME_LENGTH = 64
    }
}
