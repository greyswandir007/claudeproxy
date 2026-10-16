package ru.wizard.web.claudeproxy.proxy

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Перехват usage из SSE-потока Anthropic: message_start (input/cache) и
 * message_delta (output). Чанки могут разрывать строки — буферизуем до "\n".
 */
class SseUsageSniffer(private val objectMapper: ObjectMapper) {
    /** Накопитель usage из релейного SSE. */
    val usageAccumulator = UsageAccumulator()
    private val pendingLine = StringBuilder()

    /** Сканит кусок SSE на usage-события. */
    fun onChunk(text: String) {
        pendingLine.append(text)
        while (true) {
            val newlineIndex = pendingLine.indexOf('\n')
            if (newlineIndex < 0) break
            val line = pendingLine.substring(0, newlineIndex)
            pendingLine.delete(0, newlineIndex + 1)
            handleLine(line)
        }
    }

    private fun handleLine(line: String) {
        if (!line.startsWith("data:")) return
        val payload = line.removePrefix("data:").trim()
        if (!payload.startsWith("{")) return
        val node = runCatching { objectMapper.readTree(payload) }.getOrNull() ?: return
        when (node.path("type").asText()) {
            "message_start" -> usageAccumulator.applyUsage(node.path("message").path("usage"))
            "message_delta" -> usageAccumulator.applyUsage(node.path("usage"))
        }
    }
}
