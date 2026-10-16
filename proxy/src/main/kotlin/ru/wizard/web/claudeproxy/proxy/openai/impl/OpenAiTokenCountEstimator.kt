package ru.wizard.web.claudeproxy.proxy.openai.impl

import com.fasterxml.jackson.databind.JsonNode

/**
 * Оценка числа токенов запроса без похода к провайдеру:
 * ~4 символа на токен по текстовым значениям system/messages/tools,
 * картинки — фиксированно по 1600 токенов. Коэффициент «символов на токен»
 * уточняется калибровкой по фактическим input_tokens ответов провайдера.
 */
class OpenAiTokenCountEstimator {

    /**
     * Оценка числа токенов; [charactersPerToken] — выученный коэффициент
     * «символов на токен» (null — стандартное приближение 4 симв./токен).
     */
    /** Оценка входных токенов OpenAI-запроса (калибровка или эвристика). */
    fun estimate(requestRoot: JsonNode, charactersPerToken: Double? = null): Long {
        val counts = textCounts(requestRoot)
        val ratio = charactersPerToken ?: DEFAULT_CHARACTERS_PER_TOKEN
        return (counts.textCharacters / ratio).toLong() + counts.imageCount * TOKENS_PER_IMAGE
    }

    /** Число текстовых символов запроса — та же база, из которой [estimate] получает токены. */
    /** Суммарная длина текстовых полей запроса, символы. */
    fun textCharacterCount(requestRoot: JsonNode): Long = textCounts(requestRoot).textCharacters

    /**
     * Фактические input_tokens ответа за вычётом фиксированной оценки картинок —
     * «текстовые» токены, на которых учится калибровка.
     */
    /** Оценка выходных токенов по input_tokens ответа. */
    fun textTokenCount(requestRoot: JsonNode, inputTokens: Long): Long =
        inputTokens - textCounts(requestRoot).imageCount * TOKENS_PER_IMAGE

    private fun textCounts(requestRoot: JsonNode): TextCounts {
        var characters = 0L
        var imageCount = 0L

        characters += textLength(requestRoot.path("system"))
        for (message in requestRoot.path("messages")) {
            characters += textLength(message.path("content"))
            imageCount += imageCount(message.path("content"))
        }
        for (tool in requestRoot.path("tools")) {
            characters += tool.path("name").asText("").length.toLong()
            characters += tool.path("description").asText("").length.toLong()
            characters += tool.path("input_schema").toString().length.toLong()
        }
        return TextCounts(characters, imageCount)
    }

    private data class TextCounts(val textCharacters: Long, val imageCount: Long)

    private fun textLength(node: JsonNode?): Long {
        if (node == null || node.isNull) return 0
        if (node.isTextual) return node.asText().length.toLong()
        if (!node.isArray && !node.isObject) return 0
        var total = 0L
        if (node.isObject) {
            node.path("text").takeIf { it.isTextual }?.let { total += it.asText().length.toLong() }
            node.path("input").takeIf { it.isObject }?.let { total += it.toString().length.toLong() }
        }
        for (child in node) {
            if (child.path("type").asText("") == "image") continue // учтено отдельно
            total += textLength(child)
        }
        return total
    }

    private fun imageCount(node: JsonNode?): Long {
        if (node == null || !node.isArray) return 0
        return node.count { it.path("type").asText("") == "image" }.toLong()
    }

    private companion object {
        /** Стандартное приближение для английского текста, пока калибровка не накоплена. */
        const val DEFAULT_CHARACTERS_PER_TOKEN = 4.0
        const val TOKENS_PER_IMAGE = 1600L
    }
}
