@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.runtime.chicory

import com.dylibso.chicory.runtime.ByteArrayMemory
import com.dylibso.chicory.wasm.types.MemoryLimits
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasm2kt.runtime.ComponentTrapException
import wasm2kt.runtime.WitWriter
import wasm2kt.runtime.canonical.FuncType
import wasm2kt.runtime.canonical.InternalWasm2KtApi
import wasm2kt.runtime.canonical.ResourceHandle
import wasm2kt.runtime.canonical.StringEncoding
import wasm2kt.runtime.canonical.TranscodeOp
import wasm2kt.runtime.canonical.Transcoder
import wasm2kt.runtime.canonical.WitType
import wasm2kt.testing.JsonBackend
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Components whose child components have their own memories and pass strings and lists to each
 * other (#47): the fused adapters import both memories and copy strings through transcoders.
 * Uses the generated `composed-str` fixture; ChicoryDifferentialTest compares it with Wasmtime.
 */
class ComposedComponentTest {
    private fun open(): JsonBackend = TestComponent("composed-str").open()

    private fun wasmName(s: String): List<Int> = listOf(s.length) + s.encodeToByteArray().map { it.toInt() and 0xFF }

    private fun wasmModule(vararg sections: Pair<Int, List<Int>>): ByteArray {
        val out = ArrayList<Byte>()
        out += listOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00).map { it.toByte() }
        fun pushU32(v: Int) {
            var x = v
            do {
                var b = x and 0x7F
                x = x ushr 7
                if (x != 0) b = b or 0x80
                out.add(b.toByte())
            } while (x != 0)
        }
        for ((id, body) in sections) {
            out.add(id.toByte())
            pushU32(body.size)
            for (b in body) out.add(b.toByte())
        }
        return out.toByteArray()
    }

    @Test
    fun `shared table resolves cross-module call_indirect with differing type indices`() {
        // m0 defines `tbl` with `addTen` ((i32) -> i32, type index 1) at slot 0 and `constFn` ((i64) -> i32, type index 2) at slot 1.
        val m0Bytes = wasmModule(
            1 to listOf(
                3,
                0x60, 0x00, 0x00, // 0: () -> ()
                0x60, 0x01, 0x7F, 0x01, 0x7F, // 1: (i32) -> i32
                0x60, 0x01, 0x7E, 0x01, 0x7F, // 2: (i64) -> i32
            ),
            3 to listOf(2, 1, 2),
            4 to listOf(1, 0x70, 0x00, 2),
            7 to listOf(1) + wasmName("tbl") + listOf(0x01, 0),
            9 to listOf(1, 0x00, 0x41, 0x00, 0x0B, 2, 0, 1),
            10 to listOf(
                2,
                7, 0, 0x20, 0x00, 0x41, 0x0A, 0x6A, 0x0B,
                4, 0, 0x41, 0x32, 0x0B,
            ),
        )
        // m1 imports `m0.tbl` and re-exports it as `tbl2` to test chained SharedTableInstance backing flattening.
        val m1Bytes = wasmModule(
            2 to listOf(1) + wasmName("m0") + wasmName("tbl") + listOf(0x01, 0x70, 0x00, 2),
            7 to listOf(1) + wasmName("tbl2") + listOf(0x01, 0),
        )
        // m2 imports `m1.tbl2`: type 0 matches m0.type[1], type 1 does not match slot 0, and m0.type[2] is absent in m2.
        val m2Bytes = wasmModule(
            1 to listOf(
                2,
                0x60, 0x01, 0x7F, 0x01, 0x7F, // 0: (i32) -> i32
                0x60, 0x02, 0x7F, 0x7F, 0x01, 0x7F, // 1: (i32, i32) -> i32
            ),
            2 to listOf(1) + wasmName("m1") + wasmName("tbl2") + listOf(0x01, 0x70, 0x00, 2),
            3 to listOf(3, 0, 1, 0),
            7 to listOf(3) +
                wasmName("call") + listOf(0x00, 0) +
                wasmName("call_mismatch") + listOf(0x00, 1) +
                wasmName("call_unmatched") + listOf(0x00, 2),
            10 to listOf(
                3,
                9, 0, 0x20, 0x00, 0x41, 0x00, 0x11, 0x00, 0x00, 0x0B,
                11, 0, 0x20, 0x00, 0x20, 0x01, 0x41, 0x00, 0x11, 0x01, 0x00, 0x0B,
                9, 0, 0x20, 0x00, 0x41, 0x01, 0x11, 0x00, 0x00, 0x0B,
            ),
        )

        val plan = ComponentPlan(
            modules = listOf(
                PlanModule(0, "m0.wasm", emptyList()),
                PlanModule(1, "m1.wasm", listOf(PlanModuleImport("m0", "tbl", "table"))),
                PlanModule(2, "m2.wasm", listOf(PlanModuleImport("m1", "tbl2", "table"))),
            ),
            initializers = listOf(
                Initializer.Instantiate(0, 0, emptyList()),
                Initializer.Instantiate(1, 1, listOf(InstantiateArg("m0", "tbl", CoreDef.Export(CoreExportRef(0, "tbl"))))),
                Initializer.Instantiate(2, 2, listOf(InstantiateArg("m1", "tbl2", CoreDef.Export(CoreExportRef(1, "tbl2"))))),
            ),
            trampolines = emptyMap(),
            options = mapOf(0 to CanonOptions(StringEncoding.UTF8, null, null, null, async = false, instance = 2)),
            imports = emptyMap(),
            resourceTables = emptyMap(),
            exports = listOf(
                PlanExport(null, "call", CoreDef.Export(CoreExportRef(2, "call")), options = 0, type = FuncType(listOf("x" to WitType.S32), listOf(WitType.S32))),
                PlanExport(null, "call-mismatch", CoreDef.Export(CoreExportRef(2, "call_mismatch")), options = 0, type = FuncType(listOf("x" to WitType.S32, "y" to WitType.S32), listOf(WitType.S32))),
                PlanExport(null, "call-unmatched", CoreDef.Export(CoreExportRef(2, "call_unmatched")), options = 0, type = FuncType(listOf("x" to WitType.S32), listOf(WitType.S32))),
            ),
        )
        fun openShared() = ChicoryComponent(
            plan,
            mapOf(0 to CoreModule.interpreted(m0Bytes), 1 to CoreModule.interpreted(m1Bytes), 2 to CoreModule.interpreted(m2Bytes)),
        ).instantiate(null)

        openShared().use { component ->
            assertEquals(42, component.invoke(component.resolve("call"), WitWriter().apply { int(32) }.toReader()).int())
        }
        openShared().use { component ->
            val mismatch = assertThrows<ComponentTrapException> {
                component.invoke(component.resolve("call-mismatch"), WitWriter().apply { int(1); int(2) }.toReader())
            }
            assertTrue("indirect call type mismatch" in mismatch.message!!, mismatch.message)
        }
        openShared().use { component ->
            val unmatched = assertThrows<ComponentTrapException> {
                component.invoke(component.resolve("call-unmatched"), WitWriter().apply { int(1) }.toReader())
            }
            assertTrue("indirect call type mismatch" in unmatched.message!!, unmatched.message)
        }
    }

    @Test
    fun `borrowing an owned resource into its defining instance tracks active lends`() {
        fun importFunc(name: String, typeIdx: Int): List<Int> =
            wasmName("t") + wasmName(name) + listOf(0x00, typeIdx)

        val borrowOnlyBody = listOf(
            0x01, 0x02, 0x7F, // 2 i32 locals: $h, $rep
            0x41, 42, 0x10, 0x00, // res_new(42)
            0x41, 0x00, 0x41, 0x01, 0x10, 0x01, // t_own(..., 0, 1)
            0x21, 0x00, // local.set $h
            0x41, 0x01, 0x41, 0x00, 0x41, 0x00, 0x10, 0x03, // enter(1, 0, 0)
            0x20, 0x00, 0x41, 0x01, 0x41, 0x00, 0x10, 0x02, // t_borrow($h, 1, 0)
            0x21, 0x01, // local.set $rep
            0x10, 0x04, // exit()
            0x20, 0x00, 0x41, 0x01, 0x41, 0x00, 0x10, 0x01, // t_own($h, 1, 0) succeeds now
            0x1A, // drop
            0x20, 0x01, // local.get $rep
            0x0B, // end
        )
        val borrowAndTakeBody = listOf(
            0x01, 0x01, 0x7F, // 1 i32 local: $h
            0x41, 42, 0x10, 0x00, // res_new(42)
            0x41, 0x00, 0x41, 0x01, 0x10, 0x01, // t_own(..., 0, 1)
            0x21, 0x00, // local.set $h
            0x41, 0x01, 0x41, 0x00, 0x41, 0x00, 0x10, 0x03, // enter(1, 0, 0)
            0x20, 0x00, 0x41, 0x01, 0x41, 0x00, 0x10, 0x02, 0x1A, // drop(t_borrow($h, 1, 0))
            0x20, 0x00, 0x41, 0x01, 0x41, 0x00, 0x10, 0x01, // t_own($h, 1, 0) -> must trap
            0x10, 0x04, // exit()
            0x0B, // end
        )
        val nestedSyncBody = listOf(
            0x01, 0x02, 0x7F, // 2 i32 locals: $h, $rep
            0x41, 42, 0x10, 0x00, // res_new(42)
            0x41, 0x00, 0x41, 0x01, 0x10, 0x01, // t_own(..., 0, 1)
            0x21, 0x00, // local.set $h
            0x41, 0x01, 0x41, 0x00, 0x41, 0x00, 0x10, 0x03, // enter(1, 0, 0)
            0x20, 0x00, 0x41, 0x01, 0x41, 0x00, 0x10, 0x02, // t_borrow($h, 1, 0)
            0x21, 0x01, // local.set $rep
            0x41, 0x00, 0x41, 0x00, 0x41, 0x01, 0x10, 0x03, // nested enter(0, 0, 1)
            0x10, 0x04, // exit() inner
            0x10, 0x04, // exit() outer
            0x20, 0x00, 0x41, 0x01, 0x41, 0x00, 0x10, 0x01, // t_own($h, 1, 0) succeeds now
            0x1A, // drop
            0x20, 0x01, // local.get $rep
            0x0B, // end
        )
        val unreleasedBorrowExitBody = listOf(
            0x01, 0x01, 0x7F, // 1 i32 local: $h
            0x41, 42, 0x10, 0x00, // res_new(42)
            0x41, 0x00, 0x41, 0x01, 0x10, 0x01, // t_own(..., 0, 1)
            0x21, 0x00, // local.set $h
            0x41, 0x01, 0x41, 0x00, 0x41, 0x01, 0x10, 0x03, // enter(1, 0, 1)
            0x20, 0x00, 0x41, 0x01, 0x41, 0x01, 0x10, 0x02, 0x1A, // drop(t_borrow($h, 1, 1))
            0x10, 0x04, // exit() -> must trap with unreleased borrow handles
            0x41, 0x00, // unreachable return
            0x0B, // end
        )
        val makeHostResBody = listOf(
            0x00, // 0 locals
            0x41, 42, 0x10, 0x00, // res_new(42) -> table 0 handle
            0x0B, // end
        )
        val borrowThenOwnBody = listOf(0x00, 0x0B)
        val takeHostResBody = listOf(0x00, 0x0B)
        val moduleBytes = wasmModule(
            1 to listOf(
                7,
                0x60, 1, 0x7F, 1, 0x7F, // 0: (i32) -> i32
                0x60, 3, 0x7F, 0x7F, 0x7F, 1, 0x7F, // 1: (i32, i32, i32) -> i32
                0x60, 3, 0x7F, 0x7F, 0x7F, 0, // 2: (i32, i32, i32) -> ()
                0x60, 0, 0, // 3: () -> ()
                0x60, 0, 1, 0x7F, // 4: () -> i32
                0x60, 2, 0x7F, 0x7F, 0, // 5: (i32, i32) -> ()
                0x60, 1, 0x7F, 0, // 6: (i32) -> ()
            ),
            2 to listOf(5) +
                importFunc("res_new", 0) +
                importFunc("t_own", 1) +
                importFunc("t_borrow", 1) +
                importFunc("enter", 2) +
                importFunc("exit", 3),
            3 to listOf(7, 4, 4, 4, 4, 4, 5, 6),
            7 to listOf(7) +
                wasmName("borrow_only") + listOf(0x00, 5) +
                wasmName("borrow_and_take") + listOf(0x00, 6) +
                wasmName("nested_sync") + listOf(0x00, 7) +
                wasmName("unreleased_borrow_exit") + listOf(0x00, 8) +
                wasmName("make_host_res") + listOf(0x00, 9) +
                wasmName("borrow_then_own") + listOf(0x00, 10) +
                wasmName("take_host_res") + listOf(0x00, 11),
            10 to listOf(7) +
                listOf(borrowOnlyBody.size) + borrowOnlyBody +
                listOf(borrowAndTakeBody.size) + borrowAndTakeBody +
                listOf(nestedSyncBody.size) + nestedSyncBody +
                listOf(unreleasedBorrowExitBody.size) + unreleasedBorrowExitBody +
                listOf(makeHostResBody.size) + makeHostResBody +
                listOf(borrowThenOwnBody.size) + borrowThenOwnBody +
                listOf(takeHostResBody.size) + takeHostResBody,
        )

        fun syncAsync(kind: String) = Trampoline.Async(kind, null, null, null, false, false, null, null, null, null, null)
        val plan = ComponentPlan(
            modules = listOf(
                PlanModule(
                    0,
                    "m0.wasm",
                    listOf(
                        PlanModuleImport("t", "res_new", "func"),
                        PlanModuleImport("t", "t_own", "func"),
                        PlanModuleImport("t", "t_borrow", "func"),
                        PlanModuleImport("t", "enter", "func"),
                        PlanModuleImport("t", "exit", "func"),
                    ),
                ),
            ),
            initializers = listOf(
                Initializer.Resource(0, null, instance = 0),
                Initializer.Instantiate(
                    instance = 1,
                    module = 0,
                    args = listOf(
                        InstantiateArg("t", "res_new", CoreDef.Trampoline(0)),
                        InstantiateArg("t", "t_own", CoreDef.Trampoline(1)),
                        InstantiateArg("t", "t_borrow", CoreDef.Trampoline(2)),
                        InstantiateArg("t", "enter", CoreDef.Trampoline(3)),
                        InstantiateArg("t", "exit", CoreDef.Trampoline(4)),
                    ),
                ),
            ),
            trampolines = mapOf(
                0 to Trampoline.ResourceNew(0),
                1 to syncAsync("resource-transfer-own"),
                2 to syncAsync("resource-transfer-borrow"),
                3 to syncAsync("enter-sync-call"),
                4 to syncAsync("exit-sync-call"),
            ),
            options = mapOf(0 to CanonOptions(StringEncoding.UTF8, null, null, null, async = false, instance = 1)),
            imports = emptyMap(),
            resourceTables = mapOf(
                0 to ResourceTableInfo(0, resource = 0, imported = false, abstract = false, instance = 0),
                1 to ResourceTableInfo(1, resource = 0, imported = true, abstract = false, instance = 1),
            ),
            exports = listOf(
                PlanExport(null, "borrow-only", CoreDef.Export(CoreExportRef(1, "borrow_only")), 0, FuncType(emptyList(), listOf(WitType.S32))),
                PlanExport(null, "borrow-and-take", CoreDef.Export(CoreExportRef(1, "borrow_and_take")), 0, FuncType(emptyList(), listOf(WitType.S32))),
                PlanExport(null, "nested-sync", CoreDef.Export(CoreExportRef(1, "nested_sync")), 0, FuncType(emptyList(), listOf(WitType.S32))),
                PlanExport(null, "unreleased-borrow-exit", CoreDef.Export(CoreExportRef(1, "unreleased_borrow_exit")), 0, FuncType(emptyList(), listOf(WitType.S32))),
                PlanExport(null, "make-host-res", CoreDef.Export(CoreExportRef(1, "make_host_res")), 0, FuncType(emptyList(), listOf(WitType.Own(0)))),
                PlanExport(null, "borrow-then-own", CoreDef.Export(CoreExportRef(1, "borrow_then_own")), 0, FuncType(listOf("a" to WitType.Borrow(0), "b" to WitType.Own(0)), emptyList())),
                PlanExport(null, "take-host-res", CoreDef.Export(CoreExportRef(1, "take_host_res")), 0, FuncType(listOf("a" to WitType.Own(0)), emptyList())),
            ),
        )

        fun openResourceComponent() = ChicoryComponent(plan, mapOf(0 to CoreModule.interpreted(moduleBytes))).instantiate(null)

        openResourceComponent().use { component ->
            val inst = component as ChicoryComponentInstance
            assertEquals(42, component.invoke(component.resolve("borrow-only"), WitWriter().toReader()).int())
            assertEquals(0, inst.async.syncCallDepth())
            assertNull(StackSwitching.currentHandler())

            assertEquals(42, component.invoke(component.resolve("nested-sync"), WitWriter().toReader()).int())
            assertEquals(0, inst.async.syncCallDepth())
            assertNull(StackSwitching.currentHandler())

            val hostHandle = component.invoke(component.resolve("make-host-res"), WitWriter().toReader()).int()
            // Lowering fails on the second argument; rollbackLowering must release the lend acquired by the first.
            assertThrows<IllegalStateException> {
                component.invoke(component.resolve("borrow-then-own"), WitWriter().apply { int(hostHandle); int(hostHandle) }.toReader())
            }
            component.invoke(component.resolve("take-host-res"), WitWriter().apply { int(hostHandle) }.toReader())

            val trap = assertThrows<ComponentTrapException> {
                component.invoke(component.resolve("borrow-and-take"), WitWriter().toReader())
            }
            assertTrue("while it is lent" in trap.message!!, trap.message)
            assertEquals(0, inst.async.syncCallDepth())
            assertNull(StackSwitching.currentHandler())

            // A trap leaves the component poisoned; subsequent calls trap cleanly.
            val poisoned = assertThrows<ComponentTrapException> {
                component.invoke(component.resolve("borrow-only"), WitWriter().toReader())
            }
            assertTrue("unusable" in poisoned.message!!)
        }

        openResourceComponent().use { component2 ->
            val inst2 = component2 as ChicoryComponentInstance
            val borrowTrap = assertThrows<ComponentTrapException> {
                component2.invoke(component2.resolve("unreleased-borrow-exit"), WitWriter().toReader())
            }
            assertTrue("borrow handles still remain at the end of the call" in borrowTrap.message!!, borrowTrap.message)
            assertEquals(0, inst2.async.syncCallDepth())
            assertNull(StackSwitching.currentHandler())
        }

        // ThreadLocal sync call handler must be cleaned up so an async component on the same thread stays runnable.
        ChicoryComponent.fromDirectory(TestComponent("async-stream").dir).instantiate(null).use { asyncComp ->
            assertEquals(4, asyncComp.invoke(asyncComp.resolve("double"), WitWriter().apply { int(2) }.toReader()).int())
        }
    }

    @Test
    fun `task return and cancel release lent resource handles`() {
        val state = ComponentInstanceState(0, AsyncStore())

        val h1 = ResourceHandle(0, 10, own = true).apply { numLends = 1 }
        val t1 = Task(state, ftAsync = false, optsAsync = false, hasCallback = false, checkReturn = { _, _ -> true }, onStart = { LongArray(0) }, onResolve = {})
        t1.state = Task.State.STARTED
        t1.lenders += h1
        t1.return_(LongArray(0))
        assertEquals(0, h1.numLends)
        assertTrue(t1.lenders.isEmpty())

        val h2 = ResourceHandle(0, 11, own = true).apply { numLends = 1 }
        val t2 = Task(state, ftAsync = false, optsAsync = false, hasCallback = false, checkReturn = { _, _ -> true }, onStart = { LongArray(0) }, onResolve = {})
        t2.state = Task.State.CANCEL_DELIVERED
        t2.lenders += h2
        t2.cancel()
        assertEquals(0, h2.numLends)
        assertTrue(t2.lenders.isEmpty())
    }

    @Test
    fun `transcoder handles UTF-8 byte-length boundaries, partial writes, and invalid encodings`() {
        val from = ByteArrayMemory(MemoryLimits(1, 1))
        val to = ByteArrayMemory(MemoryLimits(1, 1))

        fun writeUtf16Units(ptr: Int, units: CharArray) {
            for (i in units.indices) from.writeShort(ptr + i * 2, units[i].code.toShort())
        }

        val boundaryText = "\u007F\u0080\u07FF\u0800\uFFFF\uD800\uDC00\uDBFF\uDFFF"
        val boundaryUnits = boundaryText.toCharArray()
        val expectedUtf8 = boundaryText.encodeToByteArray()
        assertEquals(9, boundaryUnits.size)
        assertEquals(19, expectedUtf8.size)

        writeUtf16Units(0, boundaryUnits)
        val utf16ToUtf8 = Transcoder(TranscodeOp.UTF16_TO_UTF8, ChicoryMemory(from), ChicoryMemory(to))
        val full = utf16ToUtf8.apply(longArrayOf(0, boundaryUnits.size.toLong(), 0, expectedUtf8.size.toLong(), 0))!!
        assertEquals(9L, full[0])
        assertEquals(19L, full[1])
        assertArrayEquals(expectedUtf8, to.readBytes(0, expectedUtf8.size))

        // First pass stops at the first non-ASCII code point.
        val asciiPrefix = "a\u007F\u0080".toCharArray()
        writeUtf16Units(0, asciiPrefix)
        val pass1 = utf16ToUtf8.apply(longArrayOf(0, asciiPrefix.size.toLong(), 0, 10, 1))!!
        assertEquals(2L, pass1[0])
        assertEquals(2L, pass1[1])
        assertArrayEquals("a\u007F".encodeToByteArray(), to.readBytes(0, 2))

        // Partial write stops before a multi-byte character that would exceed dstLen.
        val splitText = "a\uD800\uDC00".toCharArray()
        writeUtf16Units(0, splitText)
        val partial = utf16ToUtf8.apply(longArrayOf(0, splitText.size.toLong(), 0, 3, 0))!!
        assertEquals(1L, partial[0])
        assertEquals(1L, partial[1])
        assertArrayEquals(byteArrayOf('a'.code.toByte()), to.readBytes(0, 1))

        // Lone surrogates and invalid UTF-8 trap across transcode operations.
        for (bad in listOf(charArrayOf('\uD800'), charArrayOf('\uDC00'))) {
            writeUtf16Units(0, bad)
            assertThrows<ComponentTrapException> {
                utf16ToUtf8.apply(longArrayOf(0, bad.size.toLong(), 0, 8, 0))
            }
            assertThrows<ComponentTrapException> {
                Transcoder(TranscodeOp.UTF16_TO_UTF16, ChicoryMemory(from), ChicoryMemory(to)).apply(longArrayOf(0, bad.size.toLong(), 0))
            }
        }

        from.write(0, expectedUtf8)
        assertNull(Transcoder(TranscodeOp.UTF8_TO_UTF8, ChicoryMemory(from), ChicoryMemory(to)).apply(longArrayOf(0, expectedUtf8.size.toLong(), 0)))
        assertArrayEquals(expectedUtf8, to.readBytes(0, expectedUtf8.size))

        val badUtf8Sequences = listOf(
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()), // surrogate
            byteArrayOf(0xC0.toByte(), 0x80.toByte()), // overlong 2-byte
            byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()), // above U+10FFFF
            byteArrayOf(0xE2.toByte(), 0x82.toByte()), // truncated 3-byte
            byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x91.toByte()), // truncated 4-byte
        )
        for (badUtf8 in badUtf8Sequences) {
            from.write(0, badUtf8)
            val err = assertThrows<ComponentTrapException> {
                Transcoder(TranscodeOp.UTF8_TO_UTF8, ChicoryMemory(from), ChicoryMemory(to)).apply(longArrayOf(0, badUtf8.size.toLong(), 0))
            }
            assertTrue("invalid utf8 encoding" in err.message!!, err.message)
        }
    }

    private fun JsonBackend.call(name: String, vararg args: JsonElement): JsonElement = invoke(name, JsonArray(args.toList()))

    private val encodings = listOf("utf8", "utf16", "compact")

    /** ASCII, Latin-1 only, BMP beyond Latin-1, a surrogate pair, mixed, empty and long strings. */
    private val samples = listOf(
        "", "a", "hello", "é ü ÿ", "日本語", "🎌", "a🎌é日",
        "x".repeat(5000), ("éa" + "日🎌").repeat(700), "é".repeat(3000) + "日",
    )

    @Test
    fun `strings cross between children with every pair of encodings`() {
        open().use { c ->
            for (caller in encodings) for (callee in encodings) for (s in samples) {
                val echoed = c.call("echo-$caller-$callee", JsonPrimitive(s)).jsonPrimitive.content
                assertEquals(s, echoed, "echo-$caller-$callee changed a string of ${s.length} chars")
            }
        }
    }

    @Test
    fun `lists are copied between the children's memories`() {
        open().use { c ->
            val items = (0 until 1000).map { JsonPrimitive(it * 7) }
            assertEquals((0 until 1000).sumOf { it * 7 }, c.call("sum", JsonArray(items)).jsonPrimitive.int)
            assertEquals(items.map { it.int }, c.call("echo-list", JsonArray(items)).jsonArray.map { it.jsonPrimitive.int })
            assertEquals(0, c.call("sum", JsonArray(emptyList())).jsonPrimitive.int)
        }
    }

    @Test
    fun `an invalid string from the callee traps and poisons the instance`() {
        for (bad in listOf("utf8", "utf16")) for (caller in encodings) {
            open().use { c ->
                val e = assertThrows<ComponentTrapException>("bad-$bad-via-$caller") { c.call("bad-$bad-via-$caller") }
                assertTrue("invalid $bad encoding" in e.message!!, "bad-$bad-via-$caller: ${e.message}")
                assertThrows<ComponentTrapException> { c.call("echo-utf8-utf8", JsonPrimitive("x")) }
            }
        }
    }
}
