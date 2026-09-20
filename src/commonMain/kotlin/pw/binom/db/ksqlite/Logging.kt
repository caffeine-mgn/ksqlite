package pw.binom.db.ksqlite

/**
 * Process-wide error logger used by the common code path when something
 * non-fatal goes wrong (e.g. rollback after an exception). The JVM
 * implementation routes to `System.err`; the Kotlin/Native one routes to
 * libc's stderr so the message ends up in the same stream the rest of
 * the application's native output uses.
 */
@PublishedApi
internal expect fun logError(message: String)