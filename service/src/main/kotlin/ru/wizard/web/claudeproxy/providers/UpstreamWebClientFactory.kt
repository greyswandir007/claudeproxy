package ru.wizard.web.claudeproxy.providers

import org.springframework.web.reactive.function.client.WebClient

/**
 * Фабрика WebClient'ов для исходящих вызовов провайдеров (M31): прямые и
 * через прокси-эндпоинты. Единственная точка создания клиентов — все вызовы
 * (основной путь, discovery, OAuth, оптимизатор) идут через неё.
 */
interface UpstreamWebClientFactory {

    /**
     * Клиент для провайдера с опциональным прокси: null/не найден/выключен —
     * прямой клиент (поведение как до M31). Клиенты кэшируются по имени
     * прокси; при изменении прокси кэш сбрасывается invalidate().
     */
    suspend fun webClient(proxyName: String?): WebClient

    /** Свежий клиент по готовому конфигу (проверка соединения, разовые нужды). */
    fun proxiedClient(config: ProxyEndpointService.ProxyEndpointConfig): WebClient

    /** Сброс кэша клиентов (вызывается после изменения/удаления прокси). */
    fun invalidate()
}
