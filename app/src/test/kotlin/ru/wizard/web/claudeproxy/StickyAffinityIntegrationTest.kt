package ru.wizard.web.claudeproxy

import com.fasterxml.jackson.databind.ObjectMapper
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.util.CharsetUtil
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer

/**
 * M27: sticky-аффинность разговоров. Общий префикс запроса (system + tools +
 * первое сообщение) держит разговор на одном провайдере из равноприоритетных;
 * новые разговоры продолжают чередоваться round-robin; строгий приоритет
 * доминирует над привязкой; count_tokens следует привязке, но не создаёт её.
 * Фейковый upstream считает запросы по upstream-модели.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["claudeproxy.conversation-affinity.enabled=true"],
)
class StickyAffinityIntegrationTest {

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
    fun `растущий разговор держится на одном провайдере`() {
        val oneId = createAnthropicProvider("st-one", "st-eq", "st-a", 10)
        val twoId = createAnthropicProvider("st-two", "st-eq", "st-b", 10)
        try {
            sendTurn("st-eq", "st-начало", tailCount = 0)
            sendTurn("st-eq", "st-начало", tailCount = 1)
            sendTurn("st-eq", "st-начало", tailCount = 2)
            assertEquals(
                listOf("st-one", "st-one", "st-one"),
                awaitSuccessProviders("st-eq", 3),
            )
            assertEquals(0, upstreamRequests("st-b"))
            // диагностика видит включённую аффинность и привязки
            val diagnostics = webTestClient.get().uri("/api/conversation-affinity-stats")
                .exchange().expectStatus().isOk
                .expectBody(String::class.java).returnResult().responseBody!!
            assertTrue(diagnostics.contains(""""enabled":true"""), diagnostics)
            // один разговор в карте; binds считает каждый успешный ход (обновление привязки)
            assertTrue(diagnostics.contains(""""entries":1"""), diagnostics)
            assertTrue(diagnostics.contains(""""binds":3"""), diagnostics)
        } finally {
            deleteProvider(oneId)
            deleteProvider(twoId)
        }
    }

    @Test
    fun `новые разговоры чередуются и возвращаются к своей привязке`() {
        val oneId = createAnthropicProvider("sn-one", "sn-eq", "sn-a", 10)
        val twoId = createAnthropicProvider("sn-two", "sn-eq", "sn-b", 10)
        try {
            sendTurn("sn-eq", "sn-разговор-один", tailCount = 0)
            sendTurn("sn-eq", "sn-разговор-два", tailCount = 0)
            // первый разговор продолжается и возвращается на своего провайдера
            sendTurn("sn-eq", "sn-разговор-один", tailCount = 1)
            assertEquals(
                listOf("sn-one", "sn-two", "sn-one"),
                awaitSuccessProviders("sn-eq", 3),
            )
        } finally {
            deleteProvider(oneId)
            deleteProvider(twoId)
        }
    }

    @Test
    fun `стриминг разговора держится на одном провайдере`() {
        val oneId = createAnthropicProvider("ss-one", "ss-eq", "ss-a", 10)
        val twoId = createAnthropicProvider("ss-two", "ss-eq", "ss-b", 10)
        try {
            sendStreamTurn("ss-eq", "ss-начало", tailCount = 0)
            sendStreamTurn("ss-eq", "ss-начало", tailCount = 1)
            assertEquals(
                listOf("ss-one", "ss-one"),
                awaitSuccessProviders("ss-eq", 2),
            )
            assertEquals(0, upstreamRequests("ss-b"))
        } finally {
            deleteProvider(oneId)
            deleteProvider(twoId)
        }
    }

    @Test
    fun `фейловер перепривязывает разговор и держится на запасном`() {
        failingUpstreamModels.add("fl-a")
        val oneId = createAnthropicProvider("fl-one", "fl-eq", "fl-a", 10)
        val twoId = createAnthropicProvider("fl-two", "fl-eq", "fl-b", 10)
        try {
            // ход 1: основной канал отказал → запасной обслужил и принял привязку
            sendTurn("fl-eq", "fl-начало", tailCount = 0)
            assertEquals(listOf("fl-two"), awaitSuccessProviders("fl-eq", 1))
            assertEquals(1, upstreamRequests("fl-a"))

            // короткий кулдаун (Retry-After: 1 от стаба) истёк, основной здоров —
            // но разговор остаётся на запасном: кэш промпта теперь живёт там
            failingUpstreamModels.clear()
            waitForCooldown()
            sendTurn("fl-eq", "fl-начало", tailCount = 1)
            assertEquals(
                listOf("fl-two", "fl-two"),
                awaitSuccessProviders("fl-eq", 2),
            )
            assertEquals(1, upstreamRequests("fl-a"))
        } finally {
            deleteProvider(oneId)
            deleteProvider(twoId)
        }
    }

    @Test
    fun `строгий приоритет доминирует над привязкой`() {
        failingUpstreamModels.add("dom-a")
        val oneId = createAnthropicProvider("dom-one", "dom", "dom-a", 5)
        val twoId = createAnthropicProvider("dom-two", "dom", "dom-b", 10)
        try {
            // основной (priority 5) отказал → обслужил запасной, привязка на нём
            sendTurn("dom", "dom-начало", tailCount = 0)
            assertEquals(listOf("dom-two"), awaitSuccessProviders("dom", 1))

            // основной восстановился: сегмент запасного одиночный (priority 10),
            // через границу приоритета привязка не продвигается
            failingUpstreamModels.clear()
            waitForCooldown()
            sendTurn("dom", "dom-начало", tailCount = 1)
            assertEquals(
                listOf("dom-two", "dom-one"),
                awaitSuccessProviders("dom", 2),
            )
        } finally {
            deleteProvider(oneId)
            deleteProvider(twoId)
        }
    }

    @Test
    fun `count_tokens следует привязке но не создаёт её`() {
        val oneId = createAnthropicProvider("ct-one", "ct-eq", "ct-a", 10)
        val twoId = createAnthropicProvider("ct-two", "ct-eq", "ct-b", 10)
        try {
            // чужой разговор тратит первый шаг ротации и привязывается к первому
            sendTurn("ct-eq", "ct-чужой-разговор", tailCount = 0)
            assertEquals(listOf("ct-one"), awaitSuccessProviders("ct-eq", 1))

            // свежий разговор: count_tokens идёт по базовому порядку (первый канал)
            // и НЕ закрепляет разговор за ним
            sendCountTokens("ct-eq", "ct-начало")
            assertEquals(2, upstreamRequests("ct-a"))

            // первый ход разговора ротирует на второй канал — привязки нет
            sendTurn("ct-eq", "ct-начало", tailCount = 0)
            assertEquals(
                listOf("ct-one", "ct-two"),
                awaitSuccessProviders("ct-eq", 2),
            )

            // теперь привязка есть: count_tokens следует ей
            sendCountTokens("ct-eq", "ct-начало")
            sendCountTokens("ct-eq", "ct-начало")
            assertEquals(2, upstreamRequests("ct-a"))
            assertEquals(3, upstreamRequests("ct-b"))
        } finally {
            deleteProvider(oneId)
            deleteProvider(twoId)
        }
    }

    /** Ход разговора: общий префикс (system + tools + первое сообщение), хвост растёт. */
    private fun sendTurn(publicName: String, firstMessage: String, tailCount: Int) {
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(conversationBody(publicName, firstMessage, tailCount, stream = false))
            .exchange()
            .expectStatus().isOk
    }

    /** Стриминговый ход разговора; тело вычитывается, чтобы doFinally зафиксировал успех. */
    private fun sendStreamTurn(publicName: String, firstMessage: String, tailCount: Int) {
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(conversationBody(publicName, firstMessage, tailCount, stream = true))
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
    }

    private fun sendCountTokens(publicName: String, firstMessage: String) {
        webTestClient.post().uri("/v1/messages/count_tokens")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(conversationBody(publicName, firstMessage, tailCount = 0, stream = false))
            .exchange()
            .expectStatus().isOk
    }

    private fun conversationBody(publicName: String, firstMessage: String, tailCount: Int, stream: Boolean): String {
        val messages = StringBuilder()
        messages.append("""{"role":"user","content":"$firstMessage"}""")
        for (index in 0 until tailCount) {
            messages.append(""",{"role":"user","content":"хвост-$publicName-$index"}""")
        }
        val streamField = if (stream) "\"stream\":true," else ""
        return """{"model":"$publicName","max_tokens":16,$streamField""" +
            """"system":"система-$publicName","tools":[{"name":"инструмент-$publicName"}],""" +
            """"messages":[$messages]}"""
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

    /** Провайдеры успешных ходов (status = 200) в порядке записи usage-событий. */
    private fun awaitSuccessProviders(model: String, expectedCount: Int): List<String> = runBlocking {
        withTimeout(15_000) {
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

    private fun upstreamRequests(upstreamName: String): Int =
        requestCountsByUpstreamModel[upstreamName]?.get() ?: 0

    /** Кулдаун отказа (Retry-After: 1) + запас на синхронность. */
    private fun waitForCooldown() {
        Thread.sleep(2_000)
    }

    companion object {
        private const val SEED_API_KEY = "test-key-123"
        private val objectMapper = ObjectMapper()

        private val requestCountsByUpstreamModel = ConcurrentHashMap<String, AtomicInteger>()

        /** Upstream-модели, отвечающие 500 с коротким Retry-After. */
        private val failingUpstreamModels: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

        private lateinit var upstream: DisposableServer

        init {
            // каталог для тестовых SQLite: DynamicPropertySource вычисляется позднее,
            // поэтому создаём заранее
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
                                    request.uri().endsWith("/count_tokens") -> {
                                    requestCountsByUpstreamModel
                                        .computeIfAbsent(model) { AtomicInteger() }
                                        .incrementAndGet()
                                    ok(response, """{"input_tokens":42}""")
                                }

                                request.method() == io.netty.handler.codec.http.HttpMethod.POST &&
                                    request.uri().endsWith("/messages") -> {
                                    requestCountsByUpstreamModel
                                        .computeIfAbsent(model) { AtomicInteger() }
                                        .incrementAndGet()
                                    if (model in failingUpstreamModels) {
                                        // короткий кулдаун, чтобы тест восстановления
                                        // не ждал минуту
                                        response.status(HttpResponseStatus.INTERNAL_SERVER_ERROR)
                                            .header("Content-Type", "application/json")
                                            .header("Retry-After", "1")
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
            """{"id":"msg_st","type":"message","role":"assistant","model":"$model",""" +
                """"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn",""" +
                """"usage":{"input_tokens":1,"output_tokens":1,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}"""

        private fun upstreamPort(): Int = upstream.port()

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(propertyRegistry: DynamicPropertyRegistry) {
            propertyRegistry.add("spring.datasource.url") {
                "jdbc:sqlite:build/test/sticky-itest-${UUID.randomUUID()}.db"
            }
            propertyRegistry.add("claudeproxy.api-keys[0].name") { "test" }
            propertyRegistry.add("claudeproxy.api-keys[0].key") { SEED_API_KEY }
        }
    }
}
