package pw.binom.db.ksqlite

import java.nio.ByteBuffer
import java.nio.ByteOrder

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual class SQLitePreparedStatement internal constructor(
    internal val conn: SQLiteConnection,
    internal val sql: String,
) : AutoCloseable {

    /**
     * Общий handle со всеми result set'ами, которые из него вышли: закрытие
     * statement'а помечает его и больше не даёт нативным вызовам уйти на
     * освобождённый `sqlite3_stmt`.
     */
    private val handle: StmtHandle
    private val closed: Boolean
        get() = handle.closed

    init {
        handle = conn.locked {
            val stmtOut = LongArray(1)
            val rc = SQLiteNative.prepare(conn.handle, sql, stmtOut)
            if (rc != SQLiteNative.sqliteOk() || stmtOut[0] == 0L) {
                throw SQLiteException("prepare failed (code $rc): ${conn.errorMessage()} sql=$sql")
            }
            StmtHandle(stmtOut[0], conn)
        }
    }

    actual fun bindNull(index: Int) {
        op { stmt -> bindCheck(stmt, SQLiteNative.bindNull(stmt, index), "bindNull", index) }
    }

    actual fun bindLong(index: Int, value: Long) {
        op { stmt -> bindCheck(stmt, SQLiteNative.bindLong(stmt, index, value), "bindLong", index) }
    }

    actual fun bindInt(index: Int, value: Int) {
        op { stmt -> bindCheck(stmt, SQLiteNative.bindLong(stmt, index, value.toLong()), "bindInt", index) }
    }

    actual fun bindDouble(index: Int, value: Double) {
        op { stmt -> bindCheck(stmt, SQLiteNative.bindDouble(stmt, index, value), "bindDouble", index) }
    }

    actual fun bindText(index: Int, value: String) {
        op { stmt -> bindCheck(stmt, SQLiteNative.bindText(stmt, index, value), "bindText", index) }
    }

    actual fun bindBlob(index: Int, value: ByteArray) {
        op { stmt -> bindCheck(stmt, SQLiteNative.bindBlob(stmt, index, value), "bindBlob", index) }
    }

    actual fun bindVector(index: Int, value: FloatArray) {
        // sqlite-vec accepts a raw float32 BLOB: N * 4 bytes, no header.
        val bytes = ByteArray(value.size * 4).also {
            ByteBuffer.wrap(it).order(ByteOrder.nativeOrder()).asFloatBuffer().put(value)
        }
        bindBlob(index, bytes)
    }

    actual fun clearBindings() {
        op { stmt -> SQLiteNative.clearBindings(stmt) }
    }

    actual fun executeUpdate(): Int {
        val rc = op { stmt -> SQLiteNative.step(stmt) }
        return when (rc) {
            SQLiteNative.sqliteDone(), SQLiteNative.sqliteOk() -> {
                op { stmt -> SQLiteNative.reset(stmt) }
                conn.changes
            }
            else -> throw mapError(conn, rc, "executeUpdate")
        }
    }

    actual fun executeQuery(): SQLiteResultSet = op { SQLiteResultSetImpl(handle) }

    actual fun reset() {
        op { stmt -> SQLiteNative.reset(stmt) }
    }

    actual override fun close() {
        conn.finalizeStmt(handle)
    }

    /** Выполняет [block] над statement'ом под монитором соединения. */
    private fun <T> op(block: (Long) -> T): T = synchronized(conn.monitor) {
        conn.checkOpen()
        if (closed) throw SQLiteException("Statement is closed")
        block(handle.stmt)
    }

    private fun bindCheck(stmt: Long, rc: Int, op: String, index: Int) {
        if (rc != SQLiteNative.sqliteOk()) {
            throw SQLiteException("$op@$index failed (code $rc): ${conn.errorMessage()}")
        }
    }
}

internal typealias SQLitePreparedStatementImpl = SQLitePreparedStatement
