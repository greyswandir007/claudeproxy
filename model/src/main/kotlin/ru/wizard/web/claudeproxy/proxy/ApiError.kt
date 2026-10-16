package ru.wizard.web.claudeproxy.proxy

import org.springframework.http.HttpStatusCode

/**
 * Ошибка, которую клиент получает в формате ошибок Anthropic.
 */
open class ApiError(val status: HttpStatusCode, val type: String, message: String?) :
    RuntimeException(message)
