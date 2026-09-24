package ru.wizard.web.claudeproxy.proxy

import com.fasterxml.jackson.databind.JsonNode

/**
 * Накопитель usage-полей (input/output/cache_creation/cache_read).
 */
class UsageAccumulator {
    var inputTokens = 0L
        private set
    var outputTokens = 0L
        private set
    var cacheCreationTokens = 0L
        private set
    var cacheReadTokens = 0L
        private set

    fun applyUsage(usage: JsonNode) {
        if (usage.isMissingNode || usage.isNull) return
        usage.path("input_tokens").takeIf { it.isNumber }?.let { inputTokens = it.asLong() }
        usage.path("output_tokens").takeIf { it.isNumber }?.let { outputTokens = it.asLong() }
        usage.path("cache_creation_input_tokens").takeIf { it.isNumber }
            ?.let { cacheCreationTokens = it.asLong() }
        usage.path("cache_read_input_tokens").takeIf { it.isNumber }
            ?.let { cacheReadTokens = it.asLong() }
    }
}
