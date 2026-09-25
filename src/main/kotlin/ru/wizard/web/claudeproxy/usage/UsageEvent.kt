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
)
