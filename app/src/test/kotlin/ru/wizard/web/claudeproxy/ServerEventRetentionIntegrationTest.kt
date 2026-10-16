package ru.wizard.web.claudeproxy

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import ru.wizard.web.claudeproxy.serverevent.ServerEventRetentionService

/** Интеграционный тест ретеншена журнала событий сервера. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServerEventRetentionIntegrationTest {

    @Autowired
    private lateinit var retentionService: ServerEventRetentionService

    @Autowired
    private lateinit var environment: Environment

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                "jdbc:sqlite:build/test/server-event-retention-itest-${UUID.randomUUID()}.db"
            }
            registry.add("claudeproxy.server-event.retention-days") { "7" }
        }
    }

    @BeforeEach
    fun setUp() {
        val datasourceUrl = environment.getProperty("spring.datasource.url")
        check(datasourceUrl != null && datasourceUrl.contains("build/test")) {
            "Test datasource must point into build/test, got: $datasourceUrl"
        }
        Files.createDirectories(Path.of("build/test"))
        jdbcTemplate.update("DELETE FROM server_event")
    }

    @Test
    fun `события старше retention-days удаляются, свежие остаются`() = runBlocking {
        val day = 24L * 60 * 60 * 1000
        val now = System.currentTimeMillis()
        jdbcTemplate.update(
            "INSERT INTO server_event (ts, level, logger, message) VALUES (?,?,?,?)",
            now - 10 * day, "WARN", "itest.Old", "старое событие",
        )
        jdbcTemplate.update(
            "INSERT INTO server_event (ts, level, logger, message) VALUES (?,?,?,?)",
            now - day, "ERROR", "itest.Fresh", "свежее событие",
        )

        retentionService.removeObsoleteEvents()

        val remaining = jdbcTemplate.queryForList("SELECT logger FROM server_event", String::class.java)
        assertEquals(listOf("itest.Fresh"), remaining)
    }

    @Test
    fun `повторная очистка без устаревших не трогает журнал`() = runBlocking {
        jdbcTemplate.update(
            "INSERT INTO server_event (ts, level, logger, message) VALUES (?,?,?,?)",
            System.currentTimeMillis(), "INFO", "itest.Only", "единственное событие",
        )

        retentionService.removeObsoleteEvents()
        retentionService.removeObsoleteEvents()

        val remaining = jdbcTemplate.queryForList("SELECT logger FROM server_event", String::class.java)
        assertEquals(listOf("itest.Only"), remaining)
    }
}
