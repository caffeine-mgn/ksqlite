# ksqlite

[![Maven Central](https://img.shields.io/maven-central/v/pw.binom.db/ksqlite?label=Maven%20Central)](https://central.sonatype.com/artifact/pw.binom.db/ksqlite)
[![License: Apache-2.0](https://img.shields.io/github/license/caffeine-mgn/ksqlite)](https://www.apache.org/licenses/LICENSE-2.0.txt)
[![Kotlin](https://img.shields.io/badge/kotlin-2.4.20-blue.svg)](https://kotlinlang.org)
[![SQLite](https://img.shields.io/badge/SQLite-3.53.4-blue.svg)](https://sqlite.org)
[![sqlite-vec](https://img.shields.io/badge/sqlite--vec-0.1.9-blueviolet.svg)](https://github.com/asg017/sqlite-vec)

Чистая SQLite-библиотека для Kotlin Multiplatform. C-амáльгама (`sqlite3.c`
плюс расширения) линкуется прямо в каждый нативный klib, а на JVM единственный
JNI `.so` собирается и загружается в рантайме — никакого стороннего JDBC-драйвера,
никакого `sqlite-jdbc` в classpath, никакой Java-обёртки поверх движка.

API идентично на JVM, нативных таргетах Linux/macOS/Windows, всех платформах
Apple (iOS / macOS / tvOS / watchOS, устройства и симуляторы) и Android Native.
Открываете соединение, подготавливаете запросы, читаете типизированные колонки.

Текущая версия: **0.1.0**.

## Поддерживаемые таргеты

| Таргет                                       | Бэкенд                                            |
|----------------------------------------------|---------------------------------------------------|
| `jvm` (любой хост: linux/macOS/windows)      | динамическая `.so` / `.dylib` / `.dll`, загружается через JNI |
| `linuxX64`, `linuxArm64`                     | статическая C-амáльгама, залинкованная в klib      |
| `macosX64`, `macosArm64`                     | статическая C-амáльгама, залинкованная в klib      |
| `iosX64`, `iosArm64`, `iosSimulatorArm64`    | статическая C-амáльгама, залинкованная в klib      |
| `tvosX64`, `tvosArm64`, `tvosSimulatorArm64` | статическая C-амáльгама, залинкованная в klib      |
| `watchosX64`, `watchosArm32`, `watchosArm64`, `watchosSimulatorArm64` | статическая C-амáльгама, залинкованная в klib |
| `mingwX64`                                   | статическая C-амáльгама, залинкованная в klib      |
| `androidNativeArm32`/`Arm64`/`X86`/`X64`     | статическая C-амáльгама, залинкованная в klib      |

Нативные таргеты настраиваются через
[`kn-clang-compiler-plugin`](https://github.com/caffeine-mgn/kn-clang-compiler-plugin);
потребителю не нужно устанавливать тулчейн `cinterop`.

> **Для Apple-таргетов нужен Mac-хост.** Плагин Kotlin Multiplatform
> автоматически отключает `macos*`, `ios*`, `tvos*` и `watchos*` на Linux и
> Windows, поэтому CI-релиз (который, как и в остальных библиотеках caffeine-mgn,
> работает на `ubuntu-latest`) поставляет JVM + Linux + Android + Mingw срез.
> Чтобы использовать Apple-klib, соберите проект локально на macOS
> (`./gradlew publishToMavenLocal`) или подождите, пока появится Apple-target CI.

## Встроенные расширения

| Расширение        | Версия   | Авто-загрузка                                                                |
|-------------------|----------|------------------------------------------------------------------------------|
| **sqlite-vec**    | `0.1.9`  | да — регистрируется через `sqlite3_auto_extension` при старте JVM, статически залинковано в каждый нативный klib |
| **JSON1**         | встроено | скомпилировано в амáльгаму с флагом `-DSQLITE_ENABLE_JSON1`                  |
| **FTS3 / FTS4 / FTS5** | встроено | полнотекстовый поиск, скомпилирован в амáльгаму                          |
| **RTREE**         | встроено | пространственный индекс R-tree, скомпилирован в амáльгаму                    |

`sqlite-vec` даёт виртуальные таблицы `vec0` для векторного поиска (k-ближайших
соседей, косинусное / L2 / Хэмминг расстояние и т.д.). Движок сконфигурирован с
`SQLITE_THREADSAFE=1`, `FTS3/4/5`, `RTREE`, `JSON1`, `COLUMN_METADATA`,
`DBSTAT_VTAB`, `EXPLAIN_COMMENTS`, `UNLOCK_NOTIFY`, `UPDATE_DELETE_LIMIT` через
амáльгаму `sqlite3.c` в `src/native/`. Точный список смотрите в комментариях в
`build.gradle.kts` (`SQLITE_COMPILE_FLAGS`).

Чтобы добавить собственное out-of-tree расширение, положите C-файл в `src/native/`
и добавьте его в вызовы `compileFile(...)` в `build.gradle.kts`.

## Версионирование

Публикуемая версия берётся из переменной окружения `GITHUB_REF_NAME` в CI, а
локально откатывается на `0.1.0-SNAPSHOT`.

## Установка

Добавьте зависимость в ваш KMP-модуль (замените `VERSION` на нужный тег):

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("pw.binom.db:ksqlite:VERSION")
        }
    }
}
```

Нативные таргеты автоматически подтягивают нужный статический klib. На JVM
ничего дополнительного не требуется — нативная библиотека собирается Gradle
во время компиляции и распаковывается при первом использовании в пользовательский
кэш-каталог (см. `NativeLoader`).

## Примеры

### Открытие соединения

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

Также доступны `SQLiteConnection.memory(name = "cache")` для базы в памяти и
`SQLiteConnection.temporary()` для временного файла, удаляемого при закрытии.

### Чтение типизированных колонок

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

Поддерживаемые аксессоры колонок: `getLong`, `getInt`, `getDouble`, `getFloat`,
`getBoolean`, `getString`, `getBlob`, `getBytes`, `getNull`, и тот же набор по
имени колонки. `getBytes` / `getBlob` возвращает `ByteArray`, `getString` —
UTF-8. JSON-колонки читаются через `getJson` (см. ниже).

### Привязка параметров

```kotlin
conn.prepare("INSERT INTO users(name, score) VALUES (?, ?)").use { stmt ->
    stmt.bind(1, "alice")
    stmt.bind(2, 0.95)
    stmt.executeUpdate()   // returns the number of affected rows
}
```

Поддерживаемые типы привязки: `Long`, `Int`, `Double`, `Float`, `Boolean`,
`String`, `ByteArray`, `FloatArray` (вектор). `null` привязывается как SQL
`NULL`.

### Транзакции

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

`try-with-resources` для транзакций намеренно не используется — это и моделирует
блок `try { ... } catch { rollback() } finally { /* закрытие соединения */ }`
выше.

### Векторный поиск через sqlite-vec

`sqlite-vec` включён из коробки — никаких отдельных шагов регистрации.

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

Используйте `Vector(...).fill(floats)` для построения типизированного значения
для привязки без создания сырого `FloatArray`:

```kotlin
val v = Vector(FloatDimension(4)).fill(0.1f, 0.2f, 0.3f, 0.4f)
stmt.bind(1, v)
```

`Vector.toFloatArray()` и `Vector.asBytes()` (для `BLOB`-колонок) покрывают
остальные направления.

### JSON через расширение JSON1

JSON1 скомпилирован в амáльгаму с флагом `-DSQLITE_ENABLE_JSON1`, поэтому
каждая TEXT-колонка может выступать JSON-документом, а SQL-функции
(`json_extract`, `json_array`, `json_object`, `json_each`, …) работают сразу:

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

`Json` — это просто типизированная обёртка над сырым JSON-текстом: `getJson`
возвращает `null` для SQL NULL, а исходный текст сохраняется дословно.
Парсинга на стороне Kotlin нет, потому что большинство проектов используют
kotlinx.serialization или свой JSON-слой; API даёт лишь самоописывающий
аксессор, который молча не приведёт значение к `String`.

### Короткие запросы

```kotlin
val rowCount = conn.queryForInt("SELECT COUNT(*) FROM users", 0)
val name     = conn.queryForString("SELECT name FROM users WHERE id = ?", 0, 42L)
```

`queryForXxx(...)` выполняет запрос, переходит к первой строке, читает колонку
`0` и освобождает запрос. Есть варианты для `Long`, `Int`, `Double`, `Boolean`,
`String`, `ByteArray`.

## Сборка

```bash
./gradlew build           # compile every configured target
./gradlew jvmTest         # run the JVM tests (builds + loads the .so)
./gradlew linuxX64Test    # run the linuxX64 tests
```

Остальные нативные таргеты (`macosArm64`, `iosArm64`, `mingwX64`, …)
пропускаются на Linux-хостах, потому что соответствующий тулчейн недоступен;
JVM и Linux тесты запускаются везде.

## Загрузка нативной библиотеки на JVM

JVM-таргет поставляет только байткод и единственный платформо-специфичный `.so`,
который собирается во время сборки тем же Gradle-модулем (см. `clangBuildDynamic`
в `build.gradle.kts`). При первом использовании `NativeLoader` копирует артефакт
в пользовательский кэш-каталог (`~/.cache/ksqlite` на Linux, эквивалент
`XDG_CACHE_HOME` в других ОС) и проверяет его по SHA-256-хешу, вшитому в JAR.
Никаких путей в `/tmp`, никакого мутируемого глобального состояния.