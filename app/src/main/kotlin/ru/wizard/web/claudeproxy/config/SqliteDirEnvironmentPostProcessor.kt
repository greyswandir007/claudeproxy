package ru.wizard.web.claudeproxy.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import java.nio.file.Files
import java.nio.file.Path

/**
 * Гарантирует существование родительского каталога файла SQLite до инициализации
 * DataSource: драйвер sqlite-jdbc не создаёт каталоги сам (SQLITE_CANTOPEN).
 * Выполняется и для тестов, и для bootRun, и для собранного jar.
 */
class SqliteDirEnvironmentPostProcessor : EnvironmentPostProcessor {

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val url = environment.getProperty("spring.datasource.url") ?: return
        if (!url.startsWith(JDBC_SQLITE_PREFIX)) return
        val file = url.removePrefix(JDBC_SQLITE_PREFIX)
        if (file.isBlank() || file == MEMORY) return
        runCatching {
            Path.of(file).toAbsolutePath().parent?.let(Files::createDirectories)
        }
    }

    private companion object {
        const val JDBC_SQLITE_PREFIX = "jdbc:sqlite:"
        const val MEMORY = ":memory:"
    }
}
