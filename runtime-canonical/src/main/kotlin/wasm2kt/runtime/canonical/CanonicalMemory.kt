package wasm2kt.runtime.canonical

/**
 * Minimal little-endian linear memory abstraction used by [Lowerer], [Lifter], [Transcoder], and
 * Canonical ABI validation helpers across backends.
 */
@InternalWasm2KtApi
interface CanonicalMemory {
    /** Total size of this linear memory in bytes. */
    val byteSize: Long

    fun readByte(addr: Int): Byte
    fun readShort(addr: Int): Short
    fun readInt(addr: Int): Int
    fun readLong(addr: Int): Long
    fun readBytes(addr: Int, len: Int): ByteArray
    fun readBytesInto(addr: Int, dst: ByteArray, dstOffset: Int, len: Int)

    fun writeByte(addr: Int, value: Byte)
    fun writeShort(addr: Int, value: Short)
    fun writeInt(addr: Int, value: Int)
    fun writeLong(addr: Int, value: Long)
    fun writeBytes(addr: Int, src: ByteArray, srcOffset: Int = 0, len: Int = src.size - srcOffset)
}
