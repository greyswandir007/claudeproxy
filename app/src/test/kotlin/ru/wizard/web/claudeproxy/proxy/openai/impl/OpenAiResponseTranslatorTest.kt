package ru.wizard.web.claudeproxy.proxy.openai.impl

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Юнит-тесты транслятора ответов OpenAI → Claude (исходящее направление,
 * ответ провайдера → клиент прокси). Без контекста Spring.
 */
class OpenAiResponseTranslatorTest {

    private val objectMapper = ObjectMapper()
    private val translator = OpenAiResponseTranslator(objectMapper)

    private fun translate(body: String) = translator.translate(body, "public-model")

    @Test
    fun `текстовый ответ превращается в message с текстовым блоком`() {
        val translated = translate(
            """
            {
              "id": "chatcmpl-123",
              "object": "chat.completion",
              "model": "upstream-model",
              "choices": [
                {
                  "index": 0,
                  "message": {"role": "assistant", "content": "Привет, чем помочь?"},
                  "finish_reason": "stop"
                }
              ],
              "usage": {"prompt_tokens": 10, "completion_tokens": 5}
            }
            """.trimIndent(),
        )
        val result = objectMapper.readTree(translated.responseBody)
        assertTrue(result.path("id").asText().startsWith("msg_"))
        assertEquals("message", result.path("type").asText())
        assertEquals("assistant", result.path("role").asText())
        assertEquals("public-model", result.path("model").asText())
        val block = result.path("content").path(0)
        assertEquals("text", block.path("type").asText())
        assertEquals("Привет, чем помочь?", block.path("text").asText())
        assertEquals("end_turn", result.path("stop_reason").asText())
        assertEquals(10, result.path("usage").path("input_tokens").asInt())
        assertEquals(5, result.path("usage").path("output_tokens").asInt())

        assertEquals("end_turn", translated.stopReason)
        assertEquals(10, translated.usageAccumulator.inputTokens)
        assertEquals(5, translated.usageAccumulator.outputTokens)
    }

    @Test
    fun `tool_calls становятся блоками tool_use с распарсенным input`() {
        val translated = translate(
            """
            {
              "id": "chatcmpl-456",
              "object": "chat.completion",
              "model": "upstream-model",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "content": null,
                    "tool_calls": [
                      {
                        "id": "call_1",
                        "type": "function",
                        "function": {
                          "name": "get_weather",
                          "arguments": "{\"city\": \"Москва\"}"
                        }
                      }
                    ]
                  },
                  "finish_reason": "tool_calls"
                }
              ],
              "usage": {"prompt_tokens": 7, "completion_tokens": 3}
            }
            """.trimIndent(),
        )
        val result = objectMapper.readTree(translated.responseBody)
        val block = result.path("content").path(0)
        assertEquals("tool_use", block.path("type").asText())
        assertEquals("call_1", block.path("id").asText())
        assertEquals("get_weather", block.path("name").asText())
        assertEquals(
            objectMapper.readTree("""{"city": "Москва"}"""),
            block.path("input"),
        )
        assertEquals("tool_use", result.path("stop_reason").asText())
        assertEquals("tool_use", translated.stopReason)
    }

    @Test
    fun `finish_reason length отображается в max_tokens`() {
        val translated = translate(
            """
            {
              "id": "chatcmpl-789",
              "model": "upstream-model",
              "choices": [
                {"index": 0, "message": {"role": "assistant", "content": "обрез"}, "finish_reason": "length"}
              ],
              "usage": {"prompt_tokens": 1, "completion_tokens": 2}
            }
            """.trimIndent(),
        )
        val result = objectMapper.readTree(translated.responseBody)
        assertEquals("max_tokens", result.path("stop_reason").asText())
        assertEquals("max_tokens", translated.stopReason)
    }

    @Test
    fun `отсутствующий usage не ломает ответ`() {
        val translated = translate(
            """
            {
              "id": "chatcmpl-000",
              "model": "upstream-model",
              "choices": [
                {"index": 0, "message": {"role": "assistant", "content": "ответ"}, "finish_reason": "stop"}
              ]
            }
            """.trimIndent(),
        )
        val result = objectMapper.readTree(translated.responseBody)
        assertEquals(0, result.path("usage").path("input_tokens").asInt(-1))
        assertEquals(0, result.path("usage").path("output_tokens").asInt(-1))
        assertEquals(1, result.path("content").size())
        assertEquals(0, translated.usageAccumulator.inputTokens)
        assertEquals(0, translated.usageAccumulator.outputTokens)
    }
}
