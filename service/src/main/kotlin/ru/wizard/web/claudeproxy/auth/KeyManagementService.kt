package ru.wizard.web.claudeproxy.auth

/**
 * Управление клиентскими ключами прокси: генерация, список, отзыв, квоты.
 * Полный ключ возвращается ровно один раз при создании.
 */
interface KeyManagementService {

    data class ClientKey(
        /** Идентификатор в БД. */
        val id: Long,
        /** Имя ключа (без «cpk_»-префикса). */
        val name: String,
        /** Первые символы ключа для отображения в дашборде. */
        val keyPrefix: String,
        /** Пустой список = все модели (безлимит по моделям). */
        val allowedModels: List<String> = emptyList(),
        /** NULL = безлимит; квота токенов на 5-часовое окно. */
        val limitWindowTokens: Long? = null,
        /** NULL = безлимит; квота токенов на скользящие 30 дней. */
        val limitMonthTokens: Long? = null,
        /** Создание, epoch millis. */
        val createdAt: Long,
        /** Последнее использование, epoch millis; null — не использовался. */
        val lastUsedAt: Long?,
        /** Отзыв, epoch millis; null — активен. */
        val revokedAt: Long?,
    )

    /** Результат создания: ключ из БД и полный секрет ровно один раз. */
    data class CreatedKey(val clientKey: ClientKey, val fullKey: String)

    /** Параметры создания/обновления ключа. */
    data class KeyRequest(
        /** Имя ключа (без «cpk_»-префикса). */
        val name: String,
        /** Пустой список = все модели. */
        val allowedModels: List<String> = emptyList(),
        /** NULL = безлимит; квота на 5-часовое окно. */
        val limitWindowTokens: Long? = null,
        /** NULL = безлимит; квота на скользящие 30 дней. */
        val limitMonthTokens: Long? = null,
    )

    /** Все неотозванные ключи. */
    suspend fun list(): List<ClientKey>

    /** Создаёт ключ; полный секрет возвращается только здесь. */
    suspend fun create(request: KeyRequest): CreatedKey

    /** Обновление квот/allowlist (сам ключ не меняется). */
    suspend fun update(id: Long, request: KeyRequest): ClientKey

    /** @return false, если ключ не найден или уже отозван. */
    suspend fun revoke(id: Long): Boolean
}
