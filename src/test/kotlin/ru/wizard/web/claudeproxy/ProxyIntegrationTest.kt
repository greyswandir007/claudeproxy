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

    @Test
    fun `openai не-stream переводит ответ и пишет usage`() {
        val responseBody = webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(CLAUDE_REQUEST_WITH_TOOLS)
            .exchange().expectStatus().isOk
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectBody(String::class.java).returnResult().responseBody!!

        // Формат Claude: thinking + text + tool_use с разобранным input
        val responseNode = objectMapper.readTree(responseBody)
        assertEquals("message", responseNode.path("type").asText())
        assertEquals("fake-openai-model", responseNode.path("model").asText())
        assertEquals("tool_use", responseNode.path("stop_reason").asText())
        val contentTypes = responseNode.path("content").map { it.path("type").asText() }
        assertEquals(listOf("thinking", "text", "tool_use"), contentTypes)
        assertEquals("Paris", responseNode.path("content")[2].path("input").path("city").asText())
        assertEquals(100L, responseNode.path("usage").path("input_tokens").asLong())
        assertEquals(50L, responseNode.path("usage").path("output_tokens").asLong())
        assertEquals(7L, responseNode.path("usage").path("cache_read_input_tokens").asLong())

        val usageEventRow = awaitUsageEventRow("model = 'fake-openai-model' AND stream = 0")
        assertEquals("openai-fake", usageEventRow["provider"])
        assertEquals(100L, asLong(usageEventRow["input_tokens"]))
        assertEquals(50L, asLong(usageEventRow["output_tokens"]))
        assertEquals(7L, asLong(usageEventRow["cache_read_tokens"]))
    }

    @Test
    fun `openai stream переводит SSE и пишет usage`() {
        val responseBody = webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(CLAUDE_REQUEST_WITH_TOOLS.replace("\"max_tokens\":200", "\"max_tokens\":200,\"stream\":true"))
            .exchange().expectStatus().isOk
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .expectBody(String::class.java).returnResult().responseBody!!

        assertTrue(responseBody.contains("event: message_start"))
        assertTrue(responseBody.contains("\"thinking_delta\""))
        assertTrue(responseBody.contains("\"text_delta\""))
        assertTrue(responseBody.contains("\"input_json_delta\""))
        assertTrue(responseBody.contains("\"partial_json\":\"ty\\\":\\\"Paris\\\"}\""))
        assertTrue(responseBody.contains("\"stop_reason\":\"tool_use\""))
        assertTrue(responseBody.contains("event: message_stop"))

        val usageEventRow = awaitUsageEventRow("model = 'fake-openai-model' AND stream = 1")
        assertEquals(100L, asLong(usageEventRow["input_tokens"]))
        assertEquals(50L, asLong(usageEventRow["output_tokens"]))
        assertEquals(7L, asLong(usageEventRow["cache_read_tokens"]))
    }

    @Test
    fun `openai count_tokens оценивает локально`() {
        webTestClient.post().uri("/v1/messages/count_tokens")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(CLAUDE_REQUEST_WITH_TOOLS)
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.input_tokens").isNumber
    }

    @Test
    fun `openai ошибка переводится в формат Anthropic`() {
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"model":"openai-err-model","max_tokens":10,"messages":[]}""")
            .exchange().expectStatus().isEqualTo(429)
            .expectHeader().valueEquals("Retry-After", "3")
            .expectBody()
            .jsonPath("$.type").isEqualTo("error")
            .jsonPath("$.error.type").isEqualTo("rate_limit_error")
            .jsonPath("$.error.message").isEqualTo("rate limited")
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
                                request.uri().endsWith("/chat/completions") ->
                                    handleChatCompletions(requestNode, response)

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

        /** Фейковый OpenAI-эндпоинт /chat/completions с валидацией перевода запроса. */
        private fun handleChatCompletions(
            requestNode: com.fasterxml.jackson.databind.JsonNode?,
            response: reactor.netty.http.server.HttpServerResponse,
        ): Mono<Void> {
            val node = requestNode ?: return response.status(HttpResponseStatus.BAD_REQUEST)
                .header("Content-Type", "application/json")
                .sendString(Mono.just("""{"error":{"message":"empty request"}}"""), CharsetUtil.UTF_8)
                .then()
            val model = node.path("model").asText("")
            if (model == "openai-err-upstream") {
                return response.status(HttpResponseStatus.TOO_MANY_REQUESTS)
                    .header("Content-Type", "application/json")
                    .header("Retry-After", "3")
                    .sendString(
                        Mono.just("""{"error":{"message":"rate limited","type":"requests"}}"""),
                        CharsetUtil.UTF_8,
                    )
                    .then()
            }
            // Валидация перевода запроса Claude → OpenAI
            val messages = node.path("messages")
            val validationError = when {
                !messages.isArray || messages.size() == 0 ->
                    "messages отсутствуют"

                messages.get(0).path("role").asText() != "system" ->
                    "первым сообщением должен быть system"

                messages.none { it.path("role").asText() == "tool" } ->
                    "ожидалось сообщение role=tool (из tool_result)"

                node.path("tools").firstOrNull()?.path("type")?.asText() != "function" ->
                    "tools не переведены в function"

                node.path("tool_choice").asText("") != "required" ->
                    "tool_choice any не переведён в required"

                else -> null
            }
            if (validationError != null) {
                return response.status(HttpResponseStatus.INTERNAL_SERVER_ERROR)
                    .header("Content-Type", "application/json")
                    .sendString(
                        Mono.just("""{"error":{"message":"bad translated request: $validationError"}}"""),
                        CharsetUtil.UTF_8,
                    )
                    .then()
            }
            return if (node.path("stream").asBoolean(false) &&
                node.path("stream_options").path("include_usage").asBoolean(false)
            ) {
                response.status(HttpResponseStatus.OK)
                    .header("Content-Type", "text/event-stream")
                    .sendString(
                        Flux.just(OPENAI_SERVER_SENT_EVENTS_FIRST_CHUNK, OPENAI_SERVER_SENT_EVENTS_SECOND_CHUNK),
                        CharsetUtil.UTF_8,
                    )
                    .then()
            } else {
                response.status(HttpResponseStatus.OK)
                    .header("Content-Type", "application/json")
                    .sendString(Mono.just(OPENAI_NON_STREAM_RESPONSE_BODY), CharsetUtil.UTF_8)
                    .then()
            }
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
            propertyRegistry.add("claudeproxy.providers[1].name") { "openai-fake" }
            propertyRegistry.add("claudeproxy.providers[1].type") { "openai" }
            propertyRegistry.add("claudeproxy.providers[1].base-url") {
                "http://127.0.0.1:${upstream.port()}"
            }
            propertyRegistry.add("claudeproxy.providers[1].api-key") { "openai-secret" }
            propertyRegistry.add("claudeproxy.providers[1].models[0].public") { "fake-openai-model" }
            propertyRegistry.add("claudeproxy.providers[1].models[0].upstream") { "openai-model" }
            propertyRegistry.add("claudeproxy.providers[1].models[0].reasoning") { "map" }
            propertyRegistry.add("claudeproxy.providers[1].models[1].public") { "openai-err-model" }
            propertyRegistry.add("claudeproxy.providers[1].models[1].upstream") { "openai-err-upstream" }
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

        private const val CLAUDE_REQUEST_WITH_TOOLS =
            """{"model":"fake-openai-model","max_tokens":200,
               "system":[{"type":"text","text":"Ты помощник.","cache_control":{"type":"ephemeral"}}],
               "tools":[{"name":"get_weather","description":"Погода в городе",
                         "input_schema":{"type":"object","properties":{"city":{"type":"string"}},
                                         "required":["city"]}}],
               "tool_choice":{"type":"any"},
               "thinking":{"type":"adaptive"},
               "output_config":{"effort":"high"},
               "messages":[
                 {"role":"user","content":"Погода в Париже?"},
                 {"role":"assistant","content":[
                    {"type":"text","text":"Смотрю."},
                    {"type":"tool_use","id":"toolu_1","name":"get_weather","input":{"city":"Paris"}}]},
                 {"role":"user","content":[
                    {"type":"tool_result","tool_use_id":"toolu_1","content":[{"type":"text","text":"20C"}]},
                    {"type":"text","text":"Спасибо"}]}]}"""

        private const val OPENAI_NON_STREAM_RESPONSE_BODY =
            """{"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"openai-model",
               "choices":[{"index":0,"message":{"role":"assistant","content":"Сейчас 20C",
                 "reasoning_content":"думаю",
                 "tool_calls":[{"id":"call_1","type":"function",
                   "function":{"name":"get_weather","arguments":"{\"city\":\"Paris\",\"unit\":\"C\"}"}}]},
                 "finish_reason":"tool_calls"}],
               "usage":{"prompt_tokens":100,"completion_tokens":50,"total_tokens":150,
                 "prompt_tokens_details":{"cached_tokens":7}}}"""

        private const val OPENAI_SERVER_SENT_EVENTS_FIRST_CHUNK =
            "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0," +
                "\"delta\":{\"role\":\"assistant\",\"reasoning_content\":\"дум\"},\"finish_reason\":null}]}\n\n" +
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Отв\"},\"finish_reason\":null}]}\n\n" +
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\"," +
                "\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"ci\\\"\"}}]}," +
                "\"finish_reason\":null}]}\n\n"

        private const val OPENAI_SERVER_SENT_EVENTS_SECOND_CHUNK =
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0," +
                "\"function\":{\"arguments\":\"ty\\\":\\\"Paris\\\"}\"}}]},\"finish_reason\":null}]}\n\n" +
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n" +
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":50," +
                "\"total_tokens\":150,\"prompt_tokens_details\":{\"cached_tokens\":7}}}\n\n" +
                "data: [DONE]\n\n"
    }
}
