package ru.wizard.web.claudeproxy.auth

/**
 * Управление клиентскими ключами прокси: генерация, список, отзыв.
 * Полный ключ возвращается ровно один раз при создании.
 */
interface KeyManagementService {

    data class ClientKey(
        val id: Long,
        val name: String,
        val keyPrefix: String,
        val createdAt: Long,
        val lastUsedAt: Long?,
        val revokedAt: Long?,
    )

    data class CreatedKey(val clientKey: ClientKey, val fullKey: String)

    suspend fun list(): List<ClientKey>

    suspend fun create(name: String): CreatedKey

    /** @return false, если ключ не найден или уже отозван. */
    suspend fun revoke(id: Long): Boolean
}
