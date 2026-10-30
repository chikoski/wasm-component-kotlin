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

/**
 * The Component Model canonical ABI (32-bit linear memory), operating on the JSON value shape
 * shared with the Wasmtime bridge. Spec: component-model/design/mvp/CanonicalABI.md.
 */
internal object CanonicalAbi {
    const val MAX_FLAT_PARAMS = 16
    const val MAX_FLAT_RESULTS = 1

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
            WitType.S8, WitType.U8, WitType.S16, WitType.U16, WitType.S32 -> out += int(v).toLong()
            WitType.U32 -> out += uint(v)
            WitType.S64 -> out += long(v)
            WitType.U64 -> out += ulong(v)
            WitType.F32 -> out += f32Bits(v.jsonPrimitive.content.toFloat())
            WitType.F64 -> out += java.lang.Double.doubleToRawLongBits(v.jsonPrimitive.content.toDouble())
            WitType.Char -> out += char(v).toLong()
            WitType.Str -> {
                val (ptr, len) = storeString(v.jsonPrimitive.content)
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
                val a = v.jsonArray
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
            WitType.S8, WitType.U8 -> m.writeByte(ptr, int(v).toByte())
            WitType.S16, WitType.U16 -> m.writeShort(ptr, int(v).toShort())
            WitType.S32 -> m.writeI32(ptr, int(v))
            WitType.U32 -> m.writeI32(ptr, uint(v).toInt())
            WitType.S64 -> m.writeLong(ptr, long(v))
            WitType.U64 -> m.writeLong(ptr, ulong(v))
            WitType.F32 -> m.writeF32(ptr, v.jsonPrimitive.content.toFloat())
            WitType.F64 -> m.writeF64(ptr, v.jsonPrimitive.content.toDouble())
            WitType.Char -> m.writeI32(ptr, char(v))
            WitType.Str -> {
                val (p, len) = storeString(v.jsonPrimitive.content)
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
                val a = v.jsonArray
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
        val ptr = cx.realloc(CanonicalAbi.alignOf(tuple), CanonicalAbi.sizeOf(tuple))
        store(JsonArray(values), tuple, ptr)
        return ptr
    }

    private fun storeString(s: String): Pair<Int, Int> {
        val m = cx.memory
        return when (cx.encoding) {
            StringEncoding.UTF8 -> {
                val bytes = s.toByteArray(Charsets.UTF_8)
                val ptr = cx.realloc(1, bytes.size)
                m.write(ptr, bytes)
                ptr to bytes.size
            }
            StringEncoding.UTF16 -> {
                val bytes = s.toByteArray(Charsets.UTF_16LE)
                val ptr = cx.realloc(2, bytes.size)
                m.write(ptr, bytes)
                ptr to s.length
            }
            StringEncoding.LATIN1_UTF16 -> {
                if (s.all { it.code <= 0xFF }) {
                    val bytes = s.toByteArray(Charsets.ISO_8859_1)
                    val ptr = cx.realloc(2, bytes.size)
                    m.write(ptr, bytes)
                    ptr to bytes.size
                } else {
                    val bytes = s.toByteArray(Charsets.UTF_16LE)
                    val ptr = cx.realloc(2, bytes.size)
                    m.write(ptr, bytes)
                    ptr to (s.length or UTF16_TAG)
                }
            }
        }
    }

    private fun storeList(items: JsonArray, element: WitType): Pair<Int, Int> {
        val size = CanonicalAbi.sizeOf(element)
        val ptr = cx.realloc(CanonicalAbi.alignOf(element), size * items.size)
        items.forEachIndexed { i, item -> store(item, element, ptr + i * size) }
        return ptr to items.size
    }

    private fun flagWords(v: JsonElement, t: WitType.Flags): IntArray {
        val words = IntArray((t.names.size + 31) / 32)
        if (v is JsonArray) {
            for (name in v) {
                val i = t.names.indexOf(name.jsonPrimitive.content)
                require(i >= 0) { "Unknown flag '${name.jsonPrimitive.content}'" }
                words[i / 32] = words[i / 32] or (1 shl (i % 32))
            }
        } else {
            val bits = v.jsonPrimitive.content.toULong()
            for (i in t.names.indices) if (i < 64 && (bits shr i) and 1uL == 1uL) words[i / 32] = words[i / 32] or (1 shl (i % 32))
        }
        return words
    }

    /** Resolves the case index and payload, accepting every shape the Wasmtime bridge accepts. */
    private fun variantCase(v: JsonElement, t: WitType.Variant): Pair<Int, JsonElement?> = when (t.kind) {
        WitType.VariantKind.ENUM -> caseIndex(t, v.jsonPrimitive.content) to null
        WitType.VariantKind.VARIANT -> {
            val o = v.jsonObject
            caseIndex(t, o.getValue("case").jsonPrimitive.content) to o["value"]
        }
        WitType.VariantKind.OPTION -> when {
            v is JsonNull -> 0 to null
            v is JsonObject && "option" in v -> when (val tag = v.getValue("option").jsonPrimitive.content) {
                "none" -> 0 to null
                "some" -> 1 to (v["value"] ?: JsonNull)
                else -> error("Invalid option tag '$tag'")
            }
            else -> 1 to v
        }
        WitType.VariantKind.RESULT -> {
            val o = v.jsonObject
            when (val tag = o.getValue("result").jsonPrimitive.content) {
                "ok" -> 0 to o["value"]
                "err" -> 1 to o["error"]
                else -> error("Invalid result tag '$tag'")
            }
        }
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
}

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
        WitType.F32 -> JsonPrimitive(java.lang.Float.intBitsToFloat(r.next().toInt()))
        WitType.F64 -> JsonPrimitive(java.lang.Double.longBitsToDouble(r.next()))
        WitType.Char -> charJson(r.next().toInt())
        WitType.Str -> loadString(r.next().toInt(), r.next().toInt())
        is WitType.ListOf -> loadList(r.next().toInt(), r.next().toInt(), t.element)
        is WitType.Record -> JsonObject(t.fields.associate { (name, ft) -> name to liftFlat(ft, r) })
        is WitType.Tuple -> JsonArray(t.types.map { liftFlat(it, r) })
        is WitType.Flags -> flagsJson(IntArray((t.names.size + 31) / 32) { r.next().toInt() })
        is WitType.Variant -> {
            val index = r.next().toInt()
            require(index in t.cases.indices) { "Invalid discriminant $index" }
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
            WitType.F32 -> JsonPrimitive(m.readFloat(ptr))
            WitType.F64 -> JsonPrimitive(m.readDouble(ptr))
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
            )
            is WitType.Variant -> {
                val discSize = CanonicalAbi.discriminantSize(t.cases.size)
                val index = when (discSize) {
                    1 -> m.read(ptr).toInt() and 0xFF
                    2 -> m.readShort(ptr).toInt() and 0xFFFF
                    else -> m.readInt(ptr)
                }
                require(index in t.cases.indices) { "Invalid discriminant $index" }
                val maxAlign = t.cases.maxOfOrNull { c -> c.second?.let { CanonicalAbi.alignOf(it) } ?: 1 } ?: 1
                val payload = t.cases[index].second?.let { load(it, ptr + CanonicalAbi.alignTo(discSize, maxAlign)) }
                variantJson(t, index, payload)
            }
            is WitType.Own -> cx.liftOwn(t.table, m.readInt(ptr))
            is WitType.Borrow -> cx.liftBorrow(t.table, m.readInt(ptr))
            is WitType.Unsupported -> CanonicalAbi.unsupported(t)
        }
    }

    fun loadTuple(ptr: Int, types: List<WitType>): List<JsonElement> = load(WitType.Tuple(types), ptr).jsonArray

    private fun loadString(ptr: Int, taggedLen: Int): JsonPrimitive {
        val m = cx.memory
        return JsonPrimitive(
            when (cx.encoding) {
                StringEncoding.UTF8 -> String(m.readBytes(ptr, taggedLen), Charsets.UTF_8)
                StringEncoding.UTF16 -> String(m.readBytes(ptr, taggedLen * 2), Charsets.UTF_16LE)
                StringEncoding.LATIN1_UTF16 ->
                    if (taggedLen and UTF16_TAG != 0) {
                        String(m.readBytes(ptr, (taggedLen and UTF16_TAG.inv()) * 2), Charsets.UTF_16LE)
                    } else {
                        String(m.readBytes(ptr, taggedLen), Charsets.ISO_8859_1)
                    }
            },
        )
    }

    private fun loadList(ptr: Int, len: Int, element: WitType): JsonArray {
        val size = CanonicalAbi.sizeOf(element)
        return JsonArray(List(len) { i -> load(element, ptr + i * size) })
    }

    private fun flagsJson(words: IntArray): JsonPrimitive {
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
        require(codePoint in 0..0x10FFFF && codePoint !in 0xD800..0xDFFF) { "Invalid char code point $codePoint" }
        return JsonPrimitive(String(Character.toChars(codePoint)))
    }

    private fun u64(v: Long): JsonPrimitive = if (v >= 0) JsonPrimitive(v) else JsonPrimitive(v.toULong().toString())
}

private const val UTF16_TAG = 1 shl 31

private fun bool(v: JsonElement): Boolean = v.jsonPrimitive.content.toBooleanStrict()
private fun int(v: JsonElement): Int = v.jsonPrimitive.content.toLong().toInt()
private fun uint(v: JsonElement): Long = v.jsonPrimitive.content.toLong() and 0xFFFF_FFFFL
private fun long(v: JsonElement): Long = v.jsonPrimitive.content.toLong()
private fun ulong(v: JsonElement): Long {
    val s = v.jsonPrimitive.content
    return s.toLongOrNull() ?: s.toULong().toLong()
}
private fun char(v: JsonElement): Int = v.jsonPrimitive.content.codePointAt(0)
private fun f32Bits(f: Float): Long = java.lang.Float.floatToRawIntBits(f).toLong()
