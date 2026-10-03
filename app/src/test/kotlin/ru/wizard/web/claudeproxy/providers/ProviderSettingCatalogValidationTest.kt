package ru.wizard.web.claudeproxy.providers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Юнит-тесты валидации каталога настроек провайдера по типам значений. */
class ProviderSettingCatalogValidationTest {

    @Test
    fun `неизвестный ключ отвергается`() {
        assertNotNull(ProviderSettingCatalog.validate("NO_SUCH_KEY", "1"))
    }

    @Test
    fun `LONG принимает положительное целое и отвергает остальное`() {
        assertNull(ProviderSettingCatalog.validate("REQUEST_CACHE_TTL_MS", "600000"))
        assertNull(ProviderSettingCatalog.validate("REQUEST_CACHE_TTL_MS", " 600000 "))

        assertNotNull(ProviderSettingCatalog.validate("REQUEST_CACHE_TTL_MS", "1.5"))
        assertNotNull(ProviderSettingCatalog.validate("REQUEST_CACHE_TTL_MS", "0"))
        assertNotNull(ProviderSettingCatalog.validate("REQUEST_CACHE_TTL_MS", "-5"))
        assertNotNull(ProviderSettingCatalog.validate("REQUEST_CACHE_TTL_MS", "абракадабра"))
    }

    @Test
    fun `DOUBLE принимает число от 0 до 2`() {
        assertNull(ProviderSettingCatalog.validate("TEMPERATURE_OVERRIDE", "0.2"))
        assertNull(ProviderSettingCatalog.validate("TEMPERATURE_OVERRIDE", "2"))
        assertNull(ProviderSettingCatalog.validate("TEMPERATURE_OVERRIDE", "0"))

        assertNotNull(ProviderSettingCatalog.validate("TEMPERATURE_OVERRIDE", "2.1"))
        assertNotNull(ProviderSettingCatalog.validate("TEMPERATURE_OVERRIDE", "-0.1"))
        assertNotNull(ProviderSettingCatalog.validate("TEMPERATURE_OVERRIDE", "warm"))
    }

    @Test
    fun `EFFORT_LEVEL принимает только канонические уровни`() {
        for (level in ProviderSettingCatalog.EFFORT_LEVELS) {
            assertNull(ProviderSettingCatalog.validate("FORCED_REASONING_EFFORT", level), level)
        }

        assertNotNull(ProviderSettingCatalog.validate("FORCED_REASONING_EFFORT", "ultra"))
    }

    @Test
    fun `BOOLEAN принимает только true и false`() {
        assertNull(ProviderSettingCatalog.validate("DISABLE_THINKING", "true"))
        assertNull(ProviderSettingCatalog.validate("DISABLE_THINKING", "false"))

        assertNotNull(ProviderSettingCatalog.validate("DISABLE_THINKING", "TRUE"))
        assertNotNull(ProviderSettingCatalog.validate("DISABLE_THINKING", "1"))
    }

    @Test
    fun `NON_EMPTY_TEXT отвергает пустое и пробелы`() {
        assertNull(ProviderSettingCatalog.validate("EXTRA_STOP_SEQUENCE", "</end>"))

        assertNotNull(ProviderSettingCatalog.validate("EXTRA_STOP_SEQUENCE", ""))
        assertNotNull(ProviderSettingCatalog.validate("EXTRA_STOP_SEQUENCE", "   "))
    }

    @Test
    fun `ONE_OF принимает только значения из списка`() {
        assertNull(ProviderSettingCatalog.validate("LIMIT_WEEK_MODE", "SLIDING"))
        assertNull(ProviderSettingCatalog.validate("LIMIT_WEEK_MODE", "FIXED_DAY"))

        assertNotNull(ProviderSettingCatalog.validate("LIMIT_WEEK_MODE", "CALENDAR"))
        assertNotNull(ProviderSettingCatalog.validate("LIMIT_MONTH_START_DAY", "0"))
        assertNotNull(ProviderSettingCatalog.validate("LIMIT_MONTH_START_DAY", "32"))
    }

    @Test
    fun `каждая настройка каталога валидируется по своему ключу`() {
        for (definition in ProviderSettingCatalog.definitions) {
            assertEquals(definition, ProviderSettingCatalog.definition(definition.key), definition.key)
        }
    }

    @Test
    fun `ключи каталога уникальны`() {
        val keys = ProviderSettingCatalog.definitions.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(ProviderSettingCatalog.keys().containsAll(keys))
    }

    @Test
    fun `normalizeEffortLevel разворачивает синонимы и регистр`() {
        assertEquals("medium", ProviderSettingCatalog.normalizeEffortLevel("middle"))
        assertEquals("max", ProviderSettingCatalog.normalizeEffortLevel("ultra"))
        assertEquals("high", ProviderSettingCatalog.normalizeEffortLevel("HIGH"))
        assertEquals("low", ProviderSettingCatalog.normalizeEffortLevel("low"))
    }
}
