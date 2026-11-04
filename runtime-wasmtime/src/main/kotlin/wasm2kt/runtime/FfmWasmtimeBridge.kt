package wasm2kt.runtime

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
    //     component_id_ptr: *const c_char, // null: generic; else the typed component (ADR-0016)
    //     wasm_path_ptr: *const c_char,
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
    //     component_id_ptr: *const c_char,
    //     wasm_bytes_ptr: *const u8,
    //     wasm_bytes_len: usize,
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
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
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

    // extern "C" fn wasm2kt_alloc_bytes(len: usize) -> *mut u8
    val allocBytesHandle: MethodHandle = linker.downcallHandle(
        lookup.find("wasm2kt_alloc_bytes").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
    )

    // extern "C" fn wasm2kt_free_bytes(ptr: *mut u8, len: usize)
    val freeBytesHandle: MethodHandle = linker.downcallHandle(
        lookup.find("wasm2kt_free_bytes").orElseThrow(),
        FunctionDescriptor.ofVoid(
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
        ),
    )

    // extern "C" fn wasm2kt_session_resolve(
    //     session_ptr: *mut BridgeSession, name: *const c_char,
    //     out_index: *mut u32, out_ptr: *mut *mut u8, out_len: *mut usize,
    // ) -> i32
    val sessionResolveHandle: MethodHandle = linker.downcallHandle(
        lookup.find("wasm2kt_session_resolve").orElseThrow(),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
        ),
    )

    // extern "C" fn wasm2kt_session_invoke(
    //     session_ptr: *mut BridgeSession, index: u32, args_ptr: *const u8, args_len: usize,
    //     out_ptr: *mut *mut u8, out_len: *mut usize,
    // ) -> i32
    val sessionInvokeHandle: MethodHandle = linker.downcallHandle(
        lookup.find("wasm2kt_session_invoke").orElseThrow(),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
        ),
    )

    // extern "C" fn wasm2kt_session_drop_resource(
    //     session_ptr: *mut BridgeSession, handle: i32, out_ptr: *mut *mut u8, out_len: *mut usize,
    // ) -> i32
    val sessionDropResourceHandle: MethodHandle = linker.downcallHandle(
        lookup.find("wasm2kt_session_drop_resource").orElseThrow(),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
        ),
    )

    // extern "C" fn wasm2kt_session_close(session_ptr: *mut BridgeSession)
    val sessionCloseHandle: MethodHandle = linker.downcallHandle(
        lookup.find("wasm2kt_session_close").orElseThrow(),
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
     *
     * With a [componentId], the session uses that component's typed calls, which the bridge library
     * must contain (a typed bridge, ADR-0016); without one, every call goes through the generic path.
     */
    fun openSession(wasmPath: Path, imports: Any?, componentId: String? = null): Long =
        openSession(wasmPath, imports, precompiled = false, componentId)

    /**
     * Opens a session on a `.cwasm` written by [precompileComponent] **with this bridge's Wasmtime build**.
     *
     * The file's native code runs without validation, so pass only files your own build produced
     * or that come from an equally trusted source; never one a user or the network supplied.
     */
    fun openPrecompiledSession(cwasmPath: Path, imports: Any?, componentId: String? = null): Long =
        openSession(cwasmPath, imports, precompiled = true, componentId)

    private fun openSession(wasmPath: Path, imports: Any?, precompiled: Boolean, componentId: String?): Long {
        val sessionArena = Arena.ofShared()
        try {
            val idPtr = componentId?.let { allocateUtf8CString(sessionArena, it) } ?: MemorySegment.NULL
            val wasmPathPtr = allocateUtf8CString(sessionArena, wasmPath.toAbsolutePath().toString())
            val callbackStub = createHostCallbackStub(sessionArena, imports)
            val errCap = 4096L
            val errBuf = sessionArena.allocate(errCap)
            val ptr = sessionOpenHandle.invokeExact(
                idPtr,
                wasmPathPtr,
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
    fun openSessionBytes(wasmBytes: ByteArray, imports: Any?, componentId: String? = null): Long =
        openSessionBytes(wasmBytes, imports, precompiled = false, componentId)

    /** Like [openPrecompiledSession] for `.cwasm` bytes in memory; the same trust requirement applies. */
    fun openPrecompiledSessionBytes(cwasmBytes: ByteArray, imports: Any?, componentId: String? = null): Long =
        openSessionBytes(cwasmBytes, imports, precompiled = true, componentId)

    private fun openSessionBytes(wasmBytes: ByteArray, imports: Any?, precompiled: Boolean, componentId: String?): Long {
        val sessionArena = Arena.ofShared()
        try {
            val idPtr = componentId?.let { allocateUtf8CString(sessionArena, it) } ?: MemorySegment.NULL
            val wasmBytesPtr = allocateBytes(sessionArena, wasmBytes)
            val callbackStub = createHostCallbackStub(sessionArena, imports)
            val errCap = 4096L
            val errBuf = sessionArena.allocate(errCap)
            val ptr = sessionOpenBytesHandle.invokeExact(
                idPtr,
                wasmBytesPtr,
                wasmBytes.size.toLong(),
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

    /**
     * The index of an exported function of the session's instance (ADR-0011 names), to pass to [invoke].
     *
     * @throws IllegalArgumentException if no export matches or the name is ambiguous.
     */
    fun resolve(sessionHandle: Long, functionName: String): Int {
        val state = acquireSession(sessionHandle)
        try {
            Arena.ofConfined().use { callArena ->
                val namePtr = allocateUtf8CString(callArena, functionName)
                val outIndex = callArena.allocate(ValueLayout.JAVA_INT)
                val out = NativeOut(callArena)
                val status = sessionResolveHandle.invokeExact(state.sessionPtr, namePtr, outIndex, out.ptr, out.len) as Int
                if (status != 0) throw IllegalArgumentException(out.takeMessage())
                return outIndex.get(ValueLayout.JAVA_INT, 0L)
            }
        } finally {
            releaseSession(state)
        }
    }

    /**
     * Calls the export [function] with the arguments in [args] (ADR-0015) and returns its results.
     *
     * @throws IllegalStateException with the bridge's message if the call fails or traps.
     */
    fun invoke(sessionHandle: Long, function: Int, args: WitWriter): WitReader {
        val state = acquireSession(sessionHandle)
        try {
            Arena.ofConfined().use { callArena ->
                val argsSeg = callArena.allocate(maxOf(args.size, 1).toLong())
                MemorySegment.copy(args.buffer, 0, argsSeg, ValueLayout.JAVA_BYTE, 0L, args.size)
                val out = NativeOut(callArena)
                val status = sessionInvokeHandle.invokeExact(state.sessionPtr, function, argsSeg, args.size.toLong(), out.ptr, out.len) as Int
                if (status != 0) throw IllegalStateException(out.takeMessage())
                return WitReader(out.takeBytes())
            }
        } finally {
            releaseSession(state)
        }
    }

    /** Drops a guest resource handle the host holds, running its destructor. */
    fun dropResource(sessionHandle: Long, handle: Int) {
        val state = acquireSession(sessionHandle)
        try {
            Arena.ofConfined().use { callArena ->
                val out = NativeOut(callArena)
                val status = sessionDropResourceHandle.invokeExact(state.sessionPtr, handle, out.ptr, out.len) as Int
                if (status != 0) throw IllegalStateException(out.takeMessage())
            }
        } finally {
            releaseSession(state)
        }
    }

    /** A `*mut *mut u8` / `*mut usize` pair the bridge fills with a buffer it allocated (freed here). */
    private inner class NativeOut(arena: Arena) {
        val ptr: MemorySegment = arena.allocate(ValueLayout.ADDRESS).also { it.set(ValueLayout.ADDRESS, 0L, MemorySegment.NULL) }
        val len: MemorySegment = arena.allocate(ValueLayout.JAVA_LONG).also { it.set(ValueLayout.JAVA_LONG, 0L, 0L) }

        fun takeBytes(): ByteArray {
            val p = ptr.get(ValueLayout.ADDRESS, 0L)
            val n = len.get(ValueLayout.JAVA_LONG, 0L)
            if (p == MemorySegment.NULL) return ByteArray(0)
            try {
                val bytes = ByteArray(n.toInt())
                MemorySegment.copy(p.reinterpret(n), ValueLayout.JAVA_BYTE, 0L, bytes, 0, n.toInt())
                return bytes
            } finally {
                freeBytesHandle.invokeExact(p, n)
            }
        }

        fun takeMessage(): String = String(takeBytes(), StandardCharsets.UTF_8).ifEmpty { "Unknown Wasmtime error" }
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
        val imports = importsInstance as? WitHostImportDispatcher
            ?: throw IllegalArgumentException("Host imports ${importsInstance::class.qualifiedName} do not implement WitHostImportDispatcher")
        val handler = HostCallbackDispatcher(imports, ::allocateNativeResponse)
        val callbackMethodHandle = MethodHandles.lookup().bind(
            handler,
            "onHostCall",
            MethodType.methodType(
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                MemorySegment::class.java,
                MemorySegment::class.java,
                Long::class.javaPrimitiveType,
                MemorySegment::class.java,
                MemorySegment::class.java,
            ),
        )
        // fn(import_index: u32, import_key, args_ptr, args_len, out_ptr: *mut *mut u8, out_len: *mut usize) -> i32
        val descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
        )
        return linker.upcallStub(callbackMethodHandle, descriptor, sessionArena)
    }

    /** Copies [bytes] into memory allocated by the bridge (`wasm2kt_alloc_bytes`), which Rust frees. */
    private fun allocateNativeResponse(bytes: ByteArray, size: Int): MemorySegment {
        val ptr = allocBytesHandle.invokeExact(size.toLong()) as MemorySegment
        check(ptr != MemorySegment.NULL) { "Could not allocate $size bytes for a host import response" }
        MemorySegment.copy(bytes, 0, ptr.reinterpret(size.toLong()), ValueLayout.JAVA_BYTE, 0L, size)
        return ptr
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

/**
 * Runs a host import for the native bridge (ADR-0015). The bridge passes the import's index and key
 * and the arguments; the results (or an error message when the status is non-zero) go into memory
 * from [allocate], whose address and length are stored in `outPtrPtr` / `outLenPtr`, so responses of
 * any size cross the boundary (#35). Host functions are looked up by key once per index.
 */
internal class HostCallbackDispatcher(
    private val imports: WitHostImportDispatcher,
    private val allocate: (ByteArray, Int) -> MemorySegment,
) {
    private val functions = ArrayList<HostFunction?>()

    fun onHostCall(
        importIndex: Int,
        importKeyPtr: MemorySegment,
        argsPtr: MemorySegment,
        argsLen: Long,
        outPtrPtr: MemorySegment,
        outLenPtr: MemorySegment,
    ): Int {
        return try {
            val function = function(importIndex) { FfmWasmtimeBridge.readNullTerminatedUtf8(importKeyPtr) }
            val args = ByteArray(argsLen.toInt())
            if (argsLen > 0) MemorySegment.copy(argsPtr.reinterpret(argsLen), ValueLayout.JAVA_BYTE, 0L, args, 0, args.size)
            val results = WitWriter()
            function.call(WitReader(args), results)
            respond(results.buffer, results.size, outPtrPtr, outLenPtr)
            0
        } catch (t: Throwable) {
            runCatching {
                val message = (t.message ?: t.toString()).toByteArray(StandardCharsets.UTF_8)
                respond(message, message.size, outPtrPtr, outLenPtr)
            }
            1
        }
    }

    private fun function(index: Int, key: () -> String): HostFunction {
        functions.getOrNull(index)?.let { return it }
        val name = key()
        val function = imports.hostFunction(name) ?: throw IllegalStateException("Unknown host import: $name")
        while (functions.size <= index) functions.add(null)
        functions[index] = function
        return function
    }

    private fun respond(bytes: ByteArray, size: Int, outPtrPtr: MemorySegment, outLenPtr: MemorySegment) {
        if (size == 0) return
        val ptr = allocate(bytes, size)
        outPtrPtr.reinterpret(ValueLayout.ADDRESS.byteSize()).set(ValueLayout.ADDRESS, 0L, ptr)
        outLenPtr.reinterpret(ValueLayout.JAVA_LONG.byteSize()).set(ValueLayout.JAVA_LONG, 0L, size.toLong())
    }
}
