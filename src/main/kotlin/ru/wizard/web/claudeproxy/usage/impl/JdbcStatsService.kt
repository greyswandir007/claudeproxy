package ru.wizard.web.claudeproxy.usage.impl

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import ru.wizard.web.claudeproxy.db.DatabaseProvider
import ru.wizard.web.claudeproxy.proxy.ApiError
import ru.wizard.web.claudeproxy.usage.StatsService
import java.time.LocalDate
import java.time.ZoneId

/**
 * Реализация StatsService на JdbcTemplate: агрегаты считаются SQL по индексам.
 */
@Service
class JdbcStatsService(
    private val databaseProvider: DatabaseProvider,
    private val jdbcTemplate: JdbcTemplate,
    private val windowService: ru.wizard.web.claudeproxy.usage.WindowService,
) : StatsService {

    override suspend fun summary(range: String, clientKey: String?): StatsService.RangeSummary =
        databaseProvider.execute {
            val bounds = resolveBounds(range, clientKey)
            StatsService.RangeSummary(
                range = range,
                fromMilliseconds = bounds.first,
                toMilliseconds = bounds.second,
                totals = totalsBetween(bounds.first, bounds.second, clientKey),
                window = if (range == RANGE_WINDOW) activeWindow(clientKey) else null,
            )
        }

    override suspend fun byModel(
        range: String,
        clientKey: String?,
        fromMilliseconds: Long?,
        toMilliseconds: Long?,
    ): List<StatsService.GroupedUsage> =
        databaseProvider.execute {
            val bounds = customOrDefaultBounds(range, clientKey, fromMilliseconds, toMilliseconds)
            grouped("model", bounds.first, bounds.second, clientKey)
        }

    override suspend fun byProvider(
        range: String,
        clientKey: String?,
        fromMilliseconds: Long?,
        toMilliseconds: Long?,
    ): List<StatsService.GroupedUsage> =
        databaseProvider.execute {
            val bounds = customOrDefaultBounds(range, clientKey, fromMilliseconds, toMilliseconds)
            grouped("provider", bounds.first, bounds.second, clientKey)
        }

    /** Пресет диапазона, если не задан произвольный from/to. */
    private fun customOrDefaultBounds(
        range: String,
        clientKey: String?,
        fromMilliseconds: Long?,
        toMilliseconds: Long?,
    ): Pair<Long, Long> =
        if (fromMilliseconds != null && toMilliseconds != null) {
            fromMilliseconds to toMilliseconds
        } else {
            resolveBounds(range, clientKey)
        }

    override suspend fun byClientKey(range: String): List<StatsService.GroupedUsage> =
        databaseProvider.execute {
            val bounds = resolveBounds(range, null)
            grouped("client_key", bounds.first, bounds.second, null)
        }

    override suspend fun windowHistory(clientKey: String, limit: Int): List<StatsService.WindowSummary> =
        databaseProvider.execute {
            jdbcTemplate.query(
                """SELECT w.started_at, w.ends_at,
                       COUNT(e.id),
                       COALESCE(SUM(e.input_tokens), 0), COALESCE(SUM(e.output_tokens), 0),
                       COALESCE(SUM(e.cache_creation_tokens), 0), COALESCE(SUM(e.cache_read_tokens), 0)
                   FROM usage_window w
                   LEFT JOIN usage_event e
                     ON e.client_key = w.client_key AND e.ts >= w.started_at AND e.ts < w.ends_at
                   WHERE w.client_key = ?
                   GROUP BY w.started_at, w.ends_at
                   ORDER BY w.started_at DESC
                   LIMIT ?""",
                { resultSet, _ ->
                    StatsService.WindowSummary(
                        startedAtMilliseconds = resultSet.getLong(1),
                        endsAtMilliseconds = resultSet.getLong(2),
                        totals = StatsService.UsageTotals(
                            requests = resultSet.getLong(3),
                            inputTokens = resultSet.getLong(4),
                            outputTokens = resultSet.getLong(5),
                            cacheCreationTokens = resultSet.getLong(6),
                            cacheReadTokens = resultSet.getLong(7),
                        ),
                    )
                },
                clientKey,
                limit,
            )
        }

    override suspend fun timeline(
        bucket: String,
        fromMilliseconds: Long?,
        toMilliseconds: Long?,
        clientKey: String?,
    ): List<StatsService.TimelinePoint> =
        databaseProvider.execute {
            val bucketMilliseconds = when (bucket) {
                "hour" -> 3_600_000L
                "day" -> 86_400_000L
                else -> throw ApiError(
                    HttpStatus.BAD_REQUEST,
                    "invalid_request_error",
                    "bucket: ожидается hour или day",
                )
            }
            val to = toMilliseconds ?: System.currentTimeMillis()
            val defaultBucketCount = if (bucket == "hour") 7 * 24 else 30
            val from = fromMilliseconds ?: to - defaultBucketCount * bucketMilliseconds
            val keyCondition = if (clientKey != null) " AND client_key = ?" else ""
            jdbcTemplate.query(
                """SELECT (ts / $bucketMilliseconds) * $bucketMilliseconds,
                          COUNT(*),
                          COALESCE(SUM(input_tokens), 0), COALESCE(SUM(output_tokens), 0),
                          COALESCE(SUM(cache_creation_tokens), 0), COALESCE(SUM(cache_read_tokens), 0)
                   FROM usage_event
                   WHERE ts >= ? AND ts <= ?$keyCondition
                   GROUP BY 1
                   ORDER BY 1""",
                { resultSet, _ ->
                    StatsService.TimelinePoint(
                        bucketStartMilliseconds = resultSet.getLong(1),
                        requests = resultSet.getLong(2),
                        inputTokens = resultSet.getLong(3),
                        outputTokens = resultSet.getLong(4),
                        cacheCreationTokens = resultSet.getLong(5),
                        cacheReadTokens = resultSet.getLong(6),
                    )
                },
                *queryArguments(from, to, clientKey),
            )
        }

    /** Эффективный лимит категории: задан или выведен из другой (приоритет месяц → неделя → окно). */
    private data class EffectiveLimit(val tokens: Long, val derived: Boolean)

    private fun windowLimitOf(row: ProviderLimitsRow): EffectiveLimit? = when {
        row.window != null -> EffectiveLimit(row.window, derived = false)
        row.month != null -> EffectiveLimit(Math.round(row.month / WINDOWS_PER_MONTH.toDouble()), derived = true)
        row.week != null -> EffectiveLimit(Math.round(row.week / WINDOWS_PER_WEEK.toDouble()), derived = true)
        else -> null
    }

    private fun weekLimitOf(row: ProviderLimitsRow): EffectiveLimit? = when {
        row.week != null -> EffectiveLimit(row.week, derived = false)
        row.month != null -> EffectiveLimit(Math.round(row.month / WEEKS_PER_MONTH.toDouble()), derived = true)
        row.window != null -> EffectiveLimit(Math.round(row.window * WINDOWS_PER_WEEK.toDouble()), derived = true)
        else -> null
    }

    private fun monthLimitOf(row: ProviderLimitsRow): EffectiveLimit? = when {
        row.month != null -> EffectiveLimit(row.month, derived = false)
        row.week != null -> EffectiveLimit(Math.round(row.week * WEEKS_PER_MONTH.toDouble()), derived = true)
        row.window != null -> EffectiveLimit(row.window * WINDOWS_PER_MONTH, derived = true)
        else -> null
    }

    override suspend fun groupedTimeline(
        bucket: String,
        fromMilliseconds: Long?,
        toMilliseconds: Long?,
        clientKey: String?,
        groupBy: String,
    ): List<StatsService.GroupedTimelinePoint> =
        databaseProvider.execute {
            val bucketMilliseconds = when (bucket) {
                "hour" -> 3_600_000L
                "day" -> 86_400_000L
                else -> throw ApiError(
                    HttpStatus.BAD_REQUEST,
                    "invalid_request_error",
                    "bucket: ожидается hour или day",
                )
            }
            val column = when (groupBy) {
                "model" -> "model"
                "provider" -> "provider"
                else -> throw ApiError(
                    HttpStatus.BAD_REQUEST,
                    "invalid_request_error",
                    "group: ожидается model или provider",
                )
            }
            val to = toMilliseconds ?: System.currentTimeMillis()
            val defaultBucketCount = if (bucket == "hour") 7 * 24 else 30
            val from = fromMilliseconds ?: to - defaultBucketCount * bucketMilliseconds
            val keyCondition = if (clientKey != null) " AND client_key = ?" else ""
            val rawPoints = ArrayList<StatsService.GroupedTimelinePoint>()
            jdbcTemplate.query(
                """SELECT (ts / $bucketMilliseconds) * $bucketMilliseconds, $column, COUNT(*),
                          COALESCE(SUM(input_tokens + output_tokens + cache_creation_tokens + cache_read_tokens), 0)
                   FROM usage_event
                   WHERE ts >= ? AND ts <= ?$keyCondition
                   GROUP BY 1, $column
                   ORDER BY 1, 4 DESC""",
                { resultSet, _ ->
                    rawPoints.add(
                        StatsService.GroupedTimelinePoint(
                            bucketStartMilliseconds = resultSet.getLong(1),
                            label = resultSet.getString(2),
                            requests = resultSet.getLong(3),
                            tokens = resultSet.getLong(4),
                        ),
                    )
                },
                *queryArguments(from, to, clientKey),
            )
            collapseToTopPerBucket(rawPoints)
        }

    /** Топ-N меток на бакет, остальное — в «прочее». */
    private fun collapseToTopPerBucket(rawPoints: List<StatsService.GroupedTimelinePoint>): List<StatsService.GroupedTimelinePoint> {
        val result = ArrayList<StatsService.GroupedTimelinePoint>()
        rawPoints.groupBy { it.bucketStartMilliseconds }.toSortedMap().forEach { (bucketStart, bucketPoints) ->
            val sorted = bucketPoints.sortedByDescending { it.tokens }
            result.addAll(sorted.take(GROUPED_TIMELINE_TOP))
            val otherPoints = sorted.drop(GROUPED_TIMELINE_TOP)
            if (otherPoints.isNotEmpty()) {
                result.add(
                    StatsService.GroupedTimelinePoint(
                        bucketStartMilliseconds = bucketStart,
                        label = OTHER_LABEL,
                        tokens = otherPoints.sumOf { it.tokens },
                        requests = otherPoints.sumOf { it.requests },
                    ),
                )
            }
        }
        return result
    }

    override suspend fun windowBoundaries(
        clientKey: String,
        fromMilliseconds: Long,
        toMilliseconds: Long,
    ): List<StatsService.WindowBoundary> =
        databaseProvider.execute {
            jdbcTemplate.query(
                """SELECT started_at, ends_at FROM usage_window
                   WHERE client_key = ? AND started_at <= ? AND ends_at >= ?
                   ORDER BY started_at""",
                { resultSet, _ ->
                    StatsService.WindowBoundary(
                        startedAtMilliseconds = resultSet.getLong(1),
                        endsAtMilliseconds = resultSet.getLong(2),
                    )
                },
                clientKey,
                toMilliseconds,
                fromMilliseconds,
            )
        }

    override suspend fun providerLimitUsage(): List<StatsService.ProviderLimitUsage> =
        databaseProvider.execute { providerLimitUsageBlocking() }

    private data class ProviderLimitsRow(
        val name: String,
        val window: Long?,
        val week: Long?,
        val month: Long?,
    )

    private fun providerLimitUsageBlocking(): List<StatsService.ProviderLimitUsage> {
        val limitRows = ArrayList<ProviderLimitsRow>()
        jdbcTemplate.query(
            """SELECT name, limit_window_tokens, limit_week_tokens, limit_month_tokens
               FROM provider ORDER BY created_at, id""",
        ) { resultSet ->
            val window = resultSet.getLong(2).takeIf { !resultSet.wasNull() }
            val week = resultSet.getLong(3).takeIf { !resultSet.wasNull() }
            val month = resultSet.getLong(4).takeIf { !resultSet.wasNull() }
            if (window != null || week != null || month != null) {
                limitRows.add(ProviderLimitsRow(resultSet.getString(1), window, week, month))
            }
        }
        val now = System.currentTimeMillis()
        return limitRows.map { limitsRow ->
            val providerWindowBounds = windowService.currentProviderWindow(limitsRow.name)
            val windowUsage = windowLimitOf(limitsRow)?.let { effectiveLimit ->
                val from = providerWindowBounds?.startedAtMilliseconds ?: now
                val to = providerWindowBounds?.endsAtMilliseconds ?: now
                // истекшее окно не расходует лимит: новое начнётся с нуля,
                // поэтому показываем 0 (выработка последнего окна не вводит в заблуждение)
                val windowCurrentlyActive = providerWindowBounds?.active == true
                StatsService.LimitPeriodUsage(
                    limitTokens = effectiveLimit.tokens,
                    spentTokens = if (windowCurrentlyActive) {
                        totalsBetween(from, now, null, limitsRow.name).totalTokens()
                    } else {
                        0
                    },
                    fromMilliseconds = from,
                    toMilliseconds = to,
                    modelTokens = if (windowCurrentlyActive) {
                        modelTokensFor(limitsRow.name, from, now)
                    } else {
                        emptyList()
                    },
                    derived = effectiveLimit.derived,
                )
            }
            val weekUsage = weekLimitOf(limitsRow)?.let { effectiveLimit ->
                val from = now - 7 * 86_400_000L
                StatsService.LimitPeriodUsage(
                    limitTokens = effectiveLimit.tokens,
                    spentTokens = totalsBetween(from, now, null, limitsRow.name).totalTokens(),
                    fromMilliseconds = from,
                    toMilliseconds = now,
                    modelTokens = modelTokensFor(limitsRow.name, from, now),
                    derived = effectiveLimit.derived,
                )
            }
            val monthUsage = monthLimitOf(limitsRow)?.let { effectiveLimit ->
                val from = now - 30 * 86_400_000L
                StatsService.LimitPeriodUsage(
                    limitTokens = effectiveLimit.tokens,
                    spentTokens = totalsBetween(from, now, null, limitsRow.name).totalTokens(),
                    fromMilliseconds = from,
                    toMilliseconds = now,
                    modelTokens = modelTokensFor(limitsRow.name, from, now),
                    derived = effectiveLimit.derived,
                )
            }
            StatsService.ProviderLimitUsage(
                providerName = limitsRow.name,
                window = windowUsage,
                week = weekUsage,
                month = monthUsage,
                windowActive = providerWindowBounds?.active ?: false,
            )
        }
    }

    private fun StatsService.UsageTotals.totalTokens(): Long =
        inputTokens + outputTokens + cacheCreationTokens + cacheReadTokens

    /** Токены по моделям провайдера за период (убывание; нулевые строки —
     *  неудачные попытки fallback — не включаются). */
    private fun modelTokensFor(providerName: String, fromMilliseconds: Long, toMilliseconds: Long): List<StatsService.ModelTokens> =
        jdbcTemplate.query(
            """SELECT model,
                      COALESCE(SUM(input_tokens + output_tokens + cache_creation_tokens + cache_read_tokens), 0)
               FROM usage_event
               WHERE ts >= ? AND ts <= ? AND provider = ?
               GROUP BY model
               HAVING SUM(input_tokens + output_tokens + cache_creation_tokens + cache_read_tokens) > 0
               ORDER BY 2 DESC""",
            { resultSet, _ ->
                StatsService.ModelTokens(
                    modelName = resultSet.getString(1),
                    tokens = resultSet.getLong(2),
                )
            },
            fromMilliseconds,
            toMilliseconds,
            providerName,
        )

    private fun resolveBounds(range: String, clientKey: String?): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        return when (range) {
            RANGE_WINDOW -> {
                if (clientKey == null) {
                    throw ApiError(
                        HttpStatus.BAD_REQUEST,
                        "invalid_request_error",
                        "range=window требует параметр key",
                    )
                }
                val window = activeWindow(clientKey)
                    ?: return now to now
                window.startedAtMilliseconds to now
            }

            RANGE_TODAY -> {
                val zone = ZoneId.systemDefault()
                LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli() to now
            }

            RANGE_7_DAYS -> (now - 7 * 86_400_000L) to now
            RANGE_30_DAYS -> (now - 30 * 86_400_000L) to now
            else -> throw ApiError(
                HttpStatus.BAD_REQUEST,
                "invalid_request_error",
                "range: ожидается window, today, 7d или 30d",
            )
        }
    }

    private fun activeWindow(clientKey: String?): StatsService.WindowSummary? {
        if (clientKey == null) return null
        val windows = jdbcTemplate.query(
            "SELECT started_at, ends_at FROM usage_window " +
                "WHERE client_key = ? AND ends_at > ? ORDER BY started_at DESC LIMIT 1",
            { resultSet, _ ->
                StatsService.WindowSummary(
                    startedAtMilliseconds = resultSet.getLong(1),
                    endsAtMilliseconds = resultSet.getLong(2),
                    totals = StatsService.UsageTotals(0, 0, 0, 0, 0),
                )
            },
            clientKey,
            System.currentTimeMillis(),
        )
        if (windows.isEmpty()) return null
        val window = windows.first()
        return window.copy(
            totals = totalsBetween(window.startedAtMilliseconds, window.endsAtMilliseconds, clientKey),
        )
    }

    private fun totalsBetween(
        fromMilliseconds: Long,
        toMilliseconds: Long,
        clientKey: String?,
        providerName: String? = null,
    ): StatsService.UsageTotals {
        val conditions = buildString {
            append("ts >= ? AND ts <= ?")
            if (clientKey != null) append(" AND client_key = ?")
            if (providerName != null) append(" AND provider = ?")
        }
        val arguments = ArrayList<Any>()
        arguments.add(fromMilliseconds)
        arguments.add(toMilliseconds)
        clientKey?.let { arguments.add(it) }
        providerName?.let { arguments.add(it) }
        return jdbcTemplate.queryForObject(
            """SELECT COUNT(*),
                      COALESCE(SUM(input_tokens), 0), COALESCE(SUM(output_tokens), 0),
                      COALESCE(SUM(cache_creation_tokens), 0), COALESCE(SUM(cache_read_tokens), 0)
               FROM usage_event
               WHERE $conditions""",
            { resultSet, _ ->
                StatsService.UsageTotals(
                    requests = resultSet.getLong(1),
                    inputTokens = resultSet.getLong(2),
                    outputTokens = resultSet.getLong(3),
                    cacheCreationTokens = resultSet.getLong(4),
                    cacheReadTokens = resultSet.getLong(5),
                )
            },
            *arguments.toTypedArray(),
        )!!
    }

    private fun grouped(
        column: String,
        fromMilliseconds: Long,
        toMilliseconds: Long,
        clientKey: String?,
    ): List<StatsService.GroupedUsage> {
        val keyCondition = if (clientKey != null) " AND client_key = ?" else ""
        return jdbcTemplate.query(
            """SELECT $column,
                      COUNT(*),
                      COALESCE(SUM(input_tokens), 0), COALESCE(SUM(output_tokens), 0),
                      COALESCE(SUM(cache_creation_tokens), 0), COALESCE(SUM(cache_read_tokens), 0)
               FROM usage_event
               WHERE ts >= ? AND ts <= ?$keyCondition
               GROUP BY $column
               ORDER BY SUM(input_tokens + output_tokens + cache_read_tokens) DESC""",
            { resultSet, _ ->
                StatsService.GroupedUsage(
                    label = resultSet.getString(1),
                    requests = resultSet.getLong(2),
                    inputTokens = resultSet.getLong(3),
                    outputTokens = resultSet.getLong(4),
                    cacheCreationTokens = resultSet.getLong(5),
                    cacheReadTokens = resultSet.getLong(6),
                )
            },
            *queryArguments(fromMilliseconds, toMilliseconds, clientKey),
        )
    }

    /** Аргументы SQL-запроса без null (для varargs JdbcTemplate). */
    private fun queryArguments(vararg values: Any?): Array<Any> =
        values.filterNotNull().toTypedArray()

    private companion object {
        const val RANGE_WINDOW = "window"
        const val RANGE_TODAY = "today"
        const val RANGE_7_DAYS = "7d"
        const val RANGE_30_DAYS = "30d"

        /** Окно 5ч; неделя 168ч = 33,6 окна; месяц 720ч = 144 окна ≈ 4,29 недели. */
        const val WINDOWS_PER_WEEK = 33.6
        const val WINDOWS_PER_MONTH = 144L
        const val WEEKS_PER_MONTH = 4.29

        const val GROUPED_TIMELINE_TOP = 7
        const val OTHER_LABEL = "прочее"
    }
}
