@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.runtime.chicory

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasm2kt.runtime.HostFunction
import wasm2kt.runtime.WitHostImportDispatcher
import wasm2kt.runtime.WitWriter
import wasm2kt.runtime.canonical.CanonicalAbi
import wasm2kt.runtime.canonical.FuncType
import wasm2kt.runtime.canonical.InternalWasm2KtApi
import wasm2kt.runtime.canonical.StringEncoding
import wasm2kt.runtime.canonical.WitType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers cached [WitType] / [FuncType] layout properties, mixed flat variant lowering and lifting,
 * LATIN1_UTF16 string encoding tags, tuple-spilled parameters/results, lazy [WitType.Unsupported]
 * failures, and multi-threaded resolution on a shared [ChicoryComponent].
 */
class VariantAndCachingTest {
    private fun wasmName(s: String): List<Int> {
        val bytes = s.encodeToByteArray()
        return uleb(bytes.size) + bytes.map { it.toInt() and 0xFF }
    }

    private fun uleb(value: Int): List<Int> {
        val out = ArrayList<Int>()
        var x = value
        do {
            var b = x and 0x7F
            x = x ushr 7
            if (x != 0) b = b or 0x80
            out += b
        } while (x != 0)
        return out
    }

    private fun sleb(value: Int): List<Int> {
        val out = ArrayList<Int>()
        var x = value
        var more = true
        while (more) {
            val b = x and 0x7F
            x = x shr 7
            val signBit = (b and 0x40) != 0
            more = !((x == 0 && !signBit) || (x == -1 && signBit))
            out += if (more) (b or 0x80) else b
        }
        return out
    }

    private fun wasmModule(vararg sections: Pair<Int, List<Int>>): ByteArray {
        val out = ArrayList<Byte>()
        out += listOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00).map { it.toByte() }
        for ((id, body) in sections) {
            out.add(id.toByte())
            for (b in uleb(body.size)) out.add(b.toByte())
            for (b in body) out.add(b.toByte())
        }
        return out.toByteArray()
    }

    private fun codeBody(locals: List<Pair<Int, Int>>, instructions: List<Int>): List<Int> {
        val body = ArrayList<Int>()
        body += uleb(locals.size)
        for ((count, type) in locals) {
            body += uleb(count)
            body += type
        }
        body += instructions
        body += 0x0B // end
        return uleb(body.size) + body
    }

    private data class Harness(
        val component: ChicoryComponent,
        val variantType: WitType.Variant,
        val recordType: WitType.Record,
    )

    private fun buildHarness(): Harness {
        val mixedVariant = WitType.Variant(
            WitType.VariantKind.VARIANT,
            listOf(
                "num" to WitType.F32,
                "wide" to WitType.S64,
                "empty" to null,
            ),
        )
        val mixedRecord = WitType.Record(
            listOf(
                "v" to mixedVariant,
                "tail" to WitType.S32,
            ),
        )
        val latin1Record = WitType.Record(
            listOf(
                "tag" to WitType.U32,
                "text" to WitType.Str,
            ),
        )

        // Core types:
        // 0: () -> ()
        // 1: (i32, i32, i32, i32) -> i32   cabi_realloc
        // 2: (i32) -> ()                   post_return
        // 3: (i32, i64, i32) -> i32        echo_mixed / bounce / h.check
        // 4: (i32, i32) -> i32             echo_latin1_utf16
        // 5: (i32) -> i32                  sum_17_and_pair
        // 6: () -> i32                     ok
        val typeSection = listOf(
            7,
            0x60, 0x00, 0x00,
            0x60, 0x04, 0x7F, 0x7F, 0x7F, 0x7F, 0x01, 0x7F,
            0x60, 0x01, 0x7F, 0x00,
            0x60, 0x03, 0x7F, 0x7E, 0x7F, 0x01, 0x7F,
            0x60, 0x02, 0x7F, 0x7F, 0x01, 0x7F,
            0x60, 0x01, 0x7F, 0x01, 0x7F,
            0x60, 0x00, 0x01, 0x7F,
        )
        // Import index 0: h.check (type 3)
        val importSection = listOf(1) + wasmName("h") + wasmName("check") + listOf(0x00, 3)
        // Defined functions 1..8
        val functionSection = listOf(8, 1, 2, 0, 3, 3, 4, 5, 6)
        val memorySection = listOf(1, 0x00, 1)
        // Mutable $bump = 4096
        val globalSection = listOf(1, 0x7F, 0x01, 0x41) + sleb(4096) + listOf(0x0B)
        val exportSection = listOf(9) +
            wasmName("memory") + listOf(0x02, 0) +
            wasmName("cabi_realloc") + listOf(0x00, 1) +
            wasmName("post_return") + listOf(0x00, 2) +
            wasmName("void_fn") + listOf(0x00, 3) +
            wasmName("echo_mixed") + listOf(0x00, 4) +
            wasmName("bounce") + listOf(0x00, 5) +
            wasmName("echo_latin1_utf16") + listOf(0x00, 6) +
            wasmName("sum_17_and_pair") + listOf(0x00, 7) +
            wasmName("ok") + listOf(0x00, 8)

        val retPtr = listOf(0x41) + sleb(1024)
        // cabi_realloc(old, oldSize, align, newSize): bump allocator aligned to $align
        val reallocBody = codeBody(
            listOf(1 to 0x7F), // $ptr
            listOf(
                0x23, 0x00, // global.get $bump
                0x20, 0x02, // local.get $align
                0x41, 0x01, // i32.const 1
                0x6B, // i32.sub
                0x6A, // i32.add
                0x41, 0x00, // i32.const 0
                0x20, 0x02, // local.get $align
                0x6B, // i32.sub (== -align == ~(align - 1))
                0x71, // i32.and
                0x22, 0x04, // local.tee $ptr
                0x20, 0x03, // local.get $newSize
                0x6A, // i32.add
                0x24, 0x00, // global.set $bump
                0x20, 0x04, // local.get $ptr
            ),
        )
        val postReturnBody = codeBody(emptyList(), emptyList())
        val voidBody = codeBody(emptyList(), emptyList())
        val echoMixedBody = codeBody(
            emptyList(),
            retPtr + listOf(0x20, 0x00, 0x3A, 0x00, 0x00) + // i32.store8 offset=0
                retPtr + listOf(0x20, 0x01, 0x37, 0x03, 0x08) + // i64.store offset=8
                retPtr + listOf(0x20, 0x02, 0x36, 0x02, 0x10) + // i32.store offset=16
                retPtr,
        )
        val bounceBody = codeBody(
            emptyList(),
            listOf(0x20, 0x00, 0x20, 0x01, 0x20, 0x02, 0x10, 0x00),
        )
        val echoLatin1Body = codeBody(
            emptyList(),
            retPtr + listOf(0x20, 0x01, 0x36, 0x02, 0x00) + // tag at +0
                retPtr + listOf(0x20, 0x00, 0x36, 0x02, 0x04) + // ptr at +4
                retPtr + listOf(0x20, 0x01, 0x36, 0x02, 0x08) + // taggedLen at +8
                retPtr,
        )
        val sum17Body = codeBody(
            emptyList(),
            retPtr +
                listOf(
                    0x20, 0x00, 0x28, 0x02, 0x00, // load p0
                    0x20, 0x00, 0x28, 0x02, 64, // load p16
                    0x6A, // p0 + p16
                    0x36, 0x02, 0x00, // store sum at +0
                ) +
                retPtr +
                listOf(
                    0x20, 0x00, 0x28, 0x02, 64, // load p16
                    0x36, 0x02, 0x04, // store p16 at +4
                ) +
                retPtr,
        )
        val okBody = codeBody(emptyList(), listOf(0x41, 0x07))

        val codeSection = listOf(8) +
            reallocBody + postReturnBody + voidBody + echoMixedBody +
            bounceBody + echoLatin1Body + sum17Body + okBody

        val wasm = wasmModule(
            1 to typeSection,
            2 to importSection,
            3 to functionSection,
            5 to memorySection,
            6 to globalSection,
            7 to exportSection,
            10 to codeSection,
        )

        val plan = ComponentPlan(
            modules = listOf(PlanModule(0, "core0.wasm", listOf(PlanModuleImport("h", "check", "func")))),
            initializers = listOf(
                Initializer.Instantiate(0, 0, listOf(InstantiateArg("h", "check", CoreDef.Trampoline(0)))),
                Initializer.ExtractMemory(0, CoreExportRef(0, "memory")),
                Initializer.ExtractRealloc(0, CoreDef.Export(CoreExportRef(0, "cabi_realloc"))),
                Initializer.ExtractPostReturn(0, CoreDef.Export(CoreExportRef(0, "post_return"))),
            ),
            trampolines = mapOf(
                0 to Trampoline.LowerImport(
                    import = 0,
                    type = FuncType(listOf("x" to mixedVariant, "tail" to WitType.S32), listOf(WitType.S32)),
                    options = 0,
                ),
            ),
            options = mapOf(
                0 to CanonOptions(StringEncoding.UTF8, memory = 0, realloc = 0, postReturn = 0, async = false),
                1 to CanonOptions(StringEncoding.LATIN1_UTF16, memory = 0, realloc = 0, postReturn = 0, async = false),
                2 to CanonOptions(StringEncoding.UTF8, memory = 0, realloc = 0, postReturn = 99, async = false),
                3 to CanonOptions(StringEncoding.UTF8, memory = 0, realloc = 0, postReturn = null, async = false),
            ),
            imports = mapOf(0 to PlanImport("check-variant", emptyList())),
            resourceTables = emptyMap(),
            exports = listOf(
                PlanExport(null, "void-fn", CoreDef.Export(CoreExportRef(0, "void_fn")), 3, FuncType(emptyList(), emptyList())),
                PlanExport(null, "echo-mixed", CoreDef.Export(CoreExportRef(0, "echo_mixed")), 0, FuncType(listOf("x" to mixedVariant, "tail" to WitType.S32), listOf(mixedRecord))),
                PlanExport(null, "bounce", CoreDef.Export(CoreExportRef(0, "bounce")), 0, FuncType(listOf("x" to mixedVariant, "tail" to WitType.S32), listOf(WitType.S32))),
                PlanExport(null, "echo-latin1-utf16", CoreDef.Export(CoreExportRef(0, "echo_latin1_utf16")), 1, FuncType(listOf("s" to WitType.Str), listOf(latin1Record))),
                PlanExport(null, "sum-17-and-pair", CoreDef.Export(CoreExportRef(0, "sum_17_and_pair")), 0, FuncType((0 until 17).map { "p$it" to WitType.U32 }, listOf(WitType.U32, WitType.U32))),
                PlanExport(null, "ok", CoreDef.Export(CoreExportRef(0, "ok")), 0, FuncType(emptyList(), listOf(WitType.S32))),
                PlanExport(null, "bad-unsupported", CoreDef.Export(CoreExportRef(0, "void_fn")), 0, FuncType(listOf("u" to WitType.Unsupported("res")), emptyList())),
                PlanExport(null, "bad-post-return", CoreDef.Export(CoreExportRef(0, "ok")), 2, FuncType(emptyList(), listOf(WitType.S32))),
            ),
        )
        return Harness(
            component = ChicoryComponent(plan, mapOf(0 to CoreModule.interpreted(wasm))),
            variantType = mixedVariant,
            recordType = mixedRecord,
        )
    }

    @Test
    fun `cached WitType and FuncType layouts stay stable across reads`() {
        val h = buildHarness()
        val v = h.variantType
        assertEquals(1, v.discSize)
        assertEquals(8, v.maxCaseAlign)
        assertEquals(8, v.payloadOffset)
        assertEquals(16, v.size)
        assertEquals(8, v.align)
        assertEquals(listOf(CanonicalAbi.CoreType.I32, CanonicalAbi.CoreType.I64), v.flat)
        assertSame(v.flat, v.flat)

        val r = h.recordType
        assertEquals(listOf(v, WitType.S32), r.fieldTypes)
        assertSame(r.fieldTypes, r.fieldTypes)
        assertEquals(8, r.align)
        assertEquals(24, r.size)

        val optStr = WitType.Variant(WitType.VariantKind.OPTION, listOf("none" to null, "some" to WitType.Str))
        assertEquals(1, optStr.discSize)
        assertEquals(4, optStr.maxCaseAlign)
        assertEquals(4, optStr.payloadOffset)
        assertEquals(12, optStr.size)
        assertEquals(4, optStr.align)
        assertEquals(listOf(CanonicalAbi.CoreType.I32, CanonicalAbi.CoreType.I32, CanonicalAbi.CoreType.I32), optStr.flat)
        assertSame(optStr.flat, optStr.flat)

        val ft = FuncType(listOf("a" to WitType.S32, "b" to WitType.Str), listOf(WitType.Bool))
        assertSame(ft.paramTypes, ft.paramTypes)
        assertSame(ft.paramTuple, ft.paramTuple)
        assertSame(ft.resultTuple, ft.resultTuple)
        assertEquals(3, ft.flatParamCount)
        assertEquals(1, ft.flatResultCount)

        val emptyFlags = WitType.Flags(emptyList())
        assertEquals(1, emptyFlags.align)
        assertEquals(0, emptyFlags.size)
        assertEquals(emptyList<CanonicalAbi.CoreType>(), emptyFlags.flat)
    }

    @Test
    fun `zero arg and zero result sync export succeeds`() {
        buildHarness().component.instantiate(null).use { backend ->
            val fn = backend.resolve("void-fn")
            val result = backend.invoke(fn, WitWriter().toReader())
            assertEquals(0, result.remaining)
        }
    }

    @Test
    fun `mixed flat variant roundtrips and preserves trailing s32 across lowerFlat and liftFlat`() {
        val seen = ArrayList<String>()
        val dispatcher = object : WitHostImportDispatcher {
            override fun hostFunction(key: String): HostFunction? = if (key == "check-variant") {
                HostFunction { args, results ->
                    val tag = args.byte().toInt()
                    val payloadDesc = when (tag) {
                        0 -> "num:${args.float()}"
                        1 -> "wide:${args.long()}"
                        2 -> "empty"
                        else -> error("unexpected tag $tag")
                    }
                    val tail = args.int()
                    seen += "$payloadDesc|tail=$tail"
                    results.int(tail + 1000)
                }
            } else {
                null
            }
        }

        buildHarness().component.instantiate(dispatcher).use { backend ->
            val echo = backend.resolve("echo-mixed")
            val bounce = backend.resolve("bounce")

            // Case 0: f32 coerced in I64 flat slot + trailing s32
            val r0 = backend.invoke(
                echo,
                WitWriter().apply { byte(0); float(-12.5f); int(77) }.toReader(),
            )
            assertEquals(0, r0.byte().toInt())
            assertEquals(-12.5f, r0.float())
            assertEquals(77, r0.int())
            assertEquals(0, r0.remaining)

            // Case 1: s64 in I64 flat slot + trailing s32
            val r1 = backend.invoke(
                echo,
                WitWriter().apply { byte(1); long(0x0102_0304_0506_0708L); int(-99) }.toReader(),
            )
            assertEquals(1, r1.byte().toInt())
            assertEquals(0x0102_0304_0506_0708L, r1.long())
            assertEquals(-99, r1.int())

            // Case 2: empty payload + trailing s32 via host import (tests FlatReader.skip)
            val b0 = backend.invoke(bounce, WitWriter().apply { byte(0); float(-12.5f); int(77) }.toReader()).int()
            val b1 = backend.invoke(bounce, WitWriter().apply { byte(1); long(0x0102_0304_0506_0708L); int(-99) }.toReader()).int()
            val b2 = backend.invoke(bounce, WitWriter().apply { byte(2); int(42) }.toReader()).int()
            assertEquals(1077, b0)
            assertEquals(901, b1)
            assertEquals(1042, b2)
            assertEquals(
                listOf(
                    "num:-12.5|tail=77",
                    "wide:72623859790382856|tail=-99",
                    "empty|tail=42",
                ),
                seen,
            )
        }
    }

    @Test
    fun `latin1 plus utf16 sets UTF16_TAG only when non-Latin1 code units are present`() {
        buildHarness().component.instantiate(null).use { backend ->
            val fn = backend.resolve("echo-latin1-utf16")

            val latin = backend.invoke(fn, WitWriter().apply { string("hello-\u00e9") }.toReader())
            val latinTag = latin.int()
            assertEquals(7, latinTag)
            assertEquals("hello-\u00e9", latin.string())

            val utf16 = backend.invoke(fn, WitWriter().apply { string("あ🎉") }.toReader())
            val utf16Tag = utf16.int()
            assertEquals(Int.MIN_VALUE or 3, utf16Tag)
            assertEquals("あ🎉", utf16.string())
        }
    }

    @Test
    fun `params over MAX_FLAT_PARAMS spill to tuple pointer and lift multi-result tuple`() {
        buildHarness().component.instantiate(null).use { backend ->
            val fn = backend.resolve("sum-17-and-pair")
            val w = WitWriter()
            for (i in 0 until 17) w.int(i + 10)
            val r = backend.invoke(fn, w.toReader())
            assertEquals(10 + 26, r.int())
            assertEquals(26, r.int())
            assertEquals(0, r.remaining)
        }
    }

    @Test
    fun `unsupported type fails on invocation and missing post_return fails at resolve`() {
        val unsupported = WitType.Unsupported("future-or-stream")
        val badVariant = WitType.Variant(WitType.VariantKind.OPTION, listOf("none" to null, "some" to unsupported))
        val badRecord = WitType.Record(listOf("bad" to unsupported))
        val badFunc = FuncType(listOf("x" to unsupported), emptyList())
        assertThrows<UnsupportedOperationException> { unsupported.size }
        assertThrows<UnsupportedOperationException> { badVariant.align }
        assertThrows<UnsupportedOperationException> { badRecord.flat }
        assertThrows<UnsupportedOperationException> { badFunc.flatParamCount }

        buildHarness().component.instantiate(null).use { backend ->
            // Instantiation and unaffected exports continue to work.
            val ok = backend.resolve("ok")
            assertEquals(7, backend.invoke(ok, WitWriter().toReader()).int())

            assertThrows<NoSuchElementException> { backend.resolve("bad-post-return") }
            assertEquals(7, backend.invoke(ok, WitWriter().toReader()).int())

            // Unsupported type raises a host error without poisoning the instance (ADR-0007).
            val bad = backend.resolve("bad-unsupported")
            assertThrows<UnsupportedOperationException> { backend.invoke(bad, WitWriter().toReader()) }
            assertEquals(7, backend.invoke(ok, WitWriter().toReader()).int())
        }
    }

    @Test
    fun `threads instantiate separate backends sharing ComponentPlan and cached WitType layouts`() {
        val component = ChicoryComponent.fromDirectory(TestComponent("types").dir)
        val threads = 4
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        try {
            val futures = (0 until threads).map { threadId ->
                pool.submit<Pair<Triple<Int, Int, Int>, List<String?>>> {
                    // Each thread uses its own ComponentBackend instance; ComponentPlan and WitType caches are shared.
                    component.instantiate(null).use { backend ->
                        ready.countDown()
                        assertTrue(ready.await(10, TimeUnit.SECONDS), "Timed out waiting for concurrent workers")
                        val echoName = backend.resolve("echo-name")
                        val echoBytes = backend.resolve("echo-bytes")
                        val echoBlob = backend.resolve("echo-blob")
                        val observed = ArrayList<String?>()
                        repeat(20) { iter ->
                            val input = if (iter % 2 == 0) "t$threadId-$iter" else null
                            val out = backend.invoke(
                                echoName,
                                WitWriter().apply {
                                    if (input == null) {
                                        byte(0)
                                    } else {
                                        byte(1)
                                        string(input)
                                    }
                                }.toReader(),
                            )
                            val some = out.bool()
                            val value = if (some) out.string() else null
                            assertEquals(input, value)
                            if (iter == 0) observed += value

                            val payload = byteArrayOf(threadId.toByte(), iter.toByte(), 3, 4)
                            val bytesOut = backend.invoke(echoBytes, WitWriter().apply { bytes(payload) }.toReader())
                            assertEquals(payload.toList(), bytesOut.bytes().toList())

                            val blobOut = backend.invoke(
                                echoBlob,
                                WitWriter().apply {
                                    string("tag-$threadId-$iter")
                                    bytes(byteArrayOf(1, 2, 3, 4))
                                    byte(1)
                                    int(iter)
                                }.toReader(),
                            )
                            assertEquals("tag-$threadId-$iter", blobOut.string())
                            assertEquals(4, blobOut.bytes().size)
                            assertTrue(blobOut.bool())
                            assertEquals(iter, blobOut.int())
                        }
                        Triple(echoName, echoBytes, echoBlob) to observed
                    }
                }
            }
            val indices = futures.map { f: Future<Pair<Triple<Int, Int, Int>, List<String?>>> ->
                f.get(15, TimeUnit.SECONDS).first
            }
            assertEquals(1, indices.toSet().size)
            assertEquals(3, indices.first().toList().distinct().size)
            assertNull(
                component.instantiate(null).use { backend ->
                    val r = backend.invoke(backend.resolve("echo-name"), WitWriter().apply { byte(0) }.toReader())
                    if (r.bool()) r.string() else null
                },
            )
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `ComponentPlan exposes only usesAsync and parse as unmangled public methods`() {
        val planMethods = ComponentPlan::class.java.methods.map { it.name }.toSet()
        assertTrue("getUsesAsync" in planMethods)
        for (hidden in listOf(
            "getModules",
            "getInitializers",
            "getTrampolines",
            "getOptions",
            "getImports",
            "getResourceTables",
            "getExports",
            "getStreamTables",
            "getFutureTables",
            "getErrorContextTables",
        )) {
            assertTrue(hidden !in planMethods, "Expected $hidden to be internal (mangled), not public")
        }
        val companionMethods = ComponentPlan.Companion::class.java.methods.map { it.name }.toSet()
        assertTrue("parse" in companionMethods)
        assertTrue("fromJson" !in companionMethods)
    }
}
