package ru.wizard.web.claudeproxy.routing

import ru.wizard.web.claudeproxy.config.ProxyProperties

/**
 * Реестр моделей: public-имя → (провайдер, upstream-имя, флаги).
 */
interface ModelRegistry {

    data class Route(val provider: ProxyProperties.Provider, val mapping: ProxyProperties.ModelMapping)

    fun find(model: String): Route?

    fun isExposed(model: String): Boolean

    fun exposedModels(): List<String>
}
