@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package pw.binom.db.ksqlite

import platform.internal_sqlite.ksqlite_init

/**
 * Process-wide init hook for Kotlin/Native targets.
 *
 * Calls [ksqlite_init], which registers sqlite-vec as a SQLite auto-extension.
 * Every connection opened after this call gets the `vec0` virtual table and
 * all related functions (distance, KNN MATCH, vec_to_json, etc.) without any
 * extra setup on the caller. Safe to call more than once — auto-extension
 * registration is idempotent.
 */
internal object SQLiteNative {
    fun init() {
        val rc = ksqlite_init()
        if (rc != 0) error("ksqlite_init failed with code $rc")
    }
}