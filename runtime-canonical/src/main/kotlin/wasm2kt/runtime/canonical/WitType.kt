@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.runtime.canonical

/** Component-level value type, as described by the decomposer's type descriptors. */
@InternalWasm2KtApi
sealed class WitType {
    abstract val align: Int
    abstract val size: Int
    abstract val flat: List<CanonicalAbi.CoreType>
    open val isRawInteger: Boolean get() = false

    @InternalWasm2KtApi
    data object Bool : WitType() {
        override val align: Int get() = 1
        override val size: Int get() = 1
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I32
    }

    @InternalWasm2KtApi
    data object S8 : WitType() {
        override val align: Int get() = 1
        override val size: Int get() = 1
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I32
        override val isRawInteger: Boolean get() = true
    }

    @InternalWasm2KtApi
    data object U8 : WitType() {
        override val align: Int get() = 1
        override val size: Int get() = 1
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I32
        override val isRawInteger: Boolean get() = true
    }

    @InternalWasm2KtApi
    data object S16 : WitType() {
        override val align: Int get() = 2
        override val size: Int get() = 2
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I32
        override val isRawInteger: Boolean get() = true
    }

    @InternalWasm2KtApi
    data object U16 : WitType() {
        override val align: Int get() = 2
        override val size: Int get() = 2
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I32
        override val isRawInteger: Boolean get() = true
    }

    @InternalWasm2KtApi
    data object S32 : WitType() {
        override val align: Int get() = 4
        override val size: Int get() = 4
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I32
        override val isRawInteger: Boolean get() = true
    }

    @InternalWasm2KtApi
    data object U32 : WitType() {
        override val align: Int get() = 4
        override val size: Int get() = 4
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I32
        override val isRawInteger: Boolean get() = true
    }

    @InternalWasm2KtApi
    data object S64 : WitType() {
        override val align: Int get() = 8
        override val size: Int get() = 8
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I64
        override val isRawInteger: Boolean get() = true
    }

    @InternalWasm2KtApi
    data object U64 : WitType() {
        override val align: Int get() = 8
        override val size: Int get() = 8
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I64
        override val isRawInteger: Boolean get() = true
    }

    @InternalWasm2KtApi
    data object F32 : WitType() {
        override val align: Int get() = 4
        override val size: Int get() = 4
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_F32
    }

    @InternalWasm2KtApi
    data object F64 : WitType() {
        override val align: Int get() = 8
        override val size: Int get() = 8
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_F64
    }

    @InternalWasm2KtApi
    data object Char : WitType() {
        override val align: Int get() = 4
        override val size: Int get() = 4
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I32
    }

    @InternalWasm2KtApi
    data object Str : WitType() {
        override val align: Int get() = 4
        override val size: Int get() = 8
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I32_I32
    }

    @InternalWasm2KtApi
    data class Record(val fields: List<Pair<String, WitType>>) : WitType() {
        @Volatile private var cachedFieldTypes: List<WitType>? = null
        @Volatile private var cachedAlign: Int = -1
        @Volatile private var cachedSize: Int = -1
        @Volatile private var cachedFlat: List<CanonicalAbi.CoreType>? = null

        val fieldTypes: List<WitType>
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

    @InternalWasm2KtApi
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

    @InternalWasm2KtApi
    data class ListOf(val element: WitType) : WitType() {
        override val align: Int get() = 4
        override val size: Int get() = 8
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I32_I32
    }

    @InternalWasm2KtApi
    data class Flags(val names: List<String>) : WitType() {
        @Volatile private var cachedFlat: List<CanonicalAbi.CoreType>? = null

        override val align: Int get() = CanonicalAbi.flagsSize(names.size).coerceIn(1, 4)
        override val size: Int get() = CanonicalAbi.flagsSize(names.size)
        override val flat: List<CanonicalAbi.CoreType>
            get() = cachedFlat ?: List((names.size + 31) / 32) { CanonicalAbi.CoreType.I32 }.also { cachedFlat = it }
    }

    /** Variants, and the variant-shaped `enum`, `option` and `result`. */
    @InternalWasm2KtApi
    data class Variant(val kind: VariantKind, val cases: List<Pair<String, WitType?>>) : WitType() {
        @Volatile private var cachedMaxCaseAlign: Int = -1
        @Volatile private var cachedAlign: Int = -1
        @Volatile private var cachedSize: Int = -1
        @Volatile private var cachedPayloadFlat: List<CanonicalAbi.CoreType>? = null
        @Volatile private var cachedFlat: List<CanonicalAbi.CoreType>? = null

        val discSize: Int
            get() = CanonicalAbi.discriminantSize(cases.size)

        val maxCaseAlign: Int
            get() {
                val c = cachedMaxCaseAlign
                return if (c >= 0) c else CanonicalAbi.computeMaxCaseAlign(this).also { cachedMaxCaseAlign = it }
            }

        val payloadOffset: Int
            get() = CanonicalAbi.alignTo(discSize, maxCaseAlign)

        val payloadFlat: List<CanonicalAbi.CoreType>
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

    @InternalWasm2KtApi
    data class Own(val table: Int) : WitType() {
        override val align: Int get() = 4
        override val size: Int get() = 4
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I32
    }

    @InternalWasm2KtApi
    data class Borrow(val table: Int) : WitType() {
        override val align: Int get() = 4
        override val size: Int get() = 4
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I32
    }

    /** The readable end of a `stream<T>`, whose element type is in the plan's stream table [table] (plan v5, #16). */
    @InternalWasm2KtApi
    data class Stream(val table: Int) : WitType() {
        override val align: Int get() = 4
        override val size: Int get() = 4
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I32
    }

    /** The readable end of a `future<T>`, whose element type is in the plan's future table [table] (plan v5, #16). */
    @InternalWasm2KtApi
    data class Future(val table: Int) : WitType() {
        override val align: Int get() = 4
        override val size: Int get() = 4
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I32
    }

    @InternalWasm2KtApi
    data object ErrorContext : WitType() {
        override val align: Int get() = 4
        override val size: Int get() = 4
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.FLAT_I32
    }

    /** A type the backend cannot handle yet; using it fails, merely declaring it does not. */
    @InternalWasm2KtApi
    data class Unsupported(val detail: String) : WitType() {
        override val align: Int get() = CanonicalAbi.unsupported(this)
        override val size: Int get() = CanonicalAbi.unsupported(this)
        override val flat: List<CanonicalAbi.CoreType> get() = CanonicalAbi.unsupported(this)
    }

    @InternalWasm2KtApi
    enum class VariantKind { VARIANT, ENUM, OPTION, RESULT }
}

/** [async] is the function type's `async` (plan v5, #16), not the canonical option. */
@InternalWasm2KtApi
class FuncType(val params: List<Pair<String, WitType>>, val results: List<WitType>, val async: Boolean = false) {
    @Volatile private var cachedParamTypes: List<WitType>? = null
    @Volatile private var cachedParamTuple: WitType.Tuple? = null
    @Volatile private var cachedResultTuple: WitType.Tuple? = null
    @Volatile private var cachedFlatParamCount: Int = -1
    @Volatile private var cachedFlatResultCount: Int = -1

    val paramTypes: List<WitType>
        get() = cachedParamTypes ?: params.map { it.second }.also { cachedParamTypes = it }

    val paramTuple: WitType.Tuple
        get() = cachedParamTuple ?: WitType.Tuple(paramTypes).also { cachedParamTuple = it }

    val resultTuple: WitType.Tuple
        get() = cachedResultTuple ?: WitType.Tuple(results).also { cachedResultTuple = it }

    val flatParamCount: Int
        get() {
            val c = cachedFlatParamCount
            return if (c >= 0) c else paramTuple.flat.size.also { cachedFlatParamCount = it }
        }

    val flatResultCount: Int
        get() {
            val c = cachedFlatResultCount
            return if (c >= 0) c else resultTuple.flat.size.also { cachedFlatResultCount = it }
        }
}
