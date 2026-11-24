@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.runtime.canonical

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasm2kt.runtime.ComponentExitException
import wasm2kt.runtime.ComponentTrapException
import wasm2kt.runtime.WitWriter
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CanonicalRuntimeTest {
    private class ArrayCanonicalMemory(pages: Int) : CanonicalMemory {
        private val data = ByteArray(pages * 65_536)
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

        override fun readBytes(addr: Int, len: Int): ByteArray =
            if (len == 0) ByteArray(0) else data.copyOfRange(addr, addr + len)

        override fun readBytesInto(addr: Int, dst: ByteArray, dstOffset: Int, len: Int) {
            if (len > 0) System.arraycopy(data, addr, dst, dstOffset, len)
        }

        override fun writeByte(addr: Int, value: Byte) {
            data[addr] = value
        }

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
            if (len > 0) System.arraycopy(src, srcOffset, data, addr, len)
        }
    }

    private fun testContext(mem: CanonicalMemory, enc: StringEncoding = StringEncoding.UTF8): AbiContext =
        object : AbiContext {
            private var bump = 64
            override val memory: CanonicalMemory = mem
            override val encoding: StringEncoding = enc
            override fun realloc(align: Int, size: Int): Int {
                val aligned = (bump + align - 1) / align * align
                bump = aligned + size
                return aligned
            }

            override fun lowerOwn(table: Int, id: Int): Int = id + 100
            override fun lowerBorrow(table: Int, id: Int): Int = id + 200
            override fun liftOwn(table: Int, handle: Int): Int = handle - 100
            override fun liftBorrow(table: Int, handle: Int): Int = handle - 200
        }

    @Test
    fun `WitType layout and Unsupported semantics work without Chicory or serialization`() {
        val v = WitType.Variant(
            WitType.VariantKind.VARIANT,
            listOf("a" to WitType.F32, "b" to WitType.S64, "c" to null),
        )
        assertEquals(1, v.discSize)
        assertEquals(8, v.payloadOffset)
        assertEquals(16, v.size)
        assertEquals(8, v.align)
        assertEquals(listOf(CanonicalAbi.CoreType.I32, CanonicalAbi.CoreType.I64), v.flat)

        val f33 = WitType.Flags((1..33).map { "f$it" })
        assertEquals(8, f33.size)
        assertEquals(4, f33.align)
        assertEquals(listOf(CanonicalAbi.CoreType.I32, CanonicalAbi.CoreType.I32), f33.flat)

        val bad = WitType.Unsupported("map")
        assertThrows<UnsupportedWitTypeException> { bad.size }
        assertThrows<UnsupportedOperationException> { bad.flat }
    }

    @Test
    fun `Lowerer and Lifter roundtrip strings, records, lists, and canonicalize NaN`() {
        for (enc in listOf(StringEncoding.UTF8, StringEncoding.UTF16, StringEncoding.LATIN1_UTF16)) {
            val mem = ArrayCanonicalMemory(1)
            val cx = testContext(mem, enc)
            val rec = WitType.Record(
                listOf(
                    "s" to WitType.Str,
                    "f" to WitType.F32,
                    "d" to WitType.F64,
                    "xs" to WitType.ListOf(WitType.U16),
                ),
            )
            val writer = WitWriter().apply {
                string("hello-é-🎌")
                int(0x7fc0_1234) // non-canonical f32 NaN
                long(0x7ff8_0000_0000_5678L) // non-canonical f64 NaN
                length(3)
                short(10)
                short(20)
                short(30)
            }
            val ptr = Lowerer(cx, writer.toReader()).storeTuple(WitType.Tuple(listOf(rec)))
            val out = WitWriter()
            Lifter(cx, out).loadTuple(ptr, WitType.Tuple(listOf(rec)))
            val r = out.toReader()
            assertEquals("hello-é-🎌", r.string())
            assertEquals(CanonicalAbi.CANONICAL_FLOAT32_NAN, r.int())
            assertEquals(CanonicalAbi.CANONICAL_FLOAT64_NAN, r.long())
            assertEquals(3, r.length())
            assertEquals(10, r.short().toInt())
            assertEquals(20, r.short().toInt())
            assertEquals(30, r.short().toInt())
            assertEquals(0, r.remaining)
        }
    }

    @Test
    fun `Transcoder copies and validates strings via CanonicalMemory`() {
        val from = ArrayCanonicalMemory(1)
        val to = ArrayCanonicalMemory(1)
        val utf8 = "abc-日本語".encodeToByteArray()
        from.writeBytes(0, utf8)
        assertNull(Transcoder(TranscodeOp.UTF8_TO_UTF8, from, to).apply(longArrayOf(0, utf8.size.toLong(), 0)))
        assertArrayEquals(utf8, to.readBytes(0, utf8.size))

        from.writeBytes(0, byteArrayOf(0xFF.toByte()))
        assertThrows<ComponentTrapException> {
            Transcoder(TranscodeOp.UTF8_TO_UTF8, from, to).apply(longArrayOf(0, 1, 0))
        }
    }

    @Test
    fun `HandleTable, ResourceTable, and BorrowScope preserve single index space and reuse freed slots`() {
        val handles = HandleTable()
        val scope = object : BorrowScope {
            override var numBorrows: Int = 0
        }
        val t0 = ResourceTable(handles, 0)
        val t1 = ResourceTable(handles, 1)

        val h1 = t0.insert(42, own = true)
        val h2 = t1.insert(99, own = false, scope = scope)
        scope.numBorrows++
        assertEquals(1, h1)
        assertEquals(2, h2)
        assertEquals(42, t0.rep(h1))
        assertNull(t1.slotOrNull(h1))
        assertEquals(99, t1.rep(h2))
        assertSame(scope, t1.slotOrNull(h2)?.borrowScope)

        val removed = t1.remove(h2)
        removed.borrowScope?.let { it.numBorrows-- }
        assertEquals(0, scope.numBorrows)
        val h3 = t0.insert(77)
        assertEquals(h2, h3) // LIFO reuse
        assertThrows<ComponentTrapException> { handles.get(0) }
        assertThrows<ComponentTrapException> { t0.rep(999) }
    }

    @Test
    fun `ComponentGuard maps Wasm traps and preserves host import exception types`() {
        val g1 = ComponentGuard()
        var poisonedCount = 0
        assertThrows<ComponentTrapException> {
            g1.guest(onPoison = { poisonedCount++ }) {
                @Suppress("DIVISION_BY_ZERO")
                1 / 0
            }
        }
        assertEquals(1, poisonedCount)
        assertThrows<ComponentTrapException> { g1.checkUsable() }

        val g2 = ComponentGuard()
        assertThrows<ComponentTrapException> {
            g2.guest { throw StackOverflowError("deep") }
        }
        assertThrows<ComponentTrapException> { g2.checkUsable() }

        class CustomHostException(msg: String) : RuntimeException(msg)
        val g3 = ComponentGuard()
        val hostErr = assertThrows<CustomHostException> {
            g3.guest {
                g3.hostImport { throw CustomHostException("boom") }
            }
        }
        assertEquals("boom", hostErr.message)
        assertThrows<ComponentTrapException> { g3.checkUsable() }

        // Host ArithmeticException through hostImport keeps its ArithmeticException type (not ComponentTrapException).
        val g4 = ComponentGuard()
        val hostArith = assertThrows<ArithmeticException> {
            g4.guest {
                g4.hostImport { throw ArithmeticException("host divide") }
            }
        }
        assertEquals(ArithmeticException::class.java, hostArith.javaClass)
        assertThrows<ComponentTrapException> { g4.checkUsable() }

        // ComponentExitException sets isExit poison message.
        val g5 = ComponentGuard()
        val exit = assertThrows<ComponentExitException> {
            g5.guest { g5.hostImport { throw ComponentExitException(7) } }
        }
        assertEquals(7, exit.statusCode)
        val afterExit = assertThrows<ComponentTrapException> { g5.checkUsable() }
        assertTrue("already exited" in afterExit.message!!)

        // HostValueException does not poison.
        val g6 = ComponentGuard()
        assertThrows<IllegalArgumentException> {
            g6.guest { throw HostValueException(IllegalArgumentException("bad host arg")) }
        }
        g6.checkUsable()

        // StackOverflowError inside hostImport maps to ComponentTrapException and poisons.
        val g7 = ComponentGuard()
        assertThrows<ComponentTrapException> {
            g7.guest {
                g7.hostImport { throw StackOverflowError("deep host recursion") }
            }
        }
        assertThrows<ComponentTrapException> { g7.checkUsable() }

        // enter rejects closed/poisoned instances and enforces may_enter, resetting entered in finally.
        val g8 = ComponentGuard()
        g8.enter("ok") {
            assertTrue(g8.entered)
        }
        assertFalse(g8.entered)
        assertThrows<ComponentTrapException> {
            g8.enter("outer") {
                g8.enter("inner") { }
            }
        }
        assertFalse(g8.entered)
        assertThrows<ComponentTrapException> { g8.enter("after") { } }
    }

    @Test
    fun `PlanAsyncRules classifies sync adapter kinds and async indicators consistently`() {
        assertTrue(PlanAsyncRules.SYNC_ADAPTER_KINDS.containsAll(
            listOf("resource-transfer-own", "resource-transfer-borrow", "enter-sync-call", "exit-sync-call"),
        ))
        assertFalse(PlanAsyncRules.isAsyncTrampolineKind("lower-import"))
        assertFalse(PlanAsyncRules.isAsyncTrampolineKind("enter-sync-call"))
        assertTrue(PlanAsyncRules.isAsyncTrampolineKind("task-return"))

        assertFalse(
            PlanAsyncRules.usesAsync(
                trampolineKinds = listOf("lower-import", "resource-transfer-own", "transcode"),
                hasAsyncOptionOrCallback = false,
                hasAsyncExport = false,
                hasExtractCallback = false,
            ),
        )
        assertTrue(
            PlanAsyncRules.usesAsync(
                trampolineKinds = listOf("task-return"),
                hasAsyncOptionOrCallback = false,
                hasAsyncExport = false,
                hasExtractCallback = false,
            ),
        )
        assertTrue(
            PlanAsyncRules.usesAsync(
                trampolineKinds = emptyList(),
                hasAsyncOptionOrCallback = true,
                hasAsyncExport = false,
                hasExtractCallback = false,
            ),
        )
    }

    @Test
    fun `runtime-canonical loads without Chicory or kotlinx serialization on the classpath`() {
        val loader = CanonicalMemory::class.java.classLoader
        assertThrows<ClassNotFoundException> { Class.forName("com.dylibso.chicory.runtime.Memory", false, loader) }
        assertThrows<ClassNotFoundException> { Class.forName("kotlinx.serialization.json.Json", false, loader) }
    }
}
