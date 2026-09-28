package ru.wizard.web.claudeproxy.serverevent

/** Параметры выборки журнала событий сервера. */
data class ServerEventFilter(
    /** Точный уровень (INFO/WARN/ERROR) или null — все уровни. */
    val level: String? = null,
    /** Подстрока в имени логгера-источника или null. */
    val loggerContains: String? = null,
    /** Подстрока в тексте сообщения или null. */
    val messageContains: String? = null,
    /** Курсор: только события с id меньше заданного (null — с самого нового). */
    val beforeId: Long? = null,
    val limit: Int = 200,
)

/** Чтение журнала событий сервера для страницы «События». */
interface ServerEventQueryService {
    /** События журнала по фильтру, новые первыми. */
    suspend fun events(filter: ServerEventFilter): List<ServerEvent>
}
