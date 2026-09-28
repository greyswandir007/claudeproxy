package ru.wizard.web.claudeproxy.usage

/**
 * 5-часовые окна: у клиентских ключей и у провайдеров (для выработки лимитов).
 * Окно = [started_at, started_at+5ч); при учтённом обращении без активного окна
 * новое начинается с этого обращения — «отсчёт от первого использования
 * после длительного времени».
 */
interface WindowService {

    data class WindowBounds(
        val startedAtMilliseconds: Long,
        val endsAtMilliseconds: Long,
        /** false — последнее окно уже истекло (новое начнётся первым обращением). */
        val active: Boolean,
    )

    fun ensureWindow(clientKey: String, timestamp: Long)

    fun ensureProviderWindow(providerName: String, timestamp: Long)

    /** Последнее окно провайдера (в том числе истекшее) или null, если окон не было. */
    fun currentProviderWindow(providerName: String): WindowBounds?
}
