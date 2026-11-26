package pw.binom.db.ksqlite.pool

import java.io.File

actual fun removeFile(path: String) {
    File(path).delete()
}
