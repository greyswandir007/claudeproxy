package ru.wizard.web.claudeproxy.proxy.openai.inbound.impl

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import ru.wizard.web.claudeproxy.proxy.openai.impl.OpenAiResponseTranslator
import java.nio.charset.StandardCharsets.UTF_8

/**
 * SSE state machine: события Claude (от хендлеров прокси) → чанки OpenAI
 * chat.completion.chunk + финальный "data: [DONE]".
 * Вход — сырые чанки текста (строки могут разрываться, буферизуем до "\n").
 */
class ClaudeSseToOpenAiSseTranslator(
    private val objectMapper: ObjectMapper,
    private val publicModel: String,
) {
    private val pendingLine = StringBuilder()
    private val chunkIdentifier = "chatcmpl-${OpenAiResponseTranslator.randomIdentifier()}"
    private val createdSeconds = System.currentTimeMillis() / 1000
    private val toolIndexByBlockIndex = HashMap<Int, Int>()
    private var nextToolIndex = 0
    private var finishReason: String? = null
    private var usageNode: ObjectNode? = null
    private var finished = false

    /** Обрабатывает кусок Claude-SSE; возвращает OpenAI-чанки. */
    fun onChunk(text: String): List<String> {
        val events = ArrayList<String>()
        pendingLine.append(text)
        while (true) {
            val newlineIndex = pendingLine.indexOf('\n')
            if (newlineIndex < 0) break
            val line = pendingLine.substring(0, newlineIndex).trimEnd('\r')
            pendingLine.delete(0, newlineIndex + 1)
            handleLine(line, events)
        }
        return events
    }

    private fun handleLine(line: String, events: MutableList<String>) {
        if (!line.startsWith("data:")) return
        val payload = line.removePrefix("data:").trim()
        if (!payload.startsWith("{")) return
        val node = runCatching { objectMapper.readTree(payload) }.getOrNull() ?: return
        when (node.path("type").asText()) {
            "message_start" -> events.add(
                chunkEvent(
                    objectMapper.createObjectNode()
                        .put("role", "assistant"),
                    finishReason = null,
                ),
            )

            "content_block_start" -> {
                val blockIndex = node.path("index").asInt()
                val block = node.path("content_block")
                if (block.path("type").asText() == "tool_use") {
                    val toolIndex = nextToolIndex++
                    toolIndexByBlockIndex[blockIndex] = toolIndex
                    val function = objectMapper.createObjectNode()
                        .put("name", block.path("name").asText())
                        .put("arguments", "")
                    val toolCall = objectMapper.createObjectNode()
                        .put("index", toolIndex)
                        .put("id", block.path("id").asText())
                        .put("type", "function")
                    toolCall.set<JsonNode>("function", function)
                    val delta = objectMapper.createObjectNode()
                    delta.putArray("tool_calls").add(toolCall)
                    events.add(chunkEvent(delta, finishReason = null))
                }
            }

            "content_block_delta" -> {
                val blockIndex = node.path("index").asInt()
                val delta = node.path("delta")
                when (delta.path("type").asText()) {
                    "text_delta" -> events.add(
                        chunkEvent(
                            objectMapper.createObjectNode().put("content", delta.path("text").asText()),
                            finishReason = null,
                        ),
                    )

                    "thinking_delta" -> events.add(
                        chunkEvent(
                            objectMapper.createObjectNode().put("reasoning_content", delta.path("thinking").asText()),
                            finishReason = null,
                        ),
                    )

                    "input_json_delta" -> {
                        val toolIndex = toolIndexByBlockIndex[blockIndex] ?: return
                        val function = objectMapper.createObjectNode()
                            .put("arguments", delta.path("partial_json").asText())
                        val toolCall = objectMapper.createObjectNode()
                            .put("index", toolIndex)
                        toolCall.set<JsonNode>("function", function)
                        val deltaPayload = objectMapper.createObjectNode()
                        deltaPayload.putArray("tool_calls").add(toolCall)
                        events.add(chunkEvent(deltaPayload, finishReason = null))
                    }
                }
            }

            "message_delta" -> {
                node.path("delta").path("stop_reason").takeIf { it.isTextual }?.let {
                    finishReason = mapStopReason(it.asText())
                }
                node.path("usage").takeIf { it.isObject }?.let { usage ->
                    val usagePayload = objectMapper.createObjectNode()
                        .put("prompt_tokens", usage.path("input_tokens").asLong(0))
                        .put("completion_tokens", usage.path("output_tokens").asLong(0))
                        .put(
                            "total_tokens",
                            usage.path("input_tokens").asLong(0) + usage.path("output_tokens").asLong(0),
                        )
                    usagePayload.putObject("prompt_tokens_details")
                        .put("cached_tokens", usage.path("cache_read_input_tokens").asLong(0))
                    usageNode = usagePayload
                }
            }

            "message_stop" -> finish(events)

            "error" -> {
                val errorPayload = objectMapper.createObjectNode()
                errorPayload.set<JsonNode>(
                    "error",
                    node.path("error").takeIf { it.isObject }
                        ?: objectMapper.createObjectNode().put("message", "upstream error"),
                )
                events.add(sseData(errorPayload))
                events.add(DONE_MARKER)
                finished = true
            }

            "ping" -> Unit
        }
    }

    private fun finish(events: MutableList<String>) {
        if (finished) return
        finished = true
        events.add(chunkEvent(objectMapper.createObjectNode(), finishReason = finishReason ?: "stop"))
        if (usageNode != null) {
            val usageOnly = objectMapper.createObjectNode()
                .put("id", chunkIdentifier)
                .put("object", "chat.completion.chunk")
                .put("created", createdSeconds)
                .put("model", publicModel)
            usageOnly.putArray("choices")
            usageOnly.set<JsonNode>("usage", usageNode)
            events.add(sseData(usageOnly))
        }
        events.add(DONE_MARKER)
    }

    private fun chunkEvent(delta: ObjectNode, finishReason: String?): String {
        val choice = objectMapper.createObjectNode()
            .put("index", 0)
            .set<ObjectNode>("delta", delta)
        choice.put("finish_reason", finishReason)
        val chunk = objectMapper.createObjectNode()
            .put("id", chunkIdentifier)
            .put("object", "chat.completion.chunk")
            .put("created", createdSeconds)
            .put("model", publicModel)
        chunk.putArray("choices").add(choice)
        return sseData(chunk)
    }

    private fun sseData(payload: ObjectNode): String =
        "data: ${objectMapper.writeValueAsString(payload)}\n\n"

    private fun mapStopReason(stopReason: String): String = when (stopReason) {
        "tool_use" -> "tool_calls"
        "max_tokens" -> "length"
        else -> "stop"
    }

    private companion object {
        const val DONE_MARKER = "data: [DONE]\n\n"

    }
}
