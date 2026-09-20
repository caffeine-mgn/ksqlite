package pw.binom.db.ksqlite

/**
 * A compiled SQL statement ready for repeated execution with different
 * bindings. Created by [SQLiteConnection.prepare].
 *
 * Bind indices are 1-based — the first `?` (or `:name` if using named
 * parameters) corresponds to index 1. See [SQLiteConnection.prepare]
 * semantics for parameter binding in detail.
 *
 * Bound values persist across [executeUpdate]/[executeQuery] calls. Call
 * [reset] to rewind the cursor (for SELECTs) and [clearBindings] to
 * release bound parameter memory.
 *
 * For sqlite-vec integration:
 *  - [bindVector] binds a float32 vector as a BLOB in the format sqlite-vec
 *    accepts directly (raw float32 array bytes, no header). Works for
 *    columns declared as `float[N]`.
 *  - [getVector] on the result set reads the same format back.
 *
 * Not thread-safe. Statements belong to the thread that created them and
 * must not be shared.
 */
@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect class SQLitePreparedStatement : AutoCloseable {

    fun bindNull(index: Int)
    fun bindLong(index: Int, value: Long)
    fun bindInt(index: Int, value: Int)
    fun bindDouble(index: Int, value: Double)
    fun bindText(index: Int, value: String)
    fun bindBlob(index: Int, value: ByteArray)

    /**
     * Bind a float32 vector for a `float[N]` column of a `vec0` virtual table.
     * Bytes are passed to sqlite-vec as a raw float32 BLOB (no JSON, no header).
     */
    fun bindVector(index: Int, value: FloatArray)

    /** Reset all bound parameters to NULL. */
    fun clearBindings()

    /** Run the statement. For DML returns rows changed; for SELECT advances the cursor one row. */
    fun executeUpdate(): Int

    /** Run a SELECT and return a forward-only result set positioned before the first row. */
    fun executeQuery(): SQLiteResultSet

    /** Rewind the cursor (SELECT) so the statement can be re-executed without recompiling. */
    fun reset()

    /** Release the compiled statement. Idempotent. */
    override fun close()
}