package ru.wizard.web.claudeproxy.optimizer.impl

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.springframework.mock.env.MockEnvironment
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import ru.wizard.web.claudeproxy.config.ProxyProperties
import ru.wizard.web.claudeproxy.db.DatabaseProvider
import ru.wizard.web.claudeproxy.optimizer.OptimizerService
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking

/**
 * Unit-тесты JdbcOptimizerService: сжатие обоих протоколов, кэш, single-flight,
 * LRU, breaker, таймаут, валидация настройки. Модель — фейковый reactor-netty
 * сервер, БД — временный sqlite-файл с минимальной схемой.
 */
class JdbcOptimizerServiceTest {

    @TempDir
    lateinit var tempDirectory: Path

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var proxyProperties: ProxyProperties
    private lateinit var service: JdbcOptimizerService
    private var optimizerServer: DisposableServer? = null

    /** Сколько раз модель реально получила HTTP-запрос. */
    private val modelHttpRequests = AtomicInteger()

    private val testDatabaseProvider = object : DatabaseProvider {
        override suspend fun <Result> execute(block: () -> Result): Result = block()
    }

    @BeforeEach
    fun setUp() {
        val dataSource = SimpleDriverDataSource(
            org.sqlite.JDBC(),
            "jdbc:sqlite:$tempDirectory/optimizer-test.db",
        )
        jdbcTemplate = JdbcTemplate(dataSource)
        jdbcTemplate.execute(
            """CREATE TABLE provider (
                 id INTEGER PRIMARY KEY,
                 name TEXT UNIQUE NOT NULL,
                 type TEXT NOT NULL,
                 base_url TEXT NOT NULL,
                 api_key TEXT NOT NULL,
                 auth_type TEXT NOT NULL DEFAULT 'api_key',
                 extra_headers TEXT
               )""",
        )
        jdbcTemplate.execute(
            """CREATE TABLE optimizer_config (
                 id INTEGER PRIMARY KEY CHECK (id = 1),
                 enabled INTEGER NOT NULL DEFAULT 0,
                 provider_name TEXT,
                 model TEXT,
                 updated_at BIGINT NOT NULL
               )""",
        )
        proxyProperties = ProxyProperties()
        service = JdbcOptimizerService(
            jdbcTemplate = jdbcTemplate,
            databaseProvider = testDatabaseProvider,
            proxyProperties = proxyProperties,
            environment = MockEnvironment(),
            objectMapper = ObjectMapper(),
        )
        service.afterPropertiesSet()
    }

    @AfterEach
    fun tearDown() {
        optimizerServer?.disposeNow()
    }

    private fun startAnthropicOptimizerServer(responseBody: String) {
        optimizerServer = HttpServer.create()
            .port(0)
            .handle { request, response ->
                modelHttpRequests.incrementAndGet()
                response.addHeader("Content-Type", "application/json")
                    .sendString(Mono.just(responseBody))
                    .then()
            }
            .bindNow()
    }

    private fun startOpenAiOptimizerServer(responseBody: String, authorizationHeader: AtomicInteger?) {
        optimizerServer = HttpServer.create()
            .port(0)
            .handle { request, response ->
                modelHttpRequests.incrementAndGet()
                authorizationHeader?.set(
                    request.requestHeaders().get("Authorization")?.let { it.length } ?: 0,
                )
                assertEquals("/chat/completions", request.uri())
                response.addHeader("Content-Type", "application/json")
                    .sendString(Mono.just(responseBody))
                    .then()
            }
            .bindNow()
    }

    private fun registerOptimizerProvider(type: String = "anthropic") {
        jdbcTemplate.update(
            """INSERT INTO provider (name, type, base_url, api_key, auth_type, extra_headers)
               VALUES (?, ?, ?, 'test-api-key', 'api_key', NULL)""",
            "optimizer-provider",
            type,
            "http://localhost:${optimizerServer!!.port()}",
        )
    }

    private suspend fun enableOptimizer(model: String = "cheap-model") {
        service.updateConfig(
            OptimizerService.OptimizerConfigRequest(
                enabled = true,
                providerName = "optimizer-provider",
                model = model,
            ),
        )
    }

    private fun longText(length: Int): String = "x".repeat(length)

    @Test
    fun `сжатие anthropic-провайдером, повтор идёт из кэша`() = runBlocking {
        startAnthropicOptimizerServer(
            """{"content":[{"type":"text","text":"short summary"}],
                "stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":5}}""",
        )
        registerOptimizerProvider()
        enableOptimizer()

        val text = longText(2_000)
        val first = service.compressToolResults(listOf(text))
        assertEquals("short summary", first.single().compressedText)

        val second = service.compressToolResults(listOf(text))
        assertEquals("short summary", second.single().compressedText)

        assertEquals(1, modelHttpRequests.get())
        val stats = service.stats()
        assertEquals(1L, stats.compressions)
        assertEquals(1L, stats.cacheHits)
        assertEquals(15L, stats.modelTokensSpent)
        assertEquals(((2_000 - "short summary".length) / 4).toLong(), stats.estimatedTokensSaved)
    }

    @Test
    fun `несжимаемый ответ кэшируется и не дёргает модель повторно`() = runBlocking {
        val almostOriginal = "y".repeat(900)
        startAnthropicOptimizerServer(
            """{"content":[{"type":"text","text":"$almostOriginal"}],
                "stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":5}}""",
        )
        registerOptimizerProvider()
        enableOptimizer()

        val text = longText(1_000)
        assertNull(service.compressToolResults(listOf(text)).single().compressedText)
        assertNull(service.compressToolResults(listOf(text)).single().compressedText)

        assertEquals(1, modelHttpRequests.get())
        assertEquals(2L, service.stats().notCompressed)
    }

    @Test
    fun `LRU вытесняет старые сжатия при maxCacheEntries = 1`() = runBlocking {
        proxyProperties.optimizer.maxCacheEntries = 1
        service.afterPropertiesSet()
        startAnthropicOptimizerServer(
            """{"content":[{"type":"text","text":"short summary"}],
                "stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":5}}""",
        )
        registerOptimizerProvider()
        enableOptimizer()

        val firstText = longText(2_000)
        val secondText = longText(2_100)
        assertNotNull(service.compressToolResults(listOf(firstText)).single().compressedText)
        assertNotNull(service.compressToolResults(listOf(secondText)).single().compressedText)
        // первый вытеснен вторым — модель вызывается заново
        assertNotNull(service.compressToolResults(listOf(firstText)).single().compressedText)

        assertEquals(3, modelHttpRequests.get())
    }

    @Test
    fun `breaker открывается после трёх отказов подряд`() = runBlocking {
        optimizerServer = HttpServer.create()
            .port(0)
            .handle { _, response ->
                modelHttpRequests.incrementAndGet()
                response.status(500).sendString(Mono.just("boom")).then()
            }
            .bindNow()
        registerOptimizerProvider()
        enableOptimizer()

        val texts = (0 until 3).map { longText(2_000 + it) }
        val results = service.compressToolResults(texts)
        assertTrue(results.all { it.compressedText == null })
        assertEquals(3, modelHttpRequests.get())

        assertFalse(service.isAvailable())
        assertEquals(3L, service.stats().failures)
        assertEquals("OPEN", service.stats().circuitState)

        // четвёртый вызов не доходит до модели — breaker открыт
        val blocked = service.compressToolResults(listOf(longText(5_000)))
        assertNull(blocked.single().compressedText)
        assertEquals(3, modelHttpRequests.get())
    }

    @Test
    fun `пустой ответ модели — маркер и повторный вызов`() = runBlocking {
        startAnthropicOptimizerServer(
            """{"content":[{"type":"text","text":"   "}],
                "stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":5}}""",
        )
        registerOptimizerProvider()
        enableOptimizer()

        val text = longText(2_000)
        assertNull(service.compressToolResults(listOf(text)).single().compressedText)
        assertNull(service.compressToolResults(listOf(text)).single().compressedText)

        assertEquals(2, modelHttpRequests.get())
    }

    @Test
    fun `обрезка по max_tokens — провал, не портить вывод`() = runBlocking {
        startAnthropicOptimizerServer(
            """{"content":[{"type":"text","text":"truncated summary"}],
                "stop_reason":"max_tokens","usage":{"input_tokens":10,"output_tokens":5}}""",
        )
        registerOptimizerProvider()
        enableOptimizer()

        assertNull(service.compressToolResults(listOf(longText(2_000))).single().compressedText)
    }

    @Test
    fun `openai-провайдер — свой путь и Bearer-заголовок`() = runBlocking {
        val authorizationHeaderLength = AtomicInteger(0)
        startOpenAiOptimizerServer(
            """{"choices":[{"finish_reason":"stop","message":{"content":"short summary"}}],
                "usage":{"prompt_tokens":7,"completion_tokens":3}}""",
            authorizationHeaderLength,
        )
        registerOptimizerProvider(type = "openai")
        enableOptimizer()

        val result = service.compressToolResults(listOf(longText(2_000)))
        assertEquals("short summary", result.single().compressedText)
        assertTrue(authorizationHeaderLength.get() > "Bearer ".length)
        assertEquals(10L, service.stats().modelTokensSpent)
    }

    @Test
    fun `бюджет maxCompressionsPerRequest ограничивает вызовы`() = runBlocking {
        proxyProperties.optimizer.maxCompressionsPerRequest = 1
        service.afterPropertiesSet()
        startAnthropicOptimizerServer(
            """{"content":[{"type":"text","text":"short summary"}],
                "stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":5}}""",
        )
        registerOptimizerProvider()
        enableOptimizer()

        val results = service.compressToolResults(listOf(longText(2_000), longText(2_100)))
        assertNotNull(results[0].compressedText)
        assertNull(results[1].compressedText)
        assertEquals(1, modelHttpRequests.get())
    }

    @Test
    fun `медленная модель обрывается по таймауту`() = runBlocking {
        proxyProperties.optimizer.requestTimeoutMilliseconds = 300
        service.afterPropertiesSet()
        optimizerServer = HttpServer.create()
            .port(0)
            .handle { _, response ->
                modelHttpRequests.incrementAndGet()
                Mono.delay(Duration.ofSeconds(5))
                    .then(response.sendString(Mono.just("""{"content":[]}""")).then())
            }
            .bindNow()
        registerOptimizerProvider()
        enableOptimizer()

        val startedAt = System.currentTimeMillis()
        val result = service.compressToolResults(listOf(longText(2_000)))
        val elapsedMilliseconds = System.currentTimeMillis() - startedAt

        assertNull(result.single().compressedText)
        assertTrue(elapsedMilliseconds < 3_000, "elapsed $elapsedMilliseconds ms")
    }

    @Test
    fun `updateConfig валидирует провайдера и модель`() = runBlocking {
        startAnthropicOptimizerServer(
            """{"content":[{"type":"text","text":"short summary"}],"stop_reason":"end_turn"}""",
        )
        registerOptimizerProvider()

        // провайдер не зарегистрирован
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service.updateConfig(
                    OptimizerService.OptimizerConfigRequest(true, "ghost", "cheap-model"),
                )
            }
        }
        // enabled без модели
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service.updateConfig(
                    OptimizerService.OptimizerConfigRequest(true, "optimizer-provider", " "),
                )
            }
        }
        // oauth не поддерживается
        jdbcTemplate.update("UPDATE provider SET auth_type = 'oauth' WHERE name = 'optimizer-provider'")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service.updateConfig(
                    OptimizerService.OptimizerConfigRequest(true, "optimizer-provider", "cheap-model"),
                )
            }
        }
        jdbcTemplate.update("UPDATE provider SET auth_type = 'api_key' WHERE name = 'optimizer-provider'")

        // валидная настройка читается обратно из БД
        service.updateConfig(
            OptimizerService.OptimizerConfigRequest(true, "optimizer-provider", "cheap-model"),
        )
        assertEquals(
            OptimizerService.OptimizerConfig(true, "optimizer-provider", "cheap-model"),
            service.config(),
        )
    }

    @Test
    fun `выключенный оптимизатор недоступен и не вызывает модель`() = runBlocking {
        assertFalse(service.isAvailable())
        val result = service.compressToolResults(listOf(longText(2_000)))
        assertNull(result.single().compressedText)
        assertEquals(0, modelHttpRequests.get())
        assertEquals("OFF", service.stats().circuitState)
    }
}
