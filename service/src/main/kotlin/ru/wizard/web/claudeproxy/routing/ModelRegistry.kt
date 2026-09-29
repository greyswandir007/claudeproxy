package ru.wizard.web.claudeproxy.routing

/**
 * Динамический реестр моделей: public-имя → отсортированные маршруты.
 * Источник — таблицы provider/model в БД; снимок перезагружается после мутаций.
 * Один public-имя может обслуживаться несколькими провайдерами: порядок —
 * по priority (меньше = выше), при повторимых ошибках происходит переключение
 * на следующий маршрут (см. UpstreamRetryPolicy).
 */
interface ModelRegistry {

    data class ProviderInfo(
        val id: Long,
        val name: String,
        val type: String,
        val baseUrl: String,
        val apiKey: String,
        /** api_key | oauth. Для oauth хендлеры берут access-токен у ProviderOAuthTokenService. */
        val authType: String,
        val extraHeaders: Map<String, String>,
        /** Маппер effort-уровней: канонический (low/medium/high/xhigh/max) → значение
         *  провайдера. Пустая карта — маппер выключен, уровни пробрасываются как есть. */
        val effortMapping: Map<String, String>,
        /** Оверрайды входных параметров (ключи из ProviderSettingCatalog). */
        val settingOverrides: Map<String, String>,
    )

    data class ModelInfo(
        val publicName: String,
        val upstreamName: String,
        val reasoning: String,
        val maxCompletionParam: Boolean,
        val priority: Int,
    )

    data class Route(val provider: ProviderInfo, val mapping: ModelInfo)

    /** Перезагружает снимок из БД (на старте после сида и после каждой мутации). */
    suspend fun reload()

    /**
     * Маршруты модели по возрастанию priority; пусто — модель неизвестна.
     * Базовый порядок — (priority, id); внутри сегментов подряд идущих равных
     * (priority, тип провайдера) маршруты чередуются от вызова к вызову
     * (round-robin). Оценочные пути (count_tokens) передают [rotate] = false,
     * чтобы не сдвигать курсор ротации: ход Claude Code — count_tokens + messages.
     *
     * [conversationKey] — ключ разговора sticky-аффинности (см.
     * [ConversationAffinityService]): при живой привязке привязанный маршрут
     * становится головой своего сегмента равных, ротация пропускается (курсор
     * не сдвигается). Приоритет всегда доминирует: через границу сегмента
     * привязка не продвигается.
     */
    fun find(model: String, rotate: Boolean = true, conversationKey: String? = null): List<Route>

    fun isExposed(model: String): Boolean

    fun exposedModels(): List<String>

    fun routes(): List<Route>
}
