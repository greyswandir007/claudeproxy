package ru.wizard.web.claudeproxy.usage

/** Одно учтённое использование (строка usage_event). */
data class UsageEvent(
    /** Момент завершения запроса, epoch millis. */
    val ts: Long,
    /** Имя ключа клиента (без «cpk_»-префикса), инициировавшего запрос. */
    val clientKey: String,
    /** Имя провайдера, обработавшего запрос. */
    val provider: String,
    /** Публичное имя модели, как его видит клиент. */
    val model: String,
    /** Имя модели у провайдера; null, если не переписывалось. */
    val upstreamModel: String?,
    /** Был ли ответ стриминговым (SSE). */
    val stream: Boolean,
    /** Входные токены по ответу провайдера. */
    val inputTokens: Long,
    /** Выходные токены по ответу провайдера. */
    val outputTokens: Long,
    /** Токены записи промпт-кэша провайдера. */
    val cacheCreationTokens: Long,
    /** Токены чтения промпт-кэша провайдера. */
    val cacheReadTokens: Long,
    /** Длительность обработки запроса (до последнего байта клиенту), ms. */
    val durationMilliseconds: Long,
    /** HTTP-код; 0 = стрим оборван клиентом. */
    val status: Int,
    /** Краткое описание ошибки (до 300 символов), если запрос не удался. */
    val error: String?,
    /** Оценка токенов, сэкономленных инструментами экономии (обрезка и т.п.). */
    val savedTokens: Long = 0,

    /** Время до первого токена от провайдера (null — не стриминговый путь). */
    val ttftMilliseconds: Long? = null,

    /** Полная длительность ответа провайдера (от отправки до конца стрима). */
    val upstreamDurationMilliseconds: Long? = null,

    /**
     * Полная расшифровка ошибки: тело ответа провайдера целиком или стектрейс
     * (в отличие от error — без обрезки до 300 символов; ограничено сверху
     * размером ProxyErrorDetails.MAX_DETAIL_LENGTH).
     */
    val errorDetail: String? = null,
)
