package ru.wizard.web.claudeproxy.optimizer.impl

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.beans.factory.InitializingBean
import org.springframework.core.env.Environment
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import ru.wizard.web.claudeproxy.config.EnvironmentReferenceResolver
import ru.wizard.web.claudeproxy.config.ProxyProperties
import ru.wizard.web.claudeproxy.db.DatabaseProvider
import ru.wizard.web.claudeproxy.optimizer.OptimizerService
import ru.wizard.web.claudeproxy.providers.UpstreamWebClientFactory
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Реализация OptimizerService: сжатие старых tool_result моделью одного из
 * подключённых провайдеров (выбор — в optimizer_config, меняется из дашборда).
 * Провайдер читается из БД на каждый пакет сжатия — срез всегда свежий.
 * Устойчивость: жёсткие таймауты, breaker на последовательных отказах, откат
 * на маркер M11 при любой проблеме — ошибки наружу не бросаются.
 */
@Service
class JdbcOptimizerService(
    private val jdbcTemplate: JdbcTemplate,
    private val databaseProvider: DatabaseProvider,
    private val proxyProperties: ProxyProperties,
    private val webClientFactory: UpstreamWebClientFactory,
    private val environment: Environment,
    private val objectMapper: ObjectMapper,
) : OptimizerService, InitializingBean {

    private val logger = KotlinLogging.logger {}

    /** Параметры вызова — фиксируются на старте (см. application.example.yml). */
    private val optimizerProperties = proxyProperties.optimizer

    /** Снимок настройки optimizer_config; обновляется на старте и из updateConfig. */
    @Volatile
    private var currentConfig: OptimizerService.OptimizerConfig =
        OptimizerService.OptimizerConfig(enabled = false, providerName = null, model = null)

    /** LRU-кэш сжатий: SHA-256 контента → сжатый текст | NOT_COMPRESSIBLE. */
    private val compressionCache = LinkedHashMap<String, Any>(16, 0.75f, true)

    /** Single-flight: не сжимать один контент параллельно дважды. */
    private val inFlightCompressions = ConcurrentHashMap<String, CompletableDeferred<Any>>()

    private val breakerConsecutiveFailures = AtomicInteger()
    @Volatile
    private var breakerOpenUntilMillis = 0L

    private val requestsTotal = AtomicLong()
    private val cacheHitsTotal = AtomicLong()
    private val compressionsTotal = AtomicLong()
    private val notCompressedTotal = AtomicLong()
    private val fallbacksTotal = AtomicLong()
    private val failuresTotal = AtomicLong()
    private val charactersBeforeTotal = AtomicLong()
    private val charactersAfterTotal = AtomicLong()
    private val modelTokensSpentTotal = AtomicLong()
    private val latencyTotalMilliseconds = AtomicLong()
    private val latencyMaxMilliseconds = AtomicLong()
    @Volatile
    private var lastError: String? = null

    /** Строка-провайдер из БД (api_key ещё не резолвирован — ссылка ${ENV:...}). */
    private data class ProviderRow(
        val name: String,
        val type: String,
        val baseUrl: String,
        val apiKeyReference: String,
        val authType: String,
        val extraHeaders: Map<String, String>,
        val proxyName: String?,
    )

    override fun afterPropertiesSet() {
        ensureTable()
        loadConfig()
    }

    override fun isAvailable(): Boolean =
        currentConfig.enabled &&
            !currentConfig.providerName.isNullOrBlank() &&
            !currentConfig.model.isNullOrBlank() &&
            System.currentTimeMillis() >= breakerOpenUntilMillis

    override suspend fun compressToolResults(texts: List<String>): List<OptimizerService.CompressionResult> {
        if (texts.isEmpty()) return emptyList()
        requestsTotal.addAndGet(texts.size.toLong())
        if (!isAvailable()) {
            fallbacksTotal.addAndGet(texts.size.toLong())
            return markerResults(texts)
        }
        val provider = providerRow(currentConfig.providerName!!)
        if (provider == null || provider.authType != "api_key" || provider.baseUrl.isBlank()) {
            fallbacksTotal.addAndGet(texts.size.toLong())
            lastError = "optimizer provider '${currentConfig.providerName}' is not available"
            return markerResults(texts)
        }
        return try {
            withTimeout(
                optimizerProperties.maxCompressionsPerRequest *
                    optimizerProperties.requestTimeoutMilliseconds + 2_000,
            ) {
                compressBatch(texts, provider)
            }
        } catch (exception: TimeoutCancellationException) {
            // бюджет батча исчерпан — запрос не должен страдать, все блоки маркером
            fallbacksTotal.addAndGet(texts.size.toLong())
            failuresTotal.incrementAndGet()
            registerFailure("optimizer batch timeout")
            markerResults(texts)
        } catch (exception: CancellationException) {
            // внешняя отмена (клиент ушёл) — наверх, как есть
            throw exception
        } catch (exception: Exception) {
            fallbacksTotal.addAndGet(texts.size.toLong())
            failuresTotal.incrementAndGet()
            registerFailure("optimizer batch failed: ${exception.message}")
            markerResults(texts)
        }
    }

    override fun config(): OptimizerService.OptimizerConfig = currentConfig

    override suspend fun updateConfig(request: OptimizerService.OptimizerConfigRequest) {
        if (request.enabled) {
            val providerName = request.providerName?.trim().orEmpty()
            if (providerName.isEmpty() || request.model.isNullOrBlank()) {
                throw IllegalArgumentException("enabled optimizer requires provider and model")
            }
            val provider = providerRow(providerName)
                ?: throw IllegalArgumentException("provider '$providerName' is not registered")
            if (provider.authType != "api_key") {
                throw IllegalArgumentException(
                    "provider '$providerName' uses ${provider.authType} auth; optimizer supports api-key providers only",
                )
            }
        }
        val updatedAt = System.currentTimeMillis()
        databaseProvider.execute {
            jdbcTemplate.update(
                UPSERT_CONFIG_SQL,
                if (request.enabled) 1 else 0,
                request.providerName?.trim()?.takeIf { it.isNotEmpty() },
                request.model?.trim()?.takeIf { it.isNotEmpty() },
                updatedAt,
            )
        }
        loadConfig()
        // смена провайдера/модели сбрасывает breaker и статистику латентности
        breakerConsecutiveFailures.set(0)
        breakerOpenUntilMillis = 0L
        logger.info {
            "Optimizer config updated: enabled=${currentConfig.enabled}, " +
                "provider=${currentConfig.providerName}, model=${currentConfig.model}"
        }
    }

    override fun stats(): OptimizerService.OptimizerStats {
        val compressions = compressionsTotal.get()
        return OptimizerService.OptimizerStats(
            enabled = currentConfig.enabled,
            providerName = currentConfig.providerName,
            model = currentConfig.model,
            circuitState = circuitState(),
            requests = requestsTotal.get(),
            cacheHits = cacheHitsTotal.get(),
            compressions = compressions,
            notCompressed = notCompressedTotal.get(),
            fallbacks = fallbacksTotal.get(),
            failures = failuresTotal.get(),
            charactersBefore = charactersBeforeTotal.get(),
            charactersAfter = charactersAfterTotal.get(),
            estimatedTokensSaved = (charactersBeforeTotal.get() - charactersAfterTotal.get()) / 4,
            modelTokensSpent = modelTokensSpentTotal.get(),
            averageLatencyMilliseconds =
                if (compressions == 0L) 0L else latencyTotalMilliseconds.get() / compressions,
            maxLatencyMilliseconds = latencyMaxMilliseconds.get(),
            lastError = lastError,
        )
    }

    private fun circuitState(): String {
        if (!currentConfig.enabled) return "OFF"
        val failures = breakerConsecutiveFailures.get()
        if (failures >= BREAKER_FAILURE_THRESHOLD) {
            return if (System.currentTimeMillis() < breakerOpenUntilMillis) "OPEN" else "PROBE"
        }
        return "CLOSED"
    }

    private suspend fun compressBatch(
        texts: List<String>,
        provider: ProviderRow,
    ): List<OptimizerService.CompressionResult> {
        val results = ArrayList<OptimizerService.CompressionResult>(texts.size)
        var remainingBudget = optimizerProperties.maxCompressionsPerRequest
        for (text in texts) {
            if (text.isBlank() || text.length > optimizerProperties.maxInputCharacters) {
                // пустое и сверхдлинное модель не увидит — сразу маркер, без вызова
                notCompressedTotal.incrementAndGet()
                results.add(markerResult(text))
                continue
            }
            if (remainingBudget <= 0) {
                fallbacksTotal.incrementAndGet()
                results.add(markerResult(text))
                continue
            }
            when (val cached = cachedCompression(text)) {
                null -> {
                    remainingBudget--
                    results.add(compressSingle(text, provider))
                }
                NOT_COMPRESSIBLE -> {
                    cacheHitsTotal.incrementAndGet()
                    notCompressedTotal.incrementAndGet()
                    results.add(markerResult(text))
                }
                else -> {
                    cacheHitsTotal.incrementAndGet()
                    results.add(compressedResult(text, cached as String))
                }
            }
        }
        return results
    }

    private suspend fun compressSingle(
        text: String,
        provider: ProviderRow,
    ): OptimizerService.CompressionResult {
        // single-flight: параллельный запрос с тем же контентом ждёт наш результат
        val deferred = CompletableDeferred<Any>()
        val existing = inFlightCompressions.putIfAbsent(cacheKey(text), deferred)
        if (existing != null) {
            val value = existing.await()
            return when (value) {
                NOT_COMPRESSIBLE -> markerResult(text).also { cacheHitsTotal.incrementAndGet() }
                FAILURE -> markerResult(text)
                else -> compressedResult(text, value as String)
            }
        }
        try {
            val startedAt = System.nanoTime()
            val outcome = callModel(text, provider)
            val latencyMilliseconds = (System.nanoTime() - startedAt) / 1_000_000
            when {
                outcome == null -> {
                    failuresTotal.incrementAndGet()
                    fallbacksTotal.incrementAndGet()
                    deferred.complete(FAILURE)
                    return markerResult(text)
                }
                outcome.compressed.length >= text.length * NOT_COMPRESSIBLE_RATIO -> {
                    notCompressedTotal.incrementAndGet()
                    storeCachedCompression(text, NOT_COMPRESSIBLE)
                    deferred.complete(NOT_COMPRESSIBLE)
                    return markerResult(text)
                }
                else -> {
                    compressionsTotal.incrementAndGet()
                    charactersBeforeTotal.addAndGet(text.length.toLong())
                    charactersAfterTotal.addAndGet(outcome.compressed.length.toLong())
                    modelTokensSpentTotal.addAndGet(outcome.tokensSpent)
                    latencyTotalMilliseconds.addAndGet(latencyMilliseconds)
                    latencyMaxMilliseconds.getAndUpdate { current -> maxOf(current, latencyMilliseconds) }
                    storeCachedCompression(text, outcome.compressed)
                    deferred.complete(outcome.compressed)
                    registerSuccess()
                    return compressedResult(text, outcome.compressed)
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            failuresTotal.incrementAndGet()
            fallbacksTotal.incrementAndGet()
            registerFailure("optimizer call failed: ${exception.message}")
            deferred.complete(FAILURE)
            return markerResult(text)
        } finally {
            inFlightCompressions.remove(cacheKey(text))
            // страховка: ждущие single-flight участники не должны зависнуть
            deferred.complete(FAILURE)
        }
    }

    /** Один вызов модели; null — любая проблема (HTTP, таймаут, пустой ответ). */
    private suspend fun callModel(text: String, provider: ProviderRow): ModelOutcome? {
        return try {
            val requestJson = buildRequestJson(provider, text)
            // таймаут вызова: у клиентов фабрики нет коннекторного responseTimeout,
            // поэтому режем здесь (плюс общий бюджет батча в compressToolResults)
            val responseBody = withTimeout(optimizerProperties.requestTimeoutMilliseconds + 1_000) {
                webClientFactory.webClient(provider.proxyName)
                    .post()
                    .uri(URI.create(provider.baseUrl.trimEnd('/') + completionPath(provider)))
                    .headers { headers -> applyAuthHeaders(headers, provider) }
                    .bodyValue(requestJson)
                    .retrieve()
                    .onStatus({ it.isError }) { response ->
                        response.bodyToMono(String::class.java)
                            .defaultIfEmpty("")
                            .map { body ->
                                IllegalStateException(
                                    "optimizer provider error ${response.statusCode().value()}: ${body.take(200)}",
                                )
                            }
                    }
                    .bodyToMono(String::class.java)
                    .awaitSingle()
            }
            parseModelOutcome(responseBody, provider)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            registerFailure("optimizer call failed: ${exception.message}")
            null
        }
    }

    private fun buildRequestJson(provider: ProviderRow, text: String): String {
        val root = objectMapper.createObjectNode()
        root.put("model", currentConfig.model)
        root.put("temperature", 0.0)
        root.put("max_tokens", optimizerProperties.maxCompletionTokens)
        root.put("stream", false)
        val wrappedContent = "<tool_output>\n$text\n</tool_output>"
        if (provider.type == "anthropic") {
            root.put("system", SYSTEM_PROMPT)
            root.putArray("messages").addObject().apply {
                put("role", "user")
                putArray("content").addObject().apply {
                    put("type", "text")
                    put("text", wrappedContent)
                }
            }
        } else {
            val messages = root.putArray("messages")
            messages.addObject().apply {
                put("role", "system")
                put("content", SYSTEM_PROMPT)
            }
            messages.addObject().apply {
                put("role", "user")
                put("content", wrappedContent)
            }
        }
        return objectMapper.writeValueAsString(root)
    }

    /** Ответ модели; null при пустом выводе или обрезке по лимиту токенов. */
    private fun parseModelOutcome(responseBody: String, provider: ProviderRow): ModelOutcome? {
        val root = runCatching { objectMapper.readTree(responseBody) }.getOrNull() ?: return null
        if (provider.type == "anthropic") {
            val content = root.path("content").firstOrNull()?.path("text")?.asText()
            if (root.path("stop_reason").asText() == "max_tokens") return null
            val tokensSpent = root.path("usage").path("input_tokens").asLong(0) +
                root.path("usage").path("output_tokens").asLong(0)
            return content?.takeIf { it.isNotBlank() }?.let { ModelOutcome(it, tokensSpent) }
        }
        val choice = root.path("choices").firstOrNull() ?: return null
        if (choice.path("finish_reason").asText() == "length") return null
        val content = choice.path("message").path("content").asText()
        val tokensSpent = root.path("usage").path("prompt_tokens").asLong(0) +
            root.path("usage").path("completion_tokens").asLong(0)
        return content.takeIf { it.isNotBlank() }?.let { ModelOutcome(it, tokensSpent) }
    }

    private fun completionPath(provider: ProviderRow): String =
        if (provider.type == "anthropic") "/v1/messages" else "/chat/completions"

    private fun applyAuthHeaders(headers: HttpHeaders, provider: ProviderRow) {
        val apiKey = EnvironmentReferenceResolver.resolve(environment, provider.apiKeyReference)
        if (provider.type == "anthropic") {
            headers.set("x-api-key", apiKey)
            headers.set("anthropic-version", "2023-06-01")
        } else {
            headers.setBearerAuth(apiKey)
        }
        provider.extraHeaders.forEach { (name, value) -> headers.set(name, value) }
    }

    private fun providerRow(name: String): ProviderRow? {
        return try {
            jdbcTemplate.queryForObject(
                """SELECT name, type, base_url, api_key, auth_type, extra_headers, proxy_name
                   FROM provider WHERE name = ?""",
                { resultSet, _ ->
                    ProviderRow(
                        name = resultSet.getString("name"),
                        type = resultSet.getString("type").ifBlank { "openai" },
                        baseUrl = resultSet.getString("base_url"),
                        apiKeyReference = resultSet.getString("api_key").orEmpty(),
                        authType = resultSet.getString("auth_type").ifBlank { "api_key" },
                        extraHeaders = parseExtraHeaders(resultSet.getString("extra_headers")),
                        proxyName = resultSet.getString("proxy_name")?.takeIf { it.isNotBlank() },
                    )
                },
                name,
            )
        } catch (exception: org.springframework.dao.EmptyResultDataAccessException) {
            null
        }
    }

    private fun parseExtraHeaders(extraHeadersJson: String?): Map<String, String> {
        if (extraHeadersJson.isNullOrBlank()) return emptyMap()
        return runCatching {
            objectMapper.readValue(extraHeadersJson, Map::class.java)
        }.getOrDefault(emptyMap<Any, Any>()).entries.associate { (key, value) ->
            key.toString() to value.toString()
        }
    }

    /** Создаёт таблицу настройки при первом обращении (идемпотентно, до миграций:
     *  оптимизатор поднимается раньше раннера миграций на свежей базе). */
    private fun ensureTable() {
        try {
            jdbcTemplate.execute(CREATE_TABLE_SQL)
        } catch (exception: Exception) {
            logger.warn(exception) { "Failed to ensure optimizer_config table" }
        }
    }

    private fun loadConfig() {
        currentConfig = try {
            val row = jdbcTemplate.queryForMap(SELECT_CONFIG_SQL)
            OptimizerService.OptimizerConfig(
                enabled = (row["enabled"] as Number).toInt() == 1,
                providerName = row["provider_name"] as? String,
                model = row["model"] as? String,
            )
        } catch (exception: org.springframework.dao.EmptyResultDataAccessException) {
            OptimizerService.OptimizerConfig(enabled = false, providerName = null, model = null)
        }
        if (currentConfig.enabled) {
            logger.info {
                "Optimizer enabled: provider=${currentConfig.providerName}, model=${currentConfig.model}"
            }
        }
    }

    private fun cacheKey(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun cachedCompression(text: String): Any? = synchronized(compressionCache) {
        compressionCache[cacheKey(text)]
    }

    private fun storeCachedCompression(text: String, value: Any) = synchronized(compressionCache) {
        compressionCache[cacheKey(text)] = value
        while (compressionCache.size > optimizerProperties.maxCacheEntries) {
            val eldest = compressionCache.keys.iterator().next()
            compressionCache.remove(eldest)
        }
    }

    private fun registerSuccess() {
        breakerConsecutiveFailures.set(0)
    }

    private fun registerFailure(message: String) {
        lastError = message.take(300)
        logger.warn { message }
        if (breakerConsecutiveFailures.incrementAndGet() >= BREAKER_FAILURE_THRESHOLD) {
            breakerOpenUntilMillis = System.currentTimeMillis() + BREAKER_OPEN_MILLISECONDS
            logger.warn {
                "Optimizer circuit breaker OPEN for ${BREAKER_OPEN_MILLISECONDS / 1000}s " +
                    "after $BREAKER_FAILURE_THRESHOLD consecutive failures"
            }
        }
    }

    private fun markerResult(text: String): OptimizerService.CompressionResult =
        OptimizerService.CompressionResult(
            compressedText = null,
            originalCharacters = text.length,
            compressedCharacters = 0,
        )

    private fun compressedResult(text: String, compressed: String): OptimizerService.CompressionResult =
        OptimizerService.CompressionResult(
            compressedText = compressed,
            originalCharacters = text.length,
            compressedCharacters = compressed.length,
        )

    private fun markerResults(texts: List<String>): List<OptimizerService.CompressionResult> =
        texts.map { markerResult(it) }

    /** Успешный ответ модели: сжатый текст и суммарный расход токенов вызова. */
    private data class ModelOutcome(val compressed: String, val tokensSpent: Long)

    companion object {
        /** Промпт сжатия: короткий пересказ, идентификаторы/пути/ошибки дословно. */
        private const val SYSTEM_PROMPT =
            "You compress tool outputs from a coding assistant conversation. " +
                "Rewrite the tool output as a much shorter summary " +
                "(aim for at most one quarter of the original length) that preserves " +
                "everything a software engineer may need later: commands, file paths, " +
                "identifiers, error messages, key numbers and their values. " +
                "Keep such verbatim elements exactly as written. " +
                "Do not add commentary. Output only the compressed text."

        /** Ответ короче 80% исходника считается бесполезным — блок уходит маркером. */
        private const val NOT_COMPRESSIBLE_RATIO = 0.8

        private const val BREAKER_FAILURE_THRESHOLD = 3
        private const val BREAKER_OPEN_MILLISECONDS = 60_000L

        private val NOT_COMPRESSIBLE = Any()
        private val FAILURE = Any()

        private val SELECT_CONFIG_SQL =
            "SELECT enabled, provider_name, model FROM optimizer_config WHERE id = 1"

        /** Дубликат V17__optimizer_config.sql (BIGINT вместим и в sqlite, и в postgres). */
        private const val CREATE_TABLE_SQL =
            """CREATE TABLE IF NOT EXISTS optimizer_config (
                 id INTEGER PRIMARY KEY CHECK (id = 1),
                 enabled INTEGER NOT NULL DEFAULT 0,
                 provider_name TEXT,
                 model TEXT,
                 updated_at BIGINT NOT NULL
               )"""
        private val UPSERT_CONFIG_SQL =
            """INSERT INTO optimizer_config (id, enabled, provider_name, model, updated_at)
               VALUES (1, ?, ?, ?, ?)
               ON CONFLICT(id) DO UPDATE SET
                 enabled = excluded.enabled,
                 provider_name = excluded.provider_name,
                 model = excluded.model,
                 updated_at = excluded.updated_at"""
    }
}
