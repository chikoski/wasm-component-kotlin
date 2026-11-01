package wasm2kt.runtime.chicory

import com.dylibso.chicory.runtime.Memory
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import wasm2kt.runtime.ComponentTrapException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * The Component Model canonical ABI (32-bit linear memory), operating on the JSON value shape
 * shared with the Wasmtime bridge. Spec: component-model/design/mvp/CanonicalABI.md.
 *
 * Validation follows the spec's `trap_if`s (definitions.py): values coming from the guest that
 * break an ABI rule raise [ComponentTrapException]; values coming from the host that do not fit
 * the WIT type raise [IllegalArgumentException].
 */
internal object CanonicalAbi {
    const val MAX_FLAT_PARAMS = 16
    const val MAX_FLAT_RESULTS = 1
    const val MAX_STRING_BYTE_LENGTH = (1L shl 28) - 1
    const val MAX_LIST_BYTE_LENGTH = (1L shl 28) - 1
    const val CANONICAL_FLOAT32_NAN = 0x7fc00000
    const val CANONICAL_FLOAT64_NAN = 0x7ff8000000000000L

    enum class CoreType { I32, I64, F32, F64 }

    fun alignOf(t: WitType): Int = when (t) {
        WitType.Bool, WitType.S8, WitType.U8 -> 1
        WitType.S16, WitType.U16 -> 2
        WitType.S32, WitType.U32, WitType.F32, WitType.Char -> 4
        WitType.S64, WitType.U64, WitType.F64 -> 8
        WitType.Str, is WitType.ListOf -> 4
        is WitType.Record -> t.fields.maxOfOrNull { alignOf(it.second) } ?: 1
        is WitType.Tuple -> t.types.maxOfOrNull { alignOf(it) } ?: 1
        is WitType.Variant -> maxOf(discriminantSize(t.cases.size), maxCaseAlign(t))
        is WitType.Flags -> flagsSize(t.names.size).coerceAtMost(4)
        is WitType.Own, is WitType.Borrow -> 4
        is WitType.Unsupported -> unsupported(t)
    }

    fun sizeOf(t: WitType): Int = when (t) {
        WitType.Bool, WitType.S8, WitType.U8 -> 1
        WitType.S16, WitType.U16 -> 2
        WitType.S32, WitType.U32, WitType.F32, WitType.Char -> 4
        WitType.S64, WitType.U64, WitType.F64 -> 8
        WitType.Str, is WitType.ListOf -> 8
        is WitType.Record -> recordSize(t.fields.map { it.second })
        is WitType.Tuple -> recordSize(t.types)
        is WitType.Variant -> {
            var s = alignTo(discriminantSize(t.cases.size), maxCaseAlign(t))
            s += t.cases.maxOfOrNull { c -> c.second?.let { sizeOf(it) } ?: 0 } ?: 0
            alignTo(s, alignOf(t))
        }
        is WitType.Flags -> flagsSize(t.names.size)
        is WitType.Own, is WitType.Borrow -> 4
        is WitType.Unsupported -> unsupported(t)
    }

    fun flatten(t: WitType): List<CoreType> = when (t) {
        WitType.Bool, WitType.S8, WitType.U8, WitType.S16, WitType.U16, WitType.S32, WitType.U32, WitType.Char -> listOf(CoreType.I32)
        WitType.S64, WitType.U64 -> listOf(CoreType.I64)
        WitType.F32 -> listOf(CoreType.F32)
        WitType.F64 -> listOf(CoreType.F64)
        WitType.Str, is WitType.ListOf -> listOf(CoreType.I32, CoreType.I32)
        is WitType.Record -> t.fields.flatMap { flatten(it.second) }
        is WitType.Tuple -> t.types.flatMap { flatten(it) }
        is WitType.Variant -> {
            val joined = mutableListOf<CoreType>()
            for ((_, payload) in t.cases) {
                payload?.let { flatten(it) }?.forEachIndexed { i, ft ->
                    if (i < joined.size) joined[i] = join(joined[i], ft) else joined += ft
                }
            }
            listOf(CoreType.I32) + joined
        }
        is WitType.Flags -> List((t.names.size + 31) / 32) { CoreType.I32 }
        is WitType.Own, is WitType.Borrow -> listOf(CoreType.I32)
        is WitType.Unsupported -> unsupported(t)
    }

    fun flattenAll(types: List<WitType>): List<CoreType> = types.flatMap { flatten(it) }

    fun discriminantSize(cases: Int): Int = when {
        cases <= 1 shl 8 -> 1
        cases <= 1 shl 16 -> 2
        else -> 4
    }

    fun alignTo(offset: Int, align: Int): Int = (offset + align - 1) / align * align

    private fun join(a: CoreType, b: CoreType): CoreType = when {
        a == b -> a
        (a == CoreType.I32 && b == CoreType.F32) || (a == CoreType.F32 && b == CoreType.I32) -> CoreType.I32
        else -> CoreType.I64
    }

    private fun maxCaseAlign(t: WitType.Variant): Int = t.cases.maxOfOrNull { c -> c.second?.let { alignOf(it) } ?: 1 } ?: 1

    private fun recordSize(types: List<WitType>): Int {
        var s = 0
        for (ft in types) s = alignTo(s, alignOf(ft)) + sizeOf(ft)
        return alignTo(s, types.maxOfOrNull { alignOf(it) } ?: 1)
    }

    private fun flagsSize(n: Int): Int = when {
        n == 0 -> 0
        n <= 8 -> 1
        n <= 16 -> 2
        else -> 4 * ((n + 31) / 32)
    }

    internal fun unsupported(t: WitType.Unsupported): Nothing =
        throw UnsupportedOperationException("WIT type is not supported by the Chicory backend yet: ${t.detail}")
}

/** Where lifted/lowered values live, and how resources map to handles, for one call direction. */
internal interface AbiContext {
    val memory: Memory
    val encoding: StringEncoding
    fun realloc(align: Int, size: Int): Int
    fun lowerOwn(table: Int, value: JsonElement): Int
    fun lowerBorrow(table: Int, value: JsonElement): Int
    fun liftOwn(table: Int, handle: Int): JsonElement
    fun liftBorrow(table: Int, handle: Int): JsonElement
}

/** Converts JSON values into core wasm values / linear memory. */
internal class Lowerer(private val cx: AbiContext) {
    fun lowerFlat(v: JsonElement, t: WitType, out: MutableList<Long>) {
        when (t) {
            WitType.Bool -> out += if (bool(v)) 1L else 0L
            WitType.S8, WitType.U8, WitType.S16, WitType.U16, WitType.S32 -> out += integer(v, t)
            WitType.U32 -> out += integer(v, t)
            WitType.S64 -> out += long(v)
            WitType.U64 -> out += ulong(v)
            WitType.F32 -> out += f32Bits(v).toLong()
            WitType.F64 -> out += f64Bits(v)
            WitType.Char -> out += char(v).toLong()
            WitType.Str -> {
                val (ptr, len) = storeString(string(v))
                out += ptr.toLong(); out += len.toLong()
            }
            is WitType.ListOf -> {
                val (ptr, len) = storeList(v.jsonArray, t.element)
                out += ptr.toLong(); out += len.toLong()
            }
            is WitType.Record -> {
                val o = v.jsonObject
                for ((name, ft) in t.fields) lowerFlat(o[name] ?: missingField(name), ft, out)
            }
            is WitType.Tuple -> {
                val a = tupleArray(v, t)
                t.types.forEachIndexed { i, et -> lowerFlat(a[i], et, out) }
            }
            is WitType.Flags -> flagWords(v, t).forEach { out += it.toLong() }
            is WitType.Variant -> {
                val (index, payload) = variantCase(v, t)
                out += index.toLong()
                val joined = CanonicalAbi.flatten(t).drop(1)
                val caseType = t.cases[index].second
                val flat = mutableListOf<Long>()
                caseType?.let { lowerFlat(payload ?: JsonNull, it, flat) }
                val caseFlat = caseType?.let { CanonicalAbi.flatten(it) } ?: emptyList()
                joined.forEachIndexed { i, jt ->
                    out += if (i < flat.size) coerce(flat[i], caseFlat[i], jt) else 0L
                }
            }
            is WitType.Own -> out += cx.lowerOwn(t.table, v).toLong()
            is WitType.Borrow -> out += cx.lowerBorrow(t.table, v).toLong()
            is WitType.Unsupported -> CanonicalAbi.unsupported(t)
        }
    }

    fun store(v: JsonElement, t: WitType, ptr: Int) {
        val m = cx.memory
        when (t) {
            WitType.Bool -> m.writeByte(ptr, if (bool(v)) 1 else 0)
            WitType.S8, WitType.U8 -> m.writeByte(ptr, integer(v, t).toByte())
            WitType.S16, WitType.U16 -> m.writeShort(ptr, integer(v, t).toShort())
            WitType.S32, WitType.U32 -> m.writeI32(ptr, integer(v, t).toInt())
            WitType.S64 -> m.writeLong(ptr, long(v))
            WitType.U64 -> m.writeLong(ptr, ulong(v))
            WitType.F32 -> m.writeI32(ptr, f32Bits(v))
            WitType.F64 -> m.writeLong(ptr, f64Bits(v))
            WitType.Char -> m.writeI32(ptr, char(v))
            WitType.Str -> {
                val (p, len) = storeString(string(v))
                m.writeI32(ptr, p); m.writeI32(ptr + 4, len)
            }
            is WitType.ListOf -> {
                val (p, len) = storeList(v.jsonArray, t.element)
                m.writeI32(ptr, p); m.writeI32(ptr + 4, len)
            }
            is WitType.Record -> {
                val o = v.jsonObject
                var off = 0
                for ((name, ft) in t.fields) {
                    off = CanonicalAbi.alignTo(off, CanonicalAbi.alignOf(ft))
                    store(o[name] ?: missingField(name), ft, ptr + off)
                    off += CanonicalAbi.sizeOf(ft)
                }
            }
            is WitType.Tuple -> {
                val a = tupleArray(v, t)
                var off = 0
                t.types.forEachIndexed { i, et ->
                    off = CanonicalAbi.alignTo(off, CanonicalAbi.alignOf(et))
                    store(a[i], et, ptr + off)
                    off += CanonicalAbi.sizeOf(et)
                }
            }
            is WitType.Flags -> {
                val words = flagWords(v, t)
                when (CanonicalAbi.sizeOf(t)) {
                    0 -> Unit
                    1 -> m.writeByte(ptr, words[0].toByte())
                    2 -> m.writeShort(ptr, words[0].toShort())
                    else -> words.forEachIndexed { i, w -> m.writeI32(ptr + 4 * i, w) }
                }
            }
            is WitType.Variant -> {
                val (index, payload) = variantCase(v, t)
                val discSize = CanonicalAbi.discriminantSize(t.cases.size)
                when (discSize) {
                    1 -> m.writeByte(ptr, index.toByte())
                    2 -> m.writeShort(ptr, index.toShort())
                    else -> m.writeI32(ptr, index)
                }
                t.cases[index].second?.let { caseType ->
                    val maxAlign = t.cases.maxOfOrNull { c -> c.second?.let { CanonicalAbi.alignOf(it) } ?: 1 } ?: 1
                    store(payload ?: JsonNull, caseType, ptr + CanonicalAbi.alignTo(discSize, maxAlign))
                }
            }
            is WitType.Own -> m.writeI32(ptr, cx.lowerOwn(t.table, v))
            is WitType.Borrow -> m.writeI32(ptr, cx.lowerBorrow(t.table, v))
            is WitType.Unsupported -> CanonicalAbi.unsupported(t)
        }
    }

    /** Stores [values] as a tuple in freshly allocated memory and returns its address. */
    fun storeTuple(values: List<JsonElement>, types: List<WitType>): Int {
        val tuple = WitType.Tuple(types)
        val ptr = alloc(CanonicalAbi.alignOf(tuple), CanonicalAbi.sizeOf(tuple).toLong())
        store(JsonArray(values), tuple, ptr)
        return ptr
    }

    /** Stores [values] as a tuple at a guest-provided return pointer, which must be aligned and in bounds. */
    fun storeTupleAt(values: List<JsonElement>, types: List<WitType>, ptr: Int) {
        val tuple = WitType.Tuple(types)
        checkRange(cx.memory, ptr, CanonicalAbi.sizeOf(tuple).toLong(), CanonicalAbi.alignOf(tuple), "return pointer")
        store(JsonArray(values), tuple, ptr)
    }

    /** Calls `cabi_realloc`; the guest must return an aligned, in-bounds pointer. */
    private fun alloc(align: Int, size: Long): Int {
        val ptr = cx.realloc(align, size.toInt())
        checkRange(cx.memory, ptr, size, align, "cabi_realloc result")
        return ptr
    }

    private fun storeString(s: String): Pair<Int, Int> {
        // JSON strings are UTF-16 and may hold lone surrogates, which no WIT string can carry.
        val utf8 = encodeStrict(s, Charsets.UTF_8)
        require(utf8.size <= CanonicalAbi.MAX_STRING_BYTE_LENGTH) { "String of ${utf8.size} bytes exceeds the Canonical ABI limit" }
        val (align, bytes, length) = when (cx.encoding) {
            StringEncoding.UTF8 -> Triple(1, utf8, utf8.size)
            StringEncoding.UTF16 -> Triple(2, s.toByteArray(Charsets.UTF_16LE), s.length)
            StringEncoding.LATIN1_UTF16 ->
                if (s.all { it.code <= 0xFF }) Triple(2, s.toByteArray(Charsets.ISO_8859_1), s.length)
                else Triple(2, s.toByteArray(Charsets.UTF_16LE), s.length or UTF16_TAG)
        }
        val ptr = alloc(align, bytes.size.toLong())
        cx.memory.write(ptr, bytes)
        return ptr to length
    }

    private fun storeList(items: JsonArray, element: WitType): Pair<Int, Int> {
        val size = CanonicalAbi.sizeOf(element)
        val byteLength = size.toLong() * items.size
        require(byteLength <= CanonicalAbi.MAX_LIST_BYTE_LENGTH) { "List of $byteLength bytes exceeds the Canonical ABI limit" }
        val ptr = alloc(CanonicalAbi.alignOf(element), byteLength)
        items.forEachIndexed { i, item -> store(item, element, ptr + i * size) }
        return ptr to items.size
    }

    private fun flagWords(v: JsonElement, t: WitType.Flags): IntArray {
        val words = IntArray((t.names.size + 31) / 32)
        if (v is JsonArray) {
            for (name in v) {
                val i = t.names.indexOf(string(name))
                require(i >= 0) { "Unknown flag '${name.jsonPrimitive.content}'" }
                words[i / 32] = words[i / 32] or (1 shl (i % 32))
            }
        } else {
            val bits = v.jsonPrimitive.content.toULongOrNull() ?: throw IllegalArgumentException("Expected flags, got $v")
            require(t.names.size >= 64 || bits shr t.names.size == 0uL) { "Flags value $bits sets bits beyond the ${t.names.size} declared flags" }
            for (i in t.names.indices) if (i < 64 && (bits shr i) and 1uL == 1uL) words[i / 32] = words[i / 32] or (1 shl (i % 32))
        }
        return words
    }

    /** Resolves the case index and payload, accepting every shape the Wasmtime bridge accepts. */
    private fun variantCase(v: JsonElement, t: WitType.Variant): Pair<Int, JsonElement?> = when (t.kind) {
        WitType.VariantKind.ENUM -> caseIndex(t, string(v)) to null
        WitType.VariantKind.VARIANT -> {
            val o = v as? JsonObject ?: throw IllegalArgumentException("Expected a variant object, got $v")
            caseIndex(t, string(o["case"] ?: throw IllegalArgumentException("Missing 'case' in variant $v"))) to o["value"]
        }
        WitType.VariantKind.OPTION -> when {
            v is JsonNull -> 0 to null
            isTaggedOption(v, t.cases[1].second) -> when (val tag = (v as JsonObject).getValue("option").jsonPrimitive.content) {
                "none" -> 0 to null
                "some" -> 1 to (v["value"] ?: JsonNull)
                else -> throw IllegalArgumentException("Invalid option tag '$tag'")
            }
            else -> 1 to v
        }
        WitType.VariantKind.RESULT -> {
            val o = v.jsonObject
            when (val tag = o.getValue("result").jsonPrimitive.content) {
                "ok" -> 0 to o["value"]
                "err" -> 1 to o["error"]
                else -> throw IllegalArgumentException("Invalid result tag '$tag'")
            }
        }
    }

    /**
     * Whether [v] is the tagged form of an option (`{"option": "none"}` / `{"option": "some", "value": ...}`)
     * rather than a bare `some` payload. Generated code sends `some` bare (ADR-0006), so a payload that
     * is itself a record with an `option` field must not be mistaken for a tag (#36): an object is a
     * tag only if its keys are exactly those of the tagged form, and never when every field of the
     * payload record would fit in it. Must match the Wasmtime bridge (`is_tagged_option`).
     */
    private fun isTaggedOption(v: JsonElement, payload: WitType?): Boolean {
        if (v !is JsonObject) return false
        val tag = v["option"] as? JsonPrimitive ?: return false
        if (!tag.isString || (tag.content != "some" && tag.content != "none")) return false
        if (!TAGGED_OPTION_KEYS.containsAll(v.keys)) return false
        val fields = (payload as? WitType.Record)?.fields?.map { it.first } ?: return true
        return !TAGGED_OPTION_KEYS.containsAll(fields)
    }

    private fun caseIndex(t: WitType.Variant, name: String): Int =
        t.cases.indexOfFirst { it.first == name }.also { require(it >= 0) { "Unknown case '$name'" } }

    private fun coerce(value: Long, from: CanonicalAbi.CoreType, to: CanonicalAbi.CoreType): Long = when {
        from == to -> value
        to == CanonicalAbi.CoreType.I32 -> value // f32 bits already live in the low 32 bits
        else -> when (from) {
            CanonicalAbi.CoreType.I32, CanonicalAbi.CoreType.F32 -> value and 0xFFFF_FFFFL
            else -> value
        }
    }

    private fun missingField(name: String): Nothing = throw IllegalArgumentException("Missing record field '$name'")

    private fun tupleArray(v: JsonElement, t: WitType.Tuple): JsonArray {
        val a = v as? JsonArray ?: throw IllegalArgumentException("Expected a tuple of ${t.types.size}, got $v")
        require(a.size == t.types.size) { "Expected a tuple of ${t.types.size}, got ${a.size} elements" }
        return a
    }
}

/** Keys of the tagged option form. */
private val TAGGED_OPTION_KEYS = setOf("option", "value")

/** Converts core wasm values / linear memory into JSON values. */
internal class Lifter(private val cx: AbiContext) {
    class FlatReader(private val values: LongArray) {
        private var i = 0
        fun next(): Long = values[i++]
    }

    fun liftFlat(t: WitType, r: FlatReader): JsonElement = when (t) {
        WitType.Bool -> JsonPrimitive(r.next().toInt() != 0)
        WitType.S8 -> JsonPrimitive(r.next().toInt().toByte())
        WitType.U8 -> JsonPrimitive(r.next().toInt() and 0xFF)
        WitType.S16 -> JsonPrimitive(r.next().toInt().toShort())
        WitType.U16 -> JsonPrimitive(r.next().toInt() and 0xFFFF)
        WitType.S32 -> JsonPrimitive(r.next().toInt())
        WitType.U32 -> JsonPrimitive(r.next().toInt().toLong() and 0xFFFF_FFFFL)
        WitType.S64 -> JsonPrimitive(r.next())
        WitType.U64 -> u64(r.next())
        WitType.F32 -> f32(r.next().toInt())
        WitType.F64 -> f64(r.next())
        WitType.Char -> charJson(r.next().toInt())
        WitType.Str -> loadString(r.next().toInt(), r.next().toInt())
        is WitType.ListOf -> loadList(r.next().toInt(), r.next().toInt(), t.element)
        is WitType.Record -> JsonObject(t.fields.associate { (name, ft) -> name to liftFlat(ft, r) })
        is WitType.Tuple -> JsonArray(t.types.map { liftFlat(it, r) })
        is WitType.Flags -> flagsJson(IntArray((t.names.size + 31) / 32) { r.next().toInt() }, t)
        is WitType.Variant -> {
            val index = r.next().toInt()
            checkDiscriminant(index, t)
            val joined = CanonicalAbi.flatten(t).drop(1)
            val raw = LongArray(joined.size) { r.next() }
            val caseType = t.cases[index].second
            val payload = caseType?.let { ct ->
                val caseFlat = CanonicalAbi.flatten(ct)
                liftFlat(ct, FlatReader(LongArray(caseFlat.size) { i -> uncoerce(raw[i], joined[i], caseFlat[i]) }))
            }
            variantJson(t, index, payload)
        }
        is WitType.Own -> cx.liftOwn(t.table, r.next().toInt())
        is WitType.Borrow -> cx.liftBorrow(t.table, r.next().toInt())
        is WitType.Unsupported -> CanonicalAbi.unsupported(t)
    }

    fun load(t: WitType, ptr: Int): JsonElement {
        val m = cx.memory
        return when (t) {
            WitType.Bool -> JsonPrimitive(m.read(ptr).toInt() != 0)
            WitType.S8 -> JsonPrimitive(m.read(ptr))
            WitType.U8 -> JsonPrimitive(m.read(ptr).toInt() and 0xFF)
            WitType.S16 -> JsonPrimitive(m.readShort(ptr))
            WitType.U16 -> JsonPrimitive(m.readShort(ptr).toInt() and 0xFFFF)
            WitType.S32 -> JsonPrimitive(m.readInt(ptr))
            WitType.U32 -> JsonPrimitive(m.readInt(ptr).toLong() and 0xFFFF_FFFFL)
            WitType.S64 -> JsonPrimitive(m.readLong(ptr))
            WitType.U64 -> u64(m.readLong(ptr))
            WitType.F32 -> f32(m.readInt(ptr))
            WitType.F64 -> f64(m.readLong(ptr))
            WitType.Char -> charJson(m.readInt(ptr))
            WitType.Str -> loadString(m.readInt(ptr), m.readInt(ptr + 4))
            is WitType.ListOf -> loadList(m.readInt(ptr), m.readInt(ptr + 4), t.element)
            is WitType.Record -> {
                var off = 0
                JsonObject(t.fields.associate { (name, ft) ->
                    off = CanonicalAbi.alignTo(off, CanonicalAbi.alignOf(ft))
                    val value = load(ft, ptr + off)
                    off += CanonicalAbi.sizeOf(ft)
                    name to value
                })
            }
            is WitType.Tuple -> {
                var off = 0
                JsonArray(t.types.map { et ->
                    off = CanonicalAbi.alignTo(off, CanonicalAbi.alignOf(et))
                    val value = load(et, ptr + off)
                    off += CanonicalAbi.sizeOf(et)
                    value
                })
            }
            is WitType.Flags -> flagsJson(
                when (CanonicalAbi.sizeOf(t)) {
                    0 -> IntArray(0)
                    1 -> intArrayOf(m.read(ptr).toInt() and 0xFF)
                    2 -> intArrayOf(m.readShort(ptr).toInt() and 0xFFFF)
                    else -> IntArray((t.names.size + 31) / 32) { i -> m.readInt(ptr + 4 * i) }
                },
                t,
            )
            is WitType.Variant -> {
                val discSize = CanonicalAbi.discriminantSize(t.cases.size)
                val index = when (discSize) {
                    1 -> m.read(ptr).toInt() and 0xFF
                    2 -> m.readShort(ptr).toInt() and 0xFFFF
                    else -> m.readInt(ptr)
                }
                checkDiscriminant(index, t)
                val maxAlign = t.cases.maxOfOrNull { c -> c.second?.let { CanonicalAbi.alignOf(it) } ?: 1 } ?: 1
                val payload = t.cases[index].second?.let { load(it, ptr + CanonicalAbi.alignTo(discSize, maxAlign)) }
                variantJson(t, index, payload)
            }
            is WitType.Own -> cx.liftOwn(t.table, m.readInt(ptr))
            is WitType.Borrow -> cx.liftBorrow(t.table, m.readInt(ptr))
            is WitType.Unsupported -> CanonicalAbi.unsupported(t)
        }
    }

    /** Loads a tuple from a guest-provided pointer (spilled params or results), which must be aligned and in bounds. */
    fun loadTuple(ptr: Int, types: List<WitType>): List<JsonElement> {
        val tuple = WitType.Tuple(types)
        checkRange(cx.memory, ptr, CanonicalAbi.sizeOf(tuple).toLong(), CanonicalAbi.alignOf(tuple), "tuple pointer")
        return load(tuple, ptr).jsonArray
    }

    private fun loadString(ptr: Int, taggedLen: Int): JsonPrimitive {
        val m = cx.memory
        val tagged = taggedLen.toLong() and 0xFFFF_FFFFL
        val utf16Tag = UTF16_TAG.toLong() and 0xFFFF_FFFFL
        val (align, byteLength, charset) = when (cx.encoding) {
            StringEncoding.UTF8 -> Triple(1, tagged, Charsets.UTF_8)
            StringEncoding.UTF16 -> Triple(2, 2 * tagged, Charsets.UTF_16LE)
            StringEncoding.LATIN1_UTF16 ->
                if (tagged and utf16Tag != 0L) Triple(2, 2 * (tagged xor utf16Tag), Charsets.UTF_16LE)
                else Triple(2, tagged, Charsets.ISO_8859_1)
        }
        if (byteLength > CanonicalAbi.MAX_STRING_BYTE_LENGTH) trap("String byte length $byteLength exceeds the maximum")
        checkRange(m, ptr, byteLength, align, "string")
        if (byteLength == 0L) return JsonPrimitive("")
        val bytes = m.readBytes(ptr, byteLength.toInt())
        return try {
            JsonPrimitive(decoder(charset).decode(ByteBuffer.wrap(bytes)).toString())
        } catch (e: CharacterCodingException) {
            throw ComponentTrapException("Invalid ${cx.encoding.name.lowercase()} string at ${unsigned(ptr)}", e)
        }
    }

    private fun loadList(ptr: Int, len: Int, element: WitType): JsonArray {
        val size = CanonicalAbi.sizeOf(element)
        val length = len.toLong() and 0xFFFF_FFFFL
        val byteLength = length * size
        if (byteLength > CanonicalAbi.MAX_LIST_BYTE_LENGTH) trap("List byte length $byteLength exceeds the maximum")
        checkRange(cx.memory, ptr, byteLength, CanonicalAbi.alignOf(element), "list")
        return JsonArray(List(length.toInt()) { i -> load(element, ptr + i * size) })
    }

    private fun checkDiscriminant(index: Int, t: WitType.Variant) {
        if (index !in t.cases.indices) trap("Discriminant ${unsigned(index)} out of range for ${t.cases.size} cases")
    }

    /** Bits past the declared flags are ignored, as in the spec's `unpack_flags_from_int`. */
    private fun flagsJson(raw: IntArray, t: WitType.Flags): JsonPrimitive {
        val words = raw.copyOf()
        val rest = t.names.size % 32
        if (rest != 0 && words.isNotEmpty()) words[words.size - 1] = words[words.size - 1] and ((1 shl rest) - 1)
        var bits = 0uL
        words.forEachIndexed { i, w -> if (i < 2) bits = bits or ((w.toULong() and 0xFFFF_FFFFuL) shl (32 * i)) }
        return if (bits <= Long.MAX_VALUE.toULong()) JsonPrimitive(bits.toLong()) else JsonPrimitive(bits.toString())
    }

    private fun variantJson(t: WitType.Variant, index: Int, payload: JsonElement?): JsonElement = when (t.kind) {
        WitType.VariantKind.ENUM -> JsonPrimitive(t.cases[index].first)
        WitType.VariantKind.VARIANT -> JsonObject(mapOf("case" to JsonPrimitive(t.cases[index].first), "value" to (payload ?: JsonNull)))
        WitType.VariantKind.OPTION ->
            if (index == 0) JsonObject(mapOf("option" to JsonPrimitive("none")))
            else JsonObject(mapOf("option" to JsonPrimitive("some"), "value" to (payload ?: JsonNull)))
        WitType.VariantKind.RESULT ->
            if (index == 0) JsonObject(mapOf("result" to JsonPrimitive("ok"), "value" to (payload ?: JsonNull)))
            else JsonObject(mapOf("result" to JsonPrimitive("err"), "error" to (payload ?: JsonNull)))
    }

    private fun uncoerce(value: Long, from: CanonicalAbi.CoreType, to: CanonicalAbi.CoreType): Long = when {
        from == to -> value
        // i64 -> i32 / f32: wrap; the f32 bits live in the low 32 bits either way.
        to == CanonicalAbi.CoreType.I32 || to == CanonicalAbi.CoreType.F32 -> value.toInt().toLong()
        else -> value
    }

    private fun charJson(codePoint: Int): JsonPrimitive {
        if (codePoint !in 0..0x10FFFF || codePoint in 0xD800..0xDFFF) trap("Invalid char code point ${unsigned(codePoint)}")
        return JsonPrimitive(String(Character.toChars(codePoint)))
    }

    /** NaNs are canonicalized when lifted (spec `decode_i32_as_float` / `decode_i64_as_float`). */
    private fun f32(bits: Int): JsonPrimitive {
        val f = java.lang.Float.intBitsToFloat(bits)
        return JsonPrimitive(if (f.isNaN()) java.lang.Float.intBitsToFloat(CanonicalAbi.CANONICAL_FLOAT32_NAN) else f)
    }

    private fun f64(bits: Long): JsonPrimitive {
        val d = java.lang.Double.longBitsToDouble(bits)
        return JsonPrimitive(if (d.isNaN()) java.lang.Double.longBitsToDouble(CanonicalAbi.CANONICAL_FLOAT64_NAN) else d)
    }

    private fun u64(v: Long): JsonPrimitive = if (v >= 0) JsonPrimitive(v) else JsonPrimitive(v.toULong().toString())
}

private const val UTF16_TAG = 1 shl 31

internal fun trap(message: String): Nothing = throw ComponentTrapException(message)

internal fun unsigned(i: Int): Long = i.toLong() and 0xFFFF_FFFFL

/** Traps unless `[ptr, ptr + byteLength)` lies inside [memory] and [ptr] is aligned to [align]. */
internal fun checkRange(memory: Memory, ptr: Int, byteLength: Long, align: Int, what: String) {
    val p = unsigned(ptr)
    if (p % align != 0L) trap("Misaligned $what: $p is not a multiple of $align")
    val size = memory.pages().toLong() * Memory.PAGE_SIZE
    if (p + byteLength > size) trap("Out-of-bounds $what: [$p, ${p + byteLength}) exceeds linear memory of $size bytes")
}

private fun decoder(charset: Charset) = charset.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)

private fun encodeStrict(s: String, charset: Charset): ByteArray {
    val buf = try {
        charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(s))
    } catch (e: CharacterCodingException) {
        throw IllegalArgumentException("String is not valid Unicode (lone surrogate?)", e)
    }
    return ByteArray(buf.remaining()).also { buf.get(it) }
}

/**
 * The JSON primitive of a host value for a WIT scalar. Like the Wasmtime bridge, JSON types must
 * match: `null` is not a value of any scalar, numbers and booleans are not strings and vice versa
 * (#36). Only u64 also accepts a decimal string, for values beyond what JSON numbers carry exactly.
 */
private fun primitive(v: JsonElement, type: String, string: Boolean = false): JsonPrimitive {
    val p = v as? JsonPrimitive
    if (p == null || p is JsonNull || p.isString != string) throw IllegalArgumentException("Expected $type, got $v")
    return p
}

private fun string(v: JsonElement): String = primitive(v, "string", string = true).content

private fun bool(v: JsonElement): Boolean =
    primitive(v, "bool").content.toBooleanStrictOrNull() ?: throw IllegalArgumentException("Expected bool, got $v")

/** Integers up to 32 bits, range-checked against the WIT type (no silent truncation). */
private fun integer(v: JsonElement, t: WitType): Long {
    val (name, range) = when (t) {
        WitType.S8 -> "s8" to (-128L..127L)
        WitType.U8 -> "u8" to (0L..0xFFL)
        WitType.S16 -> "s16" to (-32768L..32767L)
        WitType.U16 -> "u16" to (0L..0xFFFFL)
        WitType.S32 -> "s32" to (Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
        WitType.U32 -> "u32" to (0L..0xFFFF_FFFFL)
        else -> error("not a small integer type: $t")
    }
    val n = primitive(v, name).content.toLongOrNull() ?: throw IllegalArgumentException("Expected $name, got $v")
    require(n in range) { "Value $n is out of range for $name" }
    return n
}

private fun long(v: JsonElement): Long =
    primitive(v, "s64").content.toLongOrNull() ?: throw IllegalArgumentException("Expected s64, got $v")

private fun ulong(v: JsonElement): Long {
    val p = v as? JsonPrimitive
    if (p == null || p is JsonNull) throw IllegalArgumentException("Expected u64, got $v")
    return (p.content.toULongOrNull() ?: throw IllegalArgumentException("Expected u64, got $v")).toLong()
}

private fun char(v: JsonElement): Int {
    val s = primitive(v, "char", string = true).content
    require(s.isNotEmpty() && s.codePointCount(0, s.length) == 1) { "Expected a single char, got \"$s\"" }
    val cp = s.codePointAt(0)
    require(cp !in 0xD800..0xDFFF) { "char cannot be a surrogate code point" }
    return cp
}

/** NaNs are canonicalized when lowered (the spec's deterministic profile). */
private fun f32Bits(v: JsonElement): Int {
    val f = primitive(v, "f32").content.toFloatOrNull() ?: throw IllegalArgumentException("Expected f32, got $v")
    return if (f.isNaN()) CanonicalAbi.CANONICAL_FLOAT32_NAN else java.lang.Float.floatToRawIntBits(f)
}

private fun f64Bits(v: JsonElement): Long {
    val d = primitive(v, "f64").content.toDoubleOrNull() ?: throw IllegalArgumentException("Expected f64, got $v")
    return if (d.isNaN()) CanonicalAbi.CANONICAL_FLOAT64_NAN else java.lang.Double.doubleToRawLongBits(d)
}
