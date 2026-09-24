package ru.wizard.web.claudeproxy.proxy.openai

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.ResponseEntity
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import ru.wizard.web.claudeproxy.routing.ModelRegistry

/**
 * Обработка запроса Claude OpenAI-совместимыми провайдерами: полный перевод
 * протокола (запрос → Chat Completions, ответ/SSE → формат Claude).
 * Маршруты — по приоритету; при повторимой ошибке (429/5xx/сеть) — переключение
 * на следующий (для стриминга — только до первого события клиенту).
 */
interface OpenAiHandler {

    suspend fun chatCompletion(
        exchange: ServerWebExchange,
        routes: List<ModelRegistry.Route>,
        requestRoot: JsonNode,
        recordUsage: Boolean,
    ): ResponseEntity<Flux<DataBuffer>>

    /** Подсчёт токенов: локальная оценка (~4 символа на токен), без похода к провайдеру. */
    suspend fun countTokens(
        exchange: ServerWebExchange,
        route: ModelRegistry.Route,
        requestRoot: JsonNode,
    ): ResponseEntity<Flux<DataBuffer>>
}
