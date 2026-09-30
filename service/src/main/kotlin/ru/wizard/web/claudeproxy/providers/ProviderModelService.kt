package ru.wizard.web.claudeproxy.providers

/**
 * Управление провайдерами и моделями: CRUD в БД + сид из YAML при старте.
 * После каждой мутации перезагружает реестр моделей.
 * API-ключ провайдера наружу не отдаётся — только превью (первые/последние символы).
 */
interface ProviderModelService {

    data class ProviderView(
        val id: Long,
        val name: String,
        val type: String,
        val baseUrl: String,
        val apiKeyPreview: String,
        val extraHeaders: Map<String, String>,
        val exposed: Boolean,
        val limitWindowTokens: Long?,
        val limitWeekTokens: Long?,
        val limitMonthTokens: Long?,
        /** Канонический уровень → значение провайдера; пустая карта — маппер выключен. */
        val effortMapping: Map<String, String>,
        /** Оверрайды (ключи из ProviderSettingCatalog). */
        val settingOverrides: Map<String, String>,
        /** api_key | oauth. */
        val authType: String,
        val oauthGrant: String,
        val oauthClientId: String,
        val oauthTokenUrl: String,
        val oauthScopes: String,
        /** '' = не задано | per_million | monthly. */
        val pricingMode: String,
        /** $ за 1М токенов (только при pricingMode=per_million). */
        val pricePerMillionTokens: Double?,
        /** $ за месяц подписки (только при pricingMode=monthly). */
        val priceMonthly: Double?,
        /** Прокси-эндпоинт провайдера (M31); null — прямое соединение. */
        val proxyName: String?,
        val models: List<ModelView>,
        val createdAt: Long,
        val updatedAt: Long,
    )

    data class ModelView(
        val id: Long,
        val providerId: Long,
        val publicName: String,
        val upstreamName: String,
        val reasoning: String,
        val maxCompletionParam: Boolean,
        val priority: Int,
        val exposed: Boolean,
    )

    data class ProviderRequest(
        val name: String,
        val type: String,
        val baseUrl: String,
        /** null/пусто при обновлении = не менять; допускается ${ENV_VAR}. */
        val apiKey: String?,
        val extraHeaders: Map<String, String>?,
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
        val oauthClientId: String?,
        /** Допускается ${ENV_VAR}; при обновлении null/пусто = не менять. */
        val oauthClientSecret: String?,
        val oauthTokenUrl: String?,
        val oauthScopes: String?,
        /** Исходный refresh-токен (grant=refresh_token); ротация пишется в БД. */
        val oauthRefreshToken: String?,
        /** Тарификация: '' | per_million | monthly; цены XOR — ровно одна. */
        val pricingMode: String? = null,
        val pricePerMillionTokens: Double? = null,
        val priceMonthly: Double? = null,
        /** Прокси-эндпоинт (M31): null/'' — без прокси; имя должно существовать. */
        val proxyName: String? = null,
    )

    data class ModelRequest(
        val publicName: String,
        val upstreamName: String,
        val reasoning: String,
        val maxCompletionParam: Boolean,
        /** Меньше = выше приоритет; при повторимых ошибках — переключение дальше. */
        val priority: Int,
    )

    suspend fun listProviders(): List<ProviderView>

    suspend fun createProvider(request: ProviderRequest): ProviderView

    suspend fun updateProvider(id: Long, request: ProviderRequest): ProviderView

    /** @return false, если провайдер не найден. Модели удаляются вместе с ним. */
    suspend fun deleteProvider(id: Long): Boolean

    suspend fun createModel(providerId: Long, request: ModelRequest): ModelView

    suspend fun updateModel(id: Long, request: ModelRequest): ModelView

    /** @return false, если модель не найдена. */
    suspend fun deleteModel(id: Long): Boolean

    /** Видимость провайдера в выдаче GET /v1/models (маршрутизация не зависит). */
    suspend fun setProviderExposed(id: Long, exposed: Boolean): Boolean

    /** Видимость модели в выдаче GET /v1/models (маршрутизация не зависит). */
    suspend fun setModelExposed(id: Long, exposed: Boolean): Boolean
}
