@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.runtime.canonical

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasm2kt.runtime.ComponentTrapException

class DirectCanonicalAbiTest {
    private class ByteArrayMemory(size: Int) : CanonicalMemory {
        val data = ByteArray(size)
        override val byteSize: Long get() = data.size.toLong()
        override fun readByte(addr: Int): Byte = data[addr]
        override fun readShort(addr: Int): Short =
            ((data[addr].toInt() and 0xFF) or (data[addr + 1].toInt() shl 8)).toShort()
        override fun readInt(addr: Int): Int =
            (data[addr].toInt() and 0xFF) or
                ((data[addr + 1].toInt() and 0xFF) shl 8) or
                ((data[addr + 2].toInt() and 0xFF) shl 16) or
                (data[addr + 3].toInt() shl 24)
        override fun readLong(addr: Int): Long =
            (readInt(addr).toLong() and 0xFFFF_FFFFL) or (readInt(addr + 4).toLong() shl 32)
        override fun readBytes(addr: Int, len: Int): ByteArray = data.copyOfRange(addr, addr + len)
        override fun readBytesInto(addr: Int, dst: ByteArray, dstOffset: Int, len: Int) {
            System.arraycopy(data, addr, dst, dstOffset, len)
        }
        override fun writeByte(addr: Int, value: Byte) { data[addr] = value }
        override fun writeShort(addr: Int, value: Short) {
            data[addr] = value.toByte()
            data[addr + 1] = (value.toInt() ushr 8).toByte()
        }
        override fun writeInt(addr: Int, value: Int) {
            data[addr] = value.toByte()
            data[addr + 1] = (value ushr 8).toByte()
            data[addr + 2] = (value ushr 16).toByte()
            data[addr + 3] = (value ushr 24).toByte()
        }
        override fun writeLong(addr: Int, value: Long) {
            writeInt(addr, value.toInt())
            writeInt(addr + 4, (value ushr 32).toInt())
        }
        override fun writeBytes(addr: Int, src: ByteArray, srcOffset: Int, len: Int) {
            System.arraycopy(src, srcOffset, data, addr, len)
        }
    }

    @Test
    fun `char validation separates host errors from guest traps`() {
        assertEquals(0x1F600, DirectCanonicalAbi.lowerChar(0x1F600))
        assertThrows<IllegalArgumentException> { DirectCanonicalAbi.lowerChar(0xD800) }
        assertThrows<IllegalArgumentException> { DirectCanonicalAbi.lowerChar(0x110000) }
        assertEquals(0x41, DirectCanonicalAbi.liftChar(0x41))
        assertThrows<ComponentTrapException> { DirectCanonicalAbi.liftChar(0xDFFF) }
        assertThrows<ComponentTrapException> { DirectCanonicalAbi.liftChar(-1) }
    }

    @Test
    fun `nan canonicalization preserves normal floats and normalizes all NaN payloads`() {
        val sNaN32 = java.lang.Float.intBitsToFloat(0x7f800001)
        assertEquals(DirectCanonicalAbi.CANONICAL_FLOAT32_NAN, DirectCanonicalAbi.lowerF32Bits(sNaN32))
        assertEquals(DirectCanonicalAbi.CANONICAL_FLOAT32_NAN, java.lang.Float.floatToRawIntBits(DirectCanonicalAbi.liftF32Bits(0xff800042.toInt())))
        assertEquals(1.5f, DirectCanonicalAbi.liftF32Bits(DirectCanonicalAbi.lowerF32Bits(1.5f)))

        val sNaN64 = java.lang.Double.longBitsToDouble(0x7ff0000000000001L)
        assertEquals(DirectCanonicalAbi.CANONICAL_FLOAT64_NAN, DirectCanonicalAbi.lowerF64Bits(sNaN64))
        assertEquals(DirectCanonicalAbi.CANONICAL_FLOAT64_NAN, java.lang.Double.doubleToRawLongBits(DirectCanonicalAbi.liftF64Bits(-1L)))
        assertEquals(-2.25, DirectCanonicalAbi.liftF64Bits(DirectCanonicalAbi.lowerF64Bits(-2.25)))
    }

    @Test
    fun `discriminant and flags validation match canonical ABI`() {
        assertEquals(2, DirectCanonicalAbi.checkLiftDiscriminant(2, 3))
        assertThrows<ComponentTrapException> { DirectCanonicalAbi.checkLiftDiscriminant(3, 3) }
        assertThrows<ComponentTrapException> { DirectCanonicalAbi.checkLiftDiscriminant(-1, 3) }

        assertEquals(0b101, DirectCanonicalAbi.checkLowerFlags32(0b101, 3))
        assertThrows<IllegalArgumentException> { DirectCanonicalAbi.checkLowerFlags32(0b1000, 3) }
        assertEquals(0b111, DirectCanonicalAbi.maskLiftFlags32(-1, 3))

        assertEquals(1L shl 39, DirectCanonicalAbi.checkLowerFlags64(1L shl 39, 40))
        assertThrows<IllegalArgumentException> { DirectCanonicalAbi.checkLowerFlags64(1L shl 40, 40) }
        assertEquals((1L shl 40) - 1L, DirectCanonicalAbi.maskLiftFlags64(-1L, 40))
    }

    @Test
    fun `strings validate surrogates on lower and encodings on lift across UTF8, UTF16, and LATIN1_UTF16`() {
        val mem = ByteArrayMemory(256)
        val sample = "hello-\u00e9-\uD83D\uDE00"

        val utf8 = DirectCanonicalAbi.encodeUtf8(sample)
        mem.writeBytes(0, utf8)
        assertEquals(sample, DirectCanonicalAbi.liftString(mem, 0, utf8.size, StringEncoding.UTF8))

        val utf16 = DirectCanonicalAbi.encodeUtf16Le(sample)
        mem.writeBytes(64, utf16)
        assertEquals(sample, DirectCanonicalAbi.liftString(mem, 64, sample.length, StringEncoding.UTF16))

        assertNull(DirectCanonicalAbi.encodeLatin1OrNull(sample))
        val latin = DirectCanonicalAbi.encodeLatin1OrNull("abc-\u00ff")
        assertNotNull(latin)
        mem.writeBytes(128, latin!!)
        assertEquals("abc-\u00ff", DirectCanonicalAbi.liftString(mem, 128, latin.size, StringEncoding.LATIN1_UTF16))

        mem.writeBytes(160, utf16)
        assertEquals(
            sample,
            DirectCanonicalAbi.liftString(mem, 160, sample.length or DirectCanonicalAbi.UTF16_TAG, StringEncoding.LATIN1_UTF16),
        )

        val loneHigh = "\uD800"
        assertThrows<IllegalArgumentException> { DirectCanonicalAbi.encodeUtf8(loneHigh) }
        assertThrows<IllegalArgumentException> { DirectCanonicalAbi.encodeUtf16Le(loneHigh) }
        assertThrows<IllegalArgumentException> { DirectCanonicalAbi.encodeLatin1OrNull(loneHigh) }

        mem.writeBytes(200, byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        assertThrows<ComponentTrapException> { DirectCanonicalAbi.liftString(mem, 200, 2, StringEncoding.UTF8) }
        mem.writeShort(208, 0xD800.toShort())
        assertThrows<ComponentTrapException> { DirectCanonicalAbi.liftString(mem, 208, 1, StringEncoding.UTF16) }
        assertThrows<ComponentTrapException> { DirectCanonicalAbi.liftString(mem, 1, 2, StringEncoding.UTF16) } // misaligned
    }

    @Test
    fun `lists and realloc bounds reject overflow and out-of-bounds`() {
        val mem = ByteArrayMemory(32)
        assertEquals(4, DirectCanonicalAbi.checkReallocResult(mem, 4, 4, 8))
        assertThrows<ComponentTrapException> { DirectCanonicalAbi.checkReallocResult(mem, 2, 4, 8) }
        assertThrows<ComponentTrapException> { DirectCanonicalAbi.checkReallocResult(mem, 28, 4, 8) }

        val src = byteArrayOf(1, 2, 3, 4)
        mem.writeBytes(8, src)
        assertArrayEquals(src, DirectCanonicalAbi.liftByteArray(mem, 8, 4))
        assertArrayEquals(ByteArray(0), DirectCanonicalAbi.liftByteArray(mem, 0, 0))
        assertThrows<ComponentTrapException> { DirectCanonicalAbi.liftByteArray(mem, 30, 4) }
        assertThrows<IllegalArgumentException> { DirectCanonicalAbi.checkLowerListLength(-1, 4) }
    }
}
