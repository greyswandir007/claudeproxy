@file:OptIn(ExperimentalCoroutinesApi::class)

package ru.wizard.web.claudeproxy.db.impl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import ru.wizard.web.claudeproxy.db.DatabaseProvider

/**
 * SQLite: один писатель, поэтому все операции выполняются последовательно
 * на выделенном диспетчере с параллелизмом 1. R2DBC-драйвера production-качества
 * для SQLite нет — JDBC на выделенном диспетчере. Бин создаётся в
 * [ru.wizard.web.claudeproxy.db.DatabaseConfiguration] по диалекту БД.
 */
class SqliteDatabaseProvider : DatabaseProvider {

    private val singleWriterDispatcher = Dispatchers.IO.limitedParallelism(1)

    override suspend fun <Result> execute(block: () -> Result): Result =
        withContext(singleWriterDispatcher) { block() }
}
