package wasm2kt.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import wasm2kt.generator.NamingConventions
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
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.companionObjectInstance
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.primaryConstructor

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

            val result = dispatchToKotlinImports(importsInstance, importName, argsArray)
            if (result != null && result != Unit && outBufCap > 1) {
                val jsonElem = FfmJsonCodec.toJsonElement(result)
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

    private fun dispatchToKotlinImports(
        rootImports: Any,
        importSpec: String,
        argsArray: JsonArray,
    ): Any? {
        val (targetReceiver, witFuncName) = if ('#' in importSpec) {
            val ifaceName = importSpec.substringBefore('#')
            val fnName = importSpec.substringAfter('#')
            val propName = NamingConventions.toCamelCase(ifaceName)
            val propGetter = rootImports.javaClass.methods.firstOrNull {
                it.name == "get${propName.replaceFirstChar { c -> c.uppercaseChar() }}" && it.parameterCount == 0
            } ?: error("Import interface property '$propName' not found on ${rootImports.javaClass.name}")
            propGetter.isAccessible = true
            val subReceiver = propGetter.invoke(rootImports)
            subReceiver to fnName
        } else {
            rootImports to importSpec
        }

        val methodName = NamingConventions.toCamelCase(witFuncName)
        val method = targetReceiver.javaClass.methods.firstOrNull {
            (it.name == methodName || it.name.startsWith("$methodName-")) && it.parameterCount == argsArray.size
        } ?: error("Host import method '$methodName' with ${argsArray.size} parameters not found on ${targetReceiver.javaClass.name}")

        method.isAccessible = true
        val convertedArgs = method.parameterTypes.mapIndexed { idx, paramClass ->
            FfmJsonCodec.fromJsonElementToClass(argsArray[idx], paramClass)
        }.toTypedArray()

        return method.invoke(targetReceiver, *convertedArgs)
    }
}

/**
 * Serializes Kotlin values (primitives, unsigned types, data classes, sealed interfaces, value classes,
 * WitOption, WitResult, TupleN, resource handles) to and from the JSON protocol expected by the Rust Wasmtime bridge.
 */
object FfmJsonCodec {
    private fun toKebabCase(name: String): String =
        name.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2")
            .replace('_', '-')
            .lowercase()

    fun toJsonElement(value: Any?): JsonElement {
        if (value == null || value == Unit) return JsonNull
        return when (value) {
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
            else -> {
                val kClass = value::class
                val simpleName = kClass.simpleName ?: ""

                // 1. WitOption.Some / WitOption.None
                if (simpleName == "Some" && kClass.supertypes.any { it.toString().contains("WitOption") }) {
                    val innerVal = readProperty(value, "value")
                    return JsonObject(
                        mapOf(
                            "option" to JsonPrimitive("some"),
                            "value" to toJsonElement(innerVal),
                        ),
                    )
                }
                if (simpleName == "None" && kClass.supertypes.any { it.toString().contains("WitOption") }) {
                    return JsonObject(mapOf("option" to JsonPrimitive("none")))
                }

                // 2. WitResult.Ok / WitResult.Err
                if (simpleName == "Ok" && kClass.supertypes.any { it.toString().contains("WitResult") }) {
                    val innerVal = readProperty(value, "value")
                    return JsonObject(
                        mapOf(
                            "result" to JsonPrimitive("ok"),
                            "value" to toJsonElement(innerVal),
                        ),
                    )
                }
                if (simpleName == "Err" && kClass.supertypes.any { it.toString().contains("WitResult") }) {
                    val errVal = readProperty(value, "error")
                    return JsonObject(
                        mapOf(
                            "result" to JsonPrimitive("err"),
                            "error" to toJsonElement(errVal),
                        ),
                    )
                }

                // 3. TupleN -> JSON Array [f0, f1, ...]
                if (simpleName.startsWith("Tuple")) {
                    val ctorParams = kClass.primaryConstructor?.parameters.orEmpty()
                    val elements = ctorParams.map { p -> toJsonElement(readProperty(value, p.name!!)) }
                    return JsonArray(elements)
                }

                // 4. Value class (Flags) -> raw bitmask
                if (kClass.isValue) {
                    val rawVal = readProperty(value, "raw")
                    return toJsonElement(rawVal)
                }

                // 5. Resource class (has `handle: Int`)
                val handleProp = kClass.declaredMemberProperties.firstOrNull { it.name == "handle" }
                if (handleProp != null && AutoCloseable::class.java.isAssignableFrom(kClass.java)) {
                    return toJsonElement(readProperty(value, "handle"))
                }

                // 6. Variant case (implements a sealed interface)
                val sealedParent = kClass.java.interfaces.firstOrNull { it.isInterface && it.isSealed }
                if (sealedParent != null) {
                    val caseTag = toKebabCase(simpleName)
                    val valueProp = kClass.declaredMemberProperties.firstOrNull { it.name == "value" }
                    val payloadElem = if (valueProp != null) toJsonElement(readProperty(value, "value")) else JsonNull
                    return JsonObject(
                        mapOf(
                            "case" to JsonPrimitive(caseTag),
                            "value" to payloadElem,
                        ),
                    )
                }

                // 7. Data class (Record) -> JSON Object with kebab-case WIT field names
                val ctorParams = kClass.primaryConstructor?.parameters.orEmpty()
                val map = linkedMapOf<String, JsonElement>()
                for (param in ctorParams) {
                    val propName = param.name ?: continue
                    val witFieldName = toKebabCase(propName)
                    map[witFieldName] = toJsonElement(readProperty(value, propName))
                }
                JsonObject(map)
            }
        }
    }

    fun fromJsonElement(element: JsonElement, targetType: KType): Any? {
        val classifier = targetType.classifier as? KClass<*>
            ?: return fromJsonElementDynamic(element)

        if (classifier == Unit::class) return Unit
        if (element is JsonNull && targetType.isMarkedNullable) return null

        val qName = classifier.qualifiedName ?: ""
        return when (classifier) {
            Boolean::class -> element.jsonPrimitive.boolean
            Byte::class -> element.jsonPrimitive.int.toByte()
            UByte::class -> element.jsonPrimitive.int.toUByte()
            Short::class -> element.jsonPrimitive.int.toShort()
            UShort::class -> element.jsonPrimitive.int.toUShort()
            Int::class -> element.jsonPrimitive.int
            UInt::class -> element.jsonPrimitive.long.toUInt()
            Long::class -> element.jsonPrimitive.long
            ULong::class -> element.jsonPrimitive.content.toULong()
            Float::class -> element.jsonPrimitive.float
            Double::class -> element.jsonPrimitive.double
            Char::class -> element.jsonPrimitive.content.first()
            String::class -> element.jsonPrimitive.content
            List::class -> {
                val elemType = targetType.arguments.firstOrNull()?.type
                element.jsonArray.map { item ->
                    if (elemType != null) fromJsonElement(item, elemType) else fromJsonElementDynamic(item)
                }
            }
            else -> {
                val simpleName = classifier.simpleName ?: ""

                // 1. WitOption<T>
                if (simpleName == "WitOption") {
                    val obj = element.jsonObject
                    val optTag = obj["option"]?.jsonPrimitive?.content
                    val someClass = classifier.nestedClasses.first { it.simpleName == "Some" }
                    val noneClass = classifier.nestedClasses.first { it.simpleName == "None" }
                    return if (optTag == "none") {
                        noneClass.objectInstance
                    } else {
                        val innerType = targetType.arguments.first().type!!
                        val innerVal = fromJsonElement(obj["value"] ?: JsonNull, innerType)
                        someClass.primaryConstructor!!.call(innerVal)
                    }
                }

                // 2. WitResult<T, E>
                if (simpleName == "WitResult") {
                    val obj = element.jsonObject
                    val resTag = obj["result"]?.jsonPrimitive?.content
                    val okClass = classifier.nestedClasses.first { it.simpleName == "Ok" }
                    val errClass = classifier.nestedClasses.first { it.simpleName == "Err" }
                    return if (resTag == "ok") {
                        val okType = targetType.arguments.getOrNull(0)?.type!!
                        val okVal = fromJsonElement(obj["value"] ?: JsonNull, okType)
                        okClass.primaryConstructor!!.call(okVal)
                    } else {
                        val errType = targetType.arguments.getOrNull(1)?.type!!
                        val errVal = fromJsonElement(obj["error"] ?: JsonNull, errType)
                        errClass.primaryConstructor!!.call(errVal)
                    }
                }

                // 3. TupleN<T0, T1, ...>
                if (simpleName.startsWith("Tuple")) {
                    val arr = element.jsonArray
                    val ctor = classifier.primaryConstructor!!
                    val args = ctor.parameters.mapIndexed { idx, _ ->
                        val argType = targetType.arguments[idx].type!!
                        fromJsonElement(arr[idx], argType)
                    }.toTypedArray()
                    return ctor.call(*args)
                }

                // 4. Enum class
                if (classifier.java.isEnum) {
                    val constName = NamingConventions.toUpperSnakeCase(element.jsonPrimitive.content)
                    return classifier.java.enumConstants.first { (it as Enum<*>).name == constName }
                }

                // 5. Value class (Flags)
                if (classifier.isValue) {
                    val ctor = classifier.primaryConstructor!!
                    val rawParamType = ctor.parameters.first().type
                    val rawVal = fromJsonElement(element, rawParamType)
                    return ctor.call(rawVal)
                }

                // 6. Variant (Sealed interface)
                if (classifier.isSealed) {
                    val obj = element.jsonObject
                    val caseTag = obj["case"]?.jsonPrimitive?.content
                        ?: error("Missing 'case' field in variant JSON: $obj")
                    val casePascal = NamingConventions.toPascalCase(caseTag)
                    val subClass = classifier.nestedClasses.firstOrNull { it.simpleName == casePascal }
                        ?: error("Unknown variant case '$casePascal' in ${classifier.qualifiedName}")
                    subClass.objectInstance?.let { return it }
                    val ctor = subClass.primaryConstructor!!
                    val payloadType = ctor.parameters.first().type
                    val payloadVal = fromJsonElement(obj["value"] ?: JsonNull, payloadType)
                    return ctor.call(payloadVal)
                }

                // 7. Data class (Record)
                val obj = element.jsonObject
                val ctor = classifier.primaryConstructor
                    ?: error("No primary constructor for record ${classifier.qualifiedName}")
                val args = ctor.parameters.map { param ->
                    val propName = param.name!!
                    val witKey = toKebabCase(propName)
                    val fieldElem = obj[witKey] ?: obj[propName] ?: JsonNull
                    fromJsonElement(fieldElem, param.type)
                }.toTypedArray()
                ctor.call(*args)
            }
        }
    }

    fun fromJsonElementToClass(element: JsonElement, paramClass: Class<*>): Any? {
        return when (paramClass) {
            Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> element.jsonPrimitive.boolean
            Byte::class.javaPrimitiveType, Byte::class.javaObjectType -> element.jsonPrimitive.int.toByte()
            Short::class.javaPrimitiveType, Short::class.javaObjectType -> element.jsonPrimitive.int.toShort()
            Int::class.javaPrimitiveType, Int::class.javaObjectType -> element.jsonPrimitive.int
            Long::class.javaPrimitiveType, Long::class.javaObjectType -> element.jsonPrimitive.long
            Float::class.javaPrimitiveType, Float::class.javaObjectType -> element.jsonPrimitive.float
            Double::class.javaPrimitiveType, Double::class.javaObjectType -> element.jsonPrimitive.double
            Char::class.javaPrimitiveType, Char::class.javaObjectType -> element.jsonPrimitive.content.first()
            String::class.java -> element.jsonPrimitive.content
            else -> fromJsonElementDynamic(element)
        }
    }

    fun fromJsonElementDynamic(element: JsonElement): Any? {
        return when (element) {
            is JsonNull -> null
            is JsonPrimitive -> {
                if (element.isString) {
                    element.content
                } else {
                    element.booleanOrNull
                        ?: element.intOrNull
                        ?: element.longOrNull
                        ?: element.double
                }
            }
            is JsonArray -> element.map { fromJsonElementDynamic(it) }
            is JsonObject -> element.mapValues { (_, v) -> fromJsonElementDynamic(v) }
        }
    }

    private fun readProperty(instance: Any, propName: String): Any? {
        val kProp = instance::class.declaredMemberProperties.firstOrNull { it.name == propName }
        if (kProp != null) {
            try {
                @Suppress("UNCHECKED_CAST")
                return (kProp as kotlin.reflect.KProperty1<Any, *>).get(instance)
            } catch (_: Throwable) {
                // Fall back to Java reflection below
            }
        }
        val getterName = "get${propName.replaceFirstChar { it.uppercaseChar() }}"
        val method = instance.javaClass.methods.firstOrNull {
            (it.name == getterName || it.name.startsWith("$getterName-")) && it.parameterCount == 0
        }
        if (method != null) {
            method.isAccessible = true
            return method.invoke(instance)
        }
        val field = instance.javaClass.declaredFields.firstOrNull { it.name == propName }
        if (field != null) {
            field.isAccessible = true
            return field.get(instance)
        }
        error("Property '$propName' not found on ${instance.javaClass.name}")
    }
}
