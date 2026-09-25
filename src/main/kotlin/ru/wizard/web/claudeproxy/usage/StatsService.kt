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
        /** Кэш-чтения + токены, вырезанные инструментами экономии. */
        val savedTokens: Long = 0,
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
        /** Провайдеры, обслужившие запросы этого окна ключа (для истории окон). */
        val providers: List<String> = emptyList(),
        /** Стоимость токенов окна по тарификации провайдеров; null — цен нет. */
        val costUsd: Double? = null,
    )

    /** Окно провайдера (независимый отсчёт от первого обращения после простоя). */
    data class ProviderWindowSummary(
        val providerName: String,
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

    /** range — пресет; при заданных from/to используется произвольный диапазон. */
    suspend fun byModel(range: String, clientKey: String?, fromMilliseconds: Long?, toMilliseconds: Long?): List<GroupedUsage>

    suspend fun byProvider(range: String, clientKey: String?, fromMilliseconds: Long?, toMilliseconds: Long?): List<GroupedUsage>

    suspend fun byClientKey(range: String): List<GroupedUsage>

    suspend fun windowHistory(clientKey: String, limit: Int): List<WindowSummary>

    /** История окон провайдеров (у каждого свой отсчёт), свежие сверху. */
    suspend fun providerWindowHistory(limit: Int): List<ProviderWindowSummary>

    /** Бакеты hour|day; from/to по умолчанию — последние 7 и 30 дней соответственно. */
    suspend fun timeline(
        bucket: String,
        fromMilliseconds: Long?,
        toMilliseconds: Long?,
        clientKey: String?,
    ): List<TimelinePoint>

    /** Точка срез-таймлайна: токены сущности (модель/провайдер) в бакете. */
    data class GroupedTimelinePoint(
        val bucketStartMilliseconds: Long,
        val label: String,
        val tokens: Long,
        val requests: Long,
    )

    /** Срез таймлайна по моделям или провайдерам: топ-N + «прочее» на бакет. */
    suspend fun groupedTimeline(
        bucket: String,
        fromMilliseconds: Long?,
        toMilliseconds: Long?,
        clientKey: String?,
        groupBy: String,
    ): List<GroupedTimelinePoint>

    data class WindowBoundary(
        val startedAtMilliseconds: Long,
        val endsAtMilliseconds: Long,
    )

    /** 5-часовые окна ключа, пересекающие диапазон (для отметок на графике дня). */
    suspend fun windowBoundaries(
        clientKey: String,
        fromMilliseconds: Long,
        toMilliseconds: Long,
    ): List<WindowBoundary>

    /** Выработка информационных лимитов провайдеров (только заданные категории). */
    data class ModelTokens(val modelName: String, val tokens: Long)

    data class LimitPeriodUsage(
        val limitTokens: Long,
        val spentTokens: Long,
        val fromMilliseconds: Long,
        val toMilliseconds: Long,
        /** Разбивка выработки по моделям провайдера (для графиков к лимиту). */
        val modelTokens: List<ModelTokens>,
        /** true — лимит не задан, выведен из другой категории (только отображение). */
        val derived: Boolean,
    )

    data class ProviderLimitUsage(
        val providerName: String,
        /** 5-часовое окно провайдера (активное или последнее истекшее); null — лимит не задан. */
        val window: LimitPeriodUsage?,
        /** Последние 7 дней; null — лимит не задан. */
        val week: LimitPeriodUsage?,
        /** Последние 30 дней; null — лимит не задан. */
        val month: LimitPeriodUsage?,
        /** true — окно активно прямо сейчас; false — показано последнее истекшее. */
        val windowActive: Boolean,
    )

    /** Только провайдеры, у которых задан хотя бы один лимит. */
    suspend fun providerLimitUsage(): List<ProviderLimitUsage>

    /** Здоровье провайдеров: запросы/ошибки/латентность p50-p95 за диапазон. */
    data class ProviderHealth(
        val providerName: String,
        val requests: Long,
        val failedAttempts: Long,
        val p50DurationMilliseconds: Long,
        val p95DurationMilliseconds: Long,
    )

    /** Неудачная попытка маршрута (для ленты переключений). */
    data class FailedAttempt(
        val timestamp: Long,
        val model: String,
        val providerName: String,
        val status: Int,
        val error: String,
    )

    data class FallbackReport(
        val providers: List<ProviderHealth>,
        val recentFailures: List<FailedAttempt>,
    )

    suspend fun fallbackReport(range: String, clientKey: String?): FallbackReport

    /** Тарификация провайдера: одна цена задана, вторая — расчётная из месячного лимита. */
    data class ProviderCost(
        val providerName: String,
        /** per_million | monthly | "" (не задано). */
        val pricingMode: String,
        /** $ за 1М токенов; null — не определена (нет цены и лимита). */
        val pricePerMillionTokens: Double?,
        /** false = задана, true = выведена из месячной цены и лимита. */
        val pricePerMillionDerived: Boolean,
        /** $ за месяц; null — не определён. */
        val priceMonthly: Double?,
        val priceMonthlyDerived: Boolean,
        /** Токены провайдера за скользящие 7 и 30 дней. */
        val spentTokens7Days: Long,
        val spentTokens30Days: Long,
    )

    /** Провайдеры с тарификацией (цены заданы) + их расход за 7/30 дней. */
    suspend fun providerCosts(): List<ProviderCost>
}
