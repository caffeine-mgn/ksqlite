import pw.binom.kotlin.clang.addStatic
import pw.binom.kotlin.clang.clangBuildDynamic
import pw.binom.kotlin.clang.clangBuildStatic
import pw.binom.kotlin.clang.compileTaskName
import pw.binom.kotlin.clang.eachNative
import org.gradle.api.Task
import org.gradle.plugins.signing.SigningExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.util.Base64

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kn.clang)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.maven.publish)
}

allprojects {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

group = "pw.binom.db"
val PROP_VERSION = findProperty("version") as String?
val ENV_VERSION = System.getenv("GITHUB_REF_NAME")?.removePrefix("v")
// Gradle's default project.version is the literal string "unspecified" (not
// null), so a plain `?: "0.1.6"` chain never reaches the fallback when the
// `version=` Gradle property is missing — `findProperty("version")` returns
// "unspecified" instead of null. We have to explicitly guard against it.
version = when {
    !PROP_VERSION.isNullOrBlank() && PROP_VERSION != "unspecified" -> PROP_VERSION
    !ENV_VERSION.isNullOrBlank() && ENV_VERSION != "unspecified" -> ENV_VERSION
    else -> "0.1.6"
}

val KOTLIN_VERSION = "2.4.20"

val NATIVE_SQLITE_SRC = file("${layout.projectDirectory}/src/native/sqlite3.c")
val NATIVE_VEC_SRC = file("${layout.projectDirectory}/src/native/sqlite-vec/sqlite-vec.c")
val NATIVE_INCLUDE_DIRS = listOf(
    file("${layout.projectDirectory}/src/native"),
    file("${layout.projectDirectory}/src/native/sqlite-vec"),
    file("${layout.projectDirectory}/src/nativeMain/c"),
)
val JVM_JNI_SRC_DIR = file("${layout.projectDirectory}/src/jvmMain/c")

/*
 * jniLibs tree packaged into the Android AAR: <abi>/libksqlite.so. AGP copies
 * everything under this directory into the AAR's jni/<abi>/ folder, and from
 * there the consumer's APK gets it under lib/<abi>/libksqlite.so — the only
 * location the Android linker will load a native library from. The copy tasks
 * that populate it are collected in [androidNativeCopyTasks] and wired into
 * AGP's `preBuild` below.
 */
val ANDROID_JNI_LIBS_DIR = layout.buildDirectory.dir("androidJniLibs")
val androidNativeCopyTasks = mutableListOf<TaskProvider<*>>()

/*
 * Path to the Android NDK sysroot bundled into KONAN_DATA_DIR/dependencies.
 * kn-clang's Konan dependency downloader unpacks `target-sysroot-1-android_ndk`
 * and `target-toolchain-2-{linux,osx,windows}-android_ndk` here the first
 * time an Android KonanTarget's sysroot is requested (via
 * `checkSysrootInstalled`, normally reached through a static build for the
 * same target); once present, kn-clang can cross-compile for any of
 * {android_arm32, android_arm64, android_x86, android_x64} from a
 * Linux/macOS/Windows host. We probe this directory before scheduling the JNI
 * cross-build so a host without the NDK can silently skip the Android tasks
 * instead of failing mid-link.
 */
fun konanNdkDir(target: KonanTarget): File {
    val konanDataDir = System.getenv("KONAN_DATA_DIR")?.let { File(it) }
        ?: File(System.getProperty("user.home"), ".konan")
    val triple = when (target) {
        KonanTarget.ANDROID_ARM32 -> "arm-linux-androideabi"
        KonanTarget.ANDROID_ARM64 -> "aarch64-linux-android"
        KonanTarget.ANDROID_X86 -> "i686-linux-android"
        KonanTarget.ANDROID_X64 -> "x86_64-linux-android"
        else -> error("konanNdkDir called with non-Android target: $target")
    }
    // Kn-clang's Konan.downloader unpacks the Android NDK under
    //   $KONAN_DATA_DIR/dependencies/target-toolchain-2-{linux,osx,windows}-android_ndk
    // (we use the linux one — the Linux-host clang + Linux NDK sysroot works
    // fine for any host that can run Gradle). The per-target triple lives as
    //   $triple/...   (e.g. aarch64-linux-android/)
    // and the clang drivers live as
    //   bin/$triple{21,29}-clang
    // We accept any of those as the "NDK is available" marker.
    val toolchainRoot = konanDataDir.resolve("dependencies/target-toolchain-2-linux-android_ndk")
    return toolchainRoot.resolve(triple)
}

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
    // Extend the default hierarchy template with a jvm+android shared group.
    // Doing it here (rather than manual dependsOn calls) keeps the template
    // applied, so `nativeMain`/`appleMain`/... still exist for the K/N targets.
    // The group creates the `jvmSharedMain` source set (src/jvmSharedMain).
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withJvm()
                withAndroidTarget()
            }
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Android JVM/ART delivery. This produces the AAR that carries the shared
    // jvmSharedMain code plus the bionic `libksqlite.so` per ABI through
    // `jniLibs` (see the Android section below). It is independent from the
    // `androidNative*` Kotlin/Native targets further down, which build klibs
    // for Kotlin/Native consumers.
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        /*
         * Publish both Android build types. Only `release` is wired by default,
         * but an Android application's runtime classpath carries
         * `BuildTypeAttr=debug`. Without a matching `debug` variant Gradle has
         * no exact android candidate and silently falls back to `ksqlite-jvm`
         * (the jar-based variant, no jniLibs, cannot load on Android), so the
         * `debug` variant must be published too.
         */
        publishLibraryVariants("release", "debug")
    }

    // Posix-family K/N targets. appleMain/macosMain hierarchy is auto-derived.
    linuxX64()
    linuxArm64()
    mingwX64()
    macosX64()
    macosArm64()
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    tvosX64()
    tvosArm64()
    tvosSimulatorArm64()
    watchosX64()
    watchosArm32()
    watchosArm64()
    watchosSimulatorArm64()
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
    val nativeStaticTaskByKonanTarget = mutableMapOf<KonanTarget, Task>()
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
        // kn-clang (>= 0.1.21) can install the Kotlin/Native distribution
        // itself, on demand, from the correct `kotlin-native-prebuilt-*` asset.
        // But this is a KMP project, so KGP's own `downloadKotlinNativeDistribution`
        // may be scheduled for the native klib targets too; with
        // org.gradle.parallel=true the two installers could race on the same
        // ~/.konan/kotlin-native-prebuilt-* directory. Keep KGP's task as an
        // explicit ordering anchor so it wins and the plugin's install is a
        // no-op — the old 0.0.6 URL/partial-install workaround is no longer why
        // this dependency exists.
        sqliteStaticTask.dependsOn(tasks.named("downloadKotlinNativeDistribution"))
        nativeStaticTaskByKonanTarget[konanTarget] = sqliteStaticTask
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
     * into a shared object that is loaded at runtime via JNI. ksqlite_jni.c's
     * JNI_OnLoad() registers sqlite3_vec_init with sqlite3_auto_extension so
     * every new connection picks up vec0 with no caller wiring.
     *
     * We build for every JVM host the kn-clang plugin can target. On
     * Linux/Windows we cross-compile all of {linux_x64, linux_arm64,
     * mingw_x64} eagerly; on macOS we only build for the local host (Apple's
     * clang cannot cross-compile to Linux/Windows from Darwin without SDK
     * hacking, mirroring the klua convention).
     *
     * The desktop targets are copied into the JVM jar under
     * /<target>/libksqlite.<ext> and loaded by NativeLoader (jvmMain).
     *
     * The Android ABI builds (android_arm32, android_arm64, android_x86,
     * android_x64) are built for any host that has the NDK toolchain cached in
     * KONAN_DATA_DIR/dependencies. They are NOT put into the JVM jar — a .so
     * extracted from a jar cannot be dlopen'd on modern Android (linker
     * namespace + SELinux) — instead they populate the AAR's jniLibs tree, see
     * [androidNativeCopyTasks] below. (The androidNative* Kotlin/Native
     * targets elsewhere in this file are unrelated klib builds for
     * Kotlin/Native consumers, not the Android JVM/ART path.)
     */
    val currentHost = HostManager.host
    val isMacHost = currentHost == KonanTarget.MACOS_X64 ||
            currentHost == KonanTarget.MACOS_ARM64
    val jvmHostTargets = buildList {
        add(KonanTarget.LINUX_X64)
        add(KonanTarget.LINUX_ARM64)
        add(KonanTarget.MINGW_X64)
        // Android JNI: buildable from any Linux/macOS/Windows host that has
        // the Android NDK sysroot already cached in KONAN_DATA_DIR/dependencies.
        // We register all four Android targets unconditionally and let
        // `checkSysrootInstalled` no-op the ones whose sysroot isn't present.
        add(KonanTarget.ANDROID_ARM32)
        add(KonanTarget.ANDROID_ARM64)
        add(KonanTarget.ANDROID_X86)
        add(KonanTarget.ANDROID_X64)
        if (isMacHost) add(currentHost)
    }

    val jdkHome = System.getenv("JAVA_HOME")
    val jdkIncludeCandidates = listOfNotNull(
        jdkHome?.let { "$it/include" },
        "/usr/lib/jvm/java-21-openjdk/include",
        "/usr/lib/jvm/default-java/include",
    )
    val jdkInclude = jdkIncludeCandidates.firstOrNull { File(it).exists() } ?: ""

    // JDK platform include subdir for the *host* OS (`include/linux`, `win32`,
    // `darwin`). Used as the fallback for cross-targets whose own platform dir
    // the JDK doesn't ship (e.g. no `include/win32` on a Linux JDK) — see below.
    val hostJdkPlatform = when (HostManager.host.family) {
        Family.MINGW -> "win32"
        Family.OSX, Family.IOS, Family.TVOS, Family.WATCHOS -> "darwin"
        else -> "linux"
    }

    // The JDK's platform `jni_md.h` for a cross-target is only relevant on
    // Windows (where it defines the `__declspec` calling convention); the
    // Linux/macOS one compiles fine everywhere else and is all a Linux CI host
    // has. Keep the per-family lookup only to demonstrate the intent, but never
    // let an unavailable platform dir (e.g. `include/win32` on a Linux JDK)
    // disqualify the cross-compile — that silently SKIPPED the mingw_x64 .dll
    // on CI (no Windows JDK) and left the JVM jar Windows-less.
    val jvmBuildTasks = jvmHostTargets.associateWith { target ->
        val isAndroidTarget = target.family == Family.ANDROID
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
        // The platform `jni_md.h` (`win32/` vs `linux/`) is picked for real, but
        // only if the JDK actually ships one for the *cross* platform; a Linux
        // JDK has no `include/win32`, so fall back to the JDK's *host* platform
        // dir (`include/linux`), whose jni_md.h compiles just as well for mingw.
        // Picking none at all is what silently SKIPPED the mingw_x64 .dll.
        val jdkIncludePlatform = jdkIncludePlatformCandidates.firstOrNull { File(it).exists() }
            ?: jdkHome?.let { "$it/include/$hostJdkPlatform" }?.takeIf { File(it).exists() }
            ?: jdkIncludeCandidates.firstOrNull { File(it).exists() }
            ?: ""

        clangBuildDynamic(target = target, name = "ksqlite") {
            konanVersion.set(KOTLIN_VERSION)
            compileArgs("-std=gnu99", "-fno-rtti")
            compileArgs(*SQLITE_COMPILE_FLAGS.toTypedArray())
            // FTS3/4/5 use log() for IDF ranking, so the shared object must
            // link libm explicitly. Without it glibc resolves log lazily and
            // desktop JVM happens to work, but the Android linker resolves
            // every symbol at dlopen time and aborts with
            // `cannot locate symbol "log"` (verified on API 36 x86_64).
            linkArgs("-lm")
            NATIVE_INCLUDE_DIRS.forEach { include(it) }
            include(JVM_JNI_SRC_DIR)
            if (!isAndroidTarget) {
                if (jdkInclude.isNotEmpty()) include(File(jdkInclude))
                if (jdkIncludePlatform.isNotEmpty()) include(File(jdkIncludePlatform))
            }
            compileFile(NATIVE_SQLITE_SRC)
            compileFile(NATIVE_VEC_SRC)
            compileFile(file("${layout.projectDirectory}/src/nativeMain/c/ksqlite_shim.c"))
            compileFile(file("${JVM_JNI_SRC_DIR}/ksqlite_jni.c"))
            optimizationLevel(2)
        }.also { dynamicTask ->
            // Same ordering rationale as the static task above: serialize
            // against KGP's own Kotlin/Native downloader so the two installers
            // can't race on ~/.konan under parallel execution.
            dynamicTask.dependsOn(tasks.named("downloadKotlinNativeDistribution"))
            if (isAndroidTarget) {
                // The Android dynamic build also needs the bionic sysroot
                // toolchain extracted into KONAN_DATA_DIR before the onlyIf
                // below is evaluated; the static build for the same target does
                // that download via kn-clang's `checkSysrootInstalled`.
                dynamicTask.dependsOn(nativeStaticTaskByKonanTarget.getValue(target))
            }
            // Cross-targets gracefully no-op when the host can't build them:
            // missing JDK headers / sysroot make the cross-compile impossible
            // on this machine. Keeping the task registered means `gradle tasks`
            // and IDEs still see the full target list.
            val needsCrossCompile = target != currentHost
            if (needsCrossCompile) {
                dynamicTask.onlyIf("${target.name} toolchain available") {
                    if (isAndroidTarget) {
                        // Belt-and-braces check after the download task above
                        // has run; if the toolchain still isn't there we skip
                        // rather than fail the whole build.
                        konanNdkDir(target).exists()
                    } else {
                        // Only the base JDK include dir is required; the
                        // platform subdir (`win32`/`linux`) is optional and the
                        // base dir is used as a fallback (see above).
                        jdkInclude.isNotEmpty()
                    }
                }
            }
        }
    }

    // Only the desktop targets go into the JVM jar; the Android ABI `.so` are
    // shipped in the AAR's jniLibs instead (see the copy tasks below).
    val jvmCopyTasks = jvmBuildTasks
        .filterKeys { it.family != Family.ANDROID }
        .mapValues { (target, buildTask) ->
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
        }
        copyTask
    }

    /*
     * Android ABI -> jniLibs folder name. AGP only packages `lib/<abi>/...`
     * for these exact ABI directory names. The clangBuildDynamic tasks above
     * already produce the bionic .so, so we just copy each into the jniLibs
     * tree that `android { sourceSets["main"].jniLibs.srcDir(...) }` consumes.
     * `preBuild` is wired to [androidNativeCopyTasks] below.
     */
    val androidAbiByTarget = linkedMapOf(
        KonanTarget.ANDROID_ARM64 to "arm64-v8a",
        KonanTarget.ANDROID_X64 to "x86_64",
        KonanTarget.ANDROID_ARM32 to "armeabi-v7a",
        KonanTarget.ANDROID_X86 to "x86",
    )
    androidNativeCopyTasks += androidAbiByTarget.map { (target, abi) ->
        val buildTask = jvmBuildTasks.getValue(target)
        tasks.register("copyAndroidKsqlite${target.name}", Copy::class.java) {
            from(buildTask.dynamicFile)
            rename { "libksqlite.so" }
            into(ANDROID_JNI_LIBS_DIR.get().dir(abi))
        }
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
        // `jvmSharedMain` (shared JVM/Android code) is created by the
        // applyDefaultHierarchyTemplate group above; Android reuses the entire
        // JNI-backed implementation and only swaps how the native library is
        // loaded (see NativeLibraryLoader.kt).
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

/*
 * Android library (AAR) packaging. `androidTarget()` above compiles the Kotlin
 * side; this block adds the namespace/manifest configuration and points the
 * `jniLibs` source set at the tree of bionic `.so` produced by the
 * copyAndroidKsqlite* tasks. `preBuild` is made to depend on those copies so
 * the files are present before AGP assembles the AAR (each copy task depends
 * on the matching kn-clang clangBuildDynamic task).
 */
android {
    namespace = "pw.binom.db.ksqlite"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].jniLibs.srcDir(ANDROID_JNI_LIBS_DIR)
}

afterEvaluate {
    tasks.named("preBuild") {
        dependsOn(androidNativeCopyTasks)
    }
}

// Stamp the published JVM jar with the library version. NativeLoader reads
// this through Package.getImplementationVersion() to pick the versioned cache
// directory (~/.cache/ksqlite/<version>/...), so the cache layout tracks the
// release automatically instead of a hardcoded constant that used to lag.
tasks.named<Jar>("jvmJar") {
    manifest {
        attributes["Implementation-Title"] = "ksqlite"
        attributes["Implementation-Version"] = project.version.toString()
    }
}

publishing {
    /*
     * Publishing wiring.
     *
     * 1. Sonatype Central (Maven Central) is the target. We use the
     *    vanniktech.maven.publish plugin which handles the Maven Central
     *    staging + portal upload, signAllPublications(), POM defaults and
     *    the "publish to Maven Central" task.
     *
     * 2. POM coordinates are pw.binom.db:ksqlite:<version> (the version is
     *    taken from GITHUB_REF_NAME in CI, so a tagged release sets itself).
     *
     * 3. GPG signing uses the in-memory key/password/keyId Gradle
     *    properties. Locally these are read from ~/.gradle/gradle.properties
     *    (so contributors don't need to set them up); in CI they are
     *    injected from the kdns-shaped secrets the user already
     *    provisioned (GPG_PRIVATE_KEY / GPG_PASSWORD / GPG_KEY_ID) and
     *    mapped onto the same property names by the workflow.
     *
     * 4. The key is base64-encoded when supplied through CI secrets, but
     *    kept as a literal ASCII-armored block in ~/.gradle/gradle.properties.
     *    `signingInMemoryKeyIsBase64` lets us switch between the two.
     */
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

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(
        groupId = "pw.binom.db",
        artifactId = "ksqlite",
        version = project.version.toString(),
    )

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

/*
 * Apply GPG signing to every Maven publication.
 *
 * Two modes, picked at configuration time by the `signingUseGpg`
 * Gradle property:
 *
 *   signingUseGpg=true — the CI mode used by .github/workflows/release.yml
 *   and shared with kdns / kn-clang-compiler-plugin. The GPG private key
 *   is imported into the system keyring once at job start, and we
 *   configure Gradle's `signing` extension to delegate to the `gpg`
 *   binary via `useGpgCmd()`. Key name and passphrase come from
 *   `signing.gnupg.keyName` / `signing.gnupg.passphrase` Gradle properties
 *   (forwarded as `-P` flags from CI).
 *
 *   default (signingUseGpg unset) — local development mode. Read an
 *   in-memory PGP key straight from Gradle properties without ever
 *   touching the system keyring. Two property-naming conventions are
 *   accepted:
 *     - vanniktech standard: `signingInMemoryKey{,Id,Password,IsBase64}`;
 *     - binom convention:    `binom.gpg.{private_key,key_id,password}`.
 *   `signingInMemoryKey*` wins when both are present. The private key
 *   value is assumed ASCII-armored with literal "\n" escapes (or
 *   base64-encoded when `signingInMemoryKeyIsBase64=true`); either way
 *   it is normalised into the real PGP block before being handed to
 *   `useInMemoryPgpKeys`.
 */
pluginManager.withPlugin("signing") {
    if (findProperty("signingUseGpg") == "true") {
        extensions.configure<SigningExtension>("signing") {
            useGpgCmd()
        }
        logger.lifecycle("[signing] Using system gpg via signing.gnupg.keyName=${findProperty("signing.gnupg.keyName")}")
        return@withPlugin
    }

    val key = providers.gradleProperty("signingInMemoryKey").orNull
        ?: providers.gradleProperty("binom.gpg.private_key").orNull
    val keyId = providers.gradleProperty("signingInMemoryKeyId").orNull
        ?: providers.gradleProperty("binom.gpg.key_id").orNull
    val password = providers.gradleProperty("signingInMemoryKeyPassword").orNull
        ?: providers.gradleProperty("binom.gpg.password").orNull
    val isBase64 = providers.gradleProperty("signingInMemoryKeyIsBase64").orNull?.toBoolean() ?: false

    if (key != null && keyId != null && password != null) {
        val decodedKey = if (isBase64) {
            String(Base64.getDecoder().decode(key))
        } else {
            // Both `signingInMemoryKey` and `binom.gpg.private_key` are
            // typically stored as ASCII-armored with literal "\n" escapes;
            // turn them into real newlines before handing to PGP.
            key.replace("\\n", "\n")
        }
        logger.lifecycle("[signing] Using in-memory PGP key, length=${decodedKey.length}, isBase64=${isBase64}")
        extensions.getByType(SigningExtension::class.java)
            .useInMemoryPgpKeys(decodedKey, keyId, password)
    } else {
        logger.lifecycle("[signing] No in-memory PGP key configured; publications will be signed by the publishing plugin only.")
    }
}