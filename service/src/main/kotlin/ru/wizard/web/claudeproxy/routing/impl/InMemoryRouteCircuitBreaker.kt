package ru.wizard.web.claudeproxy.routing.impl

import org.springframework.stereotype.Component
import ru.wizard.web.claudeproxy.routing.RouteCircuitBreaker
import java.util.concurrent.ConcurrentHashMap

/**
 * Реализация RouteCircuitBreaker на ConcurrentHashMap: провайдер → конец кулдауна.
 */
@Component
class InMemoryRouteCircuitBreaker : RouteCircuitBreaker {

    private val cooldownUntilByName = ConcurrentHashMap<String, RouteCircuitBreaker.CooldownState>()

    override fun isAvailable(
        providerName: String,
        nowMilliseconds: Long,
    ): Boolean {
        val state = cooldownUntilByName[providerName] ?: return true
        return nowMilliseconds >= state.cooldownUntilMilliseconds
    }

    override fun trip(
        providerName: String,
        reason: String,
        durationMilliseconds: Long,
        nowMilliseconds: Long,
    ) {
        val bounded = durationMilliseconds.coerceIn(1, RouteCircuitBreaker.MAX_COOLDOWN_MILLISECONDS)
        cooldownUntilByName[providerName] = RouteCircuitBreaker.CooldownState(
            providerName = providerName,
            cooldownUntilMilliseconds = nowMilliseconds + bounded,
            reason = reason,
        )
    }

    override fun success(providerName: String) {
        cooldownUntilByName.remove(providerName)
    }

    override fun activeCooldowns(nowMilliseconds: Long): List<RouteCircuitBreaker.CooldownState> =
        cooldownUntilByName.values
            .filter { it.cooldownUntilMilliseconds > nowMilliseconds }
            .sortedByDescending { it.cooldownUntilMilliseconds }
}
