package wasm2kt.runtime.chicory

import com.dylibso.chicory.runtime.Memory
import wasm2kt.runtime.ComponentTrapException
import wasm2kt.runtime.WitReader
import wasm2kt.runtime.WitWriter

/**
 * The Component Model canonical ABI (32-bit linear memory), between linear memory and the binary
 * value format shared with the generated bindings and the Wasmtime bridge (ADR-0015).
 * Spec: component-model/design/mvp/CanonicalABI.md.
 *
 * Validation follows the spec's `trap_if`s (definitions.py): values coming from the guest that
 * break an ABI rule raise [ComponentTrapException]; values coming from the host that do not fit
 * the WIT type raise [IllegalArgumentException].
 */
internal object CanonicalAbi {
    const val MAX_FLAT_PARAMS = 16
    const val MAX_FLAT_RESULTS = 1

    /** Parameters of an async-lowered import beyond this many flat values are passed by pointer. */
    const val MAX_FLAT_ASYNC_PARAMS = 4
    const val MAX_STRING_BYTE_LENGTH = (1L shl 28) - 1
    const val MAX_LIST_BYTE_LENGTH = (1L shl 28) - 1
    const val CANONICAL_FLOAT32_NAN = 0x7fc00000
    const val CANONICAL_FLOAT64_NAN = 0x7ff8000000000000L

    enum class CoreType { I32, I64, F32, F64 }

    val EMPTY_LONG_ARRAY = LongArray(0)
    val FLAT_I32 = listOf(CoreType.I32)
    val FLAT_I64 = listOf(CoreType.I64)
    val FLAT_F32 = listOf(CoreType.F32)
    val FLAT_F64 = listOf(CoreType.F64)
    val FLAT_I32_I32 = listOf(CoreType.I32, CoreType.I32)

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

    internal fun computeMaxCaseAlign(t: WitType.Variant): Int {
        var max = 1
        for ((_, payload) in t.cases) {
            if (payload != null) {
                val a = payload.align
                if (a > max) max = a
            }
        }
        return max
    }

    internal fun computeVariantSize(t: WitType.Variant): Int {
        var maxPayload = 0
        for ((_, payload) in t.cases) {
            if (payload != null) {
                val s = payload.size
                if (s > maxPayload) maxPayload = s
            }
        }
        return alignTo(t.payloadOffset + maxPayload, t.align)
    }

    internal fun computeVariantPayloadFlat(t: WitType.Variant): List<CoreType> {
        val joined = mutableListOf<CoreType>()
        for ((_, payload) in t.cases) {
            if (payload != null) {
                val flat = payload.flat
                for (i in flat.indices) {
                    val ft = flat[i]
                    if (i < joined.size) joined[i] = join(joined[i], ft) else joined += ft
                }
            }
        }
        return joined
    }

    internal fun computeVariantFlat(payloadFlat: List<CoreType>): List<CoreType> =
        if (payloadFlat.isEmpty()) FLAT_I32
        else ArrayList<CoreType>(1 + payloadFlat.size).apply { add(CoreType.I32); addAll(payloadFlat) }

    internal fun computeRecordAlign(types: List<WitType>): Int {
        var max = 1
        for (ft in types) {
            val a = ft.align
            if (a > max) max = a
        }
        return max
    }

    internal fun computeRecordSize(types: List<WitType>): Int {
        var s = 0
        var maxAlign = 1
        for (ft in types) {
            val a = ft.align
            if (a > maxAlign) maxAlign = a
            s = alignTo(s, a) + ft.size
        }
        return alignTo(s, maxAlign)
    }

    internal fun computeFlattenAll(types: List<WitType>): List<CoreType> = when (types.size) {
        0 -> emptyList()
        1 -> types[0].flat
        else -> types.flatMap { it.flat }
    }

    internal fun flagsSize(n: Int): Int = when {
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
    fun lowerOwn(table: Int, id: Int): Int
    fun lowerBorrow(table: Int, id: Int): Int
    fun liftOwn(table: Int, handle: Int): Int
    fun liftBorrow(table: Int, handle: Int): Int

    /**
     * Streams, futures and error contexts (#16): `lift*` takes the guest's handle in [table] (a
     * stream or future table of the plan) and returns the id the other side of the copy uses;
     * `lower*` does the reverse. The host sees them as integer handles.
     */
    fun liftStream(table: Int, handle: Int): Int = unsupportedAsync("stream")
    fun lowerStream(table: Int, id: Int): Int = unsupportedAsync("stream")
    fun liftFuture(table: Int, handle: Int): Int = unsupportedAsync("future")
    fun lowerFuture(table: Int, id: Int): Int = unsupportedAsync("future")
    fun liftErrorContext(handle: Int): Int = unsupportedAsync("error-context")
    fun lowerErrorContext(id: Int): Int = unsupportedAsync("error-context")
}

private fun unsupportedAsync(what: String): Nothing =
    throw UnsupportedOperationException("Passing a $what here is not supported by the Chicory backend")

/**
 * Reads host values from [r] (the binary format) into core wasm values / linear memory. Values that
 * do not fit the WIT type are host errors ([IllegalArgumentException]).
 */
internal class Lowerer(private val cx: AbiContext, private val r: WitReader) {
    fun lowerFlat(t: WitType, out: LongArray, pos: Int): Int {
        when (t) {
            WitType.Bool -> { out[pos] = bool().toLong(); return pos + 1 }
            WitType.S8 -> { out[pos] = r.byte().toLong(); return pos + 1 }
            WitType.U8 -> { out[pos] = r.byte().toLong() and 0xFF; return pos + 1 }
            WitType.S16 -> { out[pos] = r.short().toLong(); return pos + 1 }
            WitType.U16 -> { out[pos] = r.short().toLong() and 0xFFFF; return pos + 1 }
            WitType.S32, WitType.U32 -> { out[pos] = r.int().toLong(); return pos + 1 }
            WitType.S64, WitType.U64 -> { out[pos] = r.long(); return pos + 1 }
            WitType.F32 -> { out[pos] = f32Bits().toLong(); return pos + 1 }
            WitType.F64 -> { out[pos] = f64Bits(); return pos + 1 }
            WitType.Char -> { out[pos] = char().toLong(); return pos + 1 }
            WitType.Str -> {
                val packed = storeString()
                out[pos] = packed.toInt().toLong()
                out[pos + 1] = (packed ushr 32).toInt().toLong()
                return pos + 2
            }
            is WitType.ListOf -> {
                val packed = storeList(t.element)
                out[pos] = packed.toInt().toLong()
                out[pos + 1] = (packed ushr 32).toInt().toLong()
                return pos + 2
            }
            is WitType.Record -> {
                var p = pos
                for (ft in t.fieldTypes) p = lowerFlat(ft, out, p)
                return p
            }
            is WitType.Tuple -> {
                var p = pos
                for (et in t.types) p = lowerFlat(et, out, p)
                return p
            }
            is WitType.Flags -> {
                var p = pos
                for (w in flagWords(t)) out[p++] = w.toLong()
                return p
            }
            is WitType.Variant -> {
                var p = pos
                val index = discriminant(t)
                out[p++] = index.toLong()
                val joined = t.payloadFlat
                val caseType = t.cases[index].second
                if (caseType != null) {
                    val caseFlat = caseType.flat
                    lowerFlat(caseType, out, p)
                    for (i in caseFlat.indices) {
                        val cf = caseFlat[i]
                        val jt = joined[i]
                        if (cf != jt) out[p + i] = coerce(out[p + i], cf, jt)
                    }
                    p += caseFlat.size
                }
                for (i in (p - pos - 1) until joined.size) {
                    out[p++] = 0L
                }
                return p
            }
            is WitType.Own -> { out[pos] = cx.lowerOwn(t.table, r.int()).toLong(); return pos + 1 }
            is WitType.Borrow -> { out[pos] = cx.lowerBorrow(t.table, r.int()).toLong(); return pos + 1 }
            is WitType.Stream -> { out[pos] = cx.lowerStream(t.table, r.int()).toLong(); return pos + 1 }
            is WitType.Future -> { out[pos] = cx.lowerFuture(t.table, r.int()).toLong(); return pos + 1 }
            WitType.ErrorContext -> { out[pos] = cx.lowerErrorContext(r.int()).toLong(); return pos + 1 }
            is WitType.Unsupported -> CanonicalAbi.unsupported(t)
        }
    }

    fun store(t: WitType, ptr: Int) {
        val m = cx.memory
        when (t) {
            WitType.Bool -> m.writeByte(ptr, bool().toByte())
            WitType.S8, WitType.U8 -> m.writeByte(ptr, r.byte())
            WitType.S16, WitType.U16 -> m.writeShort(ptr, r.short())
            WitType.S32, WitType.U32 -> m.writeI32(ptr, r.int())
            WitType.S64, WitType.U64 -> m.writeLong(ptr, r.long())
            WitType.F32 -> m.writeI32(ptr, f32Bits())
            WitType.F64 -> m.writeLong(ptr, f64Bits())
            WitType.Char -> m.writeI32(ptr, char())
            WitType.Str -> {
                val packed = storeString()
                m.writeI32(ptr, packed.toInt())
                m.writeI32(ptr + 4, (packed ushr 32).toInt())
            }
            is WitType.ListOf -> {
                val packed = storeList(t.element)
                m.writeI32(ptr, packed.toInt())
                m.writeI32(ptr + 4, (packed ushr 32).toInt())
            }
            is WitType.Record -> storeFields(t.fieldTypes, ptr)
            is WitType.Tuple -> storeFields(t.types, ptr)
            is WitType.Flags -> {
                val words = flagWords(t)
                when (t.size) {
                    0 -> Unit
                    1 -> m.writeByte(ptr, words[0].toByte())
                    2 -> m.writeShort(ptr, words[0].toShort())
                    else -> words.forEachIndexed { i, w -> m.writeI32(ptr + 4 * i, w) }
                }
            }
            is WitType.Variant -> {
                val index = discriminant(t)
                when (t.discSize) {
                    1 -> m.writeByte(ptr, index.toByte())
                    2 -> m.writeShort(ptr, index.toShort())
                    else -> m.writeI32(ptr, index)
                }
                t.cases[index].second?.let { caseType ->
                    store(caseType, ptr + t.payloadOffset)
                }
            }
            is WitType.Own -> m.writeI32(ptr, cx.lowerOwn(t.table, r.int()))
            is WitType.Borrow -> m.writeI32(ptr, cx.lowerBorrow(t.table, r.int()))
            is WitType.Stream -> m.writeI32(ptr, cx.lowerStream(t.table, r.int()))
            is WitType.Future -> m.writeI32(ptr, cx.lowerFuture(t.table, r.int()))
            WitType.ErrorContext -> m.writeI32(ptr, cx.lowerErrorContext(r.int()))
            is WitType.Unsupported -> CanonicalAbi.unsupported(t)
        }
    }

    private fun storeFields(types: List<WitType>, ptr: Int) {
        var off = 0
        for (ft in types) {
            off = CanonicalAbi.alignTo(off, ft.align)
            store(ft, ptr + off)
            off += ft.size
        }
    }

    /** Stores [tuple] in freshly allocated memory and returns its address. */
    fun storeTuple(tuple: WitType.Tuple): Int {
        val ptr = alloc(tuple.align, tuple.size.toLong())
        store(tuple, ptr)
        return ptr
    }

    /** Stores [tuple] at a guest-provided return pointer, which must be aligned and in bounds. */
    fun storeTupleAt(tuple: WitType.Tuple, ptr: Int) {
        checkRange(cx.memory, ptr, tuple.size.toLong(), tuple.align, "return pointer")
        store(tuple, ptr)
    }

    /** Calls `cabi_realloc`; the guest must return an aligned, in-bounds pointer. */
    private fun alloc(align: Int, size: Long): Int {
        val ptr = cx.realloc(align, size.toInt())
        checkRange(cx.memory, ptr, size, align, "cabi_realloc result")
        return ptr
    }

    private fun packPtrLen(ptr: Int, len: Int): Long = (ptr.toLong() and 0xFFFF_FFFFL) or (len.toLong() shl 32)

    /** The binary format carries UTF-8: copied as is for a UTF-8 guest, transcoded otherwise. */
    private fun storeString(): Long {
        val n = r.length()
        require(n <= CanonicalAbi.MAX_STRING_BYTE_LENGTH) { "String of $n bytes exceeds the Canonical ABI limit" }
        return r.region(n) { a, off ->
            require(Utf8.isValid(a, off, n)) { "String is not valid UTF-8" }
            when (cx.encoding) {
                StringEncoding.UTF8 -> {
                    val ptr = alloc(1, n.toLong())
                    if (n > 0) cx.memory.write(ptr, a, off, n)
                    packPtrLen(ptr, n)
                }
                StringEncoding.UTF16 -> {
                    val s = String(a, off, n, Charsets.UTF_8)
                    val ptr = alloc(2, 2L * s.length)
                    cx.memory.write(ptr, s.toByteArray(Charsets.UTF_16LE))
                    packPtrLen(ptr, s.length)
                }
                StringEncoding.LATIN1_UTF16 -> {
                    val s = String(a, off, n, Charsets.UTF_8)
                    if (s.all { it.code <= 0xFF }) {
                        val ptr = alloc(2, s.length.toLong())
                        cx.memory.write(ptr, s.toByteArray(Charsets.ISO_8859_1))
                        packPtrLen(ptr, s.length)
                    } else {
                        val ptr = alloc(2, 2L * s.length)
                        cx.memory.write(ptr, s.toByteArray(Charsets.UTF_16LE))
                        packPtrLen(ptr, s.length or UTF16_TAG)
                    }
                }
            }
        }
    }

    /** Lists of integers are copied as is; other elements are stored one by one. */
    private fun storeList(element: WitType): Long {
        val n = r.length()
        val size = element.size
        val byteLength = size.toLong() * n
        require(byteLength <= CanonicalAbi.MAX_LIST_BYTE_LENGTH) { "List of $byteLength bytes exceeds the Canonical ABI limit" }
        val ptr = alloc(element.align, byteLength)
        if (element.isRawInteger) {
            if (byteLength > 0) r.region(byteLength.toInt()) { a, off -> cx.memory.write(ptr, a, off, byteLength.toInt()) }
        } else {
            for (i in 0 until n) store(element, ptr + i * size)
        }
        return packPtrLen(ptr, n)
    }

    private fun bool(): Int = when (val b = r.byte().toInt()) {
        0, 1 -> b
        else -> throw IllegalArgumentException("Invalid bool $b")
    }

    private fun char(): Int {
        val cp = r.int()
        require(cp in 0..0x10FFFF && cp !in 0xD800..0xDFFF) { "U+${Integer.toHexString(cp).uppercase()} is not a Unicode scalar value" }
        return cp
    }

    /** NaNs are canonicalized when lowered (the spec's deterministic profile). */
    private fun f32Bits(): Int {
        val bits = r.int()
        return if (java.lang.Float.intBitsToFloat(bits).isNaN()) CanonicalAbi.CANONICAL_FLOAT32_NAN else bits
    }

    private fun f64Bits(): Long {
        val bits = r.long()
        return if (java.lang.Double.longBitsToDouble(bits).isNaN()) CanonicalAbi.CANONICAL_FLOAT64_NAN else bits
    }

    private fun discriminant(t: WitType.Variant): Int {
        val index = when (CanonicalAbi.discriminantSize(t.cases.size)) {
            1 -> r.byte().toInt() and 0xFF
            2 -> r.short().toInt() and 0xFFFF
            else -> r.int()
        }
        require(index in t.cases.indices) { "Discriminant ${unsigned(index)} out of range for ${t.cases.size} cases" }
        return index
    }

    /** Flags arrive as the Canonical ABI's words; bits past the declared flags are a host error. */
    private fun flagWords(t: WitType.Flags): IntArray {
        val n = t.names.size
        val words = when {
            n == 0 -> IntArray(0)
            n <= 8 -> intArrayOf(r.byte().toInt() and 0xFF)
            n <= 16 -> intArrayOf(r.short().toInt() and 0xFFFF)
            else -> IntArray((n + 31) / 32) { r.int() }
        }
        val rest = n % 32
        if (rest != 0 && words.isNotEmpty()) {
            require(words.last() ushr rest == 0) { "Flags value sets bits beyond the $n declared flags" }
        }
        return words
    }

    private fun coerce(value: Long, from: CanonicalAbi.CoreType, to: CanonicalAbi.CoreType): Long = when {
        from == to -> value
        to == CanonicalAbi.CoreType.I32 -> value // f32 bits already live in the low 32 bits
        else -> when (from) {
            CanonicalAbi.CoreType.I32, CanonicalAbi.CoreType.F32 -> value and 0xFFFF_FFFFL
            else -> value
        }
    }
}

/**
 * Reads guest values from core wasm values / linear memory and writes them to [w] in the binary
 * format. Values that break an ABI rule trap ([ComponentTrapException]).
 */
internal class Lifter(private val cx: AbiContext, private val w: WitWriter) {
    class FlatReader(private val values: LongArray) {
        private var i = 0
        fun next(): Long = values[i++]
        fun skip(count: Int) { i += count }
        internal fun sliceUncoerced(joined: List<CanonicalAbi.CoreType>, caseFlat: List<CanonicalAbi.CoreType>): FlatReader {
            val start = i
            i += joined.size
            val uncoerced = LongArray(caseFlat.size) { k ->
                val raw = values[start + k]
                val from = joined[k]
                val to = caseFlat[k]
                when {
                    from == to -> raw
                    to == CanonicalAbi.CoreType.I32 || to == CanonicalAbi.CoreType.F32 -> raw.toInt().toLong()
                    else -> raw
                }
            }
            return FlatReader(uncoerced)
        }
    }

    fun liftFlat(t: WitType, r: FlatReader) {
        when (t) {
            WitType.Bool -> w.bool(r.next().toInt() != 0)
            WitType.S8, WitType.U8 -> w.byte(r.next().toInt())
            WitType.S16, WitType.U16 -> w.short(r.next().toInt())
            WitType.S32, WitType.U32 -> w.int(r.next().toInt())
            WitType.S64, WitType.U64 -> w.long(r.next())
            WitType.F32 -> f32(r.next().toInt())
            WitType.F64 -> f64(r.next())
            WitType.Char -> char(r.next().toInt())
            WitType.Str -> loadString(r.next().toInt(), r.next().toInt())
            is WitType.ListOf -> loadList(r.next().toInt(), r.next().toInt(), t.element)
            is WitType.Record -> for (ft in t.fieldTypes) liftFlat(ft, r)
            is WitType.Tuple -> for (et in t.types) liftFlat(et, r)
            is WitType.Flags -> flags(IntArray((t.names.size + 31) / 32) { r.next().toInt() }, t)
            is WitType.Variant -> {
                val index = r.next().toInt()
                checkDiscriminant(index, t)
                val joined = t.payloadFlat
                val ct = t.cases[index].second
                if (ct == null) {
                    r.skip(joined.size)
                    discriminant(t, index)
                } else {
                    val caseFlat = ct.flat
                    var needsUncoerce = false
                    for (k in caseFlat.indices) {
                        if (joined[k] != caseFlat[k]) {
                            needsUncoerce = true
                            break
                        }
                    }
                    if (needsUncoerce) {
                        val sub = r.sliceUncoerced(joined, caseFlat)
                        discriminant(t, index)
                        liftFlat(ct, sub)
                    } else {
                        discriminant(t, index)
                        liftFlat(ct, r)
                        val rem = joined.size - caseFlat.size
                        if (rem > 0) r.skip(rem)
                    }
                }
            }
            is WitType.Own -> w.int(cx.liftOwn(t.table, r.next().toInt()))
            is WitType.Borrow -> w.int(cx.liftBorrow(t.table, r.next().toInt()))
            is WitType.Stream -> w.int(cx.liftStream(t.table, r.next().toInt()))
            is WitType.Future -> w.int(cx.liftFuture(t.table, r.next().toInt()))
            WitType.ErrorContext -> w.int(cx.liftErrorContext(r.next().toInt()))
            is WitType.Unsupported -> CanonicalAbi.unsupported(t)
        }
    }

    fun load(t: WitType, ptr: Int) {
        val m = cx.memory
        when (t) {
            WitType.Bool -> w.bool(m.read(ptr).toInt() != 0)
            WitType.S8, WitType.U8 -> w.byte(m.read(ptr).toInt())
            WitType.S16, WitType.U16 -> w.short(m.readShort(ptr).toInt())
            WitType.S32, WitType.U32 -> w.int(m.readInt(ptr))
            WitType.S64, WitType.U64 -> w.long(m.readLong(ptr))
            WitType.F32 -> f32(m.readInt(ptr))
            WitType.F64 -> f64(m.readLong(ptr))
            WitType.Char -> char(m.readInt(ptr))
            WitType.Str -> loadString(m.readInt(ptr), m.readInt(ptr + 4))
            is WitType.ListOf -> loadList(m.readInt(ptr), m.readInt(ptr + 4), t.element)
            is WitType.Record -> loadFields(t.fieldTypes, ptr)
            is WitType.Tuple -> loadFields(t.types, ptr)
            is WitType.Flags -> flags(
                when (t.size) {
                    0 -> IntArray(0)
                    1 -> intArrayOf(m.read(ptr).toInt() and 0xFF)
                    2 -> intArrayOf(m.readShort(ptr).toInt() and 0xFFFF)
                    else -> IntArray((t.names.size + 31) / 32) { i -> m.readInt(ptr + 4 * i) }
                },
                t,
            )
            is WitType.Variant -> {
                val index = when (t.discSize) {
                    1 -> m.read(ptr).toInt() and 0xFF
                    2 -> m.readShort(ptr).toInt() and 0xFFFF
                    else -> m.readInt(ptr)
                }
                checkDiscriminant(index, t)
                discriminant(t, index)
                t.cases[index].second?.let { load(it, ptr + t.payloadOffset) }
            }
            is WitType.Own -> w.int(cx.liftOwn(t.table, m.readInt(ptr)))
            is WitType.Borrow -> w.int(cx.liftBorrow(t.table, m.readInt(ptr)))
            is WitType.Stream -> w.int(cx.liftStream(t.table, m.readInt(ptr)))
            is WitType.Future -> w.int(cx.liftFuture(t.table, m.readInt(ptr)))
            WitType.ErrorContext -> w.int(cx.liftErrorContext(m.readInt(ptr)))
            is WitType.Unsupported -> CanonicalAbi.unsupported(t)
        }
    }

    private fun loadFields(types: List<WitType>, ptr: Int) {
        var off = 0
        for (ft in types) {
            off = CanonicalAbi.alignTo(off, ft.align)
            load(ft, ptr + off)
            off += ft.size
        }
    }

    /** Loads [tuple] from a guest-provided pointer (spilled params or results), which must be aligned and in bounds. */
    fun loadTuple(ptr: Int, tuple: WitType.Tuple) {
        checkRange(cx.memory, ptr, tuple.size.toLong(), tuple.align, "tuple pointer")
        load(tuple, ptr)
    }

    /** A UTF-8 guest string is validated and copied as is; other encodings are transcoded to UTF-8. */
    private fun loadString(ptr: Int, taggedLen: Int) {
        val m = cx.memory
        val tagged = taggedLen.toLong() and 0xFFFF_FFFFL
        val utf16Tag = UTF16_TAG.toLong() and 0xFFFF_FFFFL
        val align: Int
        val byteLength: Long
        val kind: StringEncoding
        when (cx.encoding) {
            StringEncoding.UTF8 -> {
                align = 1
                byteLength = tagged
                kind = StringEncoding.UTF8
            }
            StringEncoding.UTF16 -> {
                align = 2
                byteLength = 2 * tagged
                kind = StringEncoding.UTF16
            }
            StringEncoding.LATIN1_UTF16 -> {
                align = 2
                if (tagged and utf16Tag != 0L) {
                    byteLength = 2 * (tagged xor utf16Tag)
                    kind = StringEncoding.UTF16
                } else {
                    byteLength = tagged
                    kind = StringEncoding.LATIN1_UTF16
                }
            }
        }
        if (byteLength > CanonicalAbi.MAX_STRING_BYTE_LENGTH) trap("String byte length $byteLength exceeds the maximum")
        checkRange(m, ptr, byteLength, align, "string")
        if (byteLength == 0L) {
            w.int(0)
            return
        }
        val n = byteLength.toInt()
        when (kind) {
            StringEncoding.UTF8 -> {
                w.int(n)
                val at = copyFromMemory(m, ptr, n)
                if (!Utf8.isValid(w.buffer, at, n)) trap("Invalid ${cx.encoding.name.lowercase()} string at ${unsigned(ptr)}")
            }
            StringEncoding.UTF16 -> {
                val bytes = m.readBytes(ptr, n)
                val chars = CharArray(bytes.size / 2) { i -> ((bytes[2 * i].toInt() and 0xFF) or ((bytes[2 * i + 1].toInt() and 0xFF) shl 8)).toChar() }
                if (!Utf8.isValidUtf16(chars)) trap("Invalid ${cx.encoding.name.lowercase()} string at ${unsigned(ptr)}")
                w.string(String(chars))
            }
            StringEncoding.LATIN1_UTF16 -> {
                val bytes = m.readBytes(ptr, n)
                w.string(String(bytes, Charsets.ISO_8859_1))
            }
        }
    }

    /** Lists of integers are copied as is; other elements are loaded one by one. */
    private fun loadList(ptr: Int, len: Int, element: WitType) {
        val size = element.size
        val length = len.toLong() and 0xFFFF_FFFFL
        val byteLength = length * size
        if (byteLength > CanonicalAbi.MAX_LIST_BYTE_LENGTH) trap("List byte length $byteLength exceeds the maximum")
        checkRange(cx.memory, ptr, byteLength, element.align, "list")
        w.length(length.toInt())
        if (element.isRawInteger) {
            if (byteLength > 0) copyFromMemory(cx.memory, ptr, byteLength.toInt())
        } else {
            for (i in 0 until length.toInt()) load(element, ptr + i * size)
        }
    }

    companion object {
        private const val BULK_COPY_THRESHOLD = 256
        private const val BULK_COPY_CHUNK = 65536
    }

    /**
     * Copies [byteLength] already-bounds-checked bytes from [memory] at [ptr] directly into [w],
     * returning the start offset in [w].
     *
     * `com.dylibso.chicory.runtime.Memory` only provides bulk reads via `readBytes(addr, len)`
     * (there is no overload writing into an existing `ByteArray`), so payloads of at least
     * [BULK_COPY_THRESHOLD] bytes are copied in [BULK_COPY_CHUNK]-byte chunks to bound temporary
     * allocation while avoiding per-byte or per-long calls.
     */
    private fun copyFromMemory(memory: Memory, ptr: Int, byteLength: Int): Int {
        val start = w.reserve(byteLength)
        val buf = w.buffer
        if (byteLength >= BULK_COPY_THRESHOLD) {
            var offset = 0
            while (offset < byteLength) {
                val chunkLen = minOf(byteLength - offset, BULK_COPY_CHUNK)
                val chunk = memory.readBytes(ptr + offset, chunkLen)
                System.arraycopy(chunk, 0, buf, start + offset, chunkLen)
                offset += chunkLen
            }
            return start
        }
        var dst = start
        var src = ptr
        val end8 = ptr + (byteLength and -8)
        while (src < end8) {
            val v = memory.readLong(src)
            buf[dst] = v.toByte()
            buf[dst + 1] = (v ushr 8).toByte()
            buf[dst + 2] = (v ushr 16).toByte()
            buf[dst + 3] = (v ushr 24).toByte()
            buf[dst + 4] = (v ushr 32).toByte()
            buf[dst + 5] = (v ushr 40).toByte()
            buf[dst + 6] = (v ushr 48).toByte()
            buf[dst + 7] = (v ushr 56).toByte()
            src += 8
            dst += 8
        }
        val end = ptr + byteLength
        while (src < end) {
            buf[dst++] = memory.read(src++)
        }
        return start
    }

    private fun checkDiscriminant(index: Int, t: WitType.Variant) {
        if (index !in t.cases.indices) trap("Discriminant ${unsigned(index)} out of range for ${t.cases.size} cases")
    }

    private fun discriminant(t: WitType.Variant, index: Int) {
        when (t.discSize) {
            1 -> w.byte(index)
            2 -> w.short(index)
            else -> w.int(index)
        }
    }

    /** Bits past the declared flags are ignored, as in the spec's `unpack_flags_from_int`. */
    private fun flags(raw: IntArray, t: WitType.Flags) {
        val n = t.names.size
        val words = raw.copyOf()
        val rest = n % 32
        if (rest != 0 && words.isNotEmpty()) words[words.size - 1] = words[words.size - 1] and ((1 shl rest) - 1)
        when {
            n == 0 -> Unit
            n <= 8 -> w.byte(words[0])
            n <= 16 -> w.short(words[0])
            else -> words.forEach { w.int(it) }
        }
    }

    private fun char(codePoint: Int) {
        if (codePoint !in 0..0x10FFFF || codePoint in 0xD800..0xDFFF) trap("Invalid char code point ${unsigned(codePoint)}")
        w.int(codePoint)
    }

    /** NaNs are canonicalized when lifted (spec `decode_i32_as_float` / `decode_i64_as_float`). */
    private fun f32(bits: Int) {
        w.int(if (java.lang.Float.intBitsToFloat(bits).isNaN()) CanonicalAbi.CANONICAL_FLOAT32_NAN else bits)
    }

    private fun f64(bits: Long) {
        w.long(if (java.lang.Double.longBitsToDouble(bits).isNaN()) CanonicalAbi.CANONICAL_FLOAT64_NAN else bits)
    }
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

/**
 * Encoding checks without `CharsetDecoder`, which is costly to create on Android (#58).
 */
internal object Utf8 {
    /** Well-formed UTF-8 (no overlong forms, surrogates or code points past U+10FFFF). */
    fun isValid(a: ByteArray, offset: Int, length: Int): Boolean {
        var i = offset
        val end = offset + length
        while (i < end) {
            val b0 = a[i].toInt() and 0xFF
            if (b0 < 0x80) {
                i++
                continue
            }
            val n = when {
                b0 in 0xC2..0xDF -> 1
                b0 in 0xE0..0xEF -> 2
                b0 in 0xF0..0xF4 -> 3
                else -> return false
            }
            if (i + n >= end) return false
            val b1 = a[i + 1].toInt() and 0xFF
            when (b0) {
                0xE0 -> if (b1 !in 0xA0..0xBF) return false
                0xED -> if (b1 !in 0x80..0x9F) return false
                0xF0 -> if (b1 !in 0x90..0xBF) return false
                0xF4 -> if (b1 !in 0x80..0x8F) return false
                else -> if (b1 !in 0x80..0xBF) return false
            }
            for (k in 2..n) if ((a[i + k].toInt() and 0xC0) != 0x80) return false
            i += n + 1
        }
        return true
    }

    /** Every surrogate is part of a pair. */
    fun isValidUtf16(chars: CharArray): Boolean {
        var i = 0
        while (i < chars.size) {
            val c = chars[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= chars.size || !Character.isLowSurrogate(chars[i + 1])) return false
                i += 2
            } else if (Character.isLowSurrogate(c)) {
                return false
            } else {
                i++
            }
        }
        return true
    }
}
