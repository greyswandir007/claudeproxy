package ru.wizard.web.claudeproxy.providers

/**
 * Каталог оверрайдов входных параметров провайдера: фиксированный список
 * (выбор из списка в UI, добавленные скрываются). Ключи стабильны — хранятся в БД.
 */
object ProviderSettingCatalog {

    /** Режим недельного лимита: скользящее окно или календарная неделя от фиксированного дня. */
    const val LIMIT_WEEK_MODE = "LIMIT_WEEK_MODE"

    /** День недели, с которого начинается календарная неделя недельного лимита. */
    const val LIMIT_WEEK_START_DAY = "LIMIT_WEEK_START_DAY"

    /** День месяца, с которого начинается 30-дневный платёжный период месячного лимита. */
    const val LIMIT_MONTH_START_DAY = "LIMIT_MONTH_START_DAY"

    /** Тип значения настройки — управляет инпутом в UI и валидацией. */
    enum class ValueType { LONG, DOUBLE, EFFORT_LEVEL, BOOLEAN, NON_EMPTY_TEXT, ONE_OF }

    /** Описание одной настройки каталога. */
    data class SettingDefinition(
        /** Стабильный ключ (хранится в БД). */
        val key: String,
        /** Заголовок в UI. */
        val title: String,
        /** Пояснение в UI. */
        val description: String,
        /** Тип значения. */
        val valueType: ValueType,
        /** Подсказка-плейсхолдер инпута. */
        val placeholder: String,
        /** Варианты для ONE_OF. */
        val values: List<String> = emptyList(),
    )

    /** Все настройки каталога (порядок = порядок в UI). */
    val definitions: List<SettingDefinition> = listOf(
        SettingDefinition(
            key = "API_TIMEOUT_MS",
            title = "Таймаут вызова провайдера, мс",
            description = "Молчание провайдера дольше этого времени — обрыв и переключение на следующий маршрут (для стрима — молчание между чанками)",
            valueType = ValueType.LONG,
            placeholder = "120000",
        ),
        SettingDefinition(
            key = "MAX_OUTPUT_TOKENS",
            title = "Потолок max_tokens",
            description = "max_tokens запроса ужимается до этого значения (min)",
            valueType = ValueType.LONG,
            placeholder = "8192",
        ),
        SettingDefinition(
            key = "MAX_INPUT_TOKENS",
            title = "Лимит входных токенов",
            description = "Оценка входа выше лимита — вежливый 413 вместо ошибки провайдера",
            valueType = ValueType.LONG,
            placeholder = "200000",
        ),
        SettingDefinition(
            key = "TEMPERATURE_OVERRIDE",
            title = "Температура (переопределение)",
            description = "temperature запроса заменяется этим значением",
            valueType = ValueType.DOUBLE,
            placeholder = "0.2",
        ),
        SettingDefinition(
            key = "TOP_P_OVERRIDE",
            title = "top_p (переопределение)",
            description = "top_p запроса заменяется этим значением",
            valueType = ValueType.DOUBLE,
            placeholder = "0.9",
        ),
        SettingDefinition(
            key = "FORCED_REASONING_EFFORT",
            title = "Принудительный effort",
            description = "Уровень effort после маппера (low/medium/high/xhigh/max)",
            valueType = ValueType.EFFORT_LEVEL,
            placeholder = "medium",
        ),
        SettingDefinition(
            key = "DISABLE_THINKING",
            title = "Выключить thinking",
            description = "Запрос уходит с thinking: disabled (провайдер не тратит токены на рассуждения)",
            valueType = ValueType.BOOLEAN,
            placeholder = "true",
        ),
        SettingDefinition(
            key = "EXTRA_STOP_SEQUENCE",
            title = "Дополнительная stop-последовательность",
            description = "Добавляется к stop_sequences запроса",
            valueType = ValueType.NON_EMPTY_TEXT,
            placeholder = "</end>",
        ),
        SettingDefinition(
            key = "CACHE_INJECTION",
            title = "Экономия: инъекция кэш-маркеров",
            description = "anthropic: cache_control на system/tools, если клиент не поставил; openai: стабильный prompt_cache_key",
            valueType = ValueType.BOOLEAN,
            placeholder = "true",
        ),
        SettingDefinition(
            key = "TRIM_OLD_TOOL_RESULTS",
            title = "Экономия: обрезка старых tool_result",
            description = "tool_result старше последних 4 заменяются на «[trimmed]» — экономия на длинных агентных сессиях",
            valueType = ValueType.BOOLEAN,
            placeholder = "true",
        ),
        SettingDefinition(
            key = "DROP_OLD_TOOL_IMAGES",
            title = "Экономия: удаление старых картинок",
            description = "Изображения старше последних 2 сообщений удаляются (~1600 токенов за картинку)",
            valueType = ValueType.BOOLEAN,
            placeholder = "true",
        ),
        SettingDefinition(
            key = "REQUEST_CACHE_TTL_MS",
            title = "Экономия: кэш повторов, TTL (мс)",
            description = "Точный повтор запроса внутри окна отдаётся из кэша бесплатно, без похода к провайдеру; 0 — выключить кэш для провайдера",
            valueType = ValueType.LONG,
            placeholder = "600000",
        ),
        SettingDefinition(
            key = "CONVERT_SYSTEM_MESSAGES_TO_USER",
            title = "Совместимость: system → user внутри messages",
            description = "Сообщения с ролью system внутри messages (служебные напоминания клиентов) конвертируются в user — нужно локальным движкам (LM Studio/Qwen), иначе «System message must be at the beginning»",
            valueType = ValueType.BOOLEAN,
            placeholder = "true",
        ),
        SettingDefinition(
            key = LIMIT_WEEK_MODE,
            title = "Режим недельного лимита",
            description = "SLIDING — скользящие 7 суток; FIXED_DAY — календарная неделя со сбросом в день из настройки «День начала недели»",
            valueType = ValueType.ONE_OF,
            placeholder = "SLIDING",
            values = listOf("SLIDING", "FIXED_DAY"),
        ),
        SettingDefinition(
            key = LIMIT_WEEK_START_DAY,
            title = "День начала недели",
            description = "День недели, с которого начинается календарная неделя; применяется только в режиме FIXED_DAY недельного лимита",
            valueType = ValueType.ONE_OF,
            placeholder = "MONDAY",
            values = listOf("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"),
        ),
        SettingDefinition(
            key = LIMIT_MONTH_START_DAY,
            title = "День начала платёжного периода",
            description = "День месяца, с которого начинается 30-дневный период месячного лимита; в коротких месяцах ограничивается последним днём месяца; без настройки — скользящие 30 суток",
            valueType = ValueType.ONE_OF,
            placeholder = "1",
            values = (1..31).map(Int::toString),
        ),
    )

    private val byKey = definitions.associateBy { it.key }

    fun definition(key: String): SettingDefinition? = byKey[key]

    fun keys(): Set<String> = byKey.keys

    /** Валидация значения по типу; null — значение корректно, иначе текст ошибки. */
    fun validate(key: String, value: String): String? {
        val definition = byKey[key] ?: return "Неизвестная настройка: $key"
        val trimmed = value.trim()
        return when (definition.valueType) {
            ValueType.LONG -> {
                val parsed = trimmed.toLongOrNull()
                    ?: return "Ожидается целое число"
                if (parsed <= 0) "Ожидается положительное число" else null
            }

            ValueType.DOUBLE -> {
                val parsed = trimmed.toDoubleOrNull()
                    ?: return "Ожидается число"
                if (parsed < 0.0 || parsed > 2.0) "Ожидается число от 0 до 2" else null
            }

            ValueType.EFFORT_LEVEL ->
                if (trimmed in EFFORT_LEVELS) null else "Ожидается один из: ${EFFORT_LEVELS.joinToString(", ")}"

            ValueType.BOOLEAN ->
                if (trimmed == "true" || trimmed == "false") null else "Ожидается true или false"

            ValueType.NON_EMPTY_TEXT ->
                if (trimmed.isEmpty()) "Значение не должно быть пустым" else null

            ValueType.ONE_OF ->
                if (trimmed in definition.values) null
                else "Значение должно быть одним из: ${definition.values.joinToString(", ")}"
        }
    }

    /** Канонические effort-уровни прокси (вход); синонимы нормализуются отдельно. */
    val EFFORT_LEVELS = setOf("low", "medium", "high", "xhigh", "max")

    /** Нормализация синонимов: middle → medium, ultra → max. */
    fun normalizeEffortLevel(level: String): String = when (level.lowercase()) {
        "middle" -> "medium"
        "ultra" -> "max"
        else -> level.lowercase()
    }
}
