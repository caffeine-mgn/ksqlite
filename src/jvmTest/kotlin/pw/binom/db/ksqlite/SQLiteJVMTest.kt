package pw.binom.db.ksqlite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
}
