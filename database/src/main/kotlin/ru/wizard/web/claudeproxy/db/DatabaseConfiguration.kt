package ru.wizard.web.claudeproxy.db

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.wizard.web.claudeproxy.db.impl.PostgresDatabaseProvider
import ru.wizard.web.claudeproxy.db.impl.SqliteDatabaseProvider
import javax.sql.DataSource

/**
 * Выбор реализации доступа к БД: диалект определяется по JDBC-URL
 * настроенного DataSource, для SQLite работает одиночный писатель,
 * для PostgreSQL — пул конкурентных соединений.
 */
@Configuration
class DatabaseConfiguration {

    /** Диалект БД, определённый по JDBC-URL открытого соединения. */
    @Bean
    fun databaseDialect(dataSource: DataSource): DatabaseDialect {
        val databaseUrl = dataSource.connection.use { connection -> connection.metaData.url }
        return DatabaseDialect.entries
            .firstOrNull { dialect -> databaseUrl.startsWith(dialect.jdbcUrlPrefix) }
            ?: throw IllegalStateException(
                "Unsupported database url: $databaseUrl, supported prefixes: " +
                    DatabaseDialect.entries.joinToString(", ") { dialect -> dialect.jdbcUrlPrefix },
            )
    }

    /** Диспетчер БД по диалекту: SQLite — сериализованный, Postgres — конкурентный. */
    @Bean
    fun databaseProvider(databaseDialect: DatabaseDialect): DatabaseProvider =
        when (databaseDialect) {
            DatabaseDialect.SQLITE -> SqliteDatabaseProvider()
            DatabaseDialect.POSTGRES -> PostgresDatabaseProvider()
        }
}
