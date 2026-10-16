package ru.wizard.web.claudeproxy.cacheprovider

import com.fasterxml.jackson.databind.JsonNode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Разбор Anthropic-запроса для контура внешнего кэша: ключ разговора и
 * проверка ошибочного результата инструмента. Выделен из разборщика
 */
class RequestFlattener {

    /**
     * Ключ разговора: хэш system + первого пользовательского сообщения.
     * Стабилен, пока начало разговора не меняется (продолжение растёт
     * хвостом). null — в запросе нет пользовательских сообщений.
     */
    fun conversationKey(requestRoot: JsonNode): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(requestRoot.path("system").toString().toByteArray(StandardCharsets.UTF_8))
        val messages = requestRoot.path("messages")
        for (message in messages) {
            if (message.path("role").asText() == "user") {
                digest.update(message.toString().toByteArray(StandardCharsets.UTF_8))
                return digest.digest().joinToString("") { "%02x".format(it) }
            }
        }
        return null
    }

    /**
     * Последнее пользовательское сообщение содержит результат инструмента
     * с is_error=true — сигнал отрицательной неявной оценки: ответ,
     * выданный из кэша, привёл к ошибке.
     */
    fun hasErrorToolResultInLastUserMessage(requestRoot: JsonNode): Boolean {
        val messages = requestRoot.path("messages")
        for (index in messages.size() - 1 downTo 0) {
            val message = messages.get(index)
            if (message.path("role").asText() != "user") continue
            val content = message.path("content")
            if (!content.isArray) return false
            for (block in content) {
                if (block.path("type").asText() == "tool_result" && block.path("is_error").asBoolean(false)) {
                    return true
                }
            }
            return false
        }
        return false
    }
}
