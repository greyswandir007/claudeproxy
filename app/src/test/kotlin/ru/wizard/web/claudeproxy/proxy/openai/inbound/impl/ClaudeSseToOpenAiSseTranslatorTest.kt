package ru.wizard.web.claudeproxy.proxy.openai.inbound.impl

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Юнит-тесты SSE state machine: события Claude → чанки OpenAI
 * chat.completion.chunk. Проверяются буферизация строк, порядок чанков,
 * перевод tool_use и завершение стрима.
 */
class ClaudeSseToOpenAiSseTranslatorTest {

    private val objectMapper = ObjectMapper()

    private fun translator() = ClaudeSseToOpenAiSseTranslator(objectMapper, "public-model")

    /** Разбирает выданную строку: маркер [DONE] или JSON-чанк. */
    private fun parseChunk(raw: String): JsonNode? {
        assertTrue(raw.startsWith("data: "), raw)
        assertTrue(raw.endsWith("\n\n"), raw)
        val payload = raw.removePrefix("data: ").trim()
        if (payload == "[DONE]") return null
        return objectMapper.readTree(payload)
    }

    private fun feed(translator: ClaudeSseToOpenAiSseTranslator, vararg claudeEvents: String): List<JsonNode?> =
        translator.onChunk(claudeEvents.joinToString("") { "data: $it\n\n" })
            .map(::parseChunk)

    @Test
    fun `message_start даёт чанк с ролью`() {
        val chunks = feed(
            translator(),
            """{"type": "message_start", "message": {"model": "internal", "role": "assistant"}}""",
        )
        assertEquals(1, chunks.size)
        val chunk = chunks.single()!!
        assertEquals("chat.completion.chunk", chunk.path("object").asText())
        assertEquals("public-model", chunk.path("model").asText())
        assertEquals("assistant", chunk.path("choices").path(0).path("delta").path("role").asText())
    }

    @Test
    fun `текстовые дельты собираются в цельный текст`() {
        val chunks = feed(
            translator(),
            """{"type": "message_start", "message": {"role": "assistant"}}""",
            """{"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "При"}}""",
            """{"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "вет"}}""",
        )
        val text = chunks
            .mapNotNull { it }
            .map { it.path("choices").path(0).path("delta").path("content").asText() }
            .filter { it.isNotEmpty() }
            .joinToString("")
        assertEquals("Привет", text)
    }

    @Test
    fun `вызов инструмента даёт tool_calls с фрагментами аргументов`() {
        val chunks = feed(
            translator(),
            """{"type": "message_start", "message": {"role": "assistant"}}""",
            """{"type": "content_block_start", "index": 1, "content_block": {"type": "tool_use", "id": "toolu_1", "name": "get_weather"}}""",
            """{"type": "content_block_delta", "index": 1, "delta": {"type": "input_json_delta", "partial_json": "{\"city\""}}""",
            """{"type": "content_block_delta", "index": 1, "delta": {"type": "input_json_delta", "partial_json": ": 1}"}}""",
        )
        val toolStart = chunks.mapNotNull { it }
            .first { it.path("choices").path(0).path("delta").has("tool_calls") }
            .path("choices").path(0).path("delta").path("tool_calls").path(0)
        assertEquals(0, toolStart.path("index").asInt())
        assertEquals("toolu_1", toolStart.path("id").asText())
        assertEquals("function", toolStart.path("type").asText())
        assertEquals("get_weather", toolStart.path("function").path("name").asText())

        val arguments = chunks.mapNotNull { it }
            .filter { it.path("choices").path(0).path("delta").has("tool_calls") }
            .map { it.path("choices").path(0).path("delta").path("tool_calls").path(0)
                .path("function").path("arguments").asText() }
            .drop(1)
            .joinToString("")
        assertEquals("""{"city": 1}""", arguments)
    }

    @Test
    fun `message_stop завершает стрим с finish_reason и usage`() {
        val chunks = feed(
            translator(),
            """{"type": "message_start", "message": {"role": "assistant"}}""",
            """{"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "ok"}}""",
            """{"type": "message_delta", "delta": {"stop_reason": "end_turn"}, "usage": {"input_tokens": 21, "output_tokens": 5, "cache_read_input_tokens": 3}}""",
            """{"type": "message_stop"}""",
        )
        val last = chunks.last()
        assertEquals(null, last, "последним должен быть маркер [DONE]")

        val finishChunk = chunks.mapNotNull { it }
            .last { it.path("choices").path(0).path("finish_reason").isTextual }
        assertEquals("stop", finishChunk.path("choices").path(0).path("finish_reason").asText())

        val usageChunk = chunks.mapNotNull { it }.first { it.has("usage") }
        assertEquals(21, usageChunk.path("usage").path("prompt_tokens").asLong())
        assertEquals(5, usageChunk.path("usage").path("completion_tokens").asLong())
        assertEquals(26, usageChunk.path("usage").path("total_tokens").asLong())
        assertEquals(3, usageChunk.path("usage").path("prompt_tokens_details").path("cached_tokens").asLong())
        assertEquals(0, usageChunk.path("choices").size())
    }

    @Test
    fun `строки приходят кусками и склеиваются до перевода строки`() {
        val translator = translator()
        val first = translator.onChunk("data: {\"type\": \"message_st")
        assertTrue(first.isEmpty(), "неполная строка не должна давать событий")
        val second = translator.onChunk("art\", \"message\": {\"role\": \"assistant\"}}\n")
        assertEquals(1, second.size)
        assertEquals("assistant", parseChunk(second.single())!!
            .path("choices").path(0).path("delta").path("role").asText())
    }

    @Test
    fun `stop_reason tool_use и max_tokens отображаются в finish_reason`() {
        fun finishOf(stopReason: String): String {
            val chunks = feed(
                translator(),
                """{"type": "message_delta", "delta": {"stop_reason": "$stopReason"}}""",
                """{"type": "message_stop"}""",
            )
            return chunks.mapNotNull { it }
                .last { it.path("choices").path(0).path("finish_reason").isTextual }
                .path("choices").path(0).path("finish_reason").asText()
        }
        assertEquals("tool_calls", finishOf("tool_use"))
        assertEquals("length", finishOf("max_tokens"))
        assertEquals("stop", finishOf("end_turn"))
    }

    @Test
    fun `событие error даёт error-чанк и DONE`() {
        val chunks = feed(
            translator(),
            """{"type": "error", "error": {"type": "overloaded_error", "message": "перегруз"}}""",
        )
        val errorChunk = chunks.mapNotNull { it }.first { it.has("error") }
        assertEquals("overloaded_error", errorChunk.path("error").path("type").asText())
        assertEquals(null, chunks.last(), "последним должен быть маркер [DONE]")
        assertFalse(chunks.isEmpty())
    }
}
