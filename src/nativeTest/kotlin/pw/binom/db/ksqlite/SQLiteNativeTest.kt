package pw.binom.db.ksqlite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SQLiteNativeTest {

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
    fun `sqlite-vec auto-loaded vec0 table usable`() {
        val conn = SQLiteConnection.memory()
        try {
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
                    if (d >= 0.001) error("expected ~0, got $d")
                }
            }
        } finally {
            conn.close()
        }
    }
}