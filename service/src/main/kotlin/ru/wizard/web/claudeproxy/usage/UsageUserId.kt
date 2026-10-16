package ru.wizard.web.claudeproxy.usage

import com.fasterxml.jackson.databind.JsonNode

/**
 * Извлечение сессии клиента из `metadata.user_id` запроса.
 *
 * Claude Code присылает ЗНАЧЕНИЕ-ОБЪЕКТ (asText на нём вернул бы всю
 * JSON-строку — так в usage_event однажды легли блобы): берём поле
 * `session_id`. Другие клиенты шлют плоскую строку — используем как есть.
 */
object UsageUserId {

    fun of(requestRoot: JsonNode): String? {
        val node = requestRoot.path("metadata").path("user_id")
        if (node.isMissingNode || node.isNull) return null
        if (node.isObject) {
            return node.path("session_id").asText(null)?.takeIf { it.isNotBlank() }
                ?: node.path("device_id").asText(null)?.takeIf { it.isNotBlank() }
        }
        return node.asText(null)?.takeIf { it.isNotBlank() }
    }
}
