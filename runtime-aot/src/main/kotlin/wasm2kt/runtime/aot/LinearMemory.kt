@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.runtime.aot

import wasm2kt.runtime.canonical.CanonicalMemory
import wasm2kt.runtime.canonical.InternalWasm2KtApi
import wasm2kt.runtime.canonical.trap
import java.io.EOFException
import java.io.InputStream
import java.util.Arrays

/**
 * Concrete `final` linear memory backed directly by a [ByteArray] without `VarHandle` (compatible
 * with JVM 17 and Android API 26).
 *
 * Effective addresses are computed as unsigned 32-bit `addr + offset` in 64-bit arithmetic and
 * explicitly bounds-checked, raising [wasm2kt.runtime.ComponentTrapException] on out-of-bounds
 * access. [grow] supports up to [MAX_PAGES] (32,767) pages and returns `-1` without trapping or
 * poisoning when the limit is exceeded or an [OutOfMemoryError] occurs.
 *
 * Code generated for Core Wasm must read [data] via this object's methods (or reload the backing
 * array after calls and `memory.grow`) rather than caching a local array reference across [grow].
 */
@InternalWasm2KtApi
class LinearMemory(
    initialPages: Int,
    maxPages: Int? = null,
    dataSegments: Array<ByteArray> = EMPTY_SEGMENTS,
) : CanonicalMemory {
    val maxPages: Int = run {
        require(initialPages in 0..MAX_PAGES) { "initialPages $initialPages out of range 0..$MAX_PAGES" }
        if (maxPages != null) {
            require(maxPages >= initialPages) { "maxPages $maxPages < initialPages $initialPages" }
            minOf(maxPages, MAX_PAGES)
        } else {
            MAX_PAGES
        }
    }

    var pages: Int = initialPages
        private set

    private var data: ByteArray = ByteArray(initialPages * PAGE_SIZE)
    private val segments: Array<ByteArray> = dataSegments.copyOf()

    override val byteSize: Long
        get() = data.size.toLong()

    fun pages(): Int = pages

    /**
     * Grows linear memory by [delta] (unsigned 32-bit) pages. Returns the previous page count on
     * success, or `-1` if the growth exceeds [maxPages] or throws [OutOfMemoryError]. Never traps.
     */
    fun grow(delta: Int): Int {
        val d = delta.toLong() and 0xFFFF_FFFFL
        val oldPages = pages
        if (d == 0L) return oldPages
        val newPagesLong = oldPages.toLong() + d
        if (newPagesLong > maxPages.toLong()) return -1
        val newPages = newPagesLong.toInt()
        val newData = try {
            data.copyOf(newPages * PAGE_SIZE)
        } catch (_: OutOfMemoryError) {
            return -1
        }
        data = newData
        pages = newPages
        return oldPages
    }

    private fun effectiveAddress(addr: Int, offset: Int, width: Int): Int {
        val ea = (addr.toLong() and 0xFFFF_FFFFL) + (offset.toLong() and 0xFFFF_FFFFL)
        val size = data.size
        if (ea + width > size.toLong()) {
            trapOutOfBounds(ea, width.toLong(), size)
        }
        return ea.toInt()
    }

    private fun checkDirectRange(addr: Int, len: Int) {
        if (len < 0) trap("Negative memory access length $len")
        val a = addr.toLong() and 0xFFFF_FFFFL
        val size = data.size
        if (a + len.toLong() > size.toLong()) {
            trapOutOfBounds(a, len.toLong(), size)
        }
    }

    private fun trapOutOfBounds(ea: Long, len: Long, size: Int): Nothing {
        trap("out of bounds memory access: [$ea, ${ea + len}) exceeds linear memory of $size bytes")
    }

    // ---------------------------------------------------------------- Core Wasm loads (little-endian)

    fun loadI32(addr: Int, offset: Int = 0): Int {
        val ea = effectiveAddress(addr, offset, 4)
        val b = data
        return (b[ea].toInt() and 0xFF) or
            ((b[ea + 1].toInt() and 0xFF) shl 8) or
            ((b[ea + 2].toInt() and 0xFF) shl 16) or
            (b[ea + 3].toInt() shl 24)
    }

    fun loadI64(addr: Int, offset: Int = 0): Long {
        val ea = effectiveAddress(addr, offset, 8)
        val b = data
        val lo = (b[ea].toInt() and 0xFF) or
            ((b[ea + 1].toInt() and 0xFF) shl 8) or
            ((b[ea + 2].toInt() and 0xFF) shl 16) or
            (b[ea + 3].toInt() shl 24)
        val hi = (b[ea + 4].toInt() and 0xFF) or
            ((b[ea + 5].toInt() and 0xFF) shl 8) or
            ((b[ea + 6].toInt() and 0xFF) shl 16) or
            (b[ea + 7].toInt() shl 24)
        return (lo.toLong() and 0xFFFF_FFFFL) or (hi.toLong() shl 32)
    }

    fun loadF32(addr: Int, offset: Int = 0): Float =
        java.lang.Float.intBitsToFloat(loadI32(addr, offset))

    fun loadF64(addr: Int, offset: Int = 0): Double =
        java.lang.Double.longBitsToDouble(loadI64(addr, offset))

    fun loadI32_8s(addr: Int, offset: Int = 0): Int {
        val ea = effectiveAddress(addr, offset, 1)
        return data[ea].toInt()
    }

    fun loadI32_8u(addr: Int, offset: Int = 0): Int {
        val ea = effectiveAddress(addr, offset, 1)
        return data[ea].toInt() and 0xFF
    }

    fun loadI32_16s(addr: Int, offset: Int = 0): Int {
        val ea = effectiveAddress(addr, offset, 2)
        val b = data
        return (b[ea].toInt() and 0xFF) or (b[ea + 1].toInt() shl 8)
    }

    fun loadI32_16u(addr: Int, offset: Int = 0): Int {
        val ea = effectiveAddress(addr, offset, 2)
        val b = data
        return (b[ea].toInt() and 0xFF) or ((b[ea + 1].toInt() and 0xFF) shl 8)
    }

    fun loadI64_8s(addr: Int, offset: Int = 0): Long {
        val ea = effectiveAddress(addr, offset, 1)
        return data[ea].toLong()
    }

    fun loadI64_8u(addr: Int, offset: Int = 0): Long {
        val ea = effectiveAddress(addr, offset, 1)
        return data[ea].toLong() and 0xFFL
    }

    fun loadI64_16s(addr: Int, offset: Int = 0): Long = loadI32_16s(addr, offset).toLong()

    fun loadI64_16u(addr: Int, offset: Int = 0): Long = loadI32_16u(addr, offset).toLong()

    fun loadI64_32s(addr: Int, offset: Int = 0): Long = loadI32(addr, offset).toLong()

    fun loadI64_32u(addr: Int, offset: Int = 0): Long = loadI32(addr, offset).toLong() and 0xFFFF_FFFFL

    // ---------------------------------------------------------------- Core Wasm stores (little-endian)

    fun storeI32_8(addr: Int, offset: Int = 0, value: Int) {
        val ea = effectiveAddress(addr, offset, 1)
        data[ea] = value.toByte()
    }

    fun storeI32_16(addr: Int, offset: Int = 0, value: Int) {
        val ea = effectiveAddress(addr, offset, 2)
        val b = data
        b[ea] = value.toByte()
        b[ea + 1] = (value ushr 8).toByte()
    }

    fun storeI32(addr: Int, offset: Int = 0, value: Int) {
        val ea = effectiveAddress(addr, offset, 4)
        val b = data
        b[ea] = value.toByte()
        b[ea + 1] = (value ushr 8).toByte()
        b[ea + 2] = (value ushr 16).toByte()
        b[ea + 3] = (value ushr 24).toByte()
    }

    fun storeI64_8(addr: Int, offset: Int = 0, value: Long) {
        storeI32_8(addr, offset, value.toInt())
    }

    fun storeI64_16(addr: Int, offset: Int = 0, value: Long) {
        storeI32_16(addr, offset, value.toInt())
    }

    fun storeI64_32(addr: Int, offset: Int = 0, value: Long) {
        storeI32(addr, offset, value.toInt())
    }

    fun storeI64(addr: Int, offset: Int = 0, value: Long) {
        val ea = effectiveAddress(addr, offset, 8)
        val b = data
        val lo = value.toInt()
        val hi = (value ushr 32).toInt()
        b[ea] = lo.toByte()
        b[ea + 1] = (lo ushr 8).toByte()
        b[ea + 2] = (lo ushr 16).toByte()
        b[ea + 3] = (lo ushr 24).toByte()
        b[ea + 4] = hi.toByte()
        b[ea + 5] = (hi ushr 8).toByte()
        b[ea + 6] = (hi ushr 16).toByte()
        b[ea + 7] = (hi ushr 24).toByte()
    }

    fun storeF32(addr: Int, offset: Int = 0, value: Float) {
        storeI32(addr, offset, java.lang.Float.floatToRawIntBits(value))
    }

    fun storeF64(addr: Int, offset: Int = 0, value: Double) {
        storeI64(addr, offset, java.lang.Double.doubleToRawLongBits(value))
    }

    // ---------------------------------------------------------------- bulk memory & initialization

    /** Wasm `memory.copy` within this memory (handles overlapping ranges). */
    fun copy(dst: Int, src: Int, len: Int) {
        val d = dst.toLong() and 0xFFFF_FFFFL
        val s = src.toLong() and 0xFFFF_FFFFL
        val n = len.toLong() and 0xFFFF_FFFFL
        val size = data.size.toLong()
        if (s + n > size) trapOutOfBounds(s, n, data.size)
        if (d + n > size) trapOutOfBounds(d, n, data.size)
        if (n > 0L) System.arraycopy(data, s.toInt(), data, d.toInt(), n.toInt())
    }

    /** Wasm `memory.copy` between two linear memories (e.g. multi-memory adapters). */
    fun copyFrom(srcMemory: LinearMemory, dst: Int, src: Int, len: Int) {
        val d = dst.toLong() and 0xFFFF_FFFFL
        val s = src.toLong() and 0xFFFF_FFFFL
        val n = len.toLong() and 0xFFFF_FFFFL
        val srcArr = srcMemory.data
        val dstArr = data
        if (s + n > srcArr.size.toLong()) trapOutOfBounds(s, n, srcArr.size)
        if (d + n > dstArr.size.toLong()) trapOutOfBounds(d, n, dstArr.size)
        if (n > 0L) System.arraycopy(srcArr, s.toInt(), dstArr, d.toInt(), n.toInt())
    }

    /** Wasm `memory.fill`. */
    fun fill(dst: Int, value: Int, len: Int) {
        val d = dst.toLong() and 0xFFFF_FFFFL
        val n = len.toLong() and 0xFFFF_FFFFL
        val b = data
        if (d + n > b.size.toLong()) trapOutOfBounds(d, n, b.size)
        if (n > 0L) Arrays.fill(b, d.toInt(), (d + n).toInt(), value.toByte())
    }

    /** Wasm `memory.init`: copies [len] bytes from segment [segmentIndex] at [src] into memory at [dst]. */
    fun init(segmentIndex: Int, dst: Int, src: Int, len: Int) {
        val seg = segmentAt(segmentIndex)
        val d = dst.toLong() and 0xFFFF_FFFFL
        val s = src.toLong() and 0xFFFF_FFFFL
        val n = len.toLong() and 0xFFFF_FFFFL
        if (s + n > seg.size.toLong()) {
            trap("out of bounds memory access: segment $segmentIndex [$s, ${s + n}) exceeds segment size ${seg.size}")
        }
        val b = data
        if (d + n > b.size.toLong()) trapOutOfBounds(d, n, b.size)
        if (n > 0L) System.arraycopy(seg, s.toInt(), b, d.toInt(), n.toInt())
    }

    /** Wasm `data.drop`: clears segment [segmentIndex] to empty bytes (idempotent). */
    fun dataDrop(segmentIndex: Int) {
        if (segmentIndex !in segments.indices) {
            trap("Invalid data segment index $segmentIndex")
        }
        segments[segmentIndex] = EMPTY_BYTES
    }

    /** Applies an active data segment at [dstOffset] during instantiation and drops it. */
    fun initActiveSegment(segmentIndex: Int, dstOffset: Int) {
        val seg = segmentAt(segmentIndex)
        initBytes(dstOffset, seg)
        segments[segmentIndex] = EMPTY_BYTES
    }

    /** Copies raw [bytes] into linear memory at unsigned 32-bit [dstOffset]. */
    fun initBytes(dstOffset: Int, bytes: ByteArray) {
        val d = dstOffset.toLong() and 0xFFFF_FFFFL
        val b = data
        if (d + bytes.size.toLong() > b.size.toLong()) trapOutOfBounds(d, bytes.size.toLong(), b.size)
        if (bytes.isNotEmpty()) System.arraycopy(bytes, 0, b, d.toInt(), bytes.size)
    }

    private fun segmentAt(index: Int): ByteArray {
        if (index !in segments.indices) trap("Invalid data segment index $index")
        return segments[index]
    }

    // ---------------------------------------------------------------- CanonicalMemory

    override fun readByte(addr: Int): Byte = loadI32_8s(addr, 0).toByte()

    override fun readShort(addr: Int): Short = loadI32_16s(addr, 0).toShort()

    override fun readInt(addr: Int): Int = loadI32(addr, 0)

    override fun readLong(addr: Int): Long = loadI64(addr, 0)

    override fun readBytes(addr: Int, len: Int): ByteArray {
        checkDirectRange(addr, len)
        return if (len == 0) EMPTY_BYTES else data.copyOfRange(addr, addr + len)
    }

    override fun readBytesInto(addr: Int, dst: ByteArray, dstOffset: Int, len: Int) {
        checkDirectRange(addr, len)
        if (len > 0) System.arraycopy(data, addr, dst, dstOffset, len)
    }

    override fun writeByte(addr: Int, value: Byte) {
        storeI32_8(addr, 0, value.toInt())
    }

    override fun writeShort(addr: Int, value: Short) {
        storeI32_16(addr, 0, value.toInt())
    }

    override fun writeInt(addr: Int, value: Int) {
        storeI32(addr, 0, value)
    }

    override fun writeLong(addr: Int, value: Long) {
        storeI64(addr, 0, value)
    }

    override fun writeBytes(addr: Int, src: ByteArray, srcOffset: Int, len: Int) {
        checkDirectRange(addr, len)
        if (len > 0) System.arraycopy(src, srcOffset, data, addr, len)
    }

    companion object {
        const val PAGE_SIZE = 65_536
        const val MAX_PAGES = 32_767
        private val EMPTY_BYTES = ByteArray(0)
        private val EMPTY_SEGMENTS = emptyArray<ByteArray>()

        /**
         * Reads a raw binary resource from a fixed classpath [resourcePath] without reflection or
         * `ServiceLoader`.
         */
        fun loadResource(loader: ClassLoader, resourcePath: String): ByteArray {
            val normalized = resourcePath.removePrefix("/")
            val stream = loader.getResourceAsStream(normalized)
                ?: throw IllegalStateException("Missing AOT linear memory resource '$normalized'")
            return stream.use { it.readBytes() }
        }

        /**
         * Decodes packed raw data segments from [resourcePath] in `[count: i32 LE] ([len: i32 LE] [bytes])*` format.
         */
        fun loadDataSegments(loader: ClassLoader, resourcePath: String): Array<ByteArray> {
            val normalized = resourcePath.removePrefix("/")
            val stream = loader.getResourceAsStream(normalized)
                ?: throw IllegalStateException("Missing AOT data segments resource '$normalized'")
            return stream.use { decodeDataSegments(it) }
        }

        /**
         * Decodes packed raw data segments from [bytes] in `[count: i32 LE] ([len: i32 LE] [bytes])*` format.
         */
        fun decodeDataSegments(bytes: ByteArray): Array<ByteArray> =
            bytes.inputStream().use { decodeDataSegments(it) }

        /**
         * Instantiates a [LinearMemory] initialized with packed data segments at [resourcePath].
         */
        fun fromResource(
            initialPages: Int,
            maxPages: Int? = null,
            loader: ClassLoader,
            resourcePath: String,
        ): LinearMemory = LinearMemory(initialPages, maxPages, loadDataSegments(loader, resourcePath))

        private fun decodeDataSegments(input: InputStream): Array<ByteArray> {
            val count = readIntLE(input)
            require(count >= 0) { "Negative data segment count $count" }
            val segments = Array(count) { EMPTY_BYTES }
            for (i in 0 until count) {
                val len = readIntLE(input)
                require(len >= 0) { "Negative data segment length $len" }
                if (len > 0) {
                    val buf = ByteArray(len)
                    var off = 0
                    while (off < len) {
                        val read = input.read(buf, off, len - off)
                        if (read < 0) throw EOFException("Unexpected end of data segment $i ($off/$len bytes)")
                        off += read
                    }
                    segments[i] = buf
                }
            }
            return segments
        }

        private fun readIntLE(input: InputStream): Int {
            val b0 = input.read()
            val b1 = input.read()
            val b2 = input.read()
            val b3 = input.read()
            if ((b0 or b1 or b2 or b3) < 0) throw EOFException("Unexpected end of data segment header")
            return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        }
    }
}
