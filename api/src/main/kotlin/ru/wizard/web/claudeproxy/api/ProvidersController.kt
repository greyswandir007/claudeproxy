package ru.wizard.web.claudeproxy.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import ru.wizard.web.claudeproxy.providers.ProviderModelService
import ru.wizard.web.claudeproxy.proxy.ApiError

/**
 * /api/providers и /api/models — CRUD провайдеров и моделей (дашборд).
 * Изменения применяются сразу: реестр перезагружается после каждой мутации.
 */
@RestController
class ProvidersController(
    private val providerModelService: ProviderModelService,
    private val providerModelDiscoveryService: ru.wizard.web.claudeproxy.providers.ProviderModelDiscoveryService,
    private val objectMapper: ObjectMapper,
) {

    /** Список моделей, который отдаёт сам провайдер (для выбора upstream-имён в UI). */
    @PostMapping("/api/providers/discover-models", consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun discoverModels(
        @RequestBody requestBody: String,
    ): Map<String, List<String>> {
        val node = parseJson(requestBody)
        val models = providerModelDiscoveryService.discover(
            ru.wizard.web.claudeproxy.providers.ProviderModelDiscoveryService.DiscoveryRequest(
                type = node.path("type").asText("openai"),
                baseUrl = node.path("baseUrl").asText(""),
                apiKey = node.path("apiKey").takeIf { it.isTextual && it.asText().isNotEmpty() }?.asText(),
                providerId = node.path("providerId").takeIf { it.isNumber }?.asLong(),
            ),
        )
        return mapOf("models" to models)
    }

    @GetMapping("/api/providers")
    suspend fun listProviders(): List<ProviderModelService.ProviderView> =
        providerModelService.listProviders()

    @PostMapping("/api/providers", consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun createProvider(
        @RequestBody requestBody: String,
    ): ResponseEntity<ProviderModelService.ProviderView> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(providerModelService.createProvider(parseProviderRequest(requestBody)))

    @PutMapping("/api/providers/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun updateProvider(
        @PathVariable id: Long,
        @RequestBody requestBody: String,
    ): ProviderModelService.ProviderView =
        providerModelService.updateProvider(id, parseProviderRequest(requestBody))

    @DeleteMapping("/api/providers/{id}")
    suspend fun deleteProvider(@PathVariable id: Long): ResponseEntity<Void> {
        if (!providerModelService.deleteProvider(id)) {
            throw ApiError(HttpStatus.NOT_FOUND, "not_found_error", "Провайдер не найден")
        }
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/api/providers/{id}/models", consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun createModel(
        @PathVariable id: Long,
        @RequestBody requestBody: String,
    ): ResponseEntity<ProviderModelService.ModelView> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(providerModelService.createModel(id, parseModelRequest(requestBody)))

    @PutMapping("/api/models/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun updateModel(
        @PathVariable id: Long,
        @RequestBody requestBody: String,
    ): ProviderModelService.ModelView =
        providerModelService.updateModel(id, parseModelRequest(requestBody))

    @DeleteMapping("/api/models/{id}")
    suspend fun deleteModel(@PathVariable id: Long): ResponseEntity<Void> {
        if (!providerModelService.deleteModel(id)) {
            throw ApiError(HttpStatus.NOT_FOUND, "not_found_error", "Модель не найдена")
        }
        return ResponseEntity.noContent().build()
    }

    /** Видимость провайдера в выдаче GET /v1/models. */
    @PutMapping("/api/providers/{id}/exposure", consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun setProviderExposed(
        @PathVariable id: Long,
        @RequestBody requestBody: String,
    ): Map<String, Boolean> {
        val exposed = parseJson(requestBody).path("exposed").asBoolean(true)
        if (!providerModelService.setProviderExposed(id, exposed)) {
            throw ApiError(HttpStatus.NOT_FOUND, "not_found_error", "Провайдер не найден")
        }
        return mapOf("exposed" to exposed)
    }

    /** Видимость модели в выдаче GET /v1/models. */
    @PutMapping("/api/models/{id}/exposure", consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun setModelExposed(
        @PathVariable id: Long,
        @RequestBody requestBody: String,
    ): Map<String, Boolean> {
        val exposed = parseJson(requestBody).path("exposed").asBoolean(true)
        if (!providerModelService.setModelExposed(id, exposed)) {
            throw ApiError(HttpStatus.NOT_FOUND, "not_found_error", "Модель не найдена")
        }
        return mapOf("exposed" to exposed)
    }

    private fun parseProviderRequest(requestBody: String): ProviderModelService.ProviderRequest {
        val node = parseJson(requestBody)
        return ProviderModelService.ProviderRequest(
            name = node.path("name").asText(""),
            type = node.path("type").asText("anthropic"),
            baseUrl = node.path("baseUrl").asText(""),
            apiKey = node.path("apiKey").takeIf { it.isTextual && it.asText().isNotEmpty() }?.asText(),
            extraHeaders = node.path("extraHeaders").takeIf { it.isObject }?.let(::toStringMap),
            exposed = node.path("exposed").takeIf { it.isBoolean }?.asBoolean(),
            limitWindowTokens = optionalLimit(node, "limitWindowTokens"),
            limitWeekTokens = optionalLimit(node, "limitWeekTokens"),
            limitMonthTokens = optionalLimit(node, "limitMonthTokens"),
            effortMapping = toStringMap(node.path("effortMapping").takeIf { it.isObject }),
            settingOverrides = toStringMap(node.path("settingOverrides").takeIf { it.isObject }),
            authType = node.path("authType").takeIf { it.isTextual }?.asText(),
            oauthGrant = node.path("oauthGrant").takeIf { it.isTextual }?.asText(),
            oauthClientId = node.path("oauthClientId").takeIf { it.isTextual }?.asText(),
            oauthClientSecret = node.path("oauthClientSecret").takeIf { it.isTextual && it.asText().isNotEmpty() }?.asText(),
            oauthTokenUrl = node.path("oauthTokenUrl").takeIf { it.isTextual }?.asText(),
            oauthScopes = node.path("oauthScopes").takeIf { it.isTextual }?.asText(),
            oauthRefreshToken = node.path("oauthRefreshToken").takeIf { it.isTextual && it.asText().isNotEmpty() }?.asText(),
            pricingMode = node.path("pricingMode").takeIf { it.isTextual }?.asText(),
            pricePerMillionTokens = node.path("pricePerMillionTokens")
                .takeIf { it.isNumber && it.asDouble() > 0 }?.asDouble(),
            priceMonthly = node.path("priceMonthly")
                .takeIf { it.isNumber && it.asDouble() > 0 }?.asDouble(),
            proxyName = node.path("proxyName").takeIf { it.isTextual }?.asText(),
        )
    }

    private fun parseModelRequest(requestBody: String): ProviderModelService.ModelRequest {
        val node = parseJson(requestBody)
        return ProviderModelService.ModelRequest(
            publicName = node.path("publicName").asText(""),
            upstreamName = node.path("upstreamName").asText(""),
            reasoning = node.path("reasoning").asText("map"),
            maxCompletionParam = node.path("maxCompletionParam").asBoolean(false),
            priority = node.path("priority").asInt(100),
        )
    }

    private fun parseJson(requestBody: String): JsonNode =
        runCatching { objectMapper.readTree(requestBody) }.getOrElse {
            throw ApiError(HttpStatus.BAD_REQUEST, "invalid_request_error", "Некорректное тело запроса")
        }

    /** Лимит из тела запроса: число > 0 — задан, иначе null (не задан/сброшен). */
    private fun optionalLimit(node: JsonNode, field: String): Long? =
        node.path(field).takeIf { it.isNumber && it.asLong() > 0 }?.asLong()

    /** Объект JSON → карта строк; null-узел (нет поля) — пустая карта. */
    private fun toStringMap(node: JsonNode?): Map<String, String> {
        if (node == null || !node.isObject) return emptyMap()
        val result = HashMap<String, String>()
        node.fields().forEach { entry -> result[entry.key] = entry.value.asText() }
        return result
    }
}
