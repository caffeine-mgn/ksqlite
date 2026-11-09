package pw.binom.db.ksqlite

/**
 * Общий handle скомпилированного `sqlite3_stmt` для [SQLiteStatement] и
 * [SQLitePreparedStatement] (и их result sets).
 *
 * Флаг [closed] читается и пишется только под [SQLiteConnection.monitor] —
 * ровно тем же монитором, под которым выполняются нативные вызовы и
 * `sqlite3_finalize`. Благодаря этому «проверить, что statement жив, и
 * дёрнуть его» — одна атомарная операция: finalize из другого потока не
 * может вклиниться между проверкой и вызовом и JVM не падает SIGSEGV'ом в
 * `pthread_mutex_lock` (см. hs_err_pid*.log в репозитории).
 */
internal class StmtHandle internal constructor(
    internal val stmt: Long,
    internal val conn: SQLiteConnection,
) {
    /** Защищено [SQLiteConnection.monitor]. */
    internal var closed = false
}

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual class SQLiteStatement internal constructor(
    internal val conn: SQLiteConnection,
) : AutoCloseable {

    private var handle: StmtHandle? = null

    actual fun executeUpdate(sql: String): Int {
        val h = compile(sql)
        try {
            val rc = conn.lockedStmt(h) { SQLiteNative.step(it) }
            return when (rc) {
                SQLiteNative.sqliteDone(), SQLiteNative.sqliteOk() -> conn.changes
                else -> throw mapError(conn, rc, "executeUpdate")
            }
        } finally {
            conn.finalizeStmt(h)
        }
    }

    actual fun executeQuery(sql: String): SQLiteResultSet {
        val h = compile(sql)
        return SQLiteResultSetImpl(h)
    }

    actual override fun close() {
        val h = handle
        handle = null
        if (h != null) conn.finalizeStmt(h)
    }

    private fun compile(sql: String): StmtHandle {
        val previous = handle
        return conn.locked {
            if (previous != null && !previous.closed) {
                previous.closed = true
                SQLiteNative.finalize(previous.stmt)
            }
            val stmtOut = LongArray(1)
            val rc = SQLiteNative.prepare(conn.handle, sql, stmtOut)
            if (rc != SQLiteNative.sqliteOk() || stmtOut[0] == 0L) {
                throw SQLiteException("prepare failed (code $rc): ${conn.errorMessage()} sql=$sql")
            }
            StmtHandle(stmtOut[0], conn).also { handle = it }
        }
    }
}

internal typealias SQLiteStatementImpl = SQLiteStatement

internal fun mapError(
    conn: SQLiteConnection,
    rc: Int,
    op: String,
): SQLiteException = SQLiteException("$op failed (code $rc): ${conn.errorMessage()}")

internal fun SQLiteConnection.errorMessage(): String {
    val buf = ByteArray(512)
    val n = synchronized(monitor) { SQLiteNative.errmsg(handle, buf, buf.size) }
    if (n <= 0) return "unknown"
    return buf.copyOf(buf.size).toString(Charsets.UTF_8)
}
