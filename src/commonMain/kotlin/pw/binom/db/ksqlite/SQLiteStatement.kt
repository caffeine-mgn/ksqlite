package pw.binom.db.ksqlite

/**
 * One-shot SQL executor. Useful for ad-hoc DDL / DML where preparing once
 * would be more ceremony than the query deserves.
 *
 * Created by [SQLiteConnection.createStatement]. Not thread-safe — use from
 * the same thread as the connection.
 */
@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect class SQLiteStatement : AutoCloseable {

    /** Run [sql] as an INSERT/UPDATE/DELETE/DDL. Returns rows changed. */
    fun executeUpdate(sql: String): Int

    /** Run [sql] as a SELECT and return a forward-only result set. */
    fun executeQuery(sql: String): SQLiteResultSet

    /** Release any cached compiled statement. Idempotent. */
    override fun close()
}