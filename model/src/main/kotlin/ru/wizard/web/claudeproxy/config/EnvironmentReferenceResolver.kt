package ru.wizard.web.claudeproxy.config

import org.springframework.core.env.Environment

/**
 * Резолв ссылок на окружение в значениях из БД/конфига:
 * `${ENV_VAR}` или `${ENV_VAR:значение_по_умолчанию}` → значение окружения,
 * иначе — значение как литерал.
 */
object EnvironmentReferenceResolver {

    private val reference = Regex("^\\$\\{([A-Za-z_][A-Za-z0-9_.-]*)(?::([^}]*))?\\}$")

    /**
     * Разворачивает ссылку `${ENV_VAR}` / `${ENV_VAR:default}` в значение
     * из окружения Spring, затем ОС; без ссылки возвращает значение как есть.
     * Если переменной нет и default не задан — пустая строка.
     */
    fun resolve(environment: Environment, storedValue: String): String {
        val match = reference.find(storedValue) ?: return storedValue
        val variableName = match.groupValues[1]
        val fallbackValue = match.groupValues[2]
        return environment.getProperty(variableName)
            ?: System.getenv(variableName)
            ?: fallbackValue
    }
}
