package pw.binom.db.ksqlite

/**
 * Handle to an open SQLite database. Single-threaded by convention — a
 * connection and all of its statements must be used from the same thread.
 * For multi-threaded access, open one connection per thread.
 *
 * Closes on [close]. Resources held by the underlying sqlite3* handle are
 * released there; statements and result sets derived from this connection
 * must not be used after closing.
 */
@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect class SQLiteConnection : AutoCloseable {

    /** True until [close] has been called. */
    val isOpen: Boolean

    /** Last successful rowid inserted by an INSERT into a ROWID table. */
    val lastInsertRowId: Long

    /** Number of rows changed by the most recent INSERT/UPDATE/DELETE on this connection. */
    val changes: Int

    /** Total number of rows changed since the connection was opened. */
    val totalChanges: Int

    /** Compile a SQL statement. Caller is responsible for [SQLitePreparedStatement.close]. */
    fun prepare(sql: String): SQLitePreparedStatement

    /** Create a one-shot statement for ad-hoc SQL. */
    fun createStatement(): SQLiteStatement

    /**
     * Run one or more SQL statements separated by semicolons.
     * Returns the total number of rows changed (sum across all statements).
     * For SELECT use [createStatement] / [prepare] instead.
     */
    fun exec(sql: String): Int

    /** Begin a transaction. Nests via SAVEPOINT semantics if [transaction] is re-entered. */
    fun beginTransaction()

    /** Commit the current transaction. */
    fun commit()

    /** Rollback the current transaction. */
    fun rollback()

    /** Set the busy timeout in milliseconds used by sqlite3_busy_timeout. */
    fun setBusyTimeout(millis: Int)

    /** Release the underlying sqlite3 handle. Idempotent. */
    override fun close()

    companion object {
        /** Open a file-backed database at [path]. Creates the file if missing. */
        fun open(path: String, readOnly: Boolean = false): SQLiteConnection

        /** Open an in-memory database. If [name] is non-null, uses shared-cache naming. */
        fun memory(name: String? = null): SQLiteConnection

        /**
         * Open a private on-disk database in the OS temp directory. Cleaned up on close.
         */
        fun temporary(): SQLiteConnection
    }
}