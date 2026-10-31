package wasm2kt.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Native Wasmtime Component Model runtime bridge using Java 22+ / 21 Foreign Function & Memory (FFM) API
 * (`java.lang.foreign.*`) backed by `libwasm2kt_wasmtime_bridge`.
 */
class FfmWasmtimeBridge private constructor(
    libraryPath: Path,
) {
    private val arena: Arena = Arena.ofShared()
    private val linker: Linker = Linker.nativeLinker()
    private val lookup: SymbolLookup = SymbolLookup.libraryLookup(libraryPath, arena)

    // extern "C" fn wasm2kt_session_open(
    //     wasm_path_ptr: *const c_char,
    //     imports_json_ptr: *const c_char,
    //     host_callback: Option<HostCallbackFn>,
    //     precompiled: bool,
    //     err_buf: *mut c_char,
    //     err_buf_cap: usize,
    // ) -> *mut BridgeSession
    val sessionOpenHandle: MethodHandle = linker.downcallHandle(
        lookup.find("wasm2kt_session_open").orElseThrow(),
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_BOOLEAN,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
        ),
    )

    // extern "C" fn wasm2kt_session_open_bytes(
    //     wasm_bytes_ptr: *const u8,
    //     wasm_bytes_len: usize,
    //     imports_json_ptr: *const c_char,
    //     host_callback: Option<HostCallbackFn>,
    //     precompiled: bool,
    //     err_buf: *mut c_char,
    //     err_buf_cap: usize,
    // ) -> *mut BridgeSession
    val sessionOpenBytesHandle: MethodHandle = linker.downcallHandle(
        lookup.find("wasm2kt_session_open_bytes").orElseThrow(),
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_BOOLEAN,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
        ),
    )

    // extern "C" fn wasm2kt_precompile_component(
    //     wasm_bytes_ptr: *const u8,
    //     wasm_bytes_len: usize,
    //     out_len_ptr: *mut usize,
    //     err_buf: *mut c_char,
    //     err_buf_cap: usize,
    // ) -> *mut u8
    val precompileComponentHandle: MethodHandle = linker.downcallHandle(
        lookup.find("wasm2kt_precompile_component").orElseThrow(),
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
        ),
    )

    // extern "C" fn wasm2kt_free_bytes(ptr: *mut u8, len: usize)
    val freeBytesHandle: MethodHandle = linker.downcallHandle(
        lookup.find("wasm2kt_free_bytes").orElseThrow(),
        FunctionDescriptor.ofVoid(
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
        ),
    )

    // extern "C" fn wasm2kt_session_invoke(
    //     session_ptr: *mut BridgeSession,
    //     func_spec_ptr: *const c_char,
    //     args_json_ptr: *const c_char,
    // ) -> *mut c_char
    val sessionInvokeHandle: MethodHandle = linker.downcallHandle(
        lookup.find("wasm2kt_session_invoke").orElseThrow(),
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
        ),
    )

    // extern "C" fn wasm2kt_session_drop_resource(
    //     session_ptr: *mut BridgeSession,
    //     handle: i32,
    // ) -> *mut c_char
    val sessionDropResourceHandle: MethodHandle = linker.downcallHandle(
        lookup.find("wasm2kt_session_drop_resource").orElseThrow(),
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
        ),
    )

    // extern "C" fn wasm2kt_session_close(session_ptr: *mut BridgeSession)
    val sessionCloseHandle: MethodHandle = linker.downcallHandle(
        lookup.find("wasm2kt_session_close").orElseThrow(),
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
    )

    // extern "C" fn wasm2kt_free_string(ptr: *mut c_char)
    val freeStringHandle: MethodHandle = linker.downcallHandle(
        lookup.find("wasm2kt_free_string").orElseThrow(),
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
    )

    /**
     * Per-session FFM state. The [sessionArena] owns the host-callback upcall stub, so it must not be
     * closed while a native call (and therefore possibly an upcall) is in flight. Closing is deferred
     * until the last active call returns.
     */
    private class FfmSessionState(
        val sessionArena: Arena,
        val sessionPtr: MemorySegment,
    ) {
        var activeCalls: Int = 0
        var closeRequested: Boolean = false
        var arenaClosed: Boolean = false
    }

    private fun acquireSession(sessionHandle: Long): FfmSessionState {
        val state = activeSessions[sessionHandle]
            ?: throw IllegalStateException("FfmWasmtimeSession ($sessionHandle) is not active or already closed")
        synchronized(state) {
            check(!state.closeRequested) { "FfmWasmtimeSession ($sessionHandle) is already closed" }
            state.activeCalls++
        }
        return state
    }

    private fun releaseSession(state: FfmSessionState) {
        val shouldCloseArena = synchronized(state) {
            state.activeCalls--
            if (state.activeCalls == 0 && state.closeRequested && !state.arenaClosed) {
                state.arenaClosed = true
                true
            } else {
                false
            }
        }
        if (shouldCloseArena) state.sessionArena.close()
    }

    private val activeSessions = java.util.concurrent.ConcurrentHashMap<Long, FfmSessionState>()

    /**
     * Opens a session on the `.wasm` component at [wasmPath]. A precompiled `.cwasm` is rejected:
     * it holds native code Wasmtime does not validate, so it must go through [openPrecompiledSession].
     */
    fun openSession(wasmPath: Path, imports: Any?): Long = openSession(wasmPath, imports, precompiled = false)

    /**
     * Opens a session on a `.cwasm` written by [precompileComponent] **with this bridge's Wasmtime build**.
     *
     * The file's native code runs without validation, so pass only files your own build produced
     * or that come from an equally trusted source; never one a user or the network supplied.
     */
    fun openPrecompiledSession(cwasmPath: Path, imports: Any?): Long = openSession(cwasmPath, imports, precompiled = true)

    private fun openSession(wasmPath: Path, imports: Any?, precompiled: Boolean): Long {
        val sessionArena = Arena.ofShared()
        try {
            val wasmPathPtr = allocateUtf8CString(sessionArena, wasmPath.toAbsolutePath().toString())
            val importsJsonPtr = allocateUtf8CString(sessionArena, "[]")
            val callbackStub = createHostCallbackStub(sessionArena, imports)
            val errCap = 4096L
            val errBuf = sessionArena.allocate(errCap)
            val ptr = sessionOpenHandle.invokeExact(
                wasmPathPtr,
                importsJsonPtr,
                callbackStub,
                precompiled,
                errBuf,
                errCap,
            ) as MemorySegment
            if (ptr == MemorySegment.NULL) {
                val errMsg = readNullTerminatedUtf8(errBuf)
                throw IllegalStateException("Failed to open Wasmtime session: $errMsg")
            }
            val handle = ptr.address()
            activeSessions[handle] = FfmSessionState(sessionArena, ptr)
            return handle
        } catch (t: Throwable) {
            sessionArena.close()
            throw t
        }
    }

    /** Like [openSession] for a `.wasm` component in memory; precompiled bytes are rejected. */
    fun openSessionBytes(wasmBytes: ByteArray, imports: Any?): Long = openSessionBytes(wasmBytes, imports, precompiled = false)

    /** Like [openPrecompiledSession] for `.cwasm` bytes in memory; the same trust requirement applies. */
    fun openPrecompiledSessionBytes(cwasmBytes: ByteArray, imports: Any?): Long =
        openSessionBytes(cwasmBytes, imports, precompiled = true)

    private fun openSessionBytes(wasmBytes: ByteArray, imports: Any?, precompiled: Boolean): Long {
        val sessionArena = Arena.ofShared()
        try {
            val wasmBytesPtr = allocateBytes(sessionArena, wasmBytes)
            val importsJsonPtr = allocateUtf8CString(sessionArena, "[]")
            val callbackStub = createHostCallbackStub(sessionArena, imports)
            val errCap = 4096L
            val errBuf = sessionArena.allocate(errCap)
            val ptr = sessionOpenBytesHandle.invokeExact(
                wasmBytesPtr,
                wasmBytes.size.toLong(),
                importsJsonPtr,
                callbackStub,
                precompiled,
                errBuf,
                errCap,
            ) as MemorySegment
            if (ptr == MemorySegment.NULL) {
                val errMsg = readNullTerminatedUtf8(errBuf)
                throw IllegalStateException("Failed to open Wasmtime session from bytes: $errMsg")
            }
            val handle = ptr.address()
            activeSessions[handle] = FfmSessionState(sessionArena, ptr)
            return handle
        } catch (t: Throwable) {
            sessionArena.close()
            throw t
        }
    }

    fun sessionInvoke(sessionHandle: Long, functionName: String, argsJson: String): String {
        val state = acquireSession(sessionHandle)
        try {
            Arena.ofConfined().use { callArena ->
                val fnPtr = allocateUtf8CString(callArena, functionName)
                val argsPtr = allocateUtf8CString(callArena, argsJson)
                val respPtr = sessionInvokeHandle.invokeExact(state.sessionPtr, fnPtr, argsPtr) as MemorySegment
                return readAndFreeCString(respPtr)
            }
        } finally {
            releaseSession(state)
        }
    }

    fun sessionDropResource(sessionHandle: Long, handle: Int): String {
        val state = acquireSession(sessionHandle)
        try {
            val respPtr = sessionDropResourceHandle.invokeExact(state.sessionPtr, handle) as MemorySegment
            return readAndFreeCString(respPtr)
        } finally {
            releaseSession(state)
        }
    }

    /**
     * Closes the native session. Safe to call multiple times. When invoked re-entrantly from a host
     * callback, the native session is marked closed immediately but the Arena (which owns the upcall
     * stub currently executing) is released only after the outer call returns.
     */
    fun sessionClose(sessionHandle: Long) {
        val state = activeSessions.remove(sessionHandle) ?: return
        val closeArenaNow = synchronized(state) {
            state.closeRequested = true
            if (state.activeCalls == 0 && !state.arenaClosed) {
                state.arenaClosed = true
                true
            } else {
                false
            }
        }
        try {
            sessionCloseHandle.invokeExact(state.sessionPtr)
        } finally {
            if (closeArenaNow) state.sessionArena.close()
        }
    }

    fun createHostCallbackStub(sessionArena: Arena, importsInstance: Any?): MemorySegment {
        if (importsInstance == null) return MemorySegment.NULL
        val handler = HostCallbackDispatcher(importsInstance)
        val callbackMethodHandle = MethodHandles.lookup().bind(
            handler,
            "onHostCall",
            MethodType.methodType(
                Int::class.javaPrimitiveType,
                MemorySegment::class.java,
                MemorySegment::class.java,
                MemorySegment::class.java,
                Long::class.javaPrimitiveType,
            ),
        )
        val descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
        )
        return linker.upcallStub(callbackMethodHandle, descriptor, sessionArena)
    }

    fun readAndFreeCString(ptr: MemorySegment): String {
        if (ptr == MemorySegment.NULL) return ""
        try {
            return readNullTerminatedUtf8(ptr)
        } finally {
            freeStringHandle.invokeExact(ptr)
        }
    }

    /**
     * Precompiles a raw `.wasm` component binary into a serialized AOT `.cwasm` image (`ByteArray`)
     * that can be loaded with zero JIT compilation overhead via [openPrecompiledSessionBytes] or [openPrecompiledSession].
     */
    fun precompileComponent(wasmBytes: ByteArray): ByteArray {
        Arena.ofConfined().use { arena ->
            val inPtr = allocateBytes(arena, wasmBytes)
            val outLenSeg = arena.allocate(ValueLayout.JAVA_LONG)
            val errCap = 4096L
            val errBuf = arena.allocate(errCap)
            val outPtr = precompileComponentHandle.invokeExact(
                inPtr,
                wasmBytes.size.toLong(),
                outLenSeg,
                errBuf,
                errCap,
            ) as MemorySegment
            if (outPtr == MemorySegment.NULL) {
                val errMsg = readNullTerminatedUtf8(errBuf)
                throw IllegalStateException("Failed to precompile Wasm component: $errMsg")
            }
            val outLen = outLenSeg.get(ValueLayout.JAVA_LONG, 0L)
            try {
                val slice = outPtr.reinterpret(outLen)
                val outBytes = ByteArray(outLen.toInt())
                MemorySegment.copy(slice, ValueLayout.JAVA_BYTE, 0L, outBytes, 0, outLen.toInt())
                return outBytes
            } finally {
                freeBytesHandle.invokeExact(outPtr, outLen)
            }
        }
    }

    fun precompileComponent(wasmPath: Path): ByteArray =
        precompileComponent(Files.readAllBytes(wasmPath))

    fun precompileComponentToFile(wasmPath: Path, outputCwasmPath: Path): Path {
        val cwasmBytes = precompileComponent(wasmPath)
        Files.write(outputCwasmPath, cwasmBytes)
        return outputCwasmPath
    }

    companion object {
        fun allocateBytes(arena: Arena, bytes: ByteArray): MemorySegment {
            if (bytes.isEmpty()) return arena.allocate(1L)
            val seg = arena.allocate(bytes.size.toLong())
            MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0L, bytes.size)
            return seg
        }

        fun allocateUtf8CString(arena: Arena, text: String): MemorySegment {
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            val seg = arena.allocate((bytes.size + 1).toLong())
            MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0L, bytes.size)
            seg.set(ValueLayout.JAVA_BYTE, bytes.size.toLong(), 0.toByte())
            return seg
        }

        fun readNullTerminatedUtf8(ptr: MemorySegment): String {
            if (ptr == MemorySegment.NULL) return ""
            val unbounded = ptr.reinterpret(Long.MAX_VALUE)
            var len = 0L
            while (unbounded.get(ValueLayout.JAVA_BYTE, len) != 0.toByte()) {
                len++
            }
            if (len == 0L) return ""
            val bytes = ByteArray(len.toInt())
            MemorySegment.copy(unbounded, ValueLayout.JAVA_BYTE, 0L, bytes, 0, len.toInt())
            return String(bytes, StandardCharsets.UTF_8)
        }

        @Volatile
        private var defaultInstance: FfmWasmtimeBridge? = null

        fun getDefault(explicitPath: Path? = null): FfmWasmtimeBridge {
            explicitPath?.let { return FfmWasmtimeBridge(it) }
            defaultInstance?.let { return it }
            synchronized(this) {
                defaultInstance?.let { return it }
                val resolved = resolveBridgeLibraryPath()
                return FfmWasmtimeBridge(resolved).also { defaultInstance = it }
            }
        }

        fun resolveBridgeLibraryPath(): Path {
            System.getProperty("wasm2kt.bridge.lib")?.let { prop ->
                val p = Paths.get(prop)
                if (Files.exists(p)) return p.toAbsolutePath()
            }
            System.getenv("WASM2KT_BRIDGE_LIB")?.let { env ->
                val p = Paths.get(env)
                if (Files.exists(p)) return p.toAbsolutePath()
            }
            val osName = System.getProperty("os.name").lowercase()
            val libFileName = when {
                "mac" in osName || "darwin" in osName -> "libwasm2kt_wasmtime_bridge.dylib"
                "win" in osName -> "wasm2kt_wasmtime_bridge.dll"
                else -> "libwasm2kt_wasmtime_bridge.so"
            }
            val candidates = listOf(
                Paths.get("rust-bridge/target/release/$libFileName"),
                Paths.get("rust-bridge/target/debug/$libFileName"),
                Paths.get("../rust-bridge/target/release/$libFileName"),
            )
            for (candidate in candidates) {
                if (Files.exists(candidate)) {
                    return candidate.toAbsolutePath()
                }
            }
            throw IllegalStateException(
                "Could not locate $libFileName. Build it with `cargo build --release --manifest-path rust-bridge/Cargo.toml` " +
                    "or set -Dwasm2kt.bridge.lib=/path/to/$libFileName",
            )
        }
    }
}

internal class HostCallbackDispatcher(
    private val importsInstance: Any,
) {
    fun onHostCall(
        importNamePtr: MemorySegment,
        argsJsonPtr: MemorySegment,
        outBufPtr: MemorySegment,
        outBufCap: Long,
    ): Int {
        return try {
            val importName = FfmWasmtimeBridge.readNullTerminatedUtf8(importNamePtr)
            val argsJsonStr = FfmWasmtimeBridge.readNullTerminatedUtf8(argsJsonPtr)
            val argsArray = Json.parseToJsonElement(argsJsonStr).jsonArray

            val dispatcher = importsInstance as? WitHostImportDispatcher
                ?: error("Host imports object ${importsInstance::class.qualifiedName} does not implement WitHostImportDispatcher")
            val jsonElem = dispatcher.dispatchHostImport(importName, argsArray)

            if (jsonElem !is JsonNull) {
                val bytes = Json.encodeToString(JsonElement.serializer(), jsonElem).toByteArray(StandardCharsets.UTF_8)
                if (bytes.size >= outBufCap) {
                    error(
                        "Host import '$importName' response size (${bytes.size} bytes) exceeds callback buffer capacity ($outBufCap bytes)",
                    )
                }
                val outSlice = outBufPtr.reinterpret(outBufCap)
                MemorySegment.copy(bytes, 0, outSlice, ValueLayout.JAVA_BYTE, 0, bytes.size)
                outSlice.set(ValueLayout.JAVA_BYTE, bytes.size.toLong(), 0.toByte())
            } else if (outBufCap > 0) {
                outBufPtr.reinterpret(outBufCap).set(ValueLayout.JAVA_BYTE, 0L, 0.toByte())
            }
            0
        } catch (t: Throwable) {
            val msgBytes = (t.message ?: t.toString()).toByteArray(StandardCharsets.UTF_8)
            if (outBufCap > 1) {
                val outSlice = outBufPtr.reinterpret(outBufCap)
                val len = minOf(msgBytes.size, (outBufCap - 1).toInt())
                MemorySegment.copy(msgBytes, 0, outSlice, ValueLayout.JAVA_BYTE, 0, len)
                outSlice.set(ValueLayout.JAVA_BYTE, len.toLong(), 0.toByte())
            }
            1
        }
    }
}
