package pw.binom.db.ksqlite

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Top-level transaction helper.
 *
 * On the happy path it executes [block], commits, and returns its result.
 * If [block] throws, the helper rolls back and re-throws — never leaving
 * a dangling transaction. Any rollback failure is swallowed (the original
 * exception is more informative) but logged to [System.err] so silent
 * data loss does not go unnoticed.
 */
@OptIn(ExperimentalContracts::class)
inline fun <T> SQLiteConnection.transaction(block: (SQLiteConnection) -> T): T {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }

    beginTransaction()
    try {
        val result = block(this)
        commit()
        return result
    } catch (e: Throwable) {
        try {
            rollback()
        } catch (re: Throwable) {
            logError("ksqlite: rollback after exception failed: $re")
        }
        throw e
    }
}