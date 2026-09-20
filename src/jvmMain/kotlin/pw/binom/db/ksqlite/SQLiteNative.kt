@file:OptIn(ExperimentalStdlibApi::class)

package pw.binom.db.ksqlite

/**
 * JNI entry points for SQLite — every method here has a matching
 * Java_pw_binom_db_ksqlite_SQLiteNative_* implementation in
 * src/jvmMain/c/ksqlite_jni.c. Handles are raw sqlite3* / sqlite3_stmt*
 * pointers, opaque to the JVM side. Each call is one round-trip into
 * native code; the JNI layer is intentionally a thin pass-through with
 * no extra object lifetime tracking.
 *
 * Integer constants like the SQLITE_* flags and error codes are exposed
 * as static methods so the JVM callers can compose them without seeing
 * the SQLite header directly.
 */
internal object SQLiteNative {

    fun load() = NativeLoader.load()

    @JvmStatic external fun open(path: String, flags: Int): Long
    @JvmStatic external fun close(handle: Long): Int

    @JvmStatic external fun exec(handle: Long, sql: String, changesOut: IntArray?): Int

    @JvmStatic external fun prepare(handle: Long, sql: String, stmtOut: LongArray?): Int
    @JvmStatic external fun step(stmt: Long): Int
    @JvmStatic external fun reset(stmt: Long): Int
    @JvmStatic external fun finalize(stmt: Long): Int
    @JvmStatic external fun clearBindings(stmt: Long): Int
    @JvmStatic external fun busyTimeout(handle: Long, ms: Int): Int

    @JvmStatic external fun lastInsertRowId(handle: Long): Long
    @JvmStatic external fun changes(handle: Long): Int
    @JvmStatic external fun totalChanges(handle: Long): Int

    @JvmStatic external fun bindNull(stmt: Long, idx: Int): Int
    @JvmStatic external fun bindLong(stmt: Long, idx: Int, value: Long): Int
    @JvmStatic external fun bindDouble(stmt: Long, idx: Int, value: Double): Int
    @JvmStatic external fun bindText(stmt: Long, idx: Int, value: String): Int
    @JvmStatic external fun bindBlob(stmt: Long, idx: Int, value: ByteArray?): Int

    @JvmStatic external fun columnCount(stmt: Long): Int
    @JvmStatic external fun columnName(stmt: Long, idx: Int): String?
    @JvmStatic external fun columnType(stmt: Long, idx: Int): Int
    @JvmStatic external fun getLong(stmt: Long, idx: Int): Long
    @JvmStatic external fun getDouble(stmt: Long, idx: Int): Double
    @JvmStatic external fun getText(stmt: Long, idx: Int): String?
    @JvmStatic external fun getBytes(stmt: Long, idx: Int): ByteArray?
    @JvmStatic external fun errmsg(handle: Long, out: ByteArray, maxLen: Int): Int

    @JvmStatic external fun flagOpenReadWrite(): Int
    @JvmStatic external fun flagOpenCreate(): Int
    @JvmStatic external fun flagOpenReadOnly(): Int
    @JvmStatic external fun flagOpenMemory(): Int

    @JvmStatic external fun sqliteOk(): Int
    @JvmStatic external fun sqliteRow(): Int
    @JvmStatic external fun sqliteDone(): Int
    @JvmStatic external fun sqliteInteger(): Int
    @JvmStatic external fun sqliteFloat(): Int
    @JvmStatic external fun sqliteText(): Int
    @JvmStatic external fun sqliteBlob(): Int
    @JvmStatic external fun sqliteNull(): Int
}