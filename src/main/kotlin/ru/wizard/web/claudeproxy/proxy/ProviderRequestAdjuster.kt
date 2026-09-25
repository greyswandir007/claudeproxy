package ru.wizard.web.claudeproxy.proxy

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import ru.wizard.web.claudeproxy.providers.ProviderSettingCatalog
import ru.wizard.web.claudeproxy.routing.ModelRegistry

/**
 * Применяет настройки поведения провайдера к запросу во внутреннем (Claude)
 * представлении: маппер effort-уровней и оверрайды из каталога. Работает на
 * копии тела, которую хендлеры готовят для конкретного маршрута.
 */
@Component
class ProviderRequestAdjuster(private val objectMapper: ObjectMapper) {

    /** Ключи оверрайдов, которые интерпретируются хендлерами отдельно (таймаут). */
    fun apiTimeoutMilliseconds(provider: ModelRegistry.ProviderInfo): Long? =
        provider.settingOverrides[TIMEOUT_KEY]?.toLongOrNull()?.takeIf { it > 0 }

    fun adjust(requestRoot: ObjectNode, provider: ModelRegistry.ProviderInfo): ObjectNode {
        applyEffortMapping(requestRoot, provider)
        applySettingOverrides(requestRoot, provider)
        return requestRoot
    }

    /** Входной effort (output_config.effort, синонимы нормализуются) → значение провайдера. */
    private fun applyEffortMapping(requestRoot: ObjectNode, provider: ModelRegistry.ProviderInfo) {
        if (provider.effortMapping.isEmpty()) return
        val effortNode = requestRoot.path("output_config").path("effort")
        if (!effortNode.isTextual) return
        val canonical = ProviderSettingCatalog.normalizeEffortLevel(effortNode.asText())
        val mapped = provider.effortMapping[canonical] ?: return
        ensureOutputConfig(requestRoot).put("effort", mapped)
    }

    private fun applySettingOverrides(requestRoot: ObjectNode, provider: ModelRegistry.ProviderInfo) {
        val overrides = provider.settingOverrides
        if (overrides.isEmpty()) return

        overrides[FORCED_EFFORT_KEY]?.let { forcedEffort ->
            val canonical = ProviderSettingCatalog.normalizeEffortLevel(forcedEffort)
            val value = provider.effortMapping[canonical] ?: forcedEffort
            ensureOutputConfig(requestRoot).put("effort", value)
        }
        if (overrides[DISABLE_THINKING_KEY] == "true") {
            requestRoot.putObject("thinking").put("type", "disabled")
            // отключённый thinking + explicit effort бессмысленны
            requestRoot.remove("output_config")
        }
        overrides[TEMPERATURE_KEY]?.let { temperature ->
            requestRoot.put("temperature", temperature.toDouble())
        }
        overrides[TOP_P_KEY]?.let { topP ->
            requestRoot.put("top_p", topP.toDouble())
        }
        overrides[MAX_OUTPUT_TOKENS_KEY]?.let { maxOutput ->
            val currentMax = requestRoot.path("max_tokens").asLong(0)
            val cap = maxOutput.toLong()
            if (currentMax <= 0 || currentMax > cap) {
                requestRoot.put("max_tokens", cap)
            }
        }
        overrides[EXTRA_STOP_KEY]?.let { extraStop ->
            val stopSequences = requestRoot.get("stop_sequences") as? ArrayNode
                ?: objectMapper.createArrayNode().also { requestRoot.set<JsonNode>("stop_sequences", it) }
            if (stopSequences.none { it.asText() == extraStop }) {
                stopSequences.add(extraStop)
            }
        }
        overrides[MAX_INPUT_TOKENS_KEY]?.let { maxInput ->
            val estimatedInput = estimateTokens(requestRoot)
            val limit = maxInput.toLong()
            if (estimatedInput > limit) {
                throw ApiError(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "request_too_large",
                    "Оценка входа ~$estimatedInput токенов превышает лимит провайдера " +
                        "'${provider.name}' ($limit). Сократите контекст или поднимите MAX_INPUT_TOKENS.",
                )
            }
        }
    }

    private fun ensureOutputConfig(requestRoot: ObjectNode): ObjectNode =
        requestRoot.get("output_config") as? ObjectNode
            ?: requestRoot.putObject("output_config")

    /** Грубая оценка входа (~4 символа на токен + картинки), как в count_tokens. */
    private fun estimateTokens(requestRoot: JsonNode): Long {
        var characters = 0L
        var imageCount = 0L
        characters += textLength(requestRoot.path("system"))
        for (message in requestRoot.path("messages")) {
            characters += textLength(message.path("content"))
            imageCount += imageCount(message.path("content"))
        }
        for (tool in requestRoot.path("tools")) {
            characters += tool.path("name").asText("").length.toLong()
            characters += tool.path("description").asText("").length.toLong()
            characters += tool.path("input_schema").toString().length.toLong()
        }
        return characters / 4 + imageCount * 1600
    }

    private fun textLength(node: JsonNode?): Long {
        if (node == null || node.isNull) return 0
        if (node.isTextual) return node.asText().length.toLong()
        if (!node.isArray && !node.isObject) return 0
        var total = 0L
        if (node.isObject) {
            node.path("text").takeIf { it.isTextual }?.let { total += it.asText().length.toLong() }
            node.path("input").takeIf { it.isObject }?.let { total += it.toString().length.toLong() }
        }
        for (child in node) {
            if (child.path("type").asText("") == "image") continue
            total += textLength(child)
        }
        return total
    }

    private fun imageCount(node: JsonNode?): Long {
        if (node == null || !node.isArray) return 0
        return node.count { it.path("type").asText("") == "image" }.toLong()
    }

    private companion object {
        const val TIMEOUT_KEY = "API_TIMEOUT_MS"
        const val FORCED_EFFORT_KEY = "FORCED_REASONING_EFFORT"
        const val DISABLE_THINKING_KEY = "DISABLE_THINKING"
        const val TEMPERATURE_KEY = "TEMPERATURE_OVERRIDE"
        const val TOP_P_KEY = "TOP_P_OVERRIDE"
        const val MAX_OUTPUT_TOKENS_KEY = "MAX_OUTPUT_TOKENS"
        const val EXTRA_STOP_KEY = "EXTRA_STOP_SEQUENCE"
        const val MAX_INPUT_TOKENS_KEY = "MAX_INPUT_TOKENS"
    }
}
