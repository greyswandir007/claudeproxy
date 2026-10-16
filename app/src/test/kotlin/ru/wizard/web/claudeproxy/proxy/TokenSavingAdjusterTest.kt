package ru.wizard.web.claudeproxy.proxy

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.wizard.web.claudeproxy.optimizer.OptimizerService
import ru.wizard.web.claudeproxy.routing.ModelRegistry

/**
 * Unit-тесты TokenSavingAdjuster: обрезка маркером при недоступном
 * оптимизаторе (регресс M11), сжатие при доступном, границы KEEP_TOOL_RESULTS.
 */
class TokenSavingAdjusterTest {

    private val objectMapper = ObjectMapper()
    private val optimizer = FakeOptimizerService()
    private val adjuster = TokenSavingAdjuster(objectMapper, optimizer)

    /** Управляемый фейк: доступность и ответ по тексту. */
    private class FakeOptimizerService : OptimizerService {
        var available = false
        val requestedTexts = ArrayList<String>()
        var compressedAnswer: String? = null

        override fun isAvailable(): Boolean = available

        override suspend fun compressToolResults(
            texts: List<String>,
        ): List<OptimizerService.CompressionResult> {
            requestedTexts.addAll(texts)
            return texts.map { text ->
                val compressed = compressedAnswer?.takeIf { text.isNotBlank() }
                OptimizerService.CompressionResult(compressed, text.length, compressed?.length ?: 0)
            }
        }

        override fun config(): OptimizerService.OptimizerConfig =
            OptimizerService.OptimizerConfig(false, null, null)

        override suspend fun updateConfig(request: OptimizerService.OptimizerConfigRequest) {}

        override fun stats(): OptimizerService.OptimizerStats =
            OptimizerService.OptimizerStats(
                enabled = false, providerName = null, model = null, circuitState = "OFF",
                requests = 0, cacheHits = 0, compressions = 0, notCompressed = 0,
                fallbacks = 0, failures = 0, charactersBefore = 0, charactersAfter = 0,
                estimatedTokensSaved = 0, modelTokensSpent = 0,
                averageLatencyMilliseconds = 0, maxLatencyMilliseconds = 0, lastError = null,
            )
    }

    private fun provider(trimEnabled: Boolean): ModelRegistry.ProviderInfo =
        ModelRegistry.ProviderInfo(
            id = 1,
            name = "test-provider",
            type = "anthropic",
            baseUrl = "http://127.0.0.1:1",
            apiKey = "secret",
            authType = "api_key",
            extraHeaders = emptyMap(),
            effortMapping = emptyMap(),
            settingOverrides =
                if (trimEnabled) mapOf(TokenSavingAdjuster.TRIM_TOOL_RESULTS_KEY to "true")
                else emptyMap(),
        )

    /** Запрос с count текстовыми tool_result по 1000 символов (различимые префиксы). */
    private fun requestWithToolResults(count: Int, prefix: String): ObjectNode {
        val root = objectMapper.createObjectNode()
        root.put("model", "some-model")
        root.put("max_tokens", 64)
        val messages = root.putArray("messages")
        for (index in 0 until count) {
            messages.addObject().apply {
                put("role", "user")
                putArray("content").addObject().apply {
                    put("type", "tool_result")
                    put("tool_use_id", "toolu-$index")
                    put("content", "$prefix$index-${"x".repeat(1000)}")
                }
            }
        }
        messages.addObject().apply {
            put("role", "user")
            putArray("content").addObject().apply {
                put("type", "text")
                put("text", "final question")
            }
        }
        return root
    }

    private fun toolResultTexts(root: ObjectNode): List<String> {
        val texts = ArrayList<String>()
        root.path("messages").forEach { message ->
            message.path("content").forEach { block ->
                if (block.path("type").asText() == "tool_result") {
                    texts.add(block.path("content").asText())
                }
            }
        }
        return texts
    }

    @Test
    fun `флаг обрезки выключен - содержимое не трогается`() = runBlocking {
        val root = requestWithToolResults(6, "off-")

        val savedTokens = adjuster.adjust(root, provider(trimEnabled = false))

        assertEquals(0L, savedTokens)
        assertTrue(toolResultTexts(root).none { it == TokenSavingAdjuster.TRIMMED_MARKER })
    }

    @Test
    fun `оптимизатор недоступен - маркер и оценка как в M11`() = runBlocking {
        optimizer.available = false
        val root = requestWithToolResults(6, "m11-")

        val savedTokens = adjuster.adjust(root, provider(trimEnabled = true))

        // старшие 2 из 6 обрезаны маркером: экономия = 2 × 1000+ символов / 4
        val texts = toolResultTexts(root)
        assertEquals(2, texts.count { it == TokenSavingAdjuster.TRIMMED_MARKER })
        assertEquals(4, texts.count { it.startsWith("m11-") })
        val expected = 2 * ("m11-0-${"x".repeat(1000)}".length) / 4
        assertEquals(expected.toLong(), savedTokens)
        assertTrue(optimizer.requestedTexts.isEmpty())
    }

    @Test
    fun `оптимизатор сжимает старые tool_result`() = runBlocking {
        optimizer.available = true
        optimizer.compressedAnswer = "кратко"
        val root = requestWithToolResults(6, "m30-")

        val savedTokens = adjuster.adjust(root, provider(trimEnabled = true))

        val texts = toolResultTexts(root)
        assertEquals(2, texts.count { it == "кратко" })
        // последние 4 не тронуты
        assertEquals(4, texts.count { it.startsWith("m30-") })
        val original = "m30-0-${"x".repeat(1000)}".length
        assertEquals(((original - "кратко".length) * 2 / 4).toLong(), savedTokens)
        // оптимизатор получил оба старых текста одним батчем
        assertEquals(listOf(0, 1).map { "m30-$it-${"x".repeat(1000)}" }, optimizer.requestedTexts)
    }

    @Test
    fun `массивный tool_result уходит маркером даже при включённом оптимизаторе`() = runBlocking {
        optimizer.available = true
        optimizer.compressedAnswer = "кратко"
        val root = objectMapper.createObjectNode()
        root.put("model", "some-model")
        root.put("max_tokens", 64)
        val messages = root.putArray("messages")
        // самый старый tool_result — массивный блок с картинкой: оптимизатору не подлежит
        messages.addObject().apply {
            put("role", "user")
            putArray("content").addObject().apply {
                put("type", "tool_result")
                put("tool_use_id", "toolu-massive")
                putArray("content").addObject().apply {
                    put("type", "image")
                    put("source", "massive-image-data")
                }
            }
        }
        // четыре свежих текстовых
        repeat(4) { index ->
            messages.addObject().apply {
                put("role", "user")
                putArray("content").addObject().apply {
                    put("type", "tool_result")
                    put("tool_use_id", "toolu-$index")
                    put("content", "fresh-$index")
                }
            }
        }

        val savedTokens = adjuster.adjust(root, provider(trimEnabled = true))

        val texts = toolResultTexts(root)
        assertEquals(1, texts.count { it == TokenSavingAdjuster.TRIMMED_MARKER })
        assertEquals(4, texts.count { it.startsWith("fresh-") })
        assertTrue(savedTokens > 0)
        // массивный блок ушёл оптимизатору пустым текстом и вернулся маркером
        assertEquals(listOf(""), optimizer.requestedTexts)
    }
}
