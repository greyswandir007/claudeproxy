package ru.wizard.web.claudeproxy.optimizer

/**
 * Модель-оптимизатор (M30): сжатие старых tool_result осмысленным пересказом
 * вместо маркера «[trimmed by claudeproxy]» (M11). Провайдер и модель
 * выбираются на дашборде и хранятся в однострочной таблице optimizer_config.
 */
interface OptimizerService {

    /**
     * Результат сжатия одного блока. compressedText = null — модель не помогла
     * (ошибка, таймаут, «несжимаемо»): блок уходит маркером, как в M11.
     */
    data class CompressionResult(
        val compressedText: String?,
        val originalCharacters: Int,
        val compressedCharacters: Int,
    )

    /** Текущая настройка (для UI и дешёвой проверки доступности). */
    data class OptimizerConfig(
        val enabled: Boolean,
        val providerName: String?,
        val model: String?,
    )

    /** Запрос на изменение настройки из UI. */
    data class OptimizerConfigRequest(
        val enabled: Boolean,
        val providerName: String?,
        val model: String?,
    )

    /** Агрегаты диагностики для /api/optimizer/stats и карточки дашборда. */
    data class OptimizerStats(
        val enabled: Boolean,
        val providerName: String?,
        val model: String?,
        val circuitState: String,
        val requests: Long,
        val cacheHits: Long,
        val compressions: Long,
        val notCompressed: Long,
        val fallbacks: Long,
        val failures: Long,
        val charactersBefore: Long,
        val charactersAfter: Long,
        val estimatedTokensSaved: Long,
        val modelTokensSpent: Long,
        val averageLatencyMilliseconds: Long,
        val maxLatencyMilliseconds: Long,
        val lastError: String?,
    )

    /**
     * Дешёвая проверка без suspend: выключен/не настроен/провайдер без
     * api-ключа/breaker открыт. Держать лёгкой — зовётся на каждый запрос.
     */
    fun isAvailable(): Boolean

    /**
     * Сжатие пакета текстов (не длиннее maxCompressionsPerRequest —
     * остальное обрезается вызывающим). Возвращает список той же длины;
     * элемент с compressedText = null — «маркер». Ошибки наружу не бросает:
     * любая проблема модели — тот же null-результат.
     */
    suspend fun compressToolResults(texts: List<String>): List<CompressionResult>

    /** Снимок настройки; читается из памяти, без обращения к БД. */
    fun config(): OptimizerConfig

    /**
     * Сохраняет настройку. IllegalArgumentException (несуществующий
     * провайдер, oauth-провайдер, enabled без модели/провайдера) маппится
     * контроллером в 400.
     */
    suspend fun updateConfig(request: OptimizerConfigRequest)

    /** Агрегаты диагностики; без обращения к БД. */
    fun stats(): OptimizerStats
}
