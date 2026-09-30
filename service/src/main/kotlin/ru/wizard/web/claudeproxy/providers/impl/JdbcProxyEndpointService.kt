package ru.wizard.web.claudeproxy.providers.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI
import java.sql.ResultSet
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import ru.wizard.web.claudeproxy.db.DatabaseProvider
import ru.wizard.web.claudeproxy.providers.ProxyEndpointService
import ru.wizard.web.claudeproxy.providers.ProxyEndpointService.ProxyEndpointRequest
import ru.wizard.web.claudeproxy.providers.ProxyEndpointService.ProxyEndpointView
import ru.wizard.web.claudeproxy.providers.UpstreamWebClientFactory
import ru.wizard.web.claudeproxy.proxy.ApiError

/**
 * JDBC-реализация CRUD прокси-эндпоинтов (M31). После мутаций сбрасывает
 * кэш WebClient'ов фабрики; креды не логируются и не отдаются наружу.
 */
@Service
@org.springframework.context.annotation.DependsOn("databaseMigrationRunner")
class JdbcProxyEndpointService(
    private val jdbcTemplate: JdbcTemplate,
    private val databaseProvider: DatabaseProvider,
    private val webClientFactory: UpstreamWebClientFactory,
) : ProxyEndpointService {

    private val logger = KotlinLogging.logger {}

    override suspend fun listProxies(): List<ProxyEndpointView> =
        databaseProvider.execute {
            jdbcTemplate.query(SELECT_ALL_SQL) { resultSet, _ -> mapRow(resultSet) }
                .map { row -> row.toView(providerNames(row.name)) }
        }

    override suspend fun createProxy(request: ProxyEndpointRequest): ProxyEndpointView {
        validate(request, existingId = null)
        val name = request.name!!.trim()
        val now = System.currentTimeMillis()
        databaseProvider.execute {
            jdbcTemplate.update(
                INSERT_SQL,
                name,
                request.type!!.trim(),
                request.host!!.trim(),
                request.port!!,
                request.username?.trim()?.takeIf { it.isNotEmpty() },
                request.password?.trim()?.takeIf { it.isNotEmpty() },
                if (request.enabled == false) 0 else 1,
                now,
                now,
            )
        }
        webClientFactory.invalidate()
        return loadView(name)
    }

    override suspend fun updateProxy(id: Long, request: ProxyEndpointRequest): ProxyEndpointView {
        val current = loadRow(id)
        val merged = ProxyEndpointRequest(
            name = request.name?.trim()?.takeIf { it.isNotEmpty() } ?: current.name,
            type = request.type?.trim()?.takeIf { it.isNotEmpty() } ?: current.type,
            host = request.host?.trim()?.takeIf { it.isNotEmpty() } ?: current.host,
            port = request.port ?: current.port,
            username = request.username ?: current.username,
            // пароль: null/пусто = не менять (очистить нельзя, только перезаписать)
            password = request.password?.trim()?.takeIf { it.isNotEmpty() } ?: current.password,
            enabled = request.enabled ?: current.enabled,
        )
        validate(merged, existingId = id)
        databaseProvider.execute {
            jdbcTemplate.update(
                UPDATE_SQL,
                merged.name,
                merged.type,
                merged.host,
                merged.port,
                merged.username?.trim()?.takeIf { it.isNotEmpty() },
                merged.password?.trim()?.takeIf { it.isNotEmpty() },
                if (merged.enabled == false) 0 else 1,
                System.currentTimeMillis(),
                id,
            )
        }
        webClientFactory.invalidate()
        // после validate() имя гарантированно непустое
        return loadView(merged.name!!)
    }

    override suspend fun deleteProxy(id: Long) {
        val endpoint = loadRow(id)
        val names = databaseProvider.execute { providerNames(endpoint.name) }
        if (names.isNotEmpty()) {
            throw badRequest("Прокси используется провайдерами: ${names.joinToString(", ")}")
        }
        databaseProvider.execute { jdbcTemplate.update(DELETE_SQL, id) }
        webClientFactory.invalidate()
    }

    override suspend fun checkProxy(id: Long, testUrl: String?): ProxyEndpointView {
        val endpoint = loadRow(id)
        val targetUrl = testUrl?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_CHECK_URL
        val config = ProxyEndpointService.ProxyEndpointConfig(
            name = endpoint.name,
            type = endpoint.type,
            host = endpoint.host,
            port = endpoint.port,
            username = endpoint.username,
            passwordReference = endpoint.password,
        )
        val startedAt = System.currentTimeMillis()
        val status = performCheck(webClientFactory.proxiedClient(config), targetUrl, startedAt)
        databaseProvider.execute {
            jdbcTemplate.update(UPDATE_CHECK_SQL, status, System.currentTimeMillis(), id)
        }
        logger.info { "Proxy '${endpoint.name}' check: $status" }
        return loadView(endpoint.name)
    }

    override suspend fun configByName(name: String): ProxyEndpointService.ProxyEndpointConfig? {
        val endpoint = databaseProvider.execute {
            jdbcTemplate.query(SELECT_BY_NAME_SQL, { resultSet, _ -> mapRow(resultSet) }, name)
                .firstOrNull()
        } ?: return null
        if (!endpoint.enabled) return null
        return ProxyEndpointService.ProxyEndpointConfig(
            name = endpoint.name,
            type = endpoint.type,
            host = endpoint.host,
            port = endpoint.port,
            username = endpoint.username,
            passwordReference = endpoint.password,
        )
    }

    /** Проверка одного GET через прокси; статус в формате OK/FAIL. */
    private suspend fun performCheck(client: WebClient, targetUrl: String, startedAt: Long): String {
        return try {
            withTimeout(CHECK_TIMEOUT_MILLISECONDS) {
                val response = client.get()
                    .uri(URI.create(targetUrl))
                    .retrieve()
                    .toBodilessEntity()
                    .awaitSingle()
                "OK ${response.statusCode.value()} ${System.currentTimeMillis() - startedAt}ms"
            }
        } catch (timeout: TimeoutCancellationException) {
            "FAIL timeout after ${CHECK_TIMEOUT_MILLISECONDS / 1000}s"
        } catch (exception: Exception) {
            "FAIL ${exception.message?.take(160) ?: exception.javaClass.simpleName}"
        }
    }

    private suspend fun validate(request: ProxyEndpointRequest, existingId: Long?) {
        val errors = ArrayList<String>()
        val name = request.name?.trim().orEmpty()
        if (name.isEmpty()) {
            errors.add("name: обязательное поле")
        }
        if (request.type?.trim() !in SUPPORTED_TYPES) {
            errors.add("type: ожидается один из ${SUPPORTED_TYPES.joinToString(" / ")}")
        }
        if (request.host?.trim().orEmpty().isEmpty()) {
            errors.add("host: обязательное поле")
        }
        val portValue = request.port
        if (portValue == null || portValue < 1 || portValue > 65_535) {
            errors.add("port: ожидается 1–65535")
        }
        if (errors.isNotEmpty()) {
            throw badRequest(errors.joinToString("; "))
        }
        val occupiedId = databaseProvider.execute {
            jdbcTemplate.query(SELECT_ID_BY_NAME_SQL, { resultSet, _ -> resultSet.getLong("id") }, name)
                .firstOrNull()
        }
        if (occupiedId != null && occupiedId != existingId) {
            throw conflict("Прокси с именем '$name' уже существует")
        }
    }

    /** Провайдеры, привязанные к прокси; вызывается внутри databaseProvider.execute. */
    private fun providerNames(proxyName: String): List<String> =
        jdbcTemplate.queryForList(SELECT_PROVIDERS_USING_SQL, String::class.java, proxyName)

    private suspend fun loadRow(id: Long): ProxyEndpointRow =
        databaseProvider.execute {
            jdbcTemplate.query(SELECT_BY_ID_SQL, { resultSet, _ -> mapRow(resultSet) }, id)
                .firstOrNull()
        } ?: throw notFound("Прокси не найден")

    private suspend fun loadView(name: String): ProxyEndpointView {
        val row = databaseProvider.execute {
            jdbcTemplate.query(SELECT_BY_NAME_SQL, { resultSet, _ -> mapRow(resultSet) }, name)
                .firstOrNull()
        } ?: throw notFound("Прокси не найден")
        val names = databaseProvider.execute { providerNames(name) }
        return row.toView(names)
    }

    private fun mapRow(resultSet: ResultSet): ProxyEndpointRow = ProxyEndpointRow(
        id = resultSet.getLong("id"),
        name = resultSet.getString("name"),
        type = resultSet.getString("type"),
        host = resultSet.getString("host"),
        port = resultSet.getInt("port"),
        username = resultSet.getString("username"),
        password = resultSet.getString("password"),
        enabled = resultSet.getInt("enabled") == 1,
        lastCheckStatus = resultSet.getString("last_check_status"),
        lastCheckAt = resultSet.getLong("last_check_at").takeIf { !resultSet.wasNull() },
        createdAt = resultSet.getLong("created_at"),
        updatedAt = resultSet.getLong("updated_at"),
    )

    private fun ProxyEndpointRow.toView(providerNames: List<String>) =
        ProxyEndpointView(
            id = id,
            name = name,
            type = type,
            host = host,
            port = port,
            username = username,
            hasPassword = password != null,
            enabled = enabled,
            lastCheckStatus = lastCheckStatus,
            lastCheckAt = lastCheckAt,
            providerNames = providerNames,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    private fun badRequest(message: String) =
        ApiError(HttpStatus.BAD_REQUEST, "invalid_request_error", message)

    private fun conflict(message: String) =
        ApiError(HttpStatus.CONFLICT, "invalid_request_error", message)

    private fun notFound(message: String) =
        ApiError(HttpStatus.NOT_FOUND, "not_found_error", message)

    /** Строка таблицы proxy_endpoint (пароль — сырая ${ENV:...} ссылка). */
    private data class ProxyEndpointRow(
        val id: Long,
        val name: String,
        val type: String,
        val host: String,
        val port: Int,
        val username: String?,
        val password: String?,
        val enabled: Boolean,
        val lastCheckStatus: String?,
        val lastCheckAt: Long?,
        val createdAt: Long,
        val updatedAt: Long,
    )

    companion object {
        private val SUPPORTED_TYPES = listOf("HTTP", "HTTPS", "SOCKS4", "SOCKS5")
        private const val DEFAULT_CHECK_URL = "https://www.gstatic.com/generate_204"
        private const val CHECK_TIMEOUT_MILLISECONDS = 5_000L

        private val PROJECTION =
            "id, name, type, host, port, username, password, enabled, " +
                "last_check_status, last_check_at, created_at, updated_at"
        private val SELECT_ALL_SQL =
            "SELECT $PROJECTION FROM proxy_endpoint ORDER BY name"
        private val SELECT_BY_NAME_SQL =
            "SELECT $PROJECTION FROM proxy_endpoint WHERE name = ?"
        private val SELECT_BY_ID_SQL =
            "SELECT $PROJECTION FROM proxy_endpoint WHERE id = ?"
        private const val SELECT_ID_BY_NAME_SQL = "SELECT id FROM proxy_endpoint WHERE name = ?"
        private const val SELECT_PROVIDERS_USING_SQL =
            "SELECT name FROM provider WHERE proxy_name = ? ORDER BY name"
        private const val INSERT_SQL =
            "INSERT INTO proxy_endpoint " +
                "(name, type, host, port, username, password, enabled, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
        private const val UPDATE_SQL =
            "UPDATE proxy_endpoint SET name = ?, type = ?, host = ?, port = ?, username = ?, " +
                "password = ?, enabled = ?, updated_at = ? WHERE id = ?"
        private const val UPDATE_CHECK_SQL =
            "UPDATE proxy_endpoint SET last_check_status = ?, last_check_at = ? WHERE id = ?"
        private const val DELETE_SQL = "DELETE FROM proxy_endpoint WHERE id = ?"
    }
}
