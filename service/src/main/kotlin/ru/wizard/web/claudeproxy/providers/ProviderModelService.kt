package ru.wizard.web.claudeproxy.providers

/**
 * Управление провайдерами и моделями: CRUD в БД + сид из YAML при старте.
 * После каждой мутации перезагружает реестр моделей.
 * API-ключ провайдера наружу не отдаётся — только превью (первые/последние символы).
 */
interface ProviderModelService {

    /** Провайдер для UI: без секрета, с превью ключа и списком моделей. */
    data class ProviderView(
        /** Идентификатор в БД. */
        val id: Long,
        /** Уникальное имя провайдера. */
        val name: String,
        /** openai | anthropic. */
        val type: String,
        /** Базовый URL API провайдера. */
        val baseUrl: String,
        /** Превью api-ключа (первые/последние символы). */
        val apiKeyPreview: String,
        /** Дополнительные заголовки исходящих запросов. */
        val extraHeaders: Map<String, String>,
        /** Видимость в GET /v1/models. */
        val exposed: Boolean,
        /** NULL = безлимит; информационный лимит на 5-часовое окно. */
        val limitWindowTokens: Long?,
        /** NULL = безлимит; информационный лимит на неделю. */
        val limitWeekTokens: Long?,
        /** NULL = безлимит; информационный лимит на 30 дней. */
        val limitMonthTokens: Long?,
        /** Канонический уровень → значение провайдера; пустая карта — маппер выключен. */
        val effortMapping: Map<String, String>,
        /** Оверрайды (ключи из ProviderSettingCatalog). */
        val settingOverrides: Map<String, String>,
        /** api_key | oauth. */
        val authType: String,
        /** OAuth: client_credentials | refresh_token. */
        val oauthGrant: String,
        /** OAuth client_id. */
        val oauthClientId: String,
        /** OAuth URL токен-эндпоинта. */
        val oauthTokenUrl: String,
        /** OAuth scope'ы через пробел. */
        val oauthScopes: String,
        /** '' = не задано | per_million | monthly. */
        val pricingMode: String,
        /** $ за 1М токенов (только при pricingMode=per_million). */
        val pricePerMillionTokens: Double?,
        /** $ за месяц подписки (только при pricingMode=monthly). */
        val priceMonthly: Double?,
        /** Прокси-эндпоинт провайдера (M31); null — прямое соединение. */
        val proxyName: String?,
        /** Модели провайдера. */
        val models: List<ModelView>,
        /** Создание, epoch millis. */
        val createdAt: Long,
        /** Последнее изменение, epoch millis. */
        val updatedAt: Long,
    )

    /** Модель провайдера для UI. */
    data class ModelView(
        /** Идентификатор в БД. */
        val id: Long,
        /** Провайдер модели. */
        val providerId: Long,
        /** Публичное имя, как его запрашивает клиент. */
        val publicName: String,
        /** Имя модели у провайдера. */
        val upstreamName: String,
        /** 'off' | 'effort' | 'budget'. */
        val reasoning: String,
        /** Провайдер ждёт max_completion_tokens вместо max_tokens. */
        val maxCompletionParam: Boolean,
        /** Меньше = выше приоритет маршрутизации. */
        val priority: Int,
        /** Видимость в GET /v1/models. */
        val exposed: Boolean,
    )

    /** Параметры создания/обновления провайдера. */
    data class ProviderRequest(
        /** Уникальное имя. */
        val name: String,
        /** openai | anthropic. */
        val type: String,
        /** Базовый URL API. */
        val baseUrl: String,
        /** null/пусто при обновлении = не менять; допускается ${ENV_VAR}. */
        val apiKey: String?,
        /** Дополнительные заголовки; при обновлении перезаписываются целиком. */
        val extraHeaders: Map<String, String>?,
        /** Видимость в GET /v1/models. */
        val exposed: Boolean?,
        /** Информационные лимиты токенов (null = не задан); при обновлении
         *  перезаписываются все три — UI присылает явные значения. */
        val limitWindowTokens: Long?,
        val limitWeekTokens: Long?,
        val limitMonthTokens: Long?,
        /** Маппер effort-уровней; при обновлении перезаписывается целиком. */
        val effortMapping: Map<String, String>?,
        /** Оверрайды; при обновлении перезаписываются целиком. */
        val settingOverrides: Map<String, String>?,
        /** api_key | oauth. */
        val authType: String?,
        /** OAuth: client_credentials | refresh_token. */
        val oauthGrant: String?,
        /** OAuth client_id. */
        val oauthClientId: String?,
        /** Допускается ${ENV_VAR}; при обновлении null/пусто = не менять. */
        val oauthClientSecret: String?,
        /** OAuth URL токен-эндпоинта. */
        val oauthTokenUrl: String?,
        /** OAuth scope'ы через пробел. */
        val oauthScopes: String?,
        /** Исходный refresh-токен (grant=refresh_token); ротация пишется в БД. */
        val oauthRefreshToken: String?,
        /** Тарификация: '' | per_million | monthly; цены XOR — ровно одна. */
        val pricingMode: String? = null,
        /** $ за 1М токенов (per_million). */
        val pricePerMillionTokens: Double? = null,
        /** $ за месяц подписки (monthly). */
        val priceMonthly: Double? = null,
        /** Прокси-эндпоинт (M31): null/'' — без прокси; имя должно существовать. */
        val proxyName: String? = null,
    )

    /** Параметры создания/обновления модели. */
    data class ModelRequest(
        /** Публичное имя для клиентов. */
        val publicName: String,
        /** Имя модели у провайдера. */
        val upstreamName: String,
        /** 'off' | 'effort' | 'budget'. */
        val reasoning: String,
        /** Провайдер ждёт max_completion_tokens вместо max_tokens. */
        val maxCompletionParam: Boolean,
        /** Меньше = выше приоритет; при повторимых ошибках — переключение дальше. */
        val priority: Int,
    )

    /** Все провайдеры с моделями для страницы «Модели». */
    suspend fun listProviders(): List<ProviderView>

    /** Создаёт провайдера. */
    suspend fun createProvider(request: ProviderRequest): ProviderView

    /** Обновляет провайдера. */
    suspend fun updateProvider(id: Long, request: ProviderRequest): ProviderView

    /** @return false, если провайдер не найден. Модели удаляются вместе с ним. */
    suspend fun deleteProvider(id: Long): Boolean

    /** Добавляет модель провайдеру. */
    suspend fun createModel(providerId: Long, request: ModelRequest): ModelView

    /** Обновляет модель. */
    suspend fun updateModel(id: Long, request: ModelRequest): ModelView

    /** @return false, если модель не найдена. */
    suspend fun deleteModel(id: Long): Boolean

    /** Видимость провайдера в выдаче GET /v1/models (маршрутизация не зависит). */
    suspend fun setProviderExposed(id: Long, exposed: Boolean): Boolean

    /** Видимость модели в выдаче GET /v1/models (маршрутизация не зависит). */
    suspend fun setModelExposed(id: Long, exposed: Boolean): Boolean
}
