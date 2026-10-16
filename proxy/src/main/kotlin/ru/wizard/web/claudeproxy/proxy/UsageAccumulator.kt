package ru.wizard.web.claudeproxy.proxy

import com.fasterxml.jackson.databind.JsonNode

/**
 * Накопитель usage-полей (input/output/cache_creation/cache_read).
 */
class UsageAccumulator {
    /** Входные токены последнего ответа. */
    var inputTokens = 0L
        private set
    /** Выходные токены последнего ответа. */
    var outputTokens = 0L
        private set
    /** Токены записи промпт-кэша. */
    var cacheCreationTokens = 0L
        private set
    /** Токены чтения промпт-кэша. */
    var cacheReadTokens = 0L
        private set

    /** Плюсует usage-узел SSE-события. */
    fun applyUsage(usage: JsonNode) {
        if (usage.isMissingNode || usage.isNull) return
        usage.path("input_tokens").takeIf { it.isNumber }?.let { inputTokens = it.asLong() }
        usage.path("output_tokens").takeIf { it.isNumber }?.let { outputTokens = it.asLong() }
        usage.path("cache_creation_input_tokens").takeIf { it.isNumber }
            ?.let { cacheCreationTokens = it.asLong() }
        usage.path("cache_read_input_tokens").takeIf { it.isNumber }
            ?.let { cacheReadTokens = it.asLong() }
    }

    /** Явная установка значений (для usage-форматов других провайдеров). */
    /** Явно задаёт счётчики (нестриминговые ответы). */
    fun setValues(inputTokens: Long, outputTokens: Long, cacheCreationTokens: Long, cacheReadTokens: Long) {
        this.inputTokens = inputTokens
        this.outputTokens = outputTokens
        this.cacheCreationTokens = cacheCreationTokens
        this.cacheReadTokens = cacheReadTokens
    }
}
