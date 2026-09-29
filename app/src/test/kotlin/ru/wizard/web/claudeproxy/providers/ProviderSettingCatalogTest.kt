package ru.wizard.web.claudeproxy.providers

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Юнит-тесты каталога настроек провайдера: настройки периодов лимитов.
 * Контекст Spring не поднимается.
 */
class ProviderSettingCatalogTest {

    @Test
    fun `настройки периодов лимитов принимают корректные значения`() {
        assertNull(ProviderSettingCatalog.validate(ProviderSettingCatalog.LIMIT_WEEK_MODE, "SLIDING"))
        assertNull(ProviderSettingCatalog.validate(ProviderSettingCatalog.LIMIT_WEEK_MODE, "FIXED_DAY"))
        assertNull(ProviderSettingCatalog.validate(ProviderSettingCatalog.LIMIT_WEEK_START_DAY, "MONDAY"))
        assertNull(ProviderSettingCatalog.validate(ProviderSettingCatalog.LIMIT_WEEK_START_DAY, "WEDNESDAY"))
        assertNull(ProviderSettingCatalog.validate(ProviderSettingCatalog.LIMIT_MONTH_START_DAY, "31"))
    }

    @Test
    fun `настройки периодов лимитов отвергают некорректные значения`() {
        assertNotNull(ProviderSettingCatalog.validate(ProviderSettingCatalog.LIMIT_WEEK_MODE, "POTDAY"))
        assertNotNull(ProviderSettingCatalog.validate(ProviderSettingCatalog.LIMIT_WEEK_MODE, " "))
        assertNotNull(ProviderSettingCatalog.validate(ProviderSettingCatalog.LIMIT_WEEK_START_DAY, "MONDAYY"))
        assertNotNull(ProviderSettingCatalog.validate(ProviderSettingCatalog.LIMIT_MONTH_START_DAY, "32"))
        assertNotNull(ProviderSettingCatalog.validate(ProviderSettingCatalog.LIMIT_MONTH_START_DAY, "0"))
    }
}
