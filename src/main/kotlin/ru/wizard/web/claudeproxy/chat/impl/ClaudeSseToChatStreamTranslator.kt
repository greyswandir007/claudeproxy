package ru.wizard.web.claudeproxy.chat.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * SSE Claude → NDJSON-строки для браузера: {"type":"text","text":…} по дельтам,
 * {"type":"done","stop_reason":…} в конце, {"type":"error","message":…} при обрыве.
 * Накапливает полный текст ассистента для сохранения в историю.
 */
class ClaudeSseToChatStreamTranslator(private val objectMapper: ObjectMapper) {

    private val pendingLine = StringBuilder()
    val assistantText = StringBuilder()
    var stopReason: String? = null
        private set

    /** Один чанк SSE-потока → 0..n NDJSON-строк (без завершающего \n). */
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

    fun errorMessage(message: String?): String =
        objectMapper.writeValueAsString(
            mapOf("type" to "error", "message" to (message ?: "stream failed")),
        )

    private fun handleLine(line: String, events: MutableList<String>) {
        if (!line.startsWith("data:")) return
        val payload = line.removePrefix("data:").trim()
        if (!payload.startsWith("{")) return
        val node = runCatching { objectMapper.readTree(payload) }.getOrNull() ?: return
        when (node.path("type").asText()) {
            "content_block_delta" -> {
                val delta = node.path("delta")
                if (delta.path("type").asText() == "text_delta") {
                    val text = delta.path("text").asText()
                    assistantText.append(text)
                    events.add(textEvent(text))
                }
            }

            "message_delta" -> {
                node.path("delta").path("stop_reason").takeIf { it.isTextual }
                    ?.let { stopReason = it.asText() }
            }

            "message_stop" -> events.add(
                objectMapper.writeValueAsString(
                    mapOf("type" to "done", "stop_reason" to (stopReason ?: "end_turn")),
                ),
            )

            "error" -> events.add(
                objectMapper.writeValueAsString(
                    mapOf(
                        "type" to "error",
                        "message" to node.path("error").path("message").asText("upstream error"),
                    ),
                ),
            )
        }
    }

    private fun textEvent(text: String): String {
        val payload: ObjectNode = objectMapper.createObjectNode()
        payload.put("type", "text")
        payload.put("text", text)
        return objectMapper.writeValueAsString(payload)
    }
}
