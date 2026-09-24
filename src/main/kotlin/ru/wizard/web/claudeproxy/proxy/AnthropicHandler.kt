package ru.wizard.web.claudeproxy.proxy

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.ResponseEntity
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import ru.wizard.web.claudeproxy.routing.ModelRegistry

/**
 * Pass-through к Anthropic-совместимому провайдеру: тело пересылается почти как есть
 * (подмена модели и ключа), SSE проксируется чанками без буферизации,
 * usage перехватывается сниффером по ходу потока.
 */
interface AnthropicHandler {

    suspend fun passThrough(
        exchange: ServerWebExchange,
        route: ModelRegistry.Route,
        requestRoot: JsonNode,
        upstreamPath: String,
        recordUsage: Boolean,
    ): ResponseEntity<Flux<DataBuffer>>
}
