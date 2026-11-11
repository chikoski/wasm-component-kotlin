package wasm2kt.runtime

/**
 * Writes WIT values in the binary format generated bindings and backends exchange (ADR-0015).
 *
 * The format is tagless: both sides know the function's type, so only the values are written, in
 * declaration order and without padding.
 * * `bool`, `u8`, `s8`: 1 byte. `u16` / `s16`, `u32` / `s32`, `u64` / `s64`: little endian
 * * `f32`, `f64`: IEEE 754 bits, little endian
 * * `char`: `u32` scalar value
 * * `string`: `u32` byte length + UTF-8
 * * `list<T>`: `u32` element count + the elements (a `list` of fixed-size numbers is their bytes)
 * * `record`, `tuple`: the fields in order
 * * `variant`, `enum`: discriminant (`u8` / `u16` / `u32` by case count, as in the Canonical ABI) + payload
 * * `option`, `result`: `u8` 0 / 1 + payload
 * * `flags`: `u8` / `u16` / `u32` words, as in the Canonical ABI
 * * `own` / `borrow`: `u32` (the rep of a host resource, or the backend's handle of a guest resource)
 *
 * Errors in the host's values (a string with a lone surrogate, a `char` that is not a Unicode
 * scalar value) are [IllegalArgumentException]s raised while writing (ADR-0007).
 */
class WitWriter(initialCapacity: Int = 64) {
    private var buf = ByteArray(maxOf(initialCapacity, 8))

    /** Number of bytes written. */
    var size: Int = 0
        private set

    /** The backing array; valid up to [size] until the next write. */
    val buffer: ByteArray get() = buf

    private fun ensure(extra: Int) {
        val needed = size + extra
        if (needed <= buf.size) return
        if (needed < 0) throw IllegalArgumentException("WIT value too large")
        buf = buf.copyOf(maxOf(needed, buf.size * 2))
    }

    fun bool(v: Boolean) = byte(if (v) 1 else 0)

    /** `u8` / `s8`: the low 8 bits of [v]. */
    fun byte(v: Int) {
        ensure(1)
        buf[size++] = v.toByte()
    }

    /** `u16` / `s16`: the low 16 bits of [v]. */
    fun short(v: Int) {
        ensure(2)
        buf[size] = v.toByte()
        buf[size + 1] = (v ushr 8).toByte()
        size += 2
    }

    /** `u32` / `s32`. */
    fun int(v: Int) {
        ensure(4)
        putInt(size, v)
        size += 4
    }

    /** `u64` / `s64`. */
    fun long(v: Long) {
        ensure(8)
        putInt(size, v.toInt())
        putInt(size + 4, (v ushr 32).toInt())
        size += 8
    }

    fun float(v: Float) = int(java.lang.Float.floatToRawIntBits(v))

    fun double(v: Double) = long(java.lang.Double.doubleToRawLongBits(v))

    /** WIT `char`: a Unicode scalar value. */
    fun codePoint(v: Int) {
        require(v in 0..0x10FFFF && v !in 0xD800..0xDFFF) { "U+${Integer.toHexString(v).uppercase()} is not a Unicode scalar value" }
        int(v)
    }

    /** A list's element count. */
    fun length(n: Int) {
        require(n >= 0) { "Negative list length $n" }
        int(n)
    }

    /** `list<u8>`: the length and the bytes. */
    fun bytes(v: ByteArray) {
        length(v.size)
        ensure(v.size)
        System.arraycopy(v, 0, buf, size, v.size)
        size += v.size
    }

    /** `string`: the UTF-8 length and bytes. A lone surrogate is a host error. */
    fun string(v: String) {
        val start = size
        int(0)
        // Every UTF-16 unit takes at most 3 UTF-8 bytes (a surrogate pair, 2 units, takes 4).
        ensure(v.length * 3)
        var p = size
        val b = buf
        var i = 0
        val n = v.length
        while (i < n) {
            val c = v[i].code
            when {
                c < 0x80 -> b[p++] = c.toByte()
                c < 0x800 -> {
                    b[p++] = (0xC0 or (c shr 6)).toByte()
                    b[p++] = (0x80 or (c and 0x3F)).toByte()
                }
                c in 0xD800..0xDBFF && i + 1 < n && v[i + 1].code in 0xDC00..0xDFFF -> {
                    val cp = 0x10000 + ((c - 0xD800) shl 10) + (v[i + 1].code - 0xDC00)
                    b[p++] = (0xF0 or (cp shr 18)).toByte()
                    b[p++] = (0x80 or ((cp shr 12) and 0x3F)).toByte()
                    b[p++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                    b[p++] = (0x80 or (cp and 0x3F)).toByte()
                    i++
                }
                c in 0xD800..0xDFFF -> throw IllegalArgumentException("String has a lone surrogate at index $i")
                else -> {
                    b[p++] = (0xE0 or (c shr 12)).toByte()
                    b[p++] = (0x80 or ((c shr 6) and 0x3F)).toByte()
                    b[p++] = (0x80 or (c and 0x3F)).toByte()
                }
            }
            i++
        }
        putInt(start, p - size)
        size = p
    }

    /** [length] raw bytes of [src] from [offset], without a length prefix (for backends copying a value as is). */
    fun raw(src: ByteArray, offset: Int, length: Int) {
        ensure(length)
        System.arraycopy(src, offset, buf, size, length)
        size += length
    }

    /**
     * Reserves [length] raw bytes in [buffer] without a length prefix and returns the offset at
     * which to write them. [buffer] remains valid until the next write.
     */
    fun reserve(length: Int): Int {
        require(length >= 0) { "Negative length $length" }
        ensure(length)
        val at = size
        size += length
        return at
    }

    /** Clears the bytes written so far while keeping the backing array for reuse. */
    fun reset() {
        size = 0
    }

    /** `own<R>` of a host resource: moves it to the component (ADR-0008). */
    fun ownHostResource(v: WitHostResource) = int(HostResourceTable.current().lower(v))

    /** `borrow<R>` of a host resource: lends it for the call in progress (ADR-0008). */
    fun borrowHostResource(v: WitHostResource) = int(HostResourceTable.current().lend(v))

    /** The bytes written so far. */
    fun toByteArray(): ByteArray = buf.copyOf(size)

    /** A reader over the bytes written so far, without copying them. */
    fun toReader(): WitReader = WitReader(buf, 0, size)

    private fun putInt(at: Int, v: Int) {
        buf[at] = v.toByte()
        buf[at + 1] = (v ushr 8).toByte()
        buf[at + 2] = (v ushr 16).toByte()
        buf[at + 3] = (v ushr 24).toByte()
    }
}

/**
 * Reads WIT values written by [WitWriter] or a backend (ADR-0015). Reading past the end, or a
 * malformed value, is an [IllegalStateException]: the other side broke the format.
 */
class WitReader(private val buf: ByteArray, offset: Int = 0, private val end: Int = buf.size) {
    /** The next byte to read. */
    var position: Int = offset
        private set

    init {
        require(offset in 0..end && end <= buf.size) { "Invalid range $offset..$end of ${buf.size} bytes" }
    }

    val remaining: Int get() = end - position

    private fun need(n: Int): Int {
        if (n < 0 || n > end - position) throw IllegalStateException("WIT value ends early: need $n bytes, ${end - position} left")
        val at = position
        position += n
        return at
    }

    fun bool(): Boolean = when (val b = byte().toInt()) {
        0 -> false
        1 -> true
        else -> throw IllegalStateException("Invalid bool $b")
    }

    fun byte(): Byte = buf[need(1)]

    fun short(): Short {
        val at = need(2)
        return ((buf[at].toInt() and 0xFF) or (buf[at + 1].toInt() shl 8)).toShort()
    }

    fun int(): Int = getInt(need(4))

    fun long(): Long {
        val at = need(8)
        return (getInt(at).toLong() and 0xFFFF_FFFFL) or (getInt(at + 4).toLong() shl 32)
    }

    fun float(): Float = java.lang.Float.intBitsToFloat(int())

    fun double(): Double = java.lang.Double.longBitsToDouble(long())

    fun codePoint(): Int {
        val v = int()
        if (v !in 0..0x10FFFF || v in 0xD800..0xDFFF) throw IllegalStateException("Invalid char ${Integer.toHexString(v)}")
        return v
    }

    /** A list's element count. */
    fun length(): Int {
        val n = int()
        if (n < 0) throw IllegalStateException("List length ${n.toUInt()} is too large")
        return n
    }

    fun bytes(): ByteArray {
        val n = length()
        val at = need(n)
        return buf.copyOfRange(at, at + n)
    }

    /** `string`. The backends validate UTF-8 when they lift, so it is decoded as is. */
    fun string(): String {
        val n = length()
        val at = need(n)
        return String(buf, at, n, Charsets.UTF_8)
    }

    /**
     * Consumes [n] raw bytes and passes the backing array and their offset to [block], without
     * copying them (for backends copying a value as is).
     */
    fun <T> region(n: Int, block: (array: ByteArray, offset: Int) -> T): T {
        val at = need(n)
        return block(buf, at)
    }

    /** Reads [n] raw bytes into [dst] at [dstOffset]. */
    fun readFully(dst: ByteArray, dstOffset: Int, n: Int) {
        val at = need(n)
        System.arraycopy(buf, at, dst, dstOffset, n)
    }

    /** Fails unless every byte was read: a mismatch between the two sides' types. */
    fun requireEnd() {
        if (position != end) throw IllegalStateException("${end - position} unread bytes after the WIT values")
    }

    private fun getInt(at: Int): Int =
        (buf[at].toInt() and 0xFF) or
            ((buf[at + 1].toInt() and 0xFF) shl 8) or
            ((buf[at + 2].toInt() and 0xFF) shl 16) or
            (buf[at + 3].toInt() shl 24)
}
