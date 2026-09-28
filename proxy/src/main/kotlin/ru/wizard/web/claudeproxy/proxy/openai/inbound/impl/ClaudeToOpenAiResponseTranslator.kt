package ru.wizard.web.claudeproxy.proxy.openai.inbound.impl

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Перевод нестримового ответа Claude → ответ OpenAI Chat Completions.
 */
class ClaudeToOpenAiResponseTranslator(private val objectMapper: ObjectMapper) {

    fun translate(claudeRoot: JsonNode, publicModel: String): ObjectNode {
        val message = objectMapper.createObjectNode().put("role", "assistant")

        val textParts = ArrayList<String>()
        val toolCalls = objectMapper.createArrayNode()
        var reasoningText: String? = null
        for (block in claudeRoot.path("content")) {
            when (block.path("type").asText()) {
                "text" -> textParts.add(block.path("text").asText())
                "thinking" -> reasoningText = block.path("thinking").asText()
                "tool_use" -> {
                    val function = objectMapper.createObjectNode()
                        .put("name", block.path("name").asText())
                        .put("arguments", block.path("input").toString())
                    toolCalls.add(
                        objectMapper.createObjectNode()
                            .put("id", block.path("id").asText())
                            .put("type", "function")
                            .set<JsonNode>("function", function),
                    )
                }
            }
        }
        if (reasoningText != null && reasoningText.isNotEmpty()) {
            message.put("reasoning_content", reasoningText)
        }
        if (textParts.isNotEmpty()) {
            message.put("content", textParts.joinToString("\n\n"))
        }
        if (toolCalls.size() > 0) {
            message.set<JsonNode>("tool_calls", toolCalls)
        }

        val choice = objectMapper.createObjectNode()
            .put("index", 0)
            .set<ObjectNode>("message", message)
        choice.put("finish_reason", mapStopReason(claudeRoot.path("stop_reason").asText("")))

        val usageNode = claudeRoot.path("usage")
        val usage = objectMapper.createObjectNode()
            .put("prompt_tokens", usageNode.path("input_tokens").asLong(0))
            .put("completion_tokens", usageNode.path("output_tokens").asLong(0))
            .put(
                "total_tokens",
                usageNode.path("input_tokens").asLong(0) + usageNode.path("output_tokens").asLong(0),
            )
        usage.putObject("prompt_tokens_details")
            .put("cached_tokens", usageNode.path("cache_read_input_tokens").asLong(0))

        val response = objectMapper.createObjectNode()
            .put("id", claudeRoot.path("id").asText("msg_unknown"))
            .put("object", "chat.completion")
            .put("created", System.currentTimeMillis() / 1000)
            .put("model", publicModel)
        response.putArray("choices").add(choice)
        response.set<JsonNode>("usage", usage)
        return response
    }

    private fun mapStopReason(stopReason: String): String = when (stopReason) {
        "tool_use" -> "tool_calls"
        "max_tokens" -> "length"
        else -> "stop"
    }
}
