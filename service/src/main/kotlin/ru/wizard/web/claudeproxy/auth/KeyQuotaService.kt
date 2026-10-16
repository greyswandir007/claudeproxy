package ru.wizard.web.claudeproxy.auth

/**
 * Принудительное исполнение квот клиентского ключа: allowlist моделей
 * и лимиты токенов на окно/месяц (NULL = безлимит). Проверка — до похода
 * к провайдеру; превышение — 429 rate_limit_error, чужая модель — 403.
 */
interface KeyQuotaService {

    /** @throws ru.wizard.web.claudeproxy.proxy.ApiError 403/429 при нарушении. */
    suspend fun enforce(exchange: org.springframework.web.server.ServerWebExchange, model: String)
}
