package ru.wizard.web.claudeproxy.db.impl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.wizard.web.claudeproxy.db.DatabaseProvider

/**
 * PostgreSQL: конкурентная запись допустима (MVCC), операции не
 * сериализуются; JDBC-вызовы уходят с event-loop в пул Dispatchers.IO,
 * соединения выдаёт пул Hikari.
 */
class PostgresDatabaseProvider : DatabaseProvider {

    override suspend fun <Result> execute(block: () -> Result): Result =
        withContext(Dispatchers.IO) { block() }
}
