package ru.wizard.web.claudeproxy.usage

/** Одно учтённое использование (строка usage_event). */
data class UsageEvent(
    val ts: Long,
    val clientKey: String,
    val provider: String,
    val model: String,
    val upstreamModel: String?,
    val stream: Boolean,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheCreationTokens: Long,
    val cacheReadTokens: Long,
    val durationMilliseconds: Long,
    /** HTTP-код; 0 = стрим оборван клиентом. */
    val status: Int,
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
