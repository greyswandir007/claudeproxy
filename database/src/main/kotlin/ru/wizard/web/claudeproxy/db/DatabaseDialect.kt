package ru.wizard.web.claudeproxy.db

/**
 * Поддерживаемые СУБД.
 *
 * Диалект определяется автоматически по префиксу JDBC-URL настроенного
 * DataSource (см. [DatabaseConfiguration]) и задаёт расположение
 * SQL-миграций соответствующего диалекта: classpath:db/migration/&lt;диалект&gt;/V*.sql.
 */
enum class DatabaseDialect(
    /** Префикс JDBC-URL, по которому определяется диалект. */
    val jdbcUrlPrefix: String,
    /** Каталог SQL-миграций этого диалекта в classpath. */
    val migrationLocation: String,
) {
    SQLITE("jdbc:sqlite:", "classpath:db/migration/sqlite/V*.sql"),
    POSTGRES("jdbc:postgresql:", "classpath:db/migration/postgres/V*.sql"),
}
