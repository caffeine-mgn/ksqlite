package pw.binom.db.ksqlite

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/*
 * Locates the platform-specific ksqlite native library inside the classpath
 * and System.load()'s it.
 *
 * Layout inside the JAR (built by the Gradle `copyKsqliteNativeLib*` tasks):
 *   /<target>/libksqlite.so   (linux)
 *   /<target>/libksqlite.dylib (macOS)
 *   /<target>/libksqlite.dll  (Windows)
 *
 * Where `<target>` is the Konan target name (linux_x64, mingw_x64, ...).
 *
 * Why not `System.loadLibrary("ksqlite")`?
 *  - That requires every consumer to set `-Djava.library.path`. We don't want
 *    that — anyone dropping the JAR on the classpath should be able to run.
 *
 * Why SHA-256 verification?
 *  - We extract the binary to a per-user cache directory. The previous
 *    "extract to /tmp and trust the file" pattern is a CWE-426 vector (an
 *    attacker pre-plants a malicious .so at the predictable temp path before
 *    the victim JVM starts). Computing the hash inside the JAR — which an
 *    attacker can't tamper with without breaking the JAR signature — and
 *    comparing against the cached file closes that hole.
 */
internal object NativeLoader {

    private const val VERSION = "0.1.0"
    private val loaded = ConcurrentHashMap.newKeySet<String>()
    private val extractLocks = ConcurrentHashMap<String, ReentrantLock>()

    fun load() {
        val target = currentResource()
        val libFileName = currentLibFileName()
        val cached = extractedFile(libFileName)
        val lock = extractLocks.computeIfAbsent(libFileName) { ReentrantLock() }
        lock.withLock {
            extractIfNeeded(target, libFileName, cached)
            if (loaded.add(libFileName)) {
                System.load(cached.toAbsolutePath().toString())
            }
        }
    }

    private fun extractIfNeeded(target: String, libFileName: String, cached: Path) {
        val inJarHash = readInJarSha256(target, libFileName)
        if (Files.exists(cached) && inJarHash.contentEquals(sha256OfFile(cached))) {
            if (isSecurelyOwned(cached)) return
        }
        Files.createDirectories(cached.parent, *posixOwnerOnlyAttrs())
        val temp = Files.createTempFile(cached.parent, "ksqlite-", ".tmp")
        try {
            writeFromJar(target, libFileName, temp)
            val writtenHash = sha256OfFile(temp)
            if (!inJarHash.contentEquals(writtenHash)) {
                error("ksqlite native library integrity check failed after extract")
            }
            tightenPermissions(temp)
            Files.move(
                temp,
                cached,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun readInJarSha256(target: String, libFileName: String): ByteArray {
        val zipPath = "/$target/$libFileName"
        val stream = NativeLoader::class.java.getResourceAsStream(zipPath)
            ?: error("ksqlite native library not found in jar at $zipPath")
        return stream.use { sha256OfStream(it) }
    }

    private fun writeFromJar(target: String, libFileName: String, targetFile: Path) {
        val zipPath = "/$target/$libFileName"
        val stream = NativeLoader::class.java.getResourceAsStream(zipPath)
            ?: error("ksqlite native library not found in jar at $zipPath")
        stream.use { input -> Files.newOutputStream(targetFile).use { input.copyTo(it) } }
    }

    private fun sha256OfFile(path: Path): ByteArray = Files.newInputStream(path).use { sha256OfStream(it) }

    private fun sha256OfStream(input: InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var n = input.read(buf)
        while (n >= 0) {
            if (n > 0) digest.update(buf, 0, n)
            n = input.read(buf)
        }
        return digest.digest()
    }

    private fun tightenPermissions(file: Path) {
        try {
            val f = file.toFile()
            f.setReadable(true, true)
            f.setWritable(true, true)
            f.setExecutable(true, true)
        } catch (_: SecurityException) { /* best-effort */ }
        try {
            val updated = EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            )
            Files.setPosixFilePermissions(file, updated)
        } catch (_: UnsupportedOperationException) { /* not POSIX */ }
    }

    private fun isSecurelyOwned(file: Path): Boolean = try {
        val perms = Files.getPosixFilePermissions(file)
        val allowed = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        perms.all { it in allowed }
    } catch (_: UnsupportedOperationException) {
        true
    } catch (_: java.io.IOException) {
        false
    }

    private fun posixOwnerOnlyAttrs(): Array<FileAttribute<*>> = try {
        arrayOf(
            PosixFilePermissions.asFileAttribute(
                EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            ),
        )
    } catch (_: UnsupportedOperationException) {
        emptyArray()
    }

    private fun currentResource(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        return when {
            os.contains("linux") && (arch.contains("amd64") || arch == "x86_64") -> "linux_x64"
            os.contains("linux") && (arch.contains("aarch64") || arch.contains("arm64")) -> "linux_arm64"
            os.contains("mac") && (arch.contains("amd64") || arch == "x86_64") -> "macos_x64"
            os.contains("mac") && (arch.contains("aarch64") || arch.contains("arm64")) -> "macos_arm64"
            os.contains("windows") && (arch.contains("amd64") || arch == "x86_64") -> "mingw_x64"
            else -> error("Unsupported OS/arch: $os / $arch")
        }
    }

    private fun currentLibFileName(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("windows") -> "libksqlite.dll"
            os.contains("mac") -> "libksqlite.dylib"
            else -> "libksqlite.so"
        }
    }

    private fun extractedFile(libFileName: String): Path {
        val base = System.getProperty("user.home") ?: System.getProperty("java.io.tmpdir")
        val os = System.getProperty("os.name").lowercase()
        val subdir = if (os.contains("windows")) "ksqlite" else ".cache/ksqlite"
        return Path.of(base, subdir, VERSION, libFileName)
    }
}