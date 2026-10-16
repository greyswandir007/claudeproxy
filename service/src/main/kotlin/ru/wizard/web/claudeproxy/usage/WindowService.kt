package ru.wizard.web.claudeproxy.usage

/**
 * 5-часовые окна: у клиентских ключей и у провайдеров (для выработки лимитов).
 * Окно = [started_at, started_at+5ч); при учтённом обращении без активного окна
 * новое начинается с этого обращения — «отсчёт от первого использования
 * после длительного времени».
 */
interface WindowService {

    /** Границы 5-часового окна. */
    data class WindowBounds(
        /** Начало окна, epoch millis. */
        val startedAtMilliseconds: Long,
        /** Конец окна (эксклюзивно), epoch millis. */
        val endsAtMilliseconds: Long,
        /** false — последнее окно уже истекло (новое начнётся первым обращением). */
        val active: Boolean,
    )

    /** Убеждает, что у ключа есть окно, содержащее timestamp; иначе начинает новое. */
    fun ensureWindow(clientKey: String, timestamp: Long)

    /** То же для окна провайдера. */
    fun ensureProviderWindow(providerName: String, timestamp: Long)

    /** Последнее окно провайдера (в том числе истекшее) или null, если окон не было. */
    fun currentProviderWindow(providerName: String): WindowBounds?
}
