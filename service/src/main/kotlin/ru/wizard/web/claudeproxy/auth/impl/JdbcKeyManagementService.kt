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
                """SELECT id, name, key_prefix, allowed_models,
                          limit_window_tokens, limit_month_tokens,
                          created_at, last_used_at, revoked_at
                   FROM api_key ORDER BY created_at DESC""",
                { resultSet, _ ->
                    KeyManagementService.ClientKey(
                        id = resultSet.getLong("id"),
                        name = resultSet.getString("name"),
                        keyPrefix = resultSet.getString("key_prefix"),
                        allowedModels = resultSet.getString("allowed_models")
                            .split(',')
                            .map(String::trim)
                            .filter(String::isNotEmpty),
                        limitWindowTokens = resultSet.getLong("limit_window_tokens")
                            .takeIf { !resultSet.wasNull() },
                        limitMonthTokens = resultSet.getLong("limit_month_tokens")
                            .takeIf { !resultSet.wasNull() },
                        createdAt = resultSet.getLong("created_at"),
                        lastUsedAt = resultSet.getLong("last_used_at").takeIf { !resultSet.wasNull() },
                        revokedAt = resultSet.getLong("revoked_at").takeIf { !resultSet.wasNull() },
                    )
                },
            )
        }

    override suspend fun create(request: KeyManagementService.KeyRequest): KeyManagementService.CreatedKey {
        validateRequest(request)
        val fullKey = generateFullKey()
        return databaseProvider.execute {
            val existingNames = jdbcTemplate.query(
                "SELECT name FROM api_key WHERE name = ?",
                { resultSet, _ -> resultSet.getString(1) },
                request.name,
            )
            if (existingNames.isNotEmpty()) {
                throw ApiError(
                    HttpStatus.CONFLICT,
                    "invalid_request_error",
                    "Ключ с именем '${request.name}' уже существует",
                )
            }
            val now = System.currentTimeMillis()
            jdbcTemplate.update(
                """INSERT INTO api_key
                   (name, key_hash, key_prefix, allowed_models, limit_window_tokens, limit_month_tokens, created_at)
                   VALUES (?,?,?,?,?,?,?)""",
                request.name,
                ApiKeyService.sha256Hex(fullKey),
                fullKey.take(KEY_PREFIX_LENGTH),
                request.allowedModels.joinToString(","),
                request.limitWindowTokens,
                request.limitMonthTokens,
                now,
            )
            val identifier = jdbcTemplate.queryForObject(
                "SELECT id FROM api_key WHERE name = ?",
                Long::class.java,
                request.name,
            )!!
            logger.info { "Created client key '${request.name}'" }
            KeyManagementService.CreatedKey(
                clientKey = KeyManagementService.ClientKey(
                    id = identifier,
                    name = request.name,
                    keyPrefix = fullKey.take(KEY_PREFIX_LENGTH),
                    allowedModels = request.allowedModels,
                    limitWindowTokens = request.limitWindowTokens,
                    limitMonthTokens = request.limitMonthTokens,
                    createdAt = now,
                    lastUsedAt = null,
                    revokedAt = null,
                ),
                fullKey = fullKey,
            )
        }
    }

    override suspend fun update(id: Long, request: KeyManagementService.KeyRequest): KeyManagementService.ClientKey {
        validateRequest(request)
        return databaseProvider.execute {
            val existing = jdbcTemplate.query(
                "SELECT id FROM api_key WHERE id = ?",
                { resultSet, _ -> resultSet.getLong(1) },
                id,
            )
            if (existing.isEmpty()) {
                throw ApiError(HttpStatus.NOT_FOUND, "not_found_error", "Ключ не найден")
            }
            jdbcTemplate.update(
                """UPDATE api_key SET allowed_models = ?, limit_window_tokens = ?, limit_month_tokens = ?
                   WHERE id = ?""",
                request.allowedModels.joinToString(","),
                request.limitWindowTokens,
                request.limitMonthTokens,
                id,
            )
            loadClientKey(id)
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

    private fun loadClientKey(id: Long): KeyManagementService.ClientKey {
        val rows = ArrayList<KeyManagementService.ClientKey>()
        jdbcTemplate.query(
            """SELECT id, name, key_prefix, allowed_models, limit_window_tokens, limit_month_tokens,
                      created_at, last_used_at, revoked_at
               FROM api_key WHERE id = ?""",
            { resultSet ->
                rows.add(
                    KeyManagementService.ClientKey(
                        id = resultSet.getLong(1),
                        name = resultSet.getString(2),
                        keyPrefix = resultSet.getString(3),
                        allowedModels = resultSet.getString(4).split(',').map(String::trim)
                            .filter(String::isNotEmpty),
                        limitWindowTokens = resultSet.getLong(5).takeIf { !resultSet.wasNull() },
                        limitMonthTokens = resultSet.getLong(6).takeIf { !resultSet.wasNull() },
                        createdAt = resultSet.getLong(7),
                        lastUsedAt = resultSet.getLong(8).takeIf { !resultSet.wasNull() },
                        revokedAt = resultSet.getLong(9).takeIf { !resultSet.wasNull() },
                    ),
                )
            },
            id,
        )
        return rows.firstOrNull() ?: throw ApiError(HttpStatus.NOT_FOUND, "not_found_error", "Ключ не найден")
    }

    private fun validateRequest(request: KeyManagementService.KeyRequest) {
        val name = request.name.trim()
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) {
            throw ApiError(
                HttpStatus.BAD_REQUEST,
                "invalid_request_error",
                "Имя ключа обязательно и не длиннее $MAX_NAME_LENGTH символов",
            )
        }
        request.limitWindowTokens?.let {
            if (it <= 0) throw ApiError(HttpStatus.BAD_REQUEST, "invalid_request_error", "Квота окна должна быть положительной")
        }
        request.limitMonthTokens?.let {
            if (it <= 0) throw ApiError(HttpStatus.BAD_REQUEST, "invalid_request_error", "Месячная квота должна быть положительной")
        }
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
