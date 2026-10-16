package ru.wizard.web.claudeproxy.auth.impl

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.web.server.ServerWebExchange
import ru.wizard.web.claudeproxy.auth.ApiKeyAuthFilter
import ru.wizard.web.claudeproxy.auth.ApiKeyService.AuthorizedKey
import ru.wizard.web.claudeproxy.auth.KeyQuotaService
import ru.wizard.web.claudeproxy.db.DatabaseProvider
import ru.wizard.web.claudeproxy.proxy.ApiError

/**
 * Реализация KeyQuotaService: allowlist по атрибуту ключа из фильтра,
 * расход — SQL-сумма по usage_event за текущее окно ключа и 30 дней.
 */
@Service
class JdbcKeyQuotaService(
    private val databaseProvider: DatabaseProvider,
    private val jdbcTemplate: JdbcTemplate,
) : KeyQuotaService {

    override suspend fun enforce(exchange: ServerWebExchange, model: String) {
        val authorizedKey = exchange.getAttribute<AuthorizedKey>(AUTHORIZED_KEY_ATTRIBUTE) ?: return
        if (authorizedKey.allowedModels.isNotEmpty() && model !in authorizedKey.allowedModels) {
            throw ApiError(
                HttpStatus.FORBIDDEN,
                "permission_error",
                "Модель '$model' недоступна для ключа '${authorizedKey.name}'",
            )
        }
        authorizedKey.limitWindowTokens?.let { windowLimit ->
            val spent = databaseProvider.execute {
                jdbcTemplate.query(
                    """SELECT COALESCE(SUM(e.input_tokens + e.output_tokens +
                              e.cache_creation_tokens + e.cache_read_tokens), 0)
                       FROM usage_window w JOIN usage_event e
                         ON e.client_key = w.client_key AND e.ts >= w.started_at AND e.ts < w.ends_at
                       WHERE w.client_key = ? AND w.ends_at > ?""",
                    { resultSet, _ -> resultSet.getLong(1) },
                    authorizedKey.name,
                    System.currentTimeMillis(),
                ).firstOrNull() ?: 0L
            }
            if (spent >= windowLimit) {
                throw ApiError(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "rate_limit_error",
                    "Квота ключа '${authorizedKey.name}' на 5-часовое окно исчерпана: " +
                        "$spent из $windowLimit токенов",
                )
            }
        }
        authorizedKey.limitMonthTokens?.let { monthLimit ->
            val spent = databaseProvider.execute {
                jdbcTemplate.query(
                    """SELECT COALESCE(SUM(input_tokens + output_tokens +
                              cache_creation_tokens + cache_read_tokens), 0)
                       FROM usage_event WHERE client_key = ? AND ts >= ?""",
                    { resultSet, _ -> resultSet.getLong(1) },
                    authorizedKey.name,
                    System.currentTimeMillis() - 30L * 86_400_000,
                ).firstOrNull() ?: 0L
            }
            if (spent >= monthLimit) {
                throw ApiError(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "rate_limit_error",
                    "Месячная квота ключа '${authorizedKey.name}' исчерпана: $spent из $monthLimit токенов",
                )
            }
        }
    }

    companion object {
        /** Фильтр кладёт весь ключ сюда; имя остаётся для usage. */
        const val AUTHORIZED_KEY_ATTRIBUTE = "claudeproxy.authorizedKey"
    }
}
