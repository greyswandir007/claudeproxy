package ru.wizard.web.claudeproxy.proxy.openai.inbound

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets

/**
 * Формат ошибок OpenAI ({"error":{"message","type",...}}) для входящих
 * /v1/chat/completions-запросов.
 */
object OpenAiCompatibilityErrors {
    private val objectMapper = ObjectMapper()

    const val OPENAI_INBOUND_PATH_PREFIX = "/v1/chat/completions"

    fun isInboundPath(path: String): Boolean = path.startsWith(OPENAI_INBOUND_PATH_PREFIX)

    fun json(type: String, message: String): String =
        objectMapper.writeValueAsString(
            mapOf(
                "error" to mapOf(
                    "message" to message,
                    "type" to type,
                    "param" to null,
                    "code" to null,
                ),
            ),
        )

    /** Ответ об ошибке напрямую из WebFilter в формате OpenAI. */
    fun write(exchange: ServerWebExchange, status: HttpStatusCode, type: String, message: String): Mono<Void> {
        val response: ServerHttpResponse = exchange.response
        response.statusCode = status
        response.headers.contentType = MediaType.APPLICATION_JSON
        val buffer: DataBuffer = response.bufferFactory()
            .wrap(json(type, message).toByteArray(StandardCharsets.UTF_8))
        return response.writeWith(Mono.just(buffer))
    }
}
