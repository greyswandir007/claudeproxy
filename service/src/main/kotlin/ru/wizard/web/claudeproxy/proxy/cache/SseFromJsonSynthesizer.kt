package ru.wizard.web.claudeproxy.proxy.cache

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Синтез SSE-потока событий Claude Messages из сохранённого JSON-ответа кэша
 * повторяющихся запросов: когда не-стримовый проход записан в кэш, а повтор
 * пришёл со stream=true. Каждый блок контента отдаётся одним delta-событием.
 */
internal object SseFromJsonSynthesizer {

    private val objectMapper = ObjectMapper()

    /** Готовый SSE-транскрипт (event:/data: строки, события разделены \n\n). */
    fun synthesize(responseJson: String): String {
        val response = objectMapper.readTree(responseJson)
        val events = ArrayList<String>()
        val message = objectMapper.createObjectNode()
        message.put("type", "message")
        message.put("id", response.path("id").asText())
        message.put("role", response.path("role").asText("assistant"))
        message.put("model", response.path("model").asText())
        message.set<JsonNode>("content", objectMapper.createArrayNode())
        message.put("stop_reason", null as String?)
        message.put("stop_sequence", null as String?)
        val startUsage = objectMapper.createObjectNode()
        startUsage.put("input_tokens", response.path("usage").path("input_tokens").asLong(0))
        val cacheReadTokens = response.path("usage").path("cache_read_input_tokens").asLong(0)
        if (cacheReadTokens > 0) {
            startUsage.put("cache_read_input_tokens", cacheReadTokens)
        }
        val cacheCreationTokens = response.path("usage").path("cache_creation_input_tokens").asLong(0)
        if (cacheCreationTokens > 0) {
            startUsage.put("cache_creation_input_tokens", cacheCreationTokens)
        }
        startUsage.put("output_tokens", 0)
        message.set<JsonNode>("usage", startUsage)
        events += event("message_start", objectMapper.createObjectNode().apply {
            put("type", "message_start")
            set<JsonNode>("message", message)
        })

        val content = response.path("content")
        var index = 0
        for (block in content) {
            events += contentBlockStart(index, block)
            events += contentBlockDelta(index, block)
            events += event("content_block_stop", objectMapper.createObjectNode().apply {
                put("type", "content_block_stop")
                put("index", index)
            })
            index += 1
        }

        events += event("message_delta", objectMapper.createObjectNode().apply {
            put("type", "message_delta")
            set<JsonNode>(
                "delta",
                objectMapper.createObjectNode().apply {
                    put("stop_reason", response.path("stop_reason").asText("end_turn"))
                    put("stop_sequence", null as String?)
                },
            )
            set<JsonNode>(
                "usage",
                objectMapper.createObjectNode().apply {
                    put("output_tokens", response.path("usage").path("output_tokens").asLong(0))
                },
            )
        })
        events += event("message_stop", objectMapper.createObjectNode().apply {
            put("type", "message_stop")
        })
        return events.joinToString(separator = "\n\n", postfix = "\n\n")
    }

    private fun contentBlockStart(index: Int, block: JsonNode): String =
        event(
            "content_block_start",
            objectMapper.createObjectNode().apply {
                put("type", "content_block_start")
                put("index", index)
                when (block.path("type").asText()) {
                    "tool_use" -> set<JsonNode>(
                        "content_block",
                        objectMapper.createObjectNode().apply {
                            put("type", "tool_use")
                            put("id", block.path("id").asText(""))
                            put("name", block.path("name").asText(""))
                            set<JsonNode>("input", objectMapper.createObjectNode())
                        },
                    )
                    "thinking" -> set<JsonNode>(
                        "content_block",
                        objectMapper.createObjectNode().apply {
                            put("type", "thinking")
                            put("thinking", "")
                        },
                    )
                    else -> set<JsonNode>(
                        "content_block",
                        objectMapper.createObjectNode().apply {
                            put("type", "text")
                            put("text", "")
                        },
                    )
                }
            },
        )

    private fun contentBlockDelta(index: Int, block: JsonNode): String =
        event(
            "content_block_delta",
            objectMapper.createObjectNode().apply {
                put("type", "content_block_delta")
                put("index", index)
                when (block.path("type").asText()) {
                    "tool_use" -> set<JsonNode>(
                        "delta",
                        objectMapper.createObjectNode().apply {
                            put("type", "input_json_delta")
                            put("partial_json", block.path("input").toString())
                        },
                    )
                    "thinking" -> set<JsonNode>(
                        "delta",
                        objectMapper.createObjectNode().apply {
                            put("type", "thinking_delta")
                            put("thinking", block.path("thinking").asText(""))
                        },
                    )
                    else -> set<JsonNode>(
                        "delta",
                        objectMapper.createObjectNode().apply {
                            put("type", "text_delta")
                            put("text", block.path("text").asText(""))
                        },
                    )
                }
            },
        )

    private fun event(name: String, payload: ObjectNode): String =
        "event: $name\ndata: ${payload.toString()}"
}
