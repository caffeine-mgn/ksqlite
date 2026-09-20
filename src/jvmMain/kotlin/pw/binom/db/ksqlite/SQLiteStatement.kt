package pw.binom.db.ksqlite

/**
 * Shared lifecycle for any compiled sqlite3_stmt handle. Both the
 * one-shot [SQLiteStatement] and the reusable [SQLitePreparedStatement]
 * own one of these and delegate finalize() to it.
 */
internal class StmtHolder internal constructor(
    internal val stmt: Long,
    internal val owner: SQLiteConnection,
) {
    private var finalized = false

    fun finalize() {
        if (!finalized && stmt != 0L) {
            SQLiteNative.finalize(stmt)
        }
        finalized = true
    }

    fun requireOpen() {
        if (finalized) throw SQLiteException("Statement is finalized")
        owner.checkOpen()
    }
}

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual class SQLiteStatement internal constructor(
    internal val conn: SQLiteConnection,
) : AutoCloseable {

    private var holder: StmtHolder? = null

    actual fun executeUpdate(sql: String): Int {
        conn.checkOpen()
        val h = compile(sql)
        try {
            val rc = SQLiteNative.step(h.stmt)
            return when (rc) {
                SQLiteNative.sqliteDone(), SQLiteNative.sqliteOk() -> conn.changes
                else -> throw mapError(conn, h.stmt, rc, "executeUpdate")
            }
        } finally {
            h.finalize()
        }
    }

    actual fun executeQuery(sql: String): SQLiteResultSet {
        conn.checkOpen()
        val h = compile(sql)
        return SQLiteResultSetImpl(h)
    }

    actual override fun close() {
        holder?.finalize()
        holder = null
    }

    private fun compile(sql: String): StmtHolder {
        holder?.finalize()
        val stmtOut = LongArray(1)
        val rc = SQLiteNative.prepare(conn.handle, sql, stmtOut)
        if (rc != SQLiteNative.sqliteOk() || stmtOut[0] == 0L) {
            throw SQLiteException("prepare failed (code $rc): ${conn.errorMessage()} sql=$sql")
        }
        val h = StmtHolder(stmtOut[0], conn)
        holder = h
        return h
    }
}

internal typealias SQLiteStatementImpl = SQLiteStatement

internal fun mapError(
    conn: SQLiteConnection,
    stmt: Long,
    rc: Int,
    op: String,
): SQLiteException = SQLiteException("$op failed (code $rc): ${conn.errorMessage()}")

internal fun SQLiteConnection.errorMessage(): String {
    val buf = ByteArray(512)
    val n = SQLiteNative.errmsg(handle, buf, buf.size)
    if (n <= 0) return "unknown"
    return buf.copyOf(n).toString(Charsets.UTF_8)
}