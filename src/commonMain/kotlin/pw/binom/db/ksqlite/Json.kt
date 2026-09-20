package pw.binom.db.ksqlite

/**
 * Wrapper for JSON values stored in SQLite. SQLite has first-class JSON support
 * via the [JSON1](https://www.sqlite.org/json1.html) extension
 * (`-DSQLITE_ENABLE_JSON1`, baked into our amalgamation) — every column that
 * holds JSON is just a `TEXT` column under the hood, and SQL functions like
 * `json_extract`, `json_array`, `json_object` work straight away.
 *
 * This class exists purely to make JSON columns self-describing on the Kotlin
 * side. Read them with [SQLiteResultSet.getJson] / [getJsonOrNull], and the
 * raw JSON text is preserved as-is — no parsing, no copying. If you want to
 * turn it into a typed value, do that yourself with kotlinx.serialization or
 * your favourite JSON library.
 */
class Json(val text: String) {
    override fun toString(): String = text

    override fun equals(other: Any?): Boolean = other is Json && other.text == text

    override fun hashCode(): Int = text.hashCode()
}