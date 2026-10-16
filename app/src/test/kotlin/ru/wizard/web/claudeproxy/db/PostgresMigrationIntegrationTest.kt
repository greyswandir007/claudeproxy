package ru.wizard.web.claudeproxy.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Интеграционный тест PostgreSQL-диалекта: старт контекста применяет весь
 * postgres-набор миграций и проверяет переносимость ключевого SQL.
 *
 * Запускается только при заданных переменных окружения (по умолчанию
 * пропускается, чтобы не требовать PostgreSQL на машинах без него):
 *   CLAUDEPROXY_POSTGRES_TEST_URL      — jdbc:postgresql://host:5432/dbname
 *   CLAUDEPROXY_POSTGRES_TEST_USERNAME — пользователь
 *   CLAUDEPROXY_POSTGRES_TEST_PASSWORD — пароль
 * База должна быть пустой (тест применяет миграции с нуля) и одноразовой.
 */
@EnabledIfEnvironmentVariable(named = "CLAUDEPROXY_POSTGRES_TEST_URL", matches = "jdbc:postgresql:.+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PostgresMigrationIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { System.getenv("CLAUDEPROXY_POSTGRES_TEST_URL") }
            registry.add("spring.datasource.username") { System.getenv("CLAUDEPROXY_POSTGRES_TEST_USERNAME") }
            registry.add("spring.datasource.password") { System.getenv("CLAUDEPROXY_POSTGRES_TEST_PASSWORD") }
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.datasource.hikari.maximum-pool-size") { "4" }
        }
    }

    @Autowired
    private lateinit var databaseDialect: DatabaseDialect

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `диалект определяется как postgres`() {
        assertEquals(DatabaseDialect.POSTGRES, databaseDialect)
    }

    @Test
    fun `все миграции postgres-диалекта применены`() {
        val expected = PathMatchingResourcePatternResolver()
            .getResources(DatabaseDialect.POSTGRES.migrationLocation)
            .size
        val applied = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schema_migration", Int::class.java)!!
        assertEquals(expected, applied)
    }

    @Test
    fun `usage_event принимает epoch-milliseconds`() {
        jdbcTemplate.update("DELETE FROM usage_event WHERE client_key = 'postgres-test'")
        jdbcTemplate.update(
            """INSERT INTO usage_event (ts, client_key, provider, model, upstream_model, stream,
               input_tokens, output_tokens, cache_creation_tokens, cache_read_tokens, status)
               VALUES (?, ?, ?, ?, ?, 0, 1, 2, 3, 4, 200)""",
            System.currentTimeMillis(), "postgres-test", "postgres", "claude-test",
        )
        val saved = jdbcTemplate.queryForObject(
            "SELECT output_tokens FROM usage_event WHERE client_key = 'postgres-test'",
            Int::class.java,
        )!!
        assertEquals(2, saved)
    }

    @Test
    fun `вытеснение кэша LIMIT без отрицательных значений`() {
        jdbcTemplate.update("DELETE FROM request_cache")
        for (index in 1..3L) {
            jdbcTemplate.update(
                """INSERT INTO request_cache (cache_key, upstream_path, request_body, response_body,
                   response_format, model, provider, input_tokens, output_tokens,
                   created_at, expires_at, last_accessed_at)
                   VALUES (?, ?, '{}', '{}', 'json', 'm', 'p', 0, 0, ?, ?, ?)""",
                "pg-test-$index", "/v1/messages", index, index + 100, index,
            )
        }
        jdbcTemplate.update(
            """DELETE FROM request_cache WHERE id NOT IN
               (SELECT id FROM request_cache ORDER BY last_accessed_at DESC, id DESC LIMIT ?)""",
            2,
        )
        val remaining = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM request_cache", Int::class.java)!!
        assertEquals(2, remaining)
    }
}
