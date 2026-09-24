package ru.wizard.web.claudeproxy.routing.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import ru.wizard.web.claudeproxy.config.ProxyProperties
import ru.wizard.web.claudeproxy.routing.ModelRegistry
import ru.wizard.web.claudeproxy.routing.ModelRegistry.Route

/**
 * Статический реестр: строится один раз при старте из конфигурации.
 * Дубли public-имён — ошибка старта (балансировки в v1 нет).
 */
@Component
class StaticModelRegistry(proxyProperties: ProxyProperties) : ModelRegistry {
    private val logger = KotlinLogging.logger {}

    private val routes: Map<String, Route> = buildMap {
        for (provider in proxyProperties.providers) {
            require(provider.type == "anthropic" || provider.type == "openai") {
                "Провайдер '${provider.name}': неизвестный type '${provider.type}' (anthropic|openai)"
            }
            require(provider.baseUrl.isNotBlank()) { "Провайдер '${provider.name}': не задан base-url" }
            require(provider.models.isNotEmpty()) { "Провайдер '${provider.name}': список моделей пуст" }
            for (modelMapping in provider.models) {
                require(modelMapping.`public`.isNotBlank()) {
                    "Провайдер '${provider.name}': пустое public-имя модели"
                }
                require(modelMapping.upstream.isNotBlank()) {
                    "Провайдер '${provider.name}': пустое upstream-имя модели '${modelMapping.`public`}'"
                }
                val previous = put(modelMapping.`public`, Route(provider, modelMapping))
                check(previous == null) {
                    "Дубликат public-модели '${modelMapping.`public`}': провайдеры " +
                        "'${previous!!.provider.name}' и '${provider.name}'"
                }
            }
        }
    }

    /** Настраиваемый список: allow-фильтр поверх реестра (порядок = порядку allow). */
    private val exposed: List<String> =
        proxyProperties.models.allow.ifEmpty { routes.keys.toList() }

    private val exposedSet: Set<String> = exposed.toSet()

    init {
        exposed.filterNot { it in routes }.forEach {
            logger.warn { "models.allow содержит неизвестную модель '$it' — игнорируется" }
        }
    }

    override fun find(model: String): Route? = routes[model]

    override fun isExposed(model: String): Boolean = exposedSet.contains(model)

    override fun exposedModels(): List<String> = exposed
}
