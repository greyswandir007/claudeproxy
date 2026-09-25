package ru.wizard.web.claudeproxy.api

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import ru.wizard.web.claudeproxy.auth.KeyManagementService
import ru.wizard.web.claudeproxy.proxy.ApiError

/**
 * /api/keys — генерация, список и отзыв клиентских ключей прокси.
 * Полный ключ возвращается ровно один раз — при создании.
 */
@RestController
class KeyController(
    private val keyManagementService: KeyManagementService,
    private val objectMapper: ObjectMapper,
) {

    @GetMapping("/api/keys")
    suspend fun list(): List<KeyManagementService.ClientKey> = keyManagementService.list()

    @PostMapping("/api/keys", consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun create(@RequestBody requestBody: String): ResponseEntity<KeyManagementService.CreatedKey> {
        val request = parseKeyRequest(requestBody)
        return ResponseEntity.status(HttpStatus.CREATED).body(keyManagementService.create(request))
    }

    /** Обновление квот/allowlist ключа (сам ключ не меняется). */
    @PutMapping("/api/keys/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun update(
        @PathVariable id: Long,
        @RequestBody requestBody: String,
    ): KeyManagementService.ClientKey = keyManagementService.update(id, parseKeyRequest(requestBody))

    @PostMapping("/api/keys/{id}/revoke")
    suspend fun revoke(@PathVariable id: Long): Map<String, Boolean> {
        val revoked = keyManagementService.revoke(id)
        if (!revoked) {
            throw ApiError(HttpStatus.NOT_FOUND, "not_found_error", "Ключ не найден или уже отозван")
        }
        return mapOf("revoked" to true)
    }

    private fun parseKeyRequest(requestBody: String): KeyManagementService.KeyRequest {
        val node = runCatching { objectMapper.readTree(requestBody) }.getOrElse {
            throw ApiError(HttpStatus.BAD_REQUEST, "invalid_request_error", "Некорректное тело запроса")
        }
        val name = node.path("name").asText("")
        if (name.isBlank()) {
            throw ApiError(HttpStatus.BAD_REQUEST, "invalid_request_error", "name: Field required")
        }
        val allowedModels = ArrayList<String>()
        node.path("allowedModels").takeIf { it.isArray }?.forEach { entry ->
            if (entry.isTextual && entry.asText().isNotBlank()) allowedModels.add(entry.asText())
        }
        return KeyManagementService.KeyRequest(
            name = name,
            allowedModels = allowedModels,
            limitWindowTokens = optionalPositiveLong(node, "limitWindowTokens"),
            limitMonthTokens = optionalPositiveLong(node, "limitMonthTokens"),
        )
    }

    private fun optionalPositiveLong(node: com.fasterxml.jackson.databind.JsonNode, field: String): Long? =
        node.path(field).takeIf { it.isNumber && it.asLong() > 0 }?.asLong()
}
