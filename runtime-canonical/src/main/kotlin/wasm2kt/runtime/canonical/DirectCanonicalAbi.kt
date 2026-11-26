@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.runtime.canonical

import wasm2kt.runtime.ComponentTrapException

/**
 * Direct static Canonical ABI validation and encoding/decoding helpers for generated Phase 2 AOT
 * bindings (ADR-0023).
 *
 * Kept strictly free of wire-format and dynamic-dispatch types (`WitWriter`, `WitReader`,
 * `WitType`, `FuncType`, `Lowerer`, `Lifter`, `CanonicalAbi`) so R8 strips those classes completely
 * when an AOT component has no mapped external library imports.
 */
@InternalWasm2KtApi
object DirectCanonicalAbi {
    const val MAX_STRING_BYTE_LENGTH: Long = (1L shl 28) - 1
    const val MAX_LIST_BYTE_LENGTH: Long = (1L shl 28) - 1
    const val CANONICAL_FLOAT32_NAN: Int = 0x7fc00000
    const val CANONICAL_FLOAT64_NAN: Long = 0x7ff8000000000000L
    const val UTF16_TAG: Int = 1 shl 31
    private val EMPTY_BYTES = ByteArray(0)

    fun packPtrLen(ptr: Int, len: Int): Long =
        (ptr.toLong() and 0xFFFF_FFFFL) or (len.toLong() shl 32)

    fun unpackPtr(packed: Long): Int = packed.toInt()

    fun unpackLen(packed: Long): Int = (packed ushr 32).toInt()

    fun checkMemoryRange(memory: CanonicalMemory, ptr: Int, byteLength: Long, align: Int, what: String = "memory pointer") {
        checkRange(memory, ptr, byteLength, align, what)
    }

    fun checkReallocResult(memory: CanonicalMemory, ptr: Int, align: Int, newSize: Int): Int {
        checkRange(memory, ptr, newSize.toLong() and 0xFFFF_FFFFL, align, "cabi_realloc result")
        return ptr
    }

    fun lowerBool(v: Boolean): Int = if (v) 1 else 0

    fun liftBool(v: Int): Boolean = v != 0

    fun lowerChar(codePoint: Int): Int {
        require(codePoint in 0..0x10FFFF && codePoint !in 0xD800..0xDFFF) {
            "U+${Integer.toHexString(codePoint).uppercase()} is not a Unicode scalar value"
        }
        return codePoint
    }

    fun liftChar(codePoint: Int): Int {
        if (codePoint !in 0..0x10FFFF || codePoint in 0xD800..0xDFFF) {
            trap("Invalid char code point ${unsigned(codePoint)}")
        }
        return codePoint
    }

    fun lowerF32(v: Float): Float =
        if (v.isNaN()) java.lang.Float.intBitsToFloat(CANONICAL_FLOAT32_NAN) else v

    fun lowerF32Bits(v: Float): Int =
        if (v.isNaN()) CANONICAL_FLOAT32_NAN else java.lang.Float.floatToRawIntBits(v)

    fun liftF32(v: Float): Float =
        if (v.isNaN()) java.lang.Float.intBitsToFloat(CANONICAL_FLOAT32_NAN) else v

    fun liftF32Bits(bits: Int): Float {
        val f = java.lang.Float.intBitsToFloat(bits)
        return if (f.isNaN()) java.lang.Float.intBitsToFloat(CANONICAL_FLOAT32_NAN) else f
    }

    fun lowerF64(v: Double): Double =
        if (v.isNaN()) java.lang.Double.longBitsToDouble(CANONICAL_FLOAT64_NAN) else v

    fun lowerF64Bits(v: Double): Long =
        if (v.isNaN()) CANONICAL_FLOAT64_NAN else java.lang.Double.doubleToRawLongBits(v)

    fun liftF64(v: Double): Double =
        if (v.isNaN()) java.lang.Double.longBitsToDouble(CANONICAL_FLOAT64_NAN) else v

    fun liftF64Bits(bits: Long): Double {
        val d = java.lang.Double.longBitsToDouble(bits)
        return if (d.isNaN()) java.lang.Double.longBitsToDouble(CANONICAL_FLOAT64_NAN) else d
    }

    fun checkLiftDiscriminant(index: Int, count: Int): Int {
        if (index !in 0 until count) {
            trap("Discriminant ${unsigned(index)} out of range for $count cases")
        }
        return index
    }

    fun checkLowerFlags32(raw: Int, count: Int): Int {
        if (count in 1..31 && (raw ushr count) != 0) {
            throw IllegalArgumentException("Flags value sets bits beyond the $count declared flags")
        }
        return raw
    }

    fun checkLowerFlags64(raw: Long, count: Int): Long {
        if (count in 1..63 && (raw ushr count) != 0L) {
            throw IllegalArgumentException("Flags value sets bits beyond the $count declared flags")
        }
        return raw
    }

    fun maskLiftFlags32(raw: Int, count: Int): Int = when {
        count <= 0 -> 0
        count >= 32 -> raw
        else -> raw and ((1 shl count) - 1)
    }

    fun maskLiftFlags64(raw: Long, count: Int): Long = when {
        count <= 0 -> 0L
        count >= 64 -> raw
        else -> raw and ((1L shl count) - 1L)
    }

    /**
     * Validates that [s] contains no lone UTF-16 surrogates and returns its exact UTF-8 byte array.
     * Throws [IllegalArgumentException] before any guest `realloc` call if invalid.
     */
    fun encodeUtf8(s: String): ByteArray {
        val n = s.length
        if (n == 0) return EMPTY_BYTES
        var utf8Len = 0L
        var i = 0
        while (i < n) {
            val c = s[i].code
            when {
                c < 0x80 -> utf8Len += 1
                c < 0x800 -> utf8Len += 2
                c in 0xD800..0xDBFF && i + 1 < n && s[i + 1].code in 0xDC00..0xDFFF -> {
                    utf8Len += 4
                    i++
                }
                c in 0xD800..0xDFFF -> throw IllegalArgumentException("String has a lone surrogate at index $i")
                else -> utf8Len += 3
            }
            i++
        }
        require(utf8Len <= MAX_STRING_BYTE_LENGTH) { "String of $utf8Len bytes exceeds the Canonical ABI limit" }
        val len = utf8Len.toInt()
        val out = ByteArray(len)
        var p = 0
        i = 0
        while (i < n) {
            val c = s[i].code
            when {
                c < 0x80 -> out[p++] = c.toByte()
                c < 0x800 -> {
                    out[p++] = (0xC0 or (c shr 6)).toByte()
                    out[p++] = (0x80 or (c and 0x3F)).toByte()
                }
                c in 0xD800..0xDBFF -> {
                    val low = s[++i].code
                    val cp = 0x10000 + ((c - 0xD800) shl 10) + (low - 0xDC00)
                    out[p++] = (0xF0 or (cp shr 18)).toByte()
                    out[p++] = (0x80 or ((cp shr 12) and 0x3F)).toByte()
                    out[p++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                    out[p++] = (0x80 or (cp and 0x3F)).toByte()
                }
                else -> {
                    out[p++] = (0xE0 or (c shr 12)).toByte()
                    out[p++] = (0x80 or ((c shr 6) and 0x3F)).toByte()
                    out[p++] = (0x80 or (c and 0x3F)).toByte()
                }
            }
            i++
        }
        return out
    }

    /** Validates that [s] contains no lone UTF-16 surrogates and returns its UTF-16LE bytes. */
    fun encodeUtf16Le(s: String): ByteArray {
        validateHostUtf16(s)
        val byteLen = 2L * s.length
        require(byteLen <= MAX_STRING_BYTE_LENGTH) { "String of $byteLen bytes exceeds the Canonical ABI limit" }
        if (s.isEmpty()) return EMPTY_BYTES
        val out = ByteArray(byteLen.toInt())
        for (i in s.indices) {
            val code = s[i].code
            out[2 * i] = code.toByte()
            out[2 * i + 1] = (code ushr 8).toByte()
        }
        return out
    }

    /**
     * Validates [s] as well-formed UTF-16 and returns Latin-1 bytes if every char fits in `0..0xFF`,
     * or `null` if UTF-16LE encoding is required.
     */
    fun encodeLatin1OrNull(s: String): ByteArray? {
        validateHostUtf16(s)
        for (i in s.indices) {
            if (s[i].code > 0xFF) return null
        }
        val len = s.length
        require(len.toLong() <= MAX_STRING_BYTE_LENGTH) { "String of $len bytes exceeds the Canonical ABI limit" }
        if (len == 0) return EMPTY_BYTES
        return ByteArray(len) { i -> s[i].code.toByte() }
    }

    fun validateHostUtf16(s: String) {
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= n || !Character.isLowSurrogate(s[i + 1])) {
                    throw IllegalArgumentException("String has a lone surrogate at index $i")
                }
                i += 2
            } else if (Character.isLowSurrogate(c)) {
                throw IllegalArgumentException("String has a lone surrogate at index $i")
            } else {
                i++
            }
        }
    }

    /**
     * Computes the byte length of a string buffer encoded in linear memory from a guest
     * `(taggedLen, encoding)` pair, trapping on Canonical ABI length overflow.
     */
    internal fun stringBufferByteLength(taggedLen: Int, encoding: StringEncoding): Long {
        val tagged = taggedLen.toLong() and 0xFFFF_FFFFL
        val utf16Tag = UTF16_TAG.toLong() and 0xFFFF_FFFFL
        val byteLength = when (encoding) {
            StringEncoding.UTF8 -> tagged
            StringEncoding.UTF16 -> 2L * tagged
            StringEncoding.LATIN1_UTF16 -> if (tagged and utf16Tag != 0L) 2L * (tagged xor utf16Tag) else tagged
        }
        if (byteLength > MAX_STRING_BYTE_LENGTH) {
            trap("String byte length $byteLength exceeds the maximum")
        }
        return byteLength
    }

    internal fun stringBufferAlign(encoding: StringEncoding): Int = when (encoding) {
        StringEncoding.UTF8 -> 1
        StringEncoding.UTF16, StringEncoding.LATIN1_UTF16 -> 2
    }

    internal fun stringBufferKind(taggedLen: Int, encoding: StringEncoding): StringEncoding = when (encoding) {
        StringEncoding.UTF8 -> StringEncoding.UTF8
        StringEncoding.UTF16 -> StringEncoding.UTF16
        StringEncoding.LATIN1_UTF16 -> {
            val tagged = taggedLen.toLong() and 0xFFFF_FFFFL
            val utf16Tag = UTF16_TAG.toLong() and 0xFFFF_FFFFL
            if (tagged and utf16Tag != 0L) StringEncoding.UTF16 else StringEncoding.LATIN1_UTF16
        }
    }

    fun liftString(memory: CanonicalMemory, ptr: Int, taggedLen: Int, encoding: StringEncoding): String {
        val align = stringBufferAlign(encoding)
        val byteLength = stringBufferByteLength(taggedLen, encoding)
        checkRange(memory, ptr, byteLength, align, "string")
        if (byteLength == 0L) return ""
        val n = byteLength.toInt()
        val bytes = memory.readBytes(ptr, n)
        return when (stringBufferKind(taggedLen, encoding)) {
            StringEncoding.UTF8 -> {
                if (!Utf8.isValid(bytes, 0, n)) trap("Invalid ${encoding.name.lowercase()} string at ${unsigned(ptr)}")
                String(bytes, Charsets.UTF_8)
            }
            StringEncoding.UTF16 -> {
                val chars = CharArray(n / 2) { i ->
                    ((bytes[2 * i].toInt() and 0xFF) or ((bytes[2 * i + 1].toInt() and 0xFF) shl 8)).toChar()
                }
                if (!Utf8.isValidUtf16(chars)) trap("Invalid ${encoding.name.lowercase()} string at ${unsigned(ptr)}")
                String(chars)
            }
            StringEncoding.LATIN1_UTF16 -> String(bytes, Charsets.ISO_8859_1)
        }
    }

    fun checkLowerListLength(length: Int, elemSize: Int): Int {
        require(length >= 0) { "Negative list length $length" }
        val byteLength = length.toLong() * elemSize
        require(byteLength <= MAX_LIST_BYTE_LENGTH) { "List of $byteLength bytes exceeds the Canonical ABI limit" }
        return byteLength.toInt()
    }

    fun checkLiftList(memory: CanonicalMemory, ptr: Int, len: Int, elemSize: Int, elemAlign: Int): Int {
        val length = len.toLong() and 0xFFFF_FFFFL
        val byteLength = length * elemSize
        if (byteLength > MAX_LIST_BYTE_LENGTH) {
            trap("List byte length $byteLength exceeds the maximum")
        }
        checkRange(memory, ptr, byteLength, elemAlign, "list")
        return length.toInt()
    }

    fun liftByteArray(memory: CanonicalMemory, ptr: Int, len: Int): ByteArray {
        val n = checkLiftList(memory, ptr, len, 1, 1)
        return if (n == 0) EMPTY_BYTES else memory.readBytes(ptr, n)
    }

    // ---- Variant flat payload coercion helpers (Canonical ABI join / uncoerce) ----

    fun i32ToI64(v: Int): Long = v.toLong() and 0xFFFF_FFFFL
    fun f32ToI32(v: Float): Int = java.lang.Float.floatToRawIntBits(v)
    fun f32ToI64(v: Float): Long = java.lang.Float.floatToRawIntBits(v).toLong() and 0xFFFF_FFFFL
    fun f64ToI64(v: Double): Long = java.lang.Double.doubleToRawLongBits(v)
    fun i64ToI32(v: Long): Int = v.toInt()
    fun i32ToF32(v: Int): Float = java.lang.Float.intBitsToFloat(v)
    fun i64ToF32(v: Long): Float = java.lang.Float.intBitsToFloat(v.toInt())
    fun i64ToF64(v: Long): Double = java.lang.Double.longBitsToDouble(v)
}

@InternalWasm2KtApi
fun trap(message: String): Nothing = throw ComponentTrapException(message)

@InternalWasm2KtApi
fun unsigned(i: Int): Long = i.toLong() and 0xFFFF_FFFFL

/** Traps unless `[ptr, ptr + byteLength)` lies inside [memory] and [ptr] is aligned to [align]. */
@InternalWasm2KtApi
fun checkRange(memory: CanonicalMemory, ptr: Int, byteLength: Long, align: Int, what: String) {
    val p = unsigned(ptr)
    if (p % align != 0L) trap("Misaligned $what: $p is not a multiple of $align")
    val size = memory.byteSize
    if (p + byteLength > size) trap("Out-of-bounds $what: [$p, ${p + byteLength}) exceeds linear memory of $size bytes")
}

/**
 * Encoding checks without `CharsetDecoder`, which is costly to create on Android (#58).
 */
@InternalWasm2KtApi
object Utf8 {
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
