package wasm2kt.tooling

import com.dylibso.chicory.build.time.compiler.Config
import com.dylibso.chicory.build.time.compiler.Generator
import com.dylibso.chicory.compiler.InterpreterFallback
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.Machine
import com.dylibso.chicory.wasm.Parser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import wasm2kt.runtime.WitWriter
import wasm2kt.runtime.chicory.ChicoryComponent
import java.io.ByteArrayOutputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmMethodSplitterTest {
    private val fixtures: Path = Paths.get(System.getProperty("wasm2kt.fixtures"))

    @Test
    fun `splits oversized core wasm method below HotSpot and ART limits and preserves results`(@TempDir dir: Path) {
        val wasmBytes = buildHugeLoopAndBranchWasm(steps = 700)
        val wasmPath = dir.resolve("huge.wasm")
        Files.write(wasmPath, wasmBytes)

        // First compile with Chicory alone into unsplitDir to verify the baseline exceeds ART/HotSpot limits.
        val unsplitClasses = dir.resolve("unsplit-classes")
        val unsplitResources = dir.resolve("unsplit-resources")
        Files.createDirectories(unsplitClasses)
        Files.createDirectories(unsplitResources)
        val generator = Generator(
            Config.builder()
                .withWasmFile(wasmPath)
                .withName("test.huge.Unsplit")
                .withTargetClassFolder(unsplitClasses)
                .withTargetSourceFolder(dir.resolve("unsplit-unused-src"))
                .withTargetWasmFolder(unsplitResources)
                .withInterpreterFallback(InterpreterFallback.SILENT)
                .build(),
        )
        val interpreted = generator.generateResources()
        generator.generateMetaWasm(interpreted)
        assertEquals(emptySet<Int>(), interpreted)

        val beforeStats = JvmMethodSplitter.measureClassesDir(unsplitClasses)
        assertTrue(beforeStats.artOversizedMethods >= 1, "expected unsplit method >= ART threshold, got $beforeStats")
        assertTrue(beforeStats.hotspotOversizedMethods >= 1, "expected unsplit method > 8000 bytes, got $beforeStats")

        // Copy to splitClasses and splitClassesCopy to verify both splitting and byte-for-byte determinism.
        val splitClasses = dir.resolve("split-classes")
        val splitClassesCopy = dir.resolve("split-classes-copy")
        for (dstRoot in listOf(splitClasses, splitClassesCopy)) {
            Files.walk(unsplitClasses).use { paths ->
                for (src in paths) {
                    val rel = unsplitClasses.relativize(src)
                    val dst = dstRoot.resolve(rel)
                    if (Files.isDirectory(src)) Files.createDirectories(dst) else Files.copy(src, dst)
                }
            }
        }
        val afterStats = JvmMethodSplitter.processClasses(splitClasses, "test/huge/UnsplitMachine")
        val copyStats = JvmMethodSplitter.processClasses(splitClassesCopy, "test/huge/UnsplitMachine")
        assertEquals(afterStats, copyStats)
        assertTrue(afterStats.splitMethods >= 1, "expected at least 1 method to be split, got $afterStats")
        assertTrue(afterStats.generatedHelpers >= 1, "expected helper methods, got $afterStats")
        assertEquals(0, afterStats.hotspotOversizedMethods, "all split methods should fit <= 8000 bytes: $afterStats")
        assertEquals(0, afterStats.artOversizedMethods, "all split methods should fit < ART limit: $afterStats")
        assertTrue(afterStats.maxMethodCodeBytes <= JvmMethodSplitter.HOTSPOT_HUGE_METHOD_LIMIT_BYTES, "max=${afterStats.maxMethodCodeBytes}")

        val splitFileHashes = Files.walk(splitClasses).use { s -> s.filter { Files.isRegularFile(it) }.sorted().toList() }
        val copyFileHashes = Files.walk(splitClassesCopy).use { s -> s.filter { Files.isRegularFile(it) }.sorted().toList() }
        assertEquals(splitFileHashes.size, copyFileHashes.size)
        for (i in splitFileHashes.indices) {
            assertContentEquals(Files.readAllBytes(splitFileHashes[i]), Files.readAllBytes(copyFileHashes[i]))
        }

        // Execute both unsplit and split bytecode across multiple inputs (including NaN, -0.0, Inf, extremes).
        val metaBytes = Files.readAllBytes(dir.resolve("unsplit-resources/test/huge/Unsplit.meta"))
        fun invoke(classesDir: Path, a: Int, b: Long, c: Float, d: Double): Long {
            URLClassLoader(arrayOf(classesDir.toUri().toURL()), javaClass.classLoader).use { loader ->
                val machineCtor = loader.loadClass("test.huge.UnsplitMachine").getConstructor(Instance::class.java)
                val instance = Instance.builder(Parser.parse(metaBytes))
                    .withMachineFactory { inst -> machineCtor.newInstance(inst) as Machine }
                    .build()
                return instance.export("run").apply(
                    a.toLong(),
                    b,
                    java.lang.Float.floatToRawIntBits(c).toLong(),
                    java.lang.Double.doubleToRawLongBits(d),
                )[0]
            }
        }

        val testInputs = listOf(
            listOf(0, 1L, 0.5f, 1.25),
            listOf(7, 1234567890123L, -3.25f, 42.5),
            listOf(-15, -99L, 1.5f, -0.125),
            listOf(42, 0L, 100.0f, 3.5),
            listOf(Int.MIN_VALUE, Long.MIN_VALUE, -0.0f, -0.0),
            listOf(-1, Long.MAX_VALUE, Float.NaN, Double.NEGATIVE_INFINITY),
            listOf(1024, -1L, Float.POSITIVE_INFINITY, Double.NaN),
        )
        for (input in testInputs) {
            val a = input[0] as Int
            val b = input[1] as Long
            val c = input[2] as Float
            val d = input[3] as Double
            val expected = invoke(unsplitClasses, a, b, c, d)
            val actual = invoke(splitClasses, a, b, c, d)
            assertEquals(expected, actual, "mismatch for input ($a, $b, $c, $d)")
        }

        // Re-running processClasses with a tighter target must be re-entrant without helper name collisions.
        val reSplitStats = JvmMethodSplitter.processClasses(splitClasses, "test/huge/UnsplitMachine", targetMethodBytes = 4_000)
        assertEquals(0, reSplitStats.artOversizedMethods)
        for (input in testInputs) {
            val a = input[0] as Int
            val b = input[1] as Long
            val c = input[2] as Float
            val d = input[3] as Double
            assertEquals(invoke(unsplitClasses, a, b, c, d), invoke(splitClasses, a, b, c, d))
        }
    }

    @Test
    fun `heavy fixture matches unsplit output and exercises split functions with tight ceiling`(@TempDir dir: Path) {
        val decomposed = dir.resolve("decomposed")
        Decomposer.decompose(fixtures.resolve("heavy.wasm"), decomposed)
        val plan = wasm2kt.runtime.chicory.ComponentPlan.parse(Files.readString(decomposed.resolve("plan.json")))

        val unsplitClasses = dir.resolve("unsplit-classes")
        val unsplitResources = dir.resolve("unsplit-resources")
        Files.createDirectories(unsplitClasses)
        Files.createDirectories(unsplitResources)
        val unsplitModules = compileWithoutSplit(decomposed, unsplitClasses, unsplitResources, "aot.heavy")

        val splitClasses = dir.resolve("split-classes")
        val splitResources = dir.resolve("split-resources")
        val compiled = CoreModuleCompiler.compile(decomposed, splitClasses, splitResources, "aot.heavy")

        val mainModule = compiled.first()
        // heavy's func_366 is ~56 KB Wasm -> exceeds JVM 64 KB classfile method limit in Chicory itself.
        assertEquals(1, mainModule.interpretedFunctions)
        assertTrue(mainModule.splitMethods > 0, "expected heavy core module to split large methods: ${mainModule.splitMethods}")
        assertEquals(0, mainModule.artOversizedMethods, "no compiled method in heavy should exceed ART limit")

        fun runHeavy(classesDir: Path, resourcesDir: Path, compiledList: List<CoreModuleCompiler.Compiled>): Map<String, Any> {
            return URLClassLoader(arrayOf(classesDir.toUri().toURL()), javaClass.classLoader).use { loader ->
                val modules = compiledList.associate { c ->
                    val meta = Files.readAllBytes(resourcesDir.resolve(c.metaResource.removePrefix("/")))
                    val ctor = loader.loadClass(c.machineClassName).getConstructor(Instance::class.java)
                    c.index to wasm2kt.runtime.chicory.CoreModule(Parser.parse(meta)) { inst ->
                        ctor.newInstance(inst) as Machine
                    }
                }
                ChicoryComponent(plan, modules).instantiate(null).use { component ->
                    val lcg = component.invoke(
                        component.resolve("lcg-sum"),
                        WitWriter().apply { int(1); int(10_000) }.toReader(),
                    ).long()
                    val regexReader = component.invoke(
                        component.resolve("regex-count"),
                        WitWriter().apply { string("[a-z]+ing"); string("running walking jumping stop") }.toReader(),
                    )
                    val regexStatus = regexReader.byte()
                    val regexCount = regexReader.int()
                    val jsonReader = component.invoke(
                        component.resolve("json-roundtrip"),
                        WitWriter().apply { string("""{"a": 1, "b": [true, "ok"]}""") }.toReader(),
                    )
                    val jsonStatus = jsonReader.byte()
                    val jsonOut = jsonReader.string()
                    val bigPayload = ByteArray(1024) { i -> ((i * 73 + 19) and 0xFF).toByte() }
                    val sorted = component.invoke(
                        component.resolve("sort-bytes"),
                        WitWriter().apply { bytes(bigPayload) }.toReader(),
                    ).bytes()
                    mapOf(
                        "lcg" to lcg,
                        "regexStatus" to regexStatus,
                        "regexCount" to regexCount,
                        "jsonStatus" to jsonStatus,
                        "jsonOut" to jsonOut,
                        "sorted" to sorted.toList(),
                    )
                }
            }
        }

        val expected = runHeavy(unsplitClasses, unsplitResources, unsplitModules)
        val actual = runHeavy(splitClasses, splitResources, compiled)
        assertEquals(expected, actual)
        assertEquals(0.toByte(), actual["regexStatus"])
        assertEquals(3, actual["regexCount"])
        assertEquals(0.toByte(), actual["jsonStatus"])
        assertEquals("""{"a":1,"b":[true,"ok"]}""", actual["jsonOut"])
        assertEquals(ByteArray(1024) { i -> ((i * 73 + 19) and 0xFF).toByte() }.sortedBy { it.toInt() and 0xFF }, actual["sorted"])

        // Force aggressive splitting (targetMethodBytes = 250) on heavy.wasm and re-check bit-identical results.
        val tightStats = JvmMethodSplitter.processClasses(unsplitClasses, "aot/heavy/Core0Machine", targetMethodBytes = 250)
        assertTrue(tightStats.generatedHelpers > 20, "expected heavy under 250B target to produce many helpers: $tightStats")
        assertEquals(expected, runHeavy(unsplitClasses, unsplitResources, unsplitModules))
    }

    @Test
    fun `br_table multi-exit control flow and traps propagate identically after splitting`(@TempDir dir: Path) {
        val wasmBytes = buildBrTableAndTrapWasm(steps = 40)
        val wasmPath = dir.resolve("br_table_trap.wasm")
        Files.write(wasmPath, wasmBytes)

        val unsplitClasses = dir.resolve("unsplit")
        val resources = dir.resolve("resources")
        Files.createDirectories(unsplitClasses)
        Files.createDirectories(resources)
        val generator = Generator(
            Config.builder()
                .withWasmFile(wasmPath)
                .withName("test.trap.BrTable")
                .withTargetClassFolder(unsplitClasses)
                .withTargetSourceFolder(dir.resolve("unused-src"))
                .withTargetWasmFolder(resources)
                .withInterpreterFallback(InterpreterFallback.SILENT)
                .build(),
        )
        val interpreted = generator.generateResources()
        generator.generateMetaWasm(interpreted)
        assertEquals(emptySet<Int>(), interpreted)

        val splitClasses = dir.resolve("split")
        Files.walk(unsplitClasses).use { paths ->
            for (src in paths) {
                val rel = unsplitClasses.relativize(src)
                val dst = splitClasses.resolve(rel)
                if (Files.isDirectory(src)) Files.createDirectories(dst) else Files.copy(src, dst)
            }
        }
        val stats = JvmMethodSplitter.processClasses(splitClasses, "test/trap/BrTableMachine", targetMethodBytes = 250)
        assertTrue(stats.splitMethods >= 1 && stats.generatedHelpers >= 1, "expected br_table function to split: $stats")

        val metaBytes = Files.readAllBytes(resources.resolve("test/trap/BrTable.meta"))
        fun invoke(classesDir: Path, selector: Int, divisor: Int): Long {
            URLClassLoader(arrayOf(classesDir.toUri().toURL()), javaClass.classLoader).use { loader ->
                val ctor = loader.loadClass("test.trap.BrTableMachine").getConstructor(Instance::class.java)
                val instance = Instance.builder(Parser.parse(metaBytes))
                    .withMachineFactory { inst -> ctor.newInstance(inst) as Machine }
                    .build()
                return instance.export("dispatch").apply(selector.toLong(), divisor.toLong())[0]
            }
        }

        for (selector in 0..5) {
            assertEquals(invoke(unsplitClasses, selector, 3), invoke(splitClasses, selector, 3), "selector=$selector")
        }
        // Selector 6 triggers `unreachable`; divisor 0 on selector 1 triggers divide-by-zero trap across helper boundaries.
        assertFailsWith<RuntimeException> { invoke(splitClasses, 6, 3) }
        assertFailsWith<RuntimeException> { invoke(splitClasses, 1, 0) }
    }

    @Test
    fun `real component fixtures match unsplit execution when aggressively split at 250 bytes`(@TempDir dir: Path) {
        val componentCases = listOf("composed-str.wasm", "types.wasm")
        for (fixtureName in componentCases) {
            val base = dir.resolve(fixtureName.removeSuffix(".wasm"))
            val decomposed = base.resolve("decomposed")
            Decomposer.decompose(fixtures.resolve(fixtureName), decomposed)
            val plan = wasm2kt.runtime.chicory.ComponentPlan.parse(Files.readString(decomposed.resolve("plan.json")))

            val unsplitClasses = base.resolve("unsplit-classes")
            val splitClasses = base.resolve("split-classes")
            val resources = base.resolve("resources")
            Files.createDirectories(unsplitClasses)
            Files.createDirectories(splitClasses)
            Files.createDirectories(resources)

            val pkgName = "aot.${fixtureName.removeSuffix(".wasm").replace('-', '_')}"
            val modulesMeta = compileWithoutSplit(decomposed, unsplitClasses, resources, pkgName)
            Files.walk(unsplitClasses).use { paths ->
                for (src in paths) {
                    val rel = unsplitClasses.relativize(src)
                    val dst = splitClasses.resolve(rel)
                    if (Files.isDirectory(src)) Files.createDirectories(dst) else Files.copy(src, dst)
                }
            }
            val pkg = pkgName.replace('.', '/')
            val splitStats = modulesMeta.map { c ->
                JvmMethodSplitter.processClasses(splitClasses, "$pkg/Core${c.index}Machine", targetMethodBytes = 250)
            }
            assertTrue(splitStats.sumOf { it.generatedHelpers } > 0, "expected helpers for $fixtureName: $splitStats")

            fun runFixture(classesDir: Path): String {
                return URLClassLoader(arrayOf(classesDir.toUri().toURL()), javaClass.classLoader).use { loader ->
                    val mods = modulesMeta.associate { c ->
                        val meta = Files.readAllBytes(resources.resolve(c.metaResource.removePrefix("/")))
                        val ctor = loader.loadClass(c.machineClassName).getConstructor(Instance::class.java)
                        c.index to wasm2kt.runtime.chicory.CoreModule(Parser.parse(meta)) { inst ->
                            ctor.newInstance(inst) as Machine
                        }
                    }
                    ChicoryComponent(plan, mods).instantiate(null).use { component ->
                        when (fixtureName) {
                            "composed-str.wasm" -> {
                                val echoed = component.invoke(
                                    component.resolve("echo-utf8-utf8"),
                                    WitWriter().apply { string("multi-memory-split-世界") }.toReader(),
                                ).string()
                                val summed = component.invoke(
                                    component.resolve("sum"),
                                    WitWriter().apply { length(4); int(10); int(20); int(30); int(40) }.toReader(),
                                ).int()
                                val trapClass = runCatching {
                                    component.invoke(component.resolve("bad-utf8-via-utf8"), WitWriter().toReader())
                                }.exceptionOrNull()?.javaClass?.simpleName
                                "$echoed|$summed|$trapClass"
                            }
                            "types.wasm" -> component.invoke(
                                component.resolve("reverse-bytes"),
                                WitWriter().apply { bytes(byteArrayOf(1, 2, 3, 4, 5)) }.toReader(),
                            ).bytes().contentToString()
                            else -> ""
                        }
                    }
                }
            }

            assertEquals(runFixture(unsplitClasses), runFixture(splitClasses), "mismatch for $fixtureName")
        }
    }

    @Test
    fun `fallback skips methods with try-catch or ASTORE and enforces module prefix boundaries`(@TempDir dir: Path) {
        val core1Class = dir.resolve("pkg/Core1.class")
        val core10Class = dir.resolve("pkg/Core10.class")
        Files.createDirectories(core1Class.parent)
        writeTryCatchMethod(core1Class, "pkg/Core1", "func_0")
        writeDummyClassWithMethodNops(core10Class, "pkg/Core10", "func_0", 600)

        val stats = JvmMethodSplitter.processClasses(dir, "pkg/Core1", targetMethodBytes = 100)
        assertEquals(0, stats.splitMethods, "try-catch method must fall back cleanly without splitting")
        assertEquals(1, JvmMethodSplitter.measureClassesDir(dir).let {
            // Core10 was untouched by prefix "pkg/Core1" and exceeds 500 bytes, while Core1 try-catch < 600 bytes.
            if (it.maxMethodCodeBytes == 600) 1 else 0
        })

        val astoreClass = dir.resolve("pkg2/Core0Machine.class")
        Files.createDirectories(astoreClass.parent)
        writeAstoreMethod(astoreClass, "pkg2/Core0Machine", "func_0", 300)
        val astoreStats = JvmMethodSplitter.processClasses(dir, "pkg2/Core0Machine", targetMethodBytes = 100)
        assertEquals(0, astoreStats.splitMethods, "ASTORE method must fall back cleanly without throwing")
    }

    @Test
    fun `measureClassesDir counts HotSpot and ART oversized methods at exact thresholds`(@TempDir dir: Path) {
        writeDummyClassWithMethodNops(dir.resolve("A.class"), "pkg/A", "underLimit", 7_999)
        writeDummyClassWithMethodNops(dir.resolve("B.class"), "pkg/B", "atHotspotEdge", 8_000)
        writeDummyClassWithMethodNops(dir.resolve("C.class"), "pkg/C", "hotspotOnly", 8_001)
        writeDummyClassWithMethodNops(dir.resolve("D.class"), "pkg/D", "justUnderArt", 20_999)
        writeDummyClassWithMethodNops(dir.resolve("E.class"), "pkg/E", "atArtThreshold", 21_000)

        val stats = JvmMethodSplitter.measureClassesDir(dir)
        assertEquals(3, stats.hotspotOversizedMethods)
        assertEquals(1, stats.artOversizedMethods)
        assertEquals(21_000, stats.maxMethodCodeBytes)
    }

    @Test
    fun `warningsFor emits interpreter, ART, and HotSpot warnings when counts are positive`() {
        val clean = CoreModuleCompiler.warningsFor(
            listOf(CoreModuleCompiler.Compiled(0, "p.Core0Machine", "/p/Core0.meta", 0, splitMethods = 2, hotspotOversizedMethods = 0, artOversizedMethods = 0, maxMethodCodeBytes = 6_500)),
        )
        assertEquals(emptyList(), clean)

        val warned = CoreModuleCompiler.warningsFor(
            listOf(
                CoreModuleCompiler.Compiled(
                    index = 0,
                    machineClassName = "p.Core0Machine",
                    metaResource = "/p/Core0.meta",
                    interpretedFunctions = 1,
                    splitMethods = 4,
                    hotspotOversizedMethods = 3,
                    artOversizedMethods = 2,
                    maxMethodCodeBytes = 24_500,
                ),
            ),
        )
        assertEquals(3, warned.size)
        assertTrue("will be interpreted" in warned[0], warned[0])
        assertTrue("16,383 DEX code units" in warned[1] && "24500" in warned[1], warned[1])
        assertTrue("8,000-byte JIT limit" in warned[2] && "-XX:-DontCompileHugeMethods" in warned[2], warned[2])
    }

    private fun compileWithoutSplit(
        decomposedDir: Path,
        classesDir: Path,
        resourcesDir: Path,
        packageName: String,
    ): List<CoreModuleCompiler.Compiled> {
        return PlanFiles.coreModules(decomposedDir).map { (index, file) ->
            val baseName = "Core$index"
            val config = Config.builder()
                .withWasmFile(decomposedDir.resolve(file))
                .withName("$packageName.$baseName")
                .withTargetClassFolder(classesDir)
                .withTargetSourceFolder(classesDir.resolveSibling("${classesDir.fileName}-unused-sources"))
                .withTargetWasmFolder(resourcesDir)
                .withInterpreterFallback(InterpreterFallback.SILENT)
                .build()
            val generator = Generator(config)
            val interpreted = generator.generateResources()
            generator.generateMetaWasm(interpreted)
            val pkgPath = packageName.replace('.', '/')
            CoreModuleCompiler.Compiled(
                index = index,
                machineClassName = "$packageName.${baseName}Machine",
                metaResource = "/$pkgPath/$baseName.meta",
                interpretedFunctions = interpreted.size,
            )
        }
    }

    private fun writeTryCatchMethod(out: Path, internalName: String, methodName: String) {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        val mv: MethodVisitor = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, methodName, "()V", null, null)
        mv.visitCode()
        val start = Label()
        val end = Label()
        val handler = Label()
        val after = Label()
        mv.visitTryCatchBlock(start, end, handler, "java/lang/RuntimeException")
        mv.visitLabel(start)
        repeat(150) { mv.visitInsn(Opcodes.NOP) }
        mv.visitLabel(end)
        mv.visitJumpInsn(Opcodes.GOTO, after)
        mv.visitLabel(handler)
        mv.visitInsn(Opcodes.POP)
        mv.visitLabel(after)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(1, 1)
        mv.visitEnd()
        cw.visitEnd()
        Files.write(out, cw.toByteArray())
    }

    private fun writeAstoreMethod(out: Path, internalName: String, methodName: String, nops: Int) {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        val mv: MethodVisitor = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, methodName, "()V", null, null)
        mv.visitCode()
        mv.visitInsn(Opcodes.ACONST_NULL)
        mv.visitVarInsn(Opcodes.ASTORE, 0)
        repeat(nops) { mv.visitInsn(Opcodes.NOP) }
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(1, 1)
        mv.visitEnd()
        cw.visitEnd()
        Files.write(out, cw.toByteArray())
    }

    /**
     * Synthesizes a valid Core Wasm 1.0 module exporting `dispatch(i32 selector, i32 divisor) -> i32`
     * with a `br_table` multi-way branch, divide-by-zero trap, and `unreachable` trap across
     * multiple blocks that accumulate state before and after splitting.
     */
    private fun buildBrTableAndTrapWasm(steps: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00))

        // Type section: (i32, i32) -> (i32)
        writeSection(out, 1) { s ->
            writeUleb(s, 1)
            s.write(0x60)
            writeUleb(s, 2); s.write(0x7F); s.write(0x7F)
            writeUleb(s, 1); s.write(0x7F)
        }
        // Function section
        writeSection(out, 3) { s ->
            writeUleb(s, 1); writeUleb(s, 0)
        }
        // Export section: "dispatch" -> func 0
        writeSection(out, 7) { s ->
            writeUleb(s, 1)
            val name = "dispatch".toByteArray(Charsets.UTF_8)
            writeUleb(s, name.size); s.write(name)
            s.write(0x00); writeUleb(s, 0)
        }

        val body = ByteArrayOutputStream()
        // 1 extra i32 local (slot 2: accumulator)
        writeUleb(body, 1)
        writeUleb(body, 1); body.write(0x7F)

        // Repeatedly transform local2 before and after multi-way br_table so splitting cuts multiple segments.
        fun emitMixBlock(tag: Int) {
            body.write(0x20); writeUleb(body, 2)
            body.write(0x41); writeSleb(body, tag * 13 + 7)
            body.write(0x6A) // i32.add
            body.write(0x41); writeSleb(body, 0x5A5A)
            body.write(0x73) // i32.xor
            body.write(0x21); writeUleb(body, 2)
        }

        for (i in 0 until steps) emitMixBlock(i)

        // Nested 4-level block around br_table: target 0 = safe, 1 = div_s, 2 = add, 3(default) = unreachable.
        body.write(0x02); body.write(0x40) // block outer (depth 3 from inner)
        body.write(0x02); body.write(0x40) // block b2 (depth 2)
        body.write(0x02); body.write(0x40) // block b1 (depth 1)
        body.write(0x02); body.write(0x40) // block b0 (depth 0)
        body.write(0x20); writeUleb(body, 0) // selector
        // br_table [0, 1, 2] default=3 (Wait: 3 targets + default: table count=4 entries -> 0, 1, 2, 0; default=3)
        body.write(0x0E)
        writeUleb(body, 4)
        writeUleb(body, 0)
        writeUleb(body, 1)
        writeUleb(body, 2)
        writeUleb(body, 0)
        writeUleb(body, 3) // default depth 3 -> falls to unreachable handler after block outer
        body.write(0x0B) // end b0 (target 0)
        for (i in 0 until steps) emitMixBlock(i + 100)
        body.write(0x0C); writeUleb(body, 2) // br outer
        body.write(0x0B) // end b1 (target 1: div_s)
        body.write(0x20); writeUleb(body, 2)
        body.write(0x20); writeUleb(body, 1) // divisor
        body.write(0x6D) // i32.div_s
        body.write(0x21); writeUleb(body, 2)
        for (i in 0 until steps) emitMixBlock(i + 200)
        body.write(0x0C); writeUleb(body, 1) // br outer
        body.write(0x0B) // end b2 (target 2)
        body.write(0x20); writeUleb(body, 2)
        body.write(0x20); writeUleb(body, 1)
        body.write(0x6A) // i32.add
        body.write(0x21); writeUleb(body, 2)
        for (i in 0 until steps) emitMixBlock(i + 300)
        body.write(0x0C); writeUleb(body, 0) // br outer
        body.write(0x0B) // end outer
        // Wait: selector default breaks to outer, whereas selector 0..2 hit inner blocks and branch past default!
        // Make default execute unreachable unless selector matched 0..5 -> wrap differently:
        // Actually `br outer` jumped out of `outer` (here after `end outer`), wait so make `selector == 6` hit `unreachable` inside b3:
        // Let's keep `selector == 6` triggering explicit unreachable check right here!
        body.write(0x20); writeUleb(body, 0)
        body.write(0x41); writeSleb(body, 6)
        body.write(0x46) // i32.eq
        body.write(0x04); body.write(0x40)
        body.write(0x00) // unreachable
        body.write(0x0B)

        body.write(0x20); writeUleb(body, 2)
        body.write(0x0B) // end func

        writeSection(out, 10) { s ->
            writeUleb(s, 1)
            val b = body.toByteArray()
            writeUleb(s, b.size)
            s.write(b)
        }
        return out.toByteArray()
    }

    private fun writeDummyClassWithMethodNops(out: Path, internalName: String, methodName: String, totalBytes: Int) {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, methodName, "()V", null, null)
        mv.visitCode()
        repeat(totalBytes - 1) { mv.visitInsn(Opcodes.NOP) }
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        Files.write(out, cw.toByteArray())
    }

    /**
     * Synthesizes a valid Core Wasm 1.0 module with one large function `run(i32, i64, f32, f64) -> i64`
     * containing conditional branches, a counted loop, and repeated chained updates across all 4
     * primitive local types so the unsplit compiled bytecode exceeds 21,000 bytes.
     */
    private fun buildHugeLoopAndBranchWasm(steps: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00))

        // Type section: (i32, i64, f32, f64) -> (i64)
        writeSection(out, 1) { s ->
            writeUleb(s, 1)
            s.write(0x60) // func
            writeUleb(s, 4)
            s.write(0x7F); s.write(0x7E); s.write(0x7D); s.write(0x7C)
            writeUleb(s, 1)
            s.write(0x7E) // i64
        }
        // Function section: func 0 -> type 0
        writeSection(out, 3) { s ->
            writeUleb(s, 1); writeUleb(s, 0)
        }
        // Export section: export "run" -> func 0
        writeSection(out, 7) { s ->
            writeUleb(s, 1)
            val name = "run".toByteArray(Charsets.UTF_8)
            writeUleb(s, name.size); s.write(name)
            s.write(0x00); writeUleb(s, 0)
        }
        // Code section: 1 body
        val body = ByteArrayOutputStream()
        // Locals: 1 extra i32 local (slot 4: loop counter)
        writeUleb(body, 1)
        writeUleb(body, 1); body.write(0x7F)

        // Loop updating all 4 locals to ensure loop-carried live-ins/live-outs.
        body.write(0x41); writeSleb(body, 3); body.write(0x21); writeUleb(body, 4) // counter = 3
        body.write(0x03); body.write(0x40) // loop void
        body.write(0x20); writeUleb(body, 0); body.write(0x41); writeSleb(body, 17); body.write(0x6A); body.write(0x21); writeUleb(body, 0)
        body.write(0x20); writeUleb(body, 1); body.write(0x20); writeUleb(body, 0); body.write(0xAC); body.write(0x7C); body.write(0x21); writeUleb(body, 1)
        body.write(0x20); writeUleb(body, 2); body.write(0x20); writeUleb(body, 2); body.write(0x92); body.write(0x21); writeUleb(body, 2) // f32 += f32
        body.write(0x20); writeUleb(body, 3); body.write(0x20); writeUleb(body, 3); body.write(0xA0); body.write(0x21); writeUleb(body, 3) // f64 += f64
        body.write(0x20); writeUleb(body, 4); body.write(0x41); writeSleb(body, -1); body.write(0x6A); body.write(0x22.toInt()); writeUleb(body, 4) // local.tee 4
        body.write(0x0D); writeUleb(body, 0) // br_if 0
        body.write(0x0B) // end loop

        // Long sequence of branches updating i32, i64, f32, f64.
        for (i in 0 until steps) {
            // if ((local0 & 1) == 0) { ... } else { ... }
            body.write(0x20); writeUleb(body, 0)
            body.write(0x41); writeSleb(body, 1)
            body.write(0x71) // i32.and
            body.write(0x04); body.write(0x40) // if void
            body.write(0x20); writeUleb(body, 0); body.write(0x41); writeSleb(body, (i % 97) + 1); body.write(0x6A); body.write(0x21); writeUleb(body, 0)
            body.write(0x20); writeUleb(body, 1); body.write(0x42); writeSleb(body, (i * 31L) + 3L); body.write(0x7C); body.write(0x21); writeUleb(body, 1)
            body.write(0x20); writeUleb(body, 2); body.write(0x20); writeUleb(body, 2); body.write(0x92); body.write(0x21); writeUleb(body, 2)
            body.write(0x05) // else
            body.write(0x20); writeUleb(body, 0); body.write(0x41); writeSleb(body, (i % 53) + 5); body.write(0x73); body.write(0x21); writeUleb(body, 0)
            body.write(0x20); writeUleb(body, 1); body.write(0x20); writeUleb(body, 0); body.write(0xAC); body.write(0x85); body.write(0x21); writeUleb(body, 1)
            body.write(0x20); writeUleb(body, 3); body.write(0x20); writeUleb(body, 3); body.write(0xA0); body.write(0x21); writeUleb(body, 3)
            body.write(0x0B) // end if
        }

        // Mix i32, i64, f32 bits, f64 bits into returned i64.
        body.write(0x20); writeUleb(body, 1)
        body.write(0x20); writeUleb(body, 0); body.write(0xAC); body.write(0x85) // xor i64.extend_i32_s(local0)
        body.write(0x20); writeUleb(body, 2); body.write(0xBC); body.write(0xAC); body.write(0x85) // xor i64.extend_i32_s(i32.reinterpret_f32(local2))
        body.write(0x20); writeUleb(body, 3); body.write(0xBD); body.write(0x85) // xor i64.reinterpret_f64(local3)
        body.write(0x0B) // end func

        writeSection(out, 10) { s ->
            writeUleb(s, 1)
            val b = body.toByteArray()
            writeUleb(s, b.size)
            s.write(b)
        }
        return out.toByteArray()
    }

    private inline fun writeSection(out: ByteArrayOutputStream, id: Int, block: (ByteArrayOutputStream) -> Unit) {
        val sec = ByteArrayOutputStream()
        block(sec)
        val bytes = sec.toByteArray()
        out.write(id)
        writeUleb(out, bytes.size)
        out.write(bytes)
    }

    private fun writeUleb(out: ByteArrayOutputStream, v: Int) {
        var cur = v
        while (true) {
            val b = cur and 0x7F
            cur = cur ushr 7
            if (cur == 0) {
                out.write(b)
                return
            }
            out.write(b or 0x80)
        }
    }

    private fun writeSleb(out: ByteArrayOutputStream, v: Long) {
        var cur = v
        while (true) {
            val b = (cur and 0x7F).toInt()
            cur = cur shr 7
            val signBit = (b and 0x40) != 0
            if ((cur == 0L && !signBit) || (cur == -1L && signBit)) {
                out.write(b)
                return
            }
            out.write(b or 0x80)
        }
    }

    private fun writeSleb(out: ByteArrayOutputStream, v: Int) = writeSleb(out, v.toLong())
}
