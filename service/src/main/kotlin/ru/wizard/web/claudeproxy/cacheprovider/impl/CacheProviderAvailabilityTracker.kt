package ru.wizard.web.claudeproxy.cacheprovider.impl

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import ru.wizard.web.claudeproxy.serverevent.ServerEvent
import ru.wizard.web.claudeproxy.serverevent.ServerEventRecorder

/**
 * Трекер доступности внешнего кэш-провайдера (circuit breaker):
 * CLOSED → OPEN (первый отказ, одно WARN) → HALF_OPEN (backoff истёк,
 * проба разрешена) → CLOSED (успех, одно INFO) либо снова OPEN (backoff
 * удваивается, без нового события). Пока цепь разомкнута, обращения
 * молча пропускаются — лавины событий при лежащем сервисе нет.
 */
class CacheProviderAvailabilityTracker(
    private val serverEventRecorder: ServerEventRecorder,
    private val providerName: String,
    baseBackoffMilliseconds: Long = 30_000L,
    private val currentTimeMilliseconds: () -> Long = System::currentTimeMillis,
) {

    /** База backoff, мс. */
    @Volatile
    private var baseBackoffMilliseconds = baseBackoffMilliseconds.coerceAtLeast(1L)

    /** Потолок backoff, мс. */
    private val maxBackoffMilliseconds = 300_000L

    private enum class State { CLOSED, OPEN, HALF_OPEN }

    private val lock = Any()
    private var state = State.CLOSED
    private var openedAtMilliseconds = 0L
    private var backoffMilliseconds = baseBackoffMilliseconds
    private val droppedOperations = AtomicInteger()
    private val totalFailures = AtomicLong()

    /**
     * Можно ли звать провайдера. OPEN с истёкшим backoff переходит в
     * HALF_OPEN и пропускает пробный вызов.
     */
    fun isAvailable(): Boolean {
        synchronized(lock) {
            if (state == State.OPEN && currentTimeMilliseconds() - openedAtMilliseconds >= backoffMilliseconds) {
                state = State.HALF_OPEN
            }
            return state != State.OPEN
        }
    }

    /** Успешный вызов: закрывает разомкнутую цепь одним INFO-событием. */
    fun success() {
        val shouldReport = synchronized(lock) {
            val wasOpen = state != State.CLOSED
            state = State.CLOSED
            openedAtMilliseconds = 0L
            backoffMilliseconds = baseBackoffMilliseconds
            wasOpen
        }
        if (shouldReport) {
            val dropped = droppedOperations.getAndSet(0)
            serverEventRecorder.recordAsync(
                ServerEvent(
                    level = "INFO",
                    logger = CATEGORY,
                    message = "cache provider '$providerName' available again, " +
                        "dropped operations while unavailable: $dropped",
                    timestampMilliseconds = currentTimeMilliseconds(),
                ),
            )
        }
    }

    /** Отказ вызова: переход CLOSED→OPEN с одним WARN, далее — молча
     *  с удвоением backoff. */
    fun failure(reason: String) {
        totalFailures.incrementAndGet()
        val shouldReport = synchronized(lock) {
            when (state) {
                State.CLOSED -> {
                    state = State.OPEN
                    openedAtMilliseconds = currentTimeMilliseconds()
                    true
                }
                State.HALF_OPEN -> {
                    state = State.OPEN
                    openedAtMilliseconds = currentTimeMilliseconds()
                    backoffMilliseconds = minOf(backoffMilliseconds * 2, maxBackoffMilliseconds)
                    false
                }
                State.OPEN -> {
                    backoffMilliseconds = minOf(backoffMilliseconds * 2, maxBackoffMilliseconds)
                    false
                }
            }
        }
        if (shouldReport) {
            droppedOperations.set(0)
            serverEventRecorder.recordAsync(
                ServerEvent(
                    level = "WARN",
                    logger = CATEGORY,
                    message = "cache provider '$providerName' unavailable, calls paused: $reason",
                    timestampMilliseconds = currentTimeMilliseconds(),
                ),
            )
        }
    }

    /** Число отказов с момента создания (диагностика/тесты). */
    fun failuresTotal(): Long = totalFailures.get()

    companion object {
        /** Категория серверных событий стыковки с кэш-провайдерами. */
        const val CATEGORY = "cache-provider"
    }
}
