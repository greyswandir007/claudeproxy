package ru.wizard.web.claudeproxy.db

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * Миграции БД: файлы classpath:db/migration/<диалект>/V<версия>__<имя>.sql применяются по порядку,
 * каждый — в транзакции; применённые версии фиксируются в schema_migration.
 * Каталог диалекта (sqlite/postgres) выбирается по [DatabaseDialect].
 * V1 идемпотентен (CREATE IF NOT EXISTS): существующие базы, созданные старым
 * schema.sql, безопасно базируются на нём.
 */
@Component
class DatabaseMigrationRunner(
    private val databaseProvider: DatabaseProvider,
    private val dialect: DatabaseDialect,
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
) {
    private val logger = KotlinLogging.logger {}

    @PostConstruct
    fun migrate() {
        kotlinx.coroutines.runBlocking { databaseProvider.execute { migrateBlocking() } }
    }

    private data class Migration(val version: Long, val name: String, val sql: String)

    private fun migrateBlocking() {
        jdbcTemplate.update(
            """CREATE TABLE IF NOT EXISTS schema_migration (
                   version INTEGER PRIMARY KEY,
                   name    TEXT NOT NULL,
                   applied_at BIGINT NOT NULL)""",
        )
        val appliedVersions = jdbcTemplate.query(
            "SELECT version FROM schema_migration",
            { resultSet, _ -> resultSet.getLong(1) },
        ).toSet()
        for (migration in loadMigrations()) {
            if (migration.version in appliedVersions) continue
            transactionTemplate.executeWithoutResult { transactionStatus ->
                for (statement in splitStatements(migration.sql)) {
                    jdbcTemplate.execute(statement)
                }
                jdbcTemplate.update(
                    "INSERT INTO schema_migration (version, name, applied_at) VALUES (?,?,?)",
                    migration.version,
                    migration.name,
                    System.currentTimeMillis(),
                )
            }
            logger.info { "Applied migration V${migration.version}__${migration.name}" }
        }
    }

    private fun loadMigrations(): List<Migration> {
        val resources = PathMatchingResourcePatternResolver()
            .getResources(dialect.migrationLocation)
        return resources.map { resource ->
            val filename = resource.filename ?: return@map null
            val match = filenamePattern.find(filename) ?: return@map null
            Migration(
                version = match.groupValues[1].toLong(),
                name = match.groupValues[2],
                sql = resource.inputStream.readBytes().toString(Charsets.UTF_8),
            )
        }.filterNotNull().sortedBy { it.version }
    }

    /** Разбивка SQL на инструкции: `;` на концах инструкций; `--`-комментарии
     * (в том числе внутристрочные) вырезаются до разбиения. */
    private fun splitStatements(sql: String): List<String> =
        sql.lines()
            .map { line -> line.substringBefore("--") }
            .joinToString("\n")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private companion object {
        val filenamePattern = Regex("^V(\\d+)__(.+)\\.sql$")
    }
}
