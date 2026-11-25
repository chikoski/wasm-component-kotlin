@file:OptIn(wasm2kt.runtime.canonical.InternalWasm2KtApi::class)

package wasm2kt.tooling.aot

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LineNumberNode
import wasm2kt.runtime.ComponentTrapException
import wasm2kt.runtime.aot.FuncRef
import wasm2kt.runtime.aot.FuncTable
import wasm2kt.runtime.aot.HostTrampoline
import wasm2kt.runtime.aot.I32Global
import wasm2kt.runtime.aot.LinearMemory
import wasm2kt.runtime.aot.WasmTable
import wasm2kt.runtime.aot.registerHostTrampoline
import wasm2kt.tooling.Decomposer
import wasm2kt.tooling.ir.ChicoryWasmAdapter
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AotCoreCompilerTest {
    private val fixturesDir: Path = Paths.get(System.getProperty("wasm2kt.fixtures"))
    private val rootDir: Path = Paths.get(System.getProperty("wasm2kt.rootDir"))

    @Test
    fun `all fixture core modules including heavy compile cleanly with zero interpreter fallback and pass CheckClassAdapter`(@TempDir tempDir: Path) {
        val componentFiles = fixturesDir.listDirectoryEntries("*.wasm").sortedBy { it.name }.toMutableList()
        val extraWasm = System.getProperty("wasm2kt.extraWasm")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
        if (extraWasm != null) {
            assertTrue(Files.isRegularFile(extraWasm), "Expected wasm2kt.extraWasm to exist at $extraWasm")
            componentFiles.add(extraWasm)
        }

        var checkedHeavy366 = false
        for (component in componentFiles) {
            val decomposed = tempDir.resolve(component.nameWithoutExtension)
            Decomposer.decompose(component, decomposed)
            val coreFiles = coreModulesSorted(decomposed)
            assertTrue(coreFiles.isNotEmpty(), "${component.name} must produce at least one core module")
            for ((modIdx, coreFile) in coreFiles) {
                val ir = ChicoryWasmAdapter.convert(Files.readAllBytes(coreFile), moduleIndex = modIdx)
                val compiled = AotCoreCompiler.compile(
                    ir = ir,
                    packageName = "wasm2kt.test.fixtures.${sanitizePkg(component.nameWithoutExtension)}",
                    baseSimpleName = "Core$modIdx",
                )
                val report = AotCoreCompiler.verifyAll(compiled)
                assertEquals(0, compiled.stats.interpretedFunctions, "${component.name}#core$modIdx must not fall back to interpreter")
                val topMethods = compiled.methodCodeBytes.entries.sortedByDescending { it.value }.take(8).joinToString { "${it.key}=${it.value}" }
                assertEquals(0, report.artOversizedMethods, "${component.name}#core$modIdx must have 0 ART oversized methods (max=${report.maxMethodCodeBytes} in ${report.maxMethodName}; top: $topMethods)")
                assertTrue(
                    report.maxMethodCodeBytes <= 8_000,
                    "${component.name}#core$modIdx max method ${report.maxMethodName} is ${report.maxMethodCodeBytes} bytes (expected <= 8000; top: $topMethods)",
                )
                assertTrue(report.maxConstantPoolCount < 65_535, "${component.name}#core$modIdx CP overflow")

                if (component.name == "heavy.wasm" && modIdx == 0) {
                    val f366Len = compiled.methodCodeBytes.entries.first { ".func_366(" in it.key }.value
                    assertTrue(f366Len <= 8_000, "heavy.wasm func_366 root len=$f366Len exceeds 8000")
                    assertTrue(compiled.stats.splitFunctions >= 1)
                    checkedHeavy366 = true
                }
            }
        }
        assertTrue(checkedHeavy366, "Expected to verify heavy.wasm func_366")
    }

    @Test
    fun `all runtime-chicory component core modules compile and pass CheckClassAdapter`() {
        val componentsDir = rootDir.resolve("runtime-chicory/src/test/resources/components")
        val coreWasms = Files.walk(componentsDir).use { stream ->
            stream.filter { it.name.startsWith("core") && it.name.endsWith(".wasm") }
                .sorted()
                .toList()
        }
        assertTrue(coreWasms.size >= 35)
        for ((i, coreFile) in coreWasms.withIndex()) {
            val idx = coreFile.name.removePrefix("core").removeSuffix(".wasm").toInt()
            val ir = ChicoryWasmAdapter.convert(Files.readAllBytes(coreFile), moduleIndex = idx)
            val compiled = AotCoreCompiler.compile(ir, "wasm2kt.test.comp$i", "Core$idx")
            val report = AotCoreCompiler.verifyAll(compiled)
            assertEquals(0, compiled.stats.interpretedFunctions)
            assertEquals(0, report.artOversizedMethods)
        }
    }

    @Test
    fun `semantic edge cases execute identically to Wasm spec on compiled JVM classes`(@TempDir tempDir: Path) {
        val mod = wasmModule(
            1 to typeSection(
                funcType(listOf(I32, I32), listOf(I32)), // 0: i32_div_s
                funcType(listOf(I64, I64), listOf(I64)), // 1: i64_div_s
                funcType(listOf(I32, I32), listOf(I32)), // 2: i32_rem_s
                funcType(listOf(F32), listOf(I32)), // 3: i32.trunc_f32_s
                funcType(listOf(F64), listOf(I64)), // 4: i64.trunc_f64_u
                funcType(listOf(F32), listOf(F32)), // 5: nan preserve store/load/abs/neg
                funcType(listOf(I32), listOf(I32)), // 6: memory.grow visibility across callee
                funcType(emptyList(), listOf(I32, I64, F32)), // 7: multi-value return
                funcType(listOf(I32), listOf(I32)), // 8: br_table + block (result i32) (unreachable)
            ),
            3 to funcSection(0, 1, 2, 3, 4, 5, 6, 7, 8, 6),
            5 to memorySection(0x00 to 1, 0x01 to 1, 0x01 to 2), // mem0 unbounded (init 1), mem1 (1..2)
            6 to globalSection(
                // extended-const global: (10 + 5) * 3 = 45
                globalDef(I32, true, listOf(0x41, 0x0A, 0x41, 0x05, 0x6A, 0x41, 0x03, 0x6C, 0x0B)),
            ),
            7 to exportSection(
                "i32DivS" to (0x00 to 0),
                "i64DivS" to (0x00 to 1),
                "i32RemS" to (0x00 to 2),
                "truncF32S" to (0x00 to 3),
                "truncF64U" to (0x00 to 4),
                "nanPreserve" to (0x00 to 5),
                "growVisible" to (0x00 to 6),
                "multiVal" to (0x00 to 7),
                "brTableEdge" to (0x00 to 8),
                "helperGrow" to (0x00 to 9),
                "g0" to (0x03 to 0),
                "m1" to (0x02 to 1),
            ),
            10 to codeSection(
                // 0: i32.div_s
                codeBody(emptyList(), listOf(0x20, 0x00, 0x20, 0x01, 0x6D)),
                // 1: i64.div_s
                codeBody(emptyList(), listOf(0x20, 0x00, 0x20, 0x01, 0x7F)),
                // 2: i32.rem_s
                codeBody(emptyList(), listOf(0x20, 0x00, 0x20, 0x01, 0x6F)),
                // 3: i32.trunc_f32_s
                codeBody(emptyList(), listOf(0x20, 0x00, 0xA8)),
                // 4: i64.trunc_f64_u
                codeBody(emptyList(), listOf(0x20, 0x00, 0xB1)),
                // 5: store f32 at addr 8, load f32, f32.neg, f32.abs, reinterpret roundtrip
                codeBody(
                    emptyList(),
                    listOf(
                        0x41, 0x08, // addr
                        0x20, 0x00, // val
                        0x38, 0x02, 0x00, // f32.store
                        0x41, 0x08, // addr
                        0x2A, 0x02, 0x00, // f32.load
                        0x8C, // f32.neg
                        0x8B, // f32.abs -> Wait: flip sign once more after abs with copysign from input
                        0x20, 0x00, // original sign
                        0x98, // f32.copysign
                    ),
                ),
                // 6: caller stores 77 to mem0[0], calls helperGrow(1), then writes to newly grown mem0[65536] and reads mem0[0] + mem0[65536]
                codeBody(
                    listOf(1 to I32),
                    listOf(0x41, 0x00, 0x41) + sleb(77L) + listOf(0x36, 0x02, 0x00) + // mem0[0] = 77
                        listOf(0x41, 0x01, 0x10, 0x09, 0x21, 0x01) + // local1 = call 9(1) -> old pages (1)
                        // Now mem0 has grown to 2 pages; write 66 at 65536
                        listOf(0x41) + sleb(65536L) + listOf(0x41) + sleb(66L) + listOf(0x36, 0x02, 0x00) + // mem0[65536] = 66
                        listOf(0x41, 0x00, 0x28, 0x02, 0x00) + // mem0[0] (77)
                        listOf(0x41) + sleb(65536L) + listOf(0x28, 0x02, 0x00, 0x6A) + // + mem0[65536] (66)
                        listOf(0x20, 0x01, 0x6A), // + oldPages (1) = 144
                ),
                // 7: multi-value () -> (i32, i64, f32) reading global 0 (45) and mutating global 0 += 1
                codeBody(
                    emptyList(),
                    listOf(
                        0x23, 0x00, // global.get 0
                        0x23, 0x00, 0x41, 0x01, 0x6A, 0x24, 0x00, // g0 = g0 + 1
                        0x42) + sleb(1234567890123L) +
                        listOf(0x43) + f32LeBytes(java.lang.Float.floatToRawIntBits(2.5f)),
                ),
                // 8: br_table + (block (result i32) (unreachable)) dead region inside live fn
                codeBody(
                    emptyList(),
                    listOf(
                        0x02, 0x7F, // block (result i32)
                        0x41, 0x0A, // extra item on stack to test unwind!
                        0x02, 0x7F, // block (result i32)
                        0x00, // unreachable!
                        0x0B, // end dead block -> stack still polymorphic inside outer block? NO, handleEnd resets poly?
                        // Wait: if inner block was unreachable, outer block after inner block is dead on JVM!
                        // Wait: let's test an unreachable inner block INSIDE an if branch instead so normal fallthrough is live:
                        0x1A, 0x1A, // unreachable path dropped
                        0x0B,
                    ).let {
                        listOf(
                            0x02, 0x7F, // outer block (result i32)
                            0x02, 0x40, // inner block ()
                            0x02, 0x40, // case0 block ()
                            0x41, 0x2A, // dead excess on stack (height 1)
                            0x20, 0x00, // selector
                            0x0E, 0x02, 0x00, 0x01, 0x00, // br_table [0, 1] dflt=0 (all void labels unwinding excess 42!)
                            0x0B, // end case0 -> lands here if sel == 0 or >=2
                            0x41, 0x0B, // push 11
                            0x0C, 0x01, // br outer(11)
                            0x0B, // end inner -> lands here if sel == 1
                            0x41, 0x16, // push 22
                            0x0B, // end outer -> 22
                        )
                    },
                ),
                // 9: helperGrow(delta: i32, unused: i32): grows mem0 by arg0, returns prev pages
                codeBody(emptyList(), listOf(0x20, 0x00, 0x40, 0x00)),
            ),
            11 to dataSection(
                activeData(1, listOf(0x41, 0x10, 0x0B), "hello-mem1".encodeToByteArray()),
            ),
        )

        val ir = ChicoryWasmAdapter.convert(mod, moduleIndex = 0)
        val classesOut = tempDir.resolve("classes")
        val resOut = tempDir.resolve("resources")
        val compiled = AotCoreCompiler.compileToDirectory(ir, classesOut, resOut, "wasm2kt.test.sem", "Core0")
        val dataPath = assertNotNull(compiled.dataResourcePath)
        assertTrue(Files.isRegularFile(resOut.resolve(dataPath)))
        assertFalse(Files.exists(resOut.resolve("wasm2kt/test/sem/Core0.meta")))
        AotCoreCompiler.verifyAll(compiled)

        val loader = CompiledModuleLoader(compiled)
        val inst1 = loader.instantiate()
        val inst2 = loader.instantiate()

        assertThrows<ComponentTrapException> { loader.invoke(inst1, "i32DivS", 10, 0) }
        assertThrows<ComponentTrapException> { loader.invoke(inst1, "i32DivS", Int.MIN_VALUE, -1) }
        assertEquals(-5, loader.invoke(inst1, "i32DivS", 10, -2))
        assertEquals(0, loader.invoke(inst1, "i32RemS", Int.MIN_VALUE, -1))
        assertThrows<ComponentTrapException> { loader.invoke(inst1, "i64DivS", Long.MIN_VALUE, -1L) }

        assertThrows<ComponentTrapException> { loader.invoke(inst1, "truncF32S", Float.NaN) }
        assertThrows<ComponentTrapException> { loader.invoke(inst1, "truncF32S", 2147483648.0f) }
        assertEquals(Int.MIN_VALUE, loader.invoke(inst1, "truncF32S", -2147483648.0f))
        assertThrows<ComponentTrapException> { loader.invoke(inst1, "truncF64U", -1.0) }
        assertEquals(-1L shl 11, loader.invoke(inst1, "truncF64U", 18446744073709551616.0 - 2048.0))

        // NaN bit pattern preservation (negative signaling NaN)
        val nanBits = 0xFF80_1234.toInt()
        val outNan = loader.invoke(inst1, "nanPreserve", java.lang.Float.intBitsToFloat(nanBits)) as Float
        assertEquals(nanBits, java.lang.Float.floatToRawIntBits(outNan))

        // LinearMemory growth visible across callee without stale caching
        assertEquals(144, loader.invoke(inst1, "growVisible", 0))

        // Separate instances do not share mutable state (globals / linear memory)
        val mv1 = loader.invoke(inst1, "multiVal")!!
        val mv2 = loader.invoke(inst2, "multiVal")!!
        val mvClass = mv1.javaClass
        assertEquals(45, mvClass.getField("v0").getInt(mv1))
        assertEquals(1234567890123L, mvClass.getField("v1").getLong(mv1))
        assertEquals(2.5f, mvClass.getField("v2").getFloat(mv1))
        assertEquals(45, mvClass.getField("v0").getInt(mv2)) // inst2 still 45!
        val mv1Again = loader.invoke(inst1, "multiVal")!!
        assertEquals(46, mvClass.getField("v0").getInt(mv1Again))

        // Memory 1 initialized from fixed binary data resource path
        val m1 = loader.exportedMemory(inst1, "m1")
        assertEquals("hello-mem1", m1.readBytes(16, 10).decodeToString())

        // br_table unwind
        assertEquals(11, loader.invoke(inst1, "brTableEdge", 0))
        assertEquals(22, loader.invoke(inst1, "brTableEdge", 1))
        assertEquals(11, loader.invoke(inst1, "brTableEdge", 99))
    }

    @Test
    fun `two-level tableswitch splits call_indirect across 2000 functions and validates cross-module signatures`() {
        val count = 2_000
        val bodies = (0 until count).map { i ->
            // fn i: (x: i32) -> x + i * 3
            codeBody(emptyList(), listOf(0x20, 0x00, 0x41) + sleb((i * 3).toLong()) + listOf(0x6A))
        } + listOf(
            // dispatcher test caller fn count: (arg, elemIdx) -> call_indirect(type 0)
            codeBody(emptyList(), listOf(0x20, 0x00, 0x20, 0x01, 0x11, 0x00, 0x00)),
            // signature mismatch caller fn count+1: (elemIdx) -> call_indirect(type 2: () -> ())
            codeBody(emptyList(), listOf(0x20, 0x00, 0x11, 0x02, 0x00)),
        )
        val funcTypes = List(count) { 0 } + listOf(1, 3)
        val mod = wasmModule(
            1 to typeSection(
                funcType(listOf(I32), listOf(I32)),
                funcType(listOf(I32, I32), listOf(I32)),
                funcType(emptyList(), emptyList()),
                funcType(listOf(I32), emptyList()),
            ),
            3 to funcSection(*funcTypes.toIntArray()),
            4 to tableSection(funcrefTable(count + 2)),
            7 to exportSection(
                "callAt" to (0x00 to count),
                "callMismatch" to (0x00 to count + 1),
                "tab" to (0x01 to 0),
            ),
            9 to elementSection(
                // active elem segment offset 0 with func indices 0..1999 (all 2000 functions)
                listOf(0x00, 0x41, 0x00, 0x0B) + uleb(count) + (0 until count).flatMap { uleb(it) },
            ),
            10 to codeSection(*bodies.toTypedArray()),
        )

        val ir = ChicoryWasmAdapter.convert(mod, moduleIndex = 0)
        val compiled = AotCoreCompiler.compile(ir, "wasm2kt.test.bigtbl", "Core0")
        val report = AotCoreCompiler.verifyAll(compiled)
        assertEquals(0, compiled.stats.interpretedFunctions)
        assertEquals(0, report.artOversizedMethods)
        assertTrue(
            report.maxMethodCodeBytes <= 8_000,
            "Max method in 2000-func dispatch module was ${report.maxMethodCodeBytes} (${report.maxMethodName})",
        )

        val loader = CompiledModuleLoader(compiled)
        val inst = loader.instantiate()
        assertEquals(10, loader.invoke(inst, "callAt", 10, 0))
        assertEquals(10 + 127 * 3, loader.invoke(inst, "callAt", 10, 127))
        assertEquals(10 + 128 * 3, loader.invoke(inst, "callAt", 10, 128))
        assertEquals(10 + 1000 * 3, loader.invoke(inst, "callAt", 10, 1000))
        assertEquals(10 + 1999 * 3, loader.invoke(inst, "callAt", 10, 1999))
        assertThrows<ComponentTrapException> { loader.invoke(inst, "callAt", 10, count) } // uninitialized slot
        assertThrows<ComponentTrapException> { loader.invoke(inst, "callAt", 10, count + 50) } // OOB
        assertThrows<ComponentTrapException> { loader.invoke(inst, "callMismatch", 0) } // signature mismatch

        // Cross-module host trampoline & foreign CoreModuleInstance via shared FuncTable
        val tab = loader.exportedTable<FuncRef>(inst, "tab")
        tab.registerHostTrampoline(count, "(I)I", HostTrampoline { args -> longArrayOf(args[0] * 11) })
        assertEquals(77, loader.invoke(inst, "callAt", 7, count))

        val foreignMod = wasmModule(
            1 to typeSection(funcType(listOf(I32), listOf(I32))),
            3 to funcSection(0),
            7 to exportSection("f" to (0x00 to 0)),
            10 to codeSection(codeBody(emptyList(), listOf(0x20, 0x00, 0x41, 0x05, 0x6A))),
        )
        val foreignCompiled = AotCoreCompiler.compile(ChicoryWasmAdapter.convert(foreignMod, 1), "wasm2kt.test.foreign", "Core1")
        AotCoreCompiler.verifyAll(foreignCompiled)
        val foreignLoader = CompiledModuleLoader(foreignCompiled)
        val foreignInst = foreignLoader.instantiate()
        tab.set(count + 1, FuncRef.core(foreignInst, 0, "(I)I"))
        assertEquals(15, loader.invoke(inst, "callAt", 10, count + 1))

        // Regression (M1): FuncRef.Host sharing owner === inst & funcIndex must still dispatch via HostTrampoline
        val sameOwnerHost = FuncRef.host(
            signature = "(I)I",
            trampoline = HostTrampoline { args -> longArrayOf(args[0] * 100L) },
            owner = inst,
            funcIndex = 0,
        )
        tab.set(count, sameOwnerHost)
        assertEquals(700, loader.invoke(inst, "callAt", 7, count))
    }

    @Test
    fun `pre-emission splitter handles over-70KB function with over 300 locals and LineNumberTable`() {
        // Function 0: (x: i32, depth: i32) -> i32
        // 320 locals (> 350 JVM slots with i64s), huge straight-line + nested if/loop body > 70 KB JVM bytecode,
        // conditional local writes, external br/return inside nested loops, and recursive self-call.
        val i32Locals = 260
        val i64Locals = 60
        val insns = mutableListOf<Int>()
        // Initialize a spread of locals so liveIn/liveOut spans > 255 slots!
        for (l in 2 until 2 + i32Locals) {
            insns += listOf(0x20, 0x00, 0x41) + sleb((l % 17).toLong()) + listOf(0x6A, 0x21) + uleb(l)
        }
        for (idx in 0 until i64Locals) {
            val l = 2 + i32Locals + idx
            insns += listOf(0x42) + sleb((idx + 1).toLong()) + listOf(0x21) + uleb(l)
        }

        // Loop + outer block + if with early br / return and 1600 repeating basic blocks of math (~75 KB JVM bytecode)
        insns += listOf(0x02, 0x40) // block void
        insns += listOf(0x20, 0x01, 0x45, 0x04, 0x40) // if depth == 0
        repeat(1_800) { step ->
            val targetLocal = 2 + (step % 250)
            val auxLocal = 2 + ((step * 7 + 3) % 250)
            insns += listOf(0x20) + uleb(targetLocal)
            insns += listOf(0x20) + uleb(auxLocal)
            insns += listOf(0x6A, 0x41, 0x01, 0x6A, 0x21) + uleb(targetLocal)
            if (step % 200 == 0) {
                // Conditional local write + outer br edge
                insns += listOf(0x20, 0x00, 0x41) + sleb(-999) + listOf(0x46, 0x04, 0x40) // if x == -999
                insns += listOf(0x41, 0x2A, 0x0F) // return 42!
                insns += listOf(0x0B)
            }
        }
        // Touch i64 locals inside main body so they remain live across splits
        for (idx in 0 until i64Locals) {
            val l = 2 + i32Locals + idx
            insns += listOf(0x20) + uleb(l)
            insns += listOf(0x42, 0x01, 0x7C, 0x21) + uleb(l)
        }
        insns += listOf(0x0B) // end if depth == 0
        insns += listOf(0x0B) // end block

        // If depth > 0, recursively call self(x, depth - 1) and add result
        insns += listOf(0x20, 0x01, 0x41, 0x00, 0x4A, 0x04, 0x7F) // if depth > 0 -> i32
        insns += listOf(0x20, 0x00, 0x20, 0x01, 0x41, 0x01, 0x6B, 0x10, 0x00, 0x41, 0x01, 0x6A) // call 0(x, depth-1) + 1
        insns += listOf(0x05) // else
        insns += listOf(0x20, 0x02, 0x20) + uleb(2 + 249) + listOf(0x6A) // local[2] + local[251]
        insns += listOf(0x20) + uleb(2 + i32Locals) + listOf(0xA7, 0x6A) // + i32.wrap(i64_0)
        insns += listOf(0x0B) // end if

        val mod = wasmModule(
            1 to typeSection(funcType(listOf(I32, I32), listOf(I32))),
            3 to funcSection(0),
            7 to exportSection("big" to (0x00 to 0)),
            10 to codeSection(codeBody(listOf(i32Locals to I32, i64Locals to I64), insns)),
        )

        val ir = ChicoryWasmAdapter.convert(mod, moduleIndex = 0)
        val compiled = AotCoreCompiler.compile(ir, "wasm2kt.test.bigfn", "Core0")
        val report = AotCoreCompiler.verifyAll(compiled)
        assertEquals(0, compiled.stats.interpretedFunctions)
        assertEquals(0, report.artOversizedMethods)
        assertTrue(compiled.stats.generatedSplitHelpers >= 4, "Expected multiple pre-split helpers, got ${compiled.stats.generatedSplitHelpers}")
        assertTrue(report.maxMethodCodeBytes <= 8_000, "Max split method size=${report.maxMethodCodeBytes} (${report.maxMethodName})")

        // Verify LineNumberTable exists in FuncGroup_0
        val fgBytes = compiled.classes.getValue("wasm2kt/test/bigfn/Core0FuncGroup_0")
        val cn = ClassNode()
        ClassReader(fgBytes).accept(cn, 0)
        val hasLines = cn.methods.any { m ->
            var cur = m.instructions.first
            var found = false
            while (cur != null) {
                if (cur is LineNumberNode) found = true
                cur = cur.next
            }
            found
        }
        assertTrue(hasLines, "Expected LineNumberTable entries in generated FuncGroup_0")

        // Execute split + spilled + recursive calls and verify exact semantics
        val loader = CompiledModuleLoader(compiled)
        val inst = loader.instantiate()
        assertEquals(42, loader.invoke(inst, "big", -999, 0))
        val expectedBase = loader.invoke(inst, "big", 5, 0) as Int
        assertEquals(expectedBase + 2, loader.invoke(inst, "big", 5, 2))
    }

    @Test
    fun `handles wide parameter signatures exceeding 255 JVM slots and shared cross-module globals`() {
        val paramCount = 260
        val sumInsns = mutableListOf<Int>(0x41, 0x00)
        for (i in 0 until paramCount) {
            sumInsns += listOf(0x20) + uleb(i) + listOf(0x6A)
        }
        val modA = wasmModule(
            1 to typeSection(funcType(List(paramCount) { I32 }, listOf(I32))),
            3 to funcSection(0),
            6 to globalSection(globalDef(I32, true, listOf(0x41, 0x07, 0x0B))),
            7 to exportSection("sum260" to (0x00 to 0), "sharedG" to (0x03 to 0)),
            10 to codeSection(codeBody(emptyList(), sumInsns)),
        )
        val compiledA = AotCoreCompiler.compile(ChicoryWasmAdapter.convert(modA, 0), "wasm2kt.test.wide", "Core0")
        AotCoreCompiler.verifyAll(compiledA)
        val loaderA = CompiledModuleLoader(compiledA)
        val instA = loaderA.instantiate()
        val sumArgs = Array<Any?>(paramCount) { i -> i + 1 }
        val expectedSum = (1..paramCount).sum()
        assertEquals(expectedSum, loaderA.invoke(instA, "sum260", *sumArgs))

        val sharedCell = loaderA.exportedGlobalCell(instA, "sharedG") as I32Global
        assertEquals(7, sharedCell.value)

        // Module B imports sharedG from A and increments it.
        val modB = wasmModule(
            1 to typeSection(funcType(emptyList(), listOf(I32))),
            2 to importSection(globalImport("a", "sharedG", I32, mutable = true)),
            3 to funcSection(0),
            7 to exportSection("inc" to (0x00 to 0)),
            10 to codeSection(codeBody(emptyList(), listOf(0x23, 0x00, 0x41, 0x05, 0x6A, 0x24, 0x00, 0x23, 0x00))),
        )
        val compiledB = AotCoreCompiler.compile(ChicoryWasmAdapter.convert(modB, 1), "wasm2kt.test.wideB", "Core1")
        AotCoreCompiler.verifyAll(compiledB)
        val loaderB = CompiledModuleLoader(compiledB)
        val instB = loaderB.instantiate(sharedCell)
        assertEquals(12, loaderB.invoke(instB, "inc"))
        assertEquals(12, sharedCell.value)
    }

    @Test
    fun `compiles and instantiates table with u32 max limit 0xFFFFFFFF`() {
        val modBytes = wasmModule(
            1 to typeSection(funcType(emptyList(), listOf(I32))),
            3 to funcSection(0),
            4 to tableSection(listOf(FUNCREF, 0x01, 0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0x0F)),
            7 to exportSection("tab" to (0x01 to 0), "sz" to (0x00 to 0)),
            10 to codeSection(codeBody(emptyList(), listOf(0xFC, 0x10, 0x00))),
        )
        val compiled = AotCoreCompiler.compile(ChicoryWasmAdapter.convert(modBytes, 0), "wasm2kt.test.tablemax", "Core0")
        AotCoreCompiler.verifyAll(compiled)
        val loader = CompiledModuleLoader(compiled)
        val inst = loader.instantiate()
        val tab = loader.exportedTable<FuncRef>(inst, "tab")
        assertEquals(WasmTable.MAX_TABLE_SIZE, tab.maxSize)
        assertEquals(0, loader.invoke(inst, "sz"))
    }

    @Test
    fun `emits InnerClasses attributes for Imports and multi-value tuple nested classes`(@TempDir tempDir: Path) {
        val modBytes = wasmModule(
            1 to typeSection(funcType(listOf(I32), listOf(I32, I64))),
            2 to importSection(strBytes("env") + strBytes("pair") + listOf(0x00, 0x00)),
            3 to funcSection(0),
            7 to exportSection("run" to (0x00 to 1)),
            10 to codeSection(codeBody(emptyList(), listOf(0x20, 0x00, 0x10, 0x00))),
        )
        val classesDir = tempDir.resolve("classes")
        val resDir = tempDir.resolve("res")
        val compiled = AotCoreCompiler.compileToDirectory(
            ChicoryWasmAdapter.convert(modBytes, 0),
            classesDir,
            resDir,
            "wasm2kt.test.inner",
            "Core0",
        )
        AotCoreCompiler.verifyAll(compiled)

        val instNode = ClassNode().also { ClassReader(compiled.classes.getValue("wasm2kt/test/inner/Core0Instance")).accept(it, 0) }
        val innerNames = instNode.innerClasses.map { it.name }.toSet()
        assertTrue("wasm2kt/test/inner/Core0Instance\$Imports" in innerNames)
        assertTrue("wasm2kt/test/inner/Core0Instance\$Result_IJ" in innerNames)

        val ktSrc = tempDir.resolve("Caller.kt")
        Files.writeString(
            ktSrc,
            """
            package wasm2kt.test.inner.caller
            import wasm2kt.test.inner.Core0Instance
            import wasm2kt.test.inner.Core0FuncGroup_0
            fun callIt(): Long {
                val imp = object : Core0Instance.Imports {
                    override fun imp_0(p0: Int): Core0Instance.Result_IJ =
                        Core0Instance.Result_IJ().apply { v0 = p0 + 1; v1 = p0.toLong() * 10L }
                }
                val inst = Core0Instance(imp)
                val res = Core0FuncGroup_0.func_1(5, inst)
                return res.v0.toLong() + res.v1
            }
            """.trimIndent(),
        )
        val outDir = tempDir.resolve("caller-classes")
        Files.createDirectories(outDir)
        val classpath = listOf(System.getProperty("java.class.path"), classesDir.toString())
            .joinToString(java.io.File.pathSeparator)
        val exit = org.jetbrains.kotlin.cli.jvm.K2JVMCompiler().exec(
            System.err,
            "-jvm-target", "17", "-no-stdlib",
            "-classpath", classpath,
            "-d", outDir.toString(),
            ktSrc.toString(),
        )
        assertEquals(org.jetbrains.kotlin.cli.common.ExitCode.OK, exit)
        val loader = java.net.URLClassLoader(arrayOf(outDir.toUri().toURL(), classesDir.toUri().toURL()), javaClass.classLoader)
        val result = loader.loadClass("wasm2kt.test.inner.caller.CallerKt").getDeclaredMethod("callIt").invoke(null) as Long
        assertEquals(56L, result)
    }

    // ---------------------------------------------------------------- Test helpers

    internal class CompiledModuleLoader(
        val compiled: CompiledAotModule,
        parent: ClassLoader = AotCoreCompiler::class.java.classLoader,
    ) : ClassLoader(parent) {
        override fun findClass(name: String): Class<*> {
            val internal = name.replace('.', '/')
            val bytes = compiled.classes[internal] ?: throw ClassNotFoundException(name)
            return defineClass(name, bytes, 0, bytes.size)
        }

        override fun getResourceAsStream(name: String): InputStream? {
            val resPath = compiled.dataResourcePath
            val resBytes = compiled.dataResourceBytes
            if (resPath != null && resBytes != null && name.removePrefix("/") == resPath) {
                return resBytes.inputStream()
            }
            return super.getResourceAsStream(name)
        }

        fun instantiate(vararg ctorArgs: Any?): Any {
            val cls = loadClass(compiled.instanceBinaryName)
            val ctor = cls.constructors.first { it.parameterCount == ctorArgs.size }
            return ctor.newInstance(*ctorArgs)
        }

        fun invoke(instance: Any, exportName: String, vararg args: Any?): Any? {
            val exp = compiled.exports.filterIsInstance<CompiledExport.Function>().single { it.name == exportName }
            val target = exp.target
            val ownerClass = loadClass(target.ownerInternalName.replace('/', '.'))
            val method = ownerClass.methods.single { it.name == target.methodName && it.parameterCount == args.size + 1 - target.overflowParamCount }
            val directCount = target.funcType.params.size - target.overflowParamCount
            if (target.overflowParamCount > 0) {
                val spillField = instance.javaClass.getField("spill").get(instance) as wasm2kt.runtime.aot.AotSpillFrame
                val i32s = intArrayOf(0)
                val i64s = intArrayOf(0)
                val f32s = intArrayOf(0)
                val f64s = intArrayOf(0)
                val refs = intArrayOf(0)
                for (i in directCount until target.funcType.params.size) {
                    val v = args[i]
                    when (target.funcType.params[i]) {
                        wasm2kt.tooling.ir.IrValType.I32 -> spillField.i32[i32s[0]++] = v as Int
                        wasm2kt.tooling.ir.IrValType.I64 -> spillField.i64[i64s[0]++] = v as Long
                        wasm2kt.tooling.ir.IrValType.F32 -> spillField.f32[f32s[0]++] = v as Float
                        wasm2kt.tooling.ir.IrValType.F64 -> spillField.f64[f64s[0]++] = v as Double
                        wasm2kt.tooling.ir.IrValType.FUNCREF, wasm2kt.tooling.ir.IrValType.EXTERNREF -> spillField.refs[refs[0]++] = v
                    }
                }
            }
            val callArgs = arrayOfNulls<Any?>(directCount + 1)
            for (i in 0 until directCount) callArgs[i] = args[i]
            callArgs[directCount] = instance
            return try {
                method.invoke(null, *callArgs)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        }

        fun exportedMemory(instance: Any, name: String): LinearMemory {
            val exp = compiled.exports.filterIsInstance<CompiledExport.Memory>().single { it.name == name }
            return instance.javaClass.getField(exp.fieldName).get(instance) as LinearMemory
        }

        @Suppress("UNCHECKED_CAST")
        fun <T : Any> exportedTable(instance: Any, name: String): wasm2kt.runtime.aot.WasmTable<T> {
            val exp = compiled.exports.filterIsInstance<CompiledExport.Table>().single { it.name == name }
            return instance.javaClass.getField(exp.fieldName).get(instance) as wasm2kt.runtime.aot.WasmTable<T>
        }

        fun exportedGlobalCell(instance: Any, name: String): Any {
            val exp = compiled.exports.filterIsInstance<CompiledExport.Global>().single { it.name == name }
            return instance.javaClass.getField(exp.fieldName).get(instance)!!
        }
    }

    private fun coreModulesSorted(dir: Path): List<Pair<Int, Path>> {
        val corePattern = Regex("""core(\d+)\.wasm""")
        return Files.list(dir).use { stream ->
            stream.toList().mapNotNull { path ->
                val match = corePattern.matchEntire(path.name) ?: return@mapNotNull null
                match.groupValues[1].toInt() to path
            }.sortedBy { it.first }
        }
    }

    private fun sanitizePkg(name: String): String = name.replace('-', '_')

    private companion object {
        const val I32 = 0x7F
        const val I64 = 0x7E
        const val F32 = 0x7D
        const val F64 = 0x7C
        const val FUNCREF = 0x70
    }

    private fun uleb(v: Int): List<Int> {
        val out = mutableListOf<Int>()
        var x = v
        do {
            var b = x and 0x7F
            x = x ushr 7
            if (x != 0) b = b or 0x80
            out += b
        } while (x != 0)
        return out
    }

    private fun sleb(v: Long): List<Int> {
        val out = mutableListOf<Int>()
        var x = v
        var more = true
        while (more) {
            val b = (x and 0x7F).toInt()
            x = x shr 7
            val sign = (b and 0x40) != 0
            more = !((x == 0L && !sign) || (x == -1L && sign))
            out += if (more) (b or 0x80) else b
        }
        return out
    }

    private fun strBytes(s: String): List<Int> {
        val utf8 = s.toByteArray(Charsets.UTF_8)
        return uleb(utf8.size) + utf8.map { it.toInt() and 0xFF }
    }

    private fun f32LeBytes(rawBits: Int): List<Int> =
        (0 until 4).map { i -> (rawBits ushr (i * 8)) and 0xFF }

    private fun funcType(params: List<Int>, results: List<Int>): List<Int> =
        listOf(0x60) + uleb(params.size) + params + uleb(results.size) + results

    private fun typeSection(vararg types: List<Int>): List<Int> =
        uleb(types.size) + types.flatMap { it }

    private fun globalImport(module: String, name: String, valType: Int, mutable: Boolean): List<Int> =
        strBytes(module) + strBytes(name) + listOf(0x03, valType, if (mutable) 0x01 else 0x00)

    private fun importSection(vararg imports: List<Int>): List<Int> =
        uleb(imports.size) + imports.flatMap { it }

    private fun funcSection(vararg typeIndices: Int): List<Int> =
        uleb(typeIndices.size) + typeIndices.flatMap { uleb(it) }

    private fun funcrefTable(min: Int): List<Int> = listOf(FUNCREF, 0x00) + uleb(min)

    private fun tableSection(vararg tables: List<Int>): List<Int> =
        uleb(tables.size) + tables.flatMap { it }

    private fun memorySection(vararg entries: Pair<Int, Int>): List<Int> {
        val out = mutableListOf<Int>()
        var count = 0
        var i = 0
        while (i < entries.size) {
            val (flag, min) = entries[i]
            if (flag == 0x00) {
                out += listOf(0x00) + uleb(min)
                i += 1
            } else {
                val max = entries[i + 1].second
                out += listOf(flag) + uleb(min) + uleb(max)
                i += 2
            }
            count++
        }
        return uleb(count) + out
    }

    private fun globalDef(valType: Int, mutable: Boolean, initExprWithEnd: List<Int>): List<Int> =
        listOf(valType, if (mutable) 0x01 else 0x00) + initExprWithEnd

    private fun globalSection(vararg globals: List<Int>): List<Int> =
        uleb(globals.size) + globals.flatMap { it }

    private fun exportSection(vararg exports: Pair<String, Pair<Int, Int>>): List<Int> =
        uleb(exports.size) + exports.flatMap { (name, kindIdx) ->
            strBytes(name) + listOf(kindIdx.first) + uleb(kindIdx.second)
        }

    private fun elementSection(vararg segments: List<Int>): List<Int> =
        uleb(segments.size) + segments.flatMap { it }

    private fun activeData(memIndex: Int, offsetExprWithEnd: List<Int>, bytes: ByteArray): List<Int> {
        val header = if (memIndex == 0) {
            listOf(0x00) + offsetExprWithEnd
        } else {
            listOf(0x02) + uleb(memIndex) + offsetExprWithEnd
        }
        return header + uleb(bytes.size) + bytes.map { it.toInt() and 0xFF }
    }

    private fun dataSection(vararg segments: List<Int>): List<Int> =
        uleb(segments.size) + segments.flatMap { it }

    private fun codeBody(locals: List<Pair<Int, Int>>, insns: List<Int>): List<Int> {
        val b = mutableListOf<Int>()
        b += uleb(locals.size)
        for ((count, vt) in locals) {
            b += uleb(count)
            b += vt
        }
        b += insns
        b += 0x0B
        return uleb(b.size) + b
    }

    private fun codeSection(vararg bodies: List<Int>): List<Int> =
        uleb(bodies.size) + bodies.flatMap { it }

    private fun wasmModule(vararg sections: Pair<Int, List<Int>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00))
        for ((id, body) in sections) {
            out.write(id)
            for (b in uleb(body.size)) out.write(b)
            for (b in body) out.write(b)
        }
        return out.toByteArray()
    }
}
