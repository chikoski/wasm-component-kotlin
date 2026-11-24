@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.runtime.chicory

import com.dylibso.chicory.runtime.Memory
import wasm2kt.runtime.canonical.CanonicalMemory
import wasm2kt.runtime.canonical.InternalWasm2KtApi

/** Adapts Chicory's [Memory] to the backend-independent [CanonicalMemory] contract. */
internal class ChicoryMemory(val raw: Memory) : CanonicalMemory {
    override val byteSize: Long
        get() = raw.pages().toLong() * Memory.PAGE_SIZE

    override fun readByte(addr: Int): Byte = raw.read(addr)

    override fun readShort(addr: Int): Short = raw.readShort(addr)

    override fun readInt(addr: Int): Int = raw.readInt(addr)

    override fun readLong(addr: Int): Long = raw.readLong(addr)

    override fun readBytes(addr: Int, len: Int): ByteArray =
        if (len == 0) EMPTY_BYTES else raw.readBytes(addr, len)

    /**
     * `com.dylibso.chicory.runtime.Memory` only provides bulk reads via `readBytes(addr, len)`
     * (there is no overload writing into an existing `ByteArray`), so payloads of at least
     * [BULK_COPY_THRESHOLD] bytes are copied in [BULK_COPY_CHUNK]-byte chunks to bound temporary
     * allocation while avoiding per-byte or per-long calls.
     */
    override fun readBytesInto(addr: Int, dst: ByteArray, dstOffset: Int, len: Int) {
        if (len == 0) return
        if (len >= BULK_COPY_THRESHOLD) {
            var offset = 0
            while (offset < len) {
                val chunkLen = minOf(len - offset, BULK_COPY_CHUNK)
                val chunk = raw.readBytes(addr + offset, chunkLen)
                System.arraycopy(chunk, 0, dst, dstOffset + offset, chunkLen)
                offset += chunkLen
            }
            return
        }
        var d = dstOffset
        var src = addr
        val end8 = addr + (len and -8)
        while (src < end8) {
            val v = raw.readLong(src)
            dst[d] = v.toByte()
            dst[d + 1] = (v ushr 8).toByte()
            dst[d + 2] = (v ushr 16).toByte()
            dst[d + 3] = (v ushr 24).toByte()
            dst[d + 4] = (v ushr 32).toByte()
            dst[d + 5] = (v ushr 40).toByte()
            dst[d + 6] = (v ushr 48).toByte()
            dst[d + 7] = (v ushr 56).toByte()
            src += 8
            d += 8
        }
        val end = addr + len
        while (src < end) {
            dst[d++] = raw.read(src++)
        }
    }

    override fun writeByte(addr: Int, value: Byte) {
        raw.writeByte(addr, value)
    }

    override fun writeShort(addr: Int, value: Short) {
        raw.writeShort(addr, value)
    }

    override fun writeInt(addr: Int, value: Int) {
        raw.writeI32(addr, value)
    }

    override fun writeLong(addr: Int, value: Long) {
        raw.writeLong(addr, value)
    }

    override fun writeBytes(addr: Int, src: ByteArray, srcOffset: Int, len: Int) {
        if (len > 0) raw.write(addr, src, srcOffset, len)
    }

    private companion object {
        val EMPTY_BYTES = ByteArray(0)
        const val BULK_COPY_THRESHOLD = 256
        const val BULK_COPY_CHUNK = 65536
    }
}
