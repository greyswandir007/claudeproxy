package ru.wizard.web.claudeproxy.api

import org.springframework.web.bind.annotation.GetMapping
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
    ): List<StatsService.GroupedUsage> = statsService.byModel(range, clientKey)

    @GetMapping("/api/by-provider")
    suspend fun byProvider(
        @RequestParam(defaultValue = "7d") range: String,
        @RequestParam(name = "key", required = false) clientKey: String?,
    ): List<StatsService.GroupedUsage> = statsService.byProvider(range, clientKey)

    @GetMapping("/api/by-key")
    suspend fun byClientKey(
        @RequestParam(defaultValue = "7d") range: String,
    ): List<StatsService.GroupedUsage> = statsService.byClientKey(range)

    @GetMapping("/api/window")
    suspend fun currentWindow(
        @RequestParam(name = "key") clientKey: String,
    ): StatsService.WindowSummary? =
        statsService.windowHistory(clientKey, limit = 1).firstOrNull { window ->
            window.endsAtMilliseconds > System.currentTimeMillis()
        }

    @GetMapping("/api/windows")
    suspend fun windowHistory(
        @RequestParam(name = "key") clientKey: String,
        @RequestParam(defaultValue = "20") limit: Int,
    ): List<StatsService.WindowSummary> = statsService.windowHistory(clientKey, limit)

    @GetMapping("/api/timeline")
    suspend fun timeline(
        @RequestParam(defaultValue = "hour") bucket: String,
        @RequestParam(name = "from", required = false) fromMilliseconds: Long?,
        @RequestParam(name = "to", required = false) toMilliseconds: Long?,
        @RequestParam(name = "key", required = false) clientKey: String?,
    ): List<StatsService.TimelinePoint> =
        statsService.timeline(bucket, fromMilliseconds, toMilliseconds, clientKey)

    /** Провайдеры и модели (read-only, без ключей провайдеров). */
    @GetMapping("/api/config")
    fun configuration(): Map<String, Any?> = mapOf(
        "windowHours" to proxyProperties.windowHours,
        "exposedModels" to modelRegistry.exposedModels(),
        "providers" to proxyProperties.providers.map { provider ->
            mapOf(
                "name" to provider.name,
                "type" to provider.type,
                "baseUrl" to provider.baseUrl,
                "models" to provider.models.map { modelMapping ->
                    mapOf(
                        "public" to modelMapping.`public`,
                        "upstream" to modelMapping.upstream,
                    )
                },
            )
        },
    )
}
