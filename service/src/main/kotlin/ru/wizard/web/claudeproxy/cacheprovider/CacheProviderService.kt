package ru.wizard.web.claudeproxy.cacheprovider

/**
 * Кэш-провайдер по спеке docs/cache-providers.md (v1.1): внешний сервис
 * (claudeproxycachedb / claudeproxycachegraph) или встроенный local.
 *
 * Реализация обязана быть отказоустойчивой: сбой/таймаут провайдера —
 * тихий промах (null / no-op), никогда не бросает наружу и не добавляет
 * задержку сверх таймаута консультации.
 */
interface CacheProviderService {

    /** Имя провайдера для статистики и привязки (уникально). */
    val name: String

    /**
     * Поиск готового ответа. null — промах или провайдер недоступен;
     * ответ с confidence ниже порога — тоже обычный промах.
     */
    suspend fun lookup(request: LookupRequest): LookupResult?

    /**
     * Асинхронная запись пары «запрос → ответ» (fire-and-forget):
     * ошибки записи не влияют на клиента.
     */
    fun storeAsync(request: StoreRequest)

    /**
     * Неявная оценка ранее выданного ответа (ошибка инструмента /
     * регенерация → минус, спокойное продолжение → плюс).
     * Провайдер волен не поддерживать (no-op).
     */
    suspend fun feedback(answerId: String, positive: Boolean)

    /** Представление провайдера для дашборда (health/stats по спеке). */
    suspend fun status(): Map<String, Any?>

    /** Данные lookup-запроса по спеке. */
    data class LookupRequest(
        val model: String,
        val path: String,
        val requestHash: String,
        val requestJson: String,
        val sessionKey: String?,
    )

    /** Попадание (confidence >= порога прокси). */
    data class LookupResult(
        val answerId: String,
        val responseFormat: String,
        val responseBody: String,
        val confidence: Double,
        val inputTokens: Long,
        val outputTokens: Long,
    )

    /** Данные store-запроса по спеке. */
    data class StoreRequest(
        val model: String,
        val path: String,
        val requestHash: String,
        val requestJson: String,
        val responseFormat: String,
        val responseBody: String,
        val inputTokens: Long,
        val outputTokens: Long,
        val sessionKey: String?,
    )

    companion object {
        /** Имя встроенного провайдера (request-кэш прокси). */
        const val LOCAL_NAME = "local"

        /** Имя настройки провайдера моделей: выбранный кэш-провайдер. */
        const val SETTING_CACHE_PROVIDER = "CACHE_PROVIDER"
    }
}
