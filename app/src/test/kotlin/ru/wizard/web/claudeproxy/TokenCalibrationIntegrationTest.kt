package ru.wizard.web.claudeproxy

import com.fasterxml.jackson.databind.ObjectMapper
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import ru.wizard.web.claudeproxy.proxy.openai.impl.OpenAiTokenCountEstimator
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * M28: калибровка count_tokens для openai-провайдеров. Фейковый openai-провайдер
 * отдаёт управляемые prompt_tokens; прокси учитывает их и применяет коэффициент
 * «символы → токены» в оценке /v1/messages/count_tokens.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["claudeproxy.token-calibration.minimum-samples=1"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TokenCalibrationIntegrationTest {

    @Autowired
    private lateinit var environment: Environment

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private lateinit var webTestClient: WebTestClient

    /** Провайдер и модель создаются один раз на класс (БД общая для всех методов). */
    private var upstreamRegistered = false

    private val objectMapper = ObjectMapper()

    companion object {
        private const val SEED_API_KEY = "test-key-calib"
        private val upstreamPromptTokens = AtomicLong(100)

        init {
            // каталог для тестовой SQLite: DynamicPropertySource подставляется позже
            // EnvironmentPostProcessor, поэтому каталог создаём сами
            Files.createDirectories(Path.of("build/test"))
        }

        // каталог для тестовых SQLite: DynamicPropertySource вычисляется позднее,
        // при первом обращении к свойствам контекста, а не при разборе класса
        private val server: DisposableServer = HttpServer.create()
            .port(0)
            .handle { request, response ->
                request.receive().aggregate().asString().defaultIfEmpty("")
                    .flatMap { upstreamRequestBody ->
                        val isStream = upstreamRequestBody.contains("\"stream\":true") ||
                            upstreamRequestBody.contains("\"stream\": true")
                        if (request.uri().endsWith("/chat/completions") && isStream) {
                            val chunks = StringBuilder()
                            for (index in 0..1) {
                                chunks.append("data: ").append(
                                    """{"id":"chatcmpl-fake","object":"chat.completion.chunk","created":1730419200,"model":"upstream-model","choices":[{"index":0,"delta":{"content":"$index"},"finish_reason":null}]}""",
                                ).append("\n\n")
                            }
                            chunks.append("data: ").append(
                                """{"id":"chatcmpl-fake","object":"chat.completion.chunk","created":1730419200,"model":"upstream-model","choices":[],"usage":{"prompt_tokens":${upstreamPromptTokens.get()},"completion_tokens":1,"total_tokens":101}}""",
                            ).append("\n\n")
                            chunks.append("data: [DONE]\n\n")
                            response.addHeader("Content-Type", "text/event-stream")
                                .sendString(Mono.just(chunks.toString()), StandardCharsets.UTF_8)
                                .then()
                        } else if (request.uri().endsWith("/chat/completions")) {
                            response.addHeader("Content-Type", "application/json").sendString(
                                Mono.just(
                                    """{"id":"chatcmpl-fake","object":"chat.completion","created":1730419200,"model":"upstream-model",
                                       "choices":[{"index":0,"message":{"role":"assistant","content":"ок"},"finish_reason":"stop"}],
                                       "usage":{"prompt_tokens":${upstreamPromptTokens.get()},"completion_tokens":1,"total_tokens":101}}""",
                                ),
                                StandardCharsets.UTF_8,
                            ).then()
                        } else {
                            response.addHeader("Content-Type", "application/json")
                                .sendString(Mono.just("{}"), StandardCharsets.UTF_8)
                                .then()
                        }
                    }
            }
            .bindNow()

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(propertyRegistry: DynamicPropertyRegistry) {
            propertyRegistry.add("spring.datasource.url") {
                "jdbc:sqlite:build/test/token-calibration-itest-${UUID.randomUUID()}.db"
            }
            propertyRegistry.add("claudeproxy.api-keys[0].name") { "test" }
            propertyRegistry.add("claudeproxy.api-keys[0].key") { SEED_API_KEY }
            // каждый успешный запрос сразу участвует в калибровке
            propertyRegistry.add("claudeproxy.token-calibration.minimum-samples") { "1" }
        }
    }

    @BeforeEach
    fun prepare() {
        val serverPort = environment.getProperty("local.server.port", Int::class.java)!!
        webTestClient = WebTestClient.bindToServer()
            .baseUrl("http://127.0.0.1:$serverPort")
            .build()
        if (!upstreamRegistered) {
            createOpenAiProvider()
            upstreamRegistered = true
        }
        upstreamPromptTokens.set(100)
        webTestClient.delete().uri("/api/token-calibration").exchange().expectStatus().isNoContent
        webTestClient.get().uri("/api/token-calibration")
            .exchange().expectStatus().isOk
            .expectBody(String::class.java).isEqualTo("[]")
    }

    private fun createOpenAiProvider() {
        webTestClient.post().uri("/api/providers")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"name":"cal-openai","type":"openai","baseUrl":"http://127.0.0.1:${server.port()}","apiKey":"test"}""")
            .exchange().expectStatus().isCreated
            .expectBody(String::class.java).returnResult().responseBody!!.let { body ->
                objectMapper.readTree(body).path("id").asLong()
            }.also { providerId ->
                webTestClient.post().uri("/api/providers/$providerId/models")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""{"publicName":"cal-openai-model","upstreamName":"upstream-model"}""")
                    .exchange().expectStatus().isCreated
            }
    }

    @Test
    fun `обычный ответ обучает калибровку и меняет оценку count_tokens`() {
        val body = """{"model":"cal-openai-model","max_tokens":16,"system":"система калибровки","messages":[{"role":"user","content":"текст запроса калибровки"}]}"""

        // до обучения — стандартное приближение 4 симв./токен
        val before = countTokens(body)
        val characters = calibrationCharacters(body)
        assertEquals(
            characters / 4,
            before,
            "до обучения оценка должна считать по 4 симв./токен",
        )

        // обычный нестриминговый ответ: prompt_tokens = 100
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange().expectStatus().isOk

        val entries = awaitCalibration()
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("cal-openai-model", entry["model"])
        assertEquals("cal-openai", entry["provider"])
        assertEquals(100L, (entry["textTokens"] as Number).toLong())
        assertEquals(characters, (entry["textCharacters"] as Number).toLong())

        // после обучения оценка сходится к фактическим input_tokens
        val after = countTokens(body)
        assertTrue(
            after in 99..101,
            "ожидалась оценка ~100 токенов, получено $after",
        )
    }

    @Test
    fun `стриминговый ответ тоже обучает калибровку`() {
        val body = """{"model":"cal-openai-model","max_tokens":16,"stream":true,"messages":[{"role":"user","content":"стрим для калибровки"}]}"""
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange().expectStatus().isOk

        val entries = awaitCalibration()
        assertEquals(100L, (entries[0]["textTokens"] as Number).toLong())
        assertTrue(
            countTokens(body) in 99..101,
            "стриминговый ход тоже должен калибровать оценку",
        )
    }

    @Test
    fun `картинки вычитаются из фактических input_tokens`() {
        upstreamPromptTokens.set(5000)
        val body = """{"model":"cal-openai-model","max_tokens":16,"messages":[{"role":"user","content":[
            {"type":"text","text":"запрос с картинками"},
            {"type":"image","source":{"type":"base64","media_type":"image/png","data":"aQ=="}},
            {"type":"image","source":{"type":"base64","media_type":"image/png","data":"aQ=="}},
            {"type":"image","source":{"type":"base64","media_type":"image/png","data":"aQ=="}}]}]}"""
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange().expectStatus().isOk

        val entries = awaitCalibration()
        // 5000 фактических - 3 картинки × 1600 = 200 текстовых токенов
        assertEquals(200L, (entries[0]["textTokens"] as Number).toLong())

        // оценка: калиброванный текст + фиксированные 3 × 1600
        val estimate = countTokens(body)
        assertTrue(
            estimate in 4999..5001,
            "ожидалась оценка ~5000 токенов (текст + картинки), получено $estimate",
        )
    }

    @Test
    fun `сброс калибровки возвращает стандартную оценку`() {
        val body = """{"model":"cal-openai-model","max_tokens":16,"messages":[{"role":"user","content":"сброс калибровки"}]}"""
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange().expectStatus().isOk
        awaitCalibration()

        webTestClient.delete().uri("/api/token-calibration").exchange().expectStatus().isNoContent

        val characters = calibrationCharacters(body)
        assertEquals(
            characters / 4,
            countTokens(body),
            "после сброса оценка должна вернуться к 4 симв./токен",
        )
    }

    /** Ответ /v1/messages/count_tokens для тела запроса. */
    private fun countTokens(body: String): Long {
        val response = webTestClient.post().uri("/v1/messages/count_tokens")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        return objectMapper.readTree(response).path("input_tokens").asLong()
    }

    /** Число текстовых символов запроса — ожидаемая база калибровки. */
    private fun calibrationCharacters(body: String): Long =
        OpenAiTokenCountEstimator().textCharacterCount(objectMapper.readTree(body))

    /** Ждёт появления записей калибровки (запись асинхронная). */
    private fun awaitCalibration(): List<Map<String, Any>> {
        lateinit var rows: List<Map<String, Any>>
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).until {
            rows = fetchCalibrationRows()
            rows.isNotEmpty()
        }
        return rows
    }

    private fun fetchCalibrationRows(): List<Map<String, Any>> {
        val body = webTestClient.get().uri("/api/token-calibration")
            .exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        @Suppress("UNCHECKED_CAST")
        return objectMapper.readValue(body, List::class.java) as List<Map<String, Any>>
    }
}
