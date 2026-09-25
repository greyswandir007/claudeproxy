package ru.wizard.web.claudeproxy.usage.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import ru.wizard.web.claudeproxy.db.DatabaseProvider
import ru.wizard.web.claudeproxy.usage.UsageEvent
import ru.wizard.web.claudeproxy.usage.UsageRecorder
import ru.wizard.web.claudeproxy.usage.WindowService

/**
 * Реализация UsageRecorder: асинхронная корутина на контексте базы данных.
 * Пишет и при ошибке/обрыве стрима (status/error), чтобы статистика не теряла запросы.
 */
@Service
class AsyncUsageRecorder(
    private val databaseProvider: DatabaseProvider,
    private val jdbcTemplate: JdbcTemplate,
    private val windowService: WindowService,
) : UsageRecorder {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun recordAsync(usageEvent: UsageEvent) {
        scope.launch {
            try {
                databaseProvider.execute {
                    windowService.ensureWindow(usageEvent.clientKey, usageEvent.ts)
                    windowService.ensureProviderWindow(usageEvent.provider, usageEvent.ts)
                    jdbcTemplate.update(
                        """INSERT INTO usage_event
                           (ts, client_key, provider, model, upstream_model, stream,
                            input_tokens, output_tokens, cache_creation_tokens, cache_read_tokens,
                            duration_ms, status, error, saved_tokens)
                           VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                        usageEvent.ts,
                        usageEvent.clientKey,
                        usageEvent.provider,
                        usageEvent.model,
                        usageEvent.upstreamModel,
                        if (usageEvent.stream) 1 else 0,
                        usageEvent.inputTokens,
                        usageEvent.outputTokens,
                        usageEvent.cacheCreationTokens,
                        usageEvent.cacheReadTokens,
                        usageEvent.durationMilliseconds,
                        usageEvent.status,
                        usageEvent.error,
                        usageEvent.savedTokens,
                    )
                }
            } catch (exception: Exception) {
                logger.error(exception) {
                    "Failed to record usage_event (model=${usageEvent.model})"
                }
            }
        }
    }
}
