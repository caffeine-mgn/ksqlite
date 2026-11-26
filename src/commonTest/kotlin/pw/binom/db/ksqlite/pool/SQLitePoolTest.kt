package pw.binom.db.ksqlite.pool

import kotlinx.coroutines.test.runTest
import pw.binom.db.SqlValue
import pw.binom.db.transaction
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Адаптер SQLite на живом файле.
 *
 * ## Почему не на фейке
 *
 * Здесь проверяется не наша логика, а связка с настоящим движком: PRAGMA,
 * позиционные параметры, типы, транзакции. Фейк подтвердил бы только то, что
 * мы сами придумали.
 *
 * Файл во временной папке, удаляется после каждого теста: иначе тесты
 * начнут влиять друг на друга через оставшиеся таблицы.
 */
class SQLitePoolTest {

    private val tempFiles = mutableListOf<String>()

    private fun newPath(): String {
        val p = "${'k'}sqlite_test_${tempFiles.size}_${counter++}.db"
        tempFiles += p
        return p
    }

    private var counter = 0

    @AfterTest
    fun cleanup() {
        // Файлы в /tmp; удаляем и сам файл, и WAL-спутники, иначе следующий
        // прогон подхватит старые данные.
        for (p in tempFiles) {
            removeIfExists("/tmp/$p")
            removeIfExists("/tmp/$p-wal")
            removeIfExists("/tmp/$p-shm")
        }
        tempFiles.clear()
    }

    private fun removeIfExists(path: String) {
        runCatching { removeFile(path) }
    }

    @Test
    fun `соединение открывается и отвечает на SELECT`() = runTest {
        val conn = SQLiteConnectionAdapter(
            pw.binom.db.ksqlite.SQLiteConnection.open("/tmp/${newPath()}"),
        )
        try {
            conn.query("SELECT 42 AS answer").use { rs ->
                assertTrue(rs.next(), "строка должна быть")
                assertEquals(42, rs.getInt(0))
                assertEquals("answer", rs.columns[0])
            }
        } finally {
            conn.close()
        }
    }

    @Test
    fun `DDL и вставка работают`() = runTest {
        val conn = openTestConnection()
        try {
            conn.executeUpdate("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
            conn.executeUpdate("INSERT INTO t (name) VALUES ('Иван')")

            conn.query("SELECT id, name FROM t").use { rs ->
                assertTrue(rs.next())
                assertEquals(1L, rs.getLong(0))
                assertEquals("Иван", rs.getString(1))
            }
        } finally {
            conn.close()
        }
    }

    @Test
    fun `позиционные параметры биндятся по порядку`() = runTest {
        val conn = openTestConnection()
        try {
            conn.executeUpdate("CREATE TABLE p (a INTEGER, b TEXT)")
            val stmt = conn.prepare("INSERT INTO p (a, b) VALUES (?, ?)")
            stmt.use {
                it.bindAll(listOf(SqlValue.Int32(7), SqlValue.Text("семь")))
                it.executeUpdate()
            }

            conn.query("SELECT a, b FROM p").use { rs ->
                assertTrue(rs.next())
                assertEquals(7, rs.getInt(0))
                assertEquals("семь", rs.getString(1))
            }
        } finally {
            conn.close()
        }
    }

    @Test
    fun `NULL и пустая строка различаются`() = runTest {
        val conn = openTestConnection()
        try {
            conn.executeUpdate("CREATE TABLE n (x TEXT)")
            conn.executeUpdate("INSERT INTO n VALUES (NULL), ('')")

            conn.query("SELECT x FROM n ORDER BY rowid").use { rs ->
                assertTrue(rs.next())
                assertTrue(rs.isNull(0), "первое значение — NULL")

                assertTrue(rs.next())
                assertTrue(!rs.isNull(0), "второе — пустая строка, не NULL")
                assertEquals("", rs.getString(0))
            }
        } finally {
            conn.close()
        }
    }

    @Test
    fun `boolean хранится как 0 и 1`() = runTest {
        val conn = openTestConnection()
        try {
            conn.executeUpdate("CREATE TABLE b (flag INTEGER)")
            val stmt = conn.prepare("INSERT INTO b (flag) VALUES (?)")
            stmt.use {
                it.bindAll(listOf(SqlValue.Bool(true)))
                it.executeUpdate()
                it.reset()
                it.bindAll(listOf(SqlValue.Bool(false)))
                it.executeUpdate()
            }

            conn.query("SELECT flag FROM b ORDER BY rowid").use { rs ->
                assertTrue(rs.next())
                assertEquals(true, rs.getBoolean(0))
                assertTrue(rs.next())
                assertEquals(false, rs.getBoolean(0))
            }
        } finally {
            conn.close()
        }
    }

    @Test
    fun `транзакция коммитится`() = runTest {
        val path = newPath()
        val conn = SQLiteConnectionAdapter(pw.binom.db.ksqlite.SQLiteConnection.open("/tmp/$path"))
        try {
            conn.executeUpdate("CREATE TABLE tx (v INTEGER)")
            conn.transaction { tx ->
                tx.executeUpdate("INSERT INTO tx VALUES (1)")
                tx.executeUpdate("INSERT INTO tx VALUES (2)")
            }
        } finally {
            conn.close()
        }

        // Новое соединение видит данные: значит коммит реально записан.
        val conn2 = SQLiteConnectionAdapter(pw.binom.db.ksqlite.SQLiteConnection.open("/tmp/$path"))
        try {
            conn2.query("SELECT count(*) FROM tx").use { rs ->
                assertTrue(rs.next())
                assertEquals(2L, rs.getLong(0))
            }
        } finally {
            conn2.close()
        }
    }

    @Test
    fun `откат при исключении не оставляет данных`() = runTest {
        val conn = openTestConnection()
        try {
            conn.executeUpdate("CREATE TABLE rb (v INTEGER)")

            assertFailsWith<IllegalStateException> {
                conn.transaction { tx ->
                    tx.executeUpdate("INSERT INTO rb VALUES (1)")
                    error("сорвалось")
                }
            }

            conn.query("SELECT count(*) FROM rb").use { rs ->
                assertTrue(rs.next())
                assertEquals(0L, rs.getLong(0), "откат должен убрать вставку")
            }
        } finally {
            conn.close()
        }
    }

    @Test
    fun `вложенная транзакция идёт через SAVEPOINT`() = runTest {
        val conn = openTestConnection()
        try {
            conn.executeUpdate("CREATE TABLE sp (v INTEGER)")

            conn.transaction { tx ->
                tx.executeUpdate("INSERT INTO sp VALUES (1)")
                // Внутренний блок откатывается, внешний — нет.
                runCatching {
                    tx.transaction { inner ->
                        inner.executeUpdate("INSERT INTO sp VALUES (2)")
                        error("внутренний откат")
                    }
                }
            }

            conn.query("SELECT v FROM sp ORDER BY v").use { rs ->
                assertTrue(rs.next())
                assertEquals(1, rs.getInt(0))
                assertTrue(!rs.next(), "вторая вставка должна быть откачена")
            }
        } finally {
            conn.close()
        }
    }

    @Test
    fun `WAL включён`() = runTest {
        val conn = openTestConnection()
        try {
            conn.query("PRAGMA journal_mode").use { rs ->
                assertTrue(rs.next())
                assertEquals("wal", rs.getString(0)?.lowercase())
            }
        } finally {
            conn.close()
        }
    }

    @Test
    fun `внешние ключи включены`() = runTest {
        val conn = openTestConnection()
        try {
            // По умолчанию SQLite их НЕ проверяет — это самая тихая из
            // возможных поломок, поэтому проверяем явно.
            conn.query("PRAGMA foreign_keys").use { rs ->
                assertTrue(rs.next())
                assertEquals(1, rs.getInt(0))
            }
        } finally {
            conn.close()
        }
    }

    @Test
    fun `нарушение уникальности сообщает об ошибке`() = runTest {
        val conn = openTestConnection()
        try {
            conn.executeUpdate("CREATE TABLE u (id INTEGER PRIMARY KEY)")
            conn.executeUpdate("INSERT INTO u VALUES (1)")

            val failed = runCatching {
                conn.executeUpdate("INSERT INTO u VALUES (1)")
            }.isFailure
            assertTrue(failed, "повтор ключа должен падать, а не молчать")
        } finally {
            conn.close()
        }
    }

    @Test
    fun `соединение после close не работает`() = runTest {
        val conn = openTestConnection()
        conn.close()

        val failed = runCatching { conn.query("SELECT 1") }.isFailure
        assertTrue(failed, "закрытое соединение должно отказывать")
    }

    private suspend fun openTestConnection(): SQLiteConnectionAdapter =
        SQLiteConnectionAdapter(pw.binom.db.ksqlite.SQLiteConnection.open("/tmp/${newPath()}"))
}

/** Удаление файла. В common-коде файловой системы нет, поэтому per-platform. */
internal expect fun removeFile(path: String)
