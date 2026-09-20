# ksqlite

[![Maven Central](https://img.shields.io/maven-central/v/pw.binom.db/ksqlite?label=Maven%20Central)](https://central.sonatype.com/artifact/pw.binom.db/ksqlite)
[![License: Apache-2.0](https://img.shields.io/github/license/caffeine-mgn/ksqlite)](https://www.apache.org/licenses/LICENSE-2.0.txt)
[![Kotlin](https://img.shields.io/badge/kotlin-2.1.0-blue.svg)](https://kotlinlang.org)
[![SQLite](https://img.shields.io/badge/SQLite-3.53.4-blue.svg)](https://sqlite.org)
[![sqlite-vec](https://img.shields.io/badge/sqlite--vec-0.1.9-blueviolet.svg)](https://github.com/asg017/sqlite-vec)

A pure SQLite library for Kotlin Multiplatform. The C amalgamation (`sqlite3.c`,
plus extensions) is linked straight into every native klib, and on the JVM a
single JNI `.so` is built and loaded at runtime — there is no third-party JDBC
driver, no `sqlite-jdbc` on the classpath, no Java-side shadow of the engine.

The API is identical across JVM, Linux/macOS/Windows native, all of Apple's
platforms (iOS / macOS / tvOS / watchOS, devices and simulators), and Android
Native. You open a connection, prepare statements, and read typed columns.

Current version: **0.1.0**.

## Supported targets

| Target                                       | Backend                                         |
|----------------------------------------------|-------------------------------------------------|
| `jvm` (any host: linux/macOS/windows)        | dynamic `.so` / `.dylib` / `.dll` loaded via JNI |
| `linuxX64`, `linuxArm64`                     | static C amalgamation linked into the klib     |
| `macosX64`, `macosArm64`                     | static C amalgamation linked into the klib     |
| `iosX64`, `iosArm64`, `iosSimulatorArm64`    | static C amalgamation linked into the klib     |
| `tvosX64`, `tvosArm64`, `tvosSimulatorArm64` | static C amalgamation linked into the klib     |
| `watchosX64`, `watchosArm32`, `watchosArm64`, `watchosSimulatorArm64` | static C amalgamation linked into the klib |
| `mingwX64`                                   | static C amalgamation linked into the klib     |
| `androidNativeArm32`/`Arm64`/`X86`/`X64`     | static C amalgamation linked into the klib     |

The native targets are configured with
[`kn-clang-compiler-plugin`](https://github.com/caffeine-mgn/kn-clang-compiler-plugin);
no `cinterop` toolchain install is required from the consumer.

> **Apple targets need a Mac host.** The Kotlin Multiplatform plugin
> auto-disables `macos*`, `ios*`, `tvos*` and `watchos*` on Linux and
> Windows, so the CI release (which runs on `ubuntu-latest` like the rest
> of the caffeine-mgn libraries) ships the JVM + Linux + Android + Mingw
> slice. To consume an Apple klib, build the project locally on macOS
> (`./gradlew publishToMavenLocal`) or wait for an Apple-target CI to be
> added.

## Built-in extensions

| Extension        | Version  | Auto-loaded                                                              |
|------------------|----------|--------------------------------------------------------------------------|
| **sqlite-vec**   | `0.1.9`  | yes — registered via `sqlite3_auto_extension` on JVM startup, statically linked into every native klib |
| **JSON1**        | bundled  | compiled into the amalgamation with `-DSQLITE_ENABLE_JSON1`              |
| **FTS3 / FTS4 / FTS5** | bundled | full-text search compiled into the amalgamation                       |
| **RTREE**        | bundled  | R-tree spatial index compiled into the amalgamation                      |

`sqlite-vec` gives you virtual `vec0` tables for vector search (k-nearest
neighbours, cosine / L2 / Hamming distance, etc.). The engine is configured
with `SQLITE_THREADSAFE=1`, `FTS3/4/5`, `RTREE`, `JSON1`, `COLUMN_METADATA`,
`DBSTAT_VTAB`, `EXPLAIN_COMMENTS`, `UNLOCK_NOTIFY`, `UPDATE_DELETE_LIMIT`
through the `sqlite3.c` amalgamation in `src/native/`. See the comments in
`build.gradle.kts` (`SQLITE_COMPILE_FLAGS`) for the exact list.

Add your own out-of-tree extension by dropping the C file into `src/native/`
and appending it to the `compileFile(...)` calls in `build.gradle.kts`.

## Versioning

The published version is taken from the `GITHUB_REF_NAME` environment variable
in CI and falls back to `0.1.0-SNAPSHOT` locally.

## Installation

Add the dependency to your KMP module (replace `VERSION` with the tag you
want):

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("pw.binom.db:ksqlite:VERSION")
        }
    }
}
```

The native targets pull the right static klib automatically. On the JVM
nothing extra is required — the native library is built by Gradle at compile
time and extracted on first use into the user's cache directory (see
`NativeLoader`).

## Examples

### Opening a connection

```kotlin
import pw.binom.db.ksqlite.SQLiteConnection

SQLiteConnection.open("app.db").use { conn ->
    conn.exec("""
        CREATE TABLE IF NOT EXISTS users (
            id    INTEGER PRIMARY KEY,
            name  TEXT NOT NULL,
            score REAL
        )
    """)
}
```

There are also `SQLiteConnection.memory(name = "cache")` for an in-memory
database and `SQLiteConnection.temporary()` for a temp file that is deleted on
close.

### Reading typed columns

```kotlin
conn.prepare("SELECT id, name, score FROM users WHERE id = ?").use { stmt ->
    stmt.bind(1, 42L)
    stmt.executeQuery().use { rs ->
        if (rs.next()) {
            val id    = rs.getLong(0)        // nullable boxed types for SQL NULL
            val name  = rs.getString(1)
            val score = rs.getDouble(2)
        }
    }
}
```

Supported column accessors: `getLong`, `getInt`, `getDouble`, `getFloat`,
`getBoolean`, `getString`, `getBlob`, `getBytes`, `getNull`,
plus the same set keyed by column name. `getBytes` / `getBlob` returns a
`ByteArray`, `getString` is UTF-8. JSON columns are read with `getJson` (see
below).

### Parameter binding

```kotlin
conn.prepare("INSERT INTO users(name, score) VALUES (?, ?)").use { stmt ->
    stmt.bind(1, "alice")
    stmt.bind(2, 0.95)
    stmt.executeUpdate()   // returns the number of affected rows
}
```

Supported bind types: `Long`, `Int`, `Double`, `Float`, `Boolean`, `String`,
`ByteArray`, `FloatArray` (vector). `null` is bound as SQL `NULL`.

### Transactions

```kotlin
conn.beginTransaction()
try {
    conn.exec("INSERT INTO users(name, score) VALUES ('bob', 1.0)")
    conn.exec("UPDATE users SET score = score + 1 WHERE name = 'bob'")
    conn.commit()
} catch (e: Throwable) {
    conn.rollback()
    throw e
}
```

There is no `try-with-resources` for transactions on purpose — that is what
the `try { ... } catch { rollback() } finally { /* connection close */ }`
block above models.

### Vector search with sqlite-vec

`sqlite-vec` is enabled out of the box — no separate registration step.

```kotlin
conn.exec("""
    CREATE VIRTUAL TABLE IF NOT EXISTS items USING vec0(
        embedding float[4] distance_metric=cosine
    )
""")

val query = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)

conn.prepare("INSERT INTO items(rowid, embedding) VALUES (?, ?)").use { stmt ->
    // rowid is the row identifier, embedding is the vector
    listOf(
        1L to floatArrayOf(0.10f, 0.20f, 0.30f, 0.40f),
        2L to floatArrayOf(0.11f, 0.21f, 0.31f, 0.41f),
        3L to floatArrayOf(0.00f, 0.00f, 1.00f, 0.00f),
    ).forEach { (id, vec) ->
        stmt.bind(1, id)
        stmt.bind(2, vec)
        stmt.executeUpdate()
    }
}

conn.prepare(
    """
    SELECT rowid, distance
      FROM items
     WHERE embedding MATCH ?
     ORDER BY distance
     LIMIT 5
    """.trimIndent(),
).use { stmt ->
    stmt.bind(1, query)
    stmt.executeQuery().use { rs ->
        while (rs.next()) {
            val rowId    = rs.getLong(0)
            val distance = rs.getDouble(1)   // cosine distance
            println("$rowId  $distance")
        }
    }
}
```

Use `Vector(...).fill(floats)` to build a typed value for binding without
constructing a raw `FloatArray`:

```kotlin
val v = Vector(FloatDimension(4)).fill(0.1f, 0.2f, 0.3f, 0.4f)
stmt.bind(1, v)
```

`Vector.toFloatArray()` and `Vector.asBytes()` (for `BLOB` columns) cover the
other directions.

### JSON via the JSON1 extension

JSON1 is compiled into the amalgamation with `-DSQLITE_ENABLE_JSON1`, so
every TEXT column can act as a JSON document and the SQL functions
(`json_extract`, `json_array`, `json_object`, `json_each`, …) work straight
away:

```kotlin
conn.exec("""
    CREATE TABLE docs (id INTEGER PRIMARY KEY, payload TEXT);
    INSERT INTO docs (payload) VALUES
      ('{"name":"alice","tags":["a","b"]}'),
      ('{"name":"bob","age":30}');
""")

conn.prepare("""
    SELECT id,
           json_extract(payload, '$.name') AS name,
           payload
    FROM docs
    ORDER BY id
""").use { stmt ->
    stmt.executeQuery().use { rs ->
        while (rs.next()) {
            val name = rs.getText(1)         // "alice", "bob"
            val raw  = rs.getJson(2)        // Json(text = "{...}")
            // parse raw.text with kotlinx.serialization / your favourite lib
        }
    }
}
```

`Json` is just a typed wrapper around the raw JSON text — `getJson` returns
`null` on SQL NULL and the underlying text is preserved verbatim. There is no
Kotlin-side parsing because most projects will reach for kotlinx.serialization
or their own JSON layer; the API just gives you a self-describing accessor
that won't silently coerce to a `String`.

### Convenience queries

```kotlin
val rowCount = conn.queryForInt("SELECT COUNT(*) FROM users", 0)
val name     = conn.queryForString("SELECT name FROM users WHERE id = ?", 0, 42L)
```

`queryForXxx(...)` runs a statement, advances to the first row, reads column
`0` and frees the statement. There are variants for `Long`, `Int`, `Double`,
`Boolean`, `String`, `ByteArray`.

## Build

```bash
./gradlew build           # compile every configured target
./gradlew jvmTest         # run the JVM tests (builds + loads the .so)
./gradlew linuxX64Test    # run the linuxX64 tests
```

Other natives (`macosArm64`, `iosArm64`, `mingwX64`, …) are skipped on Linux
hosts because the corresponding toolchain is unavailable; the JVM and Linux
tests run everywhere.

## Loading the native library on the JVM

The JVM target ships only the bytecode and a single platform-specific `.so`
that is compiled at build time by the same Gradle module (see
`clangBuildDynamic` in `build.gradle.kts`). On first use, `NativeLoader`
copies that artifact into the user's cache directory
(`~/.cache/ksqlite` on Linux, the equivalent of `XDG_CACHE_HOME` elsewhere)
and verifies it with the SHA-256 hash baked into the JAR. No `/tmp` paths,
no mutable global state.