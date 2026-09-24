package ru.wizard.web.claudeproxy

import com.fasterxml.jackson.databind.ObjectMapper
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.util.CharsetUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Интеграционный тест: настоящий прокси + фейковый Anthropic-совместимый upstream
 * (reactor-netty). Проверяет auth, реестр моделей, pass-through (stream/не-stream),
 * проброс ошибок и запись usage в SQLite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProxyIntegrationTest {

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
    fun `нет ключа - 401 в формате Anthropic`() {
        webTestClient.get().uri("/v1/models").exchange().expectStatus().isUnauthorized
            .expectBody()
            .jsonPath("$.type").isEqualTo("error")
            .jsonPath("$.error.type").isEqualTo("authentication_error")
    }

    @Test
    fun `список моделей из конфига`() {
        webTestClient.get().uri("/v1/models")
            .header("x-api-key", SEED_API_KEY)
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.data[0].id").isEqualTo("fake-model")
            .jsonPath("$.has_more").isEqualTo(false)
    }

    @Test
    fun `неизвестная модель - 404`() {
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"model":"no-such-model","max_tokens":10,"messages":[]}""")
            .exchange().expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.error.type").isEqualTo("not_found_error")
    }

    @Test
    fun `не-stream pass-through пишет usage`() {
        val responseBody = webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"model":"fake-model","max_tokens":100,"messages":[{"role":"user","content":"привет"}]}""",
            )
            .exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!

        assertTrue(responseBody.contains("upstream-model"))

        val usageEventRow = awaitUsageEventRow("stream = 0")
        assertEquals("fake-model", usageEventRow["model"])
        assertEquals("upstream-model", usageEventRow["upstream_model"])
        assertEquals("test", usageEventRow["client_key"])
        assertEquals(10L, asLong(usageEventRow["input_tokens"]))
        assertEquals(20L, asLong(usageEventRow["output_tokens"]))
        assertEquals(5L, asLong(usageEventRow["cache_read_tokens"]))
        assertEquals(
            1,
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM usage_window", Int::class.java),
        )
    }

    @Test
    fun `stream pass-through проксирует SSE и пишет usage`() {
        val responseBody = webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"model":"fake-model","max_tokens":100,"stream":true,"messages":[{"role":"user","content":"привет"}]}""",
            )
            .exchange().expectStatus().isOk
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .expectBody(String::class.java).returnResult().responseBody!!

        assertTrue(responseBody.contains("message_start"))
        assertTrue(responseBody.contains("message_stop"))

        val usageEventRow = awaitUsageEventRow("stream = 1")
        assertEquals(10L, asLong(usageEventRow["input_tokens"]))
        assertEquals(20L, asLong(usageEventRow["output_tokens"]))
        assertEquals(5L, asLong(usageEventRow["cache_read_tokens"]))
    }

    @Test
    fun `count_tokens пробрасывается`() {
        webTestClient.post().uri("/v1/messages/count_tokens")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"model":"fake-model","messages":[{"role":"user","content":"hi"}]}""")
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.input_tokens").isEqualTo(42)
    }

    @Test
    fun `ошибка провайдера пробрасывается с телом`() {
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"model":"err-model","max_tokens":10,"messages":[]}""")
            .exchange().expectStatus().is5xxServerError
            .expectBody()
            .jsonPath("$.type").isEqualTo("error")
            .jsonPath("$.error.message").isEqualTo("unexpected model: err-upstream")
    }

    private fun asLong(value: Any?): Long = (value as Number).toLong()

    private fun awaitUsageEventRow(condition: String): Map<String, Any?> = runBlocking {
        withTimeout(5_000) {
            while (true) {
                val usageEventRows =
                    jdbcTemplate.queryForList("SELECT * FROM usage_event WHERE $condition")
                if (usageEventRows.isNotEmpty()) return@withTimeout usageEventRows.first()
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE")
            error("недостижимо")
        }
    }

    companion object {
        private const val SEED_API_KEY = "test-key-123"
        private val objectMapper = ObjectMapper()
        private lateinit var upstream: DisposableServer

        init {
            // Каталог для тестовой SQLite: DynamicPropertySource подставляется позже
            // EnvironmentPostProcessor, поэтому каталог создаём сами
            Files.createDirectories(Path.of("build/test"))
            upstream = HttpServer.create().port(0)
                .handle { request, response ->
                    request.receive().aggregate().asString().defaultIfEmpty("")
                        .flatMap { upstreamRequestBody ->
                            val requestNode = runCatching {
                                objectMapper.readTree(upstreamRequestBody)
                            }.getOrNull()
                            val model = requestNode?.path("model")?.asText("") ?: ""
                            when {
                                request.uri().endsWith("/count_tokens") ->
                                    response.status(HttpResponseStatus.OK)
                                        .header("Content-Type", "application/json")
                                        .sendString(
                                            Mono.just("""{"input_tokens":42}"""),
                                            CharsetUtil.UTF_8,
                                        )
                                        .then()

                                model == "upstream-model" &&
                                    requestNode?.path("stream")?.asBoolean(false) == true ->
                                    // Два чанка: разрыв строки проверяет буферизацию сниффера
                                    response.status(HttpResponseStatus.OK)
                                        .header("Content-Type", "text/event-stream")
                                        .sendString(
                                            Flux.just(
                                                SERVER_SENT_EVENTS_FIRST_CHUNK,
                                                SERVER_SENT_EVENTS_SECOND_CHUNK,
                                            ),
                                            CharsetUtil.UTF_8,
                                        )
                                        .then()

                                model == "upstream-model" ->
                                    response.status(HttpResponseStatus.OK)
                                        .header("Content-Type", "application/json")
                                        .sendString(Mono.just(NON_STREAM_RESPONSE_BODY), CharsetUtil.UTF_8)
                                        .then()

                                else ->
                                    response.status(HttpResponseStatus.INTERNAL_SERVER_ERROR)
                                        .header("Content-Type", "application/json")
                                        .sendString(
                                            Mono.just(
                                                """{"type":"error","error":{"type":"api_error",""" +
                                                    """"message":"unexpected model: $model"}}""",
                                            ),
                                            CharsetUtil.UTF_8,
                                        )
                                        .then()
                            }
                        }
                }
                .bindNow()
        }

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(propertyRegistry: DynamicPropertyRegistry) {
            propertyRegistry.add("spring.datasource.url") {
                "jdbc:sqlite:build/test/itest-${UUID.randomUUID()}.db"
            }
            propertyRegistry.add("claudeproxy.providers[0].name") { "fake" }
            propertyRegistry.add("claudeproxy.providers[0].type") { "anthropic" }
            propertyRegistry.add("claudeproxy.providers[0].base-url") {
                "http://127.0.0.1:${upstream.port()}"
            }
            propertyRegistry.add("claudeproxy.providers[0].api-key") { "upstream-secret" }
            propertyRegistry.add("claudeproxy.providers[0].models[0].public") { "fake-model" }
            propertyRegistry.add("claudeproxy.providers[0].models[0].upstream") { "upstream-model" }
            propertyRegistry.add("claudeproxy.providers[0].models[1].public") { "err-model" }
            propertyRegistry.add("claudeproxy.providers[0].models[1].upstream") { "err-upstream" }
            propertyRegistry.add("claudeproxy.api-keys[0].name") { "test" }
            propertyRegistry.add("claudeproxy.api-keys[0].key") { SEED_API_KEY }
        }

        private const val NON_STREAM_RESPONSE_BODY =
            """{"id":"msg_test","type":"message","role":"assistant","model":"upstream-model","content":[{"type":"text","text":"hello"}],"stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":20,"cache_creation_input_tokens":0,"cache_read_input_tokens":5}}"""

        private const val SERVER_SENT_EVENTS_FIRST_CHUNK =
            "event: message_start\n" +
                "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_t1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"upstream-model\",\"usage\":{\"input_tokens\":10,\"output_tokens\":1,\"cache_read_input_tokens\":5}}}\n\n" +
                "event: content_block_delta\n" +
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"привет\"}}\n\n" +
                "event: message_delta\n" +
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":20}}\n\n" +
                "event: message_stop\n" +
                "data: {\"type\":\"mess"

        private const val SERVER_SENT_EVENTS_SECOND_CHUNK = "age_stop\"}\n\n"
    }
}
