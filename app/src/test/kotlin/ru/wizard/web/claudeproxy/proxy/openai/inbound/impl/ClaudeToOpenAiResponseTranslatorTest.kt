package ru.wizard.web.claudeproxy.proxy.openai.inbound.impl

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Юнит-тесты перевода нестримового ответа Claude → OpenAI Chat Completions.
 */
class ClaudeToOpenAiResponseTranslatorTest {

    private val objectMapper = ObjectMapper()
    private val translator = ClaudeToOpenAiResponseTranslator(objectMapper)

    private fun translate(body: String) = translator.translate(objectMapper.readTree(body), "public-model")

    @Test
    fun `текстовые блоки склеиваются в content`() {
        val result = translate(
            """
            {
              "id": "msg_1",
              "type": "message",
              "role": "assistant",
              "model": "internal-model",
              "content": [
                {"type": "text", "text": "Первая часть"},
                {"type": "text", "text": "Вторая часть"}
              ],
              "stop_reason": "end_turn",
              "usage": {"input_tokens": 12, "output_tokens": 34}
            }
            """.trimIndent(),
        )
        assertEquals("msg_1", result.path("id").asText())
        assertEquals("chat.completion", result.path("object").asText())
        assertEquals("public-model", result.path("model").asText())
        val message = result.path("choices").path(0).path("message")
        assertEquals("assistant", message.path("role").asText())
        assertEquals("Первая часть\n\nВторая часть", message.path("content").asText())
        assertEquals("stop", result.path("choices").path(0).path("finish_reason").asText())
        val usage = result.path("usage")
        assertEquals(12, usage.path("prompt_tokens").asLong())
        assertEquals(34, usage.path("completion_tokens").asLong())
        assertEquals(46, usage.path("total_tokens").asLong())
        assertEquals(0, usage.path("prompt_tokens_details").path("cached_tokens").asLong())
    }

    @Test
    fun `tool_use становится tool_calls со строкой аргументов`() {
        val result = translate(
            """
            {
              "id": "msg_2",
              "content": [
                {"type": "tool_use", "id": "toolu_1", "name": "get_weather",
                 "input": {"city": "Москва"}}
              ],
              "stop_reason": "tool_use",
              "usage": {"input_tokens": 1, "output_tokens": 2}
            }
            """.trimIndent(),
        )
        val message = result.path("choices").path(0).path("message")
        assertFalse(message.has("content"))
        val toolCall = message.path("tool_calls").path(0)
        assertEquals("toolu_1", toolCall.path("id").asText())
        assertEquals("function", toolCall.path("type").asText())
        assertEquals("get_weather", toolCall.path("function").path("name").asText())
        assertEquals("""{"city":"Москва"}""", toolCall.path("function").path("arguments").asText())
        assertEquals("tool_calls", result.path("choices").path(0).path("finish_reason").asText())
    }

    @Test
    fun `блок thinking уходит в reasoning_content`() {
        val result = translate(
            """
            {
              "id": "msg_3",
              "content": [
                {"type": "thinking", "thinking": "размышления"},
                {"type": "text", "text": "ответ"}
              ],
              "stop_reason": "end_turn",
              "usage": {"input_tokens": 0, "output_tokens": 0}
            }
            """.trimIndent(),
        )
        val message = result.path("choices").path(0).path("message")
        assertEquals("размышления", message.path("reasoning_content").asText())
        assertEquals("ответ", message.path("content").asText())
    }

    @Test
    fun `stop_reason max_tokens отображается в length`() {
        val result = translate(
            """
            {
              "id": "msg_4",
              "content": [{"type": "text", "text": "обрез"}],
              "stop_reason": "max_tokens",
              "usage": {"input_tokens": 5, "output_tokens": 5}
            }
            """.trimIndent(),
        )
        assertEquals("length", result.path("choices").path(0).path("finish_reason").asText())
        assertTrue(result.path("created").asLong() > 0)
    }
}
