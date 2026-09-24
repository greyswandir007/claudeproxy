package ru.wizard.web.claudeproxy.proxy

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import ru.wizard.web.claudeproxy.routing.ModelRegistry

/**
 * GET /v1/models и /v1/models/{id} — список собирается из конфига.
 */
@RestController
class ModelsController(private val registry: ModelRegistry) {

    @GetMapping("/v1/models")
    fun list(): Map<String, Any?> {
        val models = registry.exposedModels()
        return mapOf(
            "data" to models.map { modelObj(it) },
            "first_id" to models.firstOrNull(),
            "has_more" to false,
            "last_id" to models.lastOrNull(),
        )
    }

    @GetMapping("/v1/models/{id}")
    fun one(@PathVariable id: String): Map<String, Any?> =
        if (registry.isExposed(id)) {
            modelObj(id)
        } else {
            throw ApiError(HttpStatus.NOT_FOUND, "not_found_error", "model: $id not found")
        }

    private fun modelObj(id: String): Map<String, Any?> = mapOf(
        "type" to "model",
        "id" to id,
        "display_name" to id,
        "created_at" to "2026-01-01T00:00:00Z",
    )
}
