package ru.wizard.web.claudeproxy.proxy

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

/**
 * Ошибка от провайдера: тело и статус пробрасываются клиенту как есть
 * (включая retry-after).
 */
class UpstreamError(
    status: HttpStatusCode,
    /** Полное тело ответа провайдера (релеится клиенту и пишется в error_detail). */
    val upstreamBody: String,
    /** Content-Type тела провайдера; воспроизводится в ответе клиенту. */
    val contentType: MediaType?,
    /** Заголовок Retry-After провайдера, если он был. */
    val retryAfter: String?,
) : ApiError(status, "api_error", "upstream ${status.value()}") {

    companion object {
        /** Собирает ошибку из прочитанного ResponseEntity провайдера. */
        fun from(entity: ResponseEntity<String>) = UpstreamError(
            entity.statusCode,
            entity.body ?: "",
            entity.headers.contentType,
            entity.headers.getFirst(HttpHeaders.RETRY_AFTER),
        )
    }
}
