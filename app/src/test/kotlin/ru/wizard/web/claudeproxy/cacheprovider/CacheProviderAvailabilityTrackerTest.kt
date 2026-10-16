package ru.wizard.web.claudeproxy.cacheprovider

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.wizard.web.claudeproxy.cacheprovider.impl.CacheProviderAvailabilityTracker
import ru.wizard.web.claudeproxy.serverevent.ServerEvent
import ru.wizard.web.claudeproxy.serverevent.ServerEventRecorder

/**
 * Юнит-тесты трекера доступности внешнего кэш-провайдера: одно событие на смену
 * состояния, backoff удваивается после неудачной пробы.
 */
class CacheProviderAvailabilityTrackerTest {

    private val events = ConcurrentLinkedQueue<ServerEvent>()
    private val recorder = object : ServerEventRecorder {
        override fun recordAsync(event: ServerEvent) {
            events.add(event)
        }
    }

    private var now = 100_000L
    private val tracker = CacheProviderAvailabilityTracker(recorder, "cache-provider", currentTimeMilliseconds = { now })

    @Test
    fun `первый отказ размыкает цепь одним WARN дальнейшие молчат`() {
        tracker.failure("connection refused")

        assertEquals(1, events.size)
        assertEquals("WARN", events.first().level)
        assertEquals("cache-provider", events.first().logger)
        assertFalse(tracker.isAvailable())

        tracker.failure("again")
        tracker.failure("again")

        assertEquals(1, events.size)
    }

    @Test
    fun `backoff истёк - проба разрешена и успех даёт один INFO`() {
        tracker.failure("connection refused")
        now += 30_001

        assertTrue(tracker.isAvailable())

        tracker.success()

        assertEquals(2, events.size)
        assertEquals("INFO", events.last().level)
        assertTrue(tracker.isAvailable())
    }

    @Test
    fun `неудачная проба снова размыкает без нового WARN и удваивает backoff`() {
        tracker.failure("timeout")
        now += 30_001

        assertTrue(tracker.isAvailable())
        tracker.failure("probe failed")

        assertEquals(1, events.size)
        assertFalse(tracker.isAvailable())

        // backoff удвоился: через 30с всё ещё закрыто
        now += 30_001
        assertFalse(tracker.isAvailable())
        now += 30_001
        assertTrue(tracker.isAvailable())
    }

    @Test
    fun `успех без размыкания не пишет событий`() {
        tracker.success()
        assertEquals(0, events.size)
    }
}
