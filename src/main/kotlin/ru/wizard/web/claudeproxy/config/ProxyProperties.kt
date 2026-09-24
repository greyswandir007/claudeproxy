package ru.wizard.web.claudeproxy.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Конфигурация claudeproxy (префикс `claudeproxy`), см. config/application.example.yml.
 */
@ConfigurationProperties(prefix = "claudeproxy")
class ProxyProperties(
    /** Длительность окна использования, часов (на каждый клиентский ключ). */
    var windowHours: Int = 5,
    /** Автоочистка usage_event, дней (0 = не чистить). */
    var retentionDays: Long = 365,
    /** Сид-ключи: вносятся в БД при старте, если имени ещё нет. */
    var apiKeys: List<ApiKeySeed> = emptyList(),
    var models: Models = Models(),
    var providers: List<Provider> = emptyList(),
    var dashboard: Dashboard = Dashboard(),
) {
    class Models(
        /** Белый список public-моделей; пусто = все модели всех провайдеров. */
        var allow: List<String> = emptyList(),
    )

    class ApiKeySeed(
        var name: String = "",
        var key: String = "",
    )

    class Dashboard(var auth: Auth = Auth()) {
        /** Basic Auth дашборда и /api: включается, когда заданы оба поля. */
        class Auth(
            var username: String? = null,
            var password: String? = null,
        )
    }

    class Provider(
        var name: String = "",
        /** anthropic (pass-through) | openai (перевод протокола). */
        var type: String = "anthropic",
        var baseUrl: String = "",
        var apiKey: String = "",
        var extraHeaders: Map<String, String> = emptyMap(),
        /** Информационные лимиты токенов (null = не задан); только для дашбордов. */
        var limitWindowTokens: Long? = null,
        var limitWeekTokens: Long? = null,
        var limitMonthTokens: Long? = null,
        var models: List<ModelMapping> = emptyList(),
    )

    class ModelMapping(
        /** Имя модели, которое видит клиент. */
        var `public`: String = "",
        /** Имя модели у провайдера. */
        var upstream: String = "",
        /** map | off — переводить ли thinking в reasoning_effort (openai). */
        var reasoning: String = "map",
        /** true => шлём max_completion_tokens вместо max_tokens (o-серия). */
        var maxCompletionParam: Boolean = false,
    )
}
