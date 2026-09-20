package pw.binom.db.ksqlite

/**
 * Tagged union over the SQLite value types. Use [SQLiteResultSet.getValue]
 * when the caller does not want to dispatch on type manually.
 *
 * BLOB is backed by a copy of the byte array — reading a blob is a
 * `sqlite3_column_blob` + memcpy on the native side, so the bytes are
 * already detached from the underlying sqlite3_value before we hand them
 * out.
 */
sealed class SqlValue {

    object Null : SqlValue() {
        override fun toString(): String = "Null"
    }

    data class Integer(val value: Long) : SqlValue()
    data class Real(val value: Double) : SqlValue()
    data class Text(val value: String) : SqlValue()
    data class Blob(val value: ByteArray) : SqlValue() {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Blob && value.contentEquals(other.value))

        override fun hashCode(): Int = value.contentHashCode()

        override fun toString(): String = "Blob(size=${value.size})"
    }
}