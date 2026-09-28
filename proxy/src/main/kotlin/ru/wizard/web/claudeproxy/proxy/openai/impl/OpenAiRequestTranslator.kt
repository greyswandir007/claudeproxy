package ru.wizard.web.claudeproxy.proxy.openai.impl

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpStatus
import ru.wizard.web.claudeproxy.proxy.ApiError
import ru.wizard.web.claudeproxy.routing.ModelRegistry

/**
 * Перевод запроса Claude → OpenAI Chat Completions (JsonNode → JsonNode).
 * Непереводимые поля отбрасываются с логом (политика log-and-drop);
 * серверные инструменты Claude — ошибка 400.
 */
class OpenAiRequestTranslator(private val objectMapper: ObjectMapper) {
    private val logger = KotlinLogging.logger {}

    fun translate(requestRoot: JsonNode, route: ModelRegistry.Route): ObjectNode {
        val mapping = route.mapping
        val target = objectMapper.createObjectNode()
        target.put("model", mapping.upstreamName)

        val messages = objectMapper.createArrayNode()
        translateSystemPrompt(requestRoot.path("system"))?.let { messages.add(it) }
        for (message in requestRoot.path("messages")) {
            translateMessage(message).forEach { messages.add(it) }
        }
        target.set<JsonNode>("messages", messages)

        translateLimits(requestRoot, target, mapping)
        translateSampling(requestRoot, target)
        translateTools(requestRoot, target)
        translateThinking(requestRoot, target, mapping)

        if (requestRoot.path("stream").asBoolean(false)) {
            target.put("stream", true)
            target.putObject("stream_options").put("include_usage", true)
        }
        return target
    }

    private fun translateSystemPrompt(systemNode: JsonNode): ObjectNode? {
        val text = when {
            systemNode.isMissingNode || systemNode.isNull -> return null
            systemNode.isTextual -> systemNode.asText()
            systemNode.isArray -> systemNode
                .filter { it.path("type").asText("text") == "text" }
                .joinToString("\n\n") { it.path("text").asText() }
            else -> return null
        }
        if (text.isBlank()) return null
        return objectMapper.createObjectNode().put("role", "system").put("content", text)
    }

    private fun translateMessage(message: JsonNode): List<ObjectNode> =
        if (message.path("role").asText("user") == "assistant") {
            listOf(translateAssistantMessage(message.path("content")))
        } else {
            translateUserMessage(message.path("content"))
        }

    private fun translateAssistantMessage(content: JsonNode): ObjectNode {
        val node = objectMapper.createObjectNode().put("role", "assistant")
        if (content.isTextual) {
            node.put("content", content.asText())
            return node
        }
        val textBuilder = StringBuilder()
        val toolCalls = objectMapper.createArrayNode()
        for (block in content) {
            when (block.path("type").asText()) {
                "text" -> {
                    if (textBuilder.isNotEmpty()) textBuilder.append("\n\n")
                    textBuilder.append(block.path("text").asText())
                }

                "tool_use" -> {
                    val toolCall = objectMapper.createObjectNode()
                        .put("id", block.path("id").asText())
                        .put("type", "function")
                    val function = toolCall.putObject("function")
                    function.put("name", block.path("name").asText())
                    function.put("arguments", block.path("input").toString())
                    toolCalls.add(toolCall)
                }

                "thinking" -> Unit // история рассуждений не ретранслируется
                else -> logger.debug { "Assistant block '${block.path("type").asText()}' dropped" }
            }
        }
        if (textBuilder.isNotEmpty()) {
            node.put("content", textBuilder.toString())
        } else {
            node.putNull("content")
        }
        if (toolCalls.size() > 0) {
            node.set<JsonNode>("tool_calls", toolCalls)
        }
        return node
    }

    private fun translateUserMessage(content: JsonNode): List<ObjectNode> {
        if (content.isTextual || content.isMissingNode || content.isNull) {
            val text = if (content.isTextual) content.asText() else ""
            return listOf(objectMapper.createObjectNode().put("role", "user").put("content", text))
        }
        val resultMessages = ArrayList<ObjectNode>()
        val parts = objectMapper.createArrayNode()
        for (block in content) {
            when (block.path("type").asText()) {
                "tool_result" -> resultMessages.add(translateToolResult(block))

                "text" -> parts.addObject()
                    .put("type", "text")
                    .put("text", block.path("text").asText())

                "image" -> translateImagePart(block)?.let { parts.add(it) }

                else -> logger.debug {
                    "User block '${block.path("type").asText()}' dropped (not translatable to OpenAI)"
                }
            }
        }
        if (parts.size() > 0) {
            val userMessage = objectMapper.createObjectNode().put("role", "user")
            userMessage.set<JsonNode>("content", parts)
            resultMessages.add(userMessage)
        }
        return resultMessages
    }

    private fun translateToolResult(block: JsonNode): ObjectNode {
        val isError = block.path("is_error").asBoolean(false)
        val node = objectMapper.createObjectNode()
            .put("role", "tool")
            .put("tool_call_id", block.path("tool_use_id").asText())
        val content = block.get("content")
        when {
            content == null || content.isNull -> node.put("content", "")

            content.isTextual -> node.put(
                "content",
                if (isError) "[ERROR] ${content.asText()}" else content.asText(),
            )

            content.isArray -> {
                val parts = objectMapper.createArrayNode()
                if (isError) {
                    parts.addObject().put("type", "text").put("text", "[ERROR]")
                }
                for (innerBlock in content) {
                    when (innerBlock.path("type").asText()) {
                        "text" -> parts.addObject()
                            .put("type", "text")
                            .put("text", innerBlock.path("text").asText())

                        "image" -> translateImagePart(innerBlock)?.let { parts.add(it) }
                    }
                }
                node.set<JsonNode>("content", parts)
            }
        }
        return node
    }

    private fun translateImagePart(block: JsonNode): ObjectNode? {
        val source = block.path("source")
        val url = when (source.path("type").asText()) {
            "base64" -> "data:${source.path("media_type").asText("image/png")};base64," +
                source.path("data").asText()

            "url" -> source.path("url").asText()
            else -> {
                logger.debug { "Image source '${source.path("type").asText()}' dropped" }
                return null
            }
        }
        val imagePart = objectMapper.createObjectNode().put("type", "image_url")
        imagePart.set<JsonNode>("image_url", objectMapper.createObjectNode().put("url", url))
        return imagePart
    }

    private fun translateLimits(
        requestRoot: JsonNode,
        target: ObjectNode,
        mapping: ModelRegistry.ModelInfo,
    ) {
        val maxTokens = requestRoot.path("max_tokens").asLong(0)
        if (maxTokens > 0) {
            if (mapping.maxCompletionParam) {
                target.put("max_completion_tokens", maxTokens)
            } else {
                target.put("max_tokens", maxTokens)
            }
        }
        requestRoot.path("metadata").path("user_id").takeIf { it.isTextual }?.let {
            target.put("user", it.asText())
        }
    }

    private fun translateSampling(requestRoot: JsonNode, target: ObjectNode) {
        if (requestRoot.hasNonNull("temperature")) {
            target.set<JsonNode>("temperature", requestRoot.get("temperature"))
        }
        if (requestRoot.hasNonNull("top_p")) {
            target.set<JsonNode>("top_p", requestRoot.get("top_p"))
        }
        val stopSequences = requestRoot.path("stop_sequences")
        if (stopSequences.isArray && stopSequences.size() > 0) {
            target.set<JsonNode>("stop", stopSequences)
        }
        if (requestRoot.has("top_k")) {
            logger.debug { "top_k is not supported by OpenAI - dropped" }
        }
    }

    private fun translateTools(requestRoot: JsonNode, target: ObjectNode) {
        val tools = requestRoot.path("tools")
        if (!tools.isArray || tools.size() == 0) return
        val translatedTools = objectMapper.createArrayNode()
        for (tool in tools) {
            val toolType = tool.path("type").asText("custom")
            if (toolType != "custom") {
                val name = tool.path("name").asText(toolType)
                throw ApiError(
                    HttpStatus.BAD_REQUEST,
                    "invalid_request_error",
                    "Инструмент '$name' (тип '$toolType') — серверный инструмент Claude, " +
                        "он не переводится для openai-провайдера",
                )
            }
            val function = objectMapper.createObjectNode().put("name", tool.path("name").asText())
            if (tool.hasNonNull("description")) {
                function.put("description", tool.path("description").asText())
            }
            function.set<JsonNode>("parameters", tool.path("input_schema"))
            if (tool.has("strict") && tool.path("strict").isBoolean) {
                function.set<JsonNode>("strict", tool.path("strict"))
            }
            val translatedTool = objectMapper.createObjectNode().put("type", "function")
            translatedTool.set<JsonNode>("function", function)
            translatedTools.add(translatedTool)
        }
        target.set<JsonNode>("tools", translatedTools)

        val toolChoice = requestRoot.path("tool_choice")
        if (toolChoice.isObject) {
            when (toolChoice.path("type").asText()) {
                "auto" -> target.put("tool_choice", "auto")
                "any" -> target.put("tool_choice", "required")
                "none" -> target.put("tool_choice", "none")
                "tool" -> target.putObject("tool_choice").apply {
                    put("type", "function")
                    putObject("function").put("name", toolChoice.path("name").asText())
                }
            }
            if (toolChoice.path("disable_parallel_tool_use").asBoolean(false)) {
                target.put("parallel_tool_calls", false)
            }
        }
    }

    private fun translateThinking(
        requestRoot: JsonNode,
        target: ObjectNode,
        mapping: ModelRegistry.ModelInfo,
    ) {
        if (mapping.reasoning != "map") return
        val thinking = requestRoot.path("thinking")
        if (thinking.isMissingNode || thinking.isNull) return
        if (thinking.path("type").asText() == "disabled") return
        val effort = requestRoot.path("output_config").path("effort").asText("high")
        target.put("reasoning_effort", mapEffort(effort))
    }

    private fun mapEffort(claudeEffort: String): String = when (claudeEffort) {
        "low" -> "low"
        "medium" -> "medium"
        else -> "high" // high, xhigh, max → high (верхняя грань OpenAI)
    }
}
