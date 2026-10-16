package ru.wizard.web.claudeproxy.proxy.openai.inbound.impl

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Юнит-тесты транслятора запросов OpenAI Chat Completions → Claude
 * (входящая точка совместимости /v1/chat/completions).
 */
class OpenAiInboundRequestTranslatorTest {

    private val objectMapper = ObjectMapper()
    private val translator = OpenAiInboundRequestTranslator(objectMapper)

    private fun translate(body: String) = translator.translate(objectMapper.readTree(body))

    @Test
    fun `системные сообщения уходят в поле system`() {
        val result = translate(
            """
            {
              "model": "claude-model",
              "max_tokens": 100,
              "messages": [
                {"role": "system", "content": "Правила"},
                {"role": "developer", "content": "Ещё правило"},
                {"role": "user", "content": "Вопрос"}
              ]
            }
            """.trimIndent(),
        )
        assertEquals("claude-model", result.path("model").asText())
        assertEquals("Правила\n\nЕщё правило", result.path("system").asText())
        assertEquals(1, result.path("messages").size())
        assertEquals("user", result.path("messages").path(0).path("role").asText())
        assertEquals("Вопрос", result.path("messages").path(0).path("content").asText())
    }

    @Test
    fun `текст и tool_calls ассистента превращаются в блоки`() {
        val result = translate(
            """
            {
              "model": "m",
              "max_tokens": 100,
              "messages": [
                {"role": "user", "content": "Погода?"},
                {
                  "role": "assistant",
                  "content": "Секунду",
                  "tool_calls": [
                    {"id": "call_1", "type": "function",
                     "function": {"name": "get_weather", "arguments": "{\"city\": \"Москва\"}"}}
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        val assistant = result.path("messages").path(1)
        assertEquals("assistant", assistant.path("role").asText())
        assertEquals("text", assistant.path("content").path(0).path("type").asText())
        assertEquals("Секунду", assistant.path("content").path(0).path("text").asText())
        val toolUse = assistant.path("content").path(1)
        assertEquals("tool_use", toolUse.path("type").asText())
        assertEquals("call_1", toolUse.path("id").asText())
        assertEquals("get_weather", toolUse.path("name").asText())
        assertEquals(
            objectMapper.readTree("""{"city": "Москва"}"""),
            toolUse.path("input"),
        )
    }

    @Test
    fun `битый JSON аргументов даёт пустой input`() {
        val result = translate(
            """
            {
              "model": "m",
              "max_tokens": 100,
              "messages": [
                {"role": "assistant", "tool_calls": [
                  {"id": "call_bad", "type": "function",
                   "function": {"name": "f", "arguments": "{не json"}}
                ]}
              ]
            }
            """.trimIndent(),
        )
        val toolUse = result.path("messages").path(0).path("content").path(0)
        assertEquals("tool_use", toolUse.path("type").asText())
        assertTrue(toolUse.path("input").isObject)
        assertEquals(0, toolUse.path("input").size())
    }

    @Test
    fun `подряд идущие tool-сообщения склеиваются в один tool_result`() {
        val result = translate(
            """
            {
              "model": "m",
              "max_tokens": 100,
              "messages": [
                {"role": "assistant", "tool_calls": [
                  {"id": "c1", "type": "function", "function": {"name": "f1", "arguments": "{}"}},
                  {"id": "c2", "type": "function", "function": {"name": "f2", "arguments": "{}"}}
                ]},
                {"role": "tool", "tool_call_id": "c1", "content": "результат один"},
                {"role": "tool", "tool_call_id": "c2", "content": "результат два"}
              ]
            }
            """.trimIndent(),
        )
        val toolUser = result.path("messages").path(1)
        assertEquals("user", toolUser.path("role").asText())
        assertEquals(2, toolUser.path("content").size())
        assertEquals("tool_result", toolUser.path("content").path(0).path("type").asText())
        assertEquals("c1", toolUser.path("content").path(0).path("tool_use_id").asText())
        assertEquals("результат один", toolUser.path("content").path(0).path("content").asText())
        assertEquals("c2", toolUser.path("content").path(1).path("tool_use_id").asText())
    }

    @Test
    fun `data-ссылка картинки превращается в base64-источник`() {
        val result = translate(
            """
            {
              "model": "m",
              "max_tokens": 100,
              "messages": [
                {"role": "user", "content": [
                  {"type": "text", "text": "Что на картинке?"},
                  {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,abc123"}}
                ]}
              ]
            }
            """.trimIndent(),
        )
        val content = result.path("messages").path(0).path("content")
        assertEquals("text", content.path(0).path("type").asText())
        val image = content.path(1)
        assertEquals("image", image.path("type").asText())
        val source = image.path("source")
        assertEquals("base64", source.path("type").asText())
        assertEquals("image/jpeg", source.path("media_type").asText())
        assertEquals("abc123", source.path("data").asText())
    }

    @Test
    fun `лимиты — приоритет max_completion_tokens, дефолт 8192`() {
        fun maxTokensOf(body: String) = translate(body).path("max_tokens").asLong()
        assertEquals(
            55L,
            maxTokensOf(
                """{"model":"m","max_tokens":10,"max_completion_tokens":55,
                    "messages":[{"role":"user","content":"q"}]}""",
            ),
        )
        assertEquals(
            10L,
            maxTokensOf(
                """{"model":"m","max_tokens":10,
                    "messages":[{"role":"user","content":"q"}]}""",
            ),
        )
        assertEquals(
            8192L,
            maxTokensOf("""{"model":"m","messages":[{"role":"user","content":"q"}]}"""),
        )
    }

    @Test
    fun `stop строкой и массивом даёт stop_sequences`() {
        val single = translate(
            """{"model":"m","max_tokens":1,"stop":"END",
                "messages":[{"role":"user","content":"q"}]}""",
        )
        assertEquals(
            objectMapper.readTree("""["END"]"""),
            single.path("stop_sequences"),
        )
        val multiple = translate(
            """{"model":"m","max_tokens":1,"stop":["A","B"],
                "messages":[{"role":"user","content":"q"}]}""",
        )
        assertEquals(
            objectMapper.readTree("""["A","B"]"""),
            multiple.path("stop_sequences"),
        )
    }

    @Test
    fun `инструменты и tool_choice переводятся в диалект Claude`() {
        val result = translate(
            """
            {
              "model": "m",
              "max_tokens": 100,
              "messages": [{"role": "user", "content": "q"}],
              "tools": [
                {"type": "function", "function": {"name": "f", "description": "делает",
                 "parameters": {"type": "object"}}}
              ],
              "tool_choice": {"type": "function", "function": {"name": "f"}}
            }
            """.trimIndent(),
        )
        val tool = result.path("tools").path(0)
        assertEquals("f", tool.path("name").asText())
        assertEquals("делает", tool.path("description").asText())
        assertEquals(
            objectMapper.readTree("""{"type": "object"}"""),
            tool.path("input_schema"),
        )
        assertEquals("tool", result.path("tool_choice").path("type").asText())
        assertEquals("f", result.path("tool_choice").path("name").asText())

        assertEquals(
            "any",
            translate(
                """{"model":"m","max_tokens":1,"tool_choice":"required",
                    "tools":[{"type":"function","function":{"name":"f","parameters":{"type":"object"}}}],
                    "messages":[{"role":"user","content":"q"}]}""",
            ).path("tool_choice").path("type").asText(),
        )
        assertEquals(
            "auto",
            translate(
                """{"model":"m","max_tokens":1,"tool_choice":"auto",
                    "tools":[{"type":"function","function":{"name":"f","parameters":{"type":"object"}}}],
                    "messages":[{"role":"user","content":"q"}]}""",
            ).path("tool_choice").path("type").asText(),
        )
        assertEquals(
            "none",
            translate(
                """{"model":"m","max_tokens":1,"tool_choice":"none",
                    "tools":[{"type":"function","function":{"name":"f","parameters":{"type":"object"}}}],
                    "messages":[{"role":"user","content":"q"}]}""",
            ).path("tool_choice").path("type").asText(),
        )
    }

    @Test
    fun `reasoning_effort превращается в thinking adaptive и output_config`() {
        val result = translate(
            """
            {
              "model": "m",
              "max_tokens": 100,
              "reasoning_effort": "low",
              "stream": false,
              "messages": [{"role": "user", "content": "q"}]
            }
            """.trimIndent(),
        )
        assertEquals("adaptive", result.path("thinking").path("type").asText())
        assertEquals("low", result.path("output_config").path("effort").asText())
        assertFalse(result.has("stream"))
    }

    @Test
    fun `stream=true пробрасывается`() {
        val result = translate(
            """{"model":"m","max_tokens":1,"stream":true,
                "messages":[{"role":"user","content":"q"}]}""",
        )
        assertTrue(result.path("stream").asBoolean())
    }
}
