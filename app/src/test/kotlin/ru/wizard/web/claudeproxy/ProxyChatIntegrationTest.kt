package ru.wizard.web.claudeproxy

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
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
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer

/**
 * M31, этап 2: исходящие запросы провайдера идут через привязанный прокси
 * (CONNECT-туннель FakeHttpProxy), без привязки — напрямую (регресс).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProxyChatIntegrationTest {

    @Autowired
    private lateinit var environment: Environment

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private lateinit var webTestClient: WebTestClient

    companion object {
        private val objectMapper = ObjectMapper()

        /** Тела, дошедшие до фейкового upstream. */
        private val upstreamBodies = CopyOnWriteArrayList<String>()

        private lateinit var upstream: DisposableServer
        private lateinit var fakeProxy: FakeHttpProxy

        init {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/test"))
            upstream = HttpServer.create()
                .port(0)
                .handle { request, response ->
                    request.receive().aggregate().asString().defaultIfEmpty("").flatMap { body ->
                        upstreamBodies.add(body)
                        response
                            .status(200)
                            .header("Content-Type", "application/json")
                            .sendString(
                                reactor.core.publisher.Mono.just(
                                    """{"id":"msg_proxy","type":"message","role":"assistant",
                                       "model":"proxy-model","content":[{"type":"text","text":"ok"}],
                                       "stop_reason":"end_turn",
                                       "usage":{"input_tokens":5,"output_tokens":3}}""",
                                ),
                            )
                            .then()
                    }
                }
                .bindNow()
            fakeProxy = FakeHttpProxy { upstream.port() }.also { it.start() }
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                "jdbc:sqlite:build/test/proxy-chat-itest-${UUID.randomUUID()}.db"
            }
            registry.add("claudeproxy.api-keys[0].name") { "itest" }
            registry.add("claudeproxy.api-keys[0].key") { "itest-key" }
        }
    }

    @BeforeEach
    fun setUp() {
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
        jdbcTemplate.update("DELETE FROM proxy_endpoint")
        upstreamBodies.clear()
    }

    /** Провайдер + модель; proxyName=null → напрямую. */
    private fun registerProvider(providerName: String, proxyName: String?): Long {
        val created = webTestClient.post().uri("/api/providers")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                buildMap {
                    put("name", providerName)
                    put("type", "anthropic")
                    put("baseUrl", "http://127.0.0.1:${upstream.port()}")
                    put("apiKey", "secret")
                    if (proxyName != null) put("proxyName", proxyName)
                },
            )
            .exchange()
            .expectStatus().isCreated
            .expectBody(String::class.java)
            .returnResult().responseBody!!
        val providerId = objectMapper.readTree(created).path("id").asLong()
        webTestClient.post().uri("/api/providers/$providerId/models")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                mapOf(
                    "publicName" to "proxy-model",
                    "upstreamName" to "proxy-model",
                    "reasoning" to "off",
                    "maxCompletionParam" to false,
                ),
            )
            .exchange()
            .expectStatus().isCreated
        return providerId
    }

    private fun registerProxyEndpoint() {
        webTestClient.post().uri("/api/proxies")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                mapOf(
                    "name" to "test-proxy",
                    "type" to "HTTP",
                    "host" to "127.0.0.1",
                    "port" to fakeProxy.port,
                ),
            )
            .exchange()
            .expectStatus().isOk
    }

    private fun postMessage(requestText: String) {
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", "itest-key")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"model":"proxy-model","max_tokens":32,
                   "messages":[{"role":"user","content":[{"type":"text","text":"$requestText"}]}]}""",
            )
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `запрос провайдера с прокси идёт через CONNECT-туннель`() {
        registerProxyEndpoint()
        registerProvider("via-proxy", proxyName = "test-proxy")

        val connectionsBefore = fakeProxy.connectionCount
        postMessage("через прокси")

        // тело дошло до upstream, соединение прошло через прокси
        assertEquals(1, upstreamBodies.size)
        assertTrue(upstreamBodies.single().contains("через прокси"))
        assertTrue(fakeProxy.connectionCount > connectionsBefore)
    }

    @Test
    fun `без привязки прокси запрос идёт напрямую - регресс`() {
        registerProxyEndpoint()
        registerProvider("direct", proxyName = null)

        val connectionsBefore = fakeProxy.connectionCount
        // другой текст — мимо кэша повторов первого теста
        postMessage("напрямую")

        assertEquals(1, upstreamBodies.size)
        assertTrue(upstreamBodies.single().contains("напрямую"))
        assertEquals(connectionsBefore, fakeProxy.connectionCount)
    }

    @Test
    fun `несуществующий прокси у провайдера - 400`() {
        webTestClient.post().uri("/api/providers")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                mapOf(
                    "name" to "bad-proxy-ref",
                    "type" to "anthropic",
                    "baseUrl" to "http://127.0.0.1:${upstream.port()}",
                    "apiKey" to "secret",
                    "proxyName" to "ghost",
                ),
            )
            .exchange()
            .expectStatus().isBadRequest
    }
}
