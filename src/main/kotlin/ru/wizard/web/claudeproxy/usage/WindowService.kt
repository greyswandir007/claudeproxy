package ru.wizard.web.claudeproxy.usage

/**
 * 5-часовые окна (на каждый клиентский ключ): окно = [started_at, started_at+5ч);
 * при учтённом запросе без активного окна новое начинается с этого запроса —
 * «отсчёт от первого использования после длительного времени».
 */
interface WindowService {

    fun ensureWindow(clientKey: String, timestamp: Long)
}
