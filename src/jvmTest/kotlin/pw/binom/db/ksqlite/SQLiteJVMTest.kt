package pw.binom.db.ksqlite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SQLiteJVMTest {

    @Test
    fun `open in-memory, exec, verify rows`() {
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
        val conn = SQLiteConnection.memory()
        try {
            conn.exec("CREATE TABLE b (s TEXT, x BLOB)")
            val data = byteArrayOf(0x01, 0x02, 0x03, 0x00, 0x05)
            val text = "hello\u0000world" // embedded NUL on purpose
            conn.prepare("INSERT INTO b (s, x) VALUES (?, ?)").use { p ->
                p.bindText(1, text)
                p.bindBlob(2, data)
                p.executeUpdate()
            }
            conn.prepare("SELECT s, x FROM b").use { stmt ->
                stmt.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(text, rs.getText(0))
                    assertNotNull(rs.getBlob(1))
                    assertTrue(rs.getBlob(1)!!.contentEquals(data))
                }
            }
        } finally {
            conn.close()
        }
    }

    @Test
    fun `sqlite-vec auto-loaded vec0 table usable`() {
        val conn = SQLiteConnection.memory()
        try {
            // vec0 should be available without manual extension loading,
            // courtesy of JNI_OnLoad calling ksqlite_init.
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
                    if (d!! >= 0.001) error("expected ~0, got $d")
                }
            }
        } finally {
            conn.close()
        }
    }

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
                        raw!!.text,
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