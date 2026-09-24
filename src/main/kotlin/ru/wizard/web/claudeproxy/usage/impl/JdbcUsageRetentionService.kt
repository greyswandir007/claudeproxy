package ru.wizard.web.claudeproxy.usage.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import ru.wizard.web.claudeproxy.config.ProxyProperties
import ru.wizard.web.claudeproxy.db.DatabaseProvider
import ru.wizard.web.claudeproxy.usage.UsageRetentionService

/**
 * Реализация UsageRetentionService: ежедневное удаление событий старше retention-days
 * (0 = не чистить). Отдельная корутина, не блокирует планировщик.
 */
@Service
class JdbcUsageRetentionService(
    private val databaseProvider: DatabaseProvider,
    private val jdbcTemplate: JdbcTemplate,
    private val proxyProperties: ProxyProperties,
) : UsageRetentionService {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Scheduled(cron = "0 37 4 * * *")
    fun scheduledCleanup() {
        scope.launch { deleteOutdatedEvents() }
    }

    override suspend fun deleteOutdatedEvents() {
        val retentionDays = proxyProperties.retentionDays
        if (retentionDays <= 0) return
        databaseProvider.execute {
            val deletedEvents = jdbcTemplate.update(
                "DELETE FROM usage_event WHERE ts < ?",
                System.currentTimeMillis() - retentionDays * 86_400_000L,
            )
            if (deletedEvents > 0) {
                logger.info { "Retention: deleted $deletedEvents outdated usage_event rows" }
            }
        }
    }
}
