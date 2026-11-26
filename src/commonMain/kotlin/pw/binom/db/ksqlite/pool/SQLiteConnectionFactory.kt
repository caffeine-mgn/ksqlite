package pw.binom.db.ksqlite.pool

import pw.binom.db.Connection
import pw.binom.db.ksqlite.SQLiteConnection
import pw.binom.db.pool.ConnectionFactory

/**
 * Фабрика соединений SQLite для [pw.binom.db.pool.ConnectionPoolImpl].
 *
 * ## Зачем отдельный класс, а не лямбда
 *
 * `ConnectionPoolImpl` принимает и голо лямбду `suspend () -> Connection`, но
 * здесь есть что сказать пулу, кроме «открой»: файл SQLite настраивается при
 * каждом открытии, и это решение фабрики, а не вызывающего. Через
 * [ConnectionFactory] оно живёт рядом с открытием и не разъезжается с тем, как
 * пул уничтожает соединение.
 *
 * ## Что выставляется при открытии
 *
 * Всё это делает [SQLiteConnectionAdapter] в своём `configure`, а фабрика лишь
 * передаёт параметры:
 *
 * * `busy_timeout` — вместо мгновенного `SQLITE_BUSY` подождать освобождения
 *   блокировки;
 * * `journal_mode = WAL` — читатели не блокируют писателя;
 * * `foreign_keys = ON` — SQLite по умолчанию внешние ключи **не проверяет**,
 *   и без этого pragma схема с FK выглядит живой, а нарушения не ловятся
 *   месяцами.
 *
 * Прагма `foreign_keys` действует в пределах соединения, поэтому обязана
 * повторяться на каждом [open], а не один раз при создании файла.
 */
internal class SQLiteConnectionFactory(
    private val config: SQLitePoolConfig,
) : ConnectionFactory {

    override suspend fun open(): Connection = SQLiteConnectionAdapter(
        db = SQLiteConnection.open(path = config.path, readOnly = config.readOnly),
        busyTimeoutMillis = config.busyTimeoutMs,
    )
}
