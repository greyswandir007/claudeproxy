package ru.wizard.web.claudeproxy

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
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
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer

/**
 * Таймауты исходящих соединений (claudeproxy.upstream.*): замолчавший апстрим
 * не висит вечно — клиент получает 5xx за ограниченное время. Проверяются оба
 * пути: напрямую и через HTTP-прокси (CONNECT-туннель).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UpstreamTimeoutIntegrationTest {

    @Autowired
    private lateinit var environment: Environment

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private lateinit var webTestClient: WebTestClient

    companion object {
        private val objectMapper = ObjectMapper()

        /** Апстрим, который принимает запрос и никогда не отвечает. */
        private lateinit var silentUpstream: DisposableServer
        private lateinit var fakeProxy: FakeHttpProxy

        init {
            Files.createDirectories(Path.of("build/test"))
            silentUpstream = HttpServer.create()
                .port(0)
                .handle { request, _ -> request.receive().aggregate().then(Mono.never<Void>()) }
                .bindNow()
            fakeProxy = FakeHttpProxy { silentUpstream.port() }.also { it.start() }
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                "jdbc:sqlite:build/test/upstream-timeout-itest-${UUID.randomUUID()}.db"
            }
            registry.add("claudeproxy.api-keys[0].name") { "itest" }
            registry.add("claudeproxy.api-keys[0].key") { "itest-key" }
            registry.add("claudeproxy.upstream.connect-timeout-milliseconds") { "2000" }
            registry.add("claudeproxy.upstream.read-timeout-seconds") { "1" }
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
            .responseTimeout(Duration.ofSeconds(20))
            .build()
        jdbcTemplate.update("DELETE FROM model")
        jdbcTemplate.update("DELETE FROM provider")
        jdbcTemplate.update("DELETE FROM usage_event")
        jdbcTemplate.update("DELETE FROM optimizer_config")
        jdbcTemplate.update("DELETE FROM proxy_endpoint")
    }

    /** Провайдер + модель, направленные на молчащий апстрим. */
    private fun registerProvider(providerName: String, proxyName: String?) {
        val created = webTestClient.post().uri("/api/providers")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                buildMap {
                    put("name", providerName)
                    put("type", "anthropic")
                    put("baseUrl", "http://127.0.0.1:${silentUpstream.port()}")
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
                    "publicName" to "$providerName-model",
                    "upstreamName" to "$providerName-model",
                    "reasoning" to "off",
                    "maxCompletionParam" to false,
                ),
            )
            .exchange()
            .expectStatus().isCreated
    }

    /** Регистрирует proxy_endpoint и привязывает к нему нового провайдера. */
    private fun registerProxiedProvider() {
        webTestClient.post().uri("/api/proxies")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                mapOf(
                    "name" to "itest-silent-proxy",
                    "type" to "HTTP",
                    "host" to "127.0.0.1",
                    "port" to fakeProxy.port,
                ),
            )
            .exchange()
            .expectStatus().isOk
        registerProvider("silent-proxied", "itest-silent-proxy")
    }

    private fun chatBody(modelName: String): String = objectMapper.writeValueAsString(
        mapOf(
            "model" to modelName,
            "max_tokens" to 8,
            "stream" to false,
            "messages" to listOf(mapOf("role" to "user", "content" to "ping")),
        ),
    )

    @Test
    fun `direct upstream silence ends with error, not hang`() {
        registerProvider("silent-direct", null)
        val started = System.nanoTime()
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", "itest-key")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(chatBody("silent-direct-model"))
            .exchange()
            .expectStatus().is5xxServerError
        val elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000.0
        assertTrue(elapsedSeconds < 15.0) { "upstream silence must fail fast, took $elapsedSeconds s" }
    }

    @Test
    fun `proxied upstream silence ends with error, not hang`() {
        registerProxiedProvider()
        val started = System.nanoTime()
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", "itest-key")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(chatBody("silent-proxied-model"))
            .exchange()
            .expectStatus().is5xxServerError
        val elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000.0
        assertTrue(elapsedSeconds < 15.0) { "upstream silence must fail fast, took $elapsedSeconds s" }
        assertTrue(fakeProxy.connectionCount > 0) { "request must go through the proxy" }
    }
}
