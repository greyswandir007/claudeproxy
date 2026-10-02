package ru.wizard.web.claudeproxy.providers

/**
 * Запрос списка моделей у провайдера (openai: GET /models с Bearer;
 * anthropic: GET /v1/models с x-api-key) — для выбора upstream-моделей в UI.
 */
interface ProviderModelDiscoveryService {

    /** Параметры запроса списка моделей у провайдера. */
    data class DiscoveryRequest(
        /** Тип провайдера: openai | anthropic. */
        val type: String,
        /** Базовый URL провайдера. */
        val baseUrl: String,
        /** Литерал или ${ENV_VAR}; если пусто — берётся сохранённый ключ провайдера. */
        val apiKey: String?,
        /** Идентификатор провайдера в БД; null — запрос ещё не сохранённого. */
        val providerId: Long?,
    )

    /** Имена моделей провайдера (upstream-имена, без фильтра exposed). */
    suspend fun discover(request: DiscoveryRequest): List<String>
}
