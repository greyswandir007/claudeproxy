package ru.wizard.web.claudeproxy.routing.impl

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.oshai.kotlinlogging.KotlinLogging
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.springframework.stereotype.Component
import ru.wizard.web.claudeproxy.config.ProxyProperties
import ru.wizard.web.claudeproxy.routing.ConversationAffinityService
import ru.wizard.web.claudeproxy.util.CanonicalJsonSerializer

/**
 * In-memory хранилище привязок разговоров: ConcurrentHashMap с TTL от времени
 * последнего успешного хода и лимитом записей. Чистка ленивая — заодно с
 * каждой 64-й записью и при переполнении (стиль чистки кэша запросов).
 * Привязки переживают перезагрузку снапшота реестра, но не рестарт процесса.
 */
@Component
class InMemoryConversationAffinityService(
    private val proxyProperties: ProxyProperties,
) : ConversationAffinityService {

    private data class Binding(val providerName: String, val lastSeenMilliseconds: Long)

    private val bindings = ConcurrentHashMap<String, Binding>()
    private val bindsTotal = AtomicLong()
    private val hitsTotal = AtomicLong()
    private val missesTotal = AtomicLong()
    private val evictionsExpired = AtomicLong()
    private val evictionsOverflow = AtomicLong()

    override fun conversationKey(requestRoot: JsonNode): String? {
        if (!proxyProperties.conversationAffinity.enabled) return null
        val messages = requestRoot.path("messages")
        if (!messages.isArray || messages.size() == 0) return null
        val prefix = ObjectNode(JsonNodeFactory.instance)
        requestRoot.get("system")?.let { prefix.set<JsonNode>("system", it) }
        requestRoot.get("tools")?.let { prefix.set<JsonNode>("tools", it) }
        prefix.set<JsonNode>("messages", ArrayNode(JsonNodeFactory.instance).add(messages.get(0)))
        return sha256Hex(CanonicalJsonSerializer.serialize(prefix))
    }

    override fun boundProviderName(
        model: String,
        conversationKey: String?,
        nowMilliseconds: Long,
    ): String? {
        if (conversationKey == null || !proxyProperties.conversationAffinity.enabled) return null
        val storageKey = storageKey(model, conversationKey)
        val binding = bindings[storageKey]
        if (binding == null) {
            missesTotal.incrementAndGet()
            return null
        }
        if (nowMilliseconds - binding.lastSeenMilliseconds > ttlMilliseconds()) {
            // двухаргументный remove: не затереть параллельно обновлённую привязку
            bindings.remove(storageKey, binding)
            evictionsExpired.incrementAndGet()
            missesTotal.incrementAndGet()
            return null
        }
        hitsTotal.incrementAndGet()
        return binding.providerName
    }

    override fun bind(
        model: String,
        conversationKey: String?,
        providerName: String,
        nowMilliseconds: Long,
    ) {
        if (conversationKey == null || !proxyProperties.conversationAffinity.enabled) return
        bindings[storageKey(model, conversationKey)] = Binding(providerName, nowMilliseconds)
        if (bindsTotal.incrementAndGet() % LAZY_EVICTION_PERIOD == 0L ||
            bindings.size > proxyProperties.conversationAffinity.maxEntries
        ) {
            evict(nowMilliseconds)
        }
    }

    override fun diagnostics(): ConversationAffinityService.ConversationAffinityDiagnostics {
        return ConversationAffinityService.ConversationAffinityDiagnostics(
            enabled = proxyProperties.conversationAffinity.enabled,
            entries = bindings.size,
            maxEntries = proxyProperties.conversationAffinity.maxEntries,
            binds = bindsTotal.get(),
            hits = hitsTotal.get(),
            misses = missesTotal.get(),
            evictionsExpired = evictionsExpired.get(),
            evictionsOverflow = evictionsOverflow.get(),
        )
    }

    /** Ключ хранилища: разговор в рамках одной публичной модели. */
    private fun storageKey(model: String, conversationKey: String): String = "$model::$conversationKey"

    private fun ttlMilliseconds(): Long = proxyProperties.conversationAffinity.ttlSeconds * 1000

    /** Ленивая чистка: сначала протухшие, затем при переполнении — самые старые по lastSeen. */
    private fun evict(nowMilliseconds: Long) {
        val ttlMilliseconds = ttlMilliseconds()
        val expiredKeys = ArrayList<String>()
        for ((storageKey, binding) in bindings) {
            if (nowMilliseconds - binding.lastSeenMilliseconds > ttlMilliseconds) expiredKeys.add(storageKey)
        }
        for (storageKey in expiredKeys) bindings.remove(storageKey)
        evictionsExpired.addAndGet(expiredKeys.size.toLong())
        val maxEntries = proxyProperties.conversationAffinity.maxEntries
        if (bindings.size > maxEntries) {
            val overflowKeys = bindings.entries
                .sortedBy { it.value.lastSeenMilliseconds }
                .take(bindings.size - maxEntries)
                .map { it.key }
            for (storageKey in overflowKeys) bindings.remove(storageKey)
            evictionsOverflow.addAndGet(overflowKeys.size.toLong())
            logger.debug { "Conversation affinity overflow evicted ${overflowKeys.size} oldest bindings" }
        }
    }

    private fun sha256Hex(payload: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }

    private companion object {
        private val logger = KotlinLogging.logger {}
        private const val LAZY_EVICTION_PERIOD = 64L
    }
}
