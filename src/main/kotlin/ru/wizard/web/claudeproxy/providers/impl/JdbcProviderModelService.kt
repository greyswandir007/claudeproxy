package ru.wizard.web.claudeproxy.providers.impl

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import ru.wizard.web.claudeproxy.config.ProxyProperties
import ru.wizard.web.claudeproxy.db.DatabaseProvider
import ru.wizard.web.claudeproxy.providers.ProviderModelService
import ru.wizard.web.claudeproxy.proxy.ApiError
import ru.wizard.web.claudeproxy.routing.ModelRegistry

/**
 * Реализация ProviderModelService на JdbcTemplate. Сид: провайдеры и модели из YAML
 * вносятся при старте, если имени ещё нет (models.allow фильтрует сид-модели);
 * существующие записи YAML не перезаписывает — правки из UI приоритетнее.
 */
@Service
@org.springframework.context.annotation.DependsOn("databaseMigrationRunner")
class JdbcProviderModelService(
    private val databaseProvider: DatabaseProvider,
    private val jdbcTemplate: JdbcTemplate,
    private val proxyProperties: ProxyProperties,
    private val modelRegistry: ModelRegistry,
    private val objectMapper: ObjectMapper,
) : ProviderModelService {
    private val logger = KotlinLogging.logger {}

    /** Сид из YAML → БД, затем первая загрузка реестра. */
    @PostConstruct
    fun seed() {
        val allowedModels = proxyProperties.models.allow
        for (yamlProvider in proxyProperties.providers) {
            val providerId = findProviderIdByName(yamlProvider.name) ?: run {
                jdbcTemplate.update(
                    """INSERT INTO provider
                       (name, type, base_url, api_key, extra_headers,
                        limit_window_tokens, limit_week_tokens, limit_month_tokens, created_at, updated_at)
                       VALUES (?,?,?,?,?,?,?,?,?,?)""",
                    yamlProvider.name,
                    yamlProvider.type,
                    yamlProvider.baseUrl,
                    yamlProvider.apiKey,
                    objectMapper.writeValueAsString(yamlProvider.extraHeaders),
                    yamlProvider.limitWindowTokens,
                    yamlProvider.limitWeekTokens,
                    yamlProvider.limitMonthTokens,
                    System.currentTimeMillis(),
                    System.currentTimeMillis(),
                )
                logger.info { "Seeded provider '${yamlProvider.name}' from YAML" }
                findProviderIdByName(yamlProvider.name)!!
            }
            for (modelMapping in yamlProvider.models) {
                if (allowedModels.isNotEmpty() && modelMapping.`public` !in allowedModels) continue
                if (findModelIdByPublicName(modelMapping.`public`) == null) {
                    val now = System.currentTimeMillis()
                    jdbcTemplate.update(
                        """INSERT INTO model
                           (provider_id, public_name, upstream_name, reasoning, max_completion_param,
                            enabled, created_at, updated_at)
                           VALUES (?,?,?,?,?,1,?,?)""",
                        providerId,
                        modelMapping.`public`,
                        modelMapping.upstream,
                        modelMapping.reasoning.ifBlank { "map" },
                        if (modelMapping.maxCompletionParam) 1 else 0,
                        now,
                        now,
                    )
                }
            }
        }
        runBlocking { modelRegistry.reload() }
    }

    override suspend fun listProviders(): List<ProviderModelService.ProviderView> =
        databaseProvider.execute { loadProviders() }

    override suspend fun createProvider(
        request: ProviderModelService.ProviderRequest,
    ): ProviderModelService.ProviderView {
        val created = databaseProvider.execute {
            validateProviderRequest(request)
            requireUniqueProviderName(request.name)
            val now = System.currentTimeMillis()
            jdbcTemplate.update(
                """INSERT INTO provider
                   (name, type, base_url, api_key, extra_headers, exposed,
                    limit_window_tokens, limit_week_tokens, limit_month_tokens, created_at, updated_at)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
                request.name,
                request.type,
                request.baseUrl,
                request.apiKey ?: "",
                objectMapper.writeValueAsString(request.extraHeaders ?: emptyMap<String, String>()),
                if (request.exposed ?: true) 1 else 0,
                request.limitWindowTokens,
                request.limitWeekTokens,
                request.limitMonthTokens,
                now,
                now,
            )
            loadProvider(findProviderIdByName(request.name)!!)
        }
        modelRegistry.reload()
        return created
    }

    override suspend fun updateProvider(
        id: Long,
        request: ProviderModelService.ProviderRequest,
    ): ProviderModelService.ProviderView {
        val updated = databaseProvider.execute {
            validateProviderRequest(request)
            requireProviderExists(id)
            val nameOwnerIds = jdbcTemplate.query(
                "SELECT id FROM provider WHERE name = ?",
                { resultSet, _ -> resultSet.getLong(1) },
                request.name,
            )
            if (nameOwnerIds.isNotEmpty() && nameOwnerIds.first() != id) {
                throw conflict("Провайдер с именем '${request.name}' уже существует")
            }
            val updateApiKey = !request.apiKey.isNullOrBlank()
            jdbcTemplate.update(
                """UPDATE provider SET name = ?, type = ?, base_url = ?, extra_headers = ?,
                   limit_window_tokens = ?, limit_week_tokens = ?, limit_month_tokens = ?""" +
                    (if (request.exposed != null) ", exposed = ?" else "") +
                    (if (updateApiKey) ", api_key = ?" else "") +
                    ", updated_at = ? WHERE id = ?",
                *buildUpdateProviderArguments(request, id, updateApiKey).toTypedArray(),
            )
            loadProvider(id)
        }
        modelRegistry.reload()
        return updated
    }

    override suspend fun deleteProvider(id: Long): Boolean {
        val deleted = databaseProvider.execute {
            // FK-enforcement в SQLite по умолчанию выключен — удаляем явно, в одной секции БД
            val deletedModels = jdbcTemplate.update("DELETE FROM model WHERE provider_id = ?", id)
            val deletedProviders = jdbcTemplate.update("DELETE FROM provider WHERE id = ?", id)
            if (deletedProviders > 0) {
                logger.info { "Deleted provider id=$id (models: $deletedModels)" }
            }
            deletedProviders > 0
        }
        if (deleted) {
            modelRegistry.reload()
        }
        return deleted
    }

    override suspend fun createModel(
        providerId: Long,
        request: ProviderModelService.ModelRequest,
    ): ProviderModelService.ModelView {
        val created = databaseProvider.execute {
            validateModelRequest(request)
            requireProviderExists(providerId)
            requireUniquePublicName(providerId, request.publicName)
            val now = System.currentTimeMillis()
            jdbcTemplate.update(
                """INSERT INTO model
                   (provider_id, public_name, upstream_name, reasoning, max_completion_param,
                    priority, enabled, created_at, updated_at)
                   VALUES (?,?,?,?,?,?,1,?,?)""",
                providerId,
                request.publicName,
                request.upstreamName,
                request.reasoning,
                if (request.maxCompletionParam) 1 else 0,
                request.priority,
                now,
                now,
            )
            loadModel(
                jdbcTemplate.query(
                    "SELECT id FROM model WHERE provider_id = ? AND public_name = ?",
                    { resultSet, _ -> resultSet.getLong(1) },
                    providerId,
                    request.publicName,
                ).first(),
            )
        }
        modelRegistry.reload()
        return created
    }

    override suspend fun updateModel(
        id: Long,
        request: ProviderModelService.ModelRequest,
    ): ProviderModelService.ModelView {
        val updated = databaseProvider.execute {
            validateModelRequest(request)
            requireModelExists(id)
            val ownerProviderIds = jdbcTemplate.query(
                "SELECT provider_id FROM model WHERE id = ?",
                { resultSet, _ -> resultSet.getLong(1) },
                id,
            )
            val ownerProviderId = ownerProviderIds.first()
            val duplicateIds = jdbcTemplate.query(
                "SELECT id FROM model WHERE public_name = ? AND provider_id = ?",
                { resultSet, _ -> resultSet.getLong(1) },
                request.publicName,
                ownerProviderId,
            )
            if (duplicateIds.isNotEmpty() && duplicateIds.first() != id) {
                throw conflict(
                    "Модель '${request.publicName}' уже есть у провайдера id=$ownerProviderId",
                )
            }
            jdbcTemplate.update(
                """UPDATE model SET public_name = ?, upstream_name = ?, reasoning = ?,
                   max_completion_param = ?, priority = ?, updated_at = ? WHERE id = ?""",
                request.publicName,
                request.upstreamName,
                request.reasoning,
                if (request.maxCompletionParam) 1 else 0,
                request.priority,
                System.currentTimeMillis(),
                id,
            )
            loadModel(id)
        }
        modelRegistry.reload()
        return updated
    }

    override suspend fun deleteModel(id: Long): Boolean {
        val deleted = databaseProvider.execute {
            jdbcTemplate.update("DELETE FROM model WHERE id = ?", id) > 0
        }
        if (deleted) {
            modelRegistry.reload()
        }
        return deleted
    }

    override suspend fun setProviderExposed(id: Long, exposed: Boolean): Boolean {
        val changed = databaseProvider.execute {
            jdbcTemplate.update(
                "UPDATE provider SET exposed = ?, updated_at = ? WHERE id = ?",
                if (exposed) 1 else 0,
                System.currentTimeMillis(),
                id,
            ) > 0
        }
        if (changed) {
            modelRegistry.reload()
        }
        return changed
    }

    override suspend fun setModelExposed(id: Long, exposed: Boolean): Boolean {
        val changed = databaseProvider.execute {
            jdbcTemplate.update(
                "UPDATE model SET exposed = ?, updated_at = ? WHERE id = ?",
                if (exposed) 1 else 0,
                System.currentTimeMillis(),
                id,
            ) > 0
        }
        if (changed) {
            modelRegistry.reload()
        }
        return changed
    }

    private fun findProviderIdByName(name: String): Long? =
        jdbcTemplate.query(
            "SELECT id FROM provider WHERE name = ?",
            { resultSet, _ -> resultSet.getLong(1) },
            name,
        ).firstOrNull()

    private fun findModelIdByPublicName(publicName: String): Long? =
        jdbcTemplate.query(
            "SELECT id FROM model WHERE public_name = ?",
            { resultSet, _ -> resultSet.getLong(1) },
            publicName,
        ).firstOrNull()

    private fun loadProviders(): List<ProviderModelService.ProviderView> {
        val providerIds = ArrayList<Long>()
        jdbcTemplate.query(
            "SELECT id FROM provider ORDER BY created_at, id",
        ) { resultSet -> providerIds.add(resultSet.getLong(1)) }
        return providerIds.map { loadProvider(it) }
    }

    /** Аргументы UPDATE provider в порядке SET-плейсхолдеров (лимиты перезаписываются все). */
    private fun buildUpdateProviderArguments(
        request: ProviderModelService.ProviderRequest,
        id: Long,
        updateApiKey: Boolean,
    ): List<Any?> {
        val arguments = ArrayList<Any?>()
        arguments.add(request.name)
        arguments.add(request.type)
        arguments.add(request.baseUrl)
        arguments.add(objectMapper.writeValueAsString(request.extraHeaders ?: emptyMap<String, String>()))
        arguments.add(request.limitWindowTokens)
        arguments.add(request.limitWeekTokens)
        arguments.add(request.limitMonthTokens)
        if (request.exposed != null) {
            arguments.add(if (request.exposed) 1 else 0)
        }
        if (updateApiKey) {
            arguments.add(request.apiKey!!)
        }
        arguments.add(System.currentTimeMillis())
        arguments.add(id)
        return arguments
    }

    private fun loadProvider(id: Long): ProviderModelService.ProviderView {
        val providerRows = ArrayList<Array<Any?>>()
        jdbcTemplate.query(
            """SELECT name, type, base_url, api_key, extra_headers, exposed,
                      limit_window_tokens, limit_week_tokens, limit_month_tokens,
                      created_at, updated_at
               FROM provider WHERE id = ?""",
            { resultSet ->
                providerRows.add(
                    arrayOf(
                        resultSet.getString(1),
                        resultSet.getString(2),
                        resultSet.getString(3),
                        resultSet.getString(4) ?: "",
                        resultSet.getString(5) ?: "{}",
                        resultSet.getInt(6) == 1,
                        resultSet.getLong(7).takeIf { !resultSet.wasNull() },
                        resultSet.getLong(8).takeIf { !resultSet.wasNull() },
                        resultSet.getLong(9).takeIf { !resultSet.wasNull() },
                        resultSet.getLong(10),
                        resultSet.getLong(11),
                    ),
                )
            },
            id,
        )
        if (providerRows.isEmpty()) throw notFound("Провайдер не найден")
        val row = providerRows.first()
        val models = ArrayList<ProviderModelService.ModelView>()
        jdbcTemplate.query(
            """SELECT id, public_name, upstream_name, reasoning, max_completion_param, priority, exposed
               FROM model WHERE provider_id = ? AND enabled = 1
               ORDER BY priority, created_at, id""",
            { resultSet ->
                models.add(
                    ProviderModelService.ModelView(
                        id = resultSet.getLong(1),
                        providerId = id,
                        publicName = resultSet.getString(2),
                        upstreamName = resultSet.getString(3),
                        reasoning = resultSet.getString(4).ifBlank { "map" },
                        maxCompletionParam = resultSet.getInt(5) == 1,
                        priority = resultSet.getInt(6),
                        exposed = resultSet.getInt(7) == 1,
                    ),
                )
            },
            id,
        )
        return ProviderModelService.ProviderView(
            id = id,
            name = row[0] as String,
            type = row[1] as String,
            baseUrl = row[2] as String,
            apiKeyPreview = apiKeyPreview(row[3] as String),
            extraHeaders = parseExtraHeaders(row[4] as String),
            exposed = row[5] as Boolean,
            limitWindowTokens = row[6] as Long?,
            limitWeekTokens = row[7] as Long?,
            limitMonthTokens = row[8] as Long?,
            models = models,
            createdAt = row[9] as Long,
            updatedAt = row[10] as Long,
        )
    }

    private fun loadModel(id: Long): ProviderModelService.ModelView {
        val models = ArrayList<ProviderModelService.ModelView>()
        jdbcTemplate.query(
            """SELECT id, provider_id, public_name, upstream_name, reasoning,
                      max_completion_param, priority, exposed
               FROM model WHERE id = ? AND enabled = 1""",
            { resultSet ->
                models.add(
                    ProviderModelService.ModelView(
                        id = resultSet.getLong(1),
                        providerId = resultSet.getLong(2),
                        publicName = resultSet.getString(3),
                        upstreamName = resultSet.getString(4),
                        reasoning = resultSet.getString(5).ifBlank { "map" },
                        maxCompletionParam = resultSet.getInt(6) == 1,
                        priority = resultSet.getInt(7),
                        exposed = resultSet.getInt(8) == 1,
                    ),
                )
            },
            id,
        )
        return models.firstOrNull() ?: throw notFound("Модель не найдена")
    }

    private fun parseExtraHeaders(extraHeadersJson: String): Map<String, String> =
        runCatching {
            objectMapper.readValue(extraHeadersJson, Map::class.java)
        }.getOrDefault(emptyMap<Any, Any>()).entries.associate { (key, value) ->
            key.toString() to value.toString()
        }

    private fun validateProviderRequest(request: ProviderModelService.ProviderRequest) {
        if (request.name.trim().isEmpty()) throw badRequest("Имя провайдера обязательно")
        if (request.type !in PROVIDER_TYPES) {
            throw badRequest("Тип провайдера: ожидается anthropic или openai")
        }
        if (request.baseUrl.trim().isEmpty()) throw badRequest("base-url провайдера обязателен")
    }

    private fun validateModelRequest(request: ProviderModelService.ModelRequest) {
        if (request.publicName.trim().isEmpty()) throw badRequest("Публичное имя модели обязательно")
        if (request.upstreamName.trim().isEmpty()) throw badRequest("Upstream-имя модели обязательно")
        if (request.reasoning !in REASONING_MODES) throw badRequest("reasoning: ожидается map или off")
        if (request.priority < 1 || request.priority > 100_000) {
            throw badRequest("priority: целое число от 1 (наивысший) до 100000")
        }
    }

    private fun requireUniqueProviderName(name: String) {
        if (findProviderIdByName(name) != null) {
            throw conflict("Провайдер с именем '$name' уже существует")
        }
    }

    /** Публичное имя уникально в рамках провайдера: у разных провайдеров — можно. */
    private fun requireUniquePublicName(providerId: Long, publicName: String) {
        val duplicates = jdbcTemplate.query(
            "SELECT id FROM model WHERE public_name = ? AND provider_id = ?",
            { resultSet, _ -> resultSet.getLong(1) },
            publicName,
            providerId,
        )
        if (duplicates.isNotEmpty()) {
            throw conflict("Модель '$publicName' уже есть у этого провайдера")
        }
    }

    private fun requireProviderExists(id: Long) {
        if (findProviderIdByName(id) == null) throw notFound("Провайдер не найден")
    }

    private fun requireModelExists(id: Long) {
        val existing = jdbcTemplate.query(
            "SELECT id FROM model WHERE id = ?",
            { resultSet, _ -> resultSet.getLong(1) },
            id,
        )
        if (existing.isEmpty()) throw notFound("Модель не найдена")
    }

    private fun findProviderIdByName(id: Long): Long? =
        jdbcTemplate.query(
            "SELECT id FROM provider WHERE id = ?",
            { resultSet, _ -> resultSet.getLong(1) },
            id,
        ).firstOrNull()

    /** Ключ наружу не отдаём: только превью из первых/последних символов. */
    private fun apiKeyPreview(apiKey: String): String {
        if (apiKey.isBlank()) return ""
        if (apiKey.startsWith("\${")) return apiKey // ссылка на ENV — показываем как есть
        if (apiKey.length <= PREVIEW_VISIBLE_LENGTH) return "${apiKey.take(2)}…"
        return "${apiKey.take(PREVIEW_TAIL_LENGTH)}…${apiKey.takeLast(PREVIEW_TAIL_LENGTH)}"
    }

    private fun badRequest(message: String): ApiError =
        ApiError(HttpStatus.BAD_REQUEST, "invalid_request_error", message)

    private fun conflict(message: String): ApiError =
        ApiError(HttpStatus.CONFLICT, "invalid_request_error", message)

    private fun notFound(message: String): ApiError =
        ApiError(HttpStatus.NOT_FOUND, "not_found_error", message)

    private companion object {
        val PROVIDER_TYPES = setOf("anthropic", "openai")
        val REASONING_MODES = setOf("map", "off")
        const val PREVIEW_TAIL_LENGTH = 4
        const val PREVIEW_VISIBLE_LENGTH = 8
    }
}
