package pw.binom.db.ksqlite.pool

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.remove

@OptIn(ExperimentalForeignApi::class)
actual fun removeFile(path: String) {
    remove(path)
}
