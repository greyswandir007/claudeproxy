package ru.wizard.web.claudeproxy.proxy

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferFactory
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import ru.wizard.web.claudeproxy.auth.ApiKeyAuthFilter.Companion.CLIENT_KEY_ATTRIBUTE
import ru.wizard.web.claudeproxy.auth.KeyQuotaService
import ru.wizard.web.claudeproxy.routing.ModelRegistry
import ru.wizard.web.claudeproxy.routing.ResourceBindingService
import ru.wizard.web.claudeproxy.usage.UsageEvent
import ru.wizard.web.claudeproxy.usage.UsageRecorder
import java.nio.charset.StandardCharsets

/**
 * Pass-through Batches API (/v1/messages/batches): батч живёт на конкретном
 * провайдере, поэтому create маршрутизируется по модели первого запроса,
 * идентификатор привязывается к провайдеру ([ResourceBindingService]) и все
 * последующие запросы по id идут к нему напрямую (fallback — первичный
 * anthropic-провайдер). Листинг всегда смотрит только на первичный
 * провайдер: модели в запросе нет, а мержить страницы нескольких
 * провайдеров корректно нельзя.
 */
@RestController
class MessageBatchesController(
    private val anthropicHandler: AnthropicHandler,
    private val modelRegistry: ModelRegistry,
    private val keyQuotaService: KeyQuotaService,
    private val resourceBindingService: ResourceBindingService,
    private val usageRecorder: UsageRecorder,
    private val objectMapper: ObjectMapper,
) {

    /** Создание батча: все requests[] должны разрешаться в один провайдер. */
    @PostMapping("/v1/messages/batches")
    suspend fun create(
        exchange: ServerWebExchange,
        @RequestBody requestBody: String,
    ): ResponseEntity<Flux<DataBuffer>> {
        val requestRoot = objectMapper.readTree(requestBody)
        val requests = requestRoot.get("requests")
        if (requests == null || !requests.isArray || requests.isEmpty) {
            throw ApiError(HttpStatus.BAD_REQUEST, "invalid_request_error", "body.requests должен быть непустым массивом")
        }
        val publicModels = requests
            .map { element -> element.path("params").path("model").asText("") }
            .distinct()
        val invalidModel = publicModels.firstOrNull { it.isEmpty() }
        if (invalidModel != null) {
            throw ApiError(HttpStatus.BAD_REQUEST, "invalid_request_error", "body.requests[].params.model обязателен")
        }

        val routes = modelRegistry.find(publicModels.first(), rotate = true, conversationKey = null)
        val route = routes.firstOrNull()
            ?: throw ApiError(HttpStatus.NOT_FOUND, "not_found_error", "model: ${publicModels.first()}")
        val providerName = route.provider.name
        val providerRoutes = modelRegistry.routesForProvider(providerName)

        // Перезапись публичных имён в upstream-имена выбранного провайдера;
        // модель чужого провайдера здесь не найдётся — это и есть проверка
        // «все запросы батча на один провайдер».
        requests.forEach { element ->
            val params = element.get("params") as? ObjectNode
                ?: throw ApiError(HttpStatus.BAD_REQUEST, "invalid_request_error", "body.requests[].params обязателен")
            val publicModel = params.path("model").asText("")
            val mapping = providerRoutes.firstOrNull { candidate -> candidate.mapping.publicName == publicModel }
                ?: throw ApiError(
                    HttpStatus.BAD_REQUEST,
                    "invalid_request_error",
                    "Модель '$publicModel' недоступна на провайдере '$providerName'; все запросы батча должны идти на один провайдер",
                )
            params.put("model", mapping.mapping.upstreamName)
        }

        publicModels.forEach { publicModel -> keyQuotaService.enforce(exchange, publicModel) }

        val upstreamResponse = anthropicHandler.passThroughRequest(
            exchange = exchange,
            route = route,
            method = HttpMethod.POST,
            upstreamPath = "/v1/messages/batches",
            query = null,
            bodyBytes = objectMapper.writeValueAsBytes(requestRoot),
        )
        if (!upstreamResponse.statusCode.is2xxSuccessful) {
            return upstreamResponse
        }

        // Ответ create — маленький JSON; агрегируем, чтобы вытащить id батча
        // и привязать его к провайдеру.
        val upstreamBody = upstreamResponse.body
            ?: throw ApiError(HttpStatus.BAD_GATEWAY, "api_error", "Пустой ответ upstream на создание батча")
        val joined = DataBufferUtils.join(upstreamBody).awaitSingle()
        val responseText = joined.toString(StandardCharsets.UTF_8)
        DataBufferUtils.release(joined)
        val batchId = runCatching { objectMapper.readTree(responseText).path("id").asText("") }.getOrDefault("")
        if (batchId.isNotEmpty()) {
            resourceBindingService.bind(ResourceBindingService.ResourceType.MESSAGE_BATCH, batchId, providerName)
        }
        return ResponseEntity.status(upstreamResponse.statusCode)
            .headers(upstreamResponse.headers)
            .body(Flux.just<DataBuffer>(dataBufferFactory.wrap(responseText.toByteArray(StandardCharsets.UTF_8))))
    }

    /** Листинг батчей первичного провайдера (query: limit, before_id, after_id). */
    @GetMapping("/v1/messages/batches")
    suspend fun list(exchange: ServerWebExchange): ResponseEntity<Flux<DataBuffer>> {
        val route = modelRegistry.primaryAnthropicRoute()
            ?: throw ApiError(HttpStatus.NOT_IMPLEMENTED, "not_found_error", "Нет anthropic-провайдеров для Batches API")
        return anthropicHandler.passThroughRequest(
            exchange = exchange,
            route = route,
            method = HttpMethod.GET,
            upstreamPath = "/v1/messages/batches",
            query = exchange.request.queryParams,
            bodyBytes = null,
        )
    }

    /** Метаданные батча. */
    @GetMapping("/v1/messages/batches/{batchId}")
    suspend fun retrieve(
        exchange: ServerWebExchange,
        @PathVariable batchId: String,
    ): ResponseEntity<Flux<DataBuffer>> = boundRoute(ResourceBindingService.ResourceType.MESSAGE_BATCH, batchId)
        .let { route ->
            anthropicHandler.passThroughRequest(
                exchange = exchange,
                route = route,
                method = HttpMethod.GET,
                upstreamPath = "/v1/messages/batches/$batchId",
                query = exchange.request.queryParams,
                bodyBytes = null,
            )
        }

    /** Отмена незавершённого батча. */
    @PostMapping("/v1/messages/batches/{batchId}/cancel")
    suspend fun cancel(
        exchange: ServerWebExchange,
        @PathVariable batchId: String,
    ): ResponseEntity<Flux<DataBuffer>> = boundRoute(ResourceBindingService.ResourceType.MESSAGE_BATCH, batchId)
        .let { route ->
            anthropicHandler.passThroughRequest(
                exchange = exchange,
                route = route,
                method = HttpMethod.POST,
                upstreamPath = "/v1/messages/batches/$batchId/cancel",
                query = null,
                bodyBytes = null,
            )
        }

    /**
     * Результаты батча: JSONL стримится построчно, model переписывается в
     * публичное имя, usage учитывается один раз на строку (дедуп по
     * (batch_id, custom_id) — results можно читать многократно).
     */
    @PostMapping("/v1/messages/batches/{batchId}/results")
    suspend fun results(
        exchange: ServerWebExchange,
        @PathVariable batchId: String,
    ): ResponseEntity<Flux<DataBuffer>> {
        val route = boundRoute(ResourceBindingService.ResourceType.MESSAGE_BATCH, batchId)
        val providerName = route.provider.name
        val clientKey = exchange.attributes.getOrDefault(CLIENT_KEY_ATTRIBUTE, "").toString()
        val upstreamResponse = anthropicHandler.passThroughRequest(
            exchange = exchange,
            route = route,
            method = HttpMethod.POST,
            upstreamPath = "/v1/messages/batches/$batchId/results",
            query = null,
            bodyBytes = null,
        )
        if (!upstreamResponse.statusCode.is2xxSuccessful) {
            return upstreamResponse
        }
        val upstreamBody = upstreamResponse.body
            ?: throw ApiError(HttpStatus.BAD_GATEWAY, "api_error", "Пустой ответ upstream на results")
        // Длина строк меняется после перезаписи model — Content-Length upstream
        // больше не соответствует телу.
        val responseHeaders = HttpHeaders()
        responseHeaders.putAll(upstreamResponse.headers)
        responseHeaders.remove(HttpHeaders.CONTENT_LENGTH)
        return ResponseEntity.status(upstreamResponse.statusCode)
            .headers(responseHeaders)
            .body(transformResults(batchId, providerName, clientKey, upstreamBody))
    }

    /** Маршрут по привязке ресурса; без привязки — первичный anthropic-провайдер. */
    private suspend fun boundRoute(
        resourceType: ResourceBindingService.ResourceType,
        resourceId: String,
    ): ModelRegistry.Route {
        val providerName = resourceBindingService.providerNameOf(resourceType, resourceId)
        if (providerName != null) {
            modelRegistry.routesForProvider(providerName).firstOrNull()?.let { return it }
        }
        return modelRegistry.primaryAnthropicRoute()
            ?: throw ApiError(HttpStatus.NOT_IMPLEMENTED, "not_found_error", "Нет anthropic-провайдеров для Batches API")
    }

    /**
     * Построчный трансформер JSONL без агрегации всего тела: хвост неполной
     * строки пережидает следующий буфер. Дедуп usage выполняется в потоке —
     * локальный sqlite, вставка микросекундная.
     */
    private fun transformResults(
        batchId: String,
        providerName: String,
        clientKey: String,
        upstreamBody: Flux<DataBuffer>,
    ): Flux<DataBuffer> {
        val providerRoutes = modelRegistry.routesForProvider(providerName)
        val lineBuffer = StringBuilder()
        return upstreamBody
            .concatMap { buffer ->
                mono {
                    val transformedLines = ArrayList<ByteArray>()
                    lineBuffer.append(buffer.toString(StandardCharsets.UTF_8))
                    DataBufferUtils.release(buffer)
                    while (true) {
                        val newlineIndex = lineBuffer.indexOf('\n')
                        if (newlineIndex < 0) break
                        val line = lineBuffer.substring(0, newlineIndex)
                        lineBuffer.delete(0, newlineIndex + 1)
                        val transformed = transformedLine(line, providerRoutes, providerName, clientKey) ?: continue
                        recordUsageOnce(batchId, transformed)
                        transformedLines.add(transformed.bytes)
                    }
                    transformedLines
                }.flatMapMany { lines -> Flux.fromIterable(lines).map(dataBufferFactory::wrap) }
            }
            .concatWith(
                mono {
                    val tail = lineBuffer.toString().trim()
                    if (tail.isEmpty()) {
                        null
                    } else {
                        val transformed = transformedLine(tail, providerRoutes, providerName, clientKey)
                        if (transformed != null) {
                            recordUsageOnce(batchId, transformed)
                        }
                        transformed?.bytes
                    }
                }.flatMapMany { bytes -> Flux.just<DataBuffer>(dataBufferFactory.wrap(bytes)) },
            )
    }

    /** Записывает usage строки results один раз — дедуп по (batch_id, custom_id). */
    private suspend fun recordUsageOnce(batchId: String, transformed: TransformedLine) {
        val usageEvent = transformed.usageEvent ?: return
        val customId = transformed.customId ?: return
        if (resourceBindingService.rememberBatchResult(batchId, customId)) {
            usageRecorder.recordAsync(usageEvent)
        }
    }

    /** Одна строка results: перезапись model и сбор usage-события, если есть. */
    private fun transformedLine(
        line: String,
        providerRoutes: List<ModelRegistry.Route>,
        providerName: String,
        clientKey: String,
    ): TransformedLine? {
        if (line.isBlank()) return null
        val root = runCatching { objectMapper.readTree(line) }.getOrNull()
            ?: return TransformedLine(line.toByteArray(StandardCharsets.UTF_8), customId = null, usageEvent = null)
        val customId = root.path("custom_id").asText(null)
        val message = root.path("result").path("message")
        var usageEvent: UsageEvent? = null
        if (!message.isMissingNode && message.has("model")) {
            val upstreamModel = message.path("model").asText()
            val publicModel = providerRoutes
                .firstOrNull { candidate -> candidate.mapping.upstreamName == upstreamModel }
                ?.mapping?.publicName
                ?: upstreamModel
            (message as ObjectNode).put("model", publicModel)
            val usage = message.path("usage")
            if (!usage.isMissingNode) {
                usageEvent = UsageEvent(
                    ts = System.currentTimeMillis(),
                    clientKey = clientKey,
                    provider = providerName,
                    model = publicModel,
                    upstreamModel = upstreamModel,
                    stream = false,
                    inputTokens = usage.path("input_tokens").asLong(0),
                    outputTokens = usage.path("output_tokens").asLong(0),
                    cacheCreationTokens = usage.path("cache_creation_input_tokens").asLong(0),
                    cacheReadTokens = usage.path("cache_read_input_tokens").asLong(0),
                    durationMilliseconds = 0,
                    status = 200,
                    error = null,
                )
            }
        }
        return TransformedLine(
            bytes = objectMapper.writeValueAsBytes(root) + NEWLINE_BYTES,
            customId = customId,
            usageEvent = usageEvent,
        )
    }

    private data class TransformedLine(
        val bytes: ByteArray,
        val customId: String?,
        val usageEvent: UsageEvent?,
    )

    private companion object {
        val dataBufferFactory: DataBufferFactory = DefaultDataBufferFactory.sharedInstance
        val NEWLINE_BYTES = "\n".toByteArray(StandardCharsets.UTF_8)
    }
}
