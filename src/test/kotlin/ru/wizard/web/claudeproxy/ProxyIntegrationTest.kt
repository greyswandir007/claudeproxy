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

    @Autowired
    private lateinit var usageRetentionService: ru.wizard.web.claudeproxy.usage.UsageRetentionService

    private lateinit var webTestClient: WebTestClient

    @BeforeEach
    fun prepare() {
        val serverPort = environment.getProperty("local.server.port", Int::class.java)!!
        webTestClient = WebTestClient.bindToServer()
            .baseUrl("http://127.0.0.1:$serverPort")
            .build()
        jdbcTemplate.update("DELETE FROM usage_event")
        jdbcTemplate.update("DELETE FROM usage_window")
        jdbcTemplate.update("DELETE FROM chat_message")
        jdbcTemplate.update("DELETE FROM chat_thread")
        jdbcTemplate.update("DELETE FROM api_key WHERE name <> 'test'")
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
            .jsonPath("$.object").isEqualTo("list")
            .jsonPath("$.data[0].id").isEqualTo("fake-model")
            .jsonPath("$.data[0].object").isEqualTo("model")
            .jsonPath("$.data[0].display_name").isEqualTo("fake-model")
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

    @Test
    fun `жизненный цикл ключа через api`() {
        val keyName = "generated-${UUID.randomUUID()}"
        val createResponse = webTestClient.post().uri("/api/keys")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"name":"$keyName"}""")
            .exchange().expectStatus().isCreated
            .expectBody(String::class.java).returnResult().responseBody!!
        val createdNode = objectMapper.readTree(createResponse)
        val fullKey = createdNode.path("fullKey").asText()
        val keyId = createdNode.path("clientKey").path("id").asLong()
        assertTrue(fullKey.startsWith("cpk_"))
        assertEquals(47, fullKey.length) // cpk_ + 43 символа base64url

        // сгенерированный ключ проходит auth
        webTestClient.get().uri("/v1/models")
            .header("x-api-key", fullKey)
            .exchange().expectStatus().isOk

        // дубликат имени — 409
        webTestClient.post().uri("/api/keys")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"name":"$keyName"}""")
            .exchange().expectStatus().isEqualTo(409)

        // список содержит оба ключа
        webTestClient.get().uri("/api/keys")
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$[?(@.name == '$keyName')].keyPrefix").isNotEmpty
            .jsonPath("$[?(@.name == 'test')]").isNotEmpty

        // отзыв → ключ перестаёт работать
        webTestClient.post().uri("/api/keys/$keyId/revoke")
            .exchange().expectStatus().isOk
            .expectBody().jsonPath("$.revoked").isEqualTo(true)
        webTestClient.get().uri("/v1/models")
            .header("x-api-key", fullKey)
            .exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `статистика summary, by-model, окна и timeline`() {
        // генерируем usage-событие
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"model":"fake-model","max_tokens":100,"messages":[{"role":"user","content":"привет"}]}""",
            )
            .exchange().expectStatus().isOk
        awaitUsageEventRow("stream = 0")

        webTestClient.get().uri("/api/summary?range=7d&key=test")
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.totals.requests").isEqualTo(1)
            .jsonPath("$.totals.inputTokens").isEqualTo(10)
            .jsonPath("$.totals.outputTokens").isEqualTo(20)

        // текущее окно по диапазону window
        webTestClient.get().uri("/api/summary?range=window&key=test")
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.window.startedAtMilliseconds").isNumber
            .jsonPath("$.totals.inputTokens").isEqualTo(10)

        webTestClient.get().uri("/api/by-model?range=7d")
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$[0].label").isEqualTo("fake-model")
            .jsonPath("$[0].requests").isEqualTo(1)

        webTestClient.get().uri("/api/window?key=test")
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.startedAtMilliseconds").isNumber

        // в истории окон ключа — перечень провайдеров, обслуживших окно
        webTestClient.get().uri("/api/windows?key=test&limit=5")
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$[0].totals.inputTokens").isEqualTo(10)
            .jsonPath("$[0].providers[0]").isEqualTo("fake")

        // история окон провайдеров — независимый отсчёт у каждого
        webTestClient.get().uri("/api/provider-windows?limit=5")
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$[?(@.providerName == 'fake')].totals.inputTokens")
            .isEqualTo(10)

        webTestClient.get().uri("/api/timeline?bucket=hour")
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$[0].requests").isEqualTo(1)

        // срез таймлайна по моделям и кастомный диапазон в срезах
        webTestClient.get().uri("/api/timeline?bucket=hour&group=model")
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$[0].label").isEqualTo("fake-model")
            .jsonPath("$[0].tokens").isEqualTo(35)
        webTestClient.get().uri("/api/timeline?bucket=hour&group=provider")
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$[0].label").isEqualTo("fake")
        val from = System.currentTimeMillis() - 3_600_000
        val to = System.currentTimeMillis() + 3_600_000
        webTestClient.get().uri("/api/by-model?from=$from&to=$to")
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$[0].label").isEqualTo("fake-model")

        // границы 5-часовых окон ключа для графика дня
        webTestClient.get().uri("/api/window-boundaries?key=test&from=$from&to=$to")
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$[0].startedAtMilliseconds").isNumber
            .jsonPath("$[0].endsAtMilliseconds").isNumber

        // таймлайн фильтруется по ключу: другой ключ — пусто
        val createdKey = webTestClient.post().uri("/api/keys")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"name":"timeline-other-key"}""")
            .exchange().expectStatus().isCreated
            .expectBody(String::class.java).returnResult().responseBody!!
        val otherKeyName = objectMapper.readTree(createdKey).path("clientKey").path("name").asText()
        webTestClient.get().uri("/api/timeline?bucket=hour&key=$otherKeyName")
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$").isArray()
            .jsonPath("$.length()").isEqualTo(0)

        webTestClient.get().uri("/api/config")
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.providers[1].name").isEqualTo("openai-fake")
            .jsonPath("$.exposedModels[0]").isEqualTo("fake-model")
    }

    @Test
    fun `retention удаляет только устаревшие события`() = kotlinx.coroutines.runBlocking {
        val now = System.currentTimeMillis()
        val outdatedTimestamp = now - 366L * 86_400_000L
        for (pair in listOf("old-model" to outdatedTimestamp, "new-model" to now)) {
            jdbcTemplate.update(
                """INSERT INTO usage_event
                   (ts, client_key, provider, model, upstream_model, stream,
                    input_tokens, output_tokens, cache_creation_tokens, cache_read_tokens)
                   VALUES (?,?,?,?,?,0,1,1,0,0)""",
                pair.second,
                "test",
                "fake",
                pair.first,
                "upstream-${pair.first}",
            )
        }
        usageRetentionService.deleteOutdatedEvents()
        val remainingModels =
            jdbcTemplate.queryForList("SELECT model FROM usage_event", String::class.java)
        assertTrue(remainingModels.contains("new-model"))
        assertTrue(!remainingModels.contains("old-model"))
    }

    @Test
    fun `управление провайдерами и моделями через api`() {
        // 1. Создание провайдера через UI-API (указывает на фейковый upstream)
        val createProviderBody = webTestClient.post().uri("/api/providers")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"name":"ui-provider","type":"openai",
                    "baseUrl":"http://127.0.0.1:${upstream.port()}","apiKey":"ui-provider-secret"}""",
            )
            .exchange().expectStatus().isCreated
            .expectBody(String::class.java).returnResult().responseBody!!
        val providerId = objectMapper.readTree(createProviderBody).path("id").asLong()

        // 2. Дубликат имени провайдера — 409
        webTestClient.post().uri("/api/providers")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"name":"ui-provider","type":"openai","baseUrl":"http://127.0.0.1:1"}""")
            .exchange().expectStatus().isEqualTo(409)

        // 3. Модель у нового провайдера
        val createModelBody = webTestClient.post().uri("/api/providers/$providerId/models")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"publicName":"ui-model","upstreamName":"openai-model",
                    "reasoning":"map","maxCompletionParam":false}""",
            )
            .exchange().expectStatus().isCreated
            .expectBody(String::class.java).returnResult().responseBody!!
        val modelId = objectMapper.readTree(createModelBody).path("id").asLong()

        // 4. Сразу работает через /v1/messages (реестр перезагружен)
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(CLAUDE_REQUEST_WITH_TOOLS.replace("fake-openai-model", "ui-model"))
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.model").isEqualTo("ui-model")

        // 5. Список: ключ не утекает, только превью
        val providerListBody = webTestClient.get().uri("/api/providers")
            .exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val uiProvider = objectMapper.readTree(providerListBody)
            .firstOrNull { it.path("name").asText() == "ui-provider" }!!
        val apiKeyPreview = uiProvider.path("apiKeyPreview").asText()
        assertTrue(apiKeyPreview.isNotEmpty())
        assertTrue(!apiKeyPreview.contains("ui-provider-secret"))
        assertEquals(1, uiProvider.path("models").size())

        // 6. Переименование модели — новое имя работает, старое 404
        webTestClient.put().uri("/api/models/$modelId")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"publicName":"ui-model-renamed","upstreamName":"openai-model",
                    "reasoning":"off","maxCompletionParam":false}""",
            )
            .exchange().expectStatus().isOk
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(CLAUDE_REQUEST_WITH_TOOLS.replace("fake-openai-model", "ui-model-renamed"))
            .exchange().expectStatus().isOk
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"model":"ui-model","max_tokens":10,"messages":[]}""")
            .exchange().expectStatus().isNotFound

        // 7. Удаление модели и провайдера
        webTestClient.delete().uri("/api/models/$modelId").exchange().expectStatus().isNoContent
        webTestClient.delete().uri("/api/providers/$providerId").exchange().expectStatus().isNoContent
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"model":"ui-model-renamed","max_tokens":10,"messages":[]}""")
            .exchange().expectStatus().isNotFound
    }

    @Test
    fun `миграции применены и зафиксированы`() {
        val appliedMigrations =
            jdbcTemplate.queryForList("SELECT version FROM schema_migration ORDER BY version", Int::class.java)
        assertTrue(appliedMigrations.containsAll(listOf(1, 2, 3)))
        // V2: колонки приоритета и выдачи на месте
        jdbcTemplate.queryForObject("SELECT priority, exposed FROM model LIMIT 1") { resultSet, _ ->
            // достаточно, что запрос не падает
            resultSet.getInt(1) + resultSet.getInt(2)
        }
    }

    @Test
    fun `дискавери моделей провайдера`() {
        webTestClient.post().uri("/api/providers/discover-models")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"type":"openai","baseUrl":"http://127.0.0.1:${upstreamPort()}","apiKey":"any"}""",
            )
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.models[0]").isEqualTo("discovered-model")
            .jsonPath("$.models[1]").isEqualTo("openai-model")
    }

    private fun upstreamPort(): Int = upstream.port()

    @Test
    fun `выдача моделей скрывается флагами exposed`() {
        val createdProvider = webTestClient.post().uri("/api/providers")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"name":"exposure-provider","type":"openai",
                    "baseUrl":"http://127.0.0.1:${upstreamPort()}","apiKey":"exposure-secret"}""",
            )
            .exchange().expectStatus().isCreated
            .expectBody(String::class.java).returnResult().responseBody!!
        val providerId = objectMapper.readTree(createdProvider).path("id").asLong()

        fun addModel(publicName: String): Long {
            val createdModel = webTestClient.post().uri("/api/providers/$providerId/models")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                    """{"publicName":"$publicName","upstreamName":"openai-model",
                        "reasoning":"map","maxCompletionParam":false,"priority":100}""",
                )
                .exchange().expectStatus().isCreated
                .expectBody(String::class.java).returnResult().responseBody!!
            return objectMapper.readTree(createdModel).path("id").asLong()
        }
        val visibleModelId = addModel("exposed-visible-model")
        val hiddenModelId = addModel("exposed-hidden-model")

        fun exposedModelNames(): List<String> =
            objectMapper.readTree(
                webTestClient.get().uri("/v1/models")
                    .header("x-api-key", SEED_API_KEY)
                    .exchange().expectStatus().isOk
                    .expectBody(String::class.java).returnResult().responseBody!!,
            ).path("data").map { it.path("id").asText() }

        assertTrue(exposedModelNames().containsAll(listOf("exposed-visible-model", "exposed-hidden-model")))

        // скрытая модель пропадает из списка, но остаётся маршрутизируемой
        webTestClient.put().uri("/api/models/$hiddenModelId/exposure")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"exposed":false}""")
            .exchange().expectStatus().isOk
        assertTrue(!exposedModelNames().contains("exposed-hidden-model"))
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                CLAUDE_REQUEST_WITH_TOOLS.replace("fake-openai-model", "exposed-hidden-model"),
            )
            .exchange().expectStatus().isOk

        // скрытие провайдера прянет и его видимые модели
        webTestClient.put().uri("/api/providers/$providerId/exposure")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"exposed":false}""")
            .exchange().expectStatus().isOk
        assertTrue(!exposedModelNames().contains("exposed-visible-model"))

        webTestClient.delete().uri("/api/models/$visibleModelId").exchange().expectStatus().isNoContent
        webTestClient.delete().uri("/api/models/$hiddenModelId").exchange().expectStatus().isNoContent
        webTestClient.delete().uri("/api/providers/$providerId").exchange().expectStatus().isNoContent
    }

    @Test
    fun `fallback переключает на второй маршрут при quota exceeded`() {
        // primary: та же публичная модель, но upstream всегда отвечает 429
        val primaryProvider = webTestClient.post().uri("/api/providers")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"name":"fallback-primary","type":"openai",
                    "baseUrl":"http://127.0.0.1:${upstreamPort()}","apiKey":"primary-secret"}""",
            )
            .exchange().expectStatus().isCreated
            .expectBody(String::class.java).returnResult().responseBody!!
        val primaryProviderId = objectMapper.readTree(primaryProvider).path("id").asLong()
        webTestClient.post().uri("/api/providers/$primaryProviderId/models")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"publicName":"fb-model","upstreamName":"fallback-primary-upstream",
                    "reasoning":"map","maxCompletionParam":false,"priority":1}""",
            )
            .exchange().expectStatus().isCreated

        // secondary: та же публичная модель у другого провайдера — работает
        val secondaryProvider = webTestClient.post().uri("/api/providers")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"name":"fallback-secondary","type":"openai",
                    "baseUrl":"http://127.0.0.1:${upstreamPort()}","apiKey":"secondary-secret"}""",
            )
            .exchange().expectStatus().isCreated
            .expectBody(String::class.java).returnResult().responseBody!!
        val secondaryProviderId = objectMapper.readTree(secondaryProvider).path("id").asLong()
        webTestClient.post().uri("/api/providers/$secondaryProviderId/models")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"publicName":"fb-model","upstreamName":"openai-model",
                    "reasoning":"map","maxCompletionParam":false,"priority":2}""",
            )
            .exchange().expectStatus().isCreated

        // не-stream: ответ пришёл от secondary
        val responseBody = webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(CLAUDE_REQUEST_WITH_TOOLS.replace("fake-openai-model", "fb-model"))
            .exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        assertEquals("fb-model", objectMapper.readTree(responseBody).path("model").asText())

        // usage: неудачная попытка primary (429) + успешная secondary
        val failedRows = awaitUsageEventRows("model = 'fb-model' AND stream = 0 AND status = 429")
        assertEquals("fallback-primary", failedRows.first()["provider"])
        val successRows = awaitUsageEventRows("model = 'fb-model' AND stream = 0 AND status = 200")
        assertEquals("fallback-secondary", successRows.first()["provider"])

        // stream: тоже переключение до первого события
        val streamBody = webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                CLAUDE_REQUEST_WITH_TOOLS
                    .replace("fake-openai-model", "fb-model")
                    .replace("\"max_tokens\":200", "\"max_tokens\":200,\"stream\":true"),
            )
            .exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        assertTrue(streamBody.contains("event: message_stop"))
        awaitUsageEventRows("model = 'fb-model' AND stream = 1 AND status = 200")

        // после 429 primary в кулдауне — виден в /api/route-cooldowns
        webTestClient.get().uri("/api/route-cooldowns")
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$[?(@.providerName == 'fallback-primary')]").isNotEmpty

        // второй запрос не дёргает primary (кулдаун) — сразу secondary
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(CLAUDE_REQUEST_WITH_TOOLS.replace("fake-openai-model", "fb-model"))
            .exchange().expectStatus().isOk
        val successRowsAfterSecond = awaitUsageEventRows(
            "model = 'fb-model' AND stream = 0 AND status = 200",
            expectedCount = 2,
        )
        // успешных две (первый и второй запрос), а 429-попытка одна — кулдаун работает
        assertEquals(2, successRowsAfterSecond.size)
        awaitUsageEventRows("model = 'fb-model' AND stream = 0 AND status = 429")

        // fallback-report: ошибки и латентность primary
        webTestClient.get().uri("/api/fallback-report?range=7d")
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.providers[?(@.providerName == 'fallback-primary')].failedAttempts")
            .isEqualTo(1)

        webTestClient.delete().uri("/api/providers/$primaryProviderId").exchange().expectStatus().isNoContent
        webTestClient.delete().uri("/api/providers/$secondaryProviderId").exchange().expectStatus().isNoContent
    }

    @Test
    fun `openai-клиент не-stream через openai-провайдера (полный roundtrip)`() {
        val responseBody = webTestClient.post().uri("/v1/chat/completions")
            .header("Authorization", "Bearer $SEED_API_KEY")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(OPENAI_CLIENT_REQUEST)
            .exchange().expectStatus().isOk
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectBody(String::class.java).returnResult().responseBody!!

        val responseNode = objectMapper.readTree(responseBody)
        assertEquals("chat.completion", responseNode.path("object").asText())
        assertEquals("fake-openai-model", responseNode.path("model").asText())
        assertEquals("Сейчас 20C", responseNode.path("choices").get(0).path("message").path("content").asText())
        assertEquals("думаю", responseNode.path("choices").get(0).path("message").path("reasoning_content").asText())
        assertEquals(
            "get_weather",
            responseNode.path("choices").get(0).path("message").path("tool_calls").get(0)
                .path("function").path("name").asText(),
        )
        // arguments у OpenAI — строка с JSON
        val argumentsJson = responseNode.path("choices").get(0).path("message").path("tool_calls").get(0)
            .path("function").path("arguments").asText()
        assertEquals("Paris", objectMapper.readTree(argumentsJson).path("city").asText())
        assertEquals("tool_calls", responseNode.path("choices").get(0).path("finish_reason").asText())
        assertEquals(100L, responseNode.path("usage").path("prompt_tokens").asLong())
        assertEquals(50L, responseNode.path("usage").path("completion_tokens").asLong())
        assertEquals(7L, responseNode.path("usage").path("prompt_tokens_details").path("cached_tokens").asLong())

        val usageEventRow = awaitUsageEventRow("model = 'fake-openai-model' AND stream = 0")
        assertEquals(100L, asLong(usageEventRow["input_tokens"]))
    }

    @Test
    fun `openai-клиент stream через openai-провайдера`() {
        val responseBody = webTestClient.post().uri("/v1/chat/completions")
            .header("Authorization", "Bearer $SEED_API_KEY")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(OPENAI_CLIENT_REQUEST.replace("\"max_tokens\":200", "\"max_tokens\":200,\"stream\":true"))
            .exchange().expectStatus().isOk
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .expectBody(String::class.java).returnResult().responseBody!!

        assertTrue(responseBody.contains("chat.completion.chunk"))
        assertTrue(responseBody.contains("\"role\":\"assistant\""))
        assertTrue(responseBody.contains("\"content\":\"Отв\""))
        assertTrue(responseBody.contains("tool_calls"))
        assertTrue(responseBody.contains("\"finish_reason\":\"tool_calls\""))
        assertTrue(responseBody.contains("\"prompt_tokens\":100"))
        assertTrue(responseBody.trimEnd().endsWith("data: [DONE]"))

        awaitUsageEventRow("model = 'fake-openai-model' AND stream = 1")
    }

    @Test
    fun `openai-клиент через anthropic-провайдера (обратный перевод)`() {
        val responseBody = webTestClient.post().uri("/v1/chat/completions")
            .header("Authorization", "Bearer $SEED_API_KEY")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"model":"fake-model","max_tokens":100,"messages":[{"role":"user","content":"привет"}]}""")
            .exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!

        val responseNode = objectMapper.readTree(responseBody)
        assertEquals("hello", responseNode.path("choices").get(0).path("message").path("content").asText())
        assertEquals("stop", responseNode.path("choices").get(0).path("finish_reason").asText())
        assertEquals(10L, responseNode.path("usage").path("prompt_tokens").asLong())
        assertEquals(20L, responseNode.path("usage").path("completion_tokens").asLong())
        awaitUsageEventRow("model = 'fake-model' AND stream = 0")
    }

    @Test
    fun `openai-клиент ошибка в формате OpenAI`() {
        webTestClient.post().uri("/v1/chat/completions")
            .header("Authorization", "Bearer $SEED_API_KEY")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"model":"no-such-model","messages":[]}""")
            .exchange().expectStatus().isNotFound
            .expectBody()
            .jsonPath("$.error.message").isNotEmpty
            .jsonPath("$.error.type").isEqualTo("invalid_request_error")
            .jsonPath("$.type").doesNotExist()
    }

    @Test
    fun `лимиты провайдера и их выработка`() {
        // провайдер с лимитами: окно 1000, месяц 100000; неделя не задана
        val createdProvider = webTestClient.post().uri("/api/providers")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"name":"limits-provider","type":"openai",
                    "baseUrl":"http://127.0.0.1:${upstreamPort()}","apiKey":"limits-secret",
                    "limitWindowTokens":1000,"limitMonthTokens":100000}""",
            )
            .exchange().expectStatus().isCreated
            .expectBody(String::class.java).returnResult().responseBody!!
        val providerNode = objectMapper.readTree(createdProvider)
        val providerId = providerNode.path("id").asLong()
        assertEquals(1000L, providerNode.path("limitWindowTokens").asLong())
        assertTrue(providerNode.path("limitWeekTokens").isNull)

        webTestClient.post().uri("/api/providers/$providerId/models")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"publicName":"limits-model","upstreamName":"openai-model",
                    "reasoning":"map","maxCompletionParam":false,"priority":100}""",
            )
            .exchange().expectStatus().isCreated

        // один запрос: 100 in + 50 out + 7 cache_read = 157 токенов
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(CLAUDE_REQUEST_WITH_TOOLS.replace("fake-openai-model", "limits-model"))
            .exchange().expectStatus().isOk

        val limitsBody = webTestClient.get().uri("/api/provider-limits")
            .exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val usage = objectMapper.readTree(limitsBody)
            .firstOrNull { it.path("providerName").asText() == "limits-provider" }!!
        assertEquals(1000L, usage.path("window").path("limitTokens").asLong())
        assertEquals(157L, usage.path("window").path("spentTokens").asLong())
        assertEquals(false, usage.path("window").path("derived").asBoolean())
        assertEquals(true, usage.path("windowActive").asBoolean())

        // истёкшее окно: выработка обнуляется, пока не начнётся новое
        jdbcTemplate.update(
            "UPDATE provider_usage_window SET started_at = ?, ends_at = ? WHERE provider_name = 'limits-provider'",
            System.currentTimeMillis() - 6 * 3_600_000L,
            System.currentTimeMillis() - 3_600_000L,
        )
        val expiredBody = webTestClient.get().uri("/api/provider-limits")
            .exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val expiredUsage = objectMapper.readTree(expiredBody)
            .firstOrNull { it.path("providerName").asText() == "limits-provider" }!!
        assertEquals(false, expiredUsage.path("windowActive").asBoolean())
        assertEquals(0L, expiredUsage.path("window").path("spentTokens").asLong())
        assertEquals(0, expiredUsage.path("window").path("modelTokens").size())
        // разбивка по моделям — для графиков относительно лимита
        assertEquals("limits-model", usage.path("window").path("modelTokens").get(0).path("modelName").asText())
        assertEquals(157L, usage.path("window").path("modelTokens").get(0).path("tokens").asLong())
        // неделя не задана — выводится из месячного (100000 / 4.29)
        assertEquals(true, usage.path("week").path("derived").asBoolean())
        assertEquals(23310L, usage.path("week").path("limitTokens").asLong())
        assertEquals(100000L, usage.path("month").path("limitTokens").asLong())
        assertEquals(157L, usage.path("month").path("spentTokens").asLong())

        // провайдер только с недельным лимитом: окно и месяц — производные
        val weekOnlyCreated = webTestClient.post().uri("/api/providers")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"name":"week-only-limits-provider","type":"openai",
                    "baseUrl":"http://127.0.0.1:9","apiKey":"x","limitWeekTokens":33600000}""",
            )
            .exchange().expectStatus().isCreated
            .expectBody(String::class.java).returnResult().responseBody!!
        val weekOnlyProviderId = objectMapper.readTree(weekOnlyCreated).path("id").asLong()
        val weekOnlyBody = webTestClient.get().uri("/api/provider-limits")
            .exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val weekOnly = objectMapper.readTree(weekOnlyBody)
            .firstOrNull { it.path("providerName").asText() == "week-only-limits-provider" }!!
        assertEquals(false, weekOnly.path("week").path("derived").asBoolean())
        assertEquals(33600000L, weekOnly.path("week").path("limitTokens").asLong())
        assertEquals(true, weekOnly.path("window").path("derived").asBoolean())
        assertEquals(1000000L, weekOnly.path("window").path("limitTokens").asLong()) // 33.6M / 33.6
        // окон у провайдера не было вовсе — показывается неактивное состояние
        assertEquals(false, weekOnly.path("windowActive").asBoolean())
        assertEquals(0L, weekOnly.path("window").path("spentTokens").asLong())
        assertEquals(true, weekOnly.path("month").path("derived").asBoolean())
        assertEquals(144144000L, weekOnly.path("month").path("limitTokens").asLong()) // 33.6M * 4.29
        webTestClient.delete().uri("/api/providers/$weekOnlyProviderId")
            .exchange().expectStatus().isNoContent

        // провайдеры без лимитов в выработку не попадают
        assertTrue(
            objectMapper.readTree(limitsBody)
                .none { it.path("providerName").asText() == "fake" },
        )

        // сброс лимитов через обновление (null)
        webTestClient.put().uri("/api/providers/$providerId")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"name":"limits-provider","type":"openai",
                    "baseUrl":"http://127.0.0.1:${upstreamPort()}"}""",
            )
            .exchange().expectStatus().isOk
        val limitsAfterReset = webTestClient.get().uri("/api/provider-limits")
            .exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        assertTrue(
            objectMapper.readTree(limitsAfterReset)
                .none { it.path("providerName").asText() == "limits-provider" },
        )

        webTestClient.delete().uri("/api/providers/$providerId").exchange().expectStatus().isNoContent
    }

    @Test
    fun `маппер effort и оверрайды применяются к запросу провайдера`() {
        // провайдер: маппер high→low + потолок max_tokens + температура
        val createdProvider = webTestClient.post().uri("/api/providers")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"name":"m9-provider","type":"openai",
                    "baseUrl":"http://127.0.0.1:${upstreamPort()}","apiKey":"m9-secret",
                    "effortMapping":{"high":"low"},
                    "settingOverrides":{"MAX_OUTPUT_TOKENS":64,"TEMPERATURE_OVERRIDE":0.7}}""",
            )
            .exchange().expectStatus().isCreated
            .expectBody(String::class.java).returnResult().responseBody!!
        val providerId = objectMapper.readTree(createdProvider).path("id").asLong()
        webTestClient.post().uri("/api/providers/$providerId/models")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"publicName":"m9-model","upstreamName":"echo-model",
                    "reasoning":"map","maxCompletionParam":false,"priority":100}""",
            )
            .exchange().expectStatus().isCreated

        // запрос с effort high → провайдеру уходит low; max_tokens ужимается; температура переопределена
        val responseBody = webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"model":"m9-model","max_tokens":1000,"temperature":0.1,
                    "thinking":{"type":"adaptive"},"output_config":{"effort":"high"},
                    "messages":[{"role":"user","content":"привет"}]}""",
            )
            .exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val echo = objectMapper.readTree(
            objectMapper.readTree(responseBody)
                .path("content").get(0).path("text").asText().removePrefix("echo:"),
        )
        assertEquals("low", echo.path("reasoning_effort").asText())
        assertEquals(64L, echo.path("max_tokens").asLong())
        assertEquals("0.7", echo.path("temperature").asText())

        // отображение настроек в /api/providers
        val providerListBody = webTestClient.get().uri("/api/providers")
            .exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val m9Provider = objectMapper.readTree(providerListBody)
            .firstOrNull { it.path("name").asText() == "m9-provider" }!!
        assertEquals("low", m9Provider.path("effortMapping").path("high").asText())
        assertEquals("64", m9Provider.path("settingOverrides").path("MAX_OUTPUT_TOKENS").asText())

        // MAX_INPUT_TOKENS: слишком большой вход — вежливый 413
        webTestClient.put().uri("/api/providers/$providerId")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"name":"m9-provider","type":"openai",
                    "baseUrl":"http://127.0.0.1:${upstreamPort()}",
                    "settingOverrides":{"MAX_INPUT_TOKENS":100}}""",
            )
            .exchange().expectStatus().isOk
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"model":"m9-model","max_tokens":10,
                    "messages":[{"role":"user","content":"${"x".repeat(1000)}"}]}""",
            )
            .exchange().expectStatus().isEqualTo(413)
            .expectBody()
            .jsonPath("$.error.type").isEqualTo("request_too_large")

        webTestClient.delete().uri("/api/providers/$providerId").exchange().expectStatus().isNoContent
    }

    @Test
    fun `oauth-провайдер получает access-токен и ходит с Bearer`() {
        val createdProvider = webTestClient.post().uri("/api/providers")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"name":"oauth-provider","type":"openai",
                    "baseUrl":"http://127.0.0.1:${upstreamPort()}",
                    "authType":"oauth","oauthGrant":"client_credentials",
                    "oauthClientId":"test-client","oauthClientSecret":"test-secret",
                    "oauthTokenUrl":"http://127.0.0.1:${upstreamPort()}/oauth/token"}""",
            )
            .exchange().expectStatus().isCreated
            .expectBody(String::class.java).returnResult().responseBody!!
        val providerId = objectMapper.readTree(createdProvider).path("id").asLong()
        assertEquals("oauth", objectMapper.readTree(createdProvider).path("authType").asText())

        webTestClient.post().uri("/api/providers/$providerId/models")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"publicName":"oauth-model","upstreamName":"echo-model",
                    "reasoning":"map","maxCompletionParam":false,"priority":100}""",
            )
            .exchange().expectStatus().isCreated

        // первый запрос: прокси сам получает токен у token endpoint и ходит с Bearer
        val responseBody = webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"model":"oauth-model","max_tokens":50,"messages":[{"role":"user","content":"hi"}]}""")
            .exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val echo = objectMapper.readTree(
            objectMapper.readTree(responseBody)
                .path("content").get(0).path("text").asText().removePrefix("echo:"),
        )
        assertEquals("Bearer oauth-test-token", echo.path("auth").asText())

        // второй запрос: токен из кэша, token endpoint не дёргается (счётчик не растёт)
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"model":"oauth-model","max_tokens":50,"messages":[{"role":"user","content":"hi"}]}""")
            .exchange().expectStatus().isOk

        webTestClient.delete().uri("/api/providers/$providerId").exchange().expectStatus().isNoContent
    }

    @Test
    fun `веб-чат — стриминг, история и сброс`() {
        // отправка: NDJSON-стрим через fake anthropic-провайдера
        val streamBody = webTestClient.post().uri("/api/chat/send?key=test&model=fake-model")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"content":"привет"}""")
            .exchange().expectStatus().isOk
            .expectHeader().contentTypeCompatibleWith(MediaType.parseMediaType("application/x-ndjson"))
            .expectBody(String::class.java).returnResult().responseBody!!
        assertTrue(streamBody.contains("\"type\":\"text\""))
        assertTrue(streamBody.contains("\"type\":\"done\""))

        // история сохраняется (user + assistant — ассистент пишется асинхронно)
        runBlocking {
            withTimeout(5_000) {
                while (true) {
                    val count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM chat_message WHERE client_key = 'test'",
                        Int::class.java,
                    )
                    if (count != null && count >= 2) break
                    delay(100)
                }
            }
        }
        val stateBody = webTestClient.get().uri("/api/chat/state?key=test")
            .exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!
        val state = objectMapper.readTree(stateBody)
        assertEquals("привет", state.path("thread").path("title").asText()) // авто-заголовок
        assertEquals(2, state.path("messages").size())
        assertEquals("user", state.path("messages").get(0).path("role").asText())
        assertEquals("assistant", state.path("messages").get(1).path("role").asText())
        assertEquals("привет", state.path("messages").get(1).path("content").asText())

        // usage атрибутируется выбранному ключу
        awaitUsageEventRow("model = 'fake-model' AND client_key = 'test' AND stream = 1")

        // переименование треда
        webTestClient.put().uri("/api/chat/thread?key=test")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"title":"Мой чат"}""")
            .exchange().expectStatus().isOk
        webTestClient.get().uri("/api/chat/state?key=test")
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.thread.title").isEqualTo("Мой чат")

        // сброс
        webTestClient.delete().uri("/api/chat/messages?key=test")
            .exchange().expectStatus().isOk
        webTestClient.get().uri("/api/chat/state?key=test")
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.messages.length()").isEqualTo(0)
    }

    @Test
    fun `экономия токенов — обрезка старых tool_result фиксируется в usage`() {
        // echo-провайдер с включённой обрезкой: tool_result старше 4 → [trimmed]
        val createdProvider = webTestClient.post().uri("/api/providers")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"name":"saver-provider","type":"openai",
                    "baseUrl":"http://127.0.0.1:${upstreamPort()}","apiKey":"saver-secret",
                    "settingOverrides":{"TRIM_OLD_TOOL_RESULTS":true,"CACHE_INJECTION":true}}""",
            )
            .exchange().expectStatus().isCreated
            .expectBody(String::class.java).returnResult().responseBody!!
        val providerId = objectMapper.readTree(createdProvider).path("id").asLong()
        webTestClient.post().uri("/api/providers/$providerId/models")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"publicName":"saver-model","upstreamName":"echo-model",
                    "reasoning":"map","maxCompletionParam":false,"priority":100}""",
            )
            .exchange().expectStatus().isCreated

        // 6 tool_result в истории — старшие 2 будут обрезаны
        val toolResults = (1..6).joinToString(",") { index ->
            """{"type":"tool_result","tool_use_id":"toolu_$index","content":"${"данные инструмента номер $index. ".repeat(50)}"}"""
        }
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", SEED_API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"model":"saver-model","max_tokens":50,
                    "messages":[
                      {"role":"user","content":"выполни инструменты"},
                      {"role":"user","content":[$toolResults]}]}""",
            )
            .exchange().expectStatus().isOk
            .expectBody(String::class.java).returnResult().responseBody!!

        // вырезанные токены зафиксированы (оценка символов/4 > 0)
        val usageRow = awaitUsageEventRow("model = 'saver-model' AND stream = 0")
        assertTrue(asLong(usageRow["saved_tokens"]) > 0)

        webTestClient.delete().uri("/api/providers/$providerId").exchange().expectStatus().isNoContent
    }

    @Test
    fun `квоты ключа - allowlist, окно и безлимит`() {
        // ключ с allowlist и крошечной квотой окна (10 токенов)
        val createdKey = webTestClient.post().uri("/api/keys")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"name":"quota-key","allowedModels":["fake-model"],
                    "limitWindowTokens":10}""",
            )
            .exchange().expectStatus().isCreated
            .expectBody(String::class.java).returnResult().responseBody!!
        val createdNode = objectMapper.readTree(createdKey)
        val quotaFullKey = createdNode.path("fullKey").asText()
        assertEquals(listOf("fake-model").joinToString(), "fake-model")

        // allowlist: чужая модель → 403
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", quotaFullKey)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"model":"fake-openai-model","max_tokens":10,"messages":[]}""")
            .exchange().expectStatus().isForbidden
            .expectBody()
            .jsonPath("$.error.type").isEqualTo("permission_error")

        // разрешённая модель: квота ещё не исчерпана (расход считается после запроса)
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", quotaFullKey)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"model":"fake-model","max_tokens":100,"messages":[{"role":"user","content":"hi"}]}""")
            .exchange().expectStatus().isOk

        // после запроса расход (35 токенов) >= квоты (10) → 429
        awaitUsageEventRow("client_key = 'quota-key'")
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", quotaFullKey)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"model":"fake-model","max_tokens":100,"messages":[{"role":"user","content":"hi"}]}""")
            .exchange().expectStatus().isEqualTo(429)
            .expectBody()
            .jsonPath("$.error.type").isEqualTo("rate_limit_error")

        // обновление квот: снимаем ограничения → снова работает (безлимит)
        val keyId = createdNode.path("clientKey").path("id").asLong()
        webTestClient.put().uri("/api/keys/$keyId")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"name":"quota-key"}""")
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.limitWindowTokens").doesNotExist()
        webTestClient.post().uri("/v1/messages")
            .header("x-api-key", quotaFullKey)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"model":"fake-model","max_tokens":100,"messages":[{"role":"user","content":"hi"}]}""")
            .exchange().expectStatus().isOk // allowlist тоже снят
    }

    private fun asLong(value: Any?): Long = (value as Number).toLong()

    private fun awaitUsageEventRows(condition: String, expectedCount: Int = 1): List<Map<String, Any?>> = runBlocking {
        withTimeout(5_000) {
            while (true) {
                val usageEventRows =
                    jdbcTemplate.queryForList("SELECT * FROM usage_event WHERE $condition")
                if (usageEventRows.size >= expectedCount) return@withTimeout usageEventRows.take(expectedCount)
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE")
            error("недостижимо")
        }
    }

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
                                request.method() == io.netty.handler.codec.http.HttpMethod.POST &&
                                    request.uri() == "/oauth/token" ->
                                    response.status(HttpResponseStatus.OK)
                                        .header("Content-Type", "application/json")
                                        .sendString(
                                            Mono.just(
                                                """{"access_token":"oauth-test-token","token_type":"Bearer","expires_in":3600}""",
                                            ),
                                            CharsetUtil.UTF_8,
                                        )
                                        .then()

                                request.method() == io.netty.handler.codec.http.HttpMethod.GET &&
                                    request.uri() == "/models" ->
                                    response.status(HttpResponseStatus.OK)
                                        .header("Content-Type", "application/json")
                                        .sendString(
                                            Mono.just(
                                                """{"object":"list","data":[{"id":"discovered-model"},{"id":"openai-model"}]}""",
                                            ),
                                            CharsetUtil.UTF_8,
                                        )
                                        .then()

                                request.uri().endsWith("/chat/completions") &&
                                    model == "fallback-primary-upstream" ->
                                    response.status(HttpResponseStatus.TOO_MANY_REQUESTS)
                                        .header("Content-Type", "application/json")
                                        .sendString(
                                            Mono.just(
                                                """{"error":{"message":"quota exceeded","type":"insufficient_quota"}}""",
                                            ),
                                            CharsetUtil.UTF_8,
                                        )
                                        .then()

                                model == "echo-model" && !request.uri().endsWith("/count_tokens") -> {
                                    // echo: возвращает параметры запроса в контенте — проверка маппера/оверрайдов
                                    val echoed = objectMapper.createObjectNode().apply {
                                        put("reasoning_effort", requestNode?.path("reasoning_effort")?.asText("") ?: "")
                                        put("max_tokens", requestNode?.path("max_tokens")?.asLong(0) ?: 0L)
                                        put("temperature", requestNode?.path("temperature")?.asText("") ?: "")
                                        put("stop", requestNode?.path("stop")?.toString() ?: "")
                                        put("auth", request.requestHeaders().get("Authorization") ?: "")
                                    }
                                    val echoBody = objectMapper.createObjectNode().apply {
                                        put("id", "chatcmpl-echo")
                                        put("object", "chat.completion")
                                        put("created", 1L)
                                        put("model", "echo-model")
                                        val choice = putArray("choices").addObject()
                                        choice.put("index", 0)
                                        val message = choice.putObject("message")
                                        message.put("role", "assistant")
                                        message.put("content", "echo:" + echoed.toString())
                                        choice.put("finish_reason", "stop")
                                        val usage = putObject("usage")
                                        usage.put("prompt_tokens", 1)
                                        usage.put("completion_tokens", 1)
                                        usage.put("total_tokens", 2)
                                    }
                                    response.status(HttpResponseStatus.OK)
                                        .header("Content-Type", "application/json")
                                        .sendString(Mono.just(echoBody.toString()), CharsetUtil.UTF_8)
                                        .then()
                                }

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

        private const val OPENAI_CLIENT_REQUEST =
            """{"model":"fake-openai-model","max_tokens":200,
               "messages":[
                 {"role":"system","content":"Ты помощник."},
                 {"role":"user","content":"Погода в Париже?"},
                 {"role":"assistant","content":"Смотрю.",
                  "tool_calls":[{"id":"call_1","type":"function",
                    "function":{"name":"get_weather","arguments":"{\"city\":\"Paris\"}"}}]},
                 {"role":"tool","tool_call_id":"call_1","content":"20C"},
                 {"role":"user","content":"Спасибо"}],
               "tools":[{"type":"function",
                 "function":{"name":"get_weather","description":"Погода в городе",
                   "parameters":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}}],
               "tool_choice":"required"}"""

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
