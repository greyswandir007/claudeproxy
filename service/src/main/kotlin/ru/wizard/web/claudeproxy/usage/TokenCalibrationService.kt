package ru.wizard.web.claudeproxy.usage

/**
 * Самообучение коэффициента «символы запроса → токены» для оценки count_tokens
 * у openai-провайдеров: фактические input_tokens ответов накапливаются в БД
 * по парам (модель, провайдер).
 */
interface TokenCalibrationService {

    /**
     * Асинхронно учесть выполненный запрос: [textCharacters] — число текстовых
     * символов отправленного запроса, [textTokens] — фактические input_tokens
     * ответа за вычётом фиксированной оценки картинок.
     */
    fun observeAsync(model: String, provider: String, textCharacters: Long, textTokens: Long)

    /**
     * Выученный коэффициент «символов на токен» для пары (модель, провайдер)
     * или null, если калибровка выключена или накоплено слишком мало запросов.
     */
    suspend fun charactersPerToken(model: String, provider: String): Double?

    /** Все накопленные записи калибровки (для диагностики). */
    suspend fun entries(): List<TokenCalibrationEntry>

    /** Сбросить накопленную калибровку. */
    suspend fun clear()
}
