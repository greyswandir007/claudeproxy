package ru.wizard.web.claudeproxy.proxy

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import ru.wizard.web.claudeproxy.routing.ModelRegistry

/**
 * Pass-through к Anthropic-совместимым провайдерам: тело пересылается почти как есть
 * (подмена модели и ключа), SSE проксируется чанками без буферизации,
 * usage перехватывается сниффером по ходу потока.
 * Маршруты — по приоритету; при повторимой ошибке (429/5xx/сеть) — переключение
 * на следующий (для стриминга — только до первого события клиенту).
 */
interface AnthropicHandler {

    /** Полный проход запроса к провайдеру (стриминг и JSON). */
    suspend fun passThrough(
        exchange: ServerWebExchange,
        routes: List<ModelRegistry.Route>,
        requestRoot: JsonNode,
        upstreamPath: String,
        recordUsage: Boolean,
        conversationKey: String? = null,
    ): ResponseEntity<Flux<DataBuffer>>

    /**
     * Одноразовый pass-through произвольного запроса к Batches/Files API:
     * метод, query и готовое тело байтами уходят в один провайдер без
     * retry-каскада (create/upload неидемпотентны), ответ возвращается
     * клиенту как есть, включая статус ошибки upstream.
     */
    /** Проход нестримингового запроса: полный ответ одним телом. */
    suspend fun passThroughRequest(
        exchange: ServerWebExchange,
        route: ModelRegistry.Route,
        method: HttpMethod,
        upstreamPath: String,
        query: MultiValueMap<String, String>?,
        bodyBytes: ByteArray?,
    ): ResponseEntity<Flux<DataBuffer>>

    /**
     * Pass-through стримящегося тела (multipart-загрузка файла): байты
     * клиента прокачиваются в upstream без разбора, с клиентским
     * Content-Type (включая boundary).
     */
    /** Проход стримингового запроса: тело переливается чанками. */
    suspend fun passThroughStreamingBody(
        exchange: ServerWebExchange,
        route: ModelRegistry.Route,
        upstreamPath: String,
        contentType: MediaType?,
        body: Flux<DataBuffer>,
    ): ResponseEntity<Flux<DataBuffer>>
}
