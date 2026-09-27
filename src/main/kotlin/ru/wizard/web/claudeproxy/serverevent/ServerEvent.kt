package ru.wizard.web.claudeproxy.serverevent

/** Одна запись журнала событий сервера (страница «События»). */
data class ServerEvent(
    /** Присваивается БД; при записи в очередь — 0. */
    val id: Long = 0,
    val timestampMilliseconds: Long,
    /** INFO | WARN | ERROR. */
    val level: String,
    /** Имя логгера-источника (категория) или «application» для явных событий. */
    val logger: String,
    val message: String,
    /** Stack trace исключения, если оно есть в записи лога. */
    val stackTrace: String? = null,
)
