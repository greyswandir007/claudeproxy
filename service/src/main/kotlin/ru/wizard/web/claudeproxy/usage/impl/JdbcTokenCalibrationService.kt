package ru.wizard.web.claudeproxy.usage.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import ru.wizard.web.claudeproxy.config.ProxyProperties
import ru.wizard.web.claudeproxy.db.DatabaseProvider
import ru.wizard.web.claudeproxy.usage.TokenCalibrationEntry
import ru.wizard.web.claudeproxy.usage.TokenCalibrationService
import java.util.concurrent.ConcurrentHashMap

/** Реализация калибровки count_tokens на таблице token_calibration. */
@Service
class JdbcTokenCalibrationService(
    private val jdbcTemplate: JdbcTemplate,
    private val databaseProvider: DatabaseProvider,
    proxyProperties: ProxyProperties,
) : TokenCalibrationService {

    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val enabled = proxyProperties.tokenCalibration.enabled
    private val minimumSamples = proxyProperties.tokenCalibration.minimumSamples

    /** Кэш коэффициентов (включая отрицательный ответ «данных ещё нет») с TTL. */
    private val ratioCache = ConcurrentHashMap<String, CachedRatio>()

    private data class CachedRatio(val ratio: Double?, val loadedAtMilliseconds: Long)

    private fun cacheKey(model: String, provider: String): String = "$model\u0000$provider"

    override fun observeAsync(model: String, provider: String, textCharacters: Long, textTokens: Long) {
        if (!enabled || textCharacters <= 0 || textTokens <= 0) {
            return
        }
        scope.launch {
            runCatching {
                databaseProvider.execute {
                    jdbcTemplate.update(
                        UPSERT_CALIBRATION,
                        model, provider, textCharacters, textTokens, 1L, System.currentTimeMillis(),
                    )
                }
            }.onFailure { error ->
                logger.warn(error) { "token calibration observe failed" }
            }
            // коэффициент изменился — кэш перестаёт быть актуальным
            ratioCache.remove(cacheKey(model, provider))
        }
    }

    override suspend fun charactersPerToken(model: String, provider: String): Double? {
        if (!enabled) {
            return null
        }
        val key = cacheKey(model, provider)
        val cached = ratioCache[key]
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.loadedAtMilliseconds < RATIO_CACHE_TTL_MILLISECONDS) {
            return cached.ratio
        }
        val ratio = loadEntry(model, provider)
            ?.takeIf { it.samples >= minimumSamples }
            ?.let { it.textCharacters.toDouble() / it.textTokens.toDouble() }
        ratioCache[key] = CachedRatio(ratio, now)
        return ratio
    }

    override suspend fun entries(): List<TokenCalibrationEntry> = databaseProvider.execute {
        jdbcTemplate.query(SELECT_ALL) { resultSet, _ ->
            val textCharacters = resultSet.getLong("text_characters")
            val textTokens = resultSet.getLong("text_tokens")
            val samples = resultSet.getLong("samples")
            TokenCalibrationEntry(
                model = resultSet.getString("model"),
                provider = resultSet.getString("provider"),
                textCharacters = textCharacters,
                textTokens = textTokens,
                samples = samples,
                charactersPerToken = if (samples >= minimumSamples && textTokens > 0) {
                    textCharacters.toDouble() / textTokens.toDouble()
                } else {
                    null
                },
                updatedAtMilliseconds = resultSet.getLong("updated_at"),
            )
        }
    }

    override suspend fun clear() {
        databaseProvider.execute {
            jdbcTemplate.update(DELETE_ALL)
        }
        ratioCache.clear()
    }

    private suspend fun loadEntry(model: String, provider: String): LoadedEntry? = databaseProvider.execute {
        jdbcTemplate.query(SELECT_ENTRY, { resultSet, _ ->
            LoadedEntry(
                textCharacters = resultSet.getLong("text_characters"),
                textTokens = resultSet.getLong("text_tokens"),
                samples = resultSet.getLong("samples"),
            )
        }, model, provider).firstOrNull()
    }

    private data class LoadedEntry(val textCharacters: Long, val textTokens: Long, val samples: Long)

    private companion object {
        /** TTL кэша коэффициентов, чтобы не читать БД на каждый count_tokens. */
        const val RATIO_CACHE_TTL_MILLISECONDS = 60_000L

        const val UPSERT_CALIBRATION =
            """INSERT INTO token_calibration (model, provider, text_characters, text_tokens, samples, updated_at)
               VALUES (?, ?, ?, ?, ?, ?)
               ON CONFLICT (model, provider) DO UPDATE SET
                   text_characters = token_calibration.text_characters + excluded.text_characters,
                   text_tokens = token_calibration.text_tokens + excluded.text_tokens,
                   samples = token_calibration.samples + excluded.samples,
                   updated_at = excluded.updated_at"""

        const val SELECT_ENTRY =
            "SELECT text_characters, text_tokens, samples FROM token_calibration WHERE model = ? AND provider = ?"

        const val SELECT_ALL =
            "SELECT model, provider, text_characters, text_tokens, samples, updated_at FROM token_calibration " +
                "ORDER BY model, provider"

        const val DELETE_ALL = "DELETE FROM token_calibration"
    }
}
