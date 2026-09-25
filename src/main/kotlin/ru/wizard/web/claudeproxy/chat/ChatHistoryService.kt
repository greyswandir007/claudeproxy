package ru.wizard.web.claudeproxy.chat

/**
 * История веб-чата: на каждый клиентский ключ — отдельный тред
 * (title, сброс) и список сообщений user/assistant.
 */
interface ChatHistoryService {

    data class ChatMessage(
        val id: Long,
        val role: String,
        val content: String,
        val createdAt: Long,
    )

    data class ThreadInfo(val clientKey: String, val title: String, val updatedAt: Long)

    /** Тред ключа (создаётся при первом обращении с автозаголовком из сообщения). */
    suspend fun thread(clientKey: String): ThreadInfo

    suspend fun renameThread(clientKey: String, title: String)

    suspend fun messages(clientKey: String): List<ChatMessage>

    /** Добавляет сообщение и обновляет тред (авто-title по первому сообщению). */
    suspend fun append(clientKey: String, role: String, content: String)

    /** Сброс истории ключа (тред остаётся с текущим title). */
    suspend fun clear(clientKey: String)
}
