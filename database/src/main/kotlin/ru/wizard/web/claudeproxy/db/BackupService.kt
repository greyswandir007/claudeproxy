package ru.wizard.web.claudeproxy.db

import java.nio.file.Path

/** Создание резервных копий базы данных. */
interface BackupService {
    /**
     * Создаёт новый бэкап и вытесняет старые сверх лимита хранения.
     * Возвращает путь к созданному файлу или null, если бэкап не выполнялся
     * (выключен или не поддерживается текущей СУБД).
     */
    suspend fun createBackup(): Path?
}
