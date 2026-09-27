package ru.wizard.web.claudeproxy

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import ru.wizard.web.claudeproxy.serverevent.ServerEvent
import ru.wizard.web.claudeproxy.serverevent.ServerEventRecorder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID

/**
 * M20: журнал событий сервера — запись через recorder, чтение через
 * GET /api/server-events (сортировка, фильтр по уровню, лимит) и захват
 * WARN-событий из логов приложения (отказ аутентификации клиентского ключа).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServerEventIntegrationTest {

    @Autowired
    private lateinit var environment: Environment

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var serverEventRecorder: ServerEventRecorder

    private lateinit var webTestClient: WebTestClient

    @BeforeEach
    fun prepare() {
        val serverPort = environment.getProperty("local.server.port", Int::class.java)!!
        webTestClient = WebTestClient.bindToServer()
            .baseUrl("http://127.0.0.1:$serverPort")
            .build()
        jdbcTemplate.update("DELETE FROM server_event")
    }

    @Test
    fun `записанное событие доступно через api`() {
        serverEventRecorder.recordAsync(
            ServerEvent(
                timestampMilliseconds = System.currentTimeMillis(),
                level = "WARN",
                logger = "test.Source",
                message = "route failed",
                stackTrace = "java.lang.IllegalStateException: boom",
            ),
        )
        assertTrue(awaitEventCount(1), "event was not written to server_event")

        webTestClient.get().uri("/api/server-events")
            .header(HttpHeaders.AUTHORIZATION, basicCredentials())
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(1)
            .jsonPath("$[0].level").isEqualTo("WARN")
            .jsonPath("$[0].logger").isEqualTo("test.Source")
            .jsonPath("$[0].message").isEqualTo("route failed")
            .jsonPath("$[0].stackTrace").isEqualTo("java.lang.IllegalStateException: boom")
    }

    @Test
    fun `фильтры по уровню и подстроке, сортировка - новые сверху, курсор`() {
        serverEventRecorder.recordAsync(
            ServerEvent(
                timestampMilliseconds = System.currentTimeMillis(),
                level = "WARN",
                logger = "proxy.impl.Handler",
                message = "first",
            ),
        )
        serverEventRecorder.recordAsync(
            ServerEvent(
                timestampMilliseconds = System.currentTimeMillis(),
                level = "ERROR",
                logger = "oauth.Token",
                message = "second",
            ),
        )
        assertTrue(awaitEventCount(2), "events were not written to server_event")

        // фильтр по уровню
        webTestClient.get().uri { builder ->
            builder.path("/api/server-events").queryParam("level", "ERROR").build()
        }
            .header(HttpHeaders.AUTHORIZATION, basicCredentials())
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(1)
            .jsonPath("$[0].level").isEqualTo("ERROR")
            .jsonPath("$[0].message").isEqualTo("second")

        // фильтр по подстроке в источнике
        webTestClient.get().uri { builder ->
            builder.path("/api/server-events").queryParam("loggerContains", "oauth").build()
        }
            .header(HttpHeaders.AUTHORIZATION, basicCredentials())
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(1)
            .jsonPath("$[0].message").isEqualTo("second")

        // сортировка новые сверху + лимит
        webTestClient.get().uri { builder ->
            builder.path("/api/server-events").queryParam("limit", "1").build()
        }
            .header(HttpHeaders.AUTHORIZATION, basicCredentials())
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(1)
            .jsonPath("$[0].message").isEqualTo("second")

        // курсор: события старше id последнего из первой страницы
        val newestId = jdbcTemplate.queryForObject(
            "SELECT max(id) FROM server_event",
            Long::class.java,
        )!!
        webTestClient.get().uri { builder ->
            builder.path("/api/server-events").queryParam("beforeId", newestId.toString()).build()
        }
            .header(HttpHeaders.AUTHORIZATION, basicCredentials())
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.length()").isEqualTo(1)
            .jsonPath("$[0].message").isEqualTo("first")
    }

    @Test
    fun `очистка журнала через delete`() {
        serverEventRecorder.recordAsync(
            ServerEvent(
                timestampMilliseconds = System.currentTimeMillis(),
                level = "WARN",
                logger = "test.Source",
                message = "to be cleared",
            ),
        )
        assertTrue(awaitEventCount(1), "event was not written to server_event")

        webTestClient.delete().uri("/api/server-events")
            .header(HttpHeaders.AUTHORIZATION, basicCredentials())
            .exchange().expectStatus().isNoContent

        val rowCount = jdbcTemplate.queryForObject("SELECT count(*) FROM server_event", Long::class.java)!!
        assertTrue(rowCount == 0L, "server_event must be empty after DELETE, got $rowCount rows")
    }

    @Test
    fun `отказ аутентификации клиентского ключа попадает в журнал`() {
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", "invalid-test-key")
            .bodyValue(mapOf("model" to "claude-test", "max_tokens" to 1))
            .exchange().expectStatus().isUnauthorized

        val deadline = System.currentTimeMillis() + 10_000
        var found = false
        while (System.currentTimeMillis() < deadline && !found) {
            found = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM server_event WHERE level = 'WARN' AND logger LIKE '%ApiKeyAuthFilter'",
                Long::class.java,
            )!! > 0
            if (!found) Thread.sleep(100)
        }
        assertTrue(found, "authentication failure was not captured in server_event")
    }

    /** Ждёт, пока асинхронный писатель зафиксирует минимум count событий. */
    private fun awaitEventCount(count: Int): Boolean {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val rowCount = jdbcTemplate.queryForObject("SELECT count(*) FROM server_event", Long::class.java)!!
            if (rowCount >= count) {
                return true
            }
            Thread.sleep(100)
        }
        return false
    }

    private fun basicCredentials(): String =
        "Basic " + Base64.getEncoder().encodeToString("admin:secret-password".toByteArray())

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(propertyRegistry: DynamicPropertyRegistry) {
            Files.createDirectories(Path.of("build/test"))
            propertyRegistry.add("spring.datasource.url") {
                "jdbc:sqlite:build/test/serverevent-${UUID.randomUUID()}.db"
            }
            propertyRegistry.add("claudeproxy.dashboard.auth.username") { "admin" }
            propertyRegistry.add("claudeproxy.dashboard.auth.password") { "secret-password" }
            propertyRegistry.add("claudeproxy.api-keys[0].name") { "test" }
            propertyRegistry.add("claudeproxy.api-keys[0].key") { "server-event-test-key" }
        }
    }
}
