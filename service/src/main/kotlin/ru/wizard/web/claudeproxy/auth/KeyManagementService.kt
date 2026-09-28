package ru.wizard.web.claudeproxy.auth

/**
 * Управление клиентскими ключами прокси: генерация, список, отзыв, квоты.
 * Полный ключ возвращается ровно один раз при создании.
 */
interface KeyManagementService {

    data class ClientKey(
        val id: Long,
        val name: String,
        val keyPrefix: String,
        /** Пустой список = все модели (безлимит по моделям). */
        val allowedModels: List<String> = emptyList(),
        /** NULL = безлимит; квота токенов на 5-часовое окно. */
        val limitWindowTokens: Long? = null,
        /** NULL = безлимит; квота токенов на скользящие 30 дней. */
        val limitMonthTokens: Long? = null,
        val createdAt: Long,
        val lastUsedAt: Long?,
        val revokedAt: Long?,
    )

    data class CreatedKey(val clientKey: ClientKey, val fullKey: String)

    data class KeyRequest(
        val name: String,
        val allowedModels: List<String> = emptyList(),
        val limitWindowTokens: Long? = null,
        val limitMonthTokens: Long? = null,
    )

    suspend fun list(): List<ClientKey>

    suspend fun create(request: KeyRequest): CreatedKey

    /** Обновление квот/allowlist (сам ключ не меняется). */
    suspend fun update(id: Long, request: KeyRequest): ClientKey

    /** @return false, если ключ не найден или уже отозван. */
    suspend fun revoke(id: Long): Boolean
}
