package pw.binom.db.ksqlite

/**
 * SQLite affinity types reported by [SQLiteResultSet.columnType].
 *
 * Note: SQLite uses dynamic typing (manifest types) — the declared column
 * affinity is a hint for coercion, but a TEXT column can legally contain
 * an INTEGER. Always check the runtime type with [SQLiteResultSet.columnType]
 * before calling a typed getter like [SQLiteResultSet.getLong].
 */
enum class SqlType { NULL, INTEGER, REAL, TEXT, BLOB }