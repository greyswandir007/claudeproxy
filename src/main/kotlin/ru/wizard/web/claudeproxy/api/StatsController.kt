package ru.wizard.web.claudeproxy.api

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.wizard.web.claudeproxy.config.ProxyProperties
import ru.wizard.web.claudeproxy.routing.ModelRegistry
import ru.wizard.web.claudeproxy.usage.StatsService

/**
 * Эндпоинты /api — агрегаты статистики для дашборда.
 * В M3 без аутентификации (прокси слушает localhost); Basic Auth — M5.
 */
@RestController
class StatsController(
    private val statsService: StatsService,
    private val modelRegistry: ModelRegistry,
    private val proxyProperties: ProxyProperties,
    private val routeCircuitBreaker: ru.wizard.web.claudeproxy.routing.RouteCircuitBreaker,
) {

    @GetMapping("/api/summary")
    suspend fun summary(
        @RequestParam(defaultValue = "7d") range: String,
        @RequestParam(name = "key", required = false) clientKey: String?,
    ): StatsService.RangeSummary = statsService.summary(range, clientKey)

    @GetMapping("/api/by-model")
    suspend fun byModel(
        @RequestParam(defaultValue = "7d") range: String,
        @RequestParam(name = "key", required = false) clientKey: String?,
        @RequestParam(name = "from", required = false) fromMilliseconds: Long?,
        @RequestParam(name = "to", required = false) toMilliseconds: Long?,
    ): List<StatsService.GroupedUsage> =
        statsService.byModel(range, clientKey, fromMilliseconds, toMilliseconds)

    @GetMapping("/api/by-provider")
    suspend fun byProvider(
        @RequestParam(defaultValue = "7d") range: String,
        @RequestParam(name = "key", required = false) clientKey: String?,
        @RequestParam(name = "from", required = false) fromMilliseconds: Long?,
        @RequestParam(name = "to", required = false) toMilliseconds: Long?,
    ): List<StatsService.GroupedUsage> =
        statsService.byProvider(range, clientKey, fromMilliseconds, toMilliseconds)

    @GetMapping("/api/by-key")
    suspend fun byClientKey(
        @RequestParam(defaultValue = "7d") range: String,
    ): List<StatsService.GroupedUsage> = statsService.byClientKey(range)

    @GetMapping("/api/window")
    suspend fun currentWindow(
        @RequestParam(name = "key") clientKey: String,
    ): ResponseEntity<StatsService.WindowSummary> {
        val activeWindow = statsService.windowHistory(clientKey, limit = 1)
            .firstOrNull { window -> window.endsAtMilliseconds > System.currentTimeMillis() }
            ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(activeWindow)
    }

    @GetMapping("/api/windows")
    suspend fun windowHistory(
        @RequestParam(name = "key") clientKey: String,
        @RequestParam(defaultValue = "20") limit: Int,
    ): List<StatsService.WindowSummary> = statsService.windowHistory(clientKey, limit)

    /** История окон провайдеров — у каждого свой отсчёт 5-часового окна. */
    @GetMapping("/api/provider-windows")
    suspend fun providerWindowHistory(
        @RequestParam(defaultValue = "20") limit: Int,
    ): List<StatsService.ProviderWindowSummary> = statsService.providerWindowHistory(limit)

    @GetMapping("/api/timeline")
    suspend fun timeline(
        @RequestParam(defaultValue = "hour") bucket: String,
        @RequestParam(name = "from", required = false) fromMilliseconds: Long?,
        @RequestParam(name = "to", required = false) toMilliseconds: Long?,
        @RequestParam(name = "key", required = false) clientKey: String?,
        @RequestParam(defaultValue = "total") group: String,
    ): Any =
        if (group == "total") {
            statsService.timeline(bucket, fromMilliseconds, toMilliseconds, clientKey)
        } else {
            statsService.groupedTimeline(bucket, fromMilliseconds, toMilliseconds, clientKey, group)
        }

    /** Границы 5-часовых окон ключа в диапазоне — отметки на графике дня. */
    @GetMapping("/api/window-boundaries")
    suspend fun windowBoundaries(
        @RequestParam(name = "key") clientKey: String,
        @RequestParam(name = "from") fromMilliseconds: Long,
        @RequestParam(name = "to") toMilliseconds: Long,
    ): List<StatsService.WindowBoundary> =
        statsService.windowBoundaries(clientKey, fromMilliseconds, toMilliseconds)

    /** Границы 5-часовых окон провайдеров в диапазоне — дорожки на графике дня. */
    @GetMapping("/api/provider-window-boundaries")
    suspend fun providerWindowBoundaries(
        @RequestParam(name = "from") fromMilliseconds: Long,
        @RequestParam(name = "to") toMilliseconds: Long,
    ): List<StatsService.ProviderWindowBoundary> =
        statsService.providerWindowBoundaries(fromMilliseconds, toMilliseconds)

    /** Выработка информационных лимитов провайдеров (заданные категории). */
    @GetMapping("/api/provider-limits")
    suspend fun providerLimits(): List<StatsService.ProviderLimitUsage> =
        statsService.providerLimitUsage()

    /** Здоровье маршрутов: ошибки/латентность p50-p95 + лента переключений. */
    @GetMapping("/api/fallback-report")
    suspend fun fallbackReport(
        @RequestParam(defaultValue = "7d") range: String,
        @RequestParam(name = "key", required = false) clientKey: String?,
    ): StatsService.FallbackReport = statsService.fallbackReport(range, clientKey)

    /** Полная расшифровка одной ошибки маршрутизации по идентификатору события. */
    @GetMapping("/api/stats/errors/{eventId}")
    suspend fun errorDetail(@PathVariable eventId: Long): ResponseEntity<Map<String, Any?>> {
        val errorDetail = statsService.errorDetail(eventId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(mapOf("eventId" to errorDetail.eventId, "errorDetail" to errorDetail.detail))
    }

    /** Тарификация провайдеров: цены (заданная + расчётная) и расход за 7/30 дней. */
    @GetMapping("/api/provider-costs")
    suspend fun providerCosts(): List<StatsService.ProviderCost> = statsService.providerCosts()

    /** Активные кулдауны провайдеров (circuit breaker). */
    @GetMapping("/api/route-cooldowns")
    fun routeCooldowns(): List<ru.wizard.web.claudeproxy.routing.RouteCircuitBreaker.CooldownState> =
        routeCircuitBreaker.activeCooldowns()

    /** Провайдеры и модели из реестра (read-only, без ключей провайдеров). */
    @GetMapping("/api/config")
    fun configuration(): Map<String, Any?> {
        val routesByProvider = modelRegistry.routes().groupBy { it.provider.name }
        return mapOf(
            "windowHours" to proxyProperties.windowHours,
            "exposedModels" to modelRegistry.exposedModels(),
            "providers" to routesByProvider.map { (providerName, providerRoutes) ->
                mapOf(
                    "name" to providerName,
                    "type" to providerRoutes.first().provider.type,
                    "baseUrl" to providerRoutes.first().provider.baseUrl,
                    "models" to providerRoutes.map { route ->
                        mapOf(
                            "public" to route.mapping.publicName,
                            "upstream" to route.mapping.upstreamName,
                        )
                    },
                )
            },
        )
    }
}
