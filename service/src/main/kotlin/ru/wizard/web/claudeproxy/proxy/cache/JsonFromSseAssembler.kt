package ru.wizard.web.claudeproxy.proxy.cache

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Сборка JSON-ответа Claude Messages из сохранённого SSE-транскрипта кэша
 * повторяющихся запросов: когда стримовый проход записан в кэш, а повтор
 * пришёл без stream. Склеивает delta-события в цельные блоки контента.
 */
internal object JsonFromSseAssembler {

    private val objectMapper = ObjectMapper()

    /** Полный JSON ответа (message) по событиям message_start/…/message_stop. */
    fun assemble(sseTranscript: String): String {
        val blocks = HashMap<Int, ObjectNode>()
        val blocksOrder = ArrayList<Int>()
        var id = ""
        var model = ""
        var role = "assistant"
        var stopReason: String? = null
        var stopSequence: JsonNode = objectMapper.nullNode()
        var inputTokens = 0L
        var outputTokens = 0L
        for (eventBlock in sseTranscript.split("\n\n")) {
            val dataLines = eventBlock.lines().filter { it.startsWith("data:") }
            if (dataLines.isEmpty()) continue
            val payload = runCatching {
                objectMapper.readTree(dataLines.joinToString("\n") { it.removePrefix("data:").trim() })
            }.getOrNull() ?: continue
            when (payload.path("type").asText()) {
                "message_start" -> {
                    val message = payload.path("message")
                    id = message.path("id").asText()
                    model = message.path("model").asText()
                    role = message.path("role").asText("assistant")
                    inputTokens = message.path("usage").path("input_tokens").asLong(0)
                }
                "content_block_start" -> {
                    val index = payload.path("index").asInt()
                    blocks[index] = payload.path("content_block").deepCopy<ObjectNode>()
                    blocksOrder += index
                }
                "content_block_delta" -> {
                    val index = payload.path("index").asInt()
                    val delta = payload.path("delta")
                    val block = blocks[index] ?: createBlockForDelta(delta) ?: continue
                    if (!blocks.containsKey(index)) {
                        blocks[index] = block
                        blocksOrder += index
                    }
                    applyDelta(block, delta)
                }
                "message_delta" -> {
                    stopReason = payload.path("delta").path("stop_reason").asText(null)
                    outputTokens = payload.path("usage").path("output_tokens").asLong(0)
                }
                else -> Unit
            }
        }
        val content: ArrayNode = objectMapper.createArrayNode()
        for (index in blocksOrder.sorted()) {
            blocks[index]?.let { content.add(it) }
        }
        val response = objectMapper.createObjectNode()
        response.put("id", id)
        response.put("type", "message")
        response.put("role", role)
        response.put("model", model)
        response.set<JsonNode>("content", content)
        response.put("stop_reason", stopReason)
        response.set<JsonNode>("stop_sequence", stopSequence)
        val usage = objectMapper.createObjectNode()
        usage.put("input_tokens", inputTokens)
        usage.put("output_tokens", outputTokens)
        response.set<JsonNode>("usage", usage)
        return response.toString()
    }

    /** Блок по delta-типу, если content_block_start отсутствовал (минимальные стримы). */
    private fun createBlockForDelta(delta: JsonNode): ObjectNode? =
        when (delta.path("type").asText()) {
            "text_delta" -> objectMapper.createObjectNode().apply {
                put("type", "text")
                put("text", "")
            }
            "thinking_delta" -> objectMapper.createObjectNode().apply {
                put("type", "thinking")
                put("thinking", "")
            }
            // tool_use без content_block_start восстановить нельзя — id и имя неизвестны
            else -> null
        }

    /** Дописывает delta в блок контента: text/thinking конкатенируются, tool_use копит JSON. */
    private fun applyDelta(block: ObjectNode, delta: JsonNode) {
        when (delta.path("type").asText()) {
            "text_delta" -> block.put("text", block.path("text").asText("") + delta.path("text").asText(""))
            "thinking_delta" -> block.put(
                "thinking",
                block.path("thinking").asText("") + delta.path("thinking").asText(""),
            )
            "input_json_delta" -> {
                val accumulatedJson = StringBuilder(block.path("partial_json").asText(""))
                accumulatedJson.append(delta.path("partial_json").asText(""))
                block.put("partial_json", accumulatedJson.toString())
                runCatching { objectMapper.readTree(accumulatedJson.toString()) }.getOrNull()?.let { parsedInput ->
                    block.set<JsonNode>("input", parsedInput)
                }
                block.remove("partial_json")
            }
            else -> Unit
        }
    }
}
