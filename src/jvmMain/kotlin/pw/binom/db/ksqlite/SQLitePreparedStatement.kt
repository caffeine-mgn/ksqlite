package pw.binom.db.ksqlite

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual class SQLitePreparedStatement internal constructor(
    internal val conn: SQLiteConnection,
    internal val sql: String,
) : AutoCloseable {

    private val stmt: Long
    private val closed = AtomicBoolean(false)

    init {
        conn.checkOpen()
        val stmtOut = LongArray(1)
        val rc = SQLiteNative.prepare(conn.handle, sql, stmtOut)
        if (rc != SQLiteNative.sqliteOk() || stmtOut[0] == 0L) {
            throw SQLiteException("prepare failed (code $rc): ${conn.errorMessage()} sql=$sql")
        }
        stmt = stmtOut[0]
    }

    actual fun bindNull(index: Int) {
        checkOpen()
        bindCheck(SQLiteNative.bindNull(stmt, index), "bindNull", index)
    }

    actual fun bindLong(index: Int, value: Long) {
        checkOpen()
        bindCheck(SQLiteNative.bindLong(stmt, index, value), "bindLong", index)
    }

    actual fun bindInt(index: Int, value: Int) {
        checkOpen()
        bindCheck(SQLiteNative.bindLong(stmt, index, value.toLong()), "bindInt", index)
    }

    actual fun bindDouble(index: Int, value: Double) {
        checkOpen()
        bindCheck(SQLiteNative.bindDouble(stmt, index, value), "bindDouble", index)
    }

    actual fun bindText(index: Int, value: String) {
        checkOpen()
        bindCheck(SQLiteNative.bindText(stmt, index, value), "bindText", index)
    }

    actual fun bindBlob(index: Int, value: ByteArray) {
        checkOpen()
        bindCheck(SQLiteNative.bindBlob(stmt, index, value), "bindBlob", index)
    }

    actual fun bindVector(index: Int, value: FloatArray) {
        // sqlite-vec accepts a raw float32 BLOB: N * 4 bytes, no header.
        checkOpen()
        val bytes = ByteArray(value.size * 4).also {
            ByteBuffer.wrap(it).order(ByteOrder.nativeOrder()).asFloatBuffer().put(value)
        }
        bindCheck(SQLiteNative.bindBlob(stmt, index, bytes), "bindVector", index)
    }

    actual fun clearBindings() {
        checkOpen()
        SQLiteNative.clearBindings(stmt)
    }

    actual fun executeUpdate(): Int {
        checkOpen()
        val rc = SQLiteNative.step(stmt)
        return when (rc) {
            SQLiteNative.sqliteDone(), SQLiteNative.sqliteOk() -> {
                SQLiteNative.reset(stmt)
                conn.changes
            }
            else -> throw mapError(conn, stmt, rc, "executeUpdate")
        }
    }

    actual fun executeQuery(): SQLiteResultSet {
        checkOpen()
        return SQLiteResultSetImpl(StmtHolder(stmt, conn))
    }

    actual fun reset() {
        checkOpen()
        SQLiteNative.reset(stmt)
    }

    actual override fun close() {
        if (closed.compareAndSet(false, true) && stmt != 0L) {
            SQLiteNative.finalize(stmt)
        }
    }

    private fun checkOpen() {
        if (closed.get()) throw SQLiteException("Statement is closed")
        conn.checkOpen()
    }

    private fun bindCheck(rc: Int, op: String, index: Int) {
        if (rc != SQLiteNative.sqliteOk()) {
            throw SQLiteException("$op@$index failed (code $rc): ${conn.errorMessage()}")
        }
    }
}

internal typealias SQLitePreparedStatementImpl = SQLitePreparedStatement