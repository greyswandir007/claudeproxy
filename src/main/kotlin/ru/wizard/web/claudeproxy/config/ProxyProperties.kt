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
    var requestCache: RequestCache = RequestCache(),
    var serverEvent: ServerEvent = ServerEvent(),
    var backup: Backup = Backup(),
) {
    class Models(
        /** Белый список public-моделей; пусто = все модели всех провайдеров. */
        var allow: List<String> = emptyList(),
    )

    class ApiKeySeed(
        var name: String = "",
        var key: String = "",
    )

    /** Периодический бэкап файла SQLite (для PostgreSQL задача неактивна). */
    class Backup(
        var enabled: Boolean = false,
        /** Каталог для файлов бэкапов (создаётся при необходимости). */
        var directory: String = "backups",
        /** Сколько последних бэкапов хранить. */
        var retentionCount: Int = 7,
    )

    class Dashboard(var auth: Auth = Auth()) {
        /** Basic Auth дашборда и /api: включается, когда заданы оба поля. */
        class Auth(
            var username: String? = null,
            var password: String? = null,
        )
    }

    /** Кэш повторяющихся запросов (TTL — настройка провайдера REQUEST_CACHE_TTL_MS). */
    class RequestCache(
        /** Максимум строк в request_cache; лишние вытесняются по LRU. */
        var maxRows: Int = 1000,
    )

    /** Журнал событий сервера (страница «События»). */
    class ServerEvent(
        /** Ёмкость async-очереди записи; при переполнении события отбрасываются. */
        var queueCapacity: Int = 1000,
        /** Автоочистка server_event, дней (0 = не чистить). */
        var retentionDays: Long = 14,
        /** Минимальный уровень захвата логов в журнал: INFO | WARN | ERROR. */
        var minLevel: String = "WARN",
    )

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
