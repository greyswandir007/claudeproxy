package ru.wizard.web.claudeproxy.proxy.cache.impl

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.TextNode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import ru.wizard.web.claudeproxy.config.ProxyProperties
import ru.wizard.web.claudeproxy.db.DatabaseProvider
import ru.wizard.web.claudeproxy.proxy.cache.RequestCacheService
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Кэш повторяющихся запросов в SQLite (таблица request_cache, V10).
 * Хэш — SHA-256 канонической сериализации тела: сортированные ключи объектов,
 * числа/строки как в исходном JSON, поэтому одинаковые по смыслу тела разных
 * SDK-сериализаций попадают в один ключ, а «точный хэш» остаётся стабильным
 * между запусками (кэш живёт в БД).
 */
@Component
internal class JdbcRequestCacheService(
    private val databaseProvider: DatabaseProvider,
    private val jdbcTemplate: JdbcTemplate,
    private val proxyProperties: ProxyProperties,
) : RequestCacheService {

    private val logger = KotlinLogging.logger {}
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Single-flight: ключ «путь#хэш» → ждущие завершения первого прохода. */
    private val parallelFlights = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /** Счётчики диагностики (M18): с момента старта процесса, в памяти. */
    private val lookupCount = AtomicLong()
    private val hitCount = AtomicLong()
    private val missNoEntryCount = AtomicLong()
    private val missExpiredCount = AtomicLong()
    private val storedCount = AtomicLong()

    override fun buildCacheKey(upstreamPath: String, requestRoot: JsonNode): RequestCacheService.RequestCacheKey {
        // stream и metadata не влияют на содержание ответа — в ключ не входят
        // (см. PLAN.md, бэклог «средние», п.5): стримовый и не-стримовый повтор
        // одного запроса делят одну запись кэша.
        val keyRoot = if (requestRoot.isObject && (requestRoot.has("stream") || requestRoot.has("metadata"))) {
            (requestRoot as com.fasterxml.jackson.databind.node.ObjectNode).deepCopy()
                .apply {
                    remove("stream")
                    remove("metadata")
                }
        } else {
            requestRoot
        }
        val canonicalRequest = canonicalJson(keyRoot)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$upstreamPath\n$canonicalRequest".toByteArray(Charsets.UTF_8))
        return RequestCacheService.RequestCacheKey(
            hash = digest.joinToString(separator = "") { "%02x".format(it) },
            upstreamPath = upstreamPath,
            canonicalRequest = canonicalRequest,
        )
    }

    override suspend fun lookup(cacheKey: RequestCacheService.RequestCacheKey): RequestCacheService.CachedResponse? =
        databaseProvider.execute {
            lookupCount.incrementAndGet()
            // expires_at читаем и сравниваем в коде (а не в SQL), чтобы отличить
            // «строки нет» от «строка протухла» — причина промаха для диагностики.
            val cached = jdbcTemplate.query(
                """SELECT response_body, response_format, model, provider, input_tokens, output_tokens, expires_at
                   FROM request_cache
                   WHERE cache_key = ? AND upstream_path = ?""",
                { resultSet, _ ->
                    RequestCacheService.CachedResponse(
                        responseBody = resultSet.getString("response_body"),
                        responseFormat = RequestCacheService.ResponseFormat.valueOf(resultSet.getString("response_format")),
                        model = resultSet.getString("model"),
                        provider = resultSet.getString("provider"),
                        inputTokens = resultSet.getLong("input_tokens"),
                        outputTokens = resultSet.getLong("output_tokens"),
                    ) to resultSet.getLong("expires_at")
                },
                cacheKey.hash,
                cacheKey.upstreamPath,
            ).firstOrNull()
            when {
                cached == null -> {
                    missNoEntryCount.incrementAndGet()
                    logger.debug { "request cache miss: no entry for path=${cacheKey.upstreamPath}" }
                    null
                }
                cached.second <= System.currentTimeMillis() -> {
                    missExpiredCount.incrementAndGet()
                    logger.debug { "request cache miss: entry expired for path=${cacheKey.upstreamPath}" }
                    null
                }
                else -> {
                    hitCount.incrementAndGet()
                    // LRU: свежепрочитанная строка считается самой недавно использованной.
                    jdbcTemplate.update(
                        "UPDATE request_cache SET last_accessed_at = ? WHERE cache_key = ? AND upstream_path = ?",
                        System.currentTimeMillis(),
                        cacheKey.hash,
                        cacheKey.upstreamPath,
                    )
                    cached.first
                }
            }
        }

    override fun storeAsync(entry: RequestCacheService.CachedEntry) {
        if (entry.timeToLiveMilliseconds <= 0) return
        storeScope.launch {
            try {
                databaseProvider.execute {
                    val now = System.currentTimeMillis()
                    // Ленивая чистка протухшего заодно с каждой записью.
                    jdbcTemplate.update("DELETE FROM request_cache WHERE expires_at <= ?", now)
                    jdbcTemplate.update(
                        """INSERT INTO request_cache
                           (cache_key, upstream_path, request_body, response_body, response_format,
                            model, provider, input_tokens, output_tokens,
                            created_at, expires_at, last_accessed_at)
                           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                           ON CONFLICT(cache_key, upstream_path) DO UPDATE SET
                               request_body = excluded.request_body,
                               response_body = excluded.response_body,
                               response_format = excluded.response_format,
                               model = excluded.model,
                               provider = excluded.provider,
                               input_tokens = excluded.input_tokens,
                               output_tokens = excluded.output_tokens,
                               created_at = excluded.created_at,
                               expires_at = excluded.expires_at,
                               last_accessed_at = excluded.last_accessed_at""",
                        entry.cacheKey.hash,
                        entry.cacheKey.upstreamPath,
                        entry.cacheKey.canonicalRequest,
                        entry.responseBody,
                        entry.responseFormat.name,
                        entry.model,
                        entry.provider,
                        entry.inputTokens,
                        entry.outputTokens,
                        now,
                        now + entry.timeToLiveMilliseconds,
                        now,
                    )
                    // LRU-вытеснение по количеству строк.
                    jdbcTemplate.update(
                        """DELETE FROM request_cache WHERE id NOT IN (
                               SELECT id FROM request_cache
                               ORDER BY last_accessed_at DESC, id DESC
                               LIMIT ?)""",
                        proxyProperties.requestCache.maxRows,
                    )
                }
                storedCount.incrementAndGet()
            } catch (exception: Exception) {
                logger.warn(exception) { "request cache store failed" }
            }
        }
    }

    override suspend fun awaitParallelFlight(cacheKey: RequestCacheService.RequestCacheKey): RequestCacheService.CachedResponse? {
        val flightKey = "${cacheKey.upstreamPath}#${cacheKey.hash}"
        while (true) {
            val registered = CompletableDeferred<Unit>()
            val inFlight = parallelFlights.putIfAbsent(flightKey, registered)
            if (inFlight == null) return null
            inFlight.await()
            val cached = lookup(cacheKey)
            if (cached != null) return cached
            // Параллельный проход не оставил записи (ошибка/отмена) — пробуем стать первым сами.
        }
    }

    override fun endFlight(cacheKey: RequestCacheService.RequestCacheKey) {
        val flightKey = "${cacheKey.upstreamPath}#${cacheKey.hash}"
        parallelFlights.remove(flightKey)?.complete(Unit)
    }

    override fun diagnostics(): RequestCacheService.RequestCacheDiagnostics =
        RequestCacheService.RequestCacheDiagnostics(
            lookups = lookupCount.get(),
            hits = hitCount.get(),
            misses = missNoEntryCount.get() + missExpiredCount.get(),
            missesNoEntry = missNoEntryCount.get(),
            missesExpired = missExpiredCount.get(),
            stored = storedCount.get(),
        )

    /** Каноническая сериализация JsonNode: объекты с сортированными ключами. */
    private fun canonicalJson(node: JsonNode): String = when {
        node.isObject -> {
            val fields = ArrayList<Map.Entry<String, JsonNode>>(node.size())
            node.fields().forEach { fields.add(it) }
            fields.sortBy { it.key }
            fields.joinToString(separator = ",", prefix = "{", postfix = "}") { (name, value) ->
                TextNode.valueOf(name).toString() + ":" + canonicalJson(value)
            }
        }
        node.isArray -> node.joinToString(separator = ",", prefix = "[", postfix = "]") { canonicalJson(it) }
        else -> node.toString()
    }
}
