package ru.wizard.web.claudeproxy.serverevent.impl

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.AppenderBase
import ru.wizard.web.claudeproxy.serverevent.ServerEvent
import ru.wizard.web.claudeproxy.serverevent.ServerEventRecorder

/**
 * Logback-аппендер, направляющий события логгеров пакета приложения в таблицу
 * server_event. Захватываются записи уровня не ниже заданного порога
 * (`claudeproxy.server-event.min-level`, по умолчанию WARN: INFO-логи есть у
 * каждого проксируемого запроса, и их захват залил бы журнал шумом). Логгеры
 * самого пакета serverevent исключаются — иначе запись в журнал порождала бы
 * новые события (рекурсия).
 */
class ServerEventLogAppender(
    private val serverEventRecorder: ServerEventRecorder,
    private val minimumLevel: Level = Level.WARN,
) : AppenderBase<ILoggingEvent>() {

    override fun append(event: ILoggingEvent) {
        val loggerName = event.loggerName ?: return
        if (!loggerName.startsWith(APPLICATION_PACKAGE_PREFIX)) {
            return
        }
        // Защита от рекурсии: собственные логи журнала событий не записываем.
        if (loggerName.startsWith("$APPLICATION_PACKAGE_PREFIX.serverevent")) {
            return
        }
        val level = event.level ?: return
        if (!level.isGreaterOrEqual(minimumLevel)) {
            return
        }
        val stackTrace = event.throwableProxy?.let { throwableProxy ->
            try {
                ThrowableProxyUtil.asString(throwableProxy)
            } catch (exception: Exception) {
                "${throwableProxy.className}: ${throwableProxy.message}"
            }?.take(MAX_STACK_TRACE_LENGTH)
        }
        serverEventRecorder.recordAsync(
            ServerEvent(
                timestampMilliseconds = event.timeStamp,
                level = level.toString(),
                logger = loggerName,
                message = event.formattedMessage ?: "",
                stackTrace = stackTrace,
            ),
        )
    }

    companion object {
        private const val APPLICATION_PACKAGE_PREFIX = "ru.wizard.web.claudeproxy"
        private const val MAX_STACK_TRACE_LENGTH = 16384

        /** Порог уровня из настройки; некорректное значение — WARN. */
        fun parseLevel(configuredLevel: String?): Level = when (configuredLevel?.trim()?.uppercase()) {
            "INFO" -> Level.INFO
            "ERROR" -> Level.ERROR
            "WARN" -> Level.WARN
            else -> Level.WARN
        }
    }
}
