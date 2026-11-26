package pw.binom.db.ksqlite.pool

import pw.binom.db.Connection
import pw.binom.db.ksqlite.SQLiteConnection

/**
 * Публичная точка входа к SQLite.
 *
 * Адаптер внутренний: снаружи нужен контракт [Connection], а не конкретный
 * класс. Иначе приложение начнёт зависеть от имени адаптера, и заменить его
 * будет нельзя.
 *
 * ## Когда открывать напрямую, а когда через пул
 *
 * Пул нужен там, где важна единая точка владения и ретрай на `SQLITE_BUSY`
 * (сервис, несколько корутин). Прямое соединение — для миграций и скриптов:
 * один прогон, одна транзакция, пул только добавит слой.
 *
 * Файл в обоих случаях настраивается одинаково (`WAL`, `busy_timeout`,
 * `foreign_keys`) — настройка живёт в адаптере.
 */
suspend fun openSQLiteConnection(
    path: String,
    readOnly: Boolean = false,
    busyTimeoutMs: Int = 5_000,
): Connection = SQLiteConnectionAdapter(
    db = SQLiteConnection.open(path = path, readOnly = readOnly),
    busyTimeoutMillis = busyTimeoutMs,
)

/** То же с автозакрытием. */
suspend fun <R> withSQLiteConnection(
    path: String,
    readOnly: Boolean = false,
    busyTimeoutMs: Int = 5_000,
    block: suspend (Connection) -> R,
): R {
    val c = openSQLiteConnection(path, readOnly, busyTimeoutMs)
    try {
        return block(c)
    } finally {
        c.close()
    }
}
