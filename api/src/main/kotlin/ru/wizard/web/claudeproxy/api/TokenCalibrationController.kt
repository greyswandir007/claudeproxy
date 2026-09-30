package ru.wizard.web.claudeproxy.api

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import ru.wizard.web.claudeproxy.usage.TokenCalibrationEntry
import ru.wizard.web.claudeproxy.usage.TokenCalibrationService

/** Диагностика калибровки count_tokens (M28): накопленные коэффициенты и сброс. */
@RestController
class TokenCalibrationController(
    private val tokenCalibrationService: TokenCalibrationService,
) {

    @GetMapping("/api/token-calibration")
    suspend fun entries(): ResponseEntity<List<TokenCalibrationEntry>> =
        ResponseEntity.ok(tokenCalibrationService.entries())

    @DeleteMapping("/api/token-calibration")
    suspend fun clear(): ResponseEntity<Void> {
        tokenCalibrationService.clear()
        return ResponseEntity.noContent().build()
    }
}
