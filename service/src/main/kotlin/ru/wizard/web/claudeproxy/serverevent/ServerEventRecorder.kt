package ru.wizard.web.claudeproxy.serverevent

/** Асинхронная запись событий сервера в БД, не блокирующая вызывающий поток. */
interface ServerEventRecorder {
    /**
     * Ставит событие в ограниченную очередь записи; при переполнении очереди
     * событие отбрасывается (журнал не должен влиять на обработку запросов).
     */
    fun recordAsync(event: ServerEvent)
}
