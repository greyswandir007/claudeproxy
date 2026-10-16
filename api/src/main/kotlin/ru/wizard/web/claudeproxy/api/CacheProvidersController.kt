package ru.wizard.web.claudeproxy.api

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import ru.wizard.web.claudeproxy.cacheprovider.impl.CacheProviderRequest
import ru.wizard.web.claudeproxy.cacheprovider.impl.CacheProviderView
import ru.wizard.web.claudeproxy.cacheprovider.impl.JdbcCacheProviderRegistry
import ru.wizard.web.claudeproxy.proxy.ApiError

/** REST вкладки «Кэш»: внешние кэш-провайдеры (CRUD + статус по спеке). */
@RestController
class CacheProvidersController(
    private val registry: JdbcCacheProviderRegistry,
) {

    /** Все внешние кэш-провайдеры (встроенный local не хранится в БД). */
    @GetMapping("/api/cache-providers")
    suspend fun list(): List<CacheProviderView> = registry.list()

    /** Добавить кэш-провайдер по URL. */
    @PostMapping("/api/cache-providers", consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun create(@RequestBody request: CacheProviderRequest): CacheProviderView =
        try {
            registry.create(request)
        } catch (exception: Exception) {
            throw ApiError(HttpStatus.BAD_REQUEST, "invalid_request_error", exception.message ?: "не создан")
        }

    /** Обновить (URL/имя/включённость). */
    @PutMapping("/api/cache-providers/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun update(
        @PathVariable id: Long,
        @RequestBody request: CacheProviderRequest,
    ): CacheProviderView =
        try {
            registry.update(id, request)
        } catch (exception: Exception) {
            throw ApiError(HttpStatus.BAD_REQUEST, "invalid_request_error", exception.message ?: "не обновлён")
        }

    /** Удалить кэш-провайдер. */
    @DeleteMapping("/api/cache-providers/{id}")
    suspend fun delete(@PathVariable id: Long) {
        registry.delete(id)
    }

    /** Статус внешнего провайдера (/v1/cache/stats по спеке). */
    @GetMapping("/api/cache-providers/{id}/status")
    suspend fun status(@PathVariable id: Long): Map<String, Any?> {
        val view = registry.list().firstOrNull { it.id == id }
            ?: throw ApiError(HttpStatus.NOT_FOUND, "not_found_error", "кэш-провайдер не найден")
        return registry.provider(view.name).status()
    }

    /** Статистика встроенного local (request-кэш прокси). */
    @GetMapping("/api/cache-providers/local/status")
    suspend fun localStatus(): Map<String, Any?> = registry.local().status()
}
