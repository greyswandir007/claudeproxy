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
    /** Ключ кэша запроса. */
    data class RequestCacheKey(
        /** SHA-256 канонического тела. */
        val hash: String,
        /** Путь к апстриму (/v1/messages или /v1/messages/count_tokens). */
        val upstreamPath: String,
        /** Каноническое тело запроса — хранится в кэше для отладки и контроля. */
        val canonicalRequest: String,
        /** Публичное имя модели из тела клиента — для диагностики по моделям. */
        val model: String,
    )

    /** Сохранённый ответ кэша для повтора. */
    /** Ответ из кэша для релея клиенту. */
    data class CachedResponse(
        /** Тело ответа (SSE-поток или JSON). */
        val responseBody: String,
        /** Формат тела (управляет Content-Type ответа). */
        val responseFormat: ResponseFormat,
        /** Модель ответа (для usage-записи). */
        val model: String,
        /** Провайдер исходного ответа. */
        val provider: String,
        /** Входные токены исходного ответа. */
        val inputTokens: Long,
        /** Выходные токены исходного ответа. */
        val outputTokens: Long,
    )

    /** Новый элемент кэша: ответ успешного прохода к провайдеру. */
    /** Строка кэша целиком (ответ + TTL) для дашборда. */
    data class CachedEntry(
        /** Ключ строки. */
        val cacheKey: RequestCacheKey,
        /** Тело ответа (SSE-поток или JSON). */
        val responseBody: String,
        /** Формат тела. */
        val responseFormat: ResponseFormat,
        /** Модель ответа. */
        val model: String,
        /** Провайдер исходного ответа. */
        val provider: String,
        /** Входные токены исходного ответа. */
        val inputTokens: Long,
        /** Выходные токены исходного ответа. */
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

    /**
     * Диагностика кэша повторов: счётчики с момента старта процесса (в памяти,
     * не персистентны). misses = missesNoEntry + missesExpired.
     */
    /** Счётчики кэша запросов для карточки дашборда. */
    data class RequestCacheDiagnostics(
        /** Всего обращений к кэшу. */
        val lookups: Long,
        /** Попаданий. */
        val hits: Long,
        /** Промахов (любого рода). */
        val misses: Long,
        /** Промах: строки с таким ключом в таблице нет вовсе. */
        val missesNoEntry: Long,
        /** Промах: строка есть, но expires_at уже прошёл. */
        val missesExpired: Long,
        /** Записано новых ответов (кэш включён и проход успешен). */
        val stored: Long,
        /** Те же счётчики в разрезе публичных моделей. */
        val perModel: List<RequestCacheModelDiagnostics>,
    )

    /** Счётчики диагностики кэша по одной публичной модели. */
    data class RequestCacheModelDiagnostics(
        val model: String,
        val lookups: Long,
        val hits: Long,
        val misses: Long,
        val missesNoEntry: Long,
        val missesExpired: Long,
        val stored: Long,
    )

    /** Снимок счётчиков диагностики кэша. */
    fun diagnostics(): RequestCacheDiagnostics

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
