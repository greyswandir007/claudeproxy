package ru.wizard.web.claudeproxy

import com.fasterxml.jackson.databind.ObjectMapper
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.util.CharsetUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
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
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Интеграционный тест round-robin равноприоритетных маршрутов: два anthropic-канала
 * с одинаковым priority чередуются как primary; разный priority держит строгий
 * порядок; отказавший равноприоритетный канал пропускается ротацией на следующем
 * запросе. Фейковый upstream считает запросы по upstream-модели.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoundRobinRoutingIntegrationTest {

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
        jdbcTemplate.update("DELETE FROM request_cache")
        requestCountsByUpstreamModel.clear()
        failingUpstreamModels.clear()
    }

    @Test
    fun `равные приоритеты чередуют провайдеров round-robin`() {
        val oneId = createAnthropicProvider("rr-one", "rr-equal", "rr-a", 10)
        val twoId = createAnthropicProvider("rr-two", "rr-equal", "rr-b", 10)
        try {
            repeat(4) { index -> sendMessages("rr-equal", index) }
            assertEquals(
                listOf("rr-one", "rr-two", "rr-one", "rr-two"),
                awaitSuccessProviders("rr-equal", 4),
            )
            assertEquals(2, upstreamRequests("rr-a"))
            assertEquals(2, upstreamRequests("rr-b"))
        } finally {
            deleteProvider(oneId)
            deleteProvider(twoId)
        }
    }

    @Test
    fun `разные приоритеты сохраняют строгий порядок`() {
        val oneId = createAnthropicProvider("rr-strict-one", "rr-strict", "rr-strict-a", 5)
        val twoId = createAnthropicProvider("rr-strict-two", "rr-strict", "rr-strict-b", 10)
        try {
            repeat(3) { index -> sendMessages("rr-strict", index) }
            assertEquals(
                listOf("rr-strict-one", "rr-strict-one", "rr-strict-one"),
                awaitSuccessProviders("rr-strict", 3),
            )
            assertEquals(0, upstreamRequests("rr-strict-b"))
        } finally {
            deleteProvider(oneId)
            deleteProvider(twoId)
        }
    }

    @Test
    fun `ротация уводит primary с отказавшего равноприоритетного канала`() {
        failingUpstreamModels.add("rr-flaky-a")
        val oneId = createAnthropicProvider("rr-flaky-one", "rr-flaky", "rr-flaky-a", 10)
        val twoId = createAnthropicProvider("rr-flaky-two", "rr-flaky", "rr-flaky-b", 10)
        try {
            // запрос 1: первичен rr-one → upstream 500 → fallback на rr-two
            sendMessages("rr-flaky", 0)
            assertEquals(listOf("rr-flaky-two"), awaitSuccessProviders("rr-flaky", 1))
            assertEquals(1, upstreamRequests("rr-flaky-a"))

            // запрос 2: ротация делает rr-two первичным — отказавший канал не опрашивается
            sendMessages("rr-flaky", 1)
            assertEquals(
                listOf("rr-flaky-two", "rr-flaky-two"),
                awaitSuccessProviders("rr-flaky", 2),
            )
            assertEquals(
                1,
                upstreamRequests("rr-flaky-a"),
                "без ротации второй запрос снова попал бы на отказавший канал",
            )
        } finally {
            deleteProvider(oneId)
            deleteProvider(twoId)
        }
    }

    /** Создаёт anthropic-провайдер с одной моделью; возвращает id провайдера. */
    private fun createAnthropicProvider(
        name: String,
        publicName: String,
        upstreamName: String,
        priority: Int,
    ): Long {
        val created = webTestClient.post().uri("/api/providers")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"name":"$name","type":"anthropic","baseUrl":"http://127.0.0.1:${upstreamPort()}",
                    "apiKey":"secret-$name"}""",
            )
            .exchange().expectStatus().isCreated
            .expectBody(String::class.java).returnResult().responseBody!!
        val providerId = objectMapper.readTree(created).path("id").asLong()
        webTestClient.post().uri("/api/providers/$providerId/models")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"publicName":"$publicName","upstreamName":"$upstreamName",
                    "reasoning":"map","maxCompletionParam":false,"priority":$priority}""",
            )
            .exchange().expectStatus().isCreated
        return providerId
    }

    private fun deleteProvider(providerId: Long) {
        webTestClient.delete().uri("/api/providers/$providerId")
            .exchange().expectStatus().isNoContent
    }

    /** Запрос /v1/messages с уникальным текстом (обход кэша ответов). */
    private fun sendMessages(publicName: String, index: Int) {
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"model":"$publicName","max_tokens":16,
                    "messages":[{"role":"user","content":"rr-запрос-$publicName-$index"}]}""",
            )
            .exchange().expectStatus().isOk
    }

    private fun upstreamRequests(upstreamName: String): Int =
        requestCountsByUpstreamModel[upstreamName]?.get() ?: 0

    /** Провайдеры успешных (status = 200) usage-строк модели в порядке обработки. */
    private fun awaitSuccessProviders(model: String, expectedCount: Int): List<String> = runBlocking {
        withTimeout(5_000) {
            while (true) {
                val providerRows = jdbcTemplate.queryForList(
                    "SELECT provider FROM usage_event WHERE model = ? AND status = 200 ORDER BY id",
                    model,
                )
                if (providerRows.size >= expectedCount) {
                    return@withTimeout providerRows.take(expectedCount).map { it["provider"].toString() }
                }
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE")
            error("недостижимо")
        }
    }

    companion object {
        private const val SEED_API_KEY = "test-key-123"
        private val objectMapper = ObjectMapper()

        private val requestCountsByUpstreamModel = ConcurrentHashMap<String, AtomicInteger>()

        /** Upstream-модели, на которые фейковый upstream отвечает 500. */
        private val failingUpstreamModels: MutableSet<String> = ConcurrentHashMap.newKeySet()

        private lateinit var upstream: DisposableServer

        init {
            // Каталог для тестовой SQLite: DynamicPropertySource подставляется позже,
            // поэтому каталог создаём сами
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
                                request.method() == io.netty.handler.codec.http.HttpMethod.POST &&
                                    request.uri().endsWith("/count_tokens") ->
                                    ok(response, """{"input_tokens":42}""")

                                request.method() == io.netty.handler.codec.http.HttpMethod.POST &&
                                    request.uri().endsWith("/messages") -> {
                                        requestCountsByUpstreamModel
                                            .computeIfAbsent(model) { AtomicInteger() }
                                            .incrementAndGet()
                                        if (model in failingUpstreamModels) {
                                            response.status(HttpResponseStatus.INTERNAL_SERVER_ERROR)
                                                .header("Content-Type", "application/json")
                                                .sendString(
                                                    Mono.just(
                                                        """{"type":"error","error":{"type":"api_error",""" +
                                                            """"message":"upstream unavailable"}}""",
                                                    ),
                                                    CharsetUtil.UTF_8,
                                                )
                                                .then()
                                        } else {
                                            ok(response, anthropicMessageBody(model))
                                        }
                                    }

                                else ->
                                    response.status(HttpResponseStatus.NOT_FOUND).send().then()
                            }
                        }
                }
                .bindNow()
        }

        private fun ok(
            response: reactor.netty.http.server.HttpServerResponse,
            body: String,
        ): Mono<Void> = response.status(HttpResponseStatus.OK)
            .header("Content-Type", "application/json")
            .sendString(Mono.just(body), CharsetUtil.UTF_8)
            .then()

        private fun anthropicMessageBody(model: String): String =
            """{"id":"msg_rr","type":"message","role":"assistant","model":"$model",""" +
                """"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn",""" +
                """"usage":{"input_tokens":1,"output_tokens":1,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}"""

        private fun upstreamPort(): Int = upstream.port()

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(propertyRegistry: DynamicPropertyRegistry) {
            propertyRegistry.add("spring.datasource.url") {
                "jdbc:sqlite:build/test/rr-itest-${UUID.randomUUID()}.db"
            }
            propertyRegistry.add("claudeproxy.api-keys[0].name") { "test" }
            propertyRegistry.add("claudeproxy.api-keys[0].key") { SEED_API_KEY }
        }
    }
}
