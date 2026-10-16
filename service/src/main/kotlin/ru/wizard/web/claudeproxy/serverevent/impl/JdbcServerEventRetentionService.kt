package ru.wizard.web.claudeproxy.serverevent.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import ru.wizard.web.claudeproxy.config.ProxyProperties
import ru.wizard.web.claudeproxy.db.DatabaseProvider
import ru.wizard.web.claudeproxy.serverevent.ServerEventRetentionService
import java.time.Duration
import java.time.Instant

/** Ежечасная очистка журнала событий сервера от устаревших записей. */
@Component
class JdbcServerEventRetentionService(
    private val databaseProvider: DatabaseProvider,
    private val jdbcTemplate: JdbcTemplate,
    private val proxyProperties: ProxyProperties,
) : ServerEventRetentionService {
    private val logger = KotlinLogging.logger {}

    @Scheduled(fixedDelay = 3600000)
    override suspend fun removeObsoleteEvents() {
        val retentionDays = proxyProperties.serverEvent.retentionDays
        if (retentionDays <= 0) {
            return
        }
        val cutoffMilliseconds = Instant.now().minus(Duration.ofDays(retentionDays)).toEpochMilli()
        try {
            val deletedCount = databaseProvider.execute {
                jdbcTemplate.update("DELETE FROM server_event WHERE ts < ?", cutoffMilliseconds)
            }
            if (deletedCount > 0) {
                logger.info { "Removed $deletedCount server events older than $retentionDays days" }
            }
        } catch (exception: Exception) {
            logger.error(exception) { "Failed to remove obsolete server events" }
        }
    }
}
