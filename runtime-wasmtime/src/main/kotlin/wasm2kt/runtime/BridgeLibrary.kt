package wasm2kt.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * Finds the native Wasmtime bridge, generic or typed (ADR-0016). In order:
 * 1. `-Dwasm2kt.bridge.lib` or `WASM2KT_BRIDGE_LIB` (a file path)
 * 2. the class path: [resourcePath] for this JVM's platform, e.g.
 *    `META-INF/wasm2kt/native/linux-x86_64/libwasm2kt_wasmtime_bridge.so`, copied to a temporary
 *    file because the OS loads libraries from files only (#65). `wasm2kt bridge --build
 *    --resources-output` writes a typed bridge there
 * 3. `rust-bridge/target/{release,debug}/` from the working directory or its parent (this repository)
 *
 * `null` when none is found; the JNI bridge then falls back to `System.loadLibrary` (Android's `jniLibs`).
 */
object BridgeLibrary {
    const val NAME = "wasm2kt_wasmtime_bridge"

    /** `linux-x86_64`, `macos-aarch64`, `windows-x86_64`, ... from `os.name` / `os.arch`. */
    fun platform(osName: String = System.getProperty("os.name"), osArch: String = System.getProperty("os.arch")): String {
        val os = osName.lowercase()
        val osPart = when {
            "mac" in os || "darwin" in os -> "macos"
            "win" in os -> "windows"
            "linux" in os -> "linux"
            else -> os.replace(Regex("[^a-z0-9]+"), "")
        }
        val archPart = when (val arch = osArch.lowercase()) {
            "amd64", "x86_64", "x64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> arch.replace(Regex("[^a-z0-9_]+"), "")
        }
        return "$osPart-$archPart"
    }

    /** `lib<name>.so`, `lib<name>.dylib` or `<name>.dll` for a [platform]. */
    fun fileName(platform: String = platform()): String = when {
        platform.startsWith("macos") -> "lib$NAME.dylib"
        platform.startsWith("windows") -> "$NAME.dll"
        else -> "lib$NAME.so"
    }

    /** The class path resource of the bridge for a [platform] (no leading slash). */
    fun resourcePath(platform: String = platform()): String = "META-INF/wasm2kt/native/$platform/${fileName(platform)}"

    /** The bridge to load, or null if there is none (see the class documentation for the order). */
    fun locate(classLoader: ClassLoader? = BridgeLibrary::class.java.classLoader): Path? {
        System.getProperty("wasm2kt.bridge.lib")?.let { Paths.get(it) }?.takeIf { Files.exists(it) }?.let { return it.toAbsolutePath() }
        System.getenv("WASM2KT_BRIDGE_LIB")?.let { Paths.get(it) }?.takeIf { Files.exists(it) }?.let { return it.toAbsolutePath() }
        fromClassPath(classLoader)?.let { return it }
        val file = fileName()
        return listOf("rust-bridge/target/release", "rust-bridge/target/debug", "../rust-bridge/target/release")
            .map { Paths.get(it, file) }
            .firstOrNull { Files.exists(it) }
            ?.toAbsolutePath()
    }

    @Volatile
    private var extracted: Pair<String, Path>? = null

    private fun privateTempDirectory(): Path {
        val posix = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
        return if (posix) {
            Files.createTempDirectory(
                "wasm2kt-bridge",
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")),
            )
        } else {
            // On Windows the default temporary directory is already per user.
            Files.createTempDirectory("wasm2kt-bridge")
        }
    }

    /** Copies the class path's bridge for this platform to a temporary file, once per JVM. */
    fun fromClassPath(classLoader: ClassLoader?, platform: String = platform()): Path? {
        val resource = resourcePath(platform)
        val url = (classLoader ?: ClassLoader.getSystemClassLoader()).getResource(resource) ?: return null
        extracted?.takeIf { it.first == url.toString() && Files.exists(it.second) }?.let { return it.second }
        synchronized(this) {
            extracted?.takeIf { it.first == url.toString() && Files.exists(it.second) }?.let { return it.second }
            // Readable and writable by this user only, so no other user can swap the library before it is loaded.
            val dir = privateTempDirectory()
            val target = dir.resolve(fileName(platform))
            url.openStream().use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
            target.toFile().deleteOnExit()
            dir.toFile().deleteOnExit()
            extracted = url.toString() to target
            return target
        }
    }
}
