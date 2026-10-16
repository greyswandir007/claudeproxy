package ru.wizard.web.claudeproxy.providers

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * Барьер стирания лимитов/тарифа: PUT без значений при заполненных в БД
 * требует limitsWipeConfirmed (инциденты 14.10 — устаревший бандл формы
 * молча слал null).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProviderLimitsWipeBarrierTest {

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
        jdbcTemplate.update(
            "UPDATE provider SET limit_window_tokens = 200000000, pricing_mode = 'monthly', price_monthly = 57.0",
        )
    }

    private fun put(body: String) = webTestClient.put()
        .uri("/api/providers/1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange()

    @Test
    fun `стирание лимитов без подтверждения отклоняется`() {
        put("""{"name":"fake","type":"anthropic","baseUrl":"http://127.0.0.1:1"}""")
            .expectStatus().isBadRequest
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
            .let { assertTrue(it.contains("limitsWipeConfirmed"), it) }
        // данные не тронуты
        val row = jdbcTemplate.queryForMap(
            "SELECT limit_window_tokens, pricing_mode FROM provider WHERE id = 1",
        )
        assertTrue(row["limit_window_tokens"] != null)
    }

    @Test
    fun `с подтверждением стирает, без лимитов меняет имя`() {
        put("""{"name":"fake","type":"anthropic","baseUrl":"http://127.0.0.1:1","limitsWipeConfirmed":true}""")
            .expectStatus().isOk
        val row = jdbcTemplate.queryForMap(
            "SELECT limit_window_tokens, pricing_mode FROM provider WHERE id = 1",
        )
        assertTrue(row["limit_window_tokens"] == null)

        // повторная правка без лимитов и без флага проходит: в БД уже пусто
        jdbcTemplate.update(
            "UPDATE provider SET limit_window_tokens = 100000000",
        )
        put("""{"name":"fake","type":"anthropic","baseUrl":"http://127.0.0.1:1","limitWindowTokens":100000000}""")
            .expectStatus().isOk
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(propertyRegistry: DynamicPropertyRegistry) {
            Files.createDirectories(Path.of("build/test"))
            propertyRegistry.add("spring.datasource.url") {
                "jdbc:sqlite:build/test/wipebarrier-${UUID.randomUUID()}.db"
            }
            propertyRegistry.add("claudeproxy.providers[0].name") { "fake" }
            propertyRegistry.add("claudeproxy.providers[0].type") { "anthropic" }
            propertyRegistry.add("claudeproxy.providers[0].base-url") { "http://127.0.0.1:9" }
            propertyRegistry.add("claudeproxy.providers[0].api-key") { "secret" }
            propertyRegistry.add("claudeproxy.providers[0].models[0].public") { "fake-model" }
            propertyRegistry.add("claudeproxy.providers[0].models[0].upstream") { "upstream-model" }
            propertyRegistry.add("claudeproxy.api-keys[0].name") { "test" }
            propertyRegistry.add("claudeproxy.api-keys[0].key") { "wipe-test-key" }
        }
    }
}
