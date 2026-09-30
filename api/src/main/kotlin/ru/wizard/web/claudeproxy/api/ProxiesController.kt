package ru.wizard.web.claudeproxy.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import ru.wizard.web.claudeproxy.providers.ProxyEndpointService
import ru.wizard.web.claudeproxy.providers.ProxyEndpointService.ProxyEndpointView
import ru.wizard.web.claudeproxy.proxy.ApiError

/** Управление прокси/туннелями доступа к провайдерам (M31). */
@RestController
class ProxiesController(
    private val proxyEndpointService: ProxyEndpointService,
    private val objectMapper: ObjectMapper,
) {

    @GetMapping("/api/proxies")
    suspend fun list(): List<ProxyEndpointView> = proxyEndpointService.listProxies()

    @PostMapping("/api/proxies", consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun create(@RequestBody body: String): ProxyEndpointView =
        proxyEndpointService.createProxy(parseProxyRequest(body))

    @PutMapping("/api/proxies/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun update(@PathVariable id: Long, @RequestBody body: String): ProxyEndpointView =
        proxyEndpointService.updateProxy(id, parseProxyRequest(body))

    @DeleteMapping("/api/proxies/{id}")
    suspend fun delete(@PathVariable id: Long) {
        proxyEndpointService.deleteProxy(id)
    }

    /** Проверка соединения через прокси; тело {"testUrl": "..."} необязательно. */
    @PostMapping("/api/proxies/{id}/check", consumes = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun check(@PathVariable id: Long, @RequestBody(required = false) body: String?): ProxyEndpointView {
        val testUrl = body
            ?.let { runCatching { objectMapper.readTree(it) }.getOrNull() }
            ?.path("testUrl")
            ?.takeIf { it.isTextual }
            ?.asText()
        return proxyEndpointService.checkProxy(id, testUrl)
    }

    /** Разбор тела запроса; некорректный JSON — 400 в общем стиле. */
    private fun parseProxyRequest(body: String): ProxyEndpointService.ProxyEndpointRequest {
        val root = runCatching { objectMapper.readTree(body) }.getOrNull()
        if (root == null || !root.isObject) {
            throw ApiError(HttpStatus.BAD_REQUEST, "invalid_request_error", "Некорректное тело запроса")
        }
        return ProxyEndpointService.ProxyEndpointRequest(
            name = root.text("name"),
            type = root.text("type"),
            host = root.text("host"),
            port = root.path("port").takeIf { it.isInt }?.asInt(),
            username = root.text("username"),
            password = root.text("password"),
            enabled = root.path("enabled").takeIf { it.isBoolean }?.asBoolean(),
        )
    }

    private fun JsonNode.text(field: String): String? =
        path(field).takeIf { it.isTextual }?.asText()
}
