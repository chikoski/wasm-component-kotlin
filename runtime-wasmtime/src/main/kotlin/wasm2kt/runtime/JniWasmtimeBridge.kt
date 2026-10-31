package wasm2kt.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Callback wrapper passed into JNI so Wasmtime can invoke host `Imports` methods
 * on Android ART and standard JVMs without `java.lang.foreign` (FFM) or reflection.
 */
class JniHostCallback(
    private val importsInstance: Any?,
) {
    fun onHostCall(importName: String, argsJson: String): String {
        if (importsInstance == null) return ""
        val dispatcher = importsInstance as? WitHostImportDispatcher
            ?: error("Host imports object ${importsInstance::class.qualifiedName} does not implement WitHostImportDispatcher")
        val argsArray = Json.parseToJsonElement(argsJson).jsonArray
        val jsonElem = dispatcher.dispatchHostImport(importName, argsArray)
        return if (jsonElem is JsonNull) {
            ""
        } else {
            Json.encodeToString(JsonElement.serializer(), jsonElem)
        }
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

    fun openSession(wasmPath: Path, imports: Any?): Long {
        ensureLoaded()
        val callback = if (imports != null) JniHostCallback(imports) else null
        val ptr = nativeSessionOpen(wasmPath.toAbsolutePath().toString(), "[]", callback)
        check(ptr != 0L) { "Failed to open JNI Wasmtime session from $wasmPath" }
        return ptr
    }

    fun openSessionBytes(wasmBytes: ByteArray, imports: Any?): Long {
        ensureLoaded()
        val callback = if (imports != null) JniHostCallback(imports) else null
        val ptr = nativeSessionOpenBytes(wasmBytes, "[]", callback)
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

    @JvmStatic
    external fun nativeSessionOpen(
        wasmPath: String,
        importsJson: String,
        hostCallback: JniHostCallback?,
    ): Long

    @JvmStatic
    external fun nativeSessionOpenBytes(
        wasmBytes: ByteArray,
        importsJson: String,
        hostCallback: JniHostCallback?,
    ): Long

    @JvmStatic
    external fun nativeSessionInvoke(
        sessionPtr: Long,
        funcSpec: String,
        argsJson: String,
    ): String

    @JvmStatic
    external fun nativeSessionDropResource(
        sessionPtr: Long,
        handle: Int,
    ): String

    @JvmStatic
    external fun nativeSessionClose(sessionPtr: Long)

    @JvmStatic
    external fun nativePrecompileComponent(wasmBytes: ByteArray): ByteArray
}
