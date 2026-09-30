package ru.wizard.web.claudeproxy.proxy

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferFactory
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import ru.wizard.web.claudeproxy.routing.ModelRegistry
import ru.wizard.web.claudeproxy.routing.ResourceBindingService
import java.nio.charset.StandardCharsets

/**
 * Pass-through Files API (/v1/files): file_id из ответа привязывается
 * к провайдеру ([ResourceBindingService]), последующие запросы по id идут
 * к нему напрямую (fallback — первичный anthropic-провайдер). Multipart
 * не разбирается: тело клиента прокачивается потоком с исходным
 * Content-Type (включая boundary). Квота не проверяется: в загрузке файла
 * нет модели, а enforce — это allowlist моделей ключа.
 */
@RestController
class FilesController(
    private val anthropicHandler: AnthropicHandler,
    private val modelRegistry: ModelRegistry,
    private val resourceBindingService: ResourceBindingService,
    private val objectMapper: ObjectMapper,
) {

    /** Загрузка файла на первичный anthropic-провайдер. */
    @PostMapping("/v1/files")
    suspend fun upload(exchange: ServerWebExchange): ResponseEntity<Flux<DataBuffer>> {
        val route = modelRegistry.primaryAnthropicRoute()
            ?: throw ApiError(HttpStatus.NOT_IMPLEMENTED, "not_found_error", "Нет anthropic-провайдеров для Files API")
        val upstreamResponse = anthropicHandler.passThroughStreamingBody(
            exchange = exchange,
            route = route,
            upstreamPath = "/v1/files",
            contentType = exchange.request.headers.contentType,
            body = exchange.request.body,
        )
        if (!upstreamResponse.statusCode.is2xxSuccessful) {
            return upstreamResponse
        }

        // Ответ загрузки — маленький JSON; агрегируем, чтобы вытащить file_id
        // и привязать его к провайдеру.
        val upstreamBody = upstreamResponse.body
            ?: throw ApiError(HttpStatus.BAD_GATEWAY, "api_error", "Пустой ответ upstream на загрузку файла")
        val joined = DataBufferUtils.join(upstreamBody).awaitSingle()
        val responseText = joined.toString(StandardCharsets.UTF_8)
        DataBufferUtils.release(joined)
        val fileId = runCatching { objectMapper.readTree(responseText).path("id").asText("") }.getOrDefault("")
        if (fileId.isNotEmpty()) {
            resourceBindingService.bind(ResourceBindingService.ResourceType.FILE, fileId, route.provider.name)
        }
        return ResponseEntity.status(upstreamResponse.statusCode)
            .headers(upstreamResponse.headers)
            .body(Flux.just<DataBuffer>(dataBufferFactory.wrap(responseText.toByteArray(StandardCharsets.UTF_8))))
    }

    /** Листинг файлов первичного провайдера (query: limit, before_id, after_id). */
    @GetMapping("/v1/files")
    suspend fun list(exchange: ServerWebExchange): ResponseEntity<Flux<DataBuffer>> {
        val route = modelRegistry.primaryAnthropicRoute()
            ?: throw ApiError(HttpStatus.NOT_IMPLEMENTED, "not_found_error", "Нет anthropic-провайдеров для Files API")
        return anthropicHandler.passThroughRequest(
            exchange = exchange,
            route = route,
            method = HttpMethod.GET,
            upstreamPath = "/v1/files",
            query = exchange.request.queryParams,
            bodyBytes = null,
        )
    }

    @GetMapping("/v1/files/{fileId}")
    suspend fun retrieve(
        exchange: ServerWebExchange,
        @PathVariable fileId: String,
    ): ResponseEntity<Flux<DataBuffer>> = anthropicHandler.passThroughRequest(
        exchange = exchange,
        route = boundRoute(fileId),
        method = HttpMethod.GET,
        upstreamPath = "/v1/files/$fileId",
        query = exchange.request.queryParams,
        bodyBytes = null,
    )

    /** Контент файла: бинарный поток без разбора. */
    @GetMapping("/v1/files/{fileId}/content")
    suspend fun content(
        exchange: ServerWebExchange,
        @PathVariable fileId: String,
    ): ResponseEntity<Flux<DataBuffer>> = anthropicHandler.passThroughRequest(
        exchange = exchange,
        route = boundRoute(fileId),
        method = HttpMethod.GET,
        upstreamPath = "/v1/files/$fileId/content",
        query = exchange.request.queryParams,
        bodyBytes = null,
    )

    /** Удаление файла; успешный ответ заодно убирает привязку. */
    @DeleteMapping("/v1/files/{fileId}")
    suspend fun delete(
        exchange: ServerWebExchange,
        @PathVariable fileId: String,
    ): ResponseEntity<Flux<DataBuffer>> {
        val response = anthropicHandler.passThroughRequest(
            exchange = exchange,
            route = boundRoute(fileId),
            method = HttpMethod.DELETE,
            upstreamPath = "/v1/files/$fileId",
            query = null,
            bodyBytes = null,
        )
        if (response.statusCode.is2xxSuccessful) {
            resourceBindingService.forget(ResourceBindingService.ResourceType.FILE, fileId)
        }
        return response
    }

    /** Маршрут по привязке файла; без привязки — первичный anthropic-провайдер. */
    private suspend fun boundRoute(fileId: String): ModelRegistry.Route {
        val providerName = resourceBindingService.providerNameOf(ResourceBindingService.ResourceType.FILE, fileId)
        if (providerName != null) {
            modelRegistry.routesForProvider(providerName).firstOrNull()?.let { return it }
        }
        return modelRegistry.primaryAnthropicRoute()
            ?: throw ApiError(HttpStatus.NOT_IMPLEMENTED, "not_found_error", "Нет anthropic-провайдеров для Files API")
    }

    private companion object {
        val dataBufferFactory: DataBufferFactory = DefaultDataBufferFactory.sharedInstance
    }
}
