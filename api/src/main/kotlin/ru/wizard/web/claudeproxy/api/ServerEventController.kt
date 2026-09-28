package ru.wizard.web.claudeproxy.api

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import ru.wizard.web.claudeproxy.db.DatabaseProvider
import ru.wizard.web.claudeproxy.serverevent.ServerEventFilter
import ru.wizard.web.claudeproxy.serverevent.ServerEventQueryService
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** API журнала событий сервера для страницы «События». */
@RestController
@RequestMapping("/api/server-events")
class ServerEventController(
    private val serverEventQueryService: ServerEventQueryService,
    private val databaseProvider: DatabaseProvider,
    private val jdbcTemplate: JdbcTemplate,
) {
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    /** События журнала по фильтру, новые первыми. */
    @GetMapping
    suspend fun serverEvents(
        @RequestParam(required = false) level: String?,
        @RequestParam(required = false) loggerContains: String?,
        @RequestParam(required = false) messageContains: String?,
        @RequestParam(required = false) beforeId: Long?,
        @RequestParam(defaultValue = "200") limit: Int,
    ): List<ServerEventView> =
        serverEventQueryService.events(
            ServerEventFilter(
                level = level?.takeIf { it.isNotBlank() },
                loggerContains = loggerContains?.takeIf { it.isNotBlank() },
                messageContains = messageContains?.takeIf { it.isNotBlank() },
                beforeId = beforeId,
                limit = limit.coerceIn(1, MAX_LIMIT),
            ),
        ).map { event ->
            ServerEventView(
                id = event.id,
                timestamp = timestampFormat.format(Instant.ofEpochMilli(event.timestampMilliseconds)),
                level = event.level,
                logger = event.logger,
                message = event.message,
                stackTrace = event.stackTrace,
            )
        }

    /** Полная очистка журнала (кнопка «Очистить» на странице «События»). */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun clearServerEvents() {
        databaseProvider.execute {
            jdbcTemplate.update("DELETE FROM server_event")
        }
    }

    /** DTO записи журнала для фронтенда. */
    data class ServerEventView(
        val id: Long,
        val timestamp: String,
        val level: String,
        val logger: String,
        val message: String,
        val stackTrace: String?,
    )

    companion object {
        private const val MAX_LIMIT = 500
    }
}
