package ru.wizard.web.claudeproxy.cacheprovider

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
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
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer

/**
 * Интеграционный тест пайплайна внешних кэш-провайдеров: прокси +
 * фейковый upstream + фейковый кэш-сервис по спеке docs/cache-providers.md.
 * Проверяет consult (hit без похода к провайдеру), store после ответа
 * апстрима и feedback по неявным сигналам разговора.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CacheProviderIntegrationTest {

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
        CacheStub.lookups.clear()
        CacheStub.stores.clear()
        CacheStub.feedbacks.clear()
        CacheStub.respondHit.set(false)
        UpstreamStub.hits.set(0)
        jdbcTemplate.update("DELETE FROM cache_provider")
        // регрессия: создание через REST (POST когда-то падал 500 — WebFlux-кодек
        // не умеет data class без Kotlin-модуля; теперь тело парсится строкой)
        webTestClient.post().uri("/api/cache-providers")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"name":"ext-cache","url":"http://127.0.0.1:${CacheStub.port}"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.name").isEqualTo("ext-cache")
            .jsonPath("$.enabled").isEqualTo(true)
        // привязка через REST: реестр моделей перезагружается сам
        val providers = webTestClient.get().uri("/api/providers")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
        val provider = ObjectMapper().readTree(providers)[0]
        webTestClient.put().uri("/api/providers/${provider.path("id").asLong()}")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"name":"fake","type":"anthropic",
                   "baseUrl":"${provider.path("baseUrl").asText()}",
                   "settingOverrides":{"CACHE_PROVIDER":"ext-cache"}}""",
            )
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `попадание внешнего кэша отдаётся без похода к провайдеру`() {
        CacheStub.respondHit.set(true)

        val response = webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"model":"fake-model","max_tokens":100,"stream":false,
                   "messages":[{"role":"user","content":"кэш-вопрос первый"}]}""",
            )
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!

        assertTrue(response.contains("из внешнего кэша"), response)
        assertTrue(UpstreamStub.hits.get() == 0, "провайдер не должен получать запрос")
        assertTrue(CacheStub.lookups.size == 1, "lookup должен быть вызван один раз")
        val lookup = CacheStub.lookups.peek()
        assertTrue(lookup.has("requestHash"), "lookup несёт хэш")
        assertTrue(lookup.has("requestJson"), "lookup несёт тело запроса")
    }

    @Test
    fun `промах пишет ответ апстрима во внешний кэш`() {
        val request = """{"model":"fake-model","max_tokens":100,"stream":false,
            "messages":[{"role":"user","content":"кэш-вопрос на промах"}]}"""
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus().isOk

        val deadline = System.currentTimeMillis() + 10_000
        while (CacheStub.stores.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        val store = CacheStub.stores.peek()
        assertTrue(store != null, "store должен прийти во внешний кэш")
        assertTrue(store!!.path("responseBody").asText().contains("upstream"), store.toString())
        assertTrue(store.path("inputTokens").asLong(0) == 10L, "токены апстрима передаются")
    }

    @Test
    fun `feedback по неявному сигналу разговора`() {
        CacheStub.respondHit.set(true)
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"model":"fake-model","max_tokens":100,"stream":false,
                   "messages":[{"role":"user","content":"feedback разговор"}]}""",
            )
            .exchange()
            .expectStatus().isOk

        // спокойное продолжение того же разговора → положительный feedback
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"model":"fake-model","max_tokens":100,"stream":false,
                   "messages":[
                     {"role":"user","content":"feedback разговор"},
                     {"role":"assistant","content":"из внешнего кэша"},
                     {"role":"user","content":"продолжаем"}
                   ]}""",
            )
            .exchange()
            .expectStatus().isOk

        val deadline = System.currentTimeMillis() + 10_000
        while (CacheStub.feedbacks.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        val feedback = CacheStub.feedbacks.peek()
        assertTrue(feedback != null, "feedback должен прийти кэш-провайдеру")
        assertTrue(feedback!!.path("positive").asBoolean(), feedback.toString())
    }

    companion object {
        private const val SEED_API_KEY = "cache-provider-test-key"
        private val objectMapper = ObjectMapper()

        /** Счётчик попаданий фейкового апстрима. */
        private object UpstreamStub {
            val hits = java.util.concurrent.atomic.AtomicInteger()
        }

        /** Фейковый кэш-сервис по спеке: lookup/store/feedback. */
        private object CacheStub {
            val lookups = ConcurrentLinkedQueue<JsonNode>()
            val stores = ConcurrentLinkedQueue<JsonNode>()
            val feedbacks = ConcurrentLinkedQueue<JsonNode>()
            val respondHit = java.util.concurrent.atomic.AtomicBoolean(false)
            var port: Int = 0
        }

        private lateinit var upstream: DisposableServer

        init {
            Files.createDirectories(Path.of("build/test"))

            upstream = HttpServer.create().port(0)
                .handle { request, response ->
                    request.receive().aggregate().asString().defaultIfEmpty("")
                        .flatMap {
                            UpstreamStub.hits.incrementAndGet()
                            response.status(io.netty.handler.codec.http.HttpResponseStatus.OK)
                                .header("Content-Type", "application/json")
                                .sendString(
                                    reactor.core.publisher.Mono.just(
                                        """{"id":"msg_up","type":"message","role":"assistant",
                                           "model":"upstream-model",
                                           "content":[{"type":"text","text":"upstream ответ"}],
                                           "stop_reason":"end_turn",
                                           "usage":{"input_tokens":10,"output_tokens":20}}""",
                                    ),
                                )
                                .then()
                        }
                }
                .bindNow()

            val cacheServer = HttpServer.create().port(0)
                .handle { request, response ->
                    request.receive().aggregate().asString().defaultIfEmpty("")
                        .flatMap { requestBody ->
                            val node = runCatching { objectMapper.readTree(requestBody) }.getOrNull()
                            when (request.uri()) {
                                "/v1/cache/lookup" -> {
                                    CacheStub.lookups.add(node)
                                    val body = if (CacheStub.respondHit.get()) {
                                        val inner = ObjectMapper().createObjectNode()
                                        inner.put("id", "msg_ext")
                                        inner.put("type", "message")
                                        inner.put("role", "assistant")
                                        inner.put("model", "fake-model")
                                        inner.putArray("content").addObject()
                                            .put("type", "text").put("text", "из внешнего кэша")
                                        inner.put("stop_reason", "end_turn")
                                        inner.putObject("usage")
                                            .put("input_tokens", 100).put("output_tokens", 10)
                                        val outer = ObjectMapper().createObjectNode()
                                        outer.put("hit", true)
                                        outer.put("answerId", "ans-1")
                                        outer.put("responseFormat", "JSON")
                                        outer.put("responseBody", ObjectMapper().writeValueAsString(inner))
                                        outer.put("confidence", 1.0)
                                        outer.put("inputTokens", 100)
                                        outer.put("outputTokens", 10)
                                        ObjectMapper().writeValueAsString(outer)
                                    } else {
                                        """{"hit":false}"""
                                    }
                                    response.status(io.netty.handler.codec.http.HttpResponseStatus.OK)
                                        .header("Content-Type", "application/json")
                                        .sendString(reactor.core.publisher.Mono.just(body))
                                        .then()
                                }
                                "/v1/cache/store" -> {
                                    CacheStub.stores.add(node)
                                    response.status(io.netty.handler.codec.http.HttpResponseStatus.OK)
                                        .header("Content-Type", "application/json")
                                        .sendString(reactor.core.publisher.Mono.just("""{"stored":true}"""))
                                        .then()
                                }
                                "/v1/cache/feedback" -> {
                                    CacheStub.feedbacks.add(node)
                                    response.status(io.netty.handler.codec.http.HttpResponseStatus.OK)
                                        .header("Content-Type", "application/json")
                                        .sendString(reactor.core.publisher.Mono.just("""{"applied":1}"""))
                                        .then()
                                }
                                else ->
                                    response.status(io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND)
                                        .send()
                                        .then()
                            }
                        }
                }
                .bindNow()
            CacheStub.port = cacheServer.port()
        }

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(propertyRegistry: DynamicPropertyRegistry) {
            propertyRegistry.add("spring.datasource.url") {
                "jdbc:sqlite:build/test/cacheprovider-itest-${UUID.randomUUID()}.db"
            }
            propertyRegistry.add("claudeproxy.providers[0].name") { "fake" }
            propertyRegistry.add("claudeproxy.providers[0].type") { "anthropic" }
            propertyRegistry.add("claudeproxy.providers[0].base-url") {
                "http://127.0.0.1:${upstream.port()}"
            }
            propertyRegistry.add("claudeproxy.providers[0].api-key") { "upstream-secret" }
            propertyRegistry.add("claudeproxy.providers[0].models[0].public") { "fake-model" }
            propertyRegistry.add("claudeproxy.providers[0].models[0].upstream") { "upstream-model" }
            propertyRegistry.add("claudeproxy.api-keys[0].name") { "test" }
            propertyRegistry.add("claudeproxy.api-keys[0].key") { SEED_API_KEY }
            // внешний кэш вкручивается в тесте через cache_provider/настройку
        }
    }
}
