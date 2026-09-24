package larder.db

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

// A pooled JDBC connection per call, ported from shelf's Database.kt — ConnectionPool.use
// already provides thread safety (borrow/return via ArrayBlockingQueue), so each method here
// just borrows, runs its statement, and lets the pool reclaim the connection.
//
// Every query is a PreparedStatement with parameters bound via `bind` — never string
// interpolation into SQL. This is larder's SQL-injection-prevention equivalent of shelf's own
// rule; see AGENTS.md.
class Database(private val pool: ConnectionPool) {
    fun <T> queryOne(sql: String, bind: (PreparedStatement) -> Unit = {}, mapRow: (ResultSet) -> T): T =
        pool.use { connectionQueryOne(it, sql, bind, mapRow) }

    fun <T> queryOneOrNull(sql: String, bind: (PreparedStatement) -> Unit = {}, mapRow: (ResultSet) -> T): T? =
        pool.use { connectionQueryOneOrNull(it, sql, bind, mapRow) }

    fun <T> queryList(sql: String, bind: (PreparedStatement) -> Unit = {}, mapRow: (ResultSet) -> T): List<T> =
        pool.use { connectionQueryList(it, sql, bind, mapRow) }

    fun update(sql: String, bind: (PreparedStatement) -> Unit = {}): Int =
        pool.use { connectionUpdate(it, sql, bind) }

    // Runs block against a single borrowed connection wrapped in a transaction — commits if
    // block returns normally, rolls back and rethrows otherwise. Needed starting Phase 5: a
    // recipe and its recipe_ingredients rows have to commit atomically, and every query/update
    // method above borrows its own connection from the pool per call, which would defeat that.
    // The Transaction handed to block offers the same query/update shape as Database itself,
    // just bound to this one connection instead.
    fun <T> transaction(block: (Transaction) -> T): T = pool.use { connection ->
        connection.autoCommit = false
        try {
            val result = block(Transaction(connection))
            connection.commit()
            result
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }
}

// See Database.transaction() — every method here runs against the one connection it was
// constructed with, never the pool, so a sequence of calls through the same Transaction
// commits or rolls back together.
class Transaction(private val connection: Connection) {
    fun <T> queryOne(sql: String, bind: (PreparedStatement) -> Unit = {}, mapRow: (ResultSet) -> T): T =
        connectionQueryOne(connection, sql, bind, mapRow)

    fun <T> queryOneOrNull(sql: String, bind: (PreparedStatement) -> Unit = {}, mapRow: (ResultSet) -> T): T? =
        connectionQueryOneOrNull(connection, sql, bind, mapRow)

    fun <T> queryList(sql: String, bind: (PreparedStatement) -> Unit = {}, mapRow: (ResultSet) -> T): List<T> =
        connectionQueryList(connection, sql, bind, mapRow)

    fun update(sql: String, bind: (PreparedStatement) -> Unit = {}): Int =
        connectionUpdate(connection, sql, bind)
}

private fun <T> connectionQueryOne(
    connection: Connection,
    sql: String,
    bind: (PreparedStatement) -> Unit,
    mapRow: (ResultSet) -> T,
): T = connection.prepareStatement(sql).use { statement ->
    bind(statement)
    statement.executeQuery().use { resultSet ->
        check(resultSet.next()) { "query returned no rows: $sql" }
        mapRow(resultSet)
    }
}

private fun <T> connectionQueryOneOrNull(
    connection: Connection,
    sql: String,
    bind: (PreparedStatement) -> Unit,
    mapRow: (ResultSet) -> T,
): T? = connection.prepareStatement(sql).use { statement ->
    bind(statement)
    statement.executeQuery().use { resultSet ->
        if (resultSet.next()) mapRow(resultSet) else null
    }
}

private fun <T> connectionQueryList(
    connection: Connection,
    sql: String,
    bind: (PreparedStatement) -> Unit,
    mapRow: (ResultSet) -> T,
): List<T> = connection.prepareStatement(sql).use { statement ->
    bind(statement)
    statement.executeQuery().use { resultSet ->
        val results = mutableListOf<T>()
        while (resultSet.next()) results += mapRow(resultSet)
        results
    }
}

private fun connectionUpdate(connection: Connection, sql: String, bind: (PreparedStatement) -> Unit): Int =
    connection.prepareStatement(sql).use { statement ->
        bind(statement)
        statement.executeUpdate()
    }
