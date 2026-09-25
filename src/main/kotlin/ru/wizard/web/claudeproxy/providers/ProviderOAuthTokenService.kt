package ru.wizard.web.claudeproxy.providers

import ru.wizard.web.claudeproxy.routing.ModelRegistry

/**
 * Access-токены OAuth-провайдеров: получение/обновление по grant-типу
 * (client_credentials | refresh_token), кэш в памяти + provider_oauth_token.
 */
interface ProviderOAuthTokenService {

    /**
     * Валидный access-токен провайдера; обновляет заранее (запас до истечения).
     * @return null — провайдер не oauth или токен получить не удалось.
     */
    suspend fun accessToken(provider: ModelRegistry.ProviderInfo): String?
}
