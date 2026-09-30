package ru.wizard.web.claudeproxy

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
 * M31, этап 1: CRUD прокси-эндпоинтов и проверка соединения. Фейковый
 * reactor-netty сервер играет роль HTTP-прокси: на absolute-URI GET он
 * отвечает 204 (для check), счётчик фиксирует обращения.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProxyEndpointIntegrationTest {

    @Autowired
    private lateinit var environment: Environment

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private lateinit var webTestClient: WebTestClient

    companion object {
        private val objectMapper = ObjectMapper()

        /** Целевой сервер за прокси: любой GET → 204. */
        private lateinit var targetServer: DisposableServer

        /** Мини HTTP-прокси: CONNECT мостится к targetServer. */
        private lateinit var fakeProxy: FakeHttpProxy

        init {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/test"))
            targetServer = HttpServer.create()
                .port(0)
                .handle { _, response -> response.status(204).send().then() }
                .bindNow()
            fakeProxy = FakeHttpProxy { targetServer.port() }.also { it.start() }
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                "jdbc:sqlite:build/test/proxy-itest-${UUID.randomUUID()}.db"
            }
            registry.add("claudeproxy.api-keys[0].name") { "itest" }
            registry.add("claudeproxy.api-keys[0].key") { "itest-key" }
        }
    }

    @BeforeEach
    fun setUp() {
        // гвард после инцидента 2026-09-30: тест не имеет права работать с чужой БД
        val datasourceUrl = environment.getProperty("spring.datasource.url")
        check(datasourceUrl != null && datasourceUrl.contains("build/test")) {
            "Test datasource must point into build/test, got: $datasourceUrl"
        }
        val serverPort = environment.getProperty("local.server.port", Int::class.java)!!
        webTestClient = WebTestClient.bindToServer()
            .baseUrl("http://127.0.0.1:$serverPort")
            .build()
        jdbcTemplate.update("DELETE FROM proxy_endpoint")
        jdbcTemplate.update("DELETE FROM model")
        jdbcTemplate.update("DELETE FROM provider")
    }

    private fun createProxy(
        name: String = "main-proxy",
        type: String = "HTTP",
        host: String = "127.0.0.1",
        port: Int = fakeProxy.port,
        password: String? = "proxy-secret",
    ): JsonNode {
        val body = LinkedHashMap<String, Any>()
        body["name"] = name
        body["type"] = type
        body["host"] = host
        body["port"] = port
        body["username"] = "proxy-user"
        password?.let { body["password"] = it }
        val responseBody = webTestClient.post().uri("/api/proxies")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult().responseBody!!
        return objectMapper.readTree(responseBody)
    }

    @Test
    fun `CRUD прокси - создание, чтение, обновление, удаление`() {
        val created = createProxy()
        assertEquals("main-proxy", created.path("name").asText())
        assertEquals("HTTP", created.path("type").asText())
        assertTrue(created.path("hasPassword").asBoolean())
        // пароль наружу не отдаётся ни в каком виде
        assertTrue(created.path("password").isMissingNode || created.path("password").isNull)
        assertEquals("proxy-user", created.path("username").asText())
        assertTrue(created.path("providerNames").isArray && created.path("providerNames").size() == 0)
        val proxyId = created.path("id").asLong()

        val list = objectMapper.readTree(
            webTestClient.get().uri("/api/proxies")
                .exchange()
                .expectStatus().isOk
                .expectBody(String::class.java)
                .returnResult().responseBody!!,
        )
        assertEquals(1, list.size())

        // обновление: порт и enabled, пароль не передан - сохранён
        webTestClient.put().uri("/api/proxies/$proxyId")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("port" to 8080, "enabled" to false))
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.port").isEqualTo(8080)
            .jsonPath("$.enabled").isEqualTo(false)
            .jsonPath("$.hasPassword").isEqualTo(true)
            .jsonPath("$.name").isEqualTo("main-proxy")

        webTestClient.delete().uri("/api/proxies/$proxyId")
            .exchange()
            .expectStatus().isOk
        webTestClient.get().uri("/api/proxies")
            .exchange()
            .expectBody()
            .jsonPath("$.length()").isEqualTo(0)
    }

    @Test
    fun `валидация - дубль имени 409, кривые поля 400`() {
        createProxy(name = "dup")
        webTestClient.post().uri("/api/proxies")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("name" to "dup", "type" to "HTTP", "host" to "127.0.0.1", "port" to 8080))
            .exchange()
            .expectStatus().isEqualTo(org.springframework.http.HttpStatus.CONFLICT)

        // несуществующий тип
        webTestClient.post().uri("/api/proxies")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("name" to "p2", "type" to "VPN", "host" to "127.0.0.1", "port" to 8080))
            .exchange()
            .expectStatus().isBadRequest
        // порт вне диапазона
        webTestClient.post().uri("/api/proxies")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("name" to "p3", "type" to "SOCKS5", "host" to "127.0.0.1", "port" to 70000))
            .exchange()
            .expectStatus().isBadRequest
        // пустое имя
        webTestClient.post().uri("/api/proxies")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("name" to " ", "type" to "HTTP", "host" to "127.0.0.1", "port" to 8080))
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    fun `удаление занятого прокси - 409 со списком провайдеров`() {
        val created = createProxy(name = "busy")
        val now = System.currentTimeMillis()
        jdbcTemplate.update(
            """INSERT INTO provider (name, type, base_url, api_key, extra_headers, proxy_name,
                 created_at, updated_at)
               VALUES ('p-user', 'anthropic', 'http://127.0.0.1:1', 'k', '[]', 'busy', ?, ?)""",
            now,
            now,
        )

        webTestClient.delete().uri("/api/proxies/${created.path("id").asLong()}")
            .exchange()
            .expectStatus().isBadRequest

        // список показывает привязку
        val list = objectMapper.readTree(
            webTestClient.get().uri("/api/proxies")
                .exchange()
                .expectStatus().isOk
                .expectBody(String::class.java)
                .returnResult().responseBody!!,
        )
        assertEquals("p-user", list.get(0).path("providerNames").get(0).asText())

        // после отвязки удаление проходит
        jdbcTemplate.update("UPDATE provider SET proxy_name = NULL WHERE name = 'p-user'")
        webTestClient.delete().uri("/api/proxies/${created.path("id").asLong()}")
            .exchange()
            .expectStatus().isOk
    }

    @Test
    fun `проверка соединения - OK через живой прокси, FAIL через мёртвый`() {
        val alive = createProxy(name = "alive")
        val testUrl = "http://example.test/check"
        val checked = objectMapper.readTree(
            webTestClient.post().uri("/api/proxies/${alive.path("id").asLong()}/check")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(mapOf("testUrl" to testUrl))
                .exchange()
                .expectStatus().isOk
                .expectBody(String::class.java)
                .returnResult().responseBody!!,
        )
        assertTrue(checked.path("lastCheckStatus").asText().startsWith("OK 204"), checked.toString())
        assertNotNull(checked.path("lastCheckAt").asLong())
        // запрос реально прошёл через фейковый прокси (CONNECT-туннель)
        assertTrue(fakeProxy.connectionCount >= 1, "proxy connections: ${fakeProxy.connectionCount}")

        // мёртвый порт - FAIL, запрос жив
        val dead = createProxy(name = "dead", port = 1)
        val failed = objectMapper.readTree(
            webTestClient.post().uri("/api/proxies/${dead.path("id").asLong()}/check")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(mapOf("testUrl" to testUrl))
                .exchange()
                .expectStatus().isOk
                .expectBody(String::class.java)
                .returnResult().responseBody!!,
        )
        assertTrue(failed.path("lastCheckStatus").asText().startsWith("FAIL"), failed.toString())
        assertFalse(fakeProxy.connectionCount > 5, "unexpected proxy traffic: ${fakeProxy.connectionCount}")
    }

    @Test
    fun `несуществующий прокси - 404 на update, delete и check`() {
        webTestClient.put().uri("/api/proxies/999")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("port" to 8080))
            .exchange()
            .expectStatus().isNotFound
        webTestClient.delete().uri("/api/proxies/999")
            .exchange()
            .expectStatus().isNotFound
        webTestClient.post().uri("/api/proxies/999/check")
            .exchange()
            .expectStatus().isNotFound
    }
}
