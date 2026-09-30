package ru.wizard.web.claudeproxy.providers

/**
 * Прокси/туннели доступа к провайдерам (M31): CRUD и проверка соединения.
 * Пароль наружу не отдаётся (только факт наличия), поддерживает ${ENV:...}.
 * Типы: HTTP, HTTPS (CONNECT-туннель), SOCKS4 (без пароля), SOCKS5.
 */
interface ProxyEndpointService {

    /** Представление прокси для UI. */
    data class ProxyEndpointView(
        val id: Long,
        val name: String,
        /** HTTP | HTTPS | SOCKS4 | SOCKS5. */
        val type: String,
        val host: String,
        val port: Int,
        val username: String?,
        val hasPassword: Boolean,
        val enabled: Boolean,
        val lastCheckStatus: String?,
        val lastCheckAt: Long?,
        /** Провайдеры, привязанные к этому прокси. */
        val providerNames: List<String>,
        val createdAt: Long,
        val updatedAt: Long,
    )

    /**
     * Запрос на создание/обновление; поля nullable для частичного обновления
     * (null = не менять). Пароль: null/пусто = не менять (очистить нельзя,
     * можно перезаписать — как api-ключ провайдера).
     */
    data class ProxyEndpointRequest(
        val name: String?,
        val type: String?,
        val host: String?,
        val port: Int?,
        val username: String?,
        val password: String?,
        val enabled: Boolean?,
    )

    /** Конфиг прокси для фабрики WebClient'ов; пароль — сырая ссылка. */
    data class ProxyEndpointConfig(
        val name: String,
        val type: String,
        val host: String,
        val port: Int,
        val username: String?,
        val passwordReference: String?,
    )

    suspend fun listProxies(): List<ProxyEndpointView>

    /** Создаёт прокси; 400 — кривые поля, 409 — дубль имени. */
    suspend fun createProxy(request: ProxyEndpointRequest): ProxyEndpointView

    /** Обновляет прокси; 404 — не найден, 409 — новое имя занято. */
    suspend fun updateProxy(id: Long, request: ProxyEndpointRequest): ProxyEndpointView

    /** Удаляет прокси; 404 — не найден, 409 — к нему привязаны провайдеры. */
    suspend fun deleteProxy(id: Long)

    /**
     * Проверка соединения через прокси: GET testUrl (дефолт — быстрая
     * публичная генерация 204), таймаут 5 с. Результат пишется в
     * last_check_status / last_check_at и возвращается в представлении.
     */
    suspend fun checkProxy(id: Long, testUrl: String?): ProxyEndpointView

    /** Конфиг по имени для фабрики; null — не найден или выключен. */
    suspend fun configByName(name: String): ProxyEndpointConfig?
}
