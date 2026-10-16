package ru.wizard.web.claudeproxy.serverevent

import ch.qos.logback.classic.LoggerContext
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import ru.wizard.web.claudeproxy.config.ProxyProperties
import ru.wizard.web.claudeproxy.serverevent.impl.ServerEventLogAppender

/**
 * Подключение [ServerEventLogAppender] к корневому логгеру Logback и запись
 * явных INFO-событий жизненного цикла (старт/остановка сервера) в журнал.
 */
@Configuration
class ServerEventLogConfiguration(
    private val serverEventRecorder: ServerEventRecorder,
    private val proxyProperties: ProxyProperties,
) {
    private val logger = KotlinLogging.logger {}
    private var attachedAppender: ServerEventLogAppender? = null

    @PostConstruct
    fun attachAppender() {
        val loggerFactory = LoggerFactory.getILoggerFactory()
        if (loggerFactory !is LoggerContext) {
            logger.warn { "SLF4J binding is not Logback, server event log appender is not attached" }
            return
        }
        val minimumLevel = ServerEventLogAppender.parseLevel(proxyProperties.serverEvent.minLevel)
        val appender = ServerEventLogAppender(serverEventRecorder, minimumLevel)
        appender.context = loggerFactory
        appender.start()
        loggerFactory.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender)
        attachedAppender = appender
    }

    @EventListener(ApplicationReadyEvent::class)
    fun recordServerStarted() {
        recordLifecycleEvent("Server started")
    }

    /** Останавливает логгер, чтобы appender не писал в закрытый контекст. */
    @PreDestroy
    fun shutdown() {
        recordLifecycleEvent("Server stopping")
        attachedAppender?.let { appender ->
            val loggerFactory = LoggerFactory.getILoggerFactory()
            if (loggerFactory is LoggerContext) {
                loggerFactory.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(appender)
            }
            appender.stop()
        }
        attachedAppender = null
    }

    private fun recordLifecycleEvent(message: String) {
        serverEventRecorder.recordAsync(
            ServerEvent(
                timestampMilliseconds = System.currentTimeMillis(),
                level = "INFO",
                logger = "application",
                message = message,
            ),
        )
    }
}
