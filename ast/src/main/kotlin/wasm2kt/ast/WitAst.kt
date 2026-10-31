package wasm2kt.ast

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Root JSON AST model emitted by `wasm-tools component wit --json`.
 */
@Serializable
data class WitResolve(
    val worlds: List<WitWorld> = emptyList(),
    val interfaces: List<WitInterface> = emptyList(),
    val types: List<WitTypeDef> = emptyList(),
    val packages: List<WitPackage> = emptyList(),
) {
    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        fun parse(jsonString: String): WitResolve = json.decodeFromString(serializer(), jsonString)
    }
}

@Serializable
data class WitPackage(
    val name: String,
    val interfaces: Map<String, Int> = emptyMap(),
    val worlds: Map<String, Int> = emptyMap(),
    val docs: WitDocs? = null,
)

@Serializable
data class WitDocs(
    val contents: String? = null,
)

@Serializable
data class WitWorld(
    val name: String,
    val imports: Map<String, WitWorldItem> = emptyMap(),
    val exports: Map<String, WitWorldItem> = emptyMap(),
    val docs: WitDocs? = null,
    val `package`: Int? = null,
)

@Serializable(with = WitWorldItemSerializer::class)
sealed interface WitWorldItem {
    data class InterfaceRef(val id: Int, val stability: JsonElement? = null) : WitWorldItem
    data class FunctionItem(val function: WitFunction) : WitWorldItem
    data class TypeRef(val id: Int) : WitWorldItem
}

@Serializable
data class WitInterface(
    val name: String? = null,
    val types: Map<String, Int> = emptyMap(),
    val functions: Map<String, WitFunction> = emptyMap(),
    val docs: WitDocs? = null,
    val `package`: Int? = null,
)

@Serializable
data class WitFunction(
    val name: String,
    val kind: WitFunctionKind,
    val params: List<WitParam> = emptyList(),
    /** wasm-tools < 1.220 emitted a `results` list. */
    @SerialName("results") val legacyResults: List<WitResultItem> = emptyList(),
    /** wasm-tools >= 1.220 emits a single optional `result` type. */
    val result: WitTypeRef? = null,
    val docs: WitDocs? = null,
) {
    val results: List<WitResultItem>
        get() = if (result != null) listOf(WitResultItem(type = result)) else legacyResults
}

@Serializable(with = WitFunctionKindSerializer::class)
sealed interface WitFunctionKind {
    data object Freestanding : WitFunctionKind
    data class Method(val resourceId: Int) : WitFunctionKind
    data class Static(val resourceId: Int) : WitFunctionKind
    data class Constructor(val resourceId: Int) : WitFunctionKind
}

@Serializable
data class WitParam(
    val name: String,
    val type: WitTypeRef,
)

@Serializable
data class WitResultItem(
    val name: String? = null,
    val type: WitTypeRef,
)

/**
 * A reference to either a built-in primitive type (e.g., `"u32"`, `"string"`) or an index into [WitResolve.types].
 */
@Serializable(with = WitTypeRefSerializer::class)
sealed interface WitTypeRef {
    data class Primitive(val name: String) : WitTypeRef
    data class Id(val index: Int) : WitTypeRef
}

@Serializable
data class WitTypeDef(
    val name: String? = null,
    val kind: WitTypeDefKind,
    val owner: WitTypeOwner? = null,
    val docs: WitDocs? = null,
)

@Serializable
data class WitTypeOwner(
    val `interface`: Int? = null,
    val world: Int? = null,
)

@Serializable(with = WitTypeDefKindSerializer::class)
sealed interface WitTypeDefKind {
    data class Record(val fields: List<WitField>) : WitTypeDefKind
    data class Enum(val cases: List<WitEnumCase>) : WitTypeDefKind
    data class Flags(val flags: List<WitFlag>) : WitTypeDefKind
    data class Variant(val cases: List<WitVariantCase>) : WitTypeDefKind
    data class Tuple(val types: List<WitTypeRef>) : WitTypeDefKind
    data class OptionType(val type: WitTypeRef) : WitTypeDefKind
    data class ResultType(val ok: WitTypeRef?, val err: WitTypeRef?) : WitTypeDefKind
    data class ListType(val type: WitTypeRef) : WitTypeDefKind
    data class TypeAlias(val type: WitTypeRef) : WitTypeDefKind
    data object Resource : WitTypeDefKind
    data class Handle(val kind: HandleKind, val resourceId: Int) : WitTypeDefKind
    data class FutureType(val type: WitTypeRef?) : WitTypeDefKind
    data class StreamType(val element: WitTypeRef?, val end: WitTypeRef?) : WitTypeDefKind
}

enum class HandleKind {
    OWN,
    BORROW,
}

@Serializable
data class WitField(
    val name: String,
    val type: WitTypeRef,
    val docs: WitDocs? = null,
)

@Serializable
data class WitEnumCase(
    val name: String,
    val docs: WitDocs? = null,
)

@Serializable
data class WitFlag(
    val name: String,
    val docs: WitDocs? = null,
)

@Serializable
data class WitVariantCase(
    val name: String,
    val type: WitTypeRef? = null,
    val docs: WitDocs? = null,
)

// --- Custom Serializers for wasm-tools polymorphic JSON unions ---

object WitTypeRefSerializer : KSerializer<WitTypeRef> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("WitTypeRef", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): WitTypeRef {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("WitTypeRefSerializer requires JsonDecoder")
        val element = jsonDecoder.decodeJsonElement()
        val primitive = element as? JsonPrimitive
            ?: throw SerializationException("Expected JsonPrimitive for WitTypeRef, got: $element")
        return if (primitive.isString) {
            WitTypeRef.Primitive(primitive.content)
        } else {
            WitTypeRef.Id(primitive.int)
        }
    }

    override fun serialize(encoder: Encoder, value: WitTypeRef) {
        when (value) {
            is WitTypeRef.Primitive -> encoder.encodeString(value.name)
            is WitTypeRef.Id -> encoder.encodeInt(value.index)
        }
    }
}

object WitWorldItemSerializer : KSerializer<WitWorldItem> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("WitWorldItem", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): WitWorldItem {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("WitWorldItemSerializer requires JsonDecoder")
        val obj = jsonDecoder.decodeJsonElement().jsonObject
        return when {
            "interface" in obj -> {
                val ifaceElem = obj.getValue("interface")
                val id = when (ifaceElem) {
                    is JsonPrimitive -> ifaceElem.int
                    is JsonObject -> ifaceElem.getValue("id").jsonPrimitive.int
                    else -> throw SerializationException("Unexpected interface item: $ifaceElem")
                }
                WitWorldItem.InterfaceRef(id = id)
            }
            "function" in obj -> {
                val fn = jsonDecoder.json.decodeFromJsonElement<WitFunction>(obj.getValue("function"))
                WitWorldItem.FunctionItem(fn)
            }
            "type" in obj -> {
                val typeElem = obj.getValue("type")
                val id = when (typeElem) {
                    is JsonPrimitive -> typeElem.int
                    is JsonObject -> typeElem.getValue("id").jsonPrimitive.int
                    else -> throw SerializationException("Unexpected type item: $typeElem")
                }
                WitWorldItem.TypeRef(id)
            }
            else -> throw SerializationException("Unknown WitWorldItem keys: ${obj.keys}")
        }
    }

    override fun serialize(encoder: Encoder, value: WitWorldItem) {
        throw UnsupportedOperationException("Serialization not supported for WitWorldItem")
    }
}

object WitFunctionKindSerializer : KSerializer<WitFunctionKind> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("WitFunctionKind", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): WitFunctionKind {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("WitFunctionKindSerializer requires JsonDecoder")
        return when (val elem = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> {
                if (elem.content == "freestanding") {
                    WitFunctionKind.Freestanding
                } else {
                    throw SerializationException("Unknown WitFunctionKind primitive: ${elem.content}")
                }
            }
            is JsonObject -> when {
                "method" in elem -> WitFunctionKind.Method(elem.getValue("method").jsonPrimitive.int)
                "static" in elem -> WitFunctionKind.Static(elem.getValue("static").jsonPrimitive.int)
                "constructor" in elem -> WitFunctionKind.Constructor(elem.getValue("constructor").jsonPrimitive.int)
                else -> throw SerializationException("Unknown WitFunctionKind object: $elem")
            }
            else -> throw SerializationException("Unexpected JSON for WitFunctionKind: $elem")
        }
    }

    override fun serialize(encoder: Encoder, value: WitFunctionKind) {
        throw UnsupportedOperationException("Serialization not supported for WitFunctionKind")
    }
}

object WitTypeDefKindSerializer : KSerializer<WitTypeDefKind> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("WitTypeDefKind", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): WitTypeDefKind {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("WitTypeDefKindSerializer requires JsonDecoder")
        val json = jsonDecoder.json
        return when (val elem = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> {
                if (elem.content == "resource") {
                    WitTypeDefKind.Resource
                } else {
                    throw SerializationException("Unknown WitTypeDefKind string: ${elem.content}")
                }
            }
            is JsonObject -> when {
                "record" in elem -> {
                    val fieldsElem = elem.getValue("record").jsonObject.getValue("fields")
                    WitTypeDefKind.Record(json.decodeFromJsonElement(fieldsElem))
                }
                "enum" in elem -> {
                    val casesElem = elem.getValue("enum").jsonObject.getValue("cases")
                    WitTypeDefKind.Enum(json.decodeFromJsonElement(casesElem))
                }
                "flags" in elem -> {
                    val flagsElem = elem.getValue("flags").jsonObject.getValue("flags")
                    WitTypeDefKind.Flags(json.decodeFromJsonElement(flagsElem))
                }
                "variant" in elem -> {
                    val casesElem = elem.getValue("variant").jsonObject.getValue("cases")
                    WitTypeDefKind.Variant(json.decodeFromJsonElement(casesElem))
                }
                "tuple" in elem -> {
                    val typesElem = elem.getValue("tuple").jsonObject.getValue("types")
                    WitTypeDefKind.Tuple(json.decodeFromJsonElement(typesElem))
                }
                "option" in elem -> {
                    val inner = json.decodeFromJsonElement<WitTypeRef>(elem.getValue("option"))
                    WitTypeDefKind.OptionType(inner)
                }
                "result" in elem -> {
                    val resObj = elem.getValue("result").jsonObject
                    val okElem = resObj["ok"]
                    val errElem = resObj["err"]
                    val okRef = if (okElem == null || okElem is JsonNull) null else json.decodeFromJsonElement<WitTypeRef>(okElem)
                    val errRef = if (errElem == null || errElem is JsonNull) null else json.decodeFromJsonElement<WitTypeRef>(errElem)
                    WitTypeDefKind.ResultType(ok = okRef, err = errRef)
                }
                "list" in elem -> {
                    val inner = json.decodeFromJsonElement<WitTypeRef>(elem.getValue("list"))
                    WitTypeDefKind.ListType(inner)
                }
                "type" in elem -> {
                    val inner = json.decodeFromJsonElement<WitTypeRef>(elem.getValue("type"))
                    WitTypeDefKind.TypeAlias(inner)
                }
                "handle" in elem -> {
                    val handleObj = elem.getValue("handle").jsonObject
                    when {
                        "own" in handleObj -> WitTypeDefKind.Handle(
                            HandleKind.OWN,
                            handleObj.getValue("own").jsonPrimitive.int,
                        )
                        "borrow" in handleObj -> WitTypeDefKind.Handle(
                            HandleKind.BORROW,
                            handleObj.getValue("borrow").jsonPrimitive.int,
                        )
                        else -> throw SerializationException("Unknown handle kind: $handleObj")
                    }
                }
                "future" in elem -> {
                    val fElem = elem.getValue("future")
                    val inner = if (fElem is JsonNull) null else json.decodeFromJsonElement<WitTypeRef>(fElem)
                    WitTypeDefKind.FutureType(inner)
                }
                "stream" in elem -> {
                    val sObj = elem.getValue("stream").jsonObject
                    val elementRef = sObj["element"]?.takeUnless { it is JsonNull }?.let { json.decodeFromJsonElement<WitTypeRef>(it) }
                    val endRef = sObj["end"]?.takeUnless { it is JsonNull }?.let { json.decodeFromJsonElement<WitTypeRef>(it) }
                    WitTypeDefKind.StreamType(elementRef, endRef)
                }
                else -> throw SerializationException("Unknown WitTypeDefKind keys: ${elem.keys}")
            }
            else -> throw SerializationException("Unexpected JSON for WitTypeDefKind: $elem")
        }
    }

    override fun serialize(encoder: Encoder, value: WitTypeDefKind) {
        throw UnsupportedOperationException("Serialization not supported for WitTypeDefKind")
    }
}
