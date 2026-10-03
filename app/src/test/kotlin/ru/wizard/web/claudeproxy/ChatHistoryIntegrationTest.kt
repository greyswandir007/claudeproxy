package ru.wizard.web.claudeproxy

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import ru.wizard.web.claudeproxy.chat.ChatHistoryService

/** Интеграционный тест персистентности чат-истории (тред + сообщения). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatHistoryIntegrationTest {

    @Autowired
    private lateinit var chatHistoryService: ChatHistoryService

    @Autowired
    private lateinit var environment: Environment

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                "jdbc:sqlite:build/test/chat-history-itest-${UUID.randomUUID()}.db"
            }
        }
    }

    @BeforeEach
    fun setUp() {
        val datasourceUrl = environment.getProperty("spring.datasource.url")
        check(datasourceUrl != null && datasourceUrl.contains("build/test")) {
            "Test datasource must point into build/test, got: $datasourceUrl"
        }
        Files.createDirectories(Path.of("build/test"))
        jdbcTemplate.update("DELETE FROM chat_message")
        jdbcTemplate.update("DELETE FROM chat_thread")
    }

    @Test
    fun `первое обращение создаёт тред с пустым заголовком`() = runBlocking {
        val thread = chatHistoryService.thread("itest-key")

        assertEquals("itest-key", thread.clientKey)
        assertEquals("", thread.title)

        chatHistoryService.append("itest-key", "user", "первое сообщение")
        val afterAppend = chatHistoryService.thread("itest-key")

        assertTrue(afterAppend.updatedAt >= thread.updatedAt)
        Unit
    }

    @Test
    fun `сообщения возвращаются от старых к новым`() = runBlocking {
        chatHistoryService.append("itest-key", "user", "первое")
        chatHistoryService.append("itest-key", "assistant", "второе")
        chatHistoryService.append("itest-key", "user", "третье")

        val messages = chatHistoryService.messages("itest-key")

        assertEquals(listOf("первое", "второе", "третье"), messages.map { it.content })
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.role })
        Unit
    }

    @Test
    fun `переименование треда переживает перезапрос`() = runBlocking {
        chatHistoryService.append("itest-key", "user", "тема разговора")

        chatHistoryService.renameThread("itest-key", "новое имя треда")

        assertEquals("новое имя треда", chatHistoryService.thread("itest-key").title)
        Unit
    }

    @Test
    fun `clear удаляет сообщения, но тред остаётся`() = runBlocking {
        chatHistoryService.append("itest-key", "user", "сообщение на удаление")
        chatHistoryService.renameThread("itest-key", "титул сохраняется")

        chatHistoryService.clear("itest-key")

        assertEquals(emptyList(), chatHistoryService.messages("itest-key"))
        assertEquals("титул сохраняется", chatHistoryService.thread("itest-key").title)
        Unit
    }

    @Test
    fun `истории разных ключей независимы`() = runBlocking {
        chatHistoryService.append("key-one", "user", "для первого ключа")
        chatHistoryService.append("key-two", "user", "для второго ключа")

        assertEquals(listOf("для первого ключа"), chatHistoryService.messages("key-one").map { it.content })
        assertEquals(listOf("для второго ключа"), chatHistoryService.messages("key-two").map { it.content })
        Unit
    }
}
