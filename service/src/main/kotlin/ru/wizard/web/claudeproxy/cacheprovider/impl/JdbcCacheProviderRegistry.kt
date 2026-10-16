package ru.wizard.web.claudeproxy.cacheprovider.impl

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.beans.factory.DisposableBean
import ru.wizard.web.claudeproxy.config.ProxyProperties
import ru.wizard.web.claudeproxy.db.DatabaseProvider
import ru.wizard.web.claudeproxy.cacheprovider.CacheProviderService
import ru.wizard.web.claudeproxy.cacheprovider.HttpCacheProviderService
import ru.wizard.web.claudeproxy.cacheprovider.LocalCacheProviderService
import ru.wizard.web.claudeproxy.proxy.cache.RequestCacheService
import ru.wizard.web.claudeproxy.serverevent.ServerEventRecorder
import java.util.concurrent.ConcurrentHashMap

/** Представление провайдера кэша для дашборда. */
data class CacheProviderView(
    val id: Long,
    val name: String,
    val url: String,
    val enabled: Boolean,
)

/** Запрос создания/обновления провайдера кэша. */
data class CacheProviderRequest(
    val name: String,
    val url: String,
    val enabled: Boolean = true,
)

/**
 * Реестр кэш-провайдеров: встроенный local (request-кэш прокси) +
 * внешние сервисы из таблицы cache_provider (V21), подключаемые по URL.
 *
 * Экземпляры HTTP-клиентов кэшируются по имени и пересоздаются при
 * изменении записи; выбор провайдера у модели — по имени из
 * provider_setting (ключ CACHE_PROVIDER), умолчание — local.
 */
@Service
class JdbcCacheProviderRegistry(
    private val jdbcTemplate: JdbcTemplate,
    private val databaseProvider: DatabaseProvider,
    requestCacheService: RequestCacheService,
    private val proxyProperties: ProxyProperties,
    private val objectMapper: ObjectMapper,
    private val serverEventRecorder: ServerEventRecorder,
) : DisposableBean {

    private val localProvider = LocalCacheProviderService(requestCacheService)
    private val httpProviders = ConcurrentHashMap<String, HttpCacheProviderService>()

    /** Снимок внешних провайдеров (правки редки — кэш в памяти). */
    @Volatile
    private var cachedViews: List<CacheProviderView>? = null

    /** Порог уверенности выдачи внешнего кэша (пока константа). */
    private val minConfidence = 0.5

    /** Провайдер по имени или null, если внешнего с таким именем нет
     *  (local не возвращается — это «нет внешнего»). */
    suspend fun providerOrNull(name: String): CacheProviderService? {
        val view = views().firstOrNull { it.name == name && it.enabled } ?: return null
        return httpProviders.computeIfAbsent(view.name) {
            HttpCacheProviderService(
                name = view.name,
                url = view.url,
                objectMapper = objectMapper,
                timeouts = proxyProperties.cacheProviders,
                minConfidence = minConfidence,
                serverEventRecorder = serverEventRecorder,
            )
        }
    }

    /** Первый включённый внешний провайдер (имя + сервис) или null. */
    suspend fun firstEnabledExternal(): Pair<String, CacheProviderService>? {
        val view = views().firstOrNull { it.enabled } ?: return null
        return view.name to provider(view.name)
    }

    /** Провайдер по имени; неизвестный/выключенный внешний → local. */
    suspend fun provider(name: String?): CacheProviderService {
        if (name == null || name == CacheProviderService.LOCAL_NAME) return localProvider
        val view = views().firstOrNull { it.name == name && it.enabled } ?: return localProvider
        return httpProviders.computeIfAbsent(view.name) {
            HttpCacheProviderService(
                name = view.name,
                url = view.url,
                objectMapper = objectMapper,
                timeouts = proxyProperties.cacheProviders,
                minConfidence = minConfidence,
                serverEventRecorder = serverEventRecorder,
            )
        }
    }

    /** Все внешние провайдеры (из кэша памяти). */
    suspend fun list(): List<CacheProviderView> = views()

    private suspend fun views(): List<CacheProviderView> =
        cachedViews ?: databaseProvider.execute { listBlocking() }.also { cachedViews = it }

    suspend fun create(request: CacheProviderRequest): CacheProviderView = databaseProvider.execute {
        ensureTable()
        val now = System.currentTimeMillis()
        jdbcTemplate.update(
            "INSERT INTO cache_provider (name, url, enabled, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
            request.name,
            request.url,
            if (request.enabled) 1 else 0,
            now,
            now,
        )
        invalidate()
        listBlocking().first { it.name == request.name }
    }

    suspend fun update(id: Long, request: CacheProviderRequest): CacheProviderView = databaseProvider.execute {
        ensureTable()
        jdbcTemplate.update(
            "UPDATE cache_provider SET name = ?, url = ?, enabled = ?, updated_at = ? WHERE id = ?",
            request.name,
            request.url,
            if (request.enabled) 1 else 0,
            System.currentTimeMillis(),
            id,
        )
        httpProviders.remove(request.name) // пересоздать клиент с новым URL
        invalidate()
        listBlocking().firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("провайдер кэша не найден: $id")
    }

    suspend fun delete(id: Long): Boolean = databaseProvider.execute {
        ensureTable()
        val removed = listBlocking().firstOrNull { it.id == id }
        val deleted = jdbcTemplate.update("DELETE FROM cache_provider WHERE id = ?", id) > 0
        if (deleted && removed != null) {
            httpProviders.remove(removed.name)
        }
        invalidate()
        deleted
    }

    /** Сброс кэша списка (после правок из дашборда). */
    private fun invalidate() {
        cachedViews = null
    }

    /** Встроенный local — для статистики на дашборде. */
    fun local(): CacheProviderService = localProvider

    override fun destroy() {
        httpProviders.values.forEach { it.destroy() }
        httpProviders.clear()
    }

    private fun listBlocking(): List<CacheProviderView> {
        ensureTable()
        return jdbcTemplate.query(
            "SELECT id, name, url, enabled FROM cache_provider ORDER BY name",
        ) { resultSet, _ ->
            CacheProviderView(
                id = resultSet.getLong(1),
                name = resultSet.getString(2),
                url = resultSet.getString(3),
                enabled = resultSet.getInt(4) != 0,
            )
        }
    }

    /** Дубликат миграции: бины могут стартовать раньше Flyway. */
    private fun ensureTable() {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS cache_provider (
              id          INTEGER PRIMARY KEY AUTOINCREMENT,
              name        TEXT NOT NULL UNIQUE,
              url         TEXT NOT NULL,
              enabled     INTEGER NOT NULL DEFAULT 1,
              created_at  INTEGER NOT NULL,
              updated_at  INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }
}
