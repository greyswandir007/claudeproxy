package ru.wizard.web.claudeproxy.db

/**
 * Абстракция доступа к базе данных: скрывает тип СУБД и особенности её планирования.
 * Реализация выбирается конфигурацией проекта (сейчас — SQLite, далее возможен
 * PostgreSQL через R2DBC без смены стиля вызовов).
 */
interface DatabaseProvider {

    /**
     * Выполняет блок работы с базой данных в её контексте.
     * Для SQLite — строго последовательно (один писатель).
     */
    suspend fun <Result> execute(block: () -> Result): Result
}
