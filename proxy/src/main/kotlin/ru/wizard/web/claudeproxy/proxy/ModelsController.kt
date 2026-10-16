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

    /** Публично видимые модели в формате Anthropic. */
    @GetMapping("/v1/models")
    fun list(): Map<String, Any?> {
        val models = registry.exposedModels()
        return mapOf(
            "object" to "list",
            "data" to models.map { modelObj(it) },
            "first_id" to models.firstOrNull(),
            "has_more" to false,
            "last_id" to models.lastOrNull(),
        )
    }

    /** Одна модель по публичному имени. */
    @GetMapping("/v1/models/{id}")
    fun one(@PathVariable id: String): Map<String, Any?> =
        if (registry.isExposed(id)) {
            modelObj(id)
        } else {
            throw ApiError(HttpStatus.NOT_FOUND, "not_found_error", "model: $id not found")
        }

    /**
     * Надмножество полей Anthropic и OpenAI: один эндпоинт обслуживает оба SDK
     * (лишние поля каждый клиент игнорирует).
     */
    private fun modelObj(id: String): Map<String, Any?> = mapOf(
        "id" to id,
        "object" to "model",
        "created" to CREATED_EPOCH_SECONDS,
        "owned_by" to "claudeproxy",
        "type" to "model",
        "display_name" to id,
        "created_at" to "2026-01-01T00:00:00Z",
    )

    private companion object {
        const val CREATED_EPOCH_SECONDS = 1_767_225_600L
    }
}
