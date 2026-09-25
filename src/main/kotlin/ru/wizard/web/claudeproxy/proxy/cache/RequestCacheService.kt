package ru.wizard.web.claudeproxy.proxy.cache

import com.fasterxml.jackson.databind.JsonNode
import ru.wizard.web.claudeproxy.routing.ModelRegistry

/**
 * Кэш повторяющихся запросов: точный хэш тела запроса → сохранённый ответ
 * без похода к провайдеру (см. V10 и ProviderSettingCatalog.REQUEST_CACHE_TTL_MS).
 * Повторы бесплатны: вызывающий код пишет usage_event с provider='cache'
 * и saved_tokens = полный объём закэшированного ответа.
 */
interface RequestCacheService {

    /** Формат сохранённого ответа: полный JSON или склеенный SSE-транскрипт. */
    enum class ResponseFormat { JSON, SSE }

    /**
     * Ключ кэша: хэш канонической сериализации тела (сортированные ключи JSON)
     * вместе с путём апстрима. Поля `stream` и `metadata` в ключ не входят:
     * стримовый и не-стримовый повтор одного запроса делят одну запись.
     * Считается по оригинальному телу клиента ДО пер-маршрутных мутаций
     * (ProviderRequestAdjuster / TokenSavingAdjuster).
     */
    data class RequestCacheKey(
        val hash: String,
        val upstreamPath: String,
        /** Каноническое тело запроса — хранится в кэше для отладки и контроля. */
        val canonicalRequest: String,
    )

    /** Сохранённый ответ кэша для повтора. */
    data class CachedResponse(
        val responseBody: String,
        val responseFormat: ResponseFormat,
        val model: String,
        val provider: String,
        val inputTokens: Long,
        val outputTokens: Long,
    )

    /** Новый элемент кэша: ответ успешного прохода к провайдеру. */
    data class CachedEntry(
        val cacheKey: RequestCacheKey,
        val responseBody: String,
        val responseFormat: ResponseFormat,
        val model: String,
        val provider: String,
        val inputTokens: Long,
        val outputTokens: Long,
        /** Время жизни строки, мс; <= 0 — не кэшировать (кэш выключен). */
        val timeToLiveMilliseconds: Long,
    )

    /** Строит ключ кэша по пути и телу запроса (без обращения к БД). */
    fun buildCacheKey(upstreamPath: String, requestRoot: JsonNode): RequestCacheKey

    /** Живой ответ из кэша или null (нет строки или истёк expires_at). */
    suspend fun lookup(cacheKey: RequestCacheKey): CachedResponse?

    /**
     * Асинхронно записывает ответ в кэш (fire-and-forget, как AsyncUsageRecorder):
     * чистит устаревшие строки и вытесняет лишние по LRU.
     */
    fun storeAsync(entry: CachedEntry)

    /**
     * Single-flight против «шторма одинаковых запросов»: если параллельный
     * проход с тем же ключом уже идёт — ждёт его завершения и возвращает
     * его результат из кэша; null означает «иди к провайдеру сам»: либо
     * прохода нет и текущий зарегистрирован как первый (после завершения
     * обязателен [endFlight]), либо параллельный проход завершился, ничего
     * не записав (ошибка/отмена) — тогда повторный вызов сам станет первым.
     */
    suspend fun awaitParallelFlight(cacheKey: RequestCacheKey): CachedResponse?

    /** Отмечает завершение «платного прохода» (разбуживает ждущих). */
    fun endFlight(cacheKey: RequestCacheKey)

    companion object {
        /** Ключ настройки провайдера: TTL кэша, мс (0 = выключен). */
        const val SETTING_TIME_TO_LIVE_MILLISECONDS = "REQUEST_CACHE_TTL_MS"

        /** TTL по умолчанию, если у провайдера нет оверрайда: 10 минут. */
        const val DEFAULT_TIME_TO_LIVE_MILLISECONDS = 600_000L

        /** Имя псевдо-провайдера в usage_event для повторов из кэша. */
        const val CACHE_PROVIDER_NAME = "cache"

        /** TTL кэша провайдера с учётом оверрайда REQUEST_CACHE_TTL_MS. */
        fun timeToLiveMilliseconds(provider: ModelRegistry.ProviderInfo): Long =
            provider.settingOverrides[SETTING_TIME_TO_LIVE_MILLISECONDS]
                ?.trim()?.toLongOrNull()
                ?: DEFAULT_TIME_TO_LIVE_MILLISECONDS
    }
}
