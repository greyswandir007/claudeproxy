package ru.wizard.web.claudeproxy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID

/**
 * Basic Auth дашборда: включён учётными данными в конфиге; /v1 остаётся на api-ключах.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BasicAuthIntegrationTest {

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
        jdbcTemplate.update("DELETE FROM usage_window")
    }

    @Test
    fun `дашборд без учётных данных - 401 с WWW-Authenticate`() {
        val response = webTestClient.get().uri("/api/config")
            .exchange().expectStatus().isUnauthorized
            .expectBody(String::class.java).returnResult()
        assertNotNull(response.responseHeaders.getFirst(HttpHeaders.WWW_AUTHENTICATE))
    }

    @Test
    fun `дашборд с учётными данными - 200`() {
        webTestClient.get().uri("/api/config")
            .header(HttpHeaders.AUTHORIZATION, basicCredentials())
            .exchange().expectStatus().isOk
    }

    @Test
    fun `v1 не требует Basic - работает на api-ключе`() {
        webTestClient.get().uri("/v1/models")
            .header("x-api-key", SEED_API_KEY)
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.data").isArray
    }

    @Test
    fun `статика дашборда тоже за Basic`() {
        webTestClient.get().uri("/")
            .exchange().expectStatus().isUnauthorized
    }

    private fun basicCredentials(): String =
        "Basic " + Base64.getEncoder().encodeToString("admin:secret-password".toByteArray())

    companion object {
        private const val SEED_API_KEY = "basic-auth-test-key"

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(propertyRegistry: DynamicPropertyRegistry) {
            Files.createDirectories(Path.of("build/test"))
            propertyRegistry.add("spring.datasource.url") {
                "jdbc:sqlite:build/test/basicauth-${UUID.randomUUID()}.db"
            }
            propertyRegistry.add("claudeproxy.dashboard.auth.username") { "admin" }
            propertyRegistry.add("claudeproxy.dashboard.auth.password") { "secret-password" }
            propertyRegistry.add("claudeproxy.api-keys[0].name") { "test" }
            propertyRegistry.add("claudeproxy.api-keys[0].key") { SEED_API_KEY }
        }
    }
}
