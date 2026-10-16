package ru.wizard.web.claudeproxy.routing.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import ru.wizard.web.claudeproxy.db.DatabaseProvider
import ru.wizard.web.claudeproxy.routing.ResourceBindingService
import ru.wizard.web.claudeproxy.routing.ResourceBindingService.ResourceType
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.days

private val logger = KotlinLogging.logger {}

/**
 * Привязки ресурсов в БД: батчи живут у провайдера до ~30 дней (результаты
 * до 29), файлы — бессрочно, поэтому хранение в памяти не переживает
 * перезапуск прокси.
 */
@Service
class JdbcResourceBindingService(
    private val databaseProvider: DatabaseProvider,
    private val jdbcTemplate: JdbcTemplate,
) : ResourceBindingService {

    private val cleanupDone = AtomicBoolean(false)

    override suspend fun bind(resourceType: ResourceType, resourceId: String, providerName: String) {
        cleanupStaleBatchesOnce()
        databaseProvider.execute {
            jdbcTemplate.update(
                INSERT_BINDING,
                resourceType.name,
                resourceId,
                providerName,
                System.currentTimeMillis(),
            )
        }
    }

    override suspend fun providerNameOf(resourceType: ResourceType, resourceId: String): String? =
        runCatching {
            databaseProvider.execute {
                jdbcTemplate.queryForObject(SELECT_BINDING, String::class.java, resourceType.name, resourceId)
            }
        }.getOrNull()

    override suspend fun forget(resourceType: ResourceType, resourceId: String) {
        databaseProvider.execute {
            jdbcTemplate.update(DELETE_BINDING, resourceType.name, resourceId)
        }
    }

    override suspend fun rememberBatchResult(batchId: String, customId: String): Boolean =
        runCatching {
            databaseProvider.execute {
                jdbcTemplate.update(REMEMBER_BATCH_RESULT, batchId, customId) > 0
            }
        }.getOrDefault(false)

    /** Результаты батчей доступны 29 дней — более старые привязки не нужны. */
    private suspend fun cleanupStaleBatchesOnce() {
        if (!cleanupDone.compareAndSet(false, true)) return
        runCatching {
            databaseProvider.execute {
                jdbcTemplate.update(
                    DELETE_EXPIRED_BATCH_BINDINGS,
                    System.currentTimeMillis() - RETENTION.inWholeMilliseconds,
                )
            }
        }.onFailure { error ->
            logger.warn(error) { "Failed to clean up stale message batch bindings" }
        }
    }

    private companion object {
        val RETENTION = 30.days
        const val INSERT_BINDING =
            "INSERT INTO provider_resources (resource_type, resource_id, provider_name, created_at) " +
                "VALUES (?, ?, ?, ?) " +
                "ON CONFLICT(resource_type, resource_id) DO UPDATE SET provider_name = excluded.provider_name, created_at = excluded.created_at"
        const val SELECT_BINDING =
            "SELECT provider_name FROM provider_resources WHERE resource_type = ? AND resource_id = ?"
        const val DELETE_BINDING =
            "DELETE FROM provider_resources WHERE resource_type = ? AND resource_id = ?"
        const val REMEMBER_BATCH_RESULT =
            "INSERT INTO message_batch_usage (batch_id, custom_id) VALUES (?, ?) ON CONFLICT DO NOTHING"
        const val DELETE_EXPIRED_BATCH_BINDINGS =
            "DELETE FROM provider_resources WHERE resource_type = 'MESSAGE_BATCH' AND created_at < ?"
    }
}
