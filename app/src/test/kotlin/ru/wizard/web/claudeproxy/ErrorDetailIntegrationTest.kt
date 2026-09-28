package ru.wizard.web.claudeproxy

import org.hamcrest.Matchers.nullValue
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
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID

/**
 * M17: полная расшифровка ошибок маршрутизации — эндпоинт /api/stats/errors/{id}
 * отдаёт error_detail события; ответы API не кэшируются (Cache-Control: no-store).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErrorDetailIntegrationTest {

    @Autowired
    private lateinit var environment: Environment

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private lateinit var webTestClient: WebTestClient

    @BeforeEach
    fun prepare() {
        val serverPort = environment.getProperty("local.server.port", Int::class.java)!!
        webTestClient = WebTestClient.bindToServer()
            .baseUrl("http://127.0.0.1:$serverPort")
            .build()
        jdbcTemplate.update("DELETE FROM usage_event")
    }

    @Test
    fun `error detail по id события`() {
        val eventId = insertFailedEvent("HTTP 429: Too Many Requests", "{\"error\":{\"type\":\"rate_limit_error\"}}")
        webTestClient.get().uri("/api/stats/errors/$eventId")
            .header(HttpHeaders.AUTHORIZATION, basicCredentials())
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.eventId").isEqualTo(eventId)
            .jsonPath("$.errorDetail").isEqualTo("{\"error\":{\"type\":\"rate_limit_error\"}}")
    }

    @Test
    fun `неизвестный id - 404`() {
        webTestClient.get().uri("/api/stats/errors/999999")
            .header(HttpHeaders.AUTHORIZATION, basicCredentials())
            .exchange().expectStatus().isNotFound
    }

    @Test
    fun `событие без расшифровки - null в ответе`() {
        val eventId = insertFailedEvent("HTTP 500", null)
        webTestClient.get().uri("/api/stats/errors/$eventId")
            .header(HttpHeaders.AUTHORIZATION, basicCredentials())
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.eventId").isEqualTo(eventId)
            .jsonPath("$.errorDetail").value(nullValue())
    }

    @Test
    fun `api-ответы помечены no-store`() {
        webTestClient.get().uri("/api/config")
            .header(HttpHeaders.AUTHORIZATION, basicCredentials())
            .exchange().expectStatus().isOk
            .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
    }

    private fun insertFailedEvent(error: String, errorDetail: String?): Long {
        jdbcTemplate.update(
            """INSERT INTO usage_event
               (ts, client_key, provider, model, upstream_model, stream,
                input_tokens, output_tokens, cache_creation_tokens, cache_read_tokens,
                duration_ms, status, error, saved_tokens, error_detail)
               VALUES (?, ?, ?, ?, ?, 0, 0, 0, 0, 0, 0, 429, ?, 0, ?)""",
            System.currentTimeMillis(),
            "test-key",
            "test-provider",
            "claude-test",
            "claude-test",
            error,
            errorDetail,
        )
        return jdbcTemplate.queryForObject("SELECT max(id) FROM usage_event", Long::class.java)!!
    }

    private fun basicCredentials(): String =
        "Basic " + Base64.getEncoder().encodeToString("admin:secret-password".toByteArray())

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(propertyRegistry: DynamicPropertyRegistry) {
            Files.createDirectories(Path.of("build/test"))
            propertyRegistry.add("spring.datasource.url") {
                "jdbc:sqlite:build/test/errordetail-${UUID.randomUUID()}.db"
            }
            propertyRegistry.add("claudeproxy.dashboard.auth.username") { "admin" }
            propertyRegistry.add("claudeproxy.dashboard.auth.password") { "secret-password" }
            propertyRegistry.add("claudeproxy.api-keys[0].name") { "test" }
            propertyRegistry.add("claudeproxy.api-keys[0].key") { "error-detail-test-key" }
        }
    }
}
