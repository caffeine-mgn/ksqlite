@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package pw.binom.db.ksqlite

import cnames.structs.sqlite3_stmt
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ByteVarOf
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.internal_sqlite.SQLITE_DONE
import platform.internal_sqlite.SQLITE_OK
import platform.internal_sqlite.SQLITE_ROW
import platform.internal_sqlite.ksqlite_free
import platform.internal_sqlite.sqlite3_errmsg
import platform.internal_sqlite.sqlite3_finalize
import platform.internal_sqlite.sqlite3_prepare_v2
import platform.internal_sqlite.sqlite3_step
import kotlin.concurrent.AtomicInt

/**
 * Shared lifecycle for any sqlite3_stmt handle. Both the one-shot
 * [SQLiteStatementImpl] and the reusable [SQLitePreparedStatementImpl]
 * hold one of these and delegate finalize() to it.
 */
internal class StmtHolder internal constructor(
    internal val stmt: CPointer<sqlite3_stmt>,
    internal val owner: SQLiteConnection,
) {
    private val finalized = AtomicInt(0)

    fun finalize() {
        if (!finalized.compareAndSet(0, 1)) return
        sqlite3_finalize(stmt)
    }

    fun requireOpen() {
        if (finalized.value != 0) throw SQLiteException("Statement is finalized")
        owner.checkOpen()
    }
}

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual class SQLiteStatement internal constructor(
    internal val conn: SQLiteConnection,
) : AutoCloseable {

    private var stmtHolder: StmtHolder? = null

    actual fun executeUpdate(sql: String): Int {
        conn.checkOpen()
        val holder = compile(sql)
        try {
            val rc = sqlite3_step(holder.stmt)
            when (rc) {
                SQLITE_DONE, SQLITE_OK -> return conn.changes
                else -> throw mapError(conn, holder.stmt, rc, "executeUpdate")
            }
        } finally {
            holder.finalize()
        }
    }

    actual fun executeQuery(sql: String): SQLiteResultSet {
        conn.checkOpen()
        val holder = compile(sql)
        return SQLiteResultSetImpl(holder)
    }

    actual override fun close() {
        stmtHolder?.finalize()
        stmtHolder = null
    }

    private fun compile(sql: String): StmtHolder {
        stmtHolder?.finalize()
        return memScoped {
            val stmt = alloc<CPointerVar<sqlite3_stmt>>()
            val tail = alloc<CPointerVar<ByteVar>>()
            val rc = sqlite3_prepare_v2(
                conn.handle,
                sql,
                sql.length,
                stmt.ptr,
                tail.ptr,
            )
            if (rc != SQLITE_OK || stmt.value == null) {
                val msg = sqlite3_errmsg(conn.handle)?.toKString() ?: "code $rc"
                throw SQLiteException("prepare failed: $msg (code $rc) sql=$sql")
            }
            val h = StmtHolder(stmt.value!!, conn)
            stmtHolder = h
            h
        }
    }
}

internal typealias SQLiteStatementImpl = SQLiteStatement

internal fun mapError(
    conn: SQLiteConnection,
    stmt: CPointer<sqlite3_stmt>,
    rc: Int,
    op: String,
): SQLiteException {
    val msg = sqlite3_errmsg(conn.handle)?.toKString() ?: "code $rc"
    return SQLiteException("$op failed: $msg (code $rc)")
}