package ru.wizard.web.claudeproxy.usage

/**
 * Агрегаты статистики использования для дашборда (/api).
 */
interface StatsService {

    data class UsageTotals(
        val requests: Long,
        val inputTokens: Long,
        val outputTokens: Long,
        val cacheCreationTokens: Long,
        val cacheReadTokens: Long,
    )

    data class GroupedUsage(
        val label: String,
        val requests: Long,
        val inputTokens: Long,
        val outputTokens: Long,
        val cacheCreationTokens: Long,
        val cacheReadTokens: Long,
    )

    data class WindowSummary(
        val startedAtMilliseconds: Long,
        val endsAtMilliseconds: Long,
        val totals: UsageTotals,
    )

    data class RangeSummary(
        val range: String,
        val fromMilliseconds: Long,
        val toMilliseconds: Long,
        val totals: UsageTotals,
        val window: WindowSummary?,
    )

    data class TimelinePoint(
        val bucketStartMilliseconds: Long,
        val requests: Long,
        val inputTokens: Long,
        val outputTokens: Long,
        val cacheCreationTokens: Long,
        val cacheReadTokens: Long,
    )

    /**
     * Диапазоны: window (активное окно ключа, требует key), today (с локальной
     * полуночи), 7d и 30d (скользящие).
     */
    suspend fun summary(range: String, clientKey: String?): RangeSummary

    suspend fun byModel(range: String, clientKey: String?): List<GroupedUsage>

    suspend fun byProvider(range: String, clientKey: String?): List<GroupedUsage>

    suspend fun byClientKey(range: String): List<GroupedUsage>

    suspend fun windowHistory(clientKey: String, limit: Int): List<WindowSummary>

    /** Бакеты hour|day; from/to по умолчанию — последние 7 и 30 дней соответственно. */
    suspend fun timeline(
        bucket: String,
        fromMilliseconds: Long?,
        toMilliseconds: Long?,
        clientKey: String?,
    ): List<TimelinePoint>

    /** Выработка информационных лимитов провайдеров (только заданные категории). */
    data class ModelTokens(val modelName: String, val tokens: Long)

    data class LimitPeriodUsage(
        val limitTokens: Long,
        val spentTokens: Long,
        val fromMilliseconds: Long,
        val toMilliseconds: Long,
        /** Разбивка выработки по моделям провайдера (для графиков к лимиту). */
        val modelTokens: List<ModelTokens>,
    )

    data class ProviderLimitUsage(
        val providerName: String,
        /** Текущее 5-часовое окно провайдера; null — лимит не задан. */
        val window: LimitPeriodUsage?,
        /** Последние 7 дней; null — лимит не задан. */
        val week: LimitPeriodUsage?,
        /** Последние 30 дней; null — лимит не задан. */
        val month: LimitPeriodUsage?,
    )

    /** Только провайдеры, у которых задан хотя бы один лимит. */
    suspend fun providerLimitUsage(): List<ProviderLimitUsage>
}
