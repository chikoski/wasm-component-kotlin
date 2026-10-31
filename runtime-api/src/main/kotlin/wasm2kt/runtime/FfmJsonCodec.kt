package wasm2kt.runtime

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
            is ByteArray -> JsonArray(value.map { JsonPrimitive(it.toInt() and 0xFF) })
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

    fun decodeChar(element: JsonElement): Char {
        val str = element.jsonPrimitive.content
        require(str.isNotEmpty()) { "Cannot decode empty string as WIT char" }
        val codePoint = str.codePointAt(0)
        require(Character.charCount(codePoint) == str.length) {
            "Expected single Unicode scalar value for WIT char, got: $str"
        }
        require(codePoint <= Char.MAX_VALUE.code) {
            "Supplementary Unicode scalar value U+${codePoint.toString(16).uppercase()} exceeds Kotlin 16-bit Char range"
        }
        return codePoint.toChar()
    }

    fun decodeString(element: JsonElement): String = element.jsonPrimitive.content

    /** WIT `char` as a code point (any Unicode scalar value, unlike [decodeChar]). */
    fun decodeCodePoint(element: JsonElement): Int {
        val str = element.jsonPrimitive.content
        require(str.isNotEmpty()) { "Cannot decode empty string as WIT char" }
        val codePoint = str.codePointAt(0)
        require(Character.charCount(codePoint) == str.length) { "Expected single Unicode scalar value for WIT char, got: $str" }
        return codePoint
    }

    /** `list<u8>`. */
    fun decodeByteArray(element: JsonElement): ByteArray {
        val arr = element.jsonArray
        return ByteArray(arr.size) { i -> arr[i].jsonPrimitive.int.toByte() }
    }

    /**
     * `option<T>` mapped to `T?` (ADR-0006): `null` for `none`. Accepts the tagged form
     * (`{"option": "some", "value": ...}`) and bare values, like the backends do.
     */
    inline fun <T : Any> decodeNullableOption(element: JsonElement, decodeSome: (JsonElement) -> T): T? = when {
        element is JsonNull -> null
        element is JsonObject && "option" in element -> if (isOptionNone(element)) null else decodeSome(optionValue(element))
        else -> decodeSome(element)
    }

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
