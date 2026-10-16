package ru.wizard.web.claudeproxy.providers

/**
 * Прокси/туннели доступа к провайдерам (M31): CRUD и проверка соединения.
 * Пароль наружу не отдаётся (только факт наличия), поддерживает ${ENV:...}.
 * Типы: HTTP, HTTPS (CONNECT-туннель), SOCKS4 (без пароля), SOCKS5.
 */
interface ProxyEndpointService {

    /** Представление прокси для UI. */
    data class ProxyEndpointView(
        /** Идентификатор в БД. */
        val id: Long,
        /** Уникальное имя прокси (на него ссылаются провайдеры). */
        val name: String,
        /** HTTP | HTTPS | SOCKS4 | SOCKS5. */
        val type: String,
        /** Хост прокси. */
        val host: String,
        /** Порт прокси. */
        val port: Int,
        /** Логин; null — без авторизации. */
        val username: String?,
        /** Задан ли пароль (сам пароль наружу не отдаётся). */
        val hasPassword: Boolean,
        /** Выключенный прокси не используется фабрикой клиентов. */
        val enabled: Boolean,
        /** Итог последней проверки: ok | error: … ; null — не проверялся. */
        val lastCheckStatus: String?,
        /** Момент последней проверки, epoch millis; null — не проверялся. */
        val lastCheckAt: Long?,
        /** Провайдеры, привязанные к этому прокси. */
        val providerNames: List<String>,
        /** Создание, epoch millis. */
        val createdAt: Long,
        /** Последнее изменение, epoch millis. */
        val updatedAt: Long,
    )

    /**
     * Запрос на создание/обновление; поля nullable для частичного обновления
     * (null = не менять). Пароль: null/пусто = не менять (очистить нельзя,
     * можно перезаписать — как api-ключ провайдера).
     */
    data class ProxyEndpointRequest(
        /** Уникальное имя; null при обновлении = не менять. */
        val name: String?,
        /** HTTP | HTTPS | SOCKS4 | SOCKS5. */
        val type: String?,
        /** Хост прокси. */
        val host: String?,
        /** Порт прокси. */
        val port: Int?,
        /** Логин; null при обновлении = не менять. */
        val username: String?,
        /** Пароль; null/пусто = не менять. */
        val password: String?,
        /** Включённость. */
        val enabled: Boolean?,
    )

    /** Конфиг прокси для фабрики WebClient'ов; пароль — сырая ссылка. */
    data class ProxyEndpointConfig(
        /** Имя прокси. */
        val name: String,
        /** HTTP | HTTPS | SOCKS4 | SOCKS5. */
        val type: String,
        /** Хост прокси. */
        val host: String,
        /** Порт прокси. */
        val port: Int,
        /** Логин; null — без авторизации. */
        val username: String?,
        /** Пароль или ${ENV:...}-ссылка; null — без пароля. */
        val passwordReference: String?,
    )

    /** Все прокси для страницы «Прокси». */
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
