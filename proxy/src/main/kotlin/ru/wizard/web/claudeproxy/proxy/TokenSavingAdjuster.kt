package ru.wizard.web.claudeproxy.proxy

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import org.springframework.stereotype.Component
import ru.wizard.web.claudeproxy.optimizer.OptimizerService
import ru.wizard.web.claudeproxy.routing.ModelRegistry
import java.security.MessageDigest

/**
 * Инструменты экономии токенов (вкл/выкл per provider через каталог оверрайдов):
 * - CACHE_INJECTION: anthropic — cache_control на конец system и tools, если клиент
 *   не поставил сам; openai — стабильный prompt_cache_key (хэш system+tools).
 * - TRIM_OLD_TOOL_RESULTS: tool_result старше последних KEEP_TOOL_RESULTS
 *   заменяются на «[trimmed]», а при включённой модели-оптимизаторе (M30) —
 *   осмысленным сжатием (провал оптимизатора = маркер, как раньше).
 * - DROP_OLD_TOOL_IMAGES: image-блоки старше последних KEEP_IMAGE_MESSAGES
 *   заменяются текстовым примечанием (~1600 токенов за картинку).
 *
 * Работает на внутреннем Claude-представлении; возвращает оценку сэкономленных
 * токенов (символы/4 + 1600 за картинку) — она фиксируется в usage_event.
 */
@Component
class TokenSavingAdjuster(
    private val objectMapper: ObjectMapper,
    private val optimizerService: OptimizerService,
) {
    private val logger = KotlinLogging.logger {}

    /** Подрезает запрос под бюджет оптимизатора; @return съэкономленные токены. */
    suspend fun adjust(requestRoot: ObjectNode, provider: ModelRegistry.ProviderInfo): Long {
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

    /** tool_result старше последних KEEP_TOOL_RESULTS → сжатие оптимизатором
     *  (M30) либо «[trimmed]»; возвращает сэкономленные токены. */
    private suspend fun trimOldToolResults(requestRoot: ObjectNode): Long {
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
        val toTrim = toolResultIndices.dropLast(KEEP_TOOL_RESULTS)
        val compressionResults = compressWithOptimizer(messages, toTrim)
        var savedCharacters = 0L
        for (trimPosition in toTrim.indices) {
            val (messageIndex, blockIndex) = toTrim[trimPosition]
            val block = (messages.get(messageIndex).get("content") as ArrayNode).get(blockIndex)
            val originalCharacters = blockContentCharacters(block)
            val compressedText = compressionResults?.get(trimPosition)?.compressedText
            if (compressedText != null) {
                (block as ObjectNode).put("content", compressedText)
                savedCharacters += (originalCharacters - compressedText.length).coerceAtLeast(0)
            } else {
                (block as ObjectNode).put("content", TRIMMED_MARKER)
                savedCharacters += originalCharacters
            }
        }
        return savedCharacters / 4
    }

    /**
     * Один батч-запрос к модели-оптимизатору по всем старым текстовым
     * tool_result; null — оптимизатор недоступен/сломался: блоки уходят
     * маркером, как в M11. Не имеет права уронить запрос.
     */
    private suspend fun compressWithOptimizer(
        messages: ArrayNode,
        toTrim: List<Pair<Int, Int>>,
    ): List<OptimizerService.CompressionResult>? {
        if (!optimizerService.isAvailable()) return null
        val texts = ArrayList<String>(toTrim.size)
        for ((messageIndex, blockIndex) in toTrim) {
            val content =
                (messages.get(messageIndex).get("content") as ArrayNode).get(blockIndex).get("content")
            texts.add(if (content != null && content.isTextual) content.asText() else "")
        }
        return try {
            optimizerService.compressToolResults(texts)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            logger.warn { "optimizer compression failed, using trim marker: ${exception.message}" }
            null
        }
    }

    /** Оценка размера tool_result-блока в символах (для расчёта экономии). */
    private fun blockContentCharacters(block: JsonNode): Long {
        val content = block.get("content") ?: return 0L
        return if (content.isTextual) {
            content.asText().length.toLong()
        } else {
            content.toString().length.toLong()
        }
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
