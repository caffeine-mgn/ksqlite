package pw.binom.db.ksqlite

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual class SQLiteResultSet internal constructor(
    internal val holder: StmtHolder,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    actual val columnCount: Int
        get() = SQLiteNative.columnCount(holder.stmt)

    actual fun next(): Boolean {
        holder.requireOpen()
        val rc = SQLiteNative.step(holder.stmt)
        return when (rc) {
            SQLiteNative.sqliteRow() -> true
            SQLiteNative.sqliteDone() -> false
            else -> throw mapError(holder.owner, holder.stmt, rc, "next")
        }
    }

    actual fun columnName(index: Int): String {
        holder.requireOpen()
        return SQLiteNative.columnName(holder.stmt, index)
            ?: throw SQLiteException("columnName($index): null pointer")
    }

    actual fun columnType(index: Int): SqlType {
        holder.requireOpen()
        return when (SQLiteNative.columnType(holder.stmt, index)) {
            SQLiteNative.sqliteInteger() -> SqlType.INTEGER
            SQLiteNative.sqliteFloat() -> SqlType.REAL
            SQLiteNative.sqliteText() -> SqlType.TEXT
            SQLiteNative.sqliteBlob() -> SqlType.BLOB
            SQLiteNative.sqliteNull() -> SqlType.NULL
            else -> SqlType.NULL
        }
    }

    actual fun isNull(index: Int): Boolean {
        holder.requireOpen()
        return SQLiteNative.columnType(holder.stmt, index) == SQLiteNative.sqliteNull()
    }

    actual fun getLong(index: Int): Long? {
        holder.requireOpen()
        return if (isNull(index)) null else SQLiteNative.getLong(holder.stmt, index)
    }

    actual fun getInt(index: Int): Int? {
        holder.requireOpen()
        return if (isNull(index)) null else SQLiteNative.getLong(holder.stmt, index).toInt()
    }

    actual fun getDouble(index: Int): Double? {
        holder.requireOpen()
        return if (isNull(index)) null else SQLiteNative.getDouble(holder.stmt, index)
    }

    actual fun getText(index: Int): String? {
        holder.requireOpen()
        return SQLiteNative.getText(holder.stmt, index)
    }

    actual fun getBlob(index: Int): ByteArray? {
        holder.requireOpen()
        return SQLiteNative.getBytes(holder.stmt, index)
    }

    actual fun getVector(index: Int): FloatArray? {
        holder.requireOpen()
        // sqlite-vec stores float32 vectors as a raw BLOB: N * 4 bytes, no
        // header. Same format as bindVector — keep them symmetric.
        val bytes = SQLiteNative.getBytes(holder.stmt, index) ?: return null
        require(bytes.size % 4 == 0) {
            "vector column $index has ${bytes.size} bytes; expected a multiple of 4"
        }
        val n = bytes.size / 4
        val out = FloatArray(n)
        ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asFloatBuffer().get(out)
        return out
    }

    actual fun getJson(index: Int): Json? {
        holder.requireOpen()
        return SQLiteNative.getText(holder.stmt, index)?.let(::Json)
    }

    actual fun getValue(index: Int): SqlValue {
        holder.requireOpen()
        return when (columnType(index)) {
            SqlType.NULL -> SqlValue.Null
            SqlType.INTEGER -> SqlValue.Integer(getLong(index)!!)
            SqlType.REAL -> SqlValue.Real(getDouble(index)!!)
            SqlType.TEXT -> SqlValue.Text(getText(index)!!)
            SqlType.BLOB -> SqlValue.Blob(getBlob(index)!!)
        }
    }

    actual override fun close() {
        if (closed.compareAndSet(false, true)) {
            // For prepared-statement-driven results, do NOT finalize: the
            // caller may continue to reuse the PreparedStatement. We only
            // reset the cursor. For ad-hoc statement results, the owner is
            // the SQLiteStatement which finalizes the stmt in its close().
            SQLiteNative.reset(holder.stmt)
            // Помечаем holder finalized — чтобы GC не пытался ничего
            // сделать с stmt handle (см. KDoc в StmtHolder).
            holder.markFinalized()
        }
    }
}

internal typealias SQLiteResultSetImpl = SQLiteResultSet