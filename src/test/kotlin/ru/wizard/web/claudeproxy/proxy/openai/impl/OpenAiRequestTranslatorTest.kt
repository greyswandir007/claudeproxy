package ru.wizard.web.claudeproxy.proxy.openai.impl

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.wizard.web.claudeproxy.routing.ModelRegistry

/**
 * Юнит-тесты транслятора запросов Claude → OpenAI (исходящее направление,
 * прокси → провайдер). Контекст Spring не поднимается.
 */
class OpenAiRequestTranslatorTest {

    private val objectMapper = ObjectMapper()
    private val translator = OpenAiRequestTranslator(objectMapper)

    private fun route(maxCompletionParam: Boolean = false, reasoning: String = "map"): ModelRegistry.Route {
        val provider = ModelRegistry.ProviderInfo(
            id = 1L,
            name = "test-provider",
            type = "openai",
            baseUrl = "http://upstream.test",
            apiKey = "secret",
            authType = "api_key",
            extraHeaders = emptyMap(),
            effortMapping = emptyMap(),
            settingOverrides = emptyMap(),
        )
        val mapping = ModelRegistry.ModelInfo(
            publicName = "public-model",
            upstreamName = "upstream-model",
            reasoning = reasoning,
            maxCompletionParam = maxCompletionParam,
            priority = 1,
        )
        return ModelRegistry.Route(provider, mapping)
    }

    private fun translate(
        body: String,
        route: ModelRegistry.Route = route(),
    ): com.fasterxml.jackson.databind.node.ObjectNode = translator.translate(objectMapper.readTree(body), route)

    @Test
    fun `система строкой становится первым сообщением`() {
        val result = translate(
            """
            {
              "model": "public-model",
              "system": "Ты полезный ассистент",
              "max_tokens": 100,
              "messages": [
                {"role": "user", "content": [{"type": "text", "text": "Привет"}]}
              ]
            }
            """.trimIndent(),
        )
        assertEquals("upstream-model", result.path("model").asText())
        val messages = result.path("messages")
        assertEquals(2, messages.size())
        assertEquals("system", messages.path(0).path("role").asText())
        assertEquals("Ты полезный ассистент", messages.path(0).path("content").asText())
        assertEquals("user", messages.path(1).path("role").asText())
        assertTrue(messages.path(1).path("content").toString().contains("Привет"))
    }

    @Test
    fun `система массивом текстовых блоков склеивается в строку`() {
        val result = translate(
            """
            {
              "model": "public-model",
              "system": [{"type": "text", "text": "Правило одно"}],
              "max_tokens": 10,
              "messages": [
                {"role": "user", "content": [{"type": "text", "text": "Вопрос"}]}
              ]
            }
            """.trimIndent(),
        )
        val systemMessage = result.path("messages").path(0)
        assertEquals("system", systemMessage.path("role").asText())
        assertEquals("Правило одно", systemMessage.path("content").asText())
    }

    @Test
    fun `assistant с текстом и tool_use превращается в tool_calls`() {
        val result = translate(
            """
            {
              "model": "public-model",
              "max_tokens": 100,
              "messages": [
                {"role": "user", "content": [{"type": "text", "text": "Погода?"}]},
                {
                  "role": "assistant",
                  "content": [
                    {"type": "text", "text": "Секунду"},
                    {"type": "tool_use", "id": "toolu_1", "name": "get_weather",
                     "input": {"city": "Москва"}}
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        val assistant = result.path("messages").path(1)
        assertEquals("assistant", assistant.path("role").asText())
        assertEquals("Секунду", assistant.path("content").asText())
        val toolCall = assistant.path("tool_calls").path(0)
        assertEquals("toolu_1", toolCall.path("id").asText())
        assertEquals("function", toolCall.path("type").asText())
        assertEquals("get_weather", toolCall.path("function").path("name").asText())
        assertEquals("""{"city":"Москва"}""", toolCall.path("function").path("arguments").asText())
    }

    @Test
    fun `tool_result становится сообщением role=tool`() {
        val result = translate(
            """
            {
              "model": "public-model",
              "max_tokens": 100,
              "messages": [
                {"role": "user", "content": [{"type": "text", "text": "Погода?"}]},
                {
                  "role": "assistant",
                  "content": [
                    {"type": "tool_use", "id": "toolu_1", "name": "get_weather", "input": {}}
                  ]
                },
                {
                  "role": "user",
                  "content": [
                    {"type": "tool_result", "tool_use_id": "toolu_1",
                     "content": [{"type": "text", "text": "+15"}]}
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        val toolMessage = result.path("messages").path(2)
        assertEquals("tool", toolMessage.path("role").asText())
        assertEquals("toolu_1", toolMessage.path("tool_call_id").asText())
        assertTrue(toolMessage.path("content").toString().contains("+15"))
    }

    @Test
    fun `base64 картинка становится image_url с data-ссылкой`() {
        val result = translate(
            """
            {
              "model": "public-model",
              "max_tokens": 100,
              "messages": [
                {
                  "role": "user",
                  "content": [
                    {"type": "image", "source": {"type": "base64", "media_type": "image/png",
                     "data": "abc123"}}
                  ]
                }
              ]
            }
            """.trimIndent(),
        )
        val image = result.path("messages").path(0).path("content").path(0)
        assertEquals("image_url", image.path("type").asText())
        assertEquals("data:image/png;base64,abc123", image.path("image_url").path("url").asText())
    }

    @Test
    fun `инструменты переводятся в function-описания`() {
        val result = translate(
            """
            {
              "model": "public-model",
              "max_tokens": 100,
              "messages": [{"role": "user", "content": [{"type": "text", "text": "Вызови"}]}],
              "tools": [
                {"name": "get_weather", "description": "Погода в городе",
                 "input_schema": {"type": "object", "properties": {"city": {"type": "string"}}}}
              ]
            }
            """.trimIndent(),
        )
        val tool = result.path("tools").path(0)
        assertEquals("function", tool.path("type").asText())
        assertEquals("get_weather", tool.path("function").path("name").asText())
        assertEquals("Погода в городе", tool.path("function").path("description").asText())
        assertEquals(
            objectMapper.readTree("""{"type":"object","properties":{"city":{"type":"string"}}}"""),
            tool.path("function").path("parameters"),
        )
    }

    @Test
    fun `tool_choice отображается во все варианты OpenAI`() {
        fun toolChoice(choice: String): JsonNode = translate(
            """
            {
              "model": "public-model",
              "max_tokens": 10,
              "messages": [{"role": "user", "content": [{"type": "text", "text": "q"}]}],
              "tools": [{"name": "get_weather", "input_schema": {"type": "object"}}],
              "tool_choice": $choice
            }
            """.trimIndent(),
        ).path("tool_choice")

        assertEquals("auto", toolChoice("""{"type": "auto"}""").asText())
        assertEquals("required", toolChoice("""{"type": "any"}""").asText())
        assertEquals("none", toolChoice("""{"type": "none"}""").asText())
        val named = toolChoice("""{"type": "tool", "name": "get_weather"}""")
        assertEquals("function", named.path("type").asText())
        assertEquals("get_weather", named.path("function").path("name").asText())
    }

    @Test
    fun `disable_parallel_tool_use выключает параллельные вызовы`() {
        val result = translate(
            """
            {
              "model": "public-model",
              "max_tokens": 10,
              "messages": [{"role": "user", "content": [{"type": "text", "text": "q"}]}],
              "tools": [{"name": "get_weather", "input_schema": {"type": "object"}}],
              "tool_choice": {"type": "auto", "disable_parallel_tool_use": true}
            }
            """.trimIndent(),
        )
        assertEquals("auto", result.path("tool_choice").asText())
        assertFalse(result.path("parallel_tool_calls").asBoolean(true))
    }

    @Test
    fun `параметры запроса пробрасываются, stop_sequences в stop`() {
        val result = translate(
            """
            {
              "model": "public-model",
              "max_tokens": 77,
              "stream": true,
              "temperature": 0.2,
              "top_p": 0.9,
              "metadata": {"user_id": "user-42"},
              "stop_sequences": ["END", "STOP"],
              "messages": [{"role": "user", "content": [{"type": "text", "text": "q"}]}]
            }
            """.trimIndent(),
        )
        assertEquals(77, result.path("max_tokens").asInt())
        assertTrue(result.path("stream").asBoolean())
        assertEquals(0.2, result.path("temperature").asDouble(), 1e-9)
        assertEquals(0.9, result.path("top_p").asDouble(), 1e-9)
        assertEquals("user-42", result.path("user").asText())
        assertEquals(
            objectMapper.readTree("""["END","STOP"]"""),
            result.path("stop"),
        )
    }

    @Test
    fun `для моделей с max_completion_params лимит уходит в новый параметр`() {
        val result = translate(
            """
            {
              "model": "public-model",
              "max_tokens": 55,
              "messages": [{"role": "user", "content": [{"type": "text", "text": "q"}]}]
            }
            """.trimIndent(),
            route = route(maxCompletionParam = true),
        )
        assertEquals(55, result.path("max_completion_tokens").asInt())
        assertFalse(result.has("max_tokens"))
    }

    @Test
    fun `effort из output_config переводится в reasoning_effort`() {
        fun effort(outputConfig: String): JsonNode = translate(
            """
            {
              "model": "public-model",
              "max_tokens": 100,
              "thinking": {"type": "enabled"},
              "output_config": $outputConfig,
              "messages": [{"role": "user", "content": [{"type": "text", "text": "q"}]}]
            }
            """.trimIndent(),
        ).path("reasoning_effort")

        assertEquals("low", effort("""{"effort": "low"}""").asText())
        assertEquals("medium", effort("""{"effort": "medium"}""").asText())
        assertEquals("high", effort("""{"effort": "high"}""").asText())
        assertEquals("high", effort("""{"effort": "absurd"}""").asText())
    }

    @Test
    fun `thinking disabled и reasoning off не дают reasoning_effort`() {
        val disabled = translate(
            """
            {
              "model": "public-model",
              "max_tokens": 100,
              "thinking": {"type": "disabled"},
              "output_config": {"effort": "low"},
              "messages": [{"role": "user", "content": [{"type": "text", "text": "q"}]}]
            }
            """.trimIndent(),
        )
        assertFalse(disabled.has("reasoning_effort"))

        val reasoningOff = translate(
            """
            {
              "model": "public-model",
              "max_tokens": 100,
              "thinking": {"type": "enabled"},
              "output_config": {"effort": "low"},
              "messages": [{"role": "user", "content": [{"type": "text", "text": "q"}]}]
            }
            """.trimIndent(),
            route = route(reasoning = "off"),
        )
        assertFalse(reasoningOff.has("reasoning_effort"))
    }
}
