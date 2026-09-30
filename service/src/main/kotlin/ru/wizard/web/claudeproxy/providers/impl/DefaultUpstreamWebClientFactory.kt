package ru.wizard.web.claudeproxy.providers.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import org.springframework.core.env.Environment
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.netty.transport.ProxyProvider
import ru.wizard.web.claudeproxy.config.EnvironmentReferenceResolver
import ru.wizard.web.claudeproxy.db.DatabaseProvider
import ru.wizard.web.claudeproxy.providers.ProxyEndpointService
import ru.wizard.web.claudeproxy.providers.UpstreamWebClientFactory

/**
 * Фабрика WebClient'ов (M31): прямой клиент (как до M31 — 64 МБ буфер) и
 * клиенты через прокси-эндпоинты (HTTP/HTTPS-CONNECT/SOCKS4/SOCKS5),
 * кэшированные по имени прокси. Пароли прокси поддерживают ${ENV:...} и
 * резолвятся при создании клиента; в логи не попадают.
 */
@Service
class DefaultUpstreamWebClientFactory(
    private val jdbcTemplate: JdbcTemplate,
    private val databaseProvider: DatabaseProvider,
    private val environment: Environment,
) : UpstreamWebClientFactory {

    private val logger = KotlinLogging.logger {}

    @Volatile
    private var directClient: WebClient? = null

    /** name -> клиент через прокси; кэш сбрасывается invalidate(). */
    private val proxiedClients = ConcurrentHashMap<String, WebClient>()

    override suspend fun webClient(proxyName: String?): WebClient {
        if (proxyName.isNullOrBlank()) return directClient()
        proxiedClients[proxyName]?.let { return it }
        val config = loadConfig(proxyName)
        if (config == null) {
            // прокси исчез/выключен, а провайдер всё ещё ссылается — не ломаем чат
            logger.warn { "Proxy '$proxyName' not found or disabled, using direct connection" }
            return directClient()
        }
        val client = buildClient(config)
        proxiedClients[proxyName] = client
        return client
    }

    override fun proxiedClient(config: ProxyEndpointService.ProxyEndpointConfig): WebClient =
        buildClient(config)

    override fun invalidate() {
        proxiedClients.clear()
    }

    private fun directClient(): WebClient {
        val existing = directClient
        if (existing != null) return existing
        val created = configure(WebClient.builder()).build()
        directClient = created
        return created
    }

    private suspend fun loadConfig(name: String): ProxyEndpointService.ProxyEndpointConfig? =
        databaseProvider.execute {
            jdbcTemplate.query(
                SELECT_ENABLED_CONFIG_SQL,
                { resultSet, _ ->
                    ProxyEndpointService.ProxyEndpointConfig(
                        name = resultSet.getString("name"),
                        type = resultSet.getString("type"),
                        host = resultSet.getString("host"),
                        port = resultSet.getInt("port"),
                        username = resultSet.getString("username"),
                        passwordReference = resultSet.getString("password"),
                    )
                },
                name,
            ).firstOrNull()
        }

    private fun buildClient(config: ProxyEndpointService.ProxyEndpointConfig): WebClient {
        val httpClient = HttpClient.create().proxy { spec ->
            val type = when (config.type.uppercase()) {
                "SOCKS4" -> ProxyProvider.Proxy.SOCKS4
                "SOCKS5" -> ProxyProvider.Proxy.SOCKS5
                // HTTP и HTTPS (CONNECT-туннель) в reactor-netty — один тип
                else -> ProxyProvider.Proxy.HTTP
            }
            val builder = spec.type(type).host(config.host).port(config.port)
            config.username?.let { username -> builder.username(username) }
            config.passwordReference?.let { reference ->
                // резолв ${ENV:...} лениво, в момент установки соединения
                builder.password { EnvironmentReferenceResolver.resolve(environment, reference) }
            }
        }
        return configure(WebClient.builder())
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }

    /** Общие настройки клиентов: буфер 64 МБ под большие ответы Claude. */
    private fun configure(builder: WebClient.Builder): WebClient.Builder =
        builder.codecs { it.defaultCodecs().maxInMemorySize(64 * 1024 * 1024) }

    companion object {
        private const val SELECT_ENABLED_CONFIG_SQL =
            "SELECT name, type, host, port, username, password FROM proxy_endpoint " +
                "WHERE name = ? AND enabled = 1"
    }
}
