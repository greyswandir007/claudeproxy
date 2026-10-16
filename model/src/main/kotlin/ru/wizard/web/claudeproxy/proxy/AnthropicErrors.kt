package ru.wizard.web.claudeproxy.proxy

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets

/**
 * Форматирование ошибок Anthropic и запись их напрямую из WebFilter.
 */
object AnthropicErrors {
    private val objectMapper = ObjectMapper()

    /** Тело ошибки в формате Anthropic: {"type":"error","error":{…}}. */
    fun json(type: String, message: String): String =
        objectMapper.writeValueAsString(
            mapOf("type" to "error", "error" to mapOf("type" to type, "message" to message)),
        )

    /** Ответ об ошибке напрямую из WebFilter (мимо контроллеров). */
    fun write(
        exchange: ServerWebExchange,
        status: HttpStatusCode,
        type: String,
        message: String,
    ): Mono<Void> {
        val response: ServerHttpResponse = exchange.response
        response.statusCode = status
        response.headers.contentType = MediaType.APPLICATION_JSON
        val buffer: DataBuffer = response.bufferFactory()
            .wrap(json(type, message).toByteArray(StandardCharsets.UTF_8))
        return response.writeWith(Mono.just(buffer))
    }
}
