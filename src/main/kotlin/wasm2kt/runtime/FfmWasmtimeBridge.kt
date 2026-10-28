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
 * Implemented by generated WIT composite types (`Record`, `Variant`, `Enum`, `Flags`, `Resource`,
 * `WitOption`, `WitResult`, `TupleN`) to serialize directly to JSON without reflection.
 */
interface WitJsonSerializable {
    fun toWitJson(): JsonElement
}

/**
 * Implemented by generated `<World>.Imports` interfaces to dispatch host import calls from Wasmtime
 * to Kotlin methods without reflection.
 */
interface WitHostImportDispatcher {
    fun dispatchHostImport(importSpec: String, args: JsonArray): JsonElement =
        error("Unknown host import: $importSpec")
}

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

    companion object {
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

            if (jsonElem !is JsonNull && outBufCap > 1) {
                val bytes = Json.encodeToString(JsonElement.serializer(), jsonElem).toByteArray(StandardCharsets.UTF_8)
                val outSlice = outBufPtr.reinterpret(outBufCap)
                val len = minOf(bytes.size, (outBufCap - 1).toInt())
                MemorySegment.copy(bytes, 0, outSlice, ValueLayout.JAVA_BYTE, 0, len)
                outSlice.set(ValueLayout.JAVA_BYTE, len.toLong(), 0.toByte())
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

/**
 * Reflection-free JSON codec for WIT values.
 * Composite WIT types implement [WitJsonSerializable] for encoding and generate static `fromWitJson`
 * companion methods for decoding.
 */
object FfmJsonCodec {
    private fun toKebabCase(name: String): String =
        name.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2")
            .replace('_', '-')
            .lowercase()

    fun toJsonElement(value: Any?): JsonElement {
        if (value == null || value == Unit) return JsonNull
        return when (value) {
            is JsonElement -> value
            is WitJsonSerializable -> value.toWitJson()
            is Boolean -> JsonPrimitive(value)
            is Byte -> JsonPrimitive(value)
            is Short -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is UByte -> JsonPrimitive(value.toShort())
            is UShort -> JsonPrimitive(value.toInt())
            is UInt -> JsonPrimitive(value.toLong())
            is ULong -> JsonPrimitive(value.toString())
            is Float -> JsonPrimitive(value)
            is Double -> JsonPrimitive(value)
            is Char -> JsonPrimitive(value.toString())
            is String -> JsonPrimitive(value)
            is Enum<*> -> JsonPrimitive(toKebabCase(value.name))
            is List<*> -> JsonArray(value.map { toJsonElement(it) })
            else -> error("Unsupported WIT value for JSON serialization: ${value::class.qualifiedName}")
        }
    }

    fun encodeOptionSome(value: Any?): JsonElement =
        JsonObject(
            mapOf(
                "option" to JsonPrimitive("some"),
                "value" to toJsonElement(value),
            ),
        )

    fun encodeOptionNone(): JsonElement =
        JsonObject(mapOf("option" to JsonPrimitive("none")))

    fun encodeResultOk(value: Any?): JsonElement =
        JsonObject(
            mapOf(
                "result" to JsonPrimitive("ok"),
                "value" to toJsonElement(value),
            ),
        )

    fun encodeResultErr(error: Any?): JsonElement =
        JsonObject(
            mapOf(
                "result" to JsonPrimitive("err"),
                "error" to toJsonElement(error),
            ),
        )

    fun encodeTuple(vararg elements: Any?): JsonElement =
        JsonArray(elements.map { toJsonElement(it) })

    fun encodeRecord(vararg fields: Pair<String, Any?>): JsonElement =
        JsonObject(fields.associate { (k, v) -> k to toJsonElement(v) })

    fun encodeVariant(caseName: String, payload: Any? = null): JsonElement =
        JsonObject(
            mapOf(
                "case" to JsonPrimitive(caseName),
                "value" to toJsonElement(payload),
            ),
        )

    fun decodeUnit(element: JsonElement): Unit = Unit

    fun decodeBoolean(element: JsonElement): Boolean = element.jsonPrimitive.boolean

    fun decodeByte(element: JsonElement): Byte = element.jsonPrimitive.int.toByte()

    fun decodeUByte(element: JsonElement): UByte = element.jsonPrimitive.int.toUByte()

    fun decodeShort(element: JsonElement): Short = element.jsonPrimitive.int.toShort()

    fun decodeUShort(element: JsonElement): UShort = element.jsonPrimitive.int.toUShort()

    fun decodeInt(element: JsonElement): Int = element.jsonPrimitive.int

    fun decodeUInt(element: JsonElement): UInt = element.jsonPrimitive.long.toUInt()

    fun decodeLong(element: JsonElement): Long = element.jsonPrimitive.long

    fun decodeULong(element: JsonElement): ULong = element.jsonPrimitive.content.toULong()

    fun decodeFloat(element: JsonElement): Float = element.jsonPrimitive.float

    fun decodeDouble(element: JsonElement): Double = element.jsonPrimitive.double

    fun decodeChar(element: JsonElement): Char = element.jsonPrimitive.content.first()

    fun decodeString(element: JsonElement): String = element.jsonPrimitive.content

    inline fun <T> decodeList(element: JsonElement, itemDecoder: (JsonElement) -> T): List<T> =
        element.jsonArray.map(itemDecoder)

    fun asJsonObject(element: JsonElement): JsonObject = element.jsonObject

    fun asJsonArray(element: JsonElement): JsonArray = element.jsonArray

    fun field(obj: JsonObject, witName: String): JsonElement = obj[witName] ?: JsonNull

    fun variantCase(obj: JsonObject): String =
        obj["case"]?.jsonPrimitive?.content ?: error("Missing 'case' field in variant JSON: $obj")

    fun variantPayload(obj: JsonObject): JsonElement = obj["value"] ?: JsonNull

    fun isOptionNone(element: JsonElement): Boolean =
        element is JsonNull || (element is JsonObject && element["option"]?.jsonPrimitive?.content == "none")

    fun optionValue(element: JsonElement): JsonElement =
        if (element is JsonObject && "option" in element) {
            element["value"] ?: JsonNull
        } else {
            element
        }

    fun isResultOk(element: JsonElement): Boolean =
        element.jsonObject["result"]?.jsonPrimitive?.content == "ok"

    fun resultOkValue(element: JsonElement): JsonElement =
        element.jsonObject["value"] ?: JsonNull

    fun resultErrValue(element: JsonElement): JsonElement =
        element.jsonObject["error"] ?: JsonNull
}
