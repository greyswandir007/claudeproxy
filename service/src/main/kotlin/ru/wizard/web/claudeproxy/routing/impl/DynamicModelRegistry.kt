package ru.wizard.web.claudeproxy.routing.impl

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import ru.wizard.web.claudeproxy.config.EnvironmentReferenceResolver
import ru.wizard.web.claudeproxy.db.DatabaseProvider
import ru.wizard.web.claudeproxy.routing.ModelRegistry

/**
 * Реализация ModelRegistry поверх таблиц provider/model:
 * в памяти держит снимок, перезагружаемый по reload() (сид на старте + мутации UI).
 * Маршрутизация работает по всем включённым моделям независимо от флагов exposed;
 * флаги exposed (модели и провайдера) управляют только выдачей GET /v1/models.
 * api_key в БД может быть ссылкой ${ENV_VAR} — резолвится при загрузке.
 */
@Component
class DynamicModelRegistry(
    private val databaseProvider: DatabaseProvider,
    private val jdbcTemplate: JdbcTemplate,
    private val environment: Environment,
    private val objectMapper: ObjectMapper,
) : ModelRegistry {
    private val logger = KotlinLogging.logger {}

    private data class Snapshot(
        val routesByName: Map<String, List<ModelRegistry.Route>>,
        val exposedNames: Set<String>,
    )

    @Volatile
    private var snapshot: Snapshot = Snapshot(emptyMap(), emptySet())

    override suspend fun reload() {
        databaseProvider.execute { reloadBlocking() }
    }

    private fun reloadBlocking() {
        val providersById = HashMap<Long, ModelRegistry.ProviderInfo>()
        val providerExposedById = HashMap<Long, Boolean>()
        val effortMappingById = HashMap<Long, Map<String, String>>()
        val settingOverridesById = HashMap<Long, Map<String, String>>()
        jdbcTemplate.query(
            """SELECT id, name, type, base_url, api_key, auth_type, extra_headers, exposed, effort_mapping
               FROM provider""",
        ) { resultSet ->
            val identifier = resultSet.getLong("id")
            providersById[identifier] = ModelRegistry.ProviderInfo(
                id = identifier,
                name = resultSet.getString("name"),
                type = resultSet.getString("type"),
                baseUrl = resultSet.getString("base_url"),
                apiKey = EnvironmentReferenceResolver.resolve(
                    environment,
                    resultSet.getString("api_key"),
                ),
                authType = resultSet.getString("auth_type").ifBlank { "api_key" },
                extraHeaders = parseExtraHeaders(resultSet.getString("extra_headers")),
                effortMapping = emptyMap(),
                settingOverrides = emptyMap(),
            )
            providerExposedById[identifier] = resultSet.getInt("exposed") == 1
            effortMappingById[identifier] =
                parseEffortMapping(resultSet.getString("effort_mapping"))
        }
        jdbcTemplate.query(
            "SELECT provider_id, setting_key, setting_value FROM provider_setting",
        ) { resultSet ->
            settingOverridesById
                .getOrPut(resultSet.getLong("provider_id")) { HashMap() }
                .let { it as MutableMap }
                .put(resultSet.getString("setting_key"), resultSet.getString("setting_value"))
        }
        providersById.forEach { (identifier, provider) ->
            providersById[identifier] = provider.copy(
                effortMapping = effortMappingById[identifier] ?: emptyMap(),
                settingOverrides = settingOverridesById[identifier] ?: emptyMap(),
            )
        }
        val routesByName = LinkedHashMap<String, MutableList<ModelRegistry.Route>>()
        val exposedNames = LinkedHashSet<String>()
        jdbcTemplate.query(
            """SELECT m.provider_id, m.public_name, m.upstream_name, m.reasoning,
                      m.max_completion_param, m.priority, m.exposed
               FROM model m
               WHERE m.enabled = 1
               ORDER BY m.priority, m.id""",
        ) { resultSet ->
            val providerId = resultSet.getLong("provider_id")
            val provider = providersById[providerId] ?: return@query
            val publicName = resultSet.getString("public_name")
            val route = ModelRegistry.Route(
                provider = provider,
                mapping = ModelRegistry.ModelInfo(
                    publicName = publicName,
                    upstreamName = resultSet.getString("upstream_name"),
                    reasoning = resultSet.getString("reasoning").ifBlank { "map" },
                    maxCompletionParam = resultSet.getInt("max_completion_param") == 1,
                    priority = resultSet.getInt("priority"),
                ),
            )
            routesByName.getOrPut(publicName) { ArrayList() }.add(route)
            val modelExposed = resultSet.getInt("exposed") == 1
            if (modelExposed && providerExposedById[providerId] == true) {
                exposedNames.add(publicName)
            }
        }
        snapshot = Snapshot(routesByName, exposedNames)
        logger.info {
            "Model registry loaded: ${routesByName.size} models " +
                "(${routesByName.values.sumOf { it.size }} routes, " +
                "${exposedNames.size} exposed) from ${providersById.size} providers"
        }
    }

    private fun parseExtraHeaders(extraHeadersJson: String?): Map<String, String> {
        if (extraHeadersJson.isNullOrBlank()) return emptyMap()
        return runCatching {
            objectMapper.readValue(extraHeadersJson, Map::class.java)
        }.getOrDefault(emptyMap<Any, Any>()).entries.associate { (key, value) ->
            key.toString() to value.toString()
        }
    }

    /** {"levels":{"low":"...",...}} → карта уровней; пустая — маппер выключен. */
    private fun parseEffortMapping(effortMappingJson: String?): Map<String, String> {
        if (effortMappingJson.isNullOrBlank()) return emptyMap()
        val levelsNode = runCatching {
            objectMapper.readTree(effortMappingJson).path("levels")
        }.getOrNull() ?: return emptyMap()
        if (!levelsNode.isObject) return emptyMap()
        val result = HashMap<String, String>()
        levelsNode.fields().forEach { entry ->
            if (entry.value.isTextual && entry.value.asText().isNotBlank()) {
                result[entry.key] = entry.value.asText()
            }
        }
        return result
    }

    /** Страховка на случай запроса до первого reload (сид должен загрузить раньше). */
    private fun ensureLoaded() {
        if (snapshot.routesByName.isEmpty()) {
            runBlocking { reload() }
        }
    }

    override fun find(model: String): List<ModelRegistry.Route> {
        ensureLoaded()
        return snapshot.routesByName[model] ?: emptyList()
    }

    override fun isExposed(model: String): Boolean {
        ensureLoaded()
        return snapshot.exposedNames.contains(model)
    }

    override fun exposedModels(): List<String> {
        ensureLoaded()
        return snapshot.routesByName.keys.filter { it in snapshot.exposedNames }
    }

    override fun routes(): List<ModelRegistry.Route> {
        ensureLoaded()
        return snapshot.routesByName.values.flatten()
    }
}
