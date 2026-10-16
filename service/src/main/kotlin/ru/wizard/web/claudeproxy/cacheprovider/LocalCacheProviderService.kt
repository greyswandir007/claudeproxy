package ru.wizard.web.claudeproxy.cacheprovider

import ru.wizard.web.claudeproxy.proxy.cache.RequestCacheService

/**
 * Встроенный кэш-провайдер local: обёртка над request-кэшем прокси.
 * Точное совпадение хэша = confidence 1.0; single-flight и TTL живут
 * внутри request-кэша и наружу не торчат.
 */
class LocalCacheProviderService(
    private val requestCacheService: RequestCacheService,
) : CacheProviderService {

    override val name: String = CacheProviderService.LOCAL_NAME

    override suspend fun lookup(
        request: CacheProviderService.LookupRequest,
    ): CacheProviderService.LookupResult? {
        val cacheKey = RequestCacheService.RequestCacheKey(
            hash = request.requestHash,
            upstreamPath = request.path,
            canonicalRequest = request.requestJson,
            model = request.model,
        )
        val cached = requestCacheService.lookup(cacheKey) ?: return null
        return CacheProviderService.LookupResult(
            answerId = "${cached.responseFormat}:${cacheKey.hash.take(16)}",
            responseFormat = cached.responseFormat.name,
            responseBody = cached.responseBody,
            confidence = 1.0,
            inputTokens = cached.inputTokens,
            outputTokens = cached.outputTokens,
        )
    }

    override fun storeAsync(request: CacheProviderService.StoreRequest) {
        // local-кэш пишет сам пайплайн (TTL/оврайды провайдера) — дубль
        // через общий store не нужен
    }

    override suspend fun feedback(answerId: String, positive: Boolean) {
        // точные повторы не оцениваются — no-op по спеке
    }

    override suspend fun status(): Map<String, Any?> {
        val diagnostics = requestCacheService.diagnostics()
        return linkedMapOf(
            "uptime_s" to 0L,
            "counters" to linkedMapOf(
                "lookups" to diagnostics.lookups,
                "hits_exact" to diagnostics.hits,
                "hits_probable" to 0L,
                "stores" to diagnostics.stored,
                "feedback_positive" to 0L,
                "feedback_negative" to 0L,
            ),
            "latency_ms" to emptyMap<String, Any>(),
            "extra" to linkedMapOf(
                "misses" to diagnostics.misses,
                "missesExpired" to diagnostics.missesExpired,
            ),
        )
    }
}
