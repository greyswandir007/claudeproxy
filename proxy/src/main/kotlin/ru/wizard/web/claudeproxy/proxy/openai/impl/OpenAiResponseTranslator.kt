package ru.wizard.web.claudeproxy.proxy.openai.impl

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.oshai.kotlinlogging.KotlinLogging
import ru.wizard.web.claudeproxy.proxy.ApiError
import ru.wizard.web.claudeproxy.proxy.UsageAccumulator

/**
 * Перевод нестримового ответа OpenAI Chat Completions → сообщение Claude.
 */
class OpenAiResponseTranslator(private val objectMapper: ObjectMapper) {
    private val logger = KotlinLogging.logger {}

    data class TranslatedResponse(
        val responseBody: String,
        val usageAccumulator: UsageAccumulator,
        val stopReason: String,
    )

    /** Переводит нестриминговый ответ OpenAI в формат Claude message. */
    fun translate(openAiResponseBody: String?, publicModel: String): TranslatedResponse {
        val root = runCatching { objectMapper.readTree(openAiResponseBody ?: "") }.getOrNull()
            ?: throw ApiError(
                org.springframework.http.HttpStatus.BAD_GATEWAY,
                "api_error",
                "Некорректный JSON в ответе провайдера",
            )
        val choice = root.path("choices").firstOrNull()
            ?: return failure("Пустой ответ провайдера (нет choices)")
        val message = choice.path("message")

        val content: ArrayNode = objectMapper.createArrayNode()
        message.path("reasoning_content").takeIf { it.isTextual && it.asText().isNotEmpty() }
            ?.let { content.add(thinkingBlock(it.asText())) }
        message.path("reasoning").takeIf { it.isTextual && it.asText().isNotEmpty() }
            ?.let { if (content.size() == 0) content.add(thinkingBlock(it.asText())) }

        val messageContent = message.get("content")
        when {
            messageContent != null && messageContent.isTextual && messageContent.asText().isNotEmpty() ->
                content.add(textBlock(messageContent.asText()))

            messageContent != null && messageContent.isArray ->
                for (part in messageContent) {
                    if (part.path("type").asText("text") == "text" && part.hasNonNull("text")) {
                        content.add(textBlock(part.path("text").asText()))
                    }
                }
        }
        for (toolCall in message.path("tool_calls")) {
            content.add(toolUseBlock(toolCall))
        }

        val stopReason = mapFinishReason(choice.path("finish_reason").asText("stop"))

        val usageNode = root.path("usage")
        val usageAccumulator = UsageAccumulator()
        usageAccumulator.setValues(
            inputTokens = usageNode.path("prompt_tokens").asLong(0),
            outputTokens = usageNode.path("completion_tokens").asLong(0),
            cacheCreationTokens = 0,
            cacheReadTokens = usageNode.path("prompt_tokens_details").path("cached_tokens").asLong(0),
        )

        val response: ObjectNode = objectMapper.createObjectNode()
            .put("id", "msg_${randomIdentifier()}")
            .put("type", "message")
            .put("role", "assistant")
            .put("model", publicModel)
        response.set<JsonNode>("content", content)
        response.put("stop_reason", stopReason)
        response.putNull("stop_sequence")
        response.set<JsonNode>("usage", usageJson(usageAccumulator))

        return TranslatedResponse(
            responseBody = objectMapper.writeValueAsString(response),
            usageAccumulator = usageAccumulator,
            stopReason = stopReason,
        )
    }

    private fun failure(message: String): Nothing =
        throw ApiError(org.springframework.http.HttpStatus.BAD_GATEWAY, "api_error", message)

    private fun thinkingBlock(text: String): ObjectNode =
        objectMapper.createObjectNode()
            .put("type", "thinking")
            .put("thinking", text)
            .put("signature", "")

    private fun textBlock(text: String): ObjectNode =
        objectMapper.createObjectNode().put("type", "text").put("text", text)

    private fun toolUseBlock(toolCall: JsonNode): ObjectNode {
        val function = toolCall.path("function")
        val input = runCatching { objectMapper.readTree(function.path("arguments").asText("")) }.getOrNull()
        if (input == null || !input.isObject) {
            logger.warn {
                "Failed to parse arguments of tool '${function.path("name").asText()}' - input is empty"
            }
        }
        val toolUseBlock = objectMapper.createObjectNode()
            .put("type", "tool_use")
            .put("id", toolCall.path("id").asText())
            .put("name", function.path("name").asText())
        toolUseBlock.set<JsonNode>(
            "input",
            input?.takeIf { it.isObject } ?: objectMapper.createObjectNode(),
        )
        return toolUseBlock
    }

    private fun usageJson(usageAccumulator: UsageAccumulator): ObjectNode =
        objectMapper.createObjectNode()
            .put("input_tokens", usageAccumulator.inputTokens)
            .put("output_tokens", usageAccumulator.outputTokens)
            .put("cache_creation_input_tokens", usageAccumulator.cacheCreationTokens)
            .put("cache_read_input_tokens", usageAccumulator.cacheReadTokens)

    companion object {
        internal fun mapFinishReason(finishReason: String): String = when (finishReason) {
            "tool_calls" -> "tool_use"
            "length" -> "max_tokens"
            "content_filter" -> "end_turn" // помечаем в логах выше по стеку
            else -> "end_turn"
        }

        internal fun randomIdentifier(): String {
            val bytes = ByteArray(12)
            java.security.SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
