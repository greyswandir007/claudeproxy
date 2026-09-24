package ru.wizard.web.claudeproxy.api

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.GetMapping
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
        val name = runCatching { objectMapper.readTree(requestBody).path("name").asText("") }
            .getOrDefault("")
        if (name.isBlank()) {
            throw ApiError(HttpStatus.BAD_REQUEST, "invalid_request_error", "name: Field required")
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(keyManagementService.create(name))
    }

    @PostMapping("/api/keys/{id}/revoke")
    suspend fun revoke(@PathVariable id: Long): Map<String, Boolean> {
        val revoked = keyManagementService.revoke(id)
        if (!revoked) {
            throw ApiError(HttpStatus.NOT_FOUND, "not_found_error", "Ключ не найден или уже отозван")
        }
        return mapOf("revoked" to true)
    }
}
