package ru.wizard.web.claudeproxy.routing

/**
 * Кулдаун неудачных маршрутов (circuit breaker): после повторимой ошибки
 * (429/5xx/сеть) провайдер уходит в кулдаун и не дёргается повторно, пока
 * кулдаун не истечёт — fallback сразу идёт на следующий маршрут.
 * Состояние in-memory (runtime), не персистится.
 */
interface RouteCircuitBreaker {

    data class CooldownState(
        /** Провайдер в кулдауне. */
        val providerName: String,
        /** До какого момента, epoch millis. */
        val cooldownUntilMilliseconds: Long,
        /** Причина (текст последней ошибки). */
        val reason: String,
    )

    /** true — маршрут можно пробовать (кулдауна нет или истёк). */
    fun isAvailable(providerName: String, nowMilliseconds: Long = System.currentTimeMillis()): Boolean

    /** Повторимая ошибка: провайдер уходит в кулдаун (длительность из retry-after или дефолт). */
    fun trip(providerName: String, reason: String, durationMilliseconds: Long, nowMilliseconds: Long = System.currentTimeMillis())

    /** Успех: снять кулдаун (если был). */
    fun success(providerName: String)

    /** Активные кулдауны (для дашборда). */
    fun activeCooldowns(nowMilliseconds: Long = System.currentTimeMillis()): List<CooldownState>

    companion object {
        /** Дефолтный кулдаун при повторимой ошибке без retry-after. */
        const val DEFAULT_COOLDOWN_MILLISECONDS = 30_000L

        /** Потолок кулдауна из retry-after (защита от вечных 86400 у некоторых шлюзов). */
        const val MAX_COOLDOWN_MILLISECONDS = 10 * 60_000L
    }
}
