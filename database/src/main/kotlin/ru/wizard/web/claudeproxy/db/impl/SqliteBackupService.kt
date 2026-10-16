package ru.wizard.web.claudeproxy.db.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import ru.wizard.web.claudeproxy.config.ProxyProperties
import ru.wizard.web.claudeproxy.db.BackupService
import ru.wizard.web.claudeproxy.db.DatabaseDialect
import ru.wizard.web.claudeproxy.db.DatabaseProvider
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.stream.Collectors

/**
 * Периодический бэкап SQLite через `VACUUM INTO` — консистентный снимок
 * файла базы без остановки записи. Запуск по cron-выражению из
 * `claudeproxy.backup.cron` (по умолчанию — ежедневно ночью).
 *
 * Для PostgreSQL задача пропускается: бэкапы серверного развёртывания
 * выполняются штатными средствами СУБД (pg_dump и т.п.).
 */
@Service
class SqliteBackupService(
    private val databaseProvider: DatabaseProvider,
    private val jdbcTemplate: JdbcTemplate,
    dialect: DatabaseDialect,
    private val proxyProperties: ProxyProperties,
) : BackupService {

    private val logger = KotlinLogging.logger {}
    private val sqlite = dialect == DatabaseDialect.SQLITE

    override suspend fun createBackup(): Path? {
        val backup = proxyProperties.backup
        if (!backup.enabled) return null
        if (!sqlite) {
            logger.debug { "backup skipped: dialect is not sqlite" }
            return null
        }
        val directory = Path.of(backup.directory).toAbsolutePath().normalize()
        Files.createDirectories(directory)
        val target = directory.resolve(
            "claudeproxy-backup-" + LocalDateTime.now().format(FILE_NAME_FORMATTER) + ".db",
        )
        val created = databaseProvider.execute {
            // VACUUM INTO не работает внутри транзакции и требует, чтобы файл не существовал.
            jdbcTemplate.execute("VACUUM INTO '${target.toString().replace("'", "''")}'")
            target
        }
        logger.info { "sqlite backup created: ${created.fileName} (${"%.1f MB".format(Files.size(created) / 1024.0 / 1024.0)})" }
        deleteExcessBackups(directory, backup.retentionCount)
        return created
    }

    /** Удаляет самые старые бэкапы, пока их больше `retentionCount`. */
    private fun deleteExcessBackups(directory: Path, retentionCount: Int) {
        if (retentionCount <= 0) return
        val backups = Files.list(directory).use { stream ->
            stream
                .filter { path -> path.fileName.toString().startsWith(FILE_NAME_PREFIX) }
                .sorted(Comparator.reverseOrder())
                .collect(Collectors.toList())
        }
        backups.drop(retentionCount).forEach { path ->
            Files.deleteIfExists(path)
            logger.info { "old backup removed: ${path.fileName}" }
        }
    }

    /** Запуск бэкапа по расписанию (cron из claudeproxy.backup.cron). */
    @Scheduled(cron = "\${claudeproxy.backup.cron:0 53 3 * * *}")
    fun scheduledBackup() {
        kotlinx.coroutines.runBlocking { createBackup() }
    }

    private companion object {
        const val FILE_NAME_PREFIX = "claudeproxy-backup-"
        val FILE_NAME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")
    }
}
