package pw.binom.db.ksqlite

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * JVM-only tests. Cross-platform behaviour (open() failure modes, DDL/DML,
 * transactions, prepared statements, sqlite-vec, JSON1) lives in
 * [SQLiteCommonTest] under `commonTest` so it runs identically on the JVM and
 * every Kotlin/Native target.
 *
 * What stays here is platform-specific to the JVM target:
 *  - the bundled-`.so`-from-jar extraction done by [NativeLoader]
 *  - the host-platform fallback path when the jar has no build for the
 *    current `os.arch`
 *
 * Anything Android-specific is not covered here: Android is delivered as an
 * AAR whose `jniLibs` are loaded through `System.loadLibrary` (see the
 * `androidMain` actual of `loadNativeLibrary`), so that path is exercised on a
 * device/emulator, not by unit tests in this module.
 */
class SQLiteJVMTest {

    @Test
    fun `native lib loads on host JVM`() {
        // On every JVM platform with a bundled .so in /<platform>/libksqlite.so,
        // opening an in-memory connection triggers NativeLoader.load() and forces
        // extraction + SHA-256 verification. If we're on a host platform that the
        // jar has no build for, this test gets skipped (we're still on a
        // supported target otherwise — the Android-only builds aren't exercised
        // here, that's covered by the runtime check on actual devices).
        val conn = SQLiteConnection.memory()
        try {
            conn.exec("CREATE TABLE t (x INTEGER)")
            conn.exec("INSERT INTO t VALUES (42)")
            assertEquals(1L, conn.lastInsertRowId)
        } finally {
            conn.close()
        }
    }

    @Test
    fun `native lib already loaded, load() is a no-op`() {
        // The first call to SQLiteConnection.memory() above (in native lib loads
        // on host JVM) already executed NativeLoader.load(). Calling SQLiteConnection.open
        // with a custom URI should not re-extract and should not throw
        // UnsatisfiedLinkError because `NativeLoader.load` is idempotent.
        val conn = SQLiteConnection.open(":memory:")
        try {
            assertNotNull(conn)
        } finally {
            conn.close()
        }
    }

    /**
     * Regression: закрытие PreparedStatement финализирует `sqlite3_stmt`.
     * Раньше уже выданный ResultSet об этом не знал, и следующий `getLong()`
     * уходил JNI-вызовом на освобождённую память — JVM падала SIGSEGV'ом в
     * `pthread_mutex_lock` (та же природа, что в hs_err_pid*.log).
     */
    @Test
    fun `result set fails cleanly after its statement is closed`() {
        val conn = SQLiteConnection.memory()
        try {
            conn.exec("CREATE TABLE t (x INTEGER)")
            val ps = conn.prepare("SELECT 1 AS x")
            val rs = ps.executeQuery()
            try {
                assertTrue(rs.next())
                assertEquals(1L, rs.getLong(0))
                ps.close()
                assertFailsWith<SQLiteException> { rs.getLong(0) }
                assertFailsWith<SQLiteException> { rs.next() }
            } finally {
                rs.close()
            }
        } finally {
            conn.close()
        }
    }

    /**
     * Regression: закрытие СОЕДИНЕНИЯ во время чтения из ResultSet'а. Раньше
     * `checkOpen()` проходил, а `sqlite3_finalize`/`sqlite3_close` успевал
     * выполниться до нативного вызова — use-after-free и SIGSEGV.
     *
     * Тест не детерминирован по потоку-победителю: если чтение успело — ок,
     * если нет — обязан быть [SQLiteException], но не падение JVM.
     */
    @Test
    fun `concurrent close never crashes the JVM`() {
        repeat(50) {
            val conn = SQLiteConnection.memory()
            conn.exec("CREATE TABLE t (x INTEGER)")
            conn.exec("INSERT INTO t VALUES (1), (2), (3), (4), (5)")
            val ps = conn.prepare("SELECT x FROM t")
            val rs = ps.executeQuery()
            val failure = AtomicReference<Throwable?>()
            val started = CountDownLatch(1)
            val reader = Thread {
                try {
                    started.countDown()
                    var rows = 0
                    while (rs.next()) {
                        rs.getLong(0)
                        rows++
                    }
                } catch (e: SQLiteException) {
                    // ожидаемо: statement/connection закрыли под нами
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }
            reader.start()
            started.await(1, TimeUnit.SECONDS)
            ps.close()
            conn.close()
            reader.join(5_000)
            failure.get()?.let { throw it }
            rs.close()
        }
    }
}
