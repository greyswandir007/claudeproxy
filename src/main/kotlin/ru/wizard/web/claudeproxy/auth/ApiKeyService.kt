package ru.wizard.web.claudeproxy.auth

/**
 * Клиентские ключи прокси: сид из YAML и аутентификация.
 * Полный ключ не хранится и не логируется — только SHA-256-хэш.
 */
interface ApiKeyService {

    /** Ключ, прошедший аутентификацию. */
    data class AuthorizedKey(val id: Long, val name: String)

    /** Вносит сид-ключи из YAML в БД (только если имени ещё нет). */
    fun seed()

    /** Аутентификация: хэш предъявленного ключа → активный ключ. */
    suspend fun authenticate(presentedKey: String): AuthorizedKey?

    companion object {
        /** Хэш полного ключа; в БД и логах хранится только он. */
        fun sha256Hex(value: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
