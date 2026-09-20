package pw.binom.db.ksqlite

import java.util.concurrent.atomic.AtomicBoolean

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual class SQLiteConnection internal constructor(
    internal val handle: Long,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    init {
        require(handle != 0L) { "Tried to wrap a null SQLite handle" }
    }

    actual val isOpen: Boolean
        get() = !closed.get()

    actual val lastInsertRowId: Long
        get() {
            checkOpen()
            return SQLiteNative.lastInsertRowId(handle)
        }

    actual val changes: Int
        get() {
            checkOpen()
            return SQLiteNative.changes(handle)
        }

    actual val totalChanges: Int
        get() {
            checkOpen()
            return SQLiteNative.totalChanges(handle)
        }

    actual fun prepare(sql: String): SQLitePreparedStatement =
        SQLitePreparedStatementImpl(this, sql)

    actual fun createStatement(): SQLiteStatement =
        SQLiteStatementImpl(this)

    actual fun exec(sql: String): Int {
        checkOpen()
        val changesOut = IntArray(1)
        val rc = SQLiteNative.exec(handle, sql, changesOut)
        if (rc != SQLiteNative.sqliteOk()) {
            throw SQLiteException("exec failed (code $rc): ${lastErrorMessage()}")
        }
        return changesOut[0]
    }

    actual fun beginTransaction() {
        checkOpen()
        execOrFail("BEGIN")
    }

    actual fun commit() {
        checkOpen()
        execOrFail("COMMIT")
    }

    actual fun rollback() {
        checkOpen()
        try {
            execOrFail("ROLLBACK")
        } catch (_: Throwable) {
            // rollback failures are non-fatal at this layer; the caller
            // is typically already unwinding from a primary exception.
        }
    }

    actual fun setBusyTimeout(millis: Int) {
        checkOpen()
        SQLiteNative.busyTimeout(handle, millis)
    }

    actual override fun close() {
        if (closed.compareAndSet(false, true)) {
            SQLiteNative.close(handle)
        }
    }

    internal fun checkOpen() {
        if (closed.get()) throw SQLiteException("Connection is closed")
    }

    private fun execOrFail(sql: String) {
        val changesOut = IntArray(1)
        val rc = SQLiteNative.exec(handle, sql, changesOut)
        if (rc != SQLiteNative.sqliteOk()) {
            throw SQLiteException("$sql failed (code $rc): ${lastErrorMessage()}")
        }
    }

    private fun lastErrorMessage(): String {
        val buf = ByteArray(512)
        val n = SQLiteNative.errmsg(handle, buf, buf.size)
        if (n <= 0) return "unknown"
        return buf.copyOf(n).toString(Charsets.UTF_8)
    }

    actual companion object {
        actual fun open(path: String, readOnly: Boolean): SQLiteConnection {
            SQLiteNative.load()
            val flags = if (readOnly) {
                SQLiteNative.flagOpenReadOnly()
            } else {
                SQLiteNative.flagOpenReadWrite() or SQLiteNative.flagOpenCreate()
            }
            val handle = SQLiteNative.open(path, flags)
            if (handle == 0L) {
                throw SQLiteException("Can't open database '$path'")
            }
            return SQLiteConnection(handle)
        }

        actual fun memory(name: String?): SQLiteConnection {
            SQLiteNative.load()
            val flags = SQLiteNative.flagOpenReadWrite() or
                SQLiteNative.flagOpenCreate() or
                SQLiteNative.flagOpenMemory()
            val path = if (name.isNullOrBlank()) ":memory:" else "file:$name?mode=memory"
            val handle = SQLiteNative.open(path, flags)
            if (handle == 0L) {
                throw SQLiteException("Can't open in-memory database")
            }
            return SQLiteConnection(handle)
        }

        actual fun temporary(): SQLiteConnection = memory()
    }
}

internal typealias SQLiteConnectionImpl = SQLiteConnection