package ru.wizard.web.claudeproxy.providers.impl

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.core.env.Environment
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import ru.wizard.web.claudeproxy.config.EnvironmentReferenceResolver
import ru.wizard.web.claudeproxy.db.DatabaseProvider
import ru.wizard.web.claudeproxy.providers.ProviderModelDiscoveryService
import ru.wizard.web.claudeproxy.providers.UpstreamWebClientFactory
import ru.wizard.web.claudeproxy.proxy.ApiError

/**
 * Реализация ProviderModelDiscoveryService на WebClient. Оба типа провайдеров
 * отдают список моделями в форме {"data":[{"id": "..."}]}.
 */
@Service
class WebClientProviderModelDiscoveryService(
    private val webClientFactory: UpstreamWebClientFactory,
    private val databaseProvider: DatabaseProvider,
    private val jdbcTemplate: JdbcTemplate,
    private val environment: Environment,
    private val objectMapper: ObjectMapper,
) : ProviderModelDiscoveryService {
    private val logger = KotlinLogging.logger {}

    override suspend fun discover(request: ProviderModelDiscoveryService.DiscoveryRequest): List<String> {
        if (request.type != "anthropic" && request.type != "openai") {
            throw ApiError(HttpStatus.BAD_REQUEST, "invalid_request_error", "type: anthropic или openai")
        }
        if (request.baseUrl.isBlank()) {
            throw ApiError(HttpStatus.BAD_REQUEST, "invalid_request_error", "baseUrl обязателен")
        }
        val apiKey = resolveApiKey(request)
        val proxyName = resolveProxyName(request)
        val requestSpecification = webClientFactory.webClient(proxyName).get()
            .uri(
                request.baseUrl.trimEnd('/') +
                    if (request.type == "anthropic") "/v1/models" else "/models",
            )
            .accept(MediaType.APPLICATION_JSON)
        if (request.type == "anthropic") {
            requestSpecification.header("x-api-key", apiKey)
            requestSpecification.header("anthropic-version", "2023-06-01")
        } else {
            requestSpecification.header(HttpHeaders.AUTHORIZATION, "Bearer $apiKey")
        }
        val responseBody = requestSpecification
            .retrieve()
            .onStatus({ !it.is2xxSuccessful }) { response ->
                response.toEntity(String::class.java).map { entity ->
                    ApiError(
                        HttpStatus.BAD_GATEWAY,
                        "api_error",
                        "Провайдер вернул HTTP ${entity.statusCode.value()}: " +
                            "${(entity.body ?: "").take(200)}",
                    )
                }
            }
            .bodyToMono(String::class.java)
            .awaitSingle()
        val models = parseModelIdentifiers(responseBody)
        logger.info { "Discovery ${request.type} ${request.baseUrl}: found ${models.size} models" }
        return models
    }

    /** Прокси discovery берёт у провайдера (если провайдер указан), M31. */
    private suspend fun resolveProxyName(request: ProviderModelDiscoveryService.DiscoveryRequest): String? {
        if (request.providerId == null) return null
        return databaseProvider.execute {
            jdbcTemplate.query(
                "SELECT proxy_name FROM provider WHERE id = ?",
                { resultSet, _ -> resultSet.getString(1)?.takeIf { it.isNotBlank() } },
                request.providerId,
            ).firstOrNull()
        }
    }

    private suspend fun resolveApiKey(request: ProviderModelDiscoveryService.DiscoveryRequest): String {
        if (!request.apiKey.isNullOrBlank()) {
            return EnvironmentReferenceResolver.resolve(environment, request.apiKey)
        }
        if (request.providerId == null) return ""
        return databaseProvider.execute {
            jdbcTemplate.query(
                "SELECT api_key FROM provider WHERE id = ?",
                { resultSet, _ -> resultSet.getString(1) },
                request.providerId,
            ).firstOrNull()
        }?.let { EnvironmentReferenceResolver.resolve(environment, it) } ?: ""
    }

    private fun parseModelIdentifiers(responseBody: String): List<String> {
        val root = runCatching { objectMapper.readTree(responseBody) }.getOrNull()
            ?: throw ApiError(HttpStatus.BAD_GATEWAY, "api_error", "Некорректный JSON от провайдера")
        return root.path("data")
            .mapNotNull { it.path("id").takeIf { identifier -> identifier.isTextual }?.asText() }
            .distinct()
            .sorted()
    }
}
