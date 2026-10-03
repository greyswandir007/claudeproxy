package ru.wizard.web.claudeproxy.api

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import ru.wizard.web.claudeproxy.optimizer.OptimizerService
import ru.wizard.web.claudeproxy.proxy.ApiError

/** Настройка и диагностика модели-оптимизатора (M30): сжатие старых tool_result. */
@RestController
class OptimizerController(
    private val optimizerService: OptimizerService,
) {

    /** Статистика оптимизатора. */
    @GetMapping("/api/optimizer/stats")
    fun stats(): ResponseEntity<OptimizerService.OptimizerStats> =
        ResponseEntity.ok(optimizerService.stats())

    /** Конфиг оптимизатора. */
    @GetMapping("/api/optimizer/config")
    suspend fun config(): ResponseEntity<OptimizerService.OptimizerConfig> =
        ResponseEntity.ok(optimizerService.config())

    /** Обновление конфига оптимизатора. */
    @PutMapping("/api/optimizer/config", consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun updateConfig(
        @RequestBody body: Map<String, Any?>,
    ): ResponseEntity<OptimizerService.OptimizerConfig> {
        val request = OptimizerService.OptimizerConfigRequest(
            enabled = body["enabled"] as? Boolean
                ?: throw ApiError(HttpStatus.BAD_REQUEST, "invalid_request_error", "enabled: Field required"),
            providerName = body["providerName"] as? String,
            model = body["model"] as? String,
        )
        try {
            optimizerService.updateConfig(request)
        } catch (exception: IllegalArgumentException) {
            throw ApiError(HttpStatus.BAD_REQUEST, "invalid_request_error", exception.message)
        }
        return ResponseEntity.ok(optimizerService.config())
    }
}
