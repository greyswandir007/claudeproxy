package ru.wizard.web.claudeproxy.usage

/** Одна накопленная запись калибровки count_tokens для пары (модель, провайдер). */
data class TokenCalibrationEntry(
    /** Публичное имя модели. */
    val model: String,
    /** Имя провайдера. */
    val provider: String,
    /** Сумма текстовых символов запросов, участвовавших в обучении. */
    val textCharacters: Long,
    /** Сумма фактических input_tokens за вычетом фиксированной оценки картинок. */
    val textTokens: Long,
    /** Число накопленных запросов. */
    val samples: Long,
    /** Текущий коэффициент «символов на токен» или null, пока накоплено меньше минимума. */
    val charactersPerToken: Double?,
    /** Момент последнего дообучения (epoch millis UTC). */
    val updatedAtMilliseconds: Long,
)
