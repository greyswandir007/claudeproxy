package ru.wizard.web.claudeproxy.proxy.openai.impl

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Юнит-тесты SSE state machine: чанки OpenAI Chat Completions → события
 * Claude. Проверяется порядок событий и содержимое payload-ов.
 */
class OpenAiSseTranslatorTest {

    private val objectMapper = ObjectMapper()

    private fun translator() = OpenAiSseTranslator(objectMapper, "public-model")

    private class SseEvent(val name: String, val data: JsonNode)

    /** Разбирает "event: X\ndata: {...}\n\n" на имя и payload. */
    private fun parseEvent(raw: String): SseEvent {
        val eventName = Regex("event: (\\S+)").find(raw)?.groupValues?.get(1)
            ?: error("no event name in: $raw")
        val dataLine = Regex("data: (.*)").find(raw)?.groupValues?.get(1)
            ?: error("no data in: $raw")
        return SseEvent(eventName, objectMapper.readTree(dataLine))
    }

    private fun feed(translator: OpenAiSseTranslator, vararg payloads: String): List<SseEvent> =
        payloads.flatMap(translator::onData).map(::parseEvent)

    @Test
    fun `текстовый стрим даёт message_start дельты и финальные события`() {
        val translator = translator()
        val events = feed(
            translator,
            """{"choices":[{"index":0,"delta":{"role":"assistant","content":""}}],"id":"c1"}""",
            """{"choices":[{"index":0,"delta":{"content":"При"}}]}""",
            """{"choices":[{"index":0,"delta":{"content":"вет"}}]}""",
            """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":11,"completion_tokens":7}}""",
            "[DONE]",
        )

        assertEquals("message_start", events.first().name)
        assertEquals("public-model", events.first().data.path("message").path("model").asText())
        assertEquals("assistant", events.first().data.path("message").path("role").asText())

        val text = events
            .filter { it.name == "content_block_delta" }
            .map { it.data.path("delta").path("text").asText() }
            .joinToString("")
        assertEquals("Привет", text)

        assertTrue(events.any { it.name == "content_block_start" && it.data.path("content_block").path("type").asText() == "text" })

        val messageDelta = events.last { it.name == "message_delta" }
        assertEquals("end_turn", messageDelta.data.path("delta").path("stop_reason").asText())
        assertEquals(11, messageDelta.data.path("usage").path("input_tokens").asInt())
        assertEquals(7, messageDelta.data.path("usage").path("output_tokens").asInt())
        assertEquals("message_stop", events.last().name)
    }

    @Test
    fun `вызов инструмента собирается из фрагментов аргументов`() {
        val translator = translator()
        val events = feed(
            translator,
            """{"choices":[{"delta":{"role":"assistant"}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_weather","arguments":""}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"city\""}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":": \"Москва\"}"}}]}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":5,"completion_tokens":6}}""",
            "[DONE]",
        )

        val blockStart = events.first { it.name == "content_block_start" }
        val block = blockStart.data.path("content_block")
        assertEquals("tool_use", block.path("type").asText())
        assertEquals("call_1", block.path("id").asText())
        assertEquals("get_weather", block.path("name").asText())

        val arguments = events
            .filter { it.name == "content_block_delta" }
            .map { it.data.path("delta").path("partial_json").asText() }
            .joinToString("")
        assertEquals("""{"city": "Москва"}""", arguments)

        val messageDelta = events.last { it.name == "message_delta" }
        assertEquals("tool_use", messageDelta.data.path("delta").path("stop_reason").asText())
        assertTrue(events.any { it.name == "content_block_stop" })
    }

    @Test
    fun `reasoning_content идёт дельтами thinking`() {
        val translator = translator()
        val events = feed(
            translator,
            """{"choices":[{"delta":{"role":"assistant","reasoning_content":"дум"}}]}""",
            """{"choices":[{"delta":{"reasoning_content":"аю"}}]}""",
            """{"choices":[{"delta":{"content":"ответ"}}]}""",
            "[DONE]",
        )

        val thinking = events
            .filter { it.name == "content_block_delta" }
            .filter { it.data.path("delta").has("thinking") }
            .map { it.data.path("delta").path("thinking").asText() }
            .joinToString("")
        assertEquals("думаю", thinking)
        assertTrue(events.any { it.name == "content_block_start" && it.data.path("content_block").path("type").asText() == "thinking" })
        assertTrue(events.any { it.name == "content_block_start" && it.data.path("content_block").path("type").asText() == "text" })
    }

    @Test
    fun `DONE без данных даёт корректный пустой стрим`() {
        val translator = translator()
        val events = feed(translator, "[DONE]")

        assertEquals("message_start", events.first().name)
        assertEquals("message_stop", events.last().name)
        val messageDelta = events.last { it.name == "message_delta" }
        assertEquals("end_turn", messageDelta.data.path("delta").path("stop_reason").asText())
        assertEquals(0, messageDelta.data.path("usage").path("input_tokens").asInt())
    }

    @Test
    fun `usage в отдельном чанке без choices учитывается в финале`() {
        val translator = translator()
        val events = feed(
            translator,
            """{"choices":[{"delta":{"content":"ok"}}]}""",
            """{"usage":{"prompt_tokens":42,"completion_tokens":4,"prompt_tokens_details":{"cached_tokens":9}}}""",
            "[DONE]",
        )

        val messageDelta = events.last { it.name == "message_delta" }
        assertEquals(42, messageDelta.data.path("usage").path("input_tokens").asInt())
        assertEquals(4, messageDelta.data.path("usage").path("output_tokens").asInt())
        assertEquals(9, messageDelta.data.path("usage").path("cache_read_input_tokens").asInt())
    }
}
