package ru.wizard.web.claudeproxy.config

import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.mock.env.MockEnvironment

/** Юнит-тесты разворачивания ${ENV:...}-ссылок в значениях секретов. */
class EnvironmentReferenceResolverTest {

    @Test
    fun `литерал без ссылки возвращается как есть`() {
        val environment = MockEnvironment()

        val resolved = EnvironmentReferenceResolver.resolve(environment, "sk-plain-value")

        assertEquals("sk-plain-value", resolved)
    }

    @Test
    fun `ссылка разворачивается из окружения Spring`() {
        val environment = MockEnvironment().withProperty("TEST_PROXY_SECRET", "resolved-value")

        val resolved = EnvironmentReferenceResolver.resolve(environment, "\${TEST_PROXY_SECRET}")

        assertEquals("resolved-value", resolved)
    }

    @Test
    fun `ссылка с default берёт default при отсутствии переменной`() {
        val environment = MockEnvironment()

        val resolved = EnvironmentReferenceResolver.resolve(environment, "\${TEST_PROXY_MISSING:запасной}")

        assertEquals("запасной", resolved)
    }

    @Test
    fun `ссылка без default при отсутствии переменной даёт пустую строку`() {
        val environment = MockEnvironment()

        val resolved = EnvironmentReferenceResolver.resolve(environment, "\${TEST_PROXY_MISSING}")

        assertEquals("", resolved)
    }

    @Test
    fun `пустой default тоже применяется`() {
        val environment = MockEnvironment()

        val resolved = EnvironmentReferenceResolver.resolve(environment, "\${TEST_PROXY_MISSING:}")

        assertEquals("", resolved)
    }

    @Test
    fun `частичная подстановка не считается ссылкой`() {
        val environment = MockEnvironment().withProperty("A", "x")

        val resolved = EnvironmentReferenceResolver.resolve(environment, "prefix-\${A}-suffix")

        assertEquals("prefix-\${A}-suffix", resolved)
    }

    @Test
    fun `имя с недопустимым символом трактуется как литерал`() {
        val environment = MockEnvironment()

        val resolved = EnvironmentReferenceResolver.resolve(environment, "\${НЕ_ИМЯ!}")

        assertEquals("\${НЕ_ИМЯ!}", resolved)
    }
}
