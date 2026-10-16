package ru.wizard.web.claudeproxy.proxy.openai.impl

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import ru.wizard.web.claudeproxy.proxy.UsageAccumulator

/**
 * SSE state machine: чанки OpenAI Chat Completions → события Claude.
 * Вход — data-payload каждого события (WebClient разбирает SSE сам),
 * выход — готовые SSE-события Claude ("event: …\ndata: …\n\n").
 *
 * Блочная модель: text/thinking живут в одном «текущем» блоке (переключение
 * закрывает предыдущий), вызовы инструментов отслеживаются по OpenAI-индексу
 * и закрываются при завершении стрима.
 */
class OpenAiSseTranslator(
    private val objectMapper: ObjectMapper,
    private val publicModel: String,
) {
    /** Накопитель usage из SSE-чанков OpenAI. */
    val usageAccumulator = UsageAccumulator()

    private var messageStarted = false
    private var finished = false
    private var currentBlockIndex = -1
    private var currentBlockType: String? = null
    private val toolBlockIndexes = LinkedHashMap<Int, Int>()
    private var nextBlockIndex = 0
    private var pendingStopReason: String? = null

    /** Один data-payload SSE-события от провайдера → 0..n событий Claude. */
    /** Обрабатывает data-payload OpenAI; возвращает SSE-строки Claude. */
    fun onData(payload: String?): List<String> {
        val events = ArrayList<String>()
        if (payload == null) return events
        if (payload == "[DONE]") {
            finish(events)
            return events
        }
        val node = runCatching { objectMapper.readTree(payload) }.getOrNull() ?: return events
        val usageNode = node.path("usage")
        if (usageNode.isObject) {
            usageAccumulator.setValues(
                inputTokens = usageNode.path("prompt_tokens").asLong(usageAccumulator.inputTokens),
                outputTokens = usageNode.path("completion_tokens").asLong(usageAccumulator.outputTokens),
                cacheCreationTokens = 0,
                cacheReadTokens = usageNode.path("prompt_tokens_details")
                    .path("cached_tokens").asLong(usageAccumulator.cacheReadTokens),
            )
        }
        val choice = node.path("choices").firstOrNull() ?: return events
        if (!messageStarted) {
            events.add(messageStartEvent())
            messageStarted = true
        }

        val delta = choice.path("delta")

        val reasoning = delta.path("reasoning_content").takeIf { it.isTextual }
            ?: delta.path("reasoning").takeIf { it.isTextual }
        if (reasoning != null && reasoning.asText().isNotEmpty()) {
            ensureBlock("thinking", events)
            events.add(
                deltaEvent(
                    currentBlockIndex,
                    objectMapper.createObjectNode()
                        .put("type", "thinking_delta")
                        .put("thinking", reasoning.asText()),
                ),
            )
        }

        val text = delta.path("content")
        if (text.isTextual && text.asText().isNotEmpty()) {
            ensureBlock("text", events)
            events.add(
                deltaEvent(
                    currentBlockIndex,
                    objectMapper.createObjectNode()
                        .put("type", "text_delta")
                        .put("text", text.asText()),
                ),
            )
        }

        for (toolCall in delta.path("tool_calls")) {
            handleToolCall(toolCall, events)
        }

        val finishReason = choice.path("finish_reason")
        if (finishReason.isTextual) {
            pendingStopReason = OpenAiResponseTranslator.mapFinishReason(finishReason.asText())
        }
        return events
    }

    private fun handleToolCall(toolCall: JsonNode, events: MutableList<String>) {
        val openaiToolIndex = toolCall.path("index").asInt(0)
        val blockIndex = toolBlockIndexes[openaiToolIndex] ?: run {
            closeCurrentBlock(events)
            val newIndex = nextBlockIndex++
            toolBlockIndexes[openaiToolIndex] = newIndex
            currentBlockIndex = newIndex
            currentBlockType = "tool_use"
            val function = toolCall.path("function")
            val toolUseBlock = objectMapper.createObjectNode()
                .put("type", "tool_use")
                .put("id", toolCall.path("id").asText("toolu_${newIndex}"))
                .put("name", function.path("name").asText())
            toolUseBlock.set<JsonNode>("input", objectMapper.createObjectNode())
            events.add(blockStartEvent(newIndex, toolUseBlock))
            newIndex
        }
        val arguments = toolCall.path("function").path("arguments")
        if (arguments.isTextual && arguments.asText().isNotEmpty()) {
            events.add(
                deltaEvent(
                    blockIndex,
                    objectMapper.createObjectNode()
                        .put("type", "input_json_delta")
                        .put("partial_json", arguments.asText()),
                ),
            )
        }
    }

    private fun ensureBlock(blockType: String, events: MutableList<String>) {
        if (currentBlockType == blockType && currentBlockIndex >= 0) return
        closeCurrentBlock(events)
        currentBlockIndex = nextBlockIndex++
        currentBlockType = blockType
        val block: ObjectNode = if (blockType == "thinking") {
            objectMapper.createObjectNode().put("type", "thinking").put("thinking", "").put("signature", "")
        } else {
            objectMapper.createObjectNode().put("type", "text").put("text", "")
        }
        events.add(blockStartEvent(currentBlockIndex, block))
    }

    private fun closeCurrentBlock(events: MutableList<String>) {
        if (currentBlockIndex >= 0 && currentBlockType != null) {
            events.add(blockStopEvent(currentBlockIndex))
        }
        currentBlockIndex = -1
        currentBlockType = null
    }

    private fun finish(events: MutableList<String>) {
        if (finished) return
        finished = true
        if (!messageStarted) {
            events.add(messageStartEvent())
            messageStarted = true
        }
        closeCurrentBlock(events)
        for (blockIndex in toolBlockIndexes.values) {
            events.add(blockStopEvent(blockIndex))
        }
        val messageDeltaPayload = objectMapper.createObjectNode()
            .put("type", "message_delta")
        val stopPayload = objectMapper.createObjectNode()
            .put("stop_reason", pendingStopReason ?: "end_turn")
        stopPayload.putNull("stop_sequence")
        messageDeltaPayload.set<JsonNode>("delta", stopPayload)
        messageDeltaPayload.set<JsonNode>("usage", usageJson())
        events.add(sseEvent("message_delta", messageDeltaPayload))
        events.add(
            sseEvent(
                "message_stop",
                objectMapper.createObjectNode().put("type", "message_stop"),
            ),
        )
    }

    private fun messageStartEvent(): String {
        val message = objectMapper.createObjectNode()
            .put("id", "msg_${OpenAiResponseTranslator.randomIdentifier()}")
            .put("type", "message")
            .put("role", "assistant")
            .put("model", publicModel)
        message.putNull("stop_reason")
        message.putNull("stop_sequence")
        message.set<JsonNode>("content", objectMapper.createArrayNode())
        message.set<JsonNode>("usage", emptyUsageJson())
        val messageStartPayload = objectMapper.createObjectNode().put("type", "message_start")
        messageStartPayload.set<JsonNode>("message", message)
        return sseEvent("message_start", messageStartPayload)
    }

    private fun usageJson(): ObjectNode =
        objectMapper.createObjectNode()
            .put("input_tokens", usageAccumulator.inputTokens)
            .put("output_tokens", usageAccumulator.outputTokens)
            .put("cache_creation_input_tokens", usageAccumulator.cacheCreationTokens)
            .put("cache_read_input_tokens", usageAccumulator.cacheReadTokens)

    private fun emptyUsageJson(): ObjectNode =
        objectMapper.createObjectNode()
            .put("input_tokens", 0)
            .put("output_tokens", 0)
            .put("cache_creation_input_tokens", 0)
            .put("cache_read_input_tokens", 0)

    private fun blockStartEvent(index: Int, block: ObjectNode): String {
        val payload = objectMapper.createObjectNode()
            .put("type", "content_block_start")
            .put("index", index)
        payload.set<JsonNode>("content_block", block)
        return sseEvent("content_block_start", payload)
    }

    private fun deltaEvent(index: Int, delta: ObjectNode): String {
        val payload = objectMapper.createObjectNode()
            .put("type", "content_block_delta")
            .put("index", index)
        payload.set<JsonNode>("delta", delta)
        return sseEvent("content_block_delta", payload)
    }

    private fun blockStopEvent(index: Int): String =
        sseEvent(
            "content_block_stop",
            objectMapper.createObjectNode()
                .put("type", "content_block_stop")
                .put("index", index),
        )

    private fun sseEvent(eventName: String, payload: ObjectNode): String {
        payload.put("type", eventName)
        return "event: $eventName\ndata: ${objectMapper.writeValueAsString(payload)}\n\n"
    }
}
