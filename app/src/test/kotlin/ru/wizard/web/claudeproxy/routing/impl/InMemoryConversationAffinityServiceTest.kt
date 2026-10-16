package ru.wizard.web.claudeproxy.routing.impl

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import ru.wizard.web.claudeproxy.config.ProxyProperties

/**
 * Юнит-тесты sticky-аффинности разговоров. Контекст Spring не поднимается.
 */
class InMemoryConversationAffinityServiceTest {

    private val objectMapper = ObjectMapper()

    private fun service(enabled: Boolean = true, ttlSeconds: Long = 3600, maxEntries: Int = 1000): InMemoryConversationAffinityService {
        val properties = ProxyProperties()
        properties.conversationAffinity.enabled = enabled
        properties.conversationAffinity.ttlSeconds = ttlSeconds
        properties.conversationAffinity.maxEntries = maxEntries
        return InMemoryConversationAffinityService(properties)
    }

    @Test
    fun `ключ стабилен при росте хвоста разговора`() {
        val affinityService = service()
        val first = affinityService.conversationKey(
            objectMapper.readTree(
                """
                {"model":"m","system":"ты ассистент","tools":[{"name":"t"}],
                 "messages":[{"role":"user","content":"привет"}]}
                """.trimIndent(),
            ),
        )
        val grown = affinityService.conversationKey(
            objectMapper.readTree(
                """
                {"model":"m","system":"ты ассистент","tools":[{"name":"t"}],
                 "messages":[{"role":"user","content":"привет"},{"role":"assistant","content":"здравствуй"},
                              {"role":"user","content":"как дела"}]}
                """.trimIndent(),
            ),
        )
        assertEquals(first, grown)
    }

    @Test
    fun `порядок полей не меняет ключ`() {
        val affinityService = service()
        val one = affinityService.conversationKey(
            objectMapper.readTree("""{"messages":[{"role":"user","content":"привет"}],"system":"s","tools":[]}"""),
        )
        val two = affinityService.conversationKey(
            objectMapper.readTree("""{"tools":[],"system":"s","messages":[{"role":"user","content":"привет"}]}"""),
        )
        assertEquals(one, two)
    }

    @Test
    fun `другое начало разговора даёт другой ключ`() {
        val affinityService = service()
        val one = affinityService.conversationKey(
            objectMapper.readTree("""{"system":"s","messages":[{"role":"user","content":"привет"}]}"""),
        )
        val otherFirstMessage = affinityService.conversationKey(
            objectMapper.readTree("""{"system":"s","messages":[{"role":"user","content":"здравствуй"}]}"""),
        )
        val otherSystem = affinityService.conversationKey(
            objectMapper.readTree("""{"system":"другая система","messages":[{"role":"user","content":"привет"}]}"""),
        )
        assertNotEquals(one, otherFirstMessage)
        assertNotEquals(one, otherSystem)
    }

    @Test
    fun `без messages ключа нет`() {
        assertNull(service().conversationKey(objectMapper.readTree("""{"model":"m","messages":[]}""")))
        assertNull(service().conversationKey(objectMapper.readTree("""{"model":"m"}""")))
    }

    @Test
    fun `выключенная аффинность не даёт ключа и не хранит привязки`() {
        val affinityService = service(enabled = false)
        assertNull(affinityService.conversationKey(objectMapper.readTree("""{"messages":[{"role":"user","content":"x"}]}""")))
        affinityService.bind("m", "key", "provider-one")
        assertNull(affinityService.boundProviderName("m", "key"))
    }

    @Test
    fun `привязка читается и перепривязывается`() {
        val affinityService = service()
        affinityService.bind("m", "key", "provider-one", nowMilliseconds = 1_000)
        assertEquals("provider-one", affinityService.boundProviderName("m", "key", nowMilliseconds = 2_000))
        // фейловер перепривязывает разговор к запасному провайдеру
        affinityService.bind("m", "key", "provider-two", nowMilliseconds = 3_000)
        assertEquals("provider-two", affinityService.boundProviderName("m", "key", nowMilliseconds = 4_000))
    }

    @Test
    fun `привязка живёт в рамках своей модели`() {
        val affinityService = service()
        affinityService.bind("model-one", "key", "provider-one")
        assertNull(affinityService.boundProviderName("model-two", "key"))
    }

    @Test
    fun `протухшая привязка не возвращается и удаляется`() {
        val affinityService = service(ttlSeconds = 10)
        affinityService.bind("m", "key", "provider-one", nowMilliseconds = 1_000)
        assertNull(affinityService.boundProviderName("m", "key", nowMilliseconds = 11_500))
        // запись удалена: обновления в прошлом не воскрешают привязку
        assertNull(affinityService.boundProviderName("m", "key", nowMilliseconds = 11_600))
    }

    @Test
    fun `переполнение выталкивает самые старые привязки`() {
        val affinityService = service(maxEntries = 2)
        affinityService.bind("m", "old", "provider-old", nowMilliseconds = 1_000)
        affinityService.bind("m", "middle", "provider-middle", nowMilliseconds = 2_000)
        affinityService.bind("m", "young", "provider-young", nowMilliseconds = 3_000)
        assertNull(affinityService.boundProviderName("m", "old", nowMilliseconds = 3_500))
        assertEquals("provider-middle", affinityService.boundProviderName("m", "middle", nowMilliseconds = 3_500))
        assertEquals("provider-young", affinityService.boundProviderName("m", "young", nowMilliseconds = 3_500))
        val diagnostics = affinityService.diagnostics()
        assertEquals(2, diagnostics.entries)
        assertEquals(1, diagnostics.evictionsOverflow)
    }

    @Test
    fun `диагностика считает попадания и промахи`() {
        val affinityService = service()
        val key = affinityService.conversationKey(
            objectMapper.readTree("""{"messages":[{"role":"user","content":"x"}]}"""),
        )
        assertNull(affinityService.boundProviderName("m", key))
        affinityService.bind("m", key, "provider-one")
        assertEquals("provider-one", affinityService.boundProviderName("m", key))
        val diagnostics = affinityService.diagnostics()
        assertEquals(1, diagnostics.binds)
        assertEquals(1, diagnostics.hits)
        assertEquals(1, diagnostics.misses)
    }
}
