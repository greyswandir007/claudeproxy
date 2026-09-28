package ru.wizard.web.claudeproxy.proxy.openai.impl

import com.fasterxml.jackson.databind.JsonNode

/**
 * Оценка числа токенов запроса без похода к провайдеру:
 * ~4 символа на токен по текстовым значениям system/messages/tools,
 * картинки — фиксированно по 1600 токенов.
 */
class OpenAiTokenCountEstimator {

    fun estimate(requestRoot: JsonNode): Long {
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
        return characters / CHARACTERS_PER_TOKEN + imageCount * TOKENS_PER_IMAGE
    }

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
        const val CHARACTERS_PER_TOKEN = 4L
        const val TOKENS_PER_IMAGE = 1600L
    }
}
