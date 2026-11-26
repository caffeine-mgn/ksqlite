package pw.binom.db.ksqlite.pool

import kotlinx.coroutines.delay
import pw.binom.db.SqlException
import pw.binom.db.pool.ConnectionPool
import pw.binom.db.pool.ConnectionPoolImpl
import pw.binom.db.pool.PoolConfig
import pw.binom.db.pool.PooledConnection
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Пул SQLite.
 *
 * ## Почему одно соединение
 *
 * SQLite — встраиваемая библиотека, а не сервер. Запись сериализуется
 * блокировкой файла, поэтому N соединений не дают N параллельных писателей:
 * они встают в очередь на блокировке. Пул на одно соединение честнее — он не
 * создаёт иллюзию параллелизма и не тратит память на лишние страничные кэши.
 *
 * ## Почему поверх `ConnectionPoolImpl`
 *
 * Общая механика — потолок, очередь, ожидание, `pingQuery`, уборка по
 * времени — уже написана и протестирована в `kdatabase`. Своя реализация
 * значила бы повторить её с новыми ошибками. Здесь добавлена только специфика
 * SQLite: ретрай на `SQLITE_BUSY`, которого у Postgres не бывает, и фабрика,
 * настраивающая файл при открытии ([SQLiteConnectionFactory]).
 */
class SQLitePool(
    private val config: SQLitePoolConfig,
) : ConnectionPool {

    /**
     * `maxConnections = 1` — не настройка, а следствие природы SQLite.
     *
     * Второе соединение к тому же файлу не даст параллельной записи, зато
     * удвоит страничный кэш и добавит класс ошибок `SQLITE_BUSY` между
     * своими же соединениями.
     */
    private val delegate = ConnectionPoolImpl(
        config = PoolConfig(
            maxConnections = 1,
            acquireTimeout = config.acquireTimeout,
            // Для файла соединение дешёвое, но переоткрывать его между
            // запросами всё равно незачем: WAL-файлы, PRAGMA заново.
            idleTimeout = 30.minutes,
            maxLifetime = 60.minutes * 24,
            pingQuery = "SELECT 1",
        ),
        factory = SQLiteConnectionFactory(config),
    )

    override val maxConnections: Int get() = delegate.maxConnections
    override val connectionCount: Int get() = delegate.connectionCount
    override val idleCount: Int get() = delegate.idleCount
    override val waitingCount: Int get() = delegate.waitingCount
    override val totalCreated: Long get() = delegate.totalCreated
    override val timeouts: Long get() = delegate.timeouts

    override suspend fun acquire(): PooledConnection =
        retry("получить соединение") { delegate.acquire() }

    override suspend fun <T> withConnection(block: suspend (PooledConnection) -> T): T =
        retry("выполнить запрос") { delegate.withConnection(block) }

    override suspend fun cleanUp(): Int = delegate.cleanUp()

    override fun close() = delegate.close()

    /**
     * Повторяет блок при `SQLITE_BUSY`.
     *
     * Повторяется **всё**: если транзакция сорвалась на блокировке, её
     * состояние неизвестно, и продолжать с середины нельзя.
     *
     * `SQLITE_BUSY_SNAPSHOT` — случай, который `busy_timeout` не покрывает:
     * наша транзакция начала читать, сосед успел записать, наша запись
     * обречена. Ждать бессмысленно, нужно начинать заново — что и делает
     * повтор.
     */
    private suspend fun <T> retry(what: String, block: suspend () -> T): T {
        var lastError: Throwable? = null
        var delayMs = config.retryBaseDelay.inWholeMilliseconds

        repeat(config.maxRetries + 1) { attempt ->
            try {
                return block()
            } catch (e: Throwable) {
                if (!isBusy(e)) throw e
                lastError = e
                if (attempt < config.maxRetries) {
                    delay(delayMs)
                    // Экспоненциальная пауза с потолком: при долгой чужой
                    // транзакции долгие повторы жгут только CPU.
                    delayMs = (delayMs * 2).coerceAtMost(config.retryMaxDelay.inWholeMilliseconds)
                }
            }
        }

        throw SqlException(
            "SQLite занят, не удалось $what после ${config.maxRetries} повторов: ${lastError?.message}",
            cause = lastError,
        )
    }

    /**
     * Признак «база занята».
     *
     * По тексту, а не по коду: драйвер `ksqlite` не отдаёт `SQLITE_BUSY`
     * отдельным типом, а текст стабилен — он приходит из самой SQLite.
     */
    private fun isBusy(e: Throwable): Boolean {
        val m = e.message ?: return false
        return m.contains("database is locked", ignoreCase = true) ||
            m.contains("database table is locked", ignoreCase = true) ||
            m.contains("SQLITE_BUSY", ignoreCase = true)
    }
}

/**
 * Настройки пула SQLite.
 *
 * `path` — путь к файлу либо `:memory:`. Для in-memory базы пул бессмысленен
 * в общем случае: каждое соединение получил бы свою пустую базу. Здесь это
 * безопасно ровно потому, что соединение одно.
 */
data class SQLitePoolConfig(
    val path: String,
    val readOnly: Boolean = false,
    val acquireTimeout: Duration = 10.seconds,
    /** `PRAGMA busy_timeout`, миллисекунды. Ноль — pragma не выставлять. */
    val busyTimeoutMs: Int = 5_000,
    /** Сколько раз повторять при `SQLITE_BUSY`. */
    val maxRetries: Int = 5,
    val retryBaseDelay: Duration = 20.milliseconds,
    val retryMaxDelay: Duration = 500.milliseconds,
)
