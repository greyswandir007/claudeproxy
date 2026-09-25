package ru.wizard.web.claudeproxy.providers.impl

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.core.env.Environment
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import ru.wizard.web.claudeproxy.config.EnvironmentReferenceResolver
import ru.wizard.web.claudeproxy.db.DatabaseProvider
import ru.wizard.web.claudeproxy.providers.ProviderOAuthTokenService
import ru.wizard.web.claudeproxy.proxy.ApiError
import ru.wizard.web.claudeproxy.routing.ModelRegistry
import java.time.Duration

/**
 * Реализация ProviderOAuthTokenService: in-memory кэш с Mutex на провайдера,
 * персист в provider_oauth_token. Конфиг (client_id/secret, token_url, grant,
 * refresh-токен) читается из БД при каждом обновлении — правки применяются
 * без перезапуска; секреты поддерживают ${ENV_VAR}.
 */
@Service
class WebClientProviderOAuthTokenService(
    private val webClient: WebClient,
    private val databaseProvider: DatabaseProvider,
    private val jdbcTemplate: JdbcTemplate,
    private val environment: Environment,
    private val objectMapper: ObjectMapper,
) : ProviderOAuthTokenService {
    private val logger = KotlinLogging.logger {}

    private data class CachedToken(
        val accessToken: String,
        val expiresAtMilliseconds: Long,
    )

    private data class OAuthConfig(
        val grant: String,
        val clientId: String,
        val clientSecret: String,
        val tokenUrl: String,
        val scopes: String,
        val refreshToken: String,
    )

    private val cachedTokens = HashMap<Long, CachedToken>()
    private val refreshMutexes = HashMap<Long, Mutex>()

    override suspend fun accessToken(provider: ModelRegistry.ProviderInfo): String? {
        if (provider.authType != "oauth") return null
        val now = System.currentTimeMillis()
        cachedTokens[provider.id]?.let { cached ->
            if (cached.expiresAtMilliseconds - now > EXPIRY_MARGIN_MILLISECONDS) {
                return cached.accessToken
            }
        }
        val mutex = synchronized(refreshMutexes) { refreshMutexes.getOrPut(provider.id) { Mutex() } }
        return mutex.withLock {
            // пока ждали мьютекс — кто-то мог обновить
            cachedTokens[provider.id]?.let { cached ->
                if (cached.expiresAtMilliseconds - System.currentTimeMillis() > EXPIRY_MARGIN_MILLISECONDS) {
                    return@withLock cached.accessToken
                }
            }
            runCatching { refreshToken(provider) }
                .onFailure { error ->
                    logger.error(error) {
                        "OAuth token refresh failed for provider '${provider.name}'"
                    }
                }
                .getOrNull()
        }
    }

    private suspend fun refreshToken(provider: ModelRegistry.ProviderInfo): String {
        val config = loadConfig(provider.id)
            ?: throw ApiError(
                HttpStatus.BAD_REQUEST,
                "invalid_request_error",
                "Провайдер '${provider.name}': не задана конфигурация OAuth",
            )
        val formValues = LinkedHashMap<String, String>()
        formValues["grant_type"] = config.grant
        formValues["client_id"] = config.clientId
        formValues["client_secret"] = config.clientSecret
        if (config.scopes.isNotBlank()) formValues["scope"] = config.scopes
        if (config.grant == GRANT_REFRESH_TOKEN) {
            formValues["refresh_token"] = config.refreshToken
        }
        val response = webClient.post()
            .uri(config.tokenUrl)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .accept(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromFormData(
                formValues.entries.fold(org.springframework.util.LinkedMultiValueMap<String, String>()) { map, entry ->
                    map.add(entry.key, entry.value)
                    map
                },
            ))
            .retrieve()
            .onStatus({ !it.is2xxSuccessful }) { clientResponse ->
                clientResponse.toEntity(String::class.java).map { entity ->
                    ApiError(
                        HttpStatus.BAD_GATEWAY,
                        "api_error",
                        "OAuth token endpoint вернул HTTP ${entity.statusCode.value()}: " +
                            "${(entity.body ?: "").take(200)}",
                    )
                }
            }
            .bodyToMono(String::class.java)
            .awaitSingle()
        val responseNode = runCatching { objectMapper.readTree(response) }.getOrNull()
            ?: throw ApiError(HttpStatus.BAD_GATEWAY, "api_error", "Некорректный JSON от token endpoint")
        val accessToken = responseNode.path("access_token").takeIf { it.isTextual }?.asText()
            ?: throw ApiError(HttpStatus.BAD_GATEWAY, "api_error", "token endpoint не вернул access_token")
        val expiresInSeconds = responseNode.path("expires_in").takeIf { it.isNumber }?.asLong()
            ?: DEFAULT_EXPIRES_IN_SECONDS
        // rotation: новый refresh-токен, если endpoint его прислал
        val rotatedRefreshToken = responseNode.path("refresh_token")
            .takeIf { it.isTextual && it.asText().isNotBlank() }?.asText()
        val expiresAt = System.currentTimeMillis() + expiresInSeconds * 1000
        val cached = CachedToken(accessToken, expiresAt)
        cachedTokens[provider.id] = cached
        persistToken(provider.id, accessToken, rotatedRefreshToken, expiresAt)
        logger.info {
            "OAuth token refreshed for provider '${provider.name}' " +
                "(expires in ${expiresInSeconds}s, grant=${config.grant})"
        }
        return accessToken
    }

    private suspend fun loadConfig(providerId: Long): OAuthConfig? =
        databaseProvider.execute {
            val rows = ArrayList<OAuthConfig>()
            jdbcTemplate.query(
                """SELECT oauth_grant, oauth_client_id, oauth_client_secret,
                          oauth_token_url, oauth_scopes, oauth_refresh_token
                   FROM provider WHERE id = ?""",
                { resultSet ->
                    val tokenUrl = EnvironmentReferenceResolver.resolve(
                        environment,
                        resultSet.getString("oauth_token_url"),
                    )
                    if (tokenUrl.isBlank()) return@query
                    rows.add(
                        OAuthConfig(
                            grant = resultSet.getString("oauth_grant").ifBlank { GRANT_CLIENT_CREDENTIALS },
                            clientId = EnvironmentReferenceResolver.resolve(
                                environment,
                                resultSet.getString("oauth_client_id"),
                            ),
                            clientSecret = EnvironmentReferenceResolver.resolve(
                                environment,
                                resultSet.getString("oauth_client_secret"),
                            ),
                            tokenUrl = tokenUrl,
                            scopes = resultSet.getString("oauth_scopes"),
                            refreshToken = EnvironmentReferenceResolver.resolve(
                                environment,
                                resultSet.getString("oauth_refresh_token"),
                            ),
                        ),
                    )
                },
                providerId,
            )
            rows.firstOrNull()
        }

    private suspend fun persistToken(
        providerId: Long,
        accessToken: String,
        rotatedRefreshToken: String?,
        expiresAtMilliseconds: Long,
    ) {
        databaseProvider.execute {
            if (rotatedRefreshToken != null) {
                jdbcTemplate.update(
                    """INSERT INTO provider_oauth_token (provider_id, access_token, refresh_token, expires_at, updated_at)
                       VALUES (?,?,?,?,?)
                       ON CONFLICT(provider_id) DO UPDATE SET
                         access_token = excluded.access_token,
                         refresh_token = excluded.refresh_token,
                         expires_at = excluded.expires_at,
                         updated_at = excluded.updated_at""",
                    providerId,
                    accessToken,
                    rotatedRefreshToken,
                    expiresAtMilliseconds,
                    System.currentTimeMillis(),
                )
                // ротация refresh-токена — сохраняем его и в конфиг провайдера
                jdbcTemplate.update(
                    "UPDATE provider SET oauth_refresh_token = ? WHERE id = ? AND auth_type = 'oauth'",
                    rotatedRefreshToken,
                    providerId,
                )
            } else {
                jdbcTemplate.update(
                    """INSERT INTO provider_oauth_token (provider_id, access_token, expires_at, updated_at)
                       VALUES (?,?,?,?)
                       ON CONFLICT(provider_id) DO UPDATE SET
                         access_token = excluded.access_token,
                         expires_at = excluded.expires_at,
                         updated_at = excluded.updated_at""",
                    providerId,
                    accessToken,
                    expiresAtMilliseconds,
                    System.currentTimeMillis(),
                )
            }
        }
    }

    private companion object {
        const val GRANT_CLIENT_CREDENTIALS = "client_credentials"
        const val GRANT_REFRESH_TOKEN = "refresh_token"
        const val DEFAULT_EXPIRES_IN_SECONDS = 3600L

        /** Обновляем заранее, чтобы не отдавать клиенту ошибку на грани истечения. */
        const val EXPIRY_MARGIN_MILLISECONDS = 300_000L
    }
}
