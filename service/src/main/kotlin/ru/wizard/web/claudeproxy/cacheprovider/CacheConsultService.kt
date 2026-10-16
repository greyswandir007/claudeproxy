package ru.wizard.web.claudeproxy.cacheprovider

import com.fasterxml.jackson.databind.JsonNode
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.InitializingBean
import org.springframework.stereotype.Service
import ru.wizard.web.claudeproxy.cacheprovider.impl.JdbcCacheProviderRegistry
import ru.wizard.web.claudeproxy.proxy.cache.RequestCacheService

/**
 * Оркестрация внешнего кэша для пайплайна проксирования: консультация до
 * роутинга, асинхронный store после ответа апстрима и неявные оценки
 * (feedback) по сигналам следующего запроса разговора.
 *
 * Корреляция оценок: после выдачи ответа из кэша запоминается
 * sessionKey → (провайдер, answerId, хэш запроса); следующий запрос того
 * же разговора закрывает её плюсом/минусом (ошибка инструмента /
 * регенерация → минус, спокойное продолжение → плюс).
 */
@Service
class CacheConsultService(
    private val registry: JdbcCacheProviderRegistry,
    private val requestCacheService: RequestCacheService,
) : InitializingBean, DisposableBean {

    private val flattener = RequestFlattener()
    private val sweepScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun afterPropertiesSet() {
        sweepScope.launch {
            while (isActive) {
                delay(300_000)
                removeStale(ttlSeconds = 1800)
            }
        }
    }

    override fun destroy() {
        sweepScope.cancel()
    }

    private data class PendingAnswer(
        val providerName: String,
        val answerId: String,
        val requestHash: String,
        val servedAtEpochMilliseconds: Long,
    )

    private val pending = ConcurrentHashMap<String, PendingAnswer>()

    /**
     * Неявная оценка ожидающего ответа внешнего кэша по сигналам запроса
     * (до локального кэша: повтор — тоже сигнал). Вызывается в начале
     * каждого проксируемого запроса.
     */
    suspend fun beforeRequest(path: String, requestRoot: JsonNode, sessionKey: String?) {
        val key = sessionKey ?: flattener.conversationKey(requestRoot) ?: return
        val answer = pending.remove(key) ?: return
        val errorToolResult = flattener.hasErrorToolResultInLastUserMessage(requestRoot)
        val retry = !errorToolResult &&
            requestCacheService.buildCacheKey(path, requestRoot).hash == answer.requestHash
        val positive = !errorToolResult && !retry
        registry.provider(answer.providerName).feedback(answer.answerId, positive)
    }

    /**
     * Консультация внешнего кэша до похода к провайдеру. null — промах,
     * сбой или уверенность ниже порога. При попадании запоминает
     * корреляцию для последующего feedback.
     */
    suspend fun consult(
        providerName: String,
        path: String,
        requestRoot: JsonNode,
        sessionKey: String?,
    ): CacheProviderService.LookupResult? {
        val cacheKey = requestCacheService.buildCacheKey(path, requestRoot)
        val result = registry.provider(providerName).lookup(
            CacheProviderService.LookupRequest(
                model = requestRoot.path("model").asText(""),
                path = path,
                requestHash = cacheKey.hash,
                requestJson = requestRoot.toString(),
                sessionKey = sessionKey,
            ),
        ) ?: return null
        if (result.answerId.isNotEmpty()) {
            val key = sessionKey ?: flattener.conversationKey(requestRoot)
            if (key != null) {
                pending[key] = PendingAnswer(
                    providerName = providerName,
                    answerId = result.answerId,
                    requestHash = cacheKey.hash,
                    servedAtEpochMilliseconds = System.currentTimeMillis(),
                )
            }
        }
        return result
    }

    /** Асинхронная запись пары во внешний кэш (fire-and-forget:
     * вызывается из не-suspend контекста завершения стрима). */
    fun storeAsync(
        providerName: String,
        path: String,
        requestRoot: JsonNode,
        responseBody: String,
        responseFormat: RequestCacheService.ResponseFormat,
        inputTokens: Long,
        outputTokens: Long,
        sessionKey: String?,
    ) {
        val cacheKey = requestCacheService.buildCacheKey(path, requestRoot)
        val model = requestRoot.path("model").asText("")
        val requestJson = requestRoot.toString()
        sweepScope.launch {
            registry.provider(providerName).storeAsync(
                CacheProviderService.StoreRequest(
                    model = model,
                    path = path,
                    requestHash = cacheKey.hash,
                    requestJson = requestJson,
                    responseFormat = responseFormat.name,
                    responseBody = responseBody,
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    sessionKey = sessionKey,
                ),
            )
        }
    }

    /** Чистка протухших корреляций (вызывается периодически). */
    fun removeStale(ttlSeconds: Long) {
        val now = System.currentTimeMillis()
        pending.entries.removeIf { now - it.value.servedAtEpochMilliseconds > ttlSeconds * 1000 }
    }
}
