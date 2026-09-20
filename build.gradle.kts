import pw.binom.kotlin.clang.addStatic
import pw.binom.kotlin.clang.clangBuildDynamic
import pw.binom.kotlin.clang.clangBuildStatic
import pw.binom.kotlin.clang.compileTaskName
import pw.binom.kotlin.clang.eachNative
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.konan.target.KonanTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kn.clang)
    `maven-publish`
}

allprojects {
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
    }
}

group = "pw.binom.db"
version = System.getenv("GITHUB_REF_NAME") ?: "0.1.0-SNAPSHOT"

val KOTLIN_VERSION = "2.1.0"

val NATIVE_SQLITE_SRC = file("${layout.projectDirectory}/src/native/sqlite3.c")
val NATIVE_VEC_SRC = file("${layout.projectDirectory}/src/native/sqlite-vec/sqlite-vec.c")
val NATIVE_INCLUDE_DIRS = listOf(
    file("${layout.projectDirectory}/src/native"),
    file("${layout.projectDirectory}/src/native/sqlite-vec"),
    file("${layout.projectDirectory}/src/nativeMain/c"),
)
val JVM_JNI_SRC_DIR = file("${layout.projectDirectory}/src/jvmMain/c")

/*
 * Compile-time flags baked into the SQLite amalgamation + sqlite-vec.
 *
 *  -DSQLITE_CORE: tells sqlite-vec to call sqlite3 APIs directly (via sqlite3.h)
 *   instead of going through the extension dispatch table (sqlite3ext.h). This
 *   is what we want because sqlite-vec is linked into the same translation
 *   units as sqlite3, not loaded as a separate shared object.
 *
 *  The rest match the feature set we settled on with the user:
 *  FTS3/4/5, RTREE, JSON1, COLUMN_METADATA, DBSTAT_VTAB, EXPLAIN_COMMENTS,
 *  THREADSAFE=1, UNLOCK_NOTIFY, UPDATE_DELETE_LIMIT.
 */
val SQLITE_COMPILE_FLAGS = listOf(
    "-DSQLITE_CORE",
    "-DSQLITE_THREADSAFE=1",
    "-DSQLITE_ENABLE_FTS3",
    "-DSQLITE_ENABLE_FTS4",
    "-DSQLITE_ENABLE_FTS5",
    "-DSQLITE_ENABLE_RTREE",
    "-DSQLITE_ENABLE_JSON1",
    "-DSQLITE_ENABLE_COLUMN_METADATA=1",
    "-DSQLITE_ENABLE_DBSTAT_VTAB",
    "-DSQLITE_ENABLE_EXPLAIN_COMMENTS",
    "-DSQLITE_ENABLE_UNLOCK_NOTIFY",
    "-DSQLITE_ENABLE_UPDATE_DELETE_LIMIT",
)

tasks.withType<Test>().configureEach {
    testLogging {
        showStandardStreams = true
        events("started", "passed", "failed", "skipped", "standardOut", "standardError")
    }
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Posix-family K/N targets. appleMain/macosMain hierarchy is auto-derived.
    linuxX64()
    linuxArm64()
    mingwX64()
    macosX64()
    macosArm64()
    androidNativeArm32()
    androidNativeArm64()
    androidNativeX86()
    androidNativeX64()

    targets {
        compilerOptions {
        }
    }

    /*
     * Build a single static archive per native target containing
     *   - SQLite amalgamation (sqlite3.c)
     *   - sqlite-vec amalgamation (sqlite-vec.c, compiled with -DSQLITE_CORE)
     *   - ksqlite_shim.c (one symbol: ksqlite_init -> sqlite3_auto_extension)
     * Link the resulting archive into the target binary. The Kotlin side calls
     * ksqlite_init() once at process start; from that point every sqlite3_open*
     * call automatically loads sqlite-vec, exposing vec0 et al.
     */
    eachNative {
        val sqliteStaticTask = clangBuildStatic(target = konanTarget, name = "sqlitevec") {
            konanVersion.set(KOTLIN_VERSION)
            compileArgs("-std=gnu99")
            compileArgs(*SQLITE_COMPILE_FLAGS.toTypedArray())
            NATIVE_INCLUDE_DIRS.forEach { include(it) }
            compileFile(NATIVE_SQLITE_SRC)
            compileFile(NATIVE_VEC_SRC)
            compileFile(file("${layout.projectDirectory}/src/nativeMain/c/ksqlite_shim.c"))
            compileFile(file("${layout.projectDirectory}/src/nativeMain/c/ksqlite_wrappers.c"))
            optimizationLevel(2)
        }
        tasks.findByName(compileTaskName)?.dependsOn(sqliteStaticTask)

        binaries {
            compilations["main"].apply {
                addStatic(sqliteStaticTask.staticFile)
                cinterops {
                    create("nativeSqlite3") {
                        defFile = project.file("src/nativeInterop/nativeSqlite3.def")
                        packageName = "platform.internal_sqlite"
                        NATIVE_INCLUDE_DIRS.forEach { includeDirs(it.absolutePath) }
                    }
                }
            }
        }
    }

    /*
     * Build the JVM-side dynamic library: SQLite + sqlite-vec + ksqlite_jni.c
     * into a shared object that we bundle into the jar and load at runtime
     * via NativeLoader (see jvmMain/.../NativeLoader.kt). ksqlite_jni.c's
     * JNI_OnLoad() registers sqlite3_vec_init with sqlite3_auto_extension so
     * every new connection picks up vec0 with no caller wiring.
     *
     * We build for every JVM host the kn-clang plugin can target. On
     * Linux/Windows we cross-compile all of {linux_x64, linux_arm64,
     * mingw_x64} eagerly; on macOS we only build for the local host (Apple's
     * clang cannot cross-compile to Linux/Windows from Darwin without SDK
     * hacking, mirroring the klua convention).
     */
    val currentHost = HostManager.host
    val isMacHost = currentHost == KonanTarget.MACOS_X64 ||
            currentHost == KonanTarget.MACOS_ARM64
    val jvmHostTargets = buildList {
        add(KonanTarget.LINUX_X64)
        add(KonanTarget.LINUX_ARM64)
        add(KonanTarget.MINGW_X64)
        if (isMacHost) add(currentHost)
    }

    val jdkHome = System.getenv("JAVA_HOME")
    val jdkIncludeCandidates = listOfNotNull(
        jdkHome?.let { "$it/include" },
        "/usr/lib/jvm/java-21-openjdk/include",
        "/usr/lib/jvm/default-java/include",
    )
    val jdkInclude = jdkIncludeCandidates.firstOrNull { File(it).exists() } ?: ""

    val jvmBuildTasks = jvmHostTargets.associateWith { target ->
        val platform = when (target.family) {
            Family.LINUX, Family.ANDROID -> "linux"
            Family.OSX -> "darwin"
            Family.MINGW -> "win32"
            else -> "linux"
        }
        val jdkIncludePlatformCandidates = listOfNotNull(
            jdkHome?.let { "$it/include/$platform" },
            "/usr/lib/jvm/java-21-openjdk/include/$platform",
            "/usr/lib/jvm/default-java/include/$platform",
        )
        val jdkIncludePlatform = jdkIncludePlatformCandidates.firstOrNull { File(it).exists() } ?: ""

        clangBuildDynamic(target = target, name = "ksqlite") {
            konanVersion.set(KOTLIN_VERSION)
            compileArgs("-std=gnu99", "-fno-rtti")
            compileArgs(*SQLITE_COMPILE_FLAGS.toTypedArray())
            NATIVE_INCLUDE_DIRS.forEach { include(it) }
            include(JVM_JNI_SRC_DIR)
            if (jdkInclude.isNotEmpty()) include(File(jdkInclude))
            if (jdkIncludePlatform.isNotEmpty()) include(File(jdkIncludePlatform))
            compileFile(NATIVE_SQLITE_SRC)
            compileFile(NATIVE_VEC_SRC)
            compileFile(file("${layout.projectDirectory}/src/nativeMain/c/ksqlite_shim.c"))
            compileFile(file("${JVM_JNI_SRC_DIR}/ksqlite_jni.c"))
            optimizationLevel(2)
        }.also { dynamicTask ->
            // Cross-targets gracefully no-op when the host can't build them:
            // missing JDK headers / sysroot make the cross-compile impossible
            // on this machine. Keeping the task registered means `gradle tasks`
            // and IDEs still see the full target list.
            val needsCrossCompile = target != currentHost
            if (needsCrossCompile) {
                val jdkIncludeOk = jdkInclude.isNotEmpty() && jdkIncludePlatform.isNotEmpty()
                dynamicTask.onlyIf("${target.name} JDK headers") {
                    jdkIncludeOk
                }
            }
        }
    }

    val jvmCopyTasks = jvmBuildTasks.mapValues { (target, buildTask) ->
        val libExt = when (target.family) {
            Family.MINGW -> "dll"
            Family.OSX, Family.IOS, Family.TVOS, Family.WATCHOS -> "dylib"
            else -> "so"
        }
        // clangBuildDynamic produces "ksqlite.<ext>". We additionally produce
        // a "libksqlite.<ext>" copy because on Linux/macOS System.loadLibrary
        // expects the "lib" prefix by convention; we feed both into the jar
        // so NativeLoader picks the right one per host.
        val outDir = layout.buildDirectory.dir("processed-resources/native/${target.name}").get().asFile
        val renameTo = "libksqlite.$libExt"
        val copyTask = tasks.register("copyKsqliteNativeLib${target.name}", Copy::class.java) {
            from(buildTask.dynamicFile)
            rename { renameTo }
            into(outDir)
            outputs.upToDateWhen { true }
        }
        copyTask
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api("org.jetbrains.kotlin:kotlin-stdlib-common:$KOTLIN_VERSION")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }
        val jvmMain by getting {
            dependencies {
                api("org.jetbrains.kotlin:kotlin-stdlib:$KOTLIN_VERSION")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }

        // Bundle the freshly-built dynamic libs into the jar at
        // native/<host>/libksqlite.<ext> so NativeLoader can locate them
        // via ClassLoader.getResource at runtime.
        tasks.named("jvmProcessResources", Copy::class.java).configure {
            dependsOn(jvmCopyTasks.values)
            from(layout.buildDirectory.dir("processed-resources/native"))
            include("**/*.so", "**/*.dylib", "**/*.dll")
        }
    }
}

publishing {
    publications.withType<MavenPublication> {
        pom {
            name.set("ksqlite")
            description.set("SQLite for Kotlin Multiplatform with built-in sqlite-vec")
            url.set("https://github.com/caffeine-mgn/ksqlite")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("subochev")
                    name.set("Anton Subochev")
                    email.set("caffeine.mgn@gmail.com")
                }
            }
            scm {
                connection.set("scm:git:git://github.com/caffeine-mgn/ksqlite.git")
                developerConnection.set("scm:git:ssh://git@github.com/caffeine-mgn/ksqlite.git")
                url.set("https://github.com/caffeine-mgn/ksqlite")
            }
        }
    }
}