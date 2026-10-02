package ru.wizard.web.claudeproxy.proxy.cache

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import ru.wizard.web.claudeproxy.auth.ApiKeyAuthFilter
import ru.wizard.web.claudeproxy.usage.UsageEvent
import java.nio.charset.StandardCharsets

/**
 * Собирает ответ и usage-событие для повтора из кэша повторяющихся запросов.
 * Повтор в том же режиме, каким записан, воспроизводится байт-в-байт (JSON как
 * есть, SSE-транскрипт одним чанком); при кросс-режимном повторе (stream не
 * входит в ключ кэша) ответ конвертируется: JSON → синтез SSE, SSE → сборка JSON.
 */
// public: используется модулем proxy (повтор ответа из кэша в upstream-обработчиках)
object CachedResponsePresenter {

    /** Готовит web-ответ из закэшированного (SSE или JSON по Content-Type). */
    fun buildResponse(
        exchange: ServerWebExchange,
        cached: RequestCacheService.CachedResponse,
        streamRequested: Boolean,
    ): ResponseEntity<Flux<DataBuffer>> {
        val (mediaType, replayBody) = when {
            streamRequested && cached.responseFormat == RequestCacheService.ResponseFormat.SSE ->
                MediaType.TEXT_EVENT_STREAM to cached.responseBody
            streamRequested ->
                // записан JSON, повтор стримовый — синтезируем SSE-события
                MediaType.TEXT_EVENT_STREAM to SseFromJsonSynthesizer.synthesize(cached.responseBody)
            cached.responseFormat == RequestCacheService.ResponseFormat.JSON ->
                MediaType.APPLICATION_JSON to cached.responseBody
            else ->
                // записан SSE-транскрипт, повтор не-стримовый — собираем JSON
                MediaType.APPLICATION_JSON to JsonFromSseAssembler.assemble(cached.responseBody)
        }
        val body = exchange.response.bufferFactory()
            .wrap(replayBody.toByteArray(StandardCharsets.UTF_8))
        return ResponseEntity.ok()
            .contentType(mediaType)
            .header("Cache-Control", "no-cache")
            .body(Flux.just(body))
    }

    /**
     * Usage-событие повтора: провайдер — псевдо-провайдер 'cache', токены 0
     * (повтор бесплатен), saved_tokens = полный объём закэшированного ответа.
     */
    fun usageEvent(
        exchange: ServerWebExchange,
        requestRoot: JsonNode,
        cached: RequestCacheService.CachedResponse,
    ): UsageEvent {
        val model = requestRoot.path("model").asText()
        return UsageEvent(
            ts = System.currentTimeMillis(),
            clientKey = exchange.getAttribute(ApiKeyAuthFilter.CLIENT_KEY_ATTRIBUTE) ?: "unknown",
            provider = RequestCacheService.CACHE_PROVIDER_NAME,
            model = model,
            upstreamModel = model,
            stream = requestRoot.path("stream").asBoolean(false),
            inputTokens = 0,
            outputTokens = 0,
            cacheCreationTokens = 0,
            cacheReadTokens = 0,
            durationMilliseconds = 0,
            status = 200,
            error = null,
            savedTokens = cached.inputTokens + cached.outputTokens,
        )
    }
}
