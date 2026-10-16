package ru.wizard.web.claudeproxy.cacheprovider

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.channel.ChannelOption
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.beans.factory.DisposableBean
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import ru.wizard.web.claudeproxy.config.ProxyProperties
import ru.wizard.web.claudeproxy.cacheprovider.impl.CacheProviderAvailabilityTracker
import ru.wizard.web.claudeproxy.serverevent.ServerEventRecorder

/**
 * HTTP-клиент внешнего кэш-провайдера по спеке docs/cache-providers.md.
 *
 * Отказоустойчивость: lookup ограничен таймаутом консультации и при
 * сбое даёт null; store идёт fire-and-forget в отдельной корутине;
 * трекер доступности глушит лавину обращений к лежащему сервису и
 * пишет по одному событию на дашборд. Операционные таймауты берутся из
 * секции claudeproxy.cache-providers (единые ручки исходящих стыковок).
 */
class HttpCacheProviderService(
    override val name: String,
    url: String,
    private val objectMapper: ObjectMapper,
    private val timeouts: ProxyProperties.CacheProviders,
    private val minConfidence: Double,
    serverEventRecorder: ServerEventRecorder,
) : CacheProviderService, DisposableBean {

    private val logger = KotlinLogging.logger {}
    private val tracker = CacheProviderAvailabilityTracker(
        serverEventRecorder,
        name,
        timeouts.availabilityBackoffMilliseconds,
    )
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val webClient: WebClient = WebClient.builder()
        .baseUrl(url)
        .clientConnector(
            ReactorClientHttpConnector(
                HttpClient.create()
                    .option(
                        ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        timeouts.timeouts.connectTimeoutMilliseconds,
                    )
                    .responseTimeout(
                        Duration.ofMillis(timeouts.timeouts.operationTimeoutMilliseconds),
                    ),
            ),
        )
        .build()

    override fun destroy() {
        storeScope.cancel()
    }

    override suspend fun lookup(
        request: CacheProviderService.LookupRequest,
    ): CacheProviderService.LookupResult? {
        if (!tracker.isAvailable()) return null
        val body = objectMapper.createObjectNode()
        body.put("model", request.model)
        body.put("path", request.path)
        body.put("requestHash", request.requestHash)
        body.put("requestJson", request.requestJson)
        request.sessionKey?.let { body.put("sessionKey", it) }
        val response = try {
            withTimeoutOrNull(timeouts.timeouts.consultTimeoutMilliseconds) {
                call("/v1/cache/lookup", body)
            } ?: run {
                tracker.failure("lookup timeout")
                return null
            }
        } catch (exception: Exception) {
            tracker.failure("lookup failed: ${exception.message}")
            return null
        }
        tracker.success()
        if (!response.path("hit").asBoolean(false)) return null
        val confidence = response.path("confidence").asDouble(1.0)
        if (confidence < minConfidence) return null
        return CacheProviderService.LookupResult(
            answerId = response.path("answerId").asText(""),
            responseFormat = response.path("responseFormat").asText("JSON"),
            responseBody = response.path("responseBody").asText(""),
            confidence = confidence,
            inputTokens = response.path("inputTokens").asLong(0),
            outputTokens = response.path("outputTokens").asLong(0),
        )
    }

    override fun storeAsync(request: CacheProviderService.StoreRequest) {
        val body = objectMapper.createObjectNode()
        body.put("model", request.model)
        body.put("path", request.path)
        body.put("requestHash", request.requestHash)
        body.put("requestJson", request.requestJson)
        body.put("responseFormat", request.responseFormat)
        body.put("responseBody", request.responseBody)
        body.put("inputTokens", request.inputTokens)
        body.put("outputTokens", request.outputTokens)
        request.sessionKey?.let { body.put("sessionKey", it) }
        storeScope.launch {
            try {
                withTimeoutOrNull(timeouts.timeouts.operationTimeoutMilliseconds) {
                    call("/v1/cache/store", body)
                }
                tracker.success()
            } catch (exception: Exception) {
                tracker.failure("store failed: ${exception.message}")
            }
        }
    }

    override suspend fun feedback(answerId: String, positive: Boolean) {
        if (!tracker.isAvailable()) return
        val body = objectMapper.createObjectNode()
        body.put("answerId", answerId)
        body.put("positive", positive)
        try {
            withTimeoutOrNull(timeouts.timeouts.operationTimeoutMilliseconds) {
                call("/v1/cache/feedback", body)
            }
            tracker.success()
        } catch (exception: Exception) {
            tracker.failure("feedback failed: ${exception.message}")
        }
    }

    override suspend fun status(): Map<String, Any?> = try {
        val response = withTimeoutOrNull(timeouts.timeouts.operationTimeoutMilliseconds) {
            webClient.get()
                .uri("/v1/cache/stats")
                .retrieve()
                .bodyToMono(String::class.java)
                .map { objectMapper.readTree(it) }
                .awaitSingle()
        }
        @Suppress("UNCHECKED_CAST")
        (response?.let { objectMapper.convertValue(it, Any::class.java) } as? Map<String, Any?>)
            ?: linkedMapOf("error" to "статистика недоступна")
    } catch (exception: Exception) {
        linkedMapOf("error" to (exception.message ?: "недоступен"))
    }

    /** POST JSON; не-2xx — исключение вызывающему (трекеру). */
    private suspend fun call(path: String, body: ObjectNode): JsonNode {
        val responseText = webClient.post()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(objectMapper.writeValueAsString(body))
            .exchangeToMono { response ->
                response.bodyToMono(String::class.java).defaultIfEmpty("").map { text ->
                    if (response.statusCode() != HttpStatus.OK) {
                        throw IllegalStateException("$path -> ${response.statusCode()}: $text")
                    }
                    text
                }
            }
            .awaitSingle()
        return objectMapper.readTree(responseText)
    }
}
