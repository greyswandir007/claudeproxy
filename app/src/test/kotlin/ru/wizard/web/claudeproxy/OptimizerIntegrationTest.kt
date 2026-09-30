package ru.wizard.web.claudeproxy

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import ru.wizard.web.claudeproxy.optimizer.OptimizerService

/**
 * M30: сжатие старых tool_result моделью-оптимизатором — сквозной сценарий.
 * Один фейковый reactor-netty сервер играет основного upstream (модель
 * main-model) и провайдера-оптимизатора (модель optimizer-model).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OptimizerIntegrationTest {

    @Autowired
    private lateinit var environment: org.springframework.core.env.Environment

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var optimizerService: OptimizerService

    private lateinit var webTestClient: WebTestClient

    companion object {
        private val objectMapper = ObjectMapper()
        private const val compressedText = "compressed by optimizer"

        private val mainUpstreamBodies = CopyOnWriteArrayList<String>()
        private val optimizerModelRequests = AtomicInteger()
        private val failOptimizer = AtomicBoolean(false)
        private val slowOptimizer = AtomicBoolean(false)
        private lateinit var fakeServer: DisposableServer

        init {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/test"))
            fakeServer = HttpServer.create()
                .port(0)
                .handle { request, response ->
                    request.receive().aggregate().asString().defaultIfEmpty("").flatMap { body ->
                        val model = runCatching { objectMapper.readTree(body).path("model").asText() }
                            .getOrDefault("")
                        when (model) {
                            "optimizer-model" -> {
                                optimizerModelRequests.incrementAndGet()
                                when {
                                    failOptimizer.get() ->
                                        response.status(500).sendString(Mono.just("boom")).then()
                                    slowOptimizer.get() ->
                                        Mono.delay(Duration.ofSeconds(5)).then(
                                            response
                                                .status(200)
                                                .header("Content-Type", "application/json")
                                                .sendString(Mono.just(optimizerJson()))
                                                .then(),
                                        )
                                    else ->
                                        response
                                            .status(200)
                                            .header("Content-Type", "application/json")
                                            .sendString(Mono.just(optimizerJson()))
                                            .then()
                                }
                            }
                            else -> {
                                // основной upstream: обычный ответ anthropic, тело запоминаем
                                mainUpstreamBodies.add(body)
                                response
                                    .status(200)
                                    .header("Content-Type", "application/json")
                                    .sendString(Mono.just(mainJson()))
                                    .then()
                            }
                        }
                    }
                }
                .bindNow()
        }

        private fun mainJson() =
            """{"id":"msg_main","type":"message","role":"assistant","model":"main-model",
               "content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn",
               "usage":{"input_tokens":10,"output_tokens":5}}"""

        private fun optimizerJson() =
            """{"content":[{"type":"text","text":"$compressedText"}],
               "stop_reason":"end_turn","usage":{"input_tokens":3,"output_tokens":2}}"""

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                "jdbc:sqlite:build/test/optimizer-itest-${UUID.randomUUID()}.db"
            }
            registry.add("claudeproxy.api-keys[0].name") { "itest" }
            registry.add("claudeproxy.api-keys[0].key") { "itest-key" }
            registry.add("claudeproxy.optimizer.request-timeout-milliseconds") { "500" }
        }
    }

    @BeforeEach
    fun setUp() {
        // Страховка после инцидента 2026-09-30: без @JvmStatic у @DynamicPropertySource
        // свойства не применялись, и тест поднимался на дефолтной БД. Гард валяет тест
        // ДО любых DELETE, если датасорс не указывает в тестовый каталог.
        val datasourceUrl = environment.getProperty("spring.datasource.url")
        check(datasourceUrl != null && datasourceUrl.contains("build/test")) {
            "Test datasource must point into build/test, got: $datasourceUrl"
        }
        val serverPort = environment.getProperty("local.server.port", Int::class.java)!!
        webTestClient = WebTestClient.bindToServer()
            .baseUrl("http://127.0.0.1:$serverPort")
            .build()
        jdbcTemplate.update("DELETE FROM model")
        jdbcTemplate.update("DELETE FROM provider")
        jdbcTemplate.update("DELETE FROM usage_event")
        jdbcTemplate.update("DELETE FROM optimizer_config")
        mainUpstreamBodies.clear()
        optimizerModelRequests.set(0)
        failOptimizer.set(false)
        slowOptimizer.set(false)
        // сброс настройки и breaker между тестами
        runBlocking {
            optimizerService.updateConfig(
                OptimizerService.OptimizerConfigRequest(enabled = false, providerName = null, model = null),
            )
        }

        val baseUrl = "http://127.0.0.1:${fakeServer.port()}"
        val createdMain = webTestClient.post().uri("/api/providers")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                mapOf(
                    "name" to "main-provider",
                    "type" to "anthropic",
                    "baseUrl" to baseUrl,
                    "apiKey" to "main-secret",
                    "settingOverrides" to mapOf("TRIM_OLD_TOOL_RESULTS" to "true"),
                ),
            )
            .exchange()
            .expectStatus().isCreated
            .expectBody(String::class.java)
            .returnResult().responseBody!!
        val mainProviderId = objectMapper.readTree(createdMain).path("id").asLong()
        assertNotNull(mainProviderId)
        webTestClient.post().uri("/api/providers/$mainProviderId/models")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                mapOf(
                    "publicName" to "main-model",
                    "upstreamName" to "main-model",
                    "reasoning" to "off",
                    "maxCompletionParam" to false,
                ),
            )
            .exchange()
            .expectStatus().isCreated
        // оптимизаторный провайдер — без моделей, только имя
        webTestClient.post().uri("/api/providers")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                mapOf(
                    "name" to "optimizer-provider",
                    "type" to "anthropic",
                    "baseUrl" to baseUrl,
                    "apiKey" to "optimizer-secret",
                ),
            )
            .exchange()
            .expectStatus().isCreated
    }

    /** Запрос с count tool_result (по 900+ символов различимого текста) + финальный вопрос. */
    private fun messagesRequest(count: Int, prefix: String, finalText: String): String {
        val texts = (0 until count).map { oldText(prefix, it) }
        val messagesJson = texts.joinToString(",") { text ->
            """{"role":"user","content":[{"type":"tool_result","tool_use_id":"tu","content":"$text"}]}"""
        }
        return """{"model":"main-model","max_tokens":64,"messages":[$messagesJson,
            {"role":"user","content":[{"type":"text","text":"$finalText"}]}]}""".trimIndent()
    }

    private fun oldText(prefix: String, index: Int): String = "$prefix-$index-${"x".repeat(900)}"

    private fun postMessages(requestJson: String) {
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", "itest-key")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestJson)
            .exchange()
            .expectStatus().isOk
    }

    private fun awaitSavedTokens(): Long {
        var savedTokens = -1L
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val rows = jdbcTemplate.queryForList("SELECT saved_tokens AS saved FROM usage_event")
            val latest = rows.maxOfOrNull { (it["saved"] as Number).toLong() }
            if (latest != null) {
                savedTokens = latest
                break
            }
            Thread.sleep(100)
        }
        return savedTokens
    }

    @Test
    fun `выключенный оптимизатор обрезает маркером как в M11`() {
        val request = messagesRequest(6, "m11", "final")

        postMessages(request)

        assertEquals(0, optimizerModelRequests.get())
        val upstreamBody = mainUpstreamBodies.single()
        assertEquals(2, Regex.fromLiteral("[trimmed by claudeproxy]").findAll(upstreamBody).count())
        assertTrue(!upstreamBody.contains("m11-0-"))
        // 2 старых блока целиком: экономия = 2 * длина / 4
        val expected = 2 * oldText("m11", 0).length / 4
        assertEquals(expected.toLong(), awaitSavedTokens())
    }

    @Test
    fun `включённый оптимизатор сжимает старые tool_result`() {
        runBlocking {
            optimizerService.updateConfig(
                OptimizerService.OptimizerConfigRequest(true, "optimizer-provider", "optimizer-model"),
            )
        }
        val statsBefore = optimizerService.stats()

        postMessages(messagesRequest(6, "m30", "final"))

        val upstreamBody = mainUpstreamBodies.single()
        assertEquals(2, Regex.fromLiteral(compressedText).findAll(upstreamBody).count())
        assertTrue(!upstreamBody.contains("m30-0-"))
        // экономия = (2 * длина - 2 * compressedText) / 4
        val expected = (2 * oldText("m30", 0).length - 2 * compressedText.length) / 4
        assertEquals(expected.toLong(), awaitSavedTokens())
        assertEquals(2, optimizerModelRequests.get())
        val stats = optimizerService.stats()
        assertEquals(statsBefore.compressions + 2, stats.compressions)
        assertEquals(statsBefore.modelTokensSpent + 10, stats.modelTokensSpent)
    }

    @Test
    fun `повторное сжатие идёт из кэша оптимизатора`() {
        runBlocking {
            optimizerService.updateConfig(
                OptimizerService.OptimizerConfigRequest(true, "optimizer-provider", "optimizer-model"),
            )
        }

        // первый запрос: два старых блока сжимаются моделью
        postMessages(messagesRequest(6, "cache", "first"))
        assertEquals(2, optimizerModelRequests.get())
        // второй: те же старые тексты, другой финальный вопрос (мимо кэша повторов)
        postMessages(messagesRequest(6, "cache", "second"))

        assertEquals(2, mainUpstreamBodies.size)
        assertTrue(mainUpstreamBodies.all { body ->
            Regex.fromLiteral(compressedText).findAll(body).count() == 2
        })
        // новых вызовов модели нет — сжатия взяты из LRU-кэша
        assertEquals(2, optimizerModelRequests.get())
        assertTrue(optimizerService.stats().cacheHits >= 2L)
    }

    @Test
    fun `отказ оптимизатора - маркер, breaker открывается и глушит вызовы`() {
        failOptimizer.set(true)
        runBlocking {
            optimizerService.updateConfig(
                OptimizerService.OptimizerConfigRequest(true, "optimizer-provider", "optimizer-model"),
            )
        }

        postMessages(messagesRequest(6, "fail1", "final"))
        assertEquals(2, optimizerModelRequests.get())
        assertTrue(
            mainUpstreamBodies.single().contains("[trimmed by claudeproxy]"),
        )

        // вторая порция отказов: 4 подряд >= порога 3 → breaker OPEN
        postMessages(messagesRequest(6, "fail2", "final"))
        assertEquals(4, optimizerModelRequests.get())

        assertEquals("OPEN", optimizerService.stats().circuitState)
        // третий запрос не дёргает модель вообще
        postMessages(messagesRequest(6, "fail3", "final"))
        assertEquals(4, optimizerModelRequests.get())
        assertEquals(3, mainUpstreamBodies.size)
    }

    @Test
    fun `медленный оптимизатор обрывается по таймауту, запрос жив`() {
        slowOptimizer.set(true)
        runBlocking {
            optimizerService.updateConfig(
                OptimizerService.OptimizerConfigRequest(true, "optimizer-provider", "optimizer-model"),
            )
        }

        val startedAt = System.currentTimeMillis()
        postMessages(messagesRequest(6, "slow", "final"))
        val elapsedMilliseconds = System.currentTimeMillis() - startedAt

        assertTrue(elapsedMilliseconds < 5_000, "elapsed $elapsedMilliseconds ms")
        assertTrue(mainUpstreamBodies.single().contains("[trimmed by claudeproxy]"))
    }

    @Test
    fun `настройка через API - валидация и чтение`() {
        // при выключенной фиче оба эндпоинта доступны
        webTestClient.get().uri("/api/optimizer/stats")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.circuitState").isEqualTo("OFF")
        webTestClient.get().uri("/api/optimizer/config")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.enabled").isEqualTo(false)

        // несуществующий провайдер -> 400
        webTestClient.put().uri("/api/optimizer/config")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("enabled" to true, "providerName" to "ghost", "model" to "some-model"))
            .exchange()
            .expectStatus().isBadRequest
        // enabled без модели -> 400
        webTestClient.put().uri("/api/optimizer/config")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("enabled" to true, "providerName" to "optimizer-provider", "model" to ""))
            .exchange()
            .expectStatus().isBadRequest

        // валидная настройка сохраняется и читается обратно
        webTestClient.put().uri("/api/optimizer/config")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                mapOf("enabled" to true, "providerName" to "optimizer-provider", "model" to "optimizer-model"),
            )
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.enabled").isEqualTo(true)
            .jsonPath("$.providerName").isEqualTo("optimizer-provider")
            .jsonPath("$.model").isEqualTo("optimizer-model")
        assertEquals(
            OptimizerService.OptimizerConfig(true, "optimizer-provider", "optimizer-model"),
            optimizerService.config(),
        )
    }
}
