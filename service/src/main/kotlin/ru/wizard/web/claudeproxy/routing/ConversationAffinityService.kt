package ru.wizard.web.claudeproxy.routing

import com.fasterxml.jackson.databind.JsonNode

/**
 * Sticky-аффинность разговоров: последовательность запросов с общим стабильным
 * префиксом (system + tools + первое сообщение) продолжает обслуживаться тем же
 * провайдером из числа равнозначных маршрутов, чтобы не терять промпт-кэш
 * вверх по течении. Ключ разговора вычисляет вызывающий (контроллер) и передаёт
 * его в реестр маршрутов и хендлеры; привязка обновляется только успешным
 * ходом разговора (см. [ModelRegistry.find]).
 */
interface ConversationAffinityService {

    /** Состояние и счётчики аффинности для диагностики дашборда. */
    data class ConversationAffinityDiagnostics(
        val enabled: Boolean,
        val entries: Int,
        val maxEntries: Int,
        val binds: Long,
        val hits: Long,
        val misses: Long,
        val evictionsExpired: Long,
        val evictionsOverflow: Long,
    )

    /**
     * Ключ разговора: SHA-256 канонической сериализации стабильного префикса
     * запроса (system, tools, messages[0]). Растущий хвост разговора ключ не
     * меняет; смена system/tools/первого сообщения — начинается новый разговор.
     * null — аффинность выключена или messages отсутствует.
     */
    fun conversationKey(requestRoot: JsonNode): String?

    /**
     * Имя провайдера, к которому привязан разговор модели; null — привязки нет,
     * она протухла или аффинность выключена. [nowMilliseconds] вынесен в
     * параметр для тестируемости (стиль RouteCircuitBreaker).
     */
    fun boundProviderName(
        model: String,
        conversationKey: String?,
        nowMilliseconds: Long = System.currentTimeMillis(),
    ): String?

    /**
     * Записать/обновить привязку разговора к провайдеру. no-op при null-ключе
     * и выключенной аффинности; перезаписывает прежнюю привязку (фейловер
     * перевязывает разговор на запасной маршрут).
     */
    fun bind(
        model: String,
        conversationKey: String?,
        providerName: String,
        nowMilliseconds: Long = System.currentTimeMillis(),
    )

    fun diagnostics(): ConversationAffinityDiagnostics
}
