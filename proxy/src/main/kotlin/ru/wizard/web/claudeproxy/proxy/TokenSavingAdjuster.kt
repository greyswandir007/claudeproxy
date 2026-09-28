package ru.wizard.web.claudeproxy.proxy

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.stereotype.Component
import ru.wizard.web.claudeproxy.routing.ModelRegistry
import java.security.MessageDigest

/**
 * Инструменты экономии токенов (вкл/выкл per provider через каталог оверрайдов):
 * - CACHE_INJECTION: anthropic — cache_control на конец system и tools, если клиент
 *   не поставил сам; openai — стабильный prompt_cache_key (хэш system+tools).
 * - TRIM_OLD_TOOL_RESULTS: tool_result старше последних KEEP_TOOL_RESULTS
 *   заменяются на «[trimmed]».
 * - DROP_OLD_TOOL_IMAGES: image-блоки старше последних KEEP_IMAGE_MESSAGES
 *   заменяются текстовым примечанием (~1600 токенов за картинку).
 *
 * Работает на внутреннем Claude-представлении; возвращает оценку сэкономленных
 * токенов (символы/4 + 1600 за картинку) — она фиксируется в usage_event.
 */
@Component
class TokenSavingAdjuster(private val objectMapper: ObjectMapper) {

    fun adjust(requestRoot: ObjectNode, provider: ModelRegistry.ProviderInfo): Long {
        var savedTokens = 0L
        val overrides = provider.settingOverrides
        if (overrides[CACHE_INJECTION_KEY] == "true") {
            applyCacheInjection(requestRoot, provider)
        }
        if (overrides[TRIM_TOOL_RESULTS_KEY] == "true") {
            savedTokens += trimOldToolResults(requestRoot)
        }
        if (overrides[DROP_OLD_IMAGES_KEY] == "true") {
            savedTokens += dropOldToolImages(requestRoot)
        }
        return savedTokens
    }

    /** anthropic: cache_control на последний system-блок и последний tool;
     *  openai: стабильный prompt_cache_key (стабильный префикс = стабильный кэш провайдера). */
    private fun applyCacheInjection(requestRoot: ObjectNode, provider: ModelRegistry.ProviderInfo) {
        if (provider.type == "anthropic") {
            val system = requestRoot.get("system")
            if (system != null && system.isArray && system.size() > 0) {
                markCacheControl(system.get(system.size() - 1))
            }
            val tools = requestRoot.get("tools")
            if (tools != null && tools.isArray && tools.size() > 0) {
                markCacheControl(tools.get(tools.size() - 1))
            }
        } else {
            val stableKey = stablePrefixHash(requestRoot)
            if (stableKey != null && !requestRoot.has("prompt_cache_key")) {
                requestRoot.put("prompt_cache_key", stableKey)
            }
        }
    }

    private fun markCacheControl(block: JsonNode?) {
        if (block == null || !block.isObject) return
        if (block.has("cache_control")) return // клиент уже расставил маркеры
        (block as ObjectNode).putObject("cache_control").put("type", "ephemeral")
    }

    /** Хэш стабильной части запроса (system + tools) — стабильный ключ кэша сессии. */
    private fun stablePrefixHash(requestRoot: ObjectNode): String? {
        val system = requestRoot.get("system") ?: return null
        val tools = requestRoot.get("tools")
        val digest = MessageDigest.getInstance("MD5")
        digest.update(system.toString().toByteArray())
        if (tools != null) digest.update(tools.toString().toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }.take(24)
    }

    /** tool_result старше последних KEEP_TOOL_RESULTS → «[trimmed]»; возвращает токены. */
    private fun trimOldToolResults(requestRoot: ObjectNode): Long {
        val messages = requestRoot.get("messages") as? ArrayNode ?: return 0
        // индексы tool_result-блоков в порядке следования
        val toolResultIndices = ArrayList<Pair<Int, Int>>() // (сообщение, блок)
        for (messageIndex in 0 until messages.size()) {
            val content = messages.get(messageIndex).path("content")
            if (!content.isArray) continue
            for (blockIndex in 0 until content.size()) {
                if (content.get(blockIndex).path("type").asText() == "tool_result") {
                    toolResultIndices.add(messageIndex to blockIndex)
                }
            }
        }
        if (toolResultIndices.size <= KEEP_TOOL_RESULTS) return 0
        var savedCharacters = 0L
        val toTrim = toolResultIndices.dropLast(KEEP_TOOL_RESULTS)
        for ((messageIndex, blockIndex) in toTrim) {
            val block = (messages.get(messageIndex).get("content") as ArrayNode).get(blockIndex)
            val content = block.get("content")
            if (content != null && content.isTextual) {
                savedCharacters += content.asText().length.toLong()
            } else if (content != null && content.isArray) {
                savedCharacters += content.toString().length.toLong()
            } else if (block.has("content")) {
                savedCharacters += block.get("content").toString().length.toLong()
            }
            (block as ObjectNode).put("content", TRIMMED_MARKER)
        }
        return savedCharacters / 4
    }

    /** image-блоки в сообщениях старше последних KEEP_IMAGE_MESSAGES → текст-заметка. */
    private fun dropOldToolImages(requestRoot: ObjectNode): Long {
        val messages = requestRoot.get("messages") as? ArrayNode ?: return 0
        if (messages.size() <= KEEP_IMAGE_MESSAGES) return 0
        val cutoff = messages.size() - KEEP_IMAGE_MESSAGES
        var removedImages = 0L
        for (messageIndex in 0 until cutoff) {
            val content = messages.get(messageIndex).path("content")
            if (!content.isArray) continue
            val contentArray = content as ArrayNode
            for (blockIndex in (contentArray.size() - 1) downTo 0) {
                if (contentArray.get(blockIndex).path("type").asText() == "image") {
                    contentArray.remove(blockIndex)
                    removedImages++
                }
            }
            if (removedImages > 0 && contentArray.size() == 0) {
                contentArray.addObject().put("type", "text").put("text", "[images removed by proxy]")
            }
        }
        return removedImages * TOKENS_PER_IMAGE
    }

    companion object {
        const val CACHE_INJECTION_KEY = "CACHE_INJECTION"
        const val TRIM_TOOL_RESULTS_KEY = "TRIM_OLD_TOOL_RESULTS"
        const val DROP_OLD_IMAGES_KEY = "DROP_OLD_TOOL_IMAGES"

        const val KEEP_TOOL_RESULTS = 4
        const val KEEP_IMAGE_MESSAGES = 2
        const val TOKENS_PER_IMAGE = 1600L
        const val TRIMMED_MARKER = "[trimmed by claudeproxy]"
    }
}
