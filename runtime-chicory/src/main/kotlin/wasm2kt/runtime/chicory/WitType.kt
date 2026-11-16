package wasm2kt.runtime.chicory

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Component-level value type, as described by the decomposer's type descriptors. */
internal sealed class WitType {
    internal abstract val align: Int
    internal abstract val size: Int
    internal abstract val flat: List<CanonicalAbi.CoreType>
    internal open val isRawInteger: Boolean get() = false

    data object Bool : WitType() {
        override val align: Int get() = 1
        override val size: Int get() = 1
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I32
    }

    data object S8 : WitType() {
        override val align: Int get() = 1
        override val size: Int get() = 1
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I32
        override val isRawInteger: Boolean get() = true
    }

    data object U8 : WitType() {
        override val align: Int get() = 1
        override val size: Int get() = 1
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I32
        override val isRawInteger: Boolean get() = true
    }

    data object S16 : WitType() {
        override val align: Int get() = 2
        override val size: Int get() = 2
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I32
        override val isRawInteger: Boolean get() = true
    }

    data object U16 : WitType() {
        override val align: Int get() = 2
        override val size: Int get() = 2
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I32
        override val isRawInteger: Boolean get() = true
    }

    data object S32 : WitType() {
        override val align: Int get() = 4
        override val size: Int get() = 4
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I32
        override val isRawInteger: Boolean get() = true
    }

    data object U32 : WitType() {
        override val align: Int get() = 4
        override val size: Int get() = 4
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I32
        override val isRawInteger: Boolean get() = true
    }

    data object S64 : WitType() {
        override val align: Int get() = 8
        override val size: Int get() = 8
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I64
        override val isRawInteger: Boolean get() = true
    }

    data object U64 : WitType() {
        override val align: Int get() = 8
        override val size: Int get() = 8
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I64
        override val isRawInteger: Boolean get() = true
    }

    data object F32 : WitType() {
        override val align: Int get() = 4
        override val size: Int get() = 4
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_F32
    }

    data object F64 : WitType() {
        override val align: Int get() = 8
        override val size: Int get() = 8
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_F64
    }

    data object Char : WitType() {
        override val align: Int get() = 4
        override val size: Int get() = 4
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I32
    }

    data object Str : WitType() {
        override val align: Int get() = 4
        override val size: Int get() = 8
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I32_I32
    }

    data class Record(val fields: List<Pair<String, WitType>>) : WitType() {
        @Volatile private var cachedFieldTypes: List<WitType>? = null
        @Volatile private var cachedAlign: Int = -1
        @Volatile private var cachedSize: Int = -1
        @Volatile private var cachedFlat: List<CanonicalAbi.CoreType>? = null

        internal val fieldTypes: List<WitType>
            get() = cachedFieldTypes ?: fields.map { it.second }.also { cachedFieldTypes = it }

        override val align: Int
            get() {
                val c = cachedAlign
                return if (c >= 0) c else CanonicalAbi.computeRecordAlign(fieldTypes).also { cachedAlign = it }
            }

        override val size: Int
            get() {
                val c = cachedSize
                return if (c >= 0) c else CanonicalAbi.computeRecordSize(fieldTypes).also { cachedSize = it }
            }

        override val flat: List<CanonicalAbi.CoreType>
            get() = cachedFlat ?: CanonicalAbi.computeFlattenAll(fieldTypes).also { cachedFlat = it }
    }

    data class Tuple(val types: List<WitType>) : WitType() {
        @Volatile private var cachedAlign: Int = -1
        @Volatile private var cachedSize: Int = -1
        @Volatile private var cachedFlat: List<CanonicalAbi.CoreType>? = null

        override val align: Int
            get() {
                val c = cachedAlign
                return if (c >= 0) c else CanonicalAbi.computeRecordAlign(types).also { cachedAlign = it }
            }

        override val size: Int
            get() {
                val c = cachedSize
                return if (c >= 0) c else CanonicalAbi.computeRecordSize(types).also { cachedSize = it }
            }

        override val flat: List<CanonicalAbi.CoreType>
            get() = cachedFlat ?: CanonicalAbi.computeFlattenAll(types).also { cachedFlat = it }
    }

    data class ListOf(val element: WitType) : WitType() {
        override val align: Int get() = 4
        override val size: Int get() = 8
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I32_I32
    }

    data class Flags(val names: List<String>) : WitType() {
        @Volatile private var cachedFlat: List<CanonicalAbi.CoreType>? = null

        override val align: Int get() = CanonicalAbi.flagsSize(names.size).coerceIn(1, 4)
        override val size: Int get() = CanonicalAbi.flagsSize(names.size)
        override val flat: List<CanonicalAbi.CoreType>
            get() = cachedFlat ?: List((names.size + 31) / 32) { CanonicalAbi.CoreType.I32 }.also { cachedFlat = it }
    }

    /** Variants, and the variant-shaped `enum`, `option` and `result`. */
    data class Variant(val kind: VariantKind, val cases: List<Pair<String, WitType?>>) : WitType() {
        @Volatile private var cachedMaxCaseAlign: Int = -1
        @Volatile private var cachedAlign: Int = -1
        @Volatile private var cachedSize: Int = -1
        @Volatile private var cachedPayloadFlat: List<CanonicalAbi.CoreType>? = null
        @Volatile private var cachedFlat: List<CanonicalAbi.CoreType>? = null

        internal val discSize: Int
            get() = CanonicalAbi.discriminantSize(cases.size)

        internal val maxCaseAlign: Int
            get() {
                val c = cachedMaxCaseAlign
                return if (c >= 0) c else CanonicalAbi.computeMaxCaseAlign(this).also { cachedMaxCaseAlign = it }
            }

        internal val payloadOffset: Int
            get() = CanonicalAbi.alignTo(discSize, maxCaseAlign)

        internal val payloadFlat: List<CanonicalAbi.CoreType>
            get() = cachedPayloadFlat ?: CanonicalAbi.computeVariantPayloadFlat(this).also { cachedPayloadFlat = it }

        override val align: Int
            get() {
                val c = cachedAlign
                return if (c >= 0) c else maxOf(discSize, maxCaseAlign).also { cachedAlign = it }
            }

        override val size: Int
            get() {
                val c = cachedSize
                return if (c >= 0) c else CanonicalAbi.computeVariantSize(this).also { cachedSize = it }
            }

        override val flat: List<CanonicalAbi.CoreType>
            get() = cachedFlat ?: CanonicalAbi.computeVariantFlat(payloadFlat).also { cachedFlat = it }
    }

    data class Own(val table: Int) : WitType() {
        override val align: Int get() = 4
        override val size: Int get() = 4
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I32
    }

    data class Borrow(val table: Int) : WitType() {
        override val align: Int get() = 4
        override val size: Int get() = 4
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I32
    }

    /** The readable end of a `stream<T>`, whose element type is in the plan's stream table [table] (plan v5, #16). */
    data class Stream(val table: Int) : WitType() {
        override val align: Int get() = 4
        override val size: Int get() = 4
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I32
    }

    /** The readable end of a `future<T>`, whose element type is in the plan's future table [table] (plan v5, #16). */
    data class Future(val table: Int) : WitType() {
        override val align: Int get() = 4
        override val size: Int get() = 4
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I32
    }

    data object ErrorContext : WitType() {
        override val align: Int get() = 4
        override val size: Int get() = 4
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I32
    }

    /** A type the Chicory backend cannot handle yet; using it fails, merely declaring it does not. */
    data class Unsupported(val detail: String) : WitType() {
        override val align: Int get() = CanonicalAbi.unsupported(this)
        override val size: Int get() = CanonicalAbi.unsupported(this)
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.unsupported(this)
    }

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
            "stream" -> Stream(o.getValue("table").jsonPrimitive.int)
            "future" -> Future(o.getValue("table").jsonPrimitive.int)
            "error-context" -> ErrorContext
            else -> Unsupported(listOfNotNull(k, o["detail"]?.jsonPrimitive?.content).joinToString(": "))
        }

        private fun JsonObject.optType(key: String): WitType? =
            this[key]?.takeUnless { it is JsonNull }?.let { fromJson(it.jsonObject) }
    }
}

/** [async] is the function type's `async` (plan v5, #16), not the canonical option. */
internal class FuncType(val params: List<Pair<String, WitType>>, val results: List<WitType>, val async: Boolean = false) {
    @Volatile private var cachedParamTypes: List<WitType>? = null
    @Volatile private var cachedParamTuple: WitType.Tuple? = null
    @Volatile private var cachedResultTuple: WitType.Tuple? = null
    @Volatile private var cachedFlatParamCount: Int = -1
    @Volatile private var cachedFlatResultCount: Int = -1

    internal val paramTypes: List<WitType>
        get() = cachedParamTypes ?: params.map { it.second }.also { cachedParamTypes = it }

    internal val paramTuple: WitType.Tuple
        get() = cachedParamTuple ?: WitType.Tuple(paramTypes).also { cachedParamTuple = it }

    internal val resultTuple: WitType.Tuple
        get() = cachedResultTuple ?: WitType.Tuple(results).also { cachedResultTuple = it }

    internal val flatParamCount: Int
        get() {
            val c = cachedFlatParamCount
            return if (c >= 0) c else paramTuple.flat.size.also { cachedFlatParamCount = it }
        }

    internal val flatResultCount: Int
        get() {
            val c = cachedFlatResultCount
            return if (c >= 0) c else resultTuple.flat.size.also { cachedFlatResultCount = it }
        }

    companion object {
        fun fromJson(o: JsonObject): FuncType = FuncType(
            params = o.getValue("params").jsonArray.map {
                val p = it.jsonObject
                p.getValue("name").jsonPrimitive.content to WitType.fromJson(p.getValue("type").jsonObject)
            },
            results = o.getValue("results").jsonArray.map { WitType.fromJson(it.jsonObject) },
            async = (o["async"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true",
        )
    }
}
