package wasm2kt.runtime

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The binary value format (ADR-0015). */
class WitWireTest {
    @Test
    fun `numbers are little endian and round trip`() {
        val w = WitWriter(1)
        w.bool(true); w.byte(0xAB); w.short(-2); w.int(0x01020304); w.long(Long.MIN_VALUE + 5)
        w.float(1.5f); w.double(-0.25); w.codePoint(0x1F38C)
        val bytes = w.toByteArray()
        assertContentEquals(byteArrayOf(4, 3, 2, 1), bytes.copyOfRange(4, 8))
        val r = WitReader(bytes)
        assertEquals(true, r.bool()); assertEquals(0xAB.toByte(), r.byte()); assertEquals((-2).toShort(), r.short())
        assertEquals(0x01020304, r.int()); assertEquals(Long.MIN_VALUE + 5, r.long())
        assertEquals(1.5f, r.float()); assertEquals(-0.25, r.double()); assertEquals(0x1F38C, r.codePoint())
        r.requireEnd()
    }

    @Test
    fun `strings are UTF-8 with a byte length`() {
        val samples = listOf("", "a", "é ü ÿ", "日本語", "🎌", "a🎌é日", "x".repeat(10_000))
        for (s in samples) {
            val w = WitWriter()
            w.string(s)
            val expected = s.toByteArray(Charsets.UTF_8)
            val r = w.toReader()
            assertEquals(expected.size, r.int())
            assertContentEquals(expected, ByteArray(expected.size).also { r.readFully(it, 0, it.size) })
            assertEquals(s, WitReader(w.toByteArray()).string())
        }
    }

    @Test
    fun `host errors are reported while writing`() {
        assertThrows<IllegalArgumentException> { WitWriter().string("a\uD800b") }
        assertThrows<IllegalArgumentException> { WitWriter().string("\uDC00") }
        assertThrows<IllegalArgumentException> { WitWriter().codePoint(0xD800) }
        assertThrows<IllegalArgumentException> { WitWriter().codePoint(0x110000) }
    }

    @Test
    fun `byte lists are copied as is`() {
        val data = ByteArray(70_000) { it.toByte() }
        val w = WitWriter()
        w.bytes(data)
        assertContentEquals(data, w.toReader().bytes())
    }

    @Test
    fun `malformed input is an IllegalStateException`() {
        assertThrows<IllegalStateException> { WitReader(byteArrayOf(1, 2)).int() }
        assertThrows<IllegalStateException> { WitReader(byteArrayOf(2)).bool() }
        assertThrows<IllegalStateException> { WitReader(byteArrayOf(-1, -1, -1, -1)).bytes() }
        assertThrows<IllegalStateException> { WitReader(byteArrayOf(1)).requireEnd() }
    }

    @Test
    fun `reset and reserve reuse the backing buffer`() {
        val w = WitWriter(8)
        w.bytes(ByteArray(256) { it.toByte() })
        val backingBefore = w.buffer
        w.reset()
        assertEquals(0, w.size)
        w.int(5)
        val offset = w.reserve(5)
        for (i in 0 until 5) {
            w.buffer[offset + i] = (10 + i).toByte()
        }
        kotlin.test.assertSame(backingBefore, w.buffer)
        assertContentEquals(byteArrayOf(10, 11, 12, 13, 14), w.toReader().bytes())
        assertThrows<IllegalArgumentException> { w.reserve(-1) }
    }

    @Test
    fun `zero initial capacity starts empty and grows to minimum step`() {
        val w = WitWriter(0)
        assertEquals(0, w.buffer.size)
        assertEquals(0, w.size)
        w.byte(42)
        assertTrue(w.buffer.size >= 8)
        assertEquals(42, w.toReader().byte().toInt())
        assertThrows<IllegalArgumentException> { WitWriter(-1) }
    }

    @Test
    fun `empty byte array reads back as empty`() {
        val empty = WitWriter(0)
        empty.bytes(ByteArray(0))
        assertEquals(0, empty.toReader().bytes().size)
    }

    @Test
    fun `reset truncates visible slice and reserve guards integer overflow`() {
        val w = WitWriter(8)
        w.bytes(byteArrayOf(1, 2, 3, 4, 5, 6))
        w.reset()
        w.bytes(byteArrayOf(9, 8))
        assertContentEquals(byteArrayOf(9, 8), w.toReader().bytes())

        val small = WitWriter(8)
        small.byte(1)
        assertThrows<IllegalArgumentException> { small.reserve(Int.MAX_VALUE) }
    }
}
