package pw.binom.db.ksqlite

/*
 * Android delivery: the four (or two) ABI-specific `libksqlite.so` files are
 * packaged into the AAR under `jni/<abi>/` by AGP (from the `jniLibs` tree
 * populated from kn-clang's bionic builds), so the platform installer places
 * them in the app's native library directory. Loading them by logical name is
 * the only supported way on modern Android — extracting a `.so` to app data
 * and calling `System.load` is blocked by the linker namespace and SELinux.
 */
internal actual fun loadNativeLibrary() {
    System.loadLibrary("ksqlite")
}
