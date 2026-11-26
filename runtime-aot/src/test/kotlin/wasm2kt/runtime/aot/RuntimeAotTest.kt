@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.runtime.aot

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasm2kt.runtime.ComponentTrapException
import wasm2kt.runtime.HostFunction as WitHostFunction
import wasm2kt.runtime.WitHostImportDispatcher
import wasm2kt.runtime.WitWriter
import wasm2kt.runtime.canonical.AbiContext
import wasm2kt.runtime.canonical.CanonicalMemory
import wasm2kt.runtime.canonical.ComponentGuard
import wasm2kt.runtime.canonical.FuncType
import wasm2kt.runtime.canonical.InternalWasm2KtApi
import wasm2kt.runtime.canonical.Lifter
import wasm2kt.runtime.canonical.Lowerer
import wasm2kt.runtime.canonical.StringEncoding
import wasm2kt.runtime.canonical.WitType
import java.io.ByteArrayOutputStream
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RuntimeAotTest {
    private fun packSegments(vararg segments: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun writeIntLE(v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 24) and 0xFF)
        }
        writeIntLE(segments.size)
        for (s in segments) {
            writeIntLE(s.size)
            out.write(s)
        }
        return out.toByteArray()
    }

    @Test
    fun `LinearMemory is final, performs little-endian loads and stores with sign extension and NaN preservation`() {
        assertTrue(Modifier.isFinal(LinearMemory::class.java.modifiers))
        val mem = LinearMemory(1, 2)

        mem.storeI32(0, 4, 0x1234_5678)
        assertEquals(0x1234_5678, mem.loadI32(0, 4))
        assertEquals(0x78.toByte(), mem.readByte(4))
        assertEquals(0x56.toByte(), mem.readByte(5))
        assertEquals(0x34.toByte(), mem.readByte(6))
        assertEquals(0x12.toByte(), mem.readByte(7))

        mem.storeI32_8(16, 0, 0x80)
        assertEquals(-128, mem.loadI32_8s(16))
        assertEquals(0x80, mem.loadI32_8u(16))
        assertEquals(-128L, mem.loadI64_8s(16))
        assertEquals(0x80L, mem.loadI64_8u(16))

        mem.storeI32_16(20, 0, 0x8001)
        assertEquals(-32767, mem.loadI32_16s(20))
        assertEquals(0x8001, mem.loadI32_16u(20))
        assertEquals(-32767L, mem.loadI64_16s(20))
        assertEquals(0x8001L, mem.loadI64_16u(20))

        mem.storeI64(32, 0, 0x8000_0000_0000_0001uL.toLong())
        assertEquals(0x8000_0000_0000_0001uL.toLong(), mem.loadI64(32))
        assertEquals(1, mem.loadI32(32))
        assertEquals(1L, mem.loadI64_32s(32))
        mem.storeI32(40, 0, -1)
        assertEquals(-1L, mem.loadI64_32s(40))
        assertEquals(0xFFFF_FFFFL, mem.loadI64_32u(40))

        // NaN payload preservation
        val f32NaNBits = 0x7fc0_1234
        val f32NaN = java.lang.Float.intBitsToFloat(f32NaNBits)
        mem.storeF32(48, 0, f32NaN)
        assertEquals(f32NaNBits, mem.loadI32(48))
        assertEquals(f32NaNBits, java.lang.Float.floatToRawIntBits(mem.loadF32(48)))

        val f64NaNBits = 0x7ff8_1234_5678_9ABCL
        val f64NaN = java.lang.Double.longBitsToDouble(f64NaNBits)
        mem.storeF64(56, 0, f64NaN)
        assertEquals(f64NaNBits, mem.loadI64(56))
        assertEquals(f64NaNBits, java.lang.Double.doubleToRawLongBits(mem.loadF64(56)))
    }

    @Test
    fun `LinearMemory enforces unsigned 64-bit effective address bounds and non-trapping grow`() {
        val mem = LinearMemory(1, 2)
        // Unsigned addr + offset must NOT wrap around 32-bit int!
        assertThrows<ComponentTrapException> { mem.loadI32(0xFFFF_FFF0.toInt(), 0x20) }
        assertThrows<ComponentTrapException> { mem.loadI32(0x8000_0000.toInt(), 0) }
        assertThrows<ComponentTrapException> { mem.loadI32(LinearMemory.PAGE_SIZE - 3, 0) }
        assertThrows<ComponentTrapException> { mem.storeI64(LinearMemory.PAGE_SIZE - 7, 0, 1L) }

        // Boundary access succeeds
        mem.storeI32(LinearMemory.PAGE_SIZE - 4, 0, 0xCAFE_BABE.toInt())
        assertEquals(0xCAFE_BABE.toInt(), mem.loadI32(LinearMemory.PAGE_SIZE - 4, 0))

        // grow within maxPages succeeds and preserves existing data
        assertEquals(1, mem.grow(0))
        assertEquals(1, mem.grow(1))
        assertEquals(2, mem.pages())
        assertEquals(0xCAFE_BABE.toInt(), mem.loadI32(LinearMemory.PAGE_SIZE - 4, 0))
        assertEquals(0, mem.loadI32(LinearMemory.PAGE_SIZE, 0))

        // grow beyond maxPages or MAX_PAGES returns -1 without trapping
        assertEquals(-1, mem.grow(1))
        assertEquals(-1, mem.grow(-1)) // unsigned 0xFFFF_FFFFL
        assertEquals(2, mem.pages())

        // Unbounded memory is capped at MAX_PAGES (32767)
        val capped = LinearMemory(0, 65536)
        assertEquals(LinearMemory.MAX_PAGES, capped.maxPages)
        assertEquals(-1, capped.grow(LinearMemory.MAX_PAGES + 1))
    }

    @Test
    fun `LinearMemory supports copy, fill, init, dataDrop, and packed resource initialization`() {
        val seg0 = byteArrayOf(1, 2, 3, 4, 5)
        val seg1 = "hello-aot".encodeToByteArray()
        val packed = packSegments(seg0, seg1)
        val decoded = LinearMemory.decodeDataSegments(packed)
        val mem = LinearMemory(1, 1, decoded)

        mem.initActiveSegment(0, 100)
        assertArrayEquals(seg0, mem.readBytes(100, 5))
        // Dropped active segment allows 0-len init and traps on >0-len init
        mem.init(0, 0, 0, 0)
        assertThrows<ComponentTrapException> { mem.init(0, 0, 0, 1) }

        mem.init(1, 200, 6, 3) // "aot"
        assertEquals("aot", mem.readBytes(200, 3).decodeToString())
        mem.dataDrop(1)
        mem.dataDrop(1) // idempotent
        assertThrows<ComponentTrapException> { mem.init(1, 0, 0, 1) }

        // Overlapping memory.copy and fill
        mem.fill(300, 0xAA, 6)
        assertArrayEquals(ByteArray(6) { 0xAA.toByte() }, mem.readBytes(300, 6))
        mem.initBytes(10, byteArrayOf(10, 20, 30, 40, 50))
        mem.copy(12, 10, 4)
        assertArrayEquals(byteArrayOf(10, 20, 10, 20, 30, 40), mem.readBytes(10, 6))

        // Zero-length copy/fill at out-of-bounds offset still traps per Wasm spec
        mem.copy(LinearMemory.PAGE_SIZE, LinearMemory.PAGE_SIZE, 0)
        assertThrows<ComponentTrapException> { mem.copy(LinearMemory.PAGE_SIZE + 1, 0, 0) }
        assertThrows<ComponentTrapException> { mem.fill(LinearMemory.PAGE_SIZE + 1, 0, 0) }

        // Resource loading from fixed classpath path without reflection
        val resourceBytes = packSegments("WASM".encodeToByteArray())
        val customLoader = object : ClassLoader(javaClass.classLoader) {
            override fun getResourceAsStream(name: String): java.io.InputStream? =
                if (name == "wasm2kt/aot/test-segments.bin") resourceBytes.inputStream() else null
        }
        assertArrayEquals(resourceBytes, LinearMemory.loadResource(customLoader, "/wasm2kt/aot/test-segments.bin"))
        val loaded = LinearMemory.fromResource(1, 1, customLoader, "wasm2kt/aot/test-segments.bin")
        loaded.init(0, 0, 0, 4)
        assertEquals("WASM", loaded.readBytes(0, 4).decodeToString())
        assertThrows<IllegalStateException> { LinearMemory.fromResource(1, 1, customLoader, "wasm2kt/aot/missing.bin") }

        // Interoperates directly as CanonicalMemory
        val cx = object : AbiContext {
            private var bump = 4096
            override val memory: CanonicalMemory = mem
            override val encoding: StringEncoding = StringEncoding.UTF8
            override fun realloc(align: Int, size: Int): Int {
                val aligned = (bump + align - 1) / align * align
                bump = aligned + size
                return aligned
            }
            override fun lowerOwn(table: Int, id: Int): Int = id
            override fun lowerBorrow(table: Int, id: Int): Int = id
            override fun liftOwn(table: Int, handle: Int): Int = handle
            override fun liftBorrow(table: Int, handle: Int): Int = handle
        }
        val ptr = Lowerer(cx, WitWriter().apply { string("canonical-aot") }.toReader())
            .storeTuple(WitType.Tuple(listOf(WitType.Str)))
        val out = WitWriter()
        Lifter(cx, out).loadTuple(ptr, WitType.Tuple(listOf(WitType.Str)))
        assertEquals("canonical-aot", out.toReader().string())
    }

    @Test
    fun `FuncRef and shared FuncTable check identity, host trampolines, structural types, and grow limits`() {
        val sigAdd = WasmTypeSignatures.canonical(listOf(WasmValKind.I32, WasmValKind.I32), listOf(WasmValKind.I32))
        val sigVoid = WasmTypeSignatures.canonical(emptyList(), emptyList())
        assertEquals("(II)I", sigAdd)
        assertEquals("()V", sigVoid)
        assertTrue(WasmTypeSignatures.areEquivalent(sigAdd, "(II)I"))
        assertFalse(WasmTypeSignatures.areEquivalent(sigAdd, "(IJ)I"))

        val ownerA = Any()
        val ownerB = Any()
        val refA0 = FuncRef.core(ownerA, 0, sigAdd)
        val refA0Dup = FuncRef.core(ownerA, 0, sigAdd)
        val refB0 = FuncRef.core(ownerB, 0, sigAdd)
        assertEquals(refA0, refA0Dup)
        assertEquals(refA0.hashCode(), refA0Dup.hashCode())
        assertNotEquals(refA0, refB0)

        // Shared table between module A and module B with delayed elem patching and host trampoline
        val table = FuncTable(initialSize = 4, maxSize = 6)
        assertNull(table.get(0))
        table.patch(1, arrayOf(refA0, refB0))
        val hostRef = table.registerHostTrampoline(3, sigAdd, HostTrampoline { args -> longArrayOf(args[0] + args[1]) })

        assertSame(refA0, table.getChecked(1, "(II)I"))
        assertSame(refB0, table.getChecked(2, "(II)I"))
        val lookedUpHost = table.getChecked(3, "(II)I") as FuncRef.Host
        assertSame(hostRef, lookedUpHost)
        assertEquals(42L, lookedUpHost.trampoline.call(longArrayOf(19, 23))!![0])

        // Type mismatch, null entry, and OOB trap
        assertThrows<ComponentTrapException> { table.getChecked(0, sigAdd) }
        assertThrows<ComponentTrapException> { table.getChecked(1, sigVoid) }
        assertThrows<ComponentTrapException> { table.getChecked(4, sigAdd) }

        // table.grow returns previous size on success, -1 on limit exceeded
        assertEquals(4, table.grow(2, refA0))
        assertEquals(6, table.size())
        assertSame(refA0, table.get(5))
        assertEquals(-1, table.grow(1, null))
        assertEquals(-1, table.grow(-1, null))
        assertEquals(6, table.size())

        // table.init and elem.drop
        val segs = ElemSegmentStore(arrayOf(arrayOf<FuncRef?>(refA0, refB0)))
        segs.init(table, 0, 0, 1, 1)
        assertSame(refB0, table.get(0))
        segs.drop(0)
        segs.init(table, 0, 0, 0, 0)
        assertThrows<ComponentTrapException> { segs.init(table, 0, 0, 0, 1) }

        // ExternTable
        val ext = ExternTable(2)
        ext.set(0, "hello")
        assertEquals("hello", ext.get(0))
        assertNull(ext.get(1))
    }

    @Test
    fun `WasmNumerics implements division traps, trunc bounds, saturating trunc, and NaN reinterpret`() {
        // Division and remainder
        assertThrows<ComponentTrapException> { WasmNumerics.i32DivS(1, 0) }
        assertThrows<ComponentTrapException> { WasmNumerics.i32DivS(Int.MIN_VALUE, -1) }
        assertEquals(0, WasmNumerics.i32RemS(Int.MIN_VALUE, -1))
        assertThrows<ComponentTrapException> { WasmNumerics.i32DivU(1, 0) }
        assertEquals(0x4000_0000, WasmNumerics.i32DivU(Int.MIN_VALUE, 2))
        assertEquals(1, WasmNumerics.i32RemU(-1, 2))

        assertThrows<ComponentTrapException> { WasmNumerics.i64DivS(1L, 0L) }
        assertThrows<ComponentTrapException> { WasmNumerics.i64DivS(Long.MIN_VALUE, -1L) }
        assertEquals(0L, WasmNumerics.i64RemS(Long.MIN_VALUE, -1L))
        assertThrows<ComponentTrapException> { WasmNumerics.i64DivU(1L, 0L) }
        assertEquals(0x4000_0000_0000_0000L, WasmNumerics.i64DivU(Long.MIN_VALUE, 2L))

        // Trapping truncations and exact boundary checks
        assertThrows<ComponentTrapException> { WasmNumerics.i32TruncF32S(Float.NaN) }
        assertThrows<ComponentTrapException> { WasmNumerics.i32TruncF32S(2147483648.0f) }
        assertEquals(Int.MIN_VALUE, WasmNumerics.i32TruncF32S(-2147483648.0f))

        assertEquals(0, WasmNumerics.i32TruncF32U(-0.9f))
        assertThrows<ComponentTrapException> { WasmNumerics.i32TruncF32U(-1.0f) }
        assertThrows<ComponentTrapException> { WasmNumerics.i32TruncF32U(4294967296.0f) }

        assertEquals(Int.MIN_VALUE, WasmNumerics.i32TruncF64S(-2147483648.5))
        assertThrows<ComponentTrapException> { WasmNumerics.i32TruncF64S(-2147483649.0) }
        assertThrows<ComponentTrapException> { WasmNumerics.i32TruncF64S(2147483648.0) }
        assertEquals(Int.MAX_VALUE, WasmNumerics.i32TruncF64S(2147483647.9))

        assertEquals(0, WasmNumerics.i32TruncF64U(-0.999999))
        assertEquals(-1, WasmNumerics.i32TruncF64U(4294967295.9))
        assertThrows<ComponentTrapException> { WasmNumerics.i32TruncF64U(-1.0) }
        assertThrows<ComponentTrapException> { WasmNumerics.i32TruncF64U(4294967296.0) }

        assertEquals(Long.MIN_VALUE, WasmNumerics.i64TruncF64S(-9223372036854775808.0))
        assertThrows<ComponentTrapException> { WasmNumerics.i64TruncF64S(9223372036854775808.0) }
        assertEquals(-1L shl 11, WasmNumerics.i64TruncF64U(18446744073709551616.0 - 2048.0))
        assertThrows<ComponentTrapException> { WasmNumerics.i64TruncF64U(18446744073709551616.0) }
        assertThrows<ComponentTrapException> { WasmNumerics.i64TruncF32U(18446744073709551616.0f) }

        // Saturating truncations never trap
        assertEquals(0, WasmNumerics.i32TruncSatF32S(Float.NaN))
        assertEquals(0, WasmNumerics.i32TruncSatF64U(-100.0))
        assertEquals(-1, WasmNumerics.i32TruncSatF64U(1e20))
        assertEquals(0L, WasmNumerics.i64TruncSatF64U(Double.NaN))
        assertEquals(-1L, WasmNumerics.i64TruncSatF64U(1e30))

        // Reinterpret NaN preservation & unsigned conversions & min/max/nearest
        val snan32 = 0x7f80_0001
        assertEquals(snan32, WasmNumerics.i32ReinterpretF32(WasmNumerics.f32ReinterpretI32(snan32)))
        val snan64 = 0x7ff0_0000_0000_0001L
        assertEquals(snan64, WasmNumerics.i64ReinterpretF64(WasmNumerics.f64ReinterpretI64(snan64)))

        assertEquals(java.lang.Float.floatToRawIntBits(-0.0f), java.lang.Float.floatToRawIntBits(WasmNumerics.f32Min(-0.0f, +0.0f)))
        assertEquals(java.lang.Double.doubleToRawLongBits(+0.0), java.lang.Double.doubleToRawLongBits(WasmNumerics.f64Max(-0.0, +0.0)))
        assertTrue(WasmNumerics.f32Min(Float.NaN, 1.0f).isNaN())
        assertEquals(2.0, WasmNumerics.f64Nearest(2.5))
        assertEquals(18446744073709551616.0, WasmNumerics.f64ConvertI64U(-1L))
    }

    @Test
    fun `AotTrapSupport checks usability, enforces may_enter, maps Wasm traps, and preserves host import exceptions`() {
        val g1 = ComponentGuard()
        assertThrows<ComponentTrapException> {
            AotTrapSupport.enterGuestCall(g1, "trapFn") { AotTrapSupport.unreachable() }
        }
        assertFalse(g1.entered)
        assertThrows<ComponentTrapException> { AotTrapSupport.enterGuestCall(g1, "after") { error("unreachable") } }

        val g2 = ComponentGuard()
        class AppHostError(msg: String) : IllegalStateException(msg)
        val err = assertThrows<AppHostError> {
            AotTrapSupport.enterGuestCall(g2, "hostErr") {
                AotTrapSupport.hostImportCall(g2) { throw AppHostError("host failed") }
            }
        }
        assertEquals("host failed", err.message)
        assertFalse(g2.entered)
        assertThrows<ComponentTrapException> { AotTrapSupport.enterGuestCall(g2, "after") { error("unreachable") } }

        // StackOverflowError inside host import maps to ComponentTrapException and poisons.
        val g3 = ComponentGuard()
        assertThrows<ComponentTrapException> {
            AotTrapSupport.enterGuestCall(g3, "stackErr") {
                AotTrapSupport.hostImportCall(g3) { throw StackOverflowError("deep") }
            }
        }
        assertThrows<ComponentTrapException> { g3.checkUsable() }

        // Reentry via enterGuestCall inside hostImportCall traps with may_enter and poisons.
        val g4 = ComponentGuard()
        var innerError: Throwable? = null
        AotTrapSupport.enterGuestCall(g4, "outer") {
            AotTrapSupport.hostImportCall(g4) {
                innerError = runCatching { AotTrapSupport.enterGuestCall(g4, "inner") { 42 } }.exceptionOrNull()
            }
        }
        val trapped = assertIs<ComponentTrapException>(innerError)
        assertTrue("reenter" in trapped.message!!)
        assertFalse(g4.entered)
        assertThrows<ComponentTrapException> { AotTrapSupport.enterGuestCall(g4, "after") { error("unreachable") } }

        // Closed instance rejects entry with IllegalStateException.
        val g5 = ComponentGuard()
        g5.closed = true
        assertThrows<IllegalStateException> { AotTrapSupport.enterGuestCall(g5, "closed") { error("unreachable") } }

        // runtime-aot classpath has no Chicory or kotlinx.serialization.
        val loader = LinearMemory::class.java.classLoader
        assertThrows<ClassNotFoundException> { Class.forName("com.dylibso.chicory.runtime.Memory", false, loader) }
        assertThrows<ClassNotFoundException> { Class.forName("kotlinx.serialization.json.Json", false, loader) }
    }

    @Test
    fun `DataSegmentStore, WasmGlobals, AotSpillFrame, AotDispatchSupport, and AotOps work without reflection`() {
        val seg0 = "alpha".encodeToByteArray()
        val seg1 = "beta".encodeToByteArray()
        val packed = packSegments(seg0, seg1)
        val resourceLoader = object : ClassLoader(javaClass.classLoader) {
            override fun getResourceAsStream(name: String): java.io.InputStream? =
                if (name == "wasm2kt/aot/test-store.data") packed.inputStream() else null
        }
        val store = DataSegmentStore.load(resourceLoader, "wasm2kt/aot/test-store.data")
        val mem0 = LinearMemory(1, 2)
        val mem1 = LinearMemory(1, 1)
        store.initActive(mem0, 0, 16)
        assertEquals("alpha", mem0.readBytes(16, 5).decodeToString())
        assertThrows<ComponentTrapException> { store.init(mem0, 0, 0, 0, 1) }

        AotOps.memoryInit(32, 1, 3, store, 1, mem1) // "eta"
        assertEquals("eta", mem1.readBytes(32, 3).decodeToString())
        store.drop(1)
        assertThrows<ComponentTrapException> { AotOps.memoryInit(0, 0, 1, store, 1, mem1) }

        // Cross-memory copy via AotOps sees grown array without stale caching
        assertEquals(1, AotOps.memoryGrow(1, mem0))
        AotOps.storeI32(LinearMemory.PAGE_SIZE, 0xCAFE_1234.toInt(), mem0, 4)
        assertEquals(0xCAFE_1234.toInt(), AotOps.loadI32(LinearMemory.PAGE_SIZE, mem0, 4))
        AotOps.memoryCopy(64, LinearMemory.PAGE_SIZE + 4, 4, mem1, mem0)
        assertEquals(0xCAFE_1234.toInt(), AotOps.loadI32(64, mem1))
        AotOps.memoryFill(80, 0x5A, 4, mem1)
        assertArrayEquals(ByteArray(4) { 0x5A }, mem1.readBytes(80, 4))

        // Table ops in Wasm operand stack order
        val ftab = FuncTable(1, 4)
        val hostRef = FuncRef.host("(IFJD)FD", HostTrampoline { args ->
            val i = args[0].toInt()
            val f = java.lang.Float.intBitsToFloat(args[1].toInt())
            val j = args[2]
            val d = java.lang.Double.longBitsToDouble(args[3])
            longArrayOf(
                java.lang.Float.floatToRawIntBits(f + i).toLong(),
                java.lang.Double.doubleToRawLongBits(d + j),
            )
        })
        assertEquals(1, AotOps.tableGrow(hostRef, 2, ftab))
        assertSame(hostRef, AotOps.tableGetFuncRef(1, ftab))
        val called = AotDispatchSupport.invokeExternal(
            AotOps.callIndirectLookup(1, "(IFJD)FD", ftab),
            arrayOf(3, 1.5f, 10L, 2.25),
        )!!
        assertEquals(4.5f, called[0])
        assertEquals(12.25, called[1])

        val coreInstance = object : CoreModuleInstance {
            override fun invokeIndirect(funcIndex: Int, args: Array<Any?>): Array<Any?>? =
                if (funcIndex == 7) arrayOf((args[0] as Int) * 2) else null
        }
        val coreRef = FuncRef.core(coreInstance, 7, "(I)I")
        val extRes = AotDispatchSupport.invokeExternal(coreRef, arrayOf(21))!!
        assertEquals(42, extRes[0])

        // Globals & SpillFrame
        val ig = I32Global(1)
        val jg = I64Global(2L)
        val fg = F32Global(3.0f)
        val dg = F64Global(4.0)
        val rg = FuncRefGlobal(null)
        val eg = ExternRefGlobal(null)
        AotOps.setI32Global(10, ig)
        AotOps.setI64Global(20L, jg)
        AotOps.setF32Global(30.0f, fg)
        AotOps.setF64Global(40.0, dg)
        AotOps.setFuncRefGlobal(coreRef, rg)
        AotOps.setExternRefGlobal("ok", eg)
        assertEquals(10, ig.value)
        assertEquals(20L, jg.value)
        assertEquals(30.0f, fg.value)
        assertEquals(40.0, dg.value)
        assertSame(coreRef, rg.value)
        assertEquals("ok", eg.value)

        val spill = AotSpillFrame(2, 1, 0, 0, 1)
        assertEquals(2, spill.i32.size)
        assertEquals(1, spill.i64.size)
        assertEquals(0, spill.f32.size)
        assertEquals(0, spill.f64.size)
        assertEquals(1, spill.refs.size)

        // Bit-exact float abs/neg/copysign/trunc/ceil/floor/sqrt with sNaN and -0.0 preservation
        val snan32 = java.lang.Float.intBitsToFloat(0x7F80_0123)
        val negSnan32 = WasmNumerics.f32Neg(snan32)
        assertEquals(0xFF80_0123.toInt(), java.lang.Float.floatToRawIntBits(negSnan32))
        assertEquals(0x7F80_0123, java.lang.Float.floatToRawIntBits(WasmNumerics.f32Abs(negSnan32)))
        assertEquals(0xFF80_0123.toInt(), java.lang.Float.floatToRawIntBits(WasmNumerics.f32Copysign(snan32, -1.0f)))
        assertEquals(java.lang.Float.floatToRawIntBits(-0.0f), java.lang.Float.floatToRawIntBits(WasmNumerics.f32Trunc(-0.5f)))
        assertEquals(java.lang.Float.floatToRawIntBits(-0.0f), java.lang.Float.floatToRawIntBits(WasmNumerics.f32Ceil(-0.5f)))
        assertEquals(-1.0f, WasmNumerics.f32Floor(-0.5f))
        assertEquals(java.lang.Float.floatToRawIntBits(-0.0f), java.lang.Float.floatToRawIntBits(WasmNumerics.f32Sqrt(-0.0f)))

        val snan64 = java.lang.Double.longBitsToDouble(0x7FF0_0000_0000_0456L)
        val negSnan64 = WasmNumerics.f64Neg(snan64)
        assertEquals(0xFFF0_0000_0000_0456uL.toLong(), java.lang.Double.doubleToRawLongBits(negSnan64))
        assertEquals(0x7FF0_0000_0000_0456L, java.lang.Double.doubleToRawLongBits(WasmNumerics.f64Abs(negSnan64)))
        assertEquals(0xFFF0_0000_0000_0456uL.toLong(), java.lang.Double.doubleToRawLongBits(WasmNumerics.f64Copysign(snan64, -1.0)))
        assertEquals(java.lang.Double.doubleToRawLongBits(-0.0), java.lang.Double.doubleToRawLongBits(WasmNumerics.f64Trunc(-0.5)))
        assertEquals(java.lang.Double.doubleToRawLongBits(-0.0), java.lang.Double.doubleToRawLongBits(WasmNumerics.f64Sqrt(-0.0)))
    }

    @Test
    fun `AotComponentBackend handles exports, sync resource transfers, traps, and host errors`() {
        val droppedReps = mutableListOf<Int>()
        val hostSeen = mutableListOf<String>()
        val tables = arrayOf(
            AotResourceTableDesc(index = 0, resource = 0, imported = false, instance = 0, definedInInstance = 0),
            AotResourceTableDesc(index = 1, resource = 0, imported = false, instance = 1, definedInInstance = 0),
            AotResourceTableDesc(index = 2, resource = -1, imported = true, instance = 0, definedInInstance = -1, dropKey = "env#[resource-drop]h"),
        )
        val dispatcher = object : WitHostImportDispatcher {
            override fun hostFunction(key: String): WitHostFunction = WitHostFunction { args, results ->
                when (key) {
                    "env#echo" -> {
                        val s = args.string()
                        hostSeen += s
                        results.string("$s!")
                    }
                    "env#[resource-drop]h" -> {
                        droppedReps += args.int()
                    }
                    "env#boom" -> throw UnsupportedOperationException("host boom")
                    else -> error("Unknown $key")
                }
            }
        }
        val mem = LinearMemory(1, 2)
        var bump = 64
        val opts = AotCanonOptions(StringEncoding.UTF8, instance = 0).apply {
            memory = mem
            realloc = AotRealloc { _, _, align, size ->
                bump = ((bump + align - 1) / align) * align
                val p = bump
                bump += size
                p
            }
        }

        lateinit var rt: AotComponentBackend
        val exports = arrayOf(
            AotExportDesc(
                instance = "pkg:ns/api@1.0",
                name = "round-str",
                type = FuncType(listOf("s" to WitType.Str), listOf(WitType.Str)),
                options = opts,
                func = AotCoreFunction { coreArgs ->
                    val ptr = coreArgs[0].toInt()
                    val len = coreArgs[1].toInt()
                    assertEquals("hi", mem.readBytes(ptr, len).decodeToString())
                    val retPtr = 128
                    rt.callHost("env#echo", FuncType(listOf("x" to WitType.Str), listOf(WitType.Str)), opts, longArrayOf(ptr.toLong(), len.toLong(), retPtr.toLong()))
                    val resPtr = mem.readInt(retPtr)
                    val resLen = mem.readInt(retPtr + 4)
                    assertEquals("hi!", mem.readBytes(resPtr, resLen).decodeToString())
                    longArrayOf(retPtr.toLong())
                },
            ),
            AotExportDesc(
                instance = null,
                name = "make-res",
                type = FuncType(emptyList(), listOf(WitType.Own(0))),
                options = opts,
                func = AotCoreFunction { longArrayOf(rt.resourceNew(0, 77).toLong()) },
            ),
            AotExportDesc(
                instance = null,
                name = "sync-borrow-rep",
                type = FuncType(listOf("r" to WitType.Borrow(0)), listOf(WitType.S32)),
                options = opts,
                func = AotCoreFunction { args ->
                    // Borrow the caller's rep inside a nested owning handle for a sync call; verify dropping the
                    // owning handle while lent traps, and that exitSyncCall releases the lend cleanly afterward.
                    rt.enterSyncCall()
                    val h0 = rt.resourceNew(0, args[0].toInt())
                    val h1 = rt.resourceTransferBorrow(h0, 0, 1)
                    val rep = rt.resourceRep(1, h1)
                    val lentDropTrap = assertThrows<ComponentTrapException> { rt.resourceDrop(0, h0) }
                    assertTrue("lent" in lentDropTrap.message!!)
                    rt.resourceDrop(1, h1)
                    rt.exitSyncCall()
                    rt.resourceDrop(0, h0)
                    longArrayOf(rep.toLong())
                },
            ),
            AotExportDesc(
                instance = null,
                name = "trap-while-borrowing",
                type = FuncType(emptyList(), emptyList()),
                options = opts,
                func = AotCoreFunction {
                    rt.enterSyncCall()
                    val h0 = rt.resourceNew(0, 55)
                    rt.resourceTransferBorrow(h0, 0, 1)
                    // Trap mid-sync-call before dropping borrow h1 or calling exitSyncCall; invoke's finally unwinds syncCallStack.
                    rt.adapterTrap(0, arrayOf("mid-sync trap"))
                },
            ),
            AotExportDesc(
                instance = null,
                name = "unreleased-borrow",
                type = FuncType(emptyList(), listOf(WitType.S32)),
                options = opts,
                func = AotCoreFunction {
                    rt.enterSyncCall()
                    val h0 = rt.resourceNew(0, 99)
                    rt.resourceTransferBorrow(h0, 0, 1)
                    rt.exitSyncCall()
                    longArrayOf(1)
                },
            ),
            AotExportDesc(
                instance = null,
                name = "call-boom",
                type = FuncType(emptyList(), emptyList()),
                options = opts,
                func = AotCoreFunction {
                    rt.callHost("env#boom", FuncType(emptyList(), emptyList()), opts, LongArray(0))
                },
            ),
        )
        rt = AotComponentBackend(dispatcher, tables).apply {
            registerResourceDtor(0, AotResourceDtor { rep -> droppedReps += rep })
            setExports(exports)
        }

        // ADR-0011 short & unversioned resolution
        val fnIdx = rt.resolve("api#round-str")
        assertEquals(fnIdx, rt.resolve("pkg:ns/api#round-str"))
        assertEquals(fnIdx, rt.resolve("round-str"))
        val out = rt.invoke(fnIdx, WitWriter().apply { string("hi") }.toReader()).string()
        assertEquals("hi!", out)
        assertEquals(listOf("hi"), hostSeen)

        // Own + Borrow lifecycle & host error on invalid unread bytes without poisoning
        val ownHandle = rt.invoke(rt.resolve("make-res"), WitWriter().toReader()).int()
        assertThrows<IllegalArgumentException> {
            rt.invoke(rt.resolve("sync-borrow-rep"), WitWriter().apply { int(ownHandle); int(123) }.toReader())
        }
        assertEquals(77, rt.invoke(rt.resolve("sync-borrow-rep"), WitWriter().apply { int(ownHandle) }.toReader()).int())
        // sync-borrow-rep destroyed its inner owning handle h0 for rep 77; the caller's ownHandle still owns rep 77 until dropped.
        assertEquals(listOf(77), droppedReps)
        rt.dropResource(ownHandle)
        assertEquals(listOf(77, 77), droppedReps)

        // Trap mid-sync-call unwinds syncCallStack and poisons the instance
        val rtMid = AotComponentBackend(dispatcher, tables).apply { setExports(exports) }
        val midTrap = assertThrows<ComponentTrapException> {
            rtMid.invoke(rtMid.resolve("trap-while-borrowing"), WitWriter().toReader())
        }
        assertTrue("mid-sync trap" in midTrap.message!!)
        assertThrows<ComponentTrapException> { rtMid.resolve("make-res") }
        rtMid.close()

        // Unreleased borrow traps and poisons
        val borrowTrap = assertThrows<ComponentTrapException> {
            rt.invoke(rt.resolve("unreleased-borrow"), WitWriter().toReader())
        }
        assertTrue("borrow handles still remain" in borrowTrap.message!!)
        assertThrows<ComponentTrapException> { rt.resolve("make-res") }
        rt.close()

        // Host exception preserves original type and poisons instance
        val rt2 = AotComponentBackend(dispatcher, tables).apply { setExports(exports) }
        assertThrows<UnsupportedOperationException> {
            rt2.invoke(rt2.resolve("call-boom"), WitWriter().toReader())
        }
        assertThrows<ComponentTrapException> { rt2.resolve("make-res") }
        rt2.close()
        assertThrows<IllegalStateException> { rt2.resolve("make-res") }
    }

    @Test
    fun `DirectResourceContext rollbackLowering restores host resources without losing usability`() {
        val tables = arrayOf(AotResourceTableDesc(index = 0, resource = -1, imported = true, instance = 0, definedInInstance = -1))
        val rt = AotComponentRuntime(tables, hasHostResources = true)
        val closed = mutableListOf<String>()
        val hostTable = requireNotNull(rt.hostResources)
        val res = object : wasm2kt.runtime.WitHostResource {
            override fun close() {
                closed += "closed"
            }
        }
        rt.withHostResources {
            val cx = rt.newResourceContext(AbiDirection.HOST_TO_GUEST)
            val coreHandle = cx.lowerHostOwn(0, res)
            assertEquals(1, hostTable.size)
            cx.rollbackLowering()
            assertEquals(0, hostTable.size)
            assertTrue(closed.isEmpty(), "Rollback must not close the host resource")
            // Host resource can still be re-lowered into a fresh call cleanly.
            val cx2 = rt.newResourceContext(AbiDirection.HOST_TO_GUEST)
            val handle2 = cx2.lowerHostOwn(0, res)
            assertNotEquals(0, handle2)
            assertNotEquals(0, coreHandle)
            cx2.commitMoves()
            hostTable.close()
        }
        assertEquals(listOf("closed"), closed)
    }
}
