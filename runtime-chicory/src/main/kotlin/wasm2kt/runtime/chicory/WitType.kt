package wasm2kt.runtime.chicory

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Component-level value type, as described by the decomposer's type descriptors. */
sealed interface WitType {
    data object Bool : WitType
    data object S8 : WitType
    data object U8 : WitType
    data object S16 : WitType
    data object U16 : WitType
    data object S32 : WitType
    data object U32 : WitType
    data object S64 : WitType
    data object U64 : WitType
    data object F32 : WitType
    data object F64 : WitType
    data object Char : WitType
    data object Str : WitType
    data class Record(val fields: List<Pair<String, WitType>>) : WitType
    data class Tuple(val types: List<WitType>) : WitType
    data class ListOf(val element: WitType) : WitType
    data class Flags(val names: List<String>) : WitType
    /** Variants, and the variant-shaped `enum`, `option` and `result`. */
    data class Variant(val kind: VariantKind, val cases: List<Pair<String, WitType?>>) : WitType
    data class Own(val table: Int) : WitType
    data class Borrow(val table: Int) : WitType
    /** A type the Chicory backend cannot handle yet; using it fails, merely declaring it does not. */
    data class Unsupported(val detail: String) : WitType

    enum class VariantKind { VARIANT, ENUM, OPTION, RESULT }

    companion object {
        fun fromJson(o: JsonObject): WitType = when (val k = o.getValue("k").jsonPrimitive.content) {
            "bool" -> Bool
            "s8" -> S8
            "u8" -> U8
            "s16" -> S16
            "u16" -> U16
            "s32" -> S32
            "u32" -> U32
            "s64" -> S64
            "u64" -> U64
            "f32" -> F32
            "f64" -> F64
            "char" -> Char
            "string" -> Str
            "record" -> Record(o.getValue("fields").jsonArray.map {
                val f = it.jsonObject
                f.getValue("name").jsonPrimitive.content to fromJson(f.getValue("type").jsonObject)
            })
            "tuple" -> Tuple(o.getValue("types").jsonArray.map { fromJson(it.jsonObject) })
            "list" -> ListOf(fromJson(o.getValue("element").jsonObject))
            "flags" -> Flags(o.getValue("names").jsonArray.map { it.jsonPrimitive.content })
            "enum" -> Variant(VariantKind.ENUM, o.getValue("names").jsonArray.map { it.jsonPrimitive.content to null })
            "variant" -> Variant(VariantKind.VARIANT, o.getValue("cases").jsonArray.map {
                val c = it.jsonObject
                c.getValue("name").jsonPrimitive.content to c.optType("type")
            })
            "option" -> Variant(VariantKind.OPTION, listOf("none" to null, "some" to fromJson(o.getValue("type").jsonObject)))
            "result" -> Variant(VariantKind.RESULT, listOf("ok" to o.optType("ok"), "err" to o.optType("err")))
            "own" -> Own(o.getValue("table").jsonPrimitive.int)
            "borrow" -> Borrow(o.getValue("table").jsonPrimitive.int)
            else -> Unsupported(listOfNotNull(k, o["detail"]?.jsonPrimitive?.content).joinToString(": "))
        }

        private fun JsonObject.optType(key: String): WitType? =
            this[key]?.takeUnless { it is JsonNull }?.let { fromJson(it.jsonObject) }
    }
}

class FuncType(val params: List<Pair<String, WitType>>, val results: List<WitType>) {
    companion object {
        fun fromJson(o: JsonObject): FuncType = FuncType(
            params = o.getValue("params").jsonArray.map {
                val p = it.jsonObject
                p.getValue("name").jsonPrimitive.content to WitType.fromJson(p.getValue("type").jsonObject)
            },
            results = o.getValue("results").jsonArray.map { WitType.fromJson(it.jsonObject) },
        )
    }
}
