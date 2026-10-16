package ru.wizard.web.claudeproxy.chat

/**
 * История веб-чата: на каждый клиентский ключ — отдельный тред
 * (title, сброс) и список сообщений user/assistant.
 */
interface ChatHistoryService {

    /** Одно сообщение истории. */
    data class ChatMessage(
        /** Идентификатор в БД. */
        val id: Long,
        /** user | assistant. */
        val role: String,
        /** Текст сообщения. */
        val content: String,
        /** Момент записи, epoch millis. */
        val createdAt: Long,
    )

    /** Тред ключа: один на клиентский ключ. */
    data class ThreadInfo(
        /** Имя ключа клиента. */
        val clientKey: String,
        /** Заголовок треда (авто — по первому сообщению, либо переименованный). */
        val title: String,
        /** Момент последнего сообщения, epoch millis. */
        val updatedAt: Long,
    )

    /** Тред ключа (создаётся при первом обращении с автозаголовком из сообщения). */
    suspend fun thread(clientKey: String): ThreadInfo

    /** Переименовывает тред ключа. */
    suspend fun renameThread(clientKey: String, title: String)

    /** Сообщения треда ключа от старых к новым. */
    suspend fun messages(clientKey: String): List<ChatMessage>

    /** Добавляет сообщение и обновляет тред (авто-title по первому сообщению). */
    suspend fun append(clientKey: String, role: String, content: String)

    /** Сброс истории ключа (тред остаётся с текущим title). */
    suspend fun clear(clientKey: String)
}
