@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package pw.binom.db.ksqlite

import cnames.structs.sqlite3_stmt
import kotlinx.cinterop.*
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.internal_sqlite.SQLITE_BLOB
import platform.internal_sqlite.SQLITE_DONE
import platform.internal_sqlite.SQLITE_FLOAT
import platform.internal_sqlite.SQLITE_INTEGER
import platform.internal_sqlite.SQLITE_NULL
import platform.internal_sqlite.SQLITE_ROW
import platform.internal_sqlite.SQLITE_TEXT
import platform.internal_sqlite.ksqlite_column_blob_dup
import platform.internal_sqlite.ksqlite_column_text_dup
import platform.internal_sqlite.ksqlite_free
import platform.internal_sqlite.sqlite3_column_blob
import platform.internal_sqlite.sqlite3_column_bytes
import platform.internal_sqlite.sqlite3_column_count
import platform.internal_sqlite.sqlite3_column_double
import platform.internal_sqlite.sqlite3_column_int64
import platform.internal_sqlite.sqlite3_column_name
import platform.internal_sqlite.sqlite3_column_text
import platform.internal_sqlite.sqlite3_column_type
import platform.internal_sqlite.sqlite3_step
import kotlin.concurrent.AtomicInt

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual class SQLiteResultSet internal constructor(
    internal val holder: StmtHolder,
) : AutoCloseable {

    private val closed = AtomicInt(0)

    actual val columnCount: Int
        get() = sqlite3_column_count(holder.stmt)

    actual fun next(): Boolean {
        holder.requireOpen()
        val rc = sqlite3_step(holder.stmt)
        return when (rc) {
            SQLITE_ROW -> true
            SQLITE_DONE -> false
            else -> throw mapError(holder.owner, holder.stmt, rc, "next")
        }
    }

    actual fun columnName(index: Int): String {
        holder.requireOpen()
        val ptr = sqlite3_column_name(holder.stmt, index)
            ?: throw SQLiteException("columnName($index): null pointer")
        return ptr.toKString()
    }

    actual fun columnType(index: Int): SqlType {
        holder.requireOpen()
        return when (sqlite3_column_type(holder.stmt, index)) {
            SQLITE_INTEGER -> SqlType.INTEGER
            SQLITE_FLOAT -> SqlType.REAL
            SQLITE_TEXT -> SqlType.TEXT
            SQLITE_BLOB -> SqlType.BLOB
            SQLITE_NULL -> SqlType.NULL
            else -> SqlType.NULL
        }
    }

    actual fun isNull(index: Int): Boolean {
        holder.requireOpen()
        return sqlite3_column_type(holder.stmt, index) == SQLITE_NULL
    }

    actual fun getLong(index: Int): Long? {
        holder.requireOpen()
        return if (isNull(index)) null else sqlite3_column_int64(holder.stmt, index)
    }

    actual fun getInt(index: Int): Int? {
        holder.requireOpen()
        return if (isNull(index)) null else sqlite3_column_int64(holder.stmt, index).toInt()
    }

    actual fun getDouble(index: Int): Double? {
        holder.requireOpen()
        return if (isNull(index)) null else sqlite3_column_double(holder.stmt, index)
    }

    actual fun getText(index: Int): String? {
        holder.requireOpen()
        memScoped {
            val len = alloc<IntVar>()
            val ptr = ksqlite_column_text_dup(holder.stmt, index, len.ptr)
            if (ptr == null) return null
            try {
                // ksqlite_column_text_dup writes a NUL-terminated copy of
                // exactly len bytes; toKString reads up to the NUL which is
                // safe even when the value itself contains an embedded NUL.
                return ptr.reinterpret<ByteVar>().toKString()
            } finally {
                ksqlite_free(ptr)
            }
        }
    }

    actual fun getBlob(index: Int): ByteArray? {
        holder.requireOpen()
        memScoped {
            val len = alloc<IntVar>()
            val ptr = ksqlite_column_blob_dup(holder.stmt, index, len.ptr)
            if (ptr == null) return null
            val size = len.value
            try {
                return ptr.reinterpret<ByteVar>().readBytes(size)
            } finally {
                ksqlite_free(ptr)
            }
        }
    }

    actual fun getVector(index: Int): FloatArray? {
        holder.requireOpen()
        // sqlite-vec stores float32 vectors as a raw BLOB: N elements × 4 bytes,
        // no header. Same format we emit from bindVector() — keep them
        // symmetric.
        val bytes = getBlob(index) ?: return null
        require(bytes.size % 4 == 0) {
            "vector column $index has ${bytes.size} bytes; expected a multiple of 4"
        }
        val n = bytes.size / 4
        val out = FloatArray(n)
        for (i in 0 until n) {
            val off = i * 4
            val bits = (bytes[off].toInt() and 0xff) or
                ((bytes[off + 1].toInt() and 0xff) shl 8) or
                ((bytes[off + 2].toInt() and 0xff) shl 16) or
                ((bytes[off + 3].toInt() and 0xff) shl 24)
            out[i] = Float.fromBits(bits)
        }
        return out
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
        if (closed.compareAndSet(0, 1)) {
            // For prepared-statement-driven results, do NOT finalize here:
            // the user may continue to reuse the statement. We only reset
            // the cursor. For ad-hoc statement results, the owner is the
            // SQLiteStatement which finalizes the stmt in its close().
            platform.internal_sqlite.sqlite3_reset(holder.stmt)
        }
    }
}

internal typealias SQLiteResultSetImpl = SQLiteResultSet

private fun CPointer<ByteVar>.readBytes(size: Int): ByteArray {
    val out = ByteArray(size)
    if (size == 0) return out
    // Avoid `platform.posix.memcpy` here: its `size_t` parameter is ULong
    // on 64-bit native targets and UInt on 32-bit ones (android_arm32,
    // wasm32). A single Kotlin function compiled to multi-target metadata
    // can't legally bridge those word sizes, so a byte-by-byte loop is the
    // simplest portable solution. SQLite blob columns are typically small
    // (kilobytes), so the constant factor doesn't matter.
    var p: CPointer<ByteVar> = this
    repeat(size) { i ->
        out[i] = p[i]
        p = p.plus(1)!!
    }
    return out
}