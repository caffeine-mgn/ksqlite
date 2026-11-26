package pw.binom.db.ksqlite.pool

import pw.binom.db.ColumnType
import pw.binom.db.Connection
import pw.binom.db.DatabaseEngine
import pw.binom.db.DatabaseInfo
import pw.binom.db.PreparedStatement
import pw.binom.db.ResultSet
import pw.binom.db.SqlValue
import pw.binom.db.TransactionMode
import pw.binom.db.ksqlite.SQLiteConnection
import pw.binom.db.ksqlite.SQLitePreparedStatement
import pw.binom.db.ksqlite.SQLiteResultSet
import pw.binom.db.ksqlite.SqlType
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Сведения о соединении для ORM.
 *
 * SQLite сохраняет регистр идентификатора как написан, поэтому кавычки нужны
 * те же, что в Postgres — двойные. Разница в другом: Postgres приводит
 * незакавыченное имя к нижнему регистру, SQLite оставляет как есть.
 */
internal object SQLiteInfo : DatabaseInfo {
    override val engine: DatabaseEngine = DatabaseEngine.SQLITE
    override val tableNameQuotesStart: String = "\""
    override val tableNameQuotesEnd: String = "\""
}

/**
 * SQLite за контрактом `kdatabase`.
 *
 * ## Настройки, без которых всё разваливается
 *
 * При открытии выполняется [configure]:
 * - `journal_mode = WAL` — читатели не блокируют писателя;
 * - `busy_timeout` — вместо мгновенного `SQLITE_BUSY` ждать освобождения;
 * - `foreign_keys = ON` — SQLite по умолчанию их **не проверяет**.
 *
 * Последнее особенно коварно: схема с внешними ключами создаётся, выглядит
 * правильно, а нарушения не ловятся. Ошибка обнаруживается через месяцы.
 */
internal class SQLiteConnectionAdapter(
    private val db: SQLiteConnection,
    private val ownsHandle: Boolean = true,
    busyTimeoutMillis: Int = 5_000,
) : Connection {

    private var closed = false
    private var inTransaction = false
    private var savepointDepth = 0

    override val dbInfo: DatabaseInfo = SQLiteInfo

    override val isClosed: Boolean get() = closed

    /**
     * Готовность к запросу.
     *
     * У Postgres это состояние протокола; у SQLite соединение всегда готово,
     * пока не закрыто. Транзакция может быть открыта — это не мешает.
     */
    override val isReadyForQuery: Boolean get() = !closed

    /**
     * Открыта ли транзакция прямо сейчас.
     *
     * Проверяется пулом при возврате соединения: соединение с незакрытой
     * транзакцией в набор не идёт, иначе следующий клиент унаследует чужое
     * состояние.
     */
    override val isInTransaction: Boolean get() = inTransaction

    /**
     * Уровень изоляции текущей транзакции.
     *
     * У SQLite нет уровней: писатель всегда сериализуется блокировкой файла.
     * Внутри транзакции честно сообщаем самый строгий, вне — READ_COMMITTED
     * как «нет активной транзакции».
     */
    override val transactionMode: TransactionMode
        get() = if (inTransaction) TransactionMode.SERIALIZABLE else TransactionMode.READ_COMMITTED

    private val busyTimeoutMillis = busyTimeoutMillis.coerceAtLeast(0)

    init {
        configure()
    }

    /**
     * Настройки, которые обязаны быть выставлены до первого запроса.
     *
     * `WAL` пишется в файл навсегда — повторный вызов безвреден. `busy_timeout`
     * и `foreign_keys` живут в пределах соединения, поэтому выставляются
     * каждый раз.
     */
    private fun configure() {
        // busy_timeout по умолчанию: запись обычно занимает миллисекунды, но
        // при массовой вставке в одной транзакции сосед может ждать долго.
        // Мгновенный отказ хуже ожидания.
        if (busyTimeoutMillis > 0) {
            db.setBusyTimeout(busyTimeoutMillis)
        }
        db.exec("PRAGMA journal_mode = WAL")
        db.exec("PRAGMA foreign_keys = ON")
        // NORMAL вместо FULL: при WAL это безопасно (потеря максимум
        // последней транзакции при отключении питания), а запись ускоряется
        // на порядок.
        db.exec("PRAGMA synchronous = NORMAL")
    }

    private fun checkOpen() {
        if (closed) throw IllegalStateException("соединение закрыто")
    }

    override suspend fun prepare(sql: String): PreparedStatement {
        checkOpen()
        return SQLitePreparedStatementAdapter(db, db.prepare(sql), this)
    }

    override suspend fun executeUpdate(sql: String): Long {
        checkOpen()
        // exec умеет несколько операторов через точку с запятой — это нужно
        // миграциям.
        return db.exec(sql).toLong()
    }

    override suspend fun query(sql: String): ResultSet {
        checkOpen()
        val stmt = db.prepare(sql)
        return SQLiteResultSetAdapter(stmt.executeQuery(), stmt)
    }

    override suspend fun beginTransaction() {
        checkOpen()
        if (inTransaction) {
            // Вложенность через SAVEPOINT: SQLite не поддерживает вложенные
            // BEGIN, но kdatabase допускает повторный вход.
            db.exec("SAVEPOINT sp_$savepointDepth")
            savepointDepth++
        } else {
            // IMMEDIATE, а не обычный BEGIN: сразу берём блокировку записи.
            // С DEFERRED транзакция начинается как читающая, и при попытке
            // записи SQLite возвращает SQLITE_BUSY, который нельзя переждать —
            // нужно начинать заново. IMMEDIATE убирает этот класс ошибок.
            db.exec("BEGIN IMMEDIATE")
            inTransaction = true
        }
    }

    override suspend fun commit() {
        checkOpen()
        if (savepointDepth > 0) {
            savepointDepth--
            db.exec("RELEASE sp_$savepointDepth")
        } else {
            db.exec("COMMIT")
            inTransaction = false
        }
    }

    override suspend fun rollback() {
        checkOpen()
        if (savepointDepth > 0) {
            savepointDepth--
            db.exec("ROLLBACK TO sp_$savepointDepth")
            db.exec("RELEASE sp_$savepointDepth")
        } else if (inTransaction) {
            db.exec("ROLLBACK")
            inTransaction = false
        }
    }

    override suspend fun setTransactionMode(mode: TransactionMode) {
        // SQLite не умеет менять уровень изоляции: он всегда сериализуемый для
        // писателей. Молча игнорируем — вызывающий получает более строгую
        // изоляцию, чем просил, а не более слабую.
    }

    override fun close() {
        if (closed) return
        closed = true
        if (ownsHandle) db.close()
    }
}

/**
 * SQLite за `PreparedStatement`.
 *
 * ## Отличие от Postgres
 *
 * В SQLite параметры позиционные (`?`), а наш ORM генерирует именованные
 * (`:name`). Переписывание делает диалект на уровне SQL, а значения
 * подставляются по порядку.
 */
internal class SQLitePreparedStatementAdapter(
    private val db: SQLiteConnection,
    private val stmt: SQLitePreparedStatement,
    override val connection: Connection,
) : PreparedStatement {

    override suspend fun reset() {
        stmt.reset()
        stmt.clearBindings()
    }

    override suspend fun bind(index: Int, value: SqlValue) {
        // Контракт kdatabase нумерует параметры с 0, SQLite — с 1.
        // Переводятся здесь, а не в вызывающем коде: иначе каждый драйвер
        // начинал бы счёт заново, и «первый параметр» значил бы разное в
        // разных местах.
        bindOne(index + 1, value)
    }

    override suspend fun executeUpdate(): Long = stmt.executeUpdate().toLong()

    override suspend fun executeQuery(): ResultSet =
        SQLiteResultSetAdapter(stmt.executeQuery(), stmt)

    override fun close() {
        stmt.close()
    }

    private fun bindOne(index: Int, value: SqlValue) {
        when (value) {
            is SqlValue.Null -> stmt.bindNull(index)
            is SqlValue.Text -> stmt.bindText(index, value.value)
            is SqlValue.Bool -> stmt.bindLong(index, if (value.value) 1L else 0L)
            is SqlValue.Int8 -> stmt.bindLong(index, value.value.toLong())
            is SqlValue.Int16 -> stmt.bindLong(index, value.value.toLong())
            is SqlValue.Int32 -> stmt.bindLong(index, value.value.toLong())
            is SqlValue.Int64 -> stmt.bindLong(index, value.value)
            is SqlValue.Float32 -> stmt.bindDouble(index, value.value.toDouble())
            is SqlValue.Float64 -> stmt.bindDouble(index, value.value)
            is SqlValue.Bytes -> stmt.bindBlob(index, value.value)
            is SqlValue.Time -> stmt.bindLong(index, value.value.toEpochMilliseconds())
            is SqlValue.UuidValue -> stmt.bindText(index, value.value.toString())
        }
    }
}

/**
 * SQLite за `ResultSet`.
 *
 * `sqlite3_step` вызывается на каждой строке, поэтому память держит одну
 * строку — порционность здесь не нужна, она уже внутри движка. Поэтому
 * `next()` синхронный по существу, но объявлен `suspend` из-за контракта.
 */
internal class SQLiteResultSetAdapter(
    private val rs: SQLiteResultSet,
    private val stmt: SQLitePreparedStatement?,
) : ResultSet {

    override val columns: List<String> = (0 until rs.columnCount).map { rs.columnName(it) }

    override suspend fun next(): Boolean = rs.next()

    override fun columnType(index: Int): ColumnType = when (rs.columnType(index)) {
        SqlType.INTEGER -> ColumnType.BIGINT
        SqlType.REAL -> ColumnType.DOUBLE
        SqlType.TEXT -> ColumnType.VARCHAR
        SqlType.BLOB -> ColumnType.VARBINARY
        SqlType.NULL -> ColumnType.OTHER
    }

    override fun isNull(index: Int): Boolean = rs.isNull(index)

    override fun getString(index: Int): String? = rs.getText(index)
    override fun getBoolean(index: Int): Boolean? = rs.getLong(index)?.let { it != 0L }
    override fun getByte(index: Int): Byte? = rs.getLong(index)?.toByte()
    override fun getShort(index: Int): Short? = rs.getLong(index)?.toShort()
    override fun getInt(index: Int): Int? = rs.getLong(index)?.toInt()
    override fun getLong(index: Int): Long? = rs.getLong(index)
    override fun getDouble(index: Int): Double? = rs.getDouble(index)
    override fun getBytes(index: Int): ByteArray? = rs.getBlob(index)

    override fun getInstant(index: Int): Instant? =
        rs.getLong(index)?.let { Instant.fromEpochMilliseconds(it) }

    @OptIn(ExperimentalUuidApi::class)
    override fun getUuid(index: Int): Uuid? = rs.getText(index)?.let { Uuid.parse(it) }

    override fun close() {
        rs.close()
    }
}
