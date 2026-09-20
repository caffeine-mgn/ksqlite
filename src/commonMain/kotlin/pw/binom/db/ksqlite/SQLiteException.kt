package pw.binom.db.ksqlite

/**
 * Thrown when SQLite returns a non-OK result code that we cannot map to
 * a more specific subtype. The SQLite error message is preserved in [message]
 * and the originating connection can be queried for the extended code.
 */
class SQLiteException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)