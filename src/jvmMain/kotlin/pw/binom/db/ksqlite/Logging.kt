package pw.binom.db.ksqlite

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
@PublishedApi
internal actual fun logError(message: String) {
    System.err.println(message)
}