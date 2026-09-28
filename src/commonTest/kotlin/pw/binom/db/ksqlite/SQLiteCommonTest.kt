package pw.binom.db.ksqlite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests that exercise public API behaviour identically across every KMP target.
 *
 * Lives in `commonTest` so each test runs once per locally-runnable target
 * (`jvm`, `linuxX64`, ...). The Kotlin code under test is an `expect`/`actual`
 * split, so an assertion that holds here on one target must hold on all of
 * them — the same source file is compiled to every host we ship binaries for.
 *
 * Anything that touches platform internals (`NativeLoader`, the jar layout,
 * Android bionic linkage, ...) lives in [SQLiteJVMTest] instead because it
 * only makes sense on the JVM target.
 */
class SQLiteCommonTest {

    /* ---- open() failure modes ------------------------------------ */

    @Test
    fun `open with non-existent parent directory throws SQLiteException`() {
        // Regression for the JVM-native `open()` bug: when sqlite3_open_v2 fails
        // with a non-zero error code (e.g. SQLITE_CANTOPEN = 14 for a missing
        // parent directory), the JNI shim used to return the SQLite error code
        // itself as the connection handle. That handle was non-zero, so the
        // Kotlin-side `handle == 0L` guard let it through and the next method
        // call dereferenced the bogus pointer — segfault inside
        // sqlite3_total_changes+0x4, taking the whole JVM down.
        //
        // On the Kotlin/Native side the open() actual already throws, so this
        // test passes there too — putting it in commonTest guarantees we keep
        // that contract and locks in the JVM regression at the same time.
        // The static path is fine — sqlite3_open_v2 only needs the parent
        // directory to not exist; the leaf file name is never created.
        assertFails {
            SQLiteConnection.open("/ksqlite-no-such-parent-dir/foo.db")
        }
    }

    @Test
    fun `open with path that is actually a directory throws SQLiteException`() {
        // Same regression coverage as above but for the "path resolves to an
        // existing directory" failure mode. /tmp is universally writable and
        // almost certainly present on every CI host; sqlite3_open_v2 will
        // refuse to open it as a database file with SQLITE_CANTOPEN.
        assertFails {
            SQLiteConnection.open("/tmp")
        }
    }

    /* ---- happy path: schema, DML, DDL, prepared statements -------- */

    @Test
    fun `open in-memory exec verify rows`() {
        val conn = SQLiteConnection.memory()
        try {
            val changes = conn.exec(
                """
                CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT, score REAL);
                INSERT INTO t (name, score) VALUES ('alice', 1.5), ('bob', 2.5);
                """.trimIndent(),
            )
            assertEquals(2, changes, "INSERT count should match")

            conn.prepare("SELECT count(*) FROM t").use { stmt ->
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(2L, rs.getLong(0))
                    assertTrue(!rs.next())
                }
            }
        } finally {
            conn.close()
        }
    }

    @Test
    fun `lastInsertRowId after single insert`() {
        val conn = SQLiteConnection.memory()
        try {
            conn.exec("CREATE TABLE seq (v INTEGER)")
            conn.prepare("INSERT INTO seq (v) VALUES (?)").use { p ->
                p.bindLong(1, 100L)
                p.bindText(1, "ignored")
                p.bindLong(1, 42L)
                p.executeUpdate()
                assertEquals(1L, conn.lastInsertRowId)
            }
            conn.prepare("INSERT INTO seq (v) VALUES (?)").use { p ->
                p.bindLong(1, 7L)
                p.executeUpdate()
                assertEquals(2L, conn.lastInsertRowId)
            }
        } finally {
            conn.close()
        }
    }

    @Test
    fun `transactions commit and rollback`() {
        val conn = SQLiteConnection.memory()
        try {
            conn.exec("CREATE TABLE t (v INTEGER)")
            conn.beginTransaction()
            conn.prepare("INSERT INTO t (v) VALUES (1)").use { it.executeUpdate() }
            conn.commit()

            conn.beginTransaction()
            conn.prepare("INSERT INTO t (v) VALUES (2)").use { it.executeUpdate() }
            conn.rollback()

            conn.prepare("SELECT count(*) FROM t").use { stmt ->
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1L, rs.getLong(0))
                }
            }
        } finally {
            conn.close()
        }
    }

    @Test
    fun `text and blob round-trip`() {
        // NOTE: TEXT round-trip with an *embedded NUL* (`"\u0000"`) is JVM-only.
        // The JNI bind/getText path uses Modified UTF-8 (`0xC0 0x80` encodes
        // NUL), which round-trips a NUL byte inside the string; the K/N path
        // uses standard UTF-8 and stops at the first real NUL via toKString.
        // That mismatch is documented at the JNI/getText call sites, so this
        // test only exercises the part that is genuinely common — blob round
        // trip with embedded NUL bytes inside the byte array.
        val conn = SQLiteConnection.memory()
        try {
            conn.exec("CREATE TABLE b (s TEXT, x BLOB)")
            val data = byteArrayOf(0x01, 0x02, 0x03, 0x00, 0x05)
            conn.prepare("INSERT INTO b (s, x) VALUES (?, ?)").use { p ->
                p.bindText(1, "hello world")
                p.bindBlob(2, data)
                p.executeUpdate()
            }
            conn.prepare("SELECT s, x FROM b").use { stmt ->
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals("hello world", rs.getText(0))
                    assertNotNull(rs.getBlob(1))
                    assertTrue(rs.getBlob(1)!!.contentEquals(data))
                }
            }
        } finally {
            conn.close()
        }
    }

    /* ---- sqlite-vec: vec0 + KNN ------------------------------------ */

    @Test
    fun `sqlite-vec auto-loaded vec0 table usable`() {
        val conn = SQLiteConnection.memory()
        try {
            // vec0 should be available without manual extension loading,
            // courtesy of ksqlite_init registering sqlite3_auto_extension
            // at process start (JNI_OnLoad on JVM, SQLiteNative.init on K/N).
            conn.exec(
                """
                CREATE VIRTUAL TABLE v USING vec0(
                  embedding float[3]
                )
                """.trimIndent(),
            )

            val q = floatArrayOf(1.0f, 0.0f, 0.0f)
            conn.prepare(
                "INSERT INTO v (rowid, embedding) VALUES (?, ?)",
            ).use { p ->
                p.bindLong(1, 1L)
                p.bindVector(2, q)
                p.executeUpdate()
            }

            conn.prepare(
                """
                SELECT rowid, vec_distance_cosine(embedding, ?) AS d
                FROM v
                ORDER BY d ASC
                LIMIT 5
                """.trimIndent(),
            ).use { stmt ->
                stmt.bindVector(1, q)
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1L, rs.getLong(0))
                    val d = rs.getDouble(1)
                    assertNotNull(d)
                    // Same vector → cosine distance ≈ 0
                    if (d >= 0.001) error("expected ~0, got $d")
                }
            }
        } finally {
            conn.close()
        }
    }

    /* ---- JSON1 ---------------------------------------------------- */

    @Test
    fun `JSON1 functions and Json accessor round-trip`() {
        val conn = SQLiteConnection.memory()
        try {
            // JSON1 is enabled at compile time (SQLITE_ENABLE_JSON1), so
            // every TEXT column can act as a JSON document and the
            // json_* SQL functions are available.
            conn.exec(
                """
                CREATE TABLE docs (id INTEGER PRIMARY KEY, payload TEXT);
                INSERT INTO docs (payload) VALUES
                  ('{"name":"alice","tags":["a","b"]}'),
                  ('{"name":"bob","age":30}');
                """.trimIndent(),
            )

            conn.prepare(
                """
                SELECT id,
                       json_extract(payload, '$.name') AS name,
                       payload
                FROM docs
                ORDER BY id
                """.trimIndent(),
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals("alice", rs.getText(1))
                    val raw = rs.getJson(2)
                    assertNotNull(raw)
                    assertEquals(
                        "{\"name\":\"alice\",\"tags\":[\"a\",\"b\"]}",
                        raw.text,
                    )

                    assertTrue(rs.next())
                    assertEquals("bob", rs.getText(1))
                }
            }

            // json_array / json_object can be bound back as TEXT.
            conn.prepare(
                """
                INSERT INTO docs (payload)
                VALUES (json_object('k', json_array(1, 2, 3)))
                """.trimIndent(),
            ).use { it.executeUpdate() }

            conn.prepare("SELECT payload FROM docs WHERE id = 3").use { stmt ->
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(
                        """{"k":[1,2,3]}""",
                        rs.getJson(0)?.text,
                    )
                }
            }
        } finally {
            conn.close()
        }
    }
}
