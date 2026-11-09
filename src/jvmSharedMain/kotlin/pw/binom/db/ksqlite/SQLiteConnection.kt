package pw.binom.db.ksqlite

import java.util.concurrent.atomic.AtomicBoolean

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual class SQLiteConnection internal constructor(
    internal val handle: Long,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    /**
     * Монитор, под которым выполняются ВСЕ обращения к нативному handle:
     * соединение, его statements и result sets. Нужен, потому что sqlite3 —
     * не потокобезопасный объект: без общей блокировки `sqlite3_finalize`
     * (закрытие statement'а/соединения) мог выполниться между проверкой
     * `checkOpen()` и самим нативным вызовом в другом потоке. Тогда вызов
     * шёл на уже освобождённый `sqlite3_stmt` и JVM падала SIGSEGV'ом
     * внутри `pthread_mutex_lock` (см. hs_err_pid*.log в репозитории).
     */
    internal val monitor = Any()

    init {
        require(handle != 0L) { "Tried to wrap a null SQLite handle" }
    }

    actual val isOpen: Boolean
        get() = !closed.get()

    actual val lastInsertRowId: Long
        get() = locked { SQLiteNative.lastInsertRowId(handle) }

    actual val changes: Int
        get() = locked { SQLiteNative.changes(handle) }

    actual val totalChanges: Int
        get() = locked { SQLiteNative.totalChanges(handle) }

    actual fun prepare(sql: String): SQLitePreparedStatement =
        SQLitePreparedStatementImpl(this, sql)

    actual fun createStatement(): SQLiteStatement =
        SQLiteStatementImpl(this)

    actual fun exec(sql: String): Int = locked {
        val changesOut = IntArray(1)
        val rc = SQLiteNative.exec(handle, sql, changesOut)
        if (rc != SQLiteNative.sqliteOk()) {
            throw SQLiteException("exec failed (code $rc): ${lastErrorMessage()}")
        }
        changesOut[0]
    }

    actual fun beginTransaction() {
        execOrFail("BEGIN")
    }

    actual fun commit() {
        execOrFail("COMMIT")
    }

    actual fun rollback() {
        try {
            execOrFail("ROLLBACK")
        } catch (_: Throwable) {
            // rollback failures are non-fatal at this layer; the caller
            // is typically already unwinding from a primary exception.
        }
    }

    actual fun setBusyTimeout(millis: Int) {
        locked { SQLiteNative.busyTimeout(handle, millis) }
    }

    actual override fun close() {
        synchronized(monitor) {
            if (closed.compareAndSet(false, true)) {
                SQLiteNative.close(handle)
            }
        }
    }

    /**
     * Выполняет [block] под [monitor], предварительно убедившись, что
     * соединение ещё открыто. Все нативные вызовы обязаны идти через этот
     * метод (или [locked]), иначе `close()` из другого потока может
     * освободить handle прямо во время вызова.
     */
    internal fun <T> locked(block: () -> T): T = synchronized(monitor) {
        checkOpen()
        block()
    }

    /**
     * Выполняет нативную операцию над statement'ом под [monitor], проверяя
     * и соединение, и сам statement.
     */
    internal fun <T> lockedStmt(stmt: StmtHandle, block: (Long) -> T): T = synchronized(monitor) {
        checkOpen()
        if (stmt.closed) throw SQLiteException("Statement is closed")
        block(stmt.stmt)
    }

    /**
     * Закрывает statement (финализирует handle) под [monitor].
     *
     * @return false, если statement уже был закрыт/финализирован.
     */
    internal fun finalizeStmt(stmt: StmtHandle): Boolean = synchronized(monitor) {
        if (stmt.closed) return@synchronized false
        stmt.closed = true
        val handle = stmt.stmt
        if (handle != 0L) {
            SQLiteNative.finalize(handle)
        }
        true
    }

    /** Сбрасывает курсор statement'а; после finalize — no-op. */
    internal fun resetStmt(stmt: StmtHandle) {
        synchronized(monitor) {
            if (stmt.closed || closed.get()) return
            SQLiteNative.reset(stmt.stmt)
        }
    }

    /** Проверка «открыто ли» без блокировки. Вызывать только под [monitor]. */
    internal fun checkOpen() {
        if (closed.get()) throw SQLiteException("Connection is closed")
    }

    private fun execOrFail(sql: String) {
        locked {
            val changesOut = IntArray(1)
            val rc = SQLiteNative.exec(handle, sql, changesOut)
            if (rc != SQLiteNative.sqliteOk()) {
                throw SQLiteException("$sql failed (code $rc): ${lastErrorMessage()}")
            }
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
