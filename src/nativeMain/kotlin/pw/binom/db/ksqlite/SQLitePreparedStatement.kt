@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package pw.binom.db.ksqlite

import cnames.structs.sqlite3_stmt
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.internal_sqlite.SQLITE_DONE
import platform.internal_sqlite.SQLITE_OK
import platform.internal_sqlite.SQLITE_ROW
import platform.internal_sqlite.ksqlite_bind_blob
import platform.internal_sqlite.ksqlite_bind_text
import platform.internal_sqlite.sqlite3_bind_double
import platform.internal_sqlite.sqlite3_bind_int64
import platform.internal_sqlite.sqlite3_bind_null
import platform.internal_sqlite.sqlite3_clear_bindings
import platform.internal_sqlite.sqlite3_errmsg
import platform.internal_sqlite.sqlite3_prepare_v2
import platform.internal_sqlite.sqlite3_reset
import platform.internal_sqlite.sqlite3_step
import kotlin.concurrent.AtomicInt

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual class SQLitePreparedStatement internal constructor(
    internal val conn: SQLiteConnection,
    internal val sql: String,
) : AutoCloseable {

    private val stmt: CPointer<sqlite3_stmt>
    private val closed = AtomicInt(0)

    init {
        memScoped {
            val stmtVar = alloc<CPointerVar<sqlite3_stmt>>()
            val tail = alloc<CPointerVar<ByteVar>>()
            val rc = sqlite3_prepare_v2(
                conn.handle,
                sql,
                sql.length,
                stmtVar.ptr,
                tail.ptr,
            )
            if (rc != SQLITE_OK || stmtVar.value == null) {
                val msg = sqlite3_errmsg(conn.handle)?.toKString() ?: "code $rc"
                throw SQLiteException("prepare failed: $msg (code $rc) sql=$sql")
            }
            stmt = stmtVar.value!!
        }
    }

    actual fun bindNull(index: Int) {
        checkOpen()
        bindCheck(sqlite3_bind_null(stmt, index), "bindNull", index)
    }

    actual fun bindLong(index: Int, value: Long) {
        checkOpen()
        bindCheck(sqlite3_bind_int64(stmt, index, value), "bindLong", index)
    }

    actual fun bindInt(index: Int, value: Int) {
        checkOpen()
        bindCheck(sqlite3_bind_int64(stmt, index, value.toLong()), "bindInt", index)
    }

    actual fun bindDouble(index: Int, value: Double) {
        checkOpen()
        bindCheck(sqlite3_bind_double(stmt, index, value), "bindDouble", index)
    }

    actual fun bindText(index: Int, value: String) {
        checkOpen()
        // ksqlite_bind_text is declared in ksqlite_shim.h with a
        // `const char *` first text argument and a separate length; the
        // cinterop generated binding carries a String? overload that
        // hands the bytes off to SQLITE_TRANSIENT internally, so we
        // don't have to pin the byte array ourselves.
        bindCheck(
            ksqlite_bind_text(stmt, index, value, value.utf8ByteCount()),
            "bindText",
            index,
        )
    }

    actual fun bindBlob(index: Int, value: ByteArray) {
        checkOpen()
        value.usePinned { pinned ->
            bindCheck(
                ksqlite_bind_blob(
                    stmt,
                    index,
                    pinned.addressOf(0),
                    value.size,
                ),
                "bindBlob",
                index,
            )
        }
    }

    actual fun bindVector(index: Int, value: FloatArray) {
        // sqlite-vec accepts a raw float32 BLOB: N elements × 4 bytes, no
        // header. Reinterpret the FloatArray's storage as a byte buffer.
        checkOpen()
        value.usePinned { pinned ->
            bindCheck(
                ksqlite_bind_blob(
                    stmt,
                    index,
                    pinned.addressOf(0),
                    value.size * 4,
                ),
                "bindVector",
                index,
            )
        }
    }

    actual fun clearBindings() {
        checkOpen()
        sqlite3_clear_bindings(stmt)
    }

    actual fun executeUpdate(): Int {
        checkOpen()
        val rc = sqlite3_step(stmt)
        when (rc) {
            SQLITE_DONE, SQLITE_OK -> {
                sqlite3_reset(stmt)
                return conn.changes
            }
            else -> throw mapError(conn, stmt, rc, "executeUpdate")
        }
    }

    actual fun executeQuery(): SQLiteResultSet {
        checkOpen()
        // Caller drives the cursor; do NOT reset before returning because
        // sqlite3_step has not been called yet for this iteration.
        return SQLiteResultSetImpl(StmtHolder(stmt, conn))
    }

    actual fun reset() {
        checkOpen()
        sqlite3_reset(stmt)
    }

    actual override fun close() {
        if (closed.compareAndSet(0, 1)) {
            platform.internal_sqlite.sqlite3_finalize(stmt)
        }
    }

    private fun checkOpen() {
        if (closed.value != 0) throw SQLiteException("Statement is closed")
        conn.checkOpen()
    }

    private fun bindCheck(rc: Int, op: String, index: Int) {
        if (rc != SQLITE_OK) {
            val msg = sqlite3_errmsg(conn.handle)?.toKString() ?: "code $rc"
            throw SQLiteException("$op@$index failed: $msg (code $rc)")
        }
    }
}

internal typealias SQLitePreparedStatementImpl = SQLitePreparedStatement

private fun String.utf8ByteCount(): Int = this.encodeToByteArray().size