package pw.binom.db.ksqlite

/**
 * Forward-only cursor over the rows produced by a SELECT.
 *
 * Created by [SQLiteStatement.executeQuery] or
 * [SQLitePreparedStatement.executeQuery]. Walk rows with [next]; the
 * cursor is positioned before the first row on construction, so the
 * canonical loop is `while (rs.next()) { ... }`.
 *
 * Typed getters ([getLong], [getText], ...) return `null` for SQL NULL
 * values. They do not coerce — calling [getLong] on a TEXT column
 * that happens to contain `"42"` will fail with a SQLite type error.
 * Use [columnType] first when the schema is dynamic, or [getValue] for
 * a tagged-union dispatch.
 */
@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect class SQLiteResultSet : AutoCloseable {

    /** Number of columns in the result set. */
    val columnCount: Int

    /** Advance to the next row. Returns false at end-of-results. */
    fun next(): Boolean

    /** Column name at [index] (0-based). */
    fun columnName(index: Int): String

    /** Runtime type of the value at [index] in the current row. */
    fun columnType(index: Int): SqlType

    /** True if the value at [index] is SQL NULL. */
    fun isNull(index: Int): Boolean

    fun getLong(index: Int): Long?
    fun getInt(index: Int): Int?
    fun getDouble(index: Int): Double?
    fun getText(index: Int): String?
    fun getBlob(index: Int): ByteArray?

    /**
     * Read a `float[N]` vector column of a `vec0` virtual table. Returns
     * null on SQL NULL. Assumes the column was written with [SQLitePreparedStatement.bindVector]
     * or any other compatible float32 BLOB source.
     */
    fun getVector(index: Int): FloatArray?

    /**
     * Read a JSON column as [Json]. The value is stored as TEXT in SQLite
     * (this is what the JSON1 extension assumes); we keep the raw text and
     * do no parsing — turn it into a typed value yourself with
     * kotlinx.serialization or whatever you want.
     *
     * Returns null on SQL NULL.
     */
    fun getJson(index: Int): Json?

    /** Read the value at [index] as a tagged union. */
    fun getValue(index: Int): SqlValue

    /** Release the cursor. Idempotent. */
    override fun close()
}