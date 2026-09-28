package ru.wizard.web.claudeproxy.providers

/**
 * Запрос списка моделей у провайдера (openai: GET /models с Bearer;
 * anthropic: GET /v1/models с x-api-key) — для выбора upstream-моделей в UI.
 */
interface ProviderModelDiscoveryService {

    data class DiscoveryRequest(
        val type: String,
        val baseUrl: String,
        /** Литерал или ${ENV_VAR}; если пусто — берётся сохранённый ключ провайдера. */
        val apiKey: String?,
        val providerId: Long?,
    )

    suspend fun discover(request: DiscoveryRequest): List<String>
}
