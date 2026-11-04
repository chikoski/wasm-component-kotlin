package wasm2kt.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Runs host imports for the JNI bridge (ADR-0015): the bridge passes the import's index and key and
 * the arguments, and receives the results. An exception is reported to the bridge as the call's error.
 * Host functions are looked up by key once per index.
 */
class JniHostCallback(private val imports: WitHostImportDispatcher) {
    private val functions = ArrayList<HostFunction?>()

    fun onHostCall(importIndex: Int, importKey: String, args: ByteArray): ByteArray {
        val function = functions.getOrNull(importIndex) ?: run {
            val found = imports.hostFunction(importKey) ?: throw IllegalStateException("Unknown host import: $importKey")
            while (functions.size <= importIndex) functions.add(null)
            functions[importIndex] = found
            found
        }
        val results = WitWriter()
        function.call(WitReader(args), results)
        return results.toByteArray()
    }
}

/**
 * JNI-based Wasmtime Component Model runtime bridge compatible with Android ART (`arm64-v8a`, `x86_64`)
 * as well as desktop/server JVMs without requiring `java.lang.foreign.*` (FFM).
 */
object JniWasmtimeBridge {
    @Volatile
    private var loaded: Boolean = false

    fun ensureLoaded(explicitPath: Path? = null) {
        if (loaded && explicitPath == null) return
        synchronized(this) {
            if (loaded && explicitPath == null) return
            if (explicitPath != null) {
                System.load(explicitPath.toAbsolutePath().toString())
                loaded = true
                return
            }
            val candidateFile = resolveLocalLibraryFileOrNull()
            if (candidateFile != null) {
                System.load(candidateFile.absolutePath)
            } else {
                // Standard Android APK/AAB path: loads `libwasm2kt_wasmtime_bridge.so` from `jniLibs/<abi>/`
                System.loadLibrary("wasm2kt_wasmtime_bridge")
            }
            loaded = true
        }
    }

    private fun resolveLocalLibraryFileOrNull(): File? {
        System.getProperty("wasm2kt.bridge.lib")?.let { prop ->
            val f = File(prop)
            if (f.exists()) return f.absoluteFile
        }
        System.getenv("WASM2KT_BRIDGE_LIB")?.let { env ->
            val f = File(env)
            if (f.exists()) return f.absoluteFile
        }
        val osName = System.getProperty("os.name")?.lowercase().orEmpty()
        val libFileName = when {
            "mac" in osName || "darwin" in osName -> "libwasm2kt_wasmtime_bridge.dylib"
            "win" in osName -> "wasm2kt_wasmtime_bridge.dll"
            else -> "libwasm2kt_wasmtime_bridge.so"
        }
        val candidates = listOf(
            File("rust-bridge/target/release/$libFileName"),
            File("rust-bridge/target/debug/$libFileName"),
            File("../rust-bridge/target/release/$libFileName"),
        )
        return candidates.firstOrNull { it.exists() }?.absoluteFile
    }

    /** Opens a session on a `.wasm` component; a precompiled `.cwasm` is rejected (see [openPrecompiledSession]). */
    fun openSession(wasmPath: Path, imports: Any?, componentId: String? = null): Long =
        openSession(wasmPath, imports, precompiled = false, componentId)

    /**
     * Opens a session on a `.cwasm` written by [precompileComponent] with this bridge's Wasmtime build.
     * Its native code runs without validation: pass only files from a trusted source.
     */
    fun openPrecompiledSession(cwasmPath: Path, imports: Any?, componentId: String? = null): Long =
        openSession(cwasmPath, imports, precompiled = true, componentId)

    private fun openSession(wasmPath: Path, imports: Any?, precompiled: Boolean, componentId: String?): Long {
        ensureLoaded()
        val callback = when (imports) {
            null -> null
            is WitHostImportDispatcher -> JniHostCallback(imports)
            else -> throw IllegalArgumentException("Host imports ${imports::class.qualifiedName} do not implement WitHostImportDispatcher")
        }
        val ptr = nativeSessionOpen(componentId, wasmPath.toAbsolutePath().toString(), callback, precompiled)
        check(ptr != 0L) { "Failed to open JNI Wasmtime session from $wasmPath" }
        return ptr
    }

    /** Like [openSession] for a `.wasm` component in memory; precompiled bytes are rejected. */
    fun openSessionBytes(wasmBytes: ByteArray, imports: Any?, componentId: String? = null): Long =
        openSessionBytes(wasmBytes, imports, precompiled = false, componentId)

    /** Like [openPrecompiledSession] for `.cwasm` bytes in memory; the same trust requirement applies. */
    fun openPrecompiledSessionBytes(cwasmBytes: ByteArray, imports: Any?, componentId: String? = null): Long =
        openSessionBytes(cwasmBytes, imports, precompiled = true, componentId)

    private fun openSessionBytes(wasmBytes: ByteArray, imports: Any?, precompiled: Boolean, componentId: String?): Long {
        ensureLoaded()
        val callback = when (imports) {
            null -> null
            is WitHostImportDispatcher -> JniHostCallback(imports)
            else -> throw IllegalArgumentException("Host imports ${imports::class.qualifiedName} do not implement WitHostImportDispatcher")
        }
        val ptr = nativeSessionOpenBytes(componentId, wasmBytes, callback, precompiled)
        check(ptr != 0L) { "Failed to open JNI Wasmtime session from ByteArray" }
        return ptr
    }

    fun precompileComponent(wasmBytes: ByteArray): ByteArray {
        ensureLoaded()
        return nativePrecompileComponent(wasmBytes)
    }

    fun precompileComponent(wasmPath: Path): ByteArray =
        precompileComponent(Files.readAllBytes(wasmPath))

    fun precompileComponentToFile(wasmPath: Path, outputCwasmPath: Path): Path {
        val cwasmBytes = precompileComponent(wasmPath)
        Files.write(outputCwasmPath, cwasmBytes)
        return outputCwasmPath
    }

    /** The index of an exported function (ADR-0011 names), to pass to [invoke]. */
    fun resolve(sessionPtr: Long, functionName: String): Int = nativeSessionResolve(sessionPtr, functionName)

    /** Calls the export [function] with the arguments in [args] (ADR-0015) and returns its results. */
    fun invoke(sessionPtr: Long, function: Int, args: WitWriter): WitReader =
        WitReader(nativeSessionInvoke(sessionPtr, function, args.buffer, args.size))

    /** Drops a guest resource handle the host holds, running its destructor. */
    fun dropResource(sessionPtr: Long, handle: Int) = nativeSessionDropResource(sessionPtr, handle)

    @JvmStatic
    external fun nativeSessionOpen(
        componentId: String?,
        wasmPath: String,
        hostCallback: JniHostCallback?,
        precompiled: Boolean,
    ): Long

    @JvmStatic
    external fun nativeSessionOpenBytes(
        componentId: String?,
        wasmBytes: ByteArray,
        hostCallback: JniHostCallback?,
        precompiled: Boolean,
    ): Long

    /** Throws [IllegalArgumentException] if no export matches or the name is ambiguous. */
    @JvmStatic
    external fun nativeSessionResolve(sessionPtr: Long, functionName: String): Int

    /** Throws [IllegalStateException] with the bridge's message if the call fails or traps. */
    @JvmStatic
    external fun nativeSessionInvoke(sessionPtr: Long, function: Int, args: ByteArray, argsLength: Int): ByteArray

    @JvmStatic
    external fun nativeSessionDropResource(sessionPtr: Long, handle: Int)

    @JvmStatic
    external fun nativeSessionClose(sessionPtr: Long)

    @JvmStatic
    external fun nativePrecompileComponent(wasmBytes: ByteArray): ByteArray
}
