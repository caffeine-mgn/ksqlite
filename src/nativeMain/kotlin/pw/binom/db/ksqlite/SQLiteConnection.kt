@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package pw.binom.db.ksqlite

import cnames.structs.sqlite3
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ByteVarOf
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.cinterop.toKString
import platform.internal_sqlite.SQLITE_OK
import platform.internal_sqlite.SQLITE_OPEN_CREATE
import platform.internal_sqlite.SQLITE_OPEN_MEMORY
import platform.internal_sqlite.SQLITE_OPEN_READONLY
import platform.internal_sqlite.SQLITE_OPEN_READWRITE
import platform.internal_sqlite.ksqlite_init
import platform.internal_sqlite.sqlite3_busy_timeout
import platform.internal_sqlite.sqlite3_changes64
import platform.internal_sqlite.sqlite3_close_v2
import platform.internal_sqlite.sqlite3_errmsg
import platform.internal_sqlite.sqlite3_exec
import platform.internal_sqlite.sqlite3_free
import platform.internal_sqlite.sqlite3_last_insert_rowid
import platform.internal_sqlite.sqlite3_open_v2
import platform.internal_sqlite.sqlite3_total_changes64
import kotlin.concurrent.AtomicInt

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual class SQLiteConnection internal constructor(
    internal val handle: CPointer<sqlite3>,
) : AutoCloseable {

    private val closed = AtomicInt(0)

    actual val isOpen: Boolean
        get() = closed.value == 0

    actual val lastInsertRowId: Long
        get() {
            checkOpen()
            return sqlite3_last_insert_rowid(handle)
        }

    actual val changes: Int
        get() {
            checkOpen()
            return sqlite3_changes64(handle).toInt()
        }

    actual val totalChanges: Int
        get() {
            checkOpen()
            return sqlite3_total_changes64(handle).toInt()
        }

    actual fun prepare(sql: String): SQLitePreparedStatement {
        checkOpen()
        return SQLitePreparedStatementImpl(this, sql)
    }

    actual fun createStatement(): SQLiteStatement = SQLiteStatementImpl(this)

    actual fun exec(sql: String): Int {
        checkOpen()
        val before = sqlite3_total_changes64(handle)
        runExec(sql)
        return (sqlite3_total_changes64(handle) - before).toInt()
    }

    actual fun beginTransaction() {
        checkOpen()
        runExec("BEGIN")
    }

    actual fun commit() {
        checkOpen()
        runExec("COMMIT")
    }

    actual fun rollback() {
        checkOpen()
        try {
            runExec("ROLLBACK")
        } catch (_: Throwable) {
            // rollback failures must never propagate; the caller is typically
            // already unwinding from a primary exception.
        }
    }

    actual fun setBusyTimeout(millis: Int) {
        checkOpen()
        sqlite3_busy_timeout(handle, millis)
    }

    actual override fun close() {
        if (!closed.compareAndSet(0, 1)) return
        sqlite3_close_v2(handle)
    }

    internal fun checkOpen() {
        if (closed.value != 0) throw SQLiteException("Connection is closed")
    }

    private fun runExec(sql: String) {
        memScoped {
            val errmsg = alloc<CPointerVar<ByteVar>>()
            val rc = sqlite3_exec(handle, sql, null, null, errmsg.ptr)
            if (rc != SQLITE_OK) {
                val msg = errmsg.value?.toKString() ?: "code $rc"
                sqlite3_free(errmsg.value)
                throw SQLiteException("$sql failed: $msg (code $rc)")
            }
        }
    }

    actual companion object {
        actual fun open(path: String, readOnly: Boolean): SQLiteConnection {
            SQLiteNative.init()
            memScoped {
                val handle = alloc<CPointerVar<sqlite3>>()
                val flags = if (readOnly) SQLITE_OPEN_READONLY
                else SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE
                val rc = sqlite3_open_v2(path, handle.ptr, flags, null)
                val db = handle.value
                if (rc != SQLITE_OK || db == null) {
                    val msg = db?.let { sqlite3_errmsg(it)?.toKString() } ?: "unknown"
                    if (db != null) sqlite3_close_v2(db)
                    throw SQLiteException("Can't open database '$path': $msg (code $rc)")
                }
                return SQLiteConnection(db)
            }
        }

        actual fun memory(name: String?): SQLiteConnection {
            SQLiteNative.init()
            memScoped {
                val handle = alloc<CPointerVar<sqlite3>>()
                val path = if (name.isNullOrBlank()) ":memory:" else "file:$name?mode=memory"
                val rc = sqlite3_open_v2(
                    path,
                    handle.ptr,
                    SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE or SQLITE_OPEN_MEMORY,
                    null,
                )
                val db = handle.value
                if (rc != SQLITE_OK || db == null) {
                    val msg = db?.let { sqlite3_errmsg(it)?.toKString() } ?: "unknown"
                    if (db != null) sqlite3_close_v2(db)
                    throw SQLiteException("Can't open in-memory database: $msg (code $rc)")
                }
                return SQLiteConnection(db)
            }
        }

        actual fun temporary(): SQLiteConnection = memory()
    }
}

internal typealias SQLiteConnectionImpl = SQLiteConnection