@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package pw.binom.db.ksqlite

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.toCValues
import kotlinx.cinterop.toKString
import platform.posix.fprintf
import platform.posix.stderr

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
@PublishedApi
internal actual fun logError(message: String) {
    fprintf(stderr, "%s\n", message.cstr)
}