package pw.binom.db.ksqlite

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual class SQLiteResultSet internal constructor(
    internal val holder: StmtHandle,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    /**
     * Каждое обращение к нативному statement'у идёт под
     * [SQLiteConnection.monitor]: пока мы читаем колонку, никто не может
     * финализировать statement или закрыть соединение.
     */
    private fun <T> stmtOp(block: (Long) -> T): T {
        if (closed.get()) throw SQLiteException("ResultSet is closed")
        return holder.conn.lockedStmt(holder, block)
    }

    actual val columnCount: Int
        get() = stmtOp { SQLiteNative.columnCount(it) }

    actual fun next(): Boolean = stmtOp { stmt ->
        val rc = SQLiteNative.step(stmt)
        when (rc) {
            SQLiteNative.sqliteRow() -> true
            SQLiteNative.sqliteDone() -> false
            else -> throw mapError(holder.conn, rc, "next")
        }
    }

    actual fun columnName(index: Int): String = stmtOp { stmt ->
        SQLiteNative.columnName(stmt, index)
            ?: throw SQLiteException("columnName($index): null pointer")
    }

    actual fun columnType(index: Int): SqlType = stmtOp { stmt ->
        when (SQLiteNative.columnType(stmt, index)) {
            SQLiteNative.sqliteInteger() -> SqlType.INTEGER
            SQLiteNative.sqliteFloat() -> SqlType.REAL
            SQLiteNative.sqliteText() -> SqlType.TEXT
            SQLiteNative.sqliteBlob() -> SqlType.BLOB
            SQLiteNative.sqliteNull() -> SqlType.NULL
            else -> SqlType.NULL
        }
    }

    actual fun isNull(index: Int): Boolean = stmtOp { stmt ->
        SQLiteNative.columnType(stmt, index) == SQLiteNative.sqliteNull()
    }

    actual fun getLong(index: Int): Long? = stmtOp { stmt ->
        if (SQLiteNative.columnType(stmt, index) == SQLiteNative.sqliteNull()) {
            null
        } else {
            SQLiteNative.getLong(stmt, index)
        }
    }

    actual fun getInt(index: Int): Int? = stmtOp { stmt ->
        if (SQLiteNative.columnType(stmt, index) == SQLiteNative.sqliteNull()) {
            null
        } else {
            SQLiteNative.getLong(stmt, index).toInt()
        }
    }

    actual fun getDouble(index: Int): Double? = stmtOp { stmt ->
        if (SQLiteNative.columnType(stmt, index) == SQLiteNative.sqliteNull()) {
            null
        } else {
            SQLiteNative.getDouble(stmt, index)
        }
    }

    actual fun getText(index: Int): String? = stmtOp { stmt ->
        SQLiteNative.getText(stmt, index)
    }

    actual fun getBlob(index: Int): ByteArray? = stmtOp { stmt ->
        SQLiteNative.getBytes(stmt, index)
    }

    actual fun getVector(index: Int): FloatArray? {
        // sqlite-vec stores float32 vectors as a raw BLOB: N * 4 bytes, no
        // header. Same format as bindVector — keep them symmetric.
        val bytes = getBlob(index) ?: return null
        require(bytes.size % 4 == 0) {
            "vector column $index has ${bytes.size} bytes; expected a multiple of 4"
        }
        val n = bytes.size / 4
        val out = FloatArray(n)
        ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asFloatBuffer().get(out)
        return out
    }

    actual fun getJson(index: Int): Json? = getText(index)?.let(::Json)

    actual fun getValue(index: Int): SqlValue {
        return when (val t = columnType(index)) {
            SqlType.NULL -> SqlValue.Null
            SqlType.INTEGER -> SqlValue.Integer(getLong(index)!!)
            SqlType.REAL -> SqlValue.Real(getDouble(index)!!)
            SqlType.TEXT -> SqlValue.Text(getText(index)!!)
            SqlType.BLOB -> SqlValue.Blob(getBlob(index)!!)
        }
    }

    actual override fun close() {
        if (closed.compareAndSet(false, true)) {
            // Для resultset'а prepared statement'а НЕ финализируем stmt: его
            // владелец — сам PreparedStatement, курсор сбрасываем, чтобы
            // statement можно было переиспользовать. Если statement уже
            // закрыт (или его закрыли параллельно) — reset становится no-op.
            holder.conn.resetStmt(holder)
        }
    }
}

internal typealias SQLiteResultSetImpl = SQLiteResultSet
