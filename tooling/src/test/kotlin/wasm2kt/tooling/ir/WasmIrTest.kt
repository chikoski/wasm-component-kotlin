package wasm2kt.tooling.ir

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import wasm2kt.tooling.Decomposer
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WasmIrTest {
    private val fixturesDir: Path = Paths.get(System.getProperty("wasm2kt.fixtures"))
    private val rootDir: Path = Paths.get(System.getProperty("wasm2kt.rootDir"))

    @Test
    fun `all fixture components decompose and lower to valid typed IR`(@TempDir tempDir: Path) {
        val componentFiles = fixturesDir.listDirectoryEntries("*.wasm").sortedBy { it.name }
        val names = componentFiles.map { it.name }.toSet()
        assertTrue("heavy.wasm" in names, "Expected heavy.wasm in fixtures")
        assertTrue("roundtrip.wasm" in names, "Expected roundtrip.wasm in fixtures")
        assertTrue("async-pipeline.wasm" in names, "Expected async-pipeline.wasm in fixtures")
        assertTrue("wasi-cli.wasm" in names, "Expected wasi-cli.wasm in fixtures")

        var totalModules = 0
        var totalFunctions = 0
        for (component in componentFiles) {
            val outDir = tempDir.resolve(component.nameWithoutExtension)
            Decomposer.decompose(component, outDir)
            val coreFiles = coreModulesSorted(outDir)
            assertTrue(coreFiles.isNotEmpty(), "${component.name} should emit at least one core module")
            for ((moduleIdx, coreFile) in coreFiles) {
                val ir = ChicoryWasmAdapter.convert(Files.readAllBytes(coreFile), moduleIndex = moduleIdx)
                assertValidIrModule(ir, expectedModuleIndex = moduleIdx, context = "${component.name}#core$moduleIdx")
                val dump1 = WasmIrDump.dump(ir, includeInstructions = false)
                val dump2 = WasmIrDump.dump(ir, includeInstructions = false)
                assertEquals(dump1, dump2, "Dump summary must be deterministic for ${component.name}#core$moduleIdx")
                assertTrue(dump1.startsWith("module core=$moduleIdx"))
                totalModules++
                totalFunctions += ir.definedFunctions.size
            }
        }
        assertTrue(totalModules >= componentFiles.size)
        assertTrue(totalFunctions > 0)
    }

    @Test
    fun `all runtime-chicory component core modules lower cleanly to typed IR`() {
        val componentsDir = rootDir.resolve("runtime-chicory/src/test/resources/components")
        val coreWasms = Files.walk(componentsDir).use { stream ->
            stream.filter { it.name.startsWith("core") && it.name.endsWith(".wasm") }
                .sorted()
                .toList()
        }
        assertTrue(coreWasms.size >= 35, "Expected at least 35 core test modules, found ${coreWasms.size}")

        for (coreFile in coreWasms) {
            val idx = coreFile.name.removePrefix("core").removeSuffix(".wasm").toInt()
            val rel = rootDir.relativize(coreFile).toString()
            val ir = ChicoryWasmAdapter.convert(Files.readAllBytes(coreFile), moduleIndex = idx)
            assertValidIrModule(ir, expectedModuleIndex = idx, context = rel)
        }
    }

    @Test
    fun `supports MVP control numeric polymorphic stacks and NaN payload preservation`() {
        val f32NanBits = 0x7FC01234
        val f64NanBits = 0x7FF000000000ABCDL
        val modBytes = wasmModule(
            1 to typeSection(
                funcType(listOf(I32), listOf(I32)), // 0: branch & loops test
                funcType(emptyList(), listOf(F32, F64)), // 1: NaN & numeric test
                funcType(listOf(I64), listOf(I32)), // 2: comparisons
                funcType(emptyList(), listOf(I32)), // 3: br_table
            ),
            3 to funcSection(0, 1, 2, 3),
            4 to tableSection(funcrefTable(1)),
            5 to memorySection(0x00 to 1, 0x01 to 2, 0x01 to 4), // bounded mem 1 & mem 2
            7 to exportSection("fn0" to (0x00 to 0)),
            10 to codeSection(
                // fn0: (i32) -> (i32) with block, loop, br_if, unreachable nested block, drop, untyped select
                codeBody(
                    listOf(1 to I32),
                    listOf(
                        0x02, 0x7F, // block (result i32)
                        0x03, 0x40, // loop ()
                        0x41, 0x2A, // i32.const 42
                        0x20, 0x00, // local.get 0
                        0x0D, 0x01, // br_if 1 (takes i32 to block)
                        0x1A, // drop 42
                        0x0C, 0x00, // br 0 (back to loop)
                        0x0B, // end loop
                        0x00, // unreachable -> poly
                        0x1A, // drop Unknown
                        0x02, 0x7F, // block (result i32) inside unreachable!
                        0x41, 0x07, // i32.const 7
                        0x0B, // end inner block
                        0x0B, // end outer block -> [i32]
                        0x21, 0x01, // local.set 1
                        0x41, 0x01, 0x41, 0x02, 0x20, 0x00, 0x1B, // select [i32, i32, i32] -> [i32]
                    ),
                ),
                // fn1: () -> (f32, f64) NaN bits preserved + f32/f64 unary & binary
                codeBody(
                    emptyList(),
                    listOf(0x43) + f32LeBytes(f32NanBits) +
                        listOf(0x43) + f32LeBytes(1.0f.toRawBits()) +
                        listOf(0x98) + // f32.copysign
                        listOf(0x1A) + // drop
                        listOf(0x43) + f32LeBytes(f32NanBits) + // exact NaN bits kept
                        listOf(0x44) + f64LeBytes(f64NanBits), // exact f64 NaN bits kept
                ),
                // fn2: (i64) -> (i32)
                codeBody(
                    emptyList(),
                    listOf(0x20, 0x00, 0x50, 0x20, 0x00, 0x20, 0x00, 0x51, 0x6A), // i64.eqz + i64.eq -> i32.add
                ),
                // fn3: () -> (i32) br_table
                codeBody(
                    emptyList(),
                    listOf(
                        0x02, 0x7F, // block (result i32)
                        0x41, 0x63, // i32.const 99
                        0x41, 0x00, // selector 0
                        0x0E, 0x01, 0x00, 0x00, // br_table [0] default 0
                        0x0B,
                    ),
                ),
            ),
        )

        val ir = ChicoryWasmAdapter.convert(modBytes, moduleIndex = 0)
        assertValidIrModule(ir, expectedModuleIndex = 0, context = "mvp")
        assertNull(ir.definedMemories[0].type.maxPages)
        assertEquals(4, ir.definedMemories[1].type.maxPages)

        val fn0 = ir.definedFunctions[0]
        assertEquals(1, fn0.params.size)
        assertEquals(1, fn0.locals.size)
        assertEquals(listOf(IrLocal(0, IrValType.I32, true), IrLocal(1, IrValType.I32, false)), fn0.allLocals)

        val br = fn0.instructions.map { it.op }.filterIsInstance<WasmOp.Br>().single()
        assertEquals(0, br.target.depth)
        assertEquals(IrBlockKind.LOOP, br.target.targetKind)
        assertEquals(emptyList(), br.target.labelTypes)

        val brIf = fn0.instructions.map { it.op }.filterIsInstance<WasmOp.BrIf>().single()
        assertEquals(1, brIf.target.depth)
        assertEquals(IrBlockKind.BLOCK, brIf.target.targetKind)
        assertEquals(listOf(IrValType.I32), brIf.target.labelTypes)

        // Check polymorphic drop inside unreachable region
        val polyDrops = fn0.instructions.filter { it.polymorphicBefore && it.op is WasmOp.Drop }
        assertEquals(1, polyDrops.size)
        assertEquals(IrStackType.Unknown, (polyDrops[0].op as WasmOp.Drop).droppedType)

        // Check select resolution
        val select = fn0.instructions.map { it.op }.filterIsInstance<WasmOp.Select>().single()
        assertEquals(IrStackType.Concrete(IrValType.I32), select.resolvedType)

        // Check raw IEEE-754 NaN bit preservation
        val fn1 = ir.definedFunctions[1]
        val f32Consts = fn1.instructions.map { it.op }.filterIsInstance<WasmOp.F32Const>()
        assertTrue(f32Consts.any { it.rawBits == f32NanBits })
        val f64Const = fn1.instructions.map { it.op }.filterIsInstance<WasmOp.F64Const>().single()
        assertEquals(f64NanBits, f64Const.rawBits)

        // Check fn2 comparisons produce I32
        val fn2 = ir.definedFunctions[2]
        assertTrue(fn2.instructions.any { it.op == WasmOp.Unary(UnaryOp.I64_EQZ) && it.stackAfter.last() == IrStackType.Concrete(IrValType.I32) })
        assertTrue(fn2.instructions.any { it.op == WasmOp.Binary(BinaryOp.I64_EQ) && it.stackAfter.last() == IrStackType.Concrete(IrValType.I32) })
    }

    @Test
    fun `disambiguates blocktype 0x40 void and 0x7f i32 from type indices 64 and 127`() {
        val types = mutableListOf<List<Int>>()
        types += funcType(emptyList(), listOf(I32)) // type 0
        for (i in 1..63) types += funcType(emptyList(), emptyList())
        types += funcType(listOf(I32), listOf(I32, I64)) // type 64! (collides with 0x40 if decoded unsigned)
        for (i in 65..126) types += funcType(emptyList(), emptyList())
        types += funcType(listOf(I32), listOf(I64)) // type 127! (collides with 0x7F if decoded unsigned)

        val body = listOf(
            0x02, 0x40, 0x0B, // block () -> ()
            0x41, 0x09, // push i32 arg for block type 64
        ) + listOf(0x02) + sleb(64) + listOf(
            0x42, 0x0A, // [i32, i64]
            0x0B,
            0x1A, 0x1A, // drop i64, drop i32
            0x41, 0x01, // arg for loop type 64
        ) + listOf(0x03) + sleb(64) + listOf(
            0x42, 0x02, // [i32, i64]
            0x0B,
            0x1A, 0x1A, // drop both
            0x41, 0x0B, // param i32 for if type 127
            0x41, 0x01, // cond i32
            0x04,
        ) + sleb(127) + listOf(
            0x1A, 0x42, 0x01, // then: i32 -> i64
            0x05,
            0x1A, 0x42, 0x02, // else: i32 -> i64
            0x0B,
            0x1A, // drop i64
            0x02, 0x7F, 0x41, 0x00, 0x0B, 0x1A, // block (result i32)
        )

        val modBytes = wasmModule(
            1 to typeSection(*types.toTypedArray()),
            3 to funcSection(1), // func 0 uses type 1 ()->()
            10 to codeSection(codeBody(emptyList(), body)),
        )

        val ir = ChicoryWasmAdapter.convert(modBytes, moduleIndex = 0)
        assertValidIrModule(ir, expectedModuleIndex = 0, context = "blocktype-collision")

        val fn = ir.definedFunctions[0]
        val blocks = fn.blocks.filter { it.kind != IrBlockKind.FUNCTION }
        assertEquals(IrBlockType.Empty, blocks[0].blockType)
        assertEquals(IrBlockType.TypeRef(64), blocks[1].blockType)
        assertEquals(listOf(IrValType.I32), blocks[1].params)
        assertEquals(listOf(IrValType.I32, IrValType.I64), blocks[1].results)
        assertEquals(IrBlockType.TypeRef(64), blocks[2].blockType)
        assertEquals(IrBlockKind.LOOP, blocks[2].kind)
        assertEquals(listOf(IrValType.I32), blocks[2].branchLabelTypes)
        assertEquals(IrBlockType.TypeRef(127), blocks[3].blockType)
        assertEquals(IrBlockKind.IF, blocks[3].kind)
        assertEquals(listOf(IrValType.I32), blocks[3].params)
        assertEquals(listOf(IrValType.I64), blocks[3].results)
        assertEquals(IrBlockType.Value(IrValType.I32), blocks[4].blockType)
    }

    @Test
    fun `supports sign-extension and nontrapping-float-to-int instructions`() {
        val body = listOf(
            0x41, 0x7F, 0xC0, 0xC1, 0x1A, // i32.extend8_s, i32.extend16_s, drop
            0x42, 0x7F, 0xC2, 0xC3, 0xC4, 0x1A, // i64.extend8_s, 16_s, 32_s, drop
            0x43, 0, 0, 0, 0, 0xFC, 0x00, 0x1A, // i32.trunc_sat_f32_s
            0x43, 0, 0, 0, 0, 0xFC, 0x01, 0x1A, // i32.trunc_sat_f32_u
            0x44, 0, 0, 0, 0, 0, 0, 0, 0, 0xFC, 0x02, 0x1A, // i32.trunc_sat_f64_s
            0x44, 0, 0, 0, 0, 0, 0, 0, 0, 0xFC, 0x03, 0x1A, // i32.trunc_sat_f64_u
            0x43, 0, 0, 0, 0, 0xFC, 0x04, 0x1A, // i64.trunc_sat_f32_s
            0x43, 0, 0, 0, 0, 0xFC, 0x05, 0x1A, // i64.trunc_sat_f32_u
            0x44, 0, 0, 0, 0, 0, 0, 0, 0, 0xFC, 0x06, 0x1A, // i64.trunc_sat_f64_s
            0x44, 0, 0, 0, 0, 0, 0, 0, 0, 0xFC, 0x07, 0x1A, // i64.trunc_sat_f64_u
        )
        val modBytes = wasmModule(
            1 to typeSection(funcType(emptyList(), emptyList())),
            3 to funcSection(0),
            10 to codeSection(codeBody(emptyList(), body)),
        )
        val ir = ChicoryWasmAdapter.convert(modBytes, moduleIndex = 0)
        assertValidIrModule(ir, expectedModuleIndex = 0, context = "sign-ext+trunc-sat")
        val unaries = ir.definedFunctions[0].instructions.map { it.op }.filterIsInstance<WasmOp.Unary>().map { it.op }
        assertEquals(
            listOf(
                UnaryOp.I32_EXTEND_8_S,
                UnaryOp.I32_EXTEND_16_S,
                UnaryOp.I64_EXTEND_8_S,
                UnaryOp.I64_EXTEND_16_S,
                UnaryOp.I64_EXTEND_32_S,
                UnaryOp.I32_TRUNC_SAT_F32_S,
                UnaryOp.I32_TRUNC_SAT_F32_U,
                UnaryOp.I32_TRUNC_SAT_F64_S,
                UnaryOp.I32_TRUNC_SAT_F64_U,
                UnaryOp.I64_TRUNC_SAT_F32_S,
                UnaryOp.I64_TRUNC_SAT_F32_U,
                UnaryOp.I64_TRUNC_SAT_F64_S,
                UnaryOp.I64_TRUNC_SAT_F64_U,
            ),
            unaries,
        )
    }

    @Test
    fun `supports bulk-memory reference-types multi-memory extended-const and mutable-globals`() {
        val modBytes = wasmModule(
            1 to typeSection(funcType(emptyList(), emptyList()), funcType(listOf(EXTERNREF), listOf(FUNCREF))),
            2 to importSection(
                globalImport("env", "base", I32, mutable = false),
                globalImport("env", "mut_i64", I64, mutable = true),
            ),
            3 to funcSection(0, 1),
            4 to tableSection(funcrefTable(2), externrefTable(2)),
            5 to memorySection(0x00 to 1, 0x00 to 1),
            6 to globalSection(
                // global 2: const i32 = (global.get 0 + 8) * 2 (extended-const + imported global)
                globalDef(I32, mutable = false, listOf(0x23, 0x00, 0x41, 0x08, 0x6A, 0x41, 0x02, 0x6C, 0x0B)),
                // global 3: mut i64 = (100 - 30) (extended-const)
                globalDef(I64, mutable = true, listOf(0x42) + sleb(100) + listOf(0x42, 0x1E, 0x7D, 0x0B)),
                // global 4: const funcref = ref.func 0
                globalDef(FUNCREF, mutable = false, listOf(0xD2, 0x00, 0x0B)),
            ),
            7 to exportSection(
                "mut_counter" to (0x03 to 3),
                "mem1" to (0x02 to 1),
            ),
            9 to elementSection(
                // elem 0: passive funcref [ref.func 0, ref.func 1]
                listOf(0x05, 0x70, 0x02) + listOf(0xD2, 0x00, 0x0B) + listOf(0xD2, 0x01, 0x0B),
                // elem 1: declarative funcref [0]
                listOf(0x03, 0x00, 0x01, 0x00),
                // elem 2: active table 0 offset (global.get 0 + 1) [0]
                listOf(0x00, 0x23, 0x00, 0x41, 0x01, 0x6A, 0x0B, 0x01, 0x00),
            ),
            12 to dataCountSection(2),
            10 to codeSection(
                // fn 0: bulk-memory + multi-memory + reference-types + mutable globals
                codeBody(
                    emptyList(),
                    listOf(
                        // multi-memory ops on mem 1
                        0x41, 0x00, 0x41, 0x2A, 0x36, 0x42, 0x01, 0x04, // i32.store mem=1 align=2 offset=4
                        0x41, 0x00, 0x28, 0x42, 0x01, 0x04, 0x1A, // i32.load mem=1 align=2 offset=4; drop
                        0x3F, 0x01, 0x1A, // memory.size 1; drop
                        0x41, 0x01, 0x40, 0x01, 0x1A, // memory.grow 1; drop
                        0x41, 0x00, 0x41, 0x01, 0x41, 0x02, 0xFC, 0x0B, 0x01, // memory.fill mem=1
                        0x41, 0x00, 0x41, 0x00, 0x41, 0x01, 0xFC, 0x0A, 0x01, 0x00, // memory.copy dst=1, src=0
                        0x41, 0x00, 0x41, 0x00, 0x41, 0x01, 0xFC, 0x08, 0x00, 0x01, // memory.init data=0, mem=1
                        0xFC, 0x09, 0x00, // data.drop 0
                        // table + reference-types
                        0x41, 0x00, 0x41, 0x00, 0x41, 0x01, 0xFC, 0x0C, 0x00, 0x00, // table.init elem=0, table=0
                        0xFC, 0x0D, 0x00, // elem.drop 0
                        0x41, 0x00, 0x41, 0x00, 0x41, 0x00, 0xFC, 0x0E, 0x00, 0x00, // table.copy 0 0
                        0x41, 0x00, 0xD2, 0x00, 0x26, 0x00, // table.set 0 (ref.func 0)
                        0x41, 0x00, 0x25, 0x00, 0xD1, 0x1A, // table.get 0; ref.is_null; drop
                        0xFC, 0x10, 0x01, 0x1A, // table.size 1; drop
                        0xD0, 0x6F, 0x41, 0x01, 0xFC, 0x0F, 0x01, 0x1A, // table.grow 1 (externref); drop
                        0x41, 0x00, 0xD0, 0x6F, 0x41, 0x01, 0xFC, 0x11, 0x01, // table.fill 1
                        0x41, 0x00, 0x11, 0x00, 0x00, // call_indirect type=0 table=0
                        0xD0, 0x70, 0xD0, 0x70, 0x41, 0x01, 0x1C, 0x01, 0x70, 0x1A, // typed select [funcref]
                        0x23, 0x01, 0x42, 0x01, 0x7C, 0x24, 0x03, // global.get 1; i64.const 1; add; global.set 3
                    ),
                ),
                // fn 1: (externref) -> (funcref)
                codeBody(
                    emptyList(),
                    listOf(0x20, 0x00, 0xD1, 0x1A, 0x23, 0x04), // local.get 0; ref.is_null; drop; global.get 4
                ),
            ),
            11 to dataSection(
                passiveData(byteArrayOf(1, 2, 3)),
                activeData(1, listOf(0x23, 0x00, 0x41, 0x04, 0x6B, 0x0B), byteArrayOf(9, 8)), // mem 1, offset = base - 4
            ),
        )

        val ir = ChicoryWasmAdapter.convert(modBytes, moduleIndex = 2)
        assertValidIrModule(ir, expectedModuleIndex = 2, context = "extended-specs")

        assertEquals(2, ir.importedGlobals.size)
        assertEquals(IrMutability.VAR, ir.importedGlobals[1].globalType.mutability)
        assertEquals(3, ir.definedGlobals.size)
        assertEquals(
            listOf(
                IrConstOp.GlobalGet(0, IrValType.I32),
                IrConstOp.I32Const(8),
                IrConstOp.I32Add,
                IrConstOp.I32Const(2),
                IrConstOp.I32Mul,
            ),
            ir.definedGlobals[0].initExpr.ops,
        )
        assertEquals(listOf(IrConstOp.RefFunc(0)), ir.definedGlobals[2].initExpr.ops)

        assertEquals(2, ir.dataCount)
        assertTrue(ir.elements[0] is IrElementSegment.Passive)
        assertTrue(ir.elements[1] is IrElementSegment.Declarative)
        val activeElem = ir.elements[2] as IrElementSegment.Active
        assertEquals(listOf(IrConstOp.GlobalGet(0, IrValType.I32), IrConstOp.I32Const(1), IrConstOp.I32Add), activeElem.offset.ops)

        assertTrue(ir.dataSegments[0] is IrDataSegment.Passive)
        val activeData = ir.dataSegments[1] as IrDataSegment.Active
        assertEquals(1, activeData.memoryIndex)
        assertEquals(listOf(IrConstOp.GlobalGet(0, IrValType.I32), IrConstOp.I32Const(4), IrConstOp.I32Sub), activeData.offset.ops)

        val fn0Ops = ir.definedFunctions[0].instructions.map { it.op }
        assertTrue(fn0Ops.any { it is WasmOp.Load && it.memoryIndex == 1 && it.offset == 4L })
        assertTrue(fn0Ops.any { it is WasmOp.Store && it.memoryIndex == 1 && it.offset == 4L })
        assertTrue(fn0Ops.any { it == WasmOp.MemorySize(1) })
        assertTrue(fn0Ops.any { it == WasmOp.MemoryGrow(1) })
        assertTrue(fn0Ops.any { it == WasmOp.MemoryFill(1) })
        assertTrue(fn0Ops.any { it == WasmOp.MemoryCopy(1, 0) })
        assertTrue(fn0Ops.any { it == WasmOp.MemoryInit(0, 1) })
        assertTrue(fn0Ops.any { it == WasmOp.DataDrop(0) })
        assertTrue(fn0Ops.any { it == WasmOp.TableInit(0, 0, IrValType.FUNCREF) })
        assertTrue(fn0Ops.any { it == WasmOp.ElemDrop(0) })
        assertTrue(fn0Ops.any { it == WasmOp.TableCopy(0, 0) })
        assertTrue(fn0Ops.any { it == WasmOp.TableGrow(1, IrValType.EXTERNREF) })
        assertTrue(fn0Ops.any { it is WasmOp.Select && it.explicitTypes == listOf(IrValType.FUNCREF) })
        assertTrue(fn0Ops.any { it == WasmOp.GlobalSet(3, IrValType.I64) })
    }

    @Test
    fun `rejects mutating immutable global`() {
        val modBytes = wasmModule(
            1 to typeSection(funcType(emptyList(), emptyList())),
            3 to funcSection(0),
            6 to globalSection(globalDef(I32, mutable = false, listOf(0x41, 0x00, 0x0B))),
            10 to codeSection(codeBody(emptyList(), listOf(0x41, 0x01, 0x24, 0x00))),
        )
        assertThrows<IllegalArgumentException> {
            ChicoryWasmAdapter.convert(modBytes, moduleIndex = 1)
        }
    }

    @Test
    fun `reports structured UnsupportedWasmFeatureException for unsupported proposals`() {
        // 1. SIMD128 instruction inside imported=1 + body=0 => functionIndex = 1, moduleIndex = 3
        val simdMod = wasmModule(
            1 to typeSection(funcType(emptyList(), emptyList())),
            2 to importSection(funcImport("env", "imp", 0)),
            3 to funcSection(0),
            10 to codeSection(
                codeBody(emptyList(), listOf(0xFD, 0x0C) + List(16) { 0 } + listOf(0x1A)),
            ),
        )
        val simdErr = assertThrows<UnsupportedWasmFeatureException> { ChicoryWasmAdapter.convert(simdMod, moduleIndex = 3) }
        assertEquals("simd128", simdErr.feature)
        assertEquals(3, simdErr.moduleIndex)
        assertEquals(1, simdErr.functionIndex)
        assertEquals("V128_CONST", simdErr.instructionName)
        assertTrue("core module 3" in simdErr.message!!)
        assertTrue("function 1" in simdErr.message!!)

        // 2. SIMD128 v128 param type
        val simdTypeMod = wasmModule(
            1 to typeSection(funcType(listOf(0x7B), emptyList())),
        )
        val simdTypeErr = assertThrows<UnsupportedWasmFeatureException> { ChicoryWasmAdapter.convert(simdTypeMod, moduleIndex = 4) }
        assertEquals("simd128", simdTypeErr.feature)
        assertEquals(4, simdTypeErr.moduleIndex)

        // 3. Exception handling (tag section)
        val ehMod = wasmModule(
            1 to typeSection(funcType(emptyList(), emptyList())),
            3 to funcSection(0),
            13 to listOf(1, 0x00, 0x00), // tag section with 1 tag
            10 to codeSection(codeBody(emptyList(), listOf(0x08, 0x00))),
        )
        val ehErr = assertThrows<UnsupportedWasmFeatureException> { ChicoryWasmAdapter.convert(ehMod, moduleIndex = 5) }
        assertEquals("exception-handling", ehErr.feature)
        assertEquals(5, ehErr.moduleIndex)

        // 4. Tail-call (return_call)
        val tailCallMod = wasmModule(
            1 to typeSection(funcType(emptyList(), emptyList())),
            3 to funcSection(0),
            10 to codeSection(codeBody(emptyList(), listOf(0x12, 0x00))),
        )
        val tcErr = assertThrows<UnsupportedWasmFeatureException> { ChicoryWasmAdapter.convert(tailCallMod, moduleIndex = 2) }
        assertEquals("tail-call", tcErr.feature)
        assertEquals(2, tcErr.moduleIndex)
        assertEquals(0, tcErr.functionIndex)
        assertEquals("RETURN_CALL", tcErr.instructionName)

        // 5. Wasm GC composite struct type (0x5F)
        val gcTypeMod = wasmModule(
            1 to listOf(1, 0x5F, 0),
        )
        val gcTypeErr = assertThrows<UnsupportedWasmFeatureException> { ChicoryWasmAdapter.convert(gcTypeMod, moduleIndex = 6) }
        assertEquals("wasm-gc", gcTypeErr.feature)
        assertEquals(6, gcTypeErr.moduleIndex)

        // 6. Wasm GC instruction (ref.eq = 0xD3)
        val gcOpMod = wasmModule(
            1 to typeSection(funcType(emptyList(), emptyList())),
            3 to funcSection(0),
            10 to codeSection(codeBody(emptyList(), listOf(0xD0, 0x70, 0xD0, 0x70, 0xD3, 0x1A))),
        )
        val gcOpErr = assertThrows<UnsupportedWasmFeatureException> { ChicoryWasmAdapter.convert(gcOpMod, moduleIndex = 7) }
        assertEquals("wasm-gc", gcOpErr.feature)
        assertEquals(7, gcOpErr.moduleIndex)
        assertEquals(0, gcOpErr.functionIndex)
        assertEquals("REF_EQ", gcOpErr.instructionName)

        // 7. Threads (atomic.fence = 0xFE 0x03 0x00)
        val atomicMod = wasmModule(
            1 to typeSection(funcType(emptyList(), emptyList())),
            3 to funcSection(0),
            10 to codeSection(codeBody(emptyList(), listOf(0xFE, 0x03, 0x00))),
        )
        val atomicErr = assertThrows<UnsupportedWasmFeatureException> { ChicoryWasmAdapter.convert(atomicMod, moduleIndex = 8) }
        assertEquals("threads", atomicErr.feature)
        assertEquals(8, atomicErr.moduleIndex)
        assertEquals(0, atomicErr.functionIndex)
        assertEquals("ATOMIC_FENCE", atomicErr.instructionName)

        // 8. Threads shared memory limit (0x03)
        val sharedRaw = wasmModule(
            5 to listOf(1, 0x03, 0x01, 0x02),
        )
        val sharedErr = assertThrows<UnsupportedWasmFeatureException> { ChicoryWasmAdapter.convert(sharedRaw, moduleIndex = 9) }
        assertEquals("threads", sharedErr.feature)
        assertEquals(9, sharedErr.moduleIndex)

        // 9. Memory64 (memory limit flags=0x04)
        val mem64Mod = wasmModule(
            5 to listOf(1, 0x04, 0x01),
        )
        val mem64Err = assertThrows<UnsupportedWasmFeatureException> { ChicoryWasmAdapter.convert(mem64Mod, moduleIndex = 10) }
        assertEquals("memory64", mem64Err.feature)
        assertEquals(10, mem64Err.moduleIndex)

        // 10. Legacy exception-handling try/catch_all inside imported=1 + body=0 => functionIndex = 1
        val legacyTryMod = wasmModule(
            1 to typeSection(funcType(emptyList(), emptyList())),
            2 to importSection(funcImport("env", "imp", 0)),
            3 to funcSection(0),
            10 to codeSection(
                codeBody(emptyList(), listOf(0x06, 0x40, 0x19, 0x0B)), // try ... catch_all ... end
            ),
        )
        val tryErr = assertThrows<UnsupportedWasmFeatureException> { ChicoryWasmAdapter.convert(legacyTryMod, moduleIndex = 11) }
        assertEquals("exception-handling", tryErr.feature)
        assertEquals(11, tryErr.moduleIndex)
        assertEquals(1, tryErr.functionIndex)
        assertEquals("TRY", tryErr.instructionName)

        // 11. exnref value type (0x69)
        val exnRefMod = wasmModule(
            1 to typeSection(funcType(listOf(0x69), emptyList())),
        )
        val exnRefErr = assertThrows<UnsupportedWasmFeatureException> { ChicoryWasmAdapter.convert(exnRefMod, moduleIndex = 12) }
        assertEquals("exception-handling", exnRefErr.feature)
        assertEquals(12, exnRefErr.moduleIndex)

        // 12. Imported Tag (import kind 0x04)
        val tagImportMod = wasmModule(
            1 to typeSection(funcType(emptyList(), emptyList())),
            2 to importSection(strBytes("env") + strBytes("exn") + listOf(0x04, 0x00, 0x00)),
        )
        val tagImportErr = assertThrows<UnsupportedWasmFeatureException> { ChicoryWasmAdapter.convert(tagImportMod, moduleIndex = 13) }
        assertEquals("exception-handling", tagImportErr.feature)
        assertEquals(13, tagImportErr.moduleIndex)

        // 13. Shared table (limits flag 0x03)
        val sharedTableMod = wasmModule(
            4 to tableSection(listOf(FUNCREF, 0x03, 0x01, 0x02)),
        )
        val sharedTableErr = assertThrows<UnsupportedWasmFeatureException> { ChicoryWasmAdapter.convert(sharedTableMod, moduleIndex = 14) }
        assertEquals("threads", sharedTableErr.feature)
        assertEquals(14, sharedTableErr.moduleIndex)

        // 14. 64-bit table limits (flags 0x04)
        val table64Mod = wasmModule(
            4 to tableSection(listOf(FUNCREF, 0x04, 0x01)),
        )
        val table64Err = assertThrows<UnsupportedWasmFeatureException> { ChicoryWasmAdapter.convert(table64Mod, moduleIndex = 15) }
        assertEquals("memory64", table64Err.feature)
        assertEquals(15, table64Err.moduleIndex)

        // 15. GC call_ref (0x14 0x00)
        val callRefMod = wasmModule(
            1 to typeSection(funcType(emptyList(), emptyList())),
            3 to funcSection(0),
            10 to codeSection(codeBody(emptyList(), listOf(0xD0, 0x70, 0x14, 0x00))),
        )
        val callRefErr = assertThrows<UnsupportedWasmFeatureException> { ChicoryWasmAdapter.convert(callRefMod, moduleIndex = 16) }
        assertEquals("wasm-gc", callRefErr.feature)
        assertEquals(16, callRefErr.moduleIndex)
        assertEquals(0, callRefErr.functionIndex)
        assertEquals("CALL_REF", callRefErr.instructionName)

        // 16. Malformed limits flag 0x08 must throw IllegalArgumentException (not UnsupportedWasmFeatureException)
        val badFlagsMod = wasmModule(
            5 to listOf(1, 0x08, 0x01),
        )
        assertThrows<IllegalArgumentException> { ChicoryWasmAdapter.convert(badFlagsMod, moduleIndex = 17) }
    }

    @Test
    fun `handles unreachable br_table multi-value loop br 0x63_0x70 nullable funcref and exact limits`() {
        val modBytes = wasmModule(
            1 to typeSection(
                funcType(emptyList(), listOf(I32)), // 0: () -> i32 (duplicate signature for structural equality check)
                funcType(emptyList(), listOf(I32)), // 1: () -> i32
                listOf(0x60, 2, I32, I64, 2, I32, I64), // 2: (i32, i64) -> (i32, i64) for loop params
                listOf(0x60, 1, 0x63, 0x70, 1, 0x63, 0x70), // 3: (ref null func) -> (ref null func) via 0x63 0x70
            ),
            3 to funcSection(0, 2, 3),
            4 to tableSection(
                listOf(FUNCREF, 0x01) + uleb(10_000_005) + uleb(20_000_000), // > 10M table limits preserved!
            ),
            5 to memorySection(
                0x00 to 1, // unbounded -> null
                0x01 to 1, 0x01 to 65536, // explicit max=65536 -> preserved as 65536!
            ),
            10 to codeSection(
                // fn 0: unreachable; br_table across differing target label types [i32] vs [f32]
                codeBody(
                    emptyList(),
                    listOf(
                        0x02, F32, // outer block result f32 (depth 1)
                        0x02, I32, // inner block result i32 (depth 0)
                        0x00, // unreachable
                        0x41, 0x00, // selector
                        0x0E, 0x01, 0x00, 0x01, // br_table [depth=0 (i32)] default depth=1 (f32)
                        0x0B, // end inner [i32] -> outer stack [i32], poly
                        0x1A, // drop i32
                        0x43, 0, 0, 0, 0, // f32.const 0.0
                        0x0B, // end outer [f32]
                        0x1A, // drop f32
                        0x41, 0x07, // return i32
                    ),
                ),
                // fn 1: multi-value br to loop with params (type 2: (i32, i64) -> (i32, i64))
                codeBody(
                    emptyList(),
                    listOf(
                        0x20, 0x00, // local.get 0 (i32)
                        0x20, 0x01, // local.get 1 (i64)
                        0x03) + sleb(2) + listOf(
                        0x1A, // drop i64
                        0x04, 0x40, // if local.get 0 nonzero -> loop again with (i32, i64)
                        0x41, 0x01,
                        0x42, 0x02,
                        0x0C, 0x01, // br depth=1 (loop #1 expects [i32, i64]!)
                        0x0B,
                        0x41, 0x05,
                        0x42, 0x06,
                        0x0B,
                    ),
                ),
                // fn 2: 0x63 0x70 nullable funcref param + select_t [0x63 0x70]
                codeBody(
                    emptyList(),
                    listOf(
                        0x20, 0x00,
                        0xD0, 0x70,
                        0x41, 0x01,
                        0x1C, 0x01, 0x63, 0x70,
                    ),
                ),
            ),
        )

        val ir = ChicoryWasmAdapter.convert(modBytes, moduleIndex = 1)
        assertValidIrModule(ir, expectedModuleIndex = 1, context = "review-edge-cases")
        assertEquals(ir.types[0], ir.types[1])
        assertEquals("(R)R", ir.types[3].compactSignature)

        assertNull(ir.definedMemories[0].type.maxPages)
        assertEquals(65536, ir.definedMemories[1].type.maxPages)
        assertEquals(IrLimits(10_000_005L, 20_000_000L), ir.definedTables[0].type.limits)

        val brTable = ir.definedFunctions[0].instructions.map { it.op }.filterIsInstance<WasmOp.BrTable>().single()
        assertEquals(listOf(IrValType.I32), brTable.targets[0].labelTypes)
        assertEquals(listOf(IrValType.F32), brTable.defaultTarget.labelTypes)

        val loopBr = ir.definedFunctions[1].instructions.map { it.op }.filterIsInstance<WasmOp.Br>().single()
        assertEquals(IrBlockKind.LOOP, loopBr.target.targetKind)
        assertEquals(listOf(IrValType.I32, IrValType.I64), loopBr.target.labelTypes)

        val typedSelect = ir.definedFunctions[2].instructions.map { it.op }.filterIsInstance<WasmOp.Select>().single()
        assertEquals(listOf(IrValType.FUNCREF), typedSelect.explicitTypes)
    }

    @Test
    fun `rejects invalid stack control flow and module validation errors`() {
        val validHeaderTypes = typeSection(funcType(emptyList(), emptyList()), funcType(listOf(I32), emptyList()))
        val cases = listOf(
            "stack underflow" to wasmModule(
                1 to validHeaderTypes,
                3 to funcSection(0),
                10 to codeSection(codeBody(emptyList(), listOf(0x1A))), // drop on empty stack
            ),
            "type mismatch" to wasmModule(
                1 to validHeaderTypes,
                3 to funcSection(0),
                10 to codeSection(codeBody(emptyList(), listOf(0x42, 0x01, 0x41, 0x01, 0x6A, 0x1A))), // i64 + i32 with i32.add
            ),
            "branch depth out of range" to wasmModule(
                1 to validHeaderTypes,
                3 to funcSection(0),
                10 to codeSection(codeBody(emptyList(), listOf(0x0C, 0x05))),
            ),
            "stray else" to wasmModule(
                1 to validHeaderTypes,
                3 to funcSection(0),
                10 to codeSection(codeBody(emptyList(), listOf(0x05))),
            ),
            "stack height mismatch at end" to wasmModule(
                1 to validHeaderTypes,
                3 to funcSection(0),
                10 to codeSection(codeBody(emptyList(), listOf(0x02, 0x40, 0x41, 0x01, 0x0B))),
            ),
            "duplicate export name" to wasmModule(
                1 to validHeaderTypes,
                3 to funcSection(0),
                7 to exportSection("dup" to (0x00 to 0), "dup" to (0x00 to 0)),
                10 to codeSection(codeBody(emptyList(), emptyList())),
            ),
            "out-of-bounds export" to wasmModule(
                1 to validHeaderTypes,
                3 to funcSection(0),
                7 to exportSection("bad" to (0x00 to 5)),
                10 to codeSection(codeBody(emptyList(), emptyList())),
            ),
            "non-empty start signature" to wasmModule(
                1 to validHeaderTypes,
                3 to funcSection(1),
                8 to listOf(0x00),
                10 to codeSection(codeBody(emptyList(), emptyList())),
            ),
            "active elem table mismatch" to wasmModule(
                1 to validHeaderTypes,
                3 to funcSection(0),
                4 to tableSection(externrefTable(1)),
                9 to elementSection(listOf(0x00, 0x41, 0x00, 0x0B, 0x01, 0x00)), // elem 0 funcref into externref table
                10 to codeSection(codeBody(emptyList(), emptyList())),
            ),
            "active data memory out of bounds" to wasmModule(
                1 to validHeaderTypes,
                3 to funcSection(0),
                5 to memorySection(0x00 to 1),
                10 to codeSection(codeBody(emptyList(), emptyList())),
                11 to dataSection(activeData(2, listOf(0x41, 0x00, 0x0B), byteArrayOf(1))),
            ),
            "memory.init without datacount" to wasmModule(
                1 to validHeaderTypes,
                3 to funcSection(0),
                5 to memorySection(0x00 to 1),
                10 to codeSection(codeBody(emptyList(), listOf(0x41, 0, 0x41, 0, 0x41, 0, 0xFC, 0x08, 0x00, 0x00))),
                11 to dataSection(passiveData(byteArrayOf(1))),
            ),
            "undeclared ref.func" to wasmModule(
                1 to validHeaderTypes,
                3 to funcSection(0),
                10 to codeSection(codeBody(emptyList(), listOf(0xD2, 0x00, 0x1A))),
            ),
            "start section does not declare ref.func" to wasmModule(
                1 to validHeaderTypes,
                3 to funcSection(0),
                8 to listOf(0x00),
                10 to codeSection(codeBody(emptyList(), listOf(0xD2, 0x00, 0x1A))),
            ),
        )
        for ((name, bytes) in cases) {
            assertThrows<IllegalArgumentException>("Expected IllegalArgumentException for $name") {
                ChicoryWasmAdapter.convert(bytes, moduleIndex = 0)
            }
        }
    }

    @Test
    fun `structured golden dump captures escaped names offsets branch targets and NaN bits`() {
        val exportName = "a\"b\nc"
        val modBytes = wasmModule(
            1 to typeSection(funcType(listOf(I32), listOf(F32))),
            3 to funcSection(0),
            7 to exportSection(exportName to (0x00 to 0)),
            10 to codeSection(
                codeBody(
                    emptyList(),
                    listOf(
                        0x03, 0x40, // loop
                        0x20, 0x00, // local.get 0
                        0x0D, 0x00, // br_if 0
                        0x0B, // end loop
                        0x43,
                    ) + f32LeBytes(0x7FC01234),
                ),
            ),
        )

        val ir = ChicoryWasmAdapter.convert(modBytes, moduleIndex = 2)
        val dump = WasmIrDump.dump(ir, includeInstructions = true)
        assertEquals(dump, WasmIrDump.dump(ir, includeInstructions = true))
        val expectedGolden = buildString {
            appendLine("module core=2")
            appendLine("  type[0] (i32)->(f32)")
            appendLine("  export \"a\\\"b\\nc\" func=0")
            appendLine("  func[0] \"a\\\"b\\nc\" type=0 (i32)->(f32)")
            appendLine("    @0x0024     loop #1 ()->()  ; [] -> []")
            appendLine("    @0x0026       local.get 0:i32  ; [] -> [i32]")
            appendLine("    @0x0028       br_if depth=0->#1(loop)  ; [i32] -> []")
            appendLine("    @0x002a     end #1 (loop)  ; [] -> []")
            appendLine("    @0x002b     f32.const bits=0x7fc01234 (NaN)  ; [] -> [f32]")
            appendLine("    @0x0030     end #0 (func)  ; [f32] -> [f32]")
        }
        assertEquals(expectedGolden, dump)
    }

    @Test
    fun `chicory parser dependency is isolated to ChicoryWasmAdapter`() {
        val irSourceDir = rootDir.resolve("tooling/src/main/kotlin/wasm2kt/tooling/ir")
        val ktFiles = irSourceDir.listDirectoryEntries("*.kt").sortedBy { it.name }
        assertTrue(ktFiles.isNotEmpty())
        for (file in ktFiles) {
            val content = Files.readString(file)
            if (file.name == "ChicoryWasmAdapter.kt") {
                assertTrue("com.dylibso.chicory" in content)
            } else {
                assertFalse(
                    "com.dylibso.chicory" in content,
                    "${file.name} must remain independent of com.dylibso.chicory",
                )
            }
        }
    }

    private fun coreModulesSorted(dir: Path): List<Pair<Int, Path>> =
        dir.listDirectoryEntries("core*.wasm")
            .map { p ->
                val idx = p.name.removePrefix("core").removeSuffix(".wasm").toInt()
                idx to p
            }
            .sortedBy { it.first }

    private fun assertValidIrModule(ir: IrModule, expectedModuleIndex: Int, context: String) {
        assertEquals(expectedModuleIndex, ir.moduleIndex, "$context: moduleIndex")
        for (fn in ir.definedFunctions) {
            assertEquals(fn.params.size + fn.locals.size, fn.allLocals.size, "$context func ${fn.index}: locals")
            assertTrue(fn.instructions.isNotEmpty(), "$context func ${fn.index}: empty instructions")
            val blocksById = fn.blocks.associateBy { it.id }
            val fnBlock = blocksById[0]
            assertNotNull(fnBlock, "$context func ${fn.index}: missing function block #0")
            assertEquals(IrBlockKind.FUNCTION, fnBlock.kind)
            assertEquals(fn.instructions.lastIndex, fnBlock.endInsnIndex, "$context func ${fn.index}: function end index")

            for (block in fn.blocks) {
                assertTrue(block.startInsnIndex < block.endInsnIndex, "$context func ${fn.index}: block #${block.id} bounds")
                block.elseInsnIndex?.let { elseIdx ->
                    assertTrue(elseIdx in (block.startInsnIndex + 1) until block.endInsnIndex)
                    assertEquals(WasmOp.Else(block.id), fn.instructions[elseIdx].op)
                }
                val endOp = fn.instructions[block.endInsnIndex].op
                assertEquals(WasmOp.End(block.id, block.kind), endOp)
            }

            val finalInsn = fn.instructions.last()
            assertFalse(finalInsn.polymorphicAfter, "$context func ${fn.index}: final end polymorphicAfter")
            assertEquals(
                fn.type.results.map { IrStackType.Concrete(it) },
                finalInsn.stackAfter,
                "$context func ${fn.index}: final stack mismatch",
            )

            for (insn in fn.instructions) {
                when (val op = insn.op) {
                    is WasmOp.Br -> assertBranchTargetMatches(blocksById, op.target, context, fn.index)
                    is WasmOp.BrIf -> assertBranchTargetMatches(blocksById, op.target, context, fn.index)
                    is WasmOp.BrTable -> {
                        for (t in op.targets) assertBranchTargetMatches(blocksById, t, context, fn.index)
                        assertBranchTargetMatches(blocksById, op.defaultTarget, context, fn.index)
                    }
                    else -> {}
                }
            }
        }
    }

    private fun assertBranchTargetMatches(
        blocksById: Map<Int, IrBlockInfo>,
        target: IrBranchTarget,
        context: String,
        funcIndex: Int,
    ) {
        val block = blocksById[target.targetBlockId]
        assertNotNull(block, "$context func $funcIndex: missing block #${target.targetBlockId}")
        assertEquals(block.kind, target.targetKind)
        assertEquals(block.branchLabelTypes, target.labelTypes)
    }

    // --- Synthetic Wasm binary builders ---

    private companion object {
        const val I32 = 0x7F
        const val I64 = 0x7E
        const val F32 = 0x7D
        const val F64 = 0x7C
        const val FUNCREF = 0x70
        const val EXTERNREF = 0x6F
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

    private fun f64LeBytes(rawBits: Long): List<Int> =
        (0 until 8).map { i -> ((rawBits ushr (i * 8)) and 0xFFL).toInt() }

    private fun funcType(params: List<Int>, results: List<Int>): List<Int> =
        listOf(0x60) + uleb(params.size) + params + uleb(results.size) + results

    private fun typeSection(vararg types: List<Int>): List<Int> =
        uleb(types.size) + types.flatMap { it }

    private fun funcImport(module: String, name: String, typeIndex: Int): List<Int> =
        strBytes(module) + strBytes(name) + listOf(0x00) + uleb(typeIndex)

    private fun globalImport(module: String, name: String, valType: Int, mutable: Boolean): List<Int> =
        strBytes(module) + strBytes(name) + listOf(0x03, valType, if (mutable) 0x01 else 0x00)

    private fun importSection(vararg imports: List<Int>): List<Int> =
        uleb(imports.size) + imports.flatMap { it }

    private fun funcSection(vararg typeIndices: Int): List<Int> =
        uleb(typeIndices.size) + typeIndices.flatMap { uleb(it) }

    private fun funcrefTable(min: Int): List<Int> = listOf(FUNCREF, 0x00) + uleb(min)
    private fun externrefTable(min: Int): List<Int> = listOf(EXTERNREF, 0x00) + uleb(min)

    private fun tableSection(vararg tables: List<Int>): List<Int> =
        uleb(tables.size) + tables.flatMap { it }

    private fun memorySection(vararg entries: Pair<Int, Int>): List<Int> {
        // Group flags=0 (1 int min) vs flags=1 (pair min,max passed sequentially if needed) -> simpler direct helper:
        // Usage: (0x00 to initialPages) emits [0x00, initialPages]. Use consecutive pairs (0x01 to initialPages), (0x01 to maxPages)?
        // Better: handle explicitly below.
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

    private fun dataCountSection(count: Int): List<Int> = uleb(count)

    private fun passiveData(bytes: ByteArray): List<Int> =
        listOf(0x01) + uleb(bytes.size) + bytes.map { it.toInt() and 0xFF }

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
