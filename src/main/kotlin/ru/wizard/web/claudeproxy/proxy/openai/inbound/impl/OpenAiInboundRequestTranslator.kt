package ru.wizard.web.claudeproxy.proxy.openai.inbound.impl

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Перевод входящего запроса OpenAI Chat Completions → запрос Claude
 * (внутреннее представление прокси; дальше работают существующие хендлеры).
 */
class OpenAiInboundRequestTranslator(private val objectMapper: ObjectMapper) {

    fun translate(openAiRoot: JsonNode): ObjectNode {
        val target = objectMapper.createObjectNode()
        target.put("model", openAiRoot.path("model").asText(""))

        val systemTexts = ArrayList<String>()
        val conversationMessages = ArrayList<JsonNode>()
        for (message in openAiRoot.path("messages")) {
            if (message.path("role").asText() == "system" || message.path("role").asText() == "developer") {
                systemTexts.add(textOf(message.path("content")))
            } else {
                conversationMessages.add(message)
            }
        }
        if (systemTexts.any { it.isNotBlank() }) {
            target.put("system", systemTexts.filter { it.isNotBlank() }.joinToString("\n\n"))
        }

        val messages = objectMapper.createArrayNode()
        var index = 0
        while (index < conversationMessages.size) {
            val message = conversationMessages[index]
            if (message.path("role").asText() == "tool") {
                // подряд идущие tool-сообщения → один user с tool_result-блоками
                val toolResults = objectMapper.createArrayNode()
                while (index < conversationMessages.size &&
                    conversationMessages[index].path("role").asText() == "tool"
                ) {
                    val toolMessage = conversationMessages[index]
                    val toolResult = objectMapper.createObjectNode()
                        .put("type", "tool_result")
                        .put("tool_use_id", toolMessage.path("tool_call_id").asText())
                    toolResult.set<JsonNode>("content", translateToolContent(toolMessage.path("content")))
                    toolResults.add(toolResult)
                    index++
                }
                val toolUserMessage = objectMapper.createObjectNode().put("role", "user")
                toolUserMessage.set<JsonNode>("content", toolResults)
                messages.add(toolUserMessage)
            } else {
                messages.add(translateMessage(message))
                index++
            }
        }
        target.set<JsonNode>("messages", messages)

        val maxTokens = openAiRoot.path("max_completion_tokens").asLong(0)
            .takeIf { it > 0 }
            ?: openAiRoot.path("max_tokens").asLong(0).takeIf { it > 0 }
            ?: DEFAULT_MAX_TOKENS
        target.put("max_tokens", maxTokens)
        if (openAiRoot.hasNonNull("temperature")) {
            target.set<JsonNode>("temperature", openAiRoot.get("temperature"))
        }
        if (openAiRoot.hasNonNull("top_p")) {
            target.set<JsonNode>("top_p", openAiRoot.get("top_p"))
        }
        val stop = openAiRoot.path("stop")
        if (stop.isTextual && stop.asText().isNotEmpty()) {
            target.set<JsonNode>("stop_sequences", objectMapper.createArrayNode().add(stop.asText()))
        } else if (stop.isArray && stop.size() > 0) {
            target.set<JsonNode>("stop_sequences", stop)
        }
        translateTools(openAiRoot, target)
        if (openAiRoot.path("stream").asBoolean(false)) {
            target.put("stream", true)
        }
        val reasoningEffort = openAiRoot.path("reasoning_effort").takeIf { it.isTextual }
        if (reasoningEffort != null) {
            target.putObject("thinking").put("type", "adaptive")
            target.putObject("output_config").put("effort", reasoningEffort.asText())
        }
        return target
    }

    private fun translateMessage(message: JsonNode): ObjectNode {
        val role = message.path("role").asText("user")
        val content = message.get("content")
        return if (role == "assistant") {
            translateAssistantMessage(content, message.path("tool_calls"))
        } else {
            val node = objectMapper.createObjectNode().put("role", "user")
            if (content == null || content.isNull) {
                node.put("content", "")
            } else if (content.isTextual) {
                node.put("content", content.asText())
            } else {
                node.set<JsonNode>("content", translateContentParts(content))
            }
            node
        }
    }

    private fun translateAssistantMessage(content: JsonNode?, toolCalls: JsonNode): ObjectNode {
        val blocks = objectMapper.createArrayNode()
        if (content != null && content.isTextual && content.asText().isNotEmpty()) {
            blocks.add(textBlock(content.asText()))
        } else if (content != null && content.isArray) {
            for (part in content) {
                if (part.path("type").asText("text") == "text" && part.hasNonNull("text")) {
                    blocks.add(textBlock(part.path("text").asText()))
                }
            }
        }
        for (toolCall in toolCalls) {
            val function = toolCall.path("function")
            val input = runCatching {
                objectMapper.readTree(function.path("arguments").asText(""))
            }.getOrNull()
            val toolUse = objectMapper.createObjectNode()
                .put("type", "tool_use")
                .put("id", toolCall.path("id").asText())
                .put("name", function.path("name").asText())
            toolUse.set<JsonNode>("input", input?.takeIf { it.isObject } ?: objectMapper.createObjectNode())
            blocks.add(toolUse)
        }
        if (blocks.size() == 0) {
            blocks.add(textBlock(""))
        }
        val node = objectMapper.createObjectNode().put("role", "assistant")
        node.set<JsonNode>("content", blocks)
        return node
    }

    private fun translateToolContent(content: JsonNode?): JsonNode {
        if (content == null || content.isNull) {
            return objectMapper.getNodeFactory().textNode("")
        }
        if (content.isTextual) {
            return content
        }
        return translateContentParts(content)
    }

    private fun translateContentParts(content: JsonNode): ArrayNode {
        val blocks = objectMapper.createArrayNode()
        for (part in content) {
            when (part.path("type").asText()) {
                "text" -> blocks.add(textBlock(part.path("text").asText()))
                "image_url" -> translateImageSource(part.path("image_url").path("url").asText())
                    ?.let { blocks.add(it) }
            }
        }
        return blocks
    }

    private fun translateImageSource(url: String): ObjectNode? {
        if (url.isEmpty()) return null
        val imageBlock = objectMapper.createObjectNode().put("type", "image")
        val source: ObjectNode = if (url.startsWith("data:")) {
            // data:<mime>;base64,<data> → base64-источник Claude
            val headerEnd = url.indexOf(',', startIndex = 5)
            if (headerEnd < 0) return null
            val mimeType = url.substring(5, headerEnd).substringBefore(';')
            val base64Data = url.substring(headerEnd + 1)
            objectMapper.createObjectNode()
                .put("type", "base64")
                .put("media_type", if (mimeType.isEmpty()) "image/png" else mimeType)
                .put("data", base64Data)
        } else {
            objectMapper.createObjectNode().put("type", "url").put("url", url)
        }
        imageBlock.set<JsonNode>("source", source)
        return imageBlock
    }

    private fun translateTools(openAiRoot: JsonNode, target: ObjectNode) {
        val tools = openAiRoot.path("tools")
        if (!tools.isArray || tools.size() == 0) return
        val claudeTools = objectMapper.createArrayNode()
        for (tool in tools) {
            val function = tool.path("function")
            val claudeTool = objectMapper.createObjectNode().put("name", function.path("name").asText())
            if (function.hasNonNull("description")) {
                claudeTool.put("description", function.path("description").asText())
            }
            claudeTool.set<JsonNode>("input_schema", function.path("parameters"))
            claudeTools.add(claudeTool)
        }
        target.set<JsonNode>("tools", claudeTools)

        val toolChoice = openAiRoot.path("tool_choice")
        when {
            toolChoice.isTextual -> when (toolChoice.asText()) {
                "auto" -> target.putObject("tool_choice").put("type", "auto")
                "required" -> target.putObject("tool_choice").put("type", "any")
                "none" -> target.putObject("tool_choice").put("type", "none")
            }

            toolChoice.isObject && toolChoice.path("type").asText() == "function" -> {
                target.putObject("tool_choice")
                    .put("type", "tool")
                    .put("name", toolChoice.path("function").path("name").asText())
            }
        }
    }

    private fun textBlock(text: String): ObjectNode =
        objectMapper.createObjectNode().put("type", "text").put("text", text)

    private fun textOf(content: JsonNode): String = when {
        content.isTextual -> content.asText()
        content.isArray -> content
            .filter { it.path("type").asText("text") == "text" }
            .joinToString("\n\n") { it.path("text").asText() }

        else -> ""
    }

    private companion object {
        const val DEFAULT_MAX_TOKENS = 8192L
    }
}
