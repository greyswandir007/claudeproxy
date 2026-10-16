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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.BodyInserters
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer

/**
 * M29: pass-through Batches + Files API. Создание батча маршрутизируется по
 * модели первого запроса и привязывает msgbatch_id к провайдеру; статус и
 * результаты идут по привязке (не на первичного провайдера); JSONL
 * результатов приходит с публичными именами моделей, usage учитывается один
 * раз; смешанные модели разных провайдеров отклоняются; неизвестный id и
 * листинг уходят на первичного провайдера; файл привязывается при загрузке
 * и отвязывается при удалении. Фейковые upstream-серверы считают запросы.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BatchesAndFilesIntegrationTest {

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
        jdbcTemplate.update("DELETE FROM provider_resources")
        jdbcTemplate.update("DELETE FROM message_batch_usage")
        primaryStats.reset()
        secondaryStats.reset()
    }

    @Test
    fun `создание батча переписывает модели и привязывает к провайдеру`() {
        val primaryId = createAnthropicProvider("bf-pri", "bf-pub-a", "bf-up-a", 5)
        val secondaryId = createAnthropicProvider("bf-sec", "bf-pub-b", "bf-up-b", 10)
        try {
            val created = webTestClient.post().uri("/v1/messages/batches")
                .header("x-api-key", SEED_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(batchBody("bf-pub-b"))
                .exchange().expectStatus().isOk
                .expectBody(String::class.java).returnResult().responseBody!!
            assertTrue(created.contains(""""id":"msgbatch_B1"""), created)

            // upstream-модель ушла на провайдер вторичного приоритета
            assertEquals(1, secondaryStats.batchesCreated.get())
            assertEquals(0, primaryStats.batchesCreated.get())
            assertTrue(secondaryStats.lastBatchesCreateBody.contains(""""model":"bf-up-b""""))
            assertFalse(secondaryStats.lastBatchesCreateBody.contains(""""model":"bf-pub-b""""))

            // привязка записана
            assertEquals(
                1,
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM provider_resources WHERE resource_type = 'MESSAGE_BATCH' AND resource_id = 'msgbatch_B1'",
                    Int::class.java,
                ),
            )
        } finally {
            deleteProvider(primaryId)
            deleteProvider(secondaryId)
        }
    }

    @Test
    fun `статус и результаты идут по привязке и usage учитывается один раз`() {
        val primaryId = createAnthropicProvider("sr-pri", "sr-pub-a", "sr-up-a", 5)
        val secondaryId = createAnthropicProvider("sr-sec", "sr-pub-b", "sr-up-b", 10)
        try {
            webTestClient.post().uri("/v1/messages/batches")
                .header("x-api-key", SEED_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(batchBody("sr-pub-b"))
                .exchange().expectStatus().isOk

            // статус — по привязке, не на первичного
            webTestClient.get().uri("/v1/messages/batches/msgbatch_B1")
                .header("x-api-key", SEED_API_KEY)
                .exchange().expectStatus().isOk
            assertEquals(1, secondaryStats.batchesRetrieved.get())
            assertEquals(0, primaryStats.batchesRetrieved.get())

            // результаты: model переписана в публичное имя
            val resultsBody = webTestClient.post().uri("/v1/messages/batches/msgbatch_B1/results")
                .header("x-api-key", SEED_API_KEY)
                .exchange().expectStatus().isOk
                .expectBody(String::class.java).returnResult().responseBody!!
            assertTrue(resultsBody.contains(""""model":"sr-pub-b""""), resultsBody)
            assertFalse(resultsBody.contains(""""model":"sr-up-b""""))
            assertEquals(1, secondaryStats.resultsServed.get())

            // usage записан один раз (строка с message.usage)
            awaitUsageRowCount(1)
            // повторное чтение results не дублирует usage
            webTestClient.post().uri("/v1/messages/batches/msgbatch_B1/results")
                .header("x-api-key", SEED_API_KEY)
                .exchange().expectStatus().isOk
            Thread.sleep(300)
            awaitUsageRowCount(1)

            val usageRow = jdbcTemplate.queryForMap(
                "SELECT provider, model, input_tokens, output_tokens, cache_creation_tokens, cache_read_tokens " +
                    "FROM usage_event WHERE model = 'sr-pub-b'",
            )
            assertEquals("sr-sec", usageRow["provider"].toString())
            assertEquals(11L, (usageRow["input_tokens"] as Number).toLong())
            assertEquals(7L, (usageRow["output_tokens"] as Number).toLong())
            assertEquals(2L, (usageRow["cache_creation_tokens"] as Number).toLong())
            assertEquals(3L, (usageRow["cache_read_tokens"] as Number).toLong())
        } finally {
            deleteProvider(primaryId)
            deleteProvider(secondaryId)
        }
    }

    @Test
    fun `смешанные модели разных провайдеров отклоняются`() {
        val primaryId = createAnthropicProvider("mx-pri", "mx-pub-a", "mx-up-a", 5)
        val secondaryId = createAnthropicProvider("mx-sec", "mx-pub-b", "mx-up-b", 10)
        try {
            val rejected = webTestClient.post().uri("/v1/messages/batches")
                .header("x-api-key", SEED_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                    """{"requests":[
                        {"custom_id":"r1","params":{"model":"mx-pub-a","max_tokens":16,"messages":[]}},
                        {"custom_id":"r2","params":{"model":"mx-pub-b","max_tokens":16,"messages":[]}}
                    ]}""",
                )
                .exchange().expectStatus().isBadRequest
                .expectBody(String::class.java).returnResult().responseBody!!
            assertTrue(rejected.contains("invalid_request_error"), rejected)
            assertEquals(0, primaryStats.batchesCreated.get())
            assertEquals(0, secondaryStats.batchesCreated.get())
        } finally {
            deleteProvider(primaryId)
            deleteProvider(secondaryId)
        }
    }

    @Test
    fun `неизвестный id и листинг уходят на первичного провайдера`() {
        val primaryId = createAnthropicProvider("uk-pri", "uk-pub-a", "uk-up-a", 5)
        val secondaryId = createAnthropicProvider("uk-sec", "uk-pub-b", "uk-up-b", 10)
        try {
            // привязки нет → первичный провайдер, upstream отвечает 404
            webTestClient.get().uri("/v1/messages/batches/msgbatch_nope")
                .header("x-api-key", SEED_API_KEY)
                .exchange().expectStatus().isNotFound
            assertEquals(1, primaryStats.batchesRetrieved.get())
            assertEquals(0, secondaryStats.batchesRetrieved.get())

            // листинг — на первичного, query пробрасывается
            webTestClient.get().uri("/v1/messages/batches?limit=5&after_id=msgbatch_x")
                .header("x-api-key", SEED_API_KEY)
                .exchange().expectStatus().isOk
            assertEquals(1, primaryStats.batchesListed.get())
            assertEquals(0, secondaryStats.batchesListed.get())
            assertTrue(primaryStats.lastBatchesListUri.contains("limit=5"))
            assertTrue(primaryStats.lastBatchesListUri.contains("after_id=msgbatch_x"))
        } finally {
            deleteProvider(primaryId)
            deleteProvider(secondaryId)
        }
    }

    @Test
    fun `файл загружается на первичного и отвязывается при удалении`() {
        val primaryId = createAnthropicProvider("fl-pri", "fl-pub-a", "fl-up-a", 5)
        val secondaryId = createAnthropicProvider("fl-sec", "fl-pub-b", "fl-up-b", 10)
        try {
            val fileResource = object : ByteArrayResource(FILE_CONTENT.toByteArray()) {
                override fun getFilename(): String = "data.bin"
            }
            val uploaded = webTestClient.post().uri("/v1/files")
                .header("x-api-key", SEED_API_KEY)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData("file", fileResource))
                .exchange().expectStatus().isOk
                .expectBody(String::class.java).returnResult().responseBody!!
            assertTrue(uploaded.contains(""""id":"file_F1""""), uploaded)
            // multipart-тело прокачано целиком и содержит содержимое файла
            assertTrue(primaryStats.lastUploadBody.contains(FILE_CONTENT), primaryStats.lastUploadBody)
            assertEquals(1, primaryStats.filesUploaded.get())

            // метаданные, контент и удаление — по привязке на первичного
            webTestClient.get().uri("/v1/files/file_F1")
                .header("x-api-key", SEED_API_KEY)
                .exchange().expectStatus().isOk
            webTestClient.get().uri("/v1/files/file_F1/content")
                .header("x-api-key", SEED_API_KEY)
                .exchange().expectStatus().isOk
                .expectBody(String::class.java).isEqualTo(FILE_CONTENT)
            webTestClient.delete().uri("/v1/files/file_F1")
                .header("x-api-key", SEED_API_KEY)
                .exchange().expectStatus().isNoContent
            assertEquals(1, primaryStats.filesDeleted.get())

            // привязка удалена
            assertEquals(
                0,
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM provider_resources WHERE resource_type = 'FILE'",
                    Int::class.java,
                ),
            )

            // листинг файлов — тоже на первичного
            webTestClient.get().uri("/v1/files")
                .header("x-api-key", SEED_API_KEY)
                .exchange().expectStatus().isOk
            assertEquals(1, primaryStats.filesListed.get())
            assertEquals(0, secondaryStats.filesUploaded.get() + secondaryStats.filesListed.get())
        } finally {
            deleteProvider(primaryId)
            deleteProvider(secondaryId)
        }
    }

    private fun batchBody(publicModel: String): String =
        """{"requests":[
            {"custom_id":"req-1","params":{"model":"$publicModel","max_tokens":16,"messages":[{"role":"user","content":"привет"}]}},
            {"custom_id":"req-2","params":{"model":"$publicModel","max_tokens":16,"messages":[{"role":"user","content":"пока"}]}}
        ]}"""

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
                """{"name":"$name","type":"anthropic","baseUrl":"${upstreamUrlOf(name)}",
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

    private fun upstreamUrlOf(providerName: String): String =
        if (providerName.endsWith("-sec")) {
            "http://127.0.0.1:${secondaryUpstream.port()}"
        } else {
            "http://127.0.0.1:${primaryUpstream.port()}"
        }

    private fun deleteProvider(providerId: Long) {
        webTestClient.delete().uri("/api/providers/$providerId")
            .exchange().expectStatus().isNoContent
    }

    private suspend fun awaitUsageRowCountImpl(expectedCount: Int) {
        while (true) {
            val rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM usage_event WHERE model LIKE 'sr-%'",
                Int::class.java,
            )!!
            if (rowCount >= expectedCount) return
            delay(100)
        }
    }

    private fun awaitUsageRowCount(expectedCount: Int) {
        runBlocking {
            withTimeout(15_000) {
                awaitUsageRowCountImpl(expectedCount)
            }
        }
    }

    /** Счётчики и последние тела запросов одного фейкового upstream. */
    private class FakeUpstreamStats {
        val batchesCreated = AtomicInteger()
        val batchesRetrieved = AtomicInteger()
        val batchesListed = AtomicInteger()
        val resultsServed = AtomicInteger()
        val filesUploaded = AtomicInteger()
        val filesListed = AtomicInteger()
        val filesDeleted = AtomicInteger()
        var lastBatchesCreateBody: String = ""
        var lastBatchesListUri: String = ""
        var lastUploadBody: String = ""

        fun reset() {
            batchesCreated.set(0)
            batchesRetrieved.set(0)
            batchesListed.set(0)
            resultsServed.set(0)
            filesUploaded.set(0)
            filesListed.set(0)
            filesDeleted.set(0)
            lastBatchesCreateBody = ""
            lastBatchesListUri = ""
            lastUploadBody = ""
        }
    }

    companion object {
        // Фиктивный ключ теста, случайный на каждый запуск — ничем не похож
        // на реальный и нигде не логируется.
        private val SEED_API_KEY = "test-key-${UUID.randomUUID()}"
        private const val FILE_CONTENT = "test-file-content"
        private val objectMapper = ObjectMapper()

        private val primaryStats = FakeUpstreamStats()
        private val secondaryStats = FakeUpstreamStats()

        private lateinit var primaryUpstream: DisposableServer
        private lateinit var secondaryUpstream: DisposableServer

        init {
            // каталог для тестовых SQLite: DynamicPropertySource вычисляется позднее,
            // поэтому создаём заранее
            Files.createDirectories(Path.of("build/test"))
            primaryUpstream = startFakeUpstream(primaryStats)
            secondaryUpstream = startFakeUpstream(secondaryStats)
        }

        private fun startFakeUpstream(stats: FakeUpstreamStats): DisposableServer =
            HttpServer.create().port(0)
                .handle { request, response ->
                    request.receive().aggregate().asString().defaultIfEmpty("")
                        .flatMap { upstreamRequestBody ->
                            val path = request.uri().substringBefore('?')
                            val method = request.method()
                            when {
                                method == io.netty.handler.codec.http.HttpMethod.POST &&
                                    path == "/v1/messages/batches" -> {
                                    stats.batchesCreated.incrementAndGet()
                                    stats.lastBatchesCreateBody = upstreamRequestBody
                                    ok(
                                        response,
                                        """{"id":"msgbatch_B1","type":"message_batch",""" +
                                            """"processing_status":"in_progress",""" +
                                            """"request_counts":{"processing":2,"succeeded":0,"errored":0,"canceled":0,"expired":0},""" +
                                            """"created_at":1,"expires_at":2}""",
                                    )
                                }

                                method == io.netty.handler.codec.http.HttpMethod.GET &&
                                    path == "/v1/messages/batches" -> {
                                    stats.batchesListed.incrementAndGet()
                                    stats.lastBatchesListUri = request.uri()
                                    ok(response, """{"data":[{"id":"msgbatch_B1"}],"has_more":false}""")
                                }

                                method == io.netty.handler.codec.http.HttpMethod.GET &&
                                    path.startsWith("/v1/messages/batches/") -> {
                                    stats.batchesRetrieved.incrementAndGet()
                                    if (path.endsWith("/msgbatch_nope")) {
                                        response.status(HttpResponseStatus.NOT_FOUND).send().then()
                                    } else {
                                        ok(
                                            response,
                                            """{"id":"msgbatch_B1","type":"message_batch",""" +
                                                """"processing_status":"ended",""" +
                                                """"request_counts":{"processing":0,"succeeded":1,"errored":1,"canceled":0,"expired":0},""" +
                                                """"created_at":1,"expires_at":2}""",
                                        )
                                    }
                                }

                                method == io.netty.handler.codec.http.HttpMethod.POST &&
                                    path.endsWith("/cancel") -> {
                                    ok(response, """{"id":"msgbatch_B1","processing_status":"canceling"}""")
                                }

                                method == io.netty.handler.codec.http.HttpMethod.POST &&
                                    path.endsWith("/results") -> {
                                    stats.resultsServed.incrementAndGet()
                                    // upstream-модель: контроллер должен переписать её в публичную
                                    response.status(HttpResponseStatus.OK)
                                        .header("Content-Type", "application/jsonl")
                                        .sendString(
                                            Mono.just(
                                                """{"custom_id":"req-1","result":{"type":"succeeded",""" +
                                                    """"message":{"id":"msg_1","model":"sr-up-b",""" +
                                                    """"content":[{"type":"text","text":"ok"}],""" +
                                                    """"usage":{"input_tokens":11,"output_tokens":7,""" +
                                                    """"cache_creation_input_tokens":2,"cache_read_input_tokens":3}}}}""" + "\n" +
                                                    """{"custom_id":"req-2","result":{"type":"errored",""" +
                                                    """"error":{"type":"invalid_request_error","message":"bad"}}}""" + "\n",
                                            ),
                                            CharsetUtil.UTF_8,
                                        )
                                        .then()
                                }

                                method == io.netty.handler.codec.http.HttpMethod.POST &&
                                    path == "/v1/files" -> {
                                    stats.filesUploaded.incrementAndGet()
                                    stats.lastUploadBody = upstreamRequestBody
                                    ok(
                                        response,
                                        """{"id":"file_F1","filename":"data.bin",""" +
                                            """"mime_type":"application/octet-stream","size_bytes":${upstreamRequestBody.toByteArray(Charsets.UTF_8).size},""" +
                                            """"created_at":1,"type":"file"}""",
                                    )
                                }

                                method == io.netty.handler.codec.http.HttpMethod.GET &&
                                    path == "/v1/files" -> {
                                    stats.filesListed.incrementAndGet()
                                    ok(response, """{"data":[{"id":"file_F1"}],"has_more":false}""")
                                }

                                method == io.netty.handler.codec.http.HttpMethod.GET &&
                                    path.endsWith("/content") -> {
                                    response.status(HttpResponseStatus.OK)
                                        .sendString(Mono.just(FILE_CONTENT), CharsetUtil.UTF_8)
                                        .then()
                                }

                                method == io.netty.handler.codec.http.HttpMethod.GET &&
                                    path.startsWith("/v1/files/") -> {
                                    ok(
                                        response,
                                        """{"id":"file_F1","filename":"data.bin",""" +
                                            """"mime_type":"application/octet-stream","size_bytes":3,""" +
                                            """"created_at":1,"type":"file"}""",
                                    )
                                }

                                method == io.netty.handler.codec.http.HttpMethod.DELETE &&
                                    path.startsWith("/v1/files/") -> {
                                    stats.filesDeleted.incrementAndGet()
                                    response.status(HttpResponseStatus.NO_CONTENT).send().then()
                                }

                                else ->
                                    response.status(HttpResponseStatus.NOT_FOUND).send().then()
                            }
                        }
                }
                .bindNow()

        private fun ok(
            response: reactor.netty.http.server.HttpServerResponse,
            body: String,
        ): Mono<Void> = response.status(HttpResponseStatus.OK)
            .header("Content-Type", "application/json")
            .sendString(Mono.just(body), CharsetUtil.UTF_8)
            .then()

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(propertyRegistry: DynamicPropertyRegistry) {
            propertyRegistry.add("spring.datasource.url") {
                "jdbc:sqlite:build/test/batches-itest-${UUID.randomUUID()}.db"
            }
            propertyRegistry.add("claudeproxy.api-keys[0].name") { "test" }
            propertyRegistry.add("claudeproxy.api-keys[0].key") { SEED_API_KEY }
        }
    }
}
