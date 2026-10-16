package ru.wizard.web.claudeproxy.routing.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.wizard.web.claudeproxy.routing.RouteCircuitBreaker

/** Юнит-тесты кулдаунов маршрутов: переходы доступен/закрыт и диагностика. */
class InMemoryRouteCircuitBreakerTest {

    private val breaker = InMemoryRouteCircuitBreaker()

    @Test
    fun `провайдер без кулдауна доступен`() {
        assertTrue(breaker.isAvailable("zai", nowMilliseconds = 1_000))
    }

    @Test
    fun `trip закрывает провайдера до конца кулдауна и отпускает после`() {
        breaker.trip("zai", "5xx", durationMilliseconds = 60_000, nowMilliseconds = 1_000)

        assertFalse(breaker.isAvailable("zai", nowMilliseconds = 60_999))
        assertTrue(breaker.isAvailable("zai", nowMilliseconds = 61_000))
    }

    @Test
    fun `success снимает кулдаун немедленно`() {
        breaker.trip("zai", "network", durationMilliseconds = 60_000, nowMilliseconds = 1_000)

        breaker.success("zai")

        assertTrue(breaker.isAvailable("zai", nowMilliseconds = 1_001))
    }

    @Test
    fun `кулдаун ограничен сверху максимумом`() {
        val overMax = RouteCircuitBreaker.MAX_COOLDOWN_MILLISECONDS * 10

        breaker.trip("zai", "5xx", durationMilliseconds = overMax, nowMilliseconds = 0)

        assertTrue(breaker.isAvailable("zai", nowMilliseconds = RouteCircuitBreaker.MAX_COOLDOWN_MILLISECONDS + 1))
    }

    @Test
    fun `нулевой кулдаун зажимается снизу единицей`() {
        breaker.trip("zai", "429", durationMilliseconds = 0, nowMilliseconds = 1_000)

        assertFalse(breaker.isAvailable("zai", nowMilliseconds = 1_000))
        assertTrue(breaker.isAvailable("zai", nowMilliseconds = 1_001))
    }

    @Test
    fun `повторный trip перезаписывает причину и конец кулдауна`() {
        breaker.trip("zai", "первая", durationMilliseconds = 10_000, nowMilliseconds = 0)
        breaker.trip("zai", "вторая", durationMilliseconds = 50_000, nowMilliseconds = 0)

        val cooldown = breaker.activeCooldowns(nowMilliseconds = 1_000).single()
        assertEquals("вторая", cooldown.reason)
        assertEquals(50_000, cooldown.cooldownUntilMilliseconds)
    }

    @Test
    fun `activeCooldowns возвращает только активные, свежие сверху`() {
        breaker.trip("долгий", "5xx", durationMilliseconds = 100_000, nowMilliseconds = 0)
        breaker.trip("короткий", "429", durationMilliseconds = 10_000, nowMilliseconds = 45_000)

        val names = breaker.activeCooldowns(nowMilliseconds = 50_000).map { it.providerName }

        assertEquals(listOf("долгий", "короткий"), names)
    }

    @Test
    fun `кулдауны независимы по провайдерам`() {
        breaker.trip("zai", "5xx", durationMilliseconds = 60_000, nowMilliseconds = 0)

        assertTrue(breaker.isAvailable("lmstudio", nowMilliseconds = 0))
    }
}
