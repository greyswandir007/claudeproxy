package ru.wizard.web.claudeproxy

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import ru.wizard.web.claudeproxy.db.BackupService

/**
 * Интеграционный тест снапшот-бэкапов SQLite: имена файлов имеют точность до
 * секунды, поэтому создание и ротация проверяются одним последовательным
 * сценарием с паузами больше секунды между снапшотами.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SqliteBackupIntegrationTest {

    @Autowired
    private lateinit var backupService: BackupService

    @Autowired
    private lateinit var environment: Environment

    companion object {
        private const val BACKUP_DIRECTORY = "build/test/sqlite-backup-itest"

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                "jdbc:sqlite:build/test/sqlite-backup-itest-${UUID.randomUUID()}.db"
            }
            registry.add("claudeproxy.backup.enabled") { "true" }
            registry.add("claudeproxy.backup.directory") { BACKUP_DIRECTORY }
            registry.add("claudeproxy.backup.retention-count") { "2" }
        }
    }

    @BeforeEach
    fun setUp() {
        val datasourceUrl = environment.getProperty("spring.datasource.url")
        check(datasourceUrl != null && datasourceUrl.contains("build/test")) {
            "Test datasource must point into build/test, got: $datasourceUrl"
        }
    }

    @Test
    fun `снапшот создаётся, не пуст, ротация хранит retention-count копий`() = runBlocking {
        val first = backupService.createBackup()

        assertNotNull(first, "backup must be created when enabled")
        assertTrue(Files.exists(first), "snapshot file must exist: $first")
        assertTrue(Files.size(first) > 0, "snapshot must not be empty")
        assertTrue(first.fileName.toString().endsWith(".db"), "snapshot name: ${first.fileName}")

        // Имена снапшотов различаются секундами — ждём больше секунды.
        Thread.sleep(1_100)
        assertNotNull(backupService.createBackup())
        Thread.sleep(1_100)
        assertNotNull(backupService.createBackup())

        val remaining = Files.list(Path.of(BACKUP_DIRECTORY)).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".db") }.count()
        }
        assertEquals(2L, remaining, "expected exactly 2 backups after rotation")
        Unit
    }
}
