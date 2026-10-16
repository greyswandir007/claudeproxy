package ru.wizard.web.claudeproxy.serverevent.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import ru.wizard.web.claudeproxy.config.ProxyProperties
import ru.wizard.web.claudeproxy.db.DatabaseProvider
import ru.wizard.web.claudeproxy.serverevent.ServerEvent
import ru.wizard.web.claudeproxy.serverevent.ServerEventRecorder
import java.util.concurrent.atomic.AtomicLong

/**
 * Асинхронная запись событий сервера: ограниченная очередь + единственный
 * писатель на Dispatchers.IO. При переполнении очереди события отбрасываются
 * со счётчиком потерь — журнал не должен влиять на обработку запросов.
 */
@Component
class AsyncServerEventRecorder(
    private val databaseProvider: DatabaseProvider,
    private val jdbcTemplate: JdbcTemplate,
    proxyProperties: ProxyProperties,
) : ServerEventRecorder {
    private val logger = KotlinLogging.logger {}

    private val capacity = proxyProperties.serverEvent.queueCapacity
    private val channel = Channel<ServerEvent>(capacity)
    private val droppedCount = AtomicLong()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("server-event-recorder"))

    private val writerJob = scope.launch {
        for (event in channel) {
            try {
                persist(event)
            } catch (exception: Exception) {
                logger.error(exception) { "Failed to record server event (level=${event.level}, logger=${event.logger})" }
            }
        }
    }

    override fun recordAsync(event: ServerEvent) {
        val result = channel.trySend(event)
        if (result.isFailure) {
            val dropped = droppedCount.incrementAndGet()
            logger.warn { "Server event queue is full (capacity=$capacity), dropped events so far: $dropped" }
        }
    }

    /** Счётчик отброшенных из-за переполнения очереди событий (для мониторинга). */
    fun droppedEvents(): Long = droppedCount.get()

    private suspend fun persist(event: ServerEvent) = databaseProvider.execute {
        jdbcTemplate.update(
            """
            INSERT INTO server_event (ts, level, logger, message, stack_trace)
            VALUES (?, ?, ?, ?, ?)
            """,
            event.timestampMilliseconds,
            event.level,
            event.logger,
            event.message,
            event.stackTrace,
        )
    }

    /** Дожидается очереди и закрывает диспетчера при остановке приложения. */
    @PreDestroy
    fun shutdown() {
        channel.close()
        // Даём писателю несколько секунд дописать остатки очереди.
        runBlocking { withTimeoutOrNull(2000) { writerJob.join() } }
        scope.cancel()
    }
}
