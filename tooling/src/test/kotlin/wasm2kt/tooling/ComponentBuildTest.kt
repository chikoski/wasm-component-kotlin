package wasm2kt.tooling

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the build-time pipeline (wasm decomposer on Chicory -> AOT core modules -> Kotlin sources),
 * compiles the generated sources against the AOT classes, and executes them — no wasm-tools, no
 * Rust, no native code, no interpreter.
 */
class ComponentBuildTest {
    private val fixtures: Path = Paths.get(System.getProperty("wasm2kt.fixtures"))

    @Test
    fun `decomposer running on Chicory matches the expected outputs`(@TempDir dir: Path) {
        Decomposer.decompose(fixtures.resolve("roundtrip.wasm"), dir)
        val files = dir.listDirectoryEntries().map { it.name }.sorted()
        assertEquals(listOf("core0.wasm", "core1.wasm", "core2.wasm", "plan.json", "wit.json"), files)
        assertTrue(Files.readString(dir.resolve("wit.json")).contains("\"transform-summary\""))
    }

    @Test
    fun `composed components with multi-memory adapters run AOT-compiled`(@TempDir dir: Path) {
        // #47: the bundled (wasm) decomposer splits it, Chicory's build-time compiler compiles the
        // fused adapter, which imports both children's memories, and the transcoders run.
        val decomposed = dir.resolve("decomposed")
        Decomposer.decompose(fixtures.resolve("composed-str.wasm"), decomposed)
        assertEquals(emptyList(), PlanCheck.unsupportedFeatures(decomposed))
        val compiled = CoreModuleCompiler.compile(decomposed, dir.resolve("classes"), dir.resolve("resources"), "aot.composed")
        assertTrue(compiled.all { it.interpretedFunctions == 0 }, "every function should be compiled")

        val loader = java.net.URLClassLoader(arrayOf(dir.resolve("classes").toUri().toURL()), javaClass.classLoader)
        val plan = wasm2kt.runtime.chicory.ComponentPlan.parse(Files.readString(decomposed.resolve("plan.json")))
        val modules = compiled.associate { c ->
            val meta = Files.readAllBytes(dir.resolve("resources").resolve(c.metaResource.removePrefix("/")))
            val machine = loader.loadClass(c.machineClassName).getConstructor(com.dylibso.chicory.runtime.Instance::class.java)
            c.index to wasm2kt.runtime.chicory.CoreModule(com.dylibso.chicory.wasm.Parser.parse(meta)) { instance ->
                machine.newInstance(instance) as com.dylibso.chicory.runtime.Machine
            }
        }
        wasm2kt.runtime.chicory.ChicoryComponent(plan, modules).instantiate(null).use { c ->
            for (pair in listOf("utf8-utf16", "utf16-utf8", "compact-utf8", "utf8-compact")) {
                val s = "aé日🎌".repeat(100)
                val echoed = c.invoke(c.resolve("echo-$pair"), wasm2kt.runtime.WitWriter().apply { string(s) }.toReader()).string()
                assertEquals(s, echoed, "echo-$pair")
            }
        }
    }

    @Test
    fun `unsupported features are found in the plan`(@TempDir dir: Path) {
        Decomposer.decompose(fixtures.resolve("roundtrip.wasm"), dir.resolve("rt"))
        assertEquals(emptyList(), PlanCheck.unsupportedFeatures(dir.resolve("rt")))

        // Nested components passing a resource: the fused adapter's intrinsics are supported (#70).
        Decomposer.decompose(fixtures.resolve("composed-res.wasm"), dir.resolve("composed"))
        assertEquals(emptyList(), PlanCheck.unsupportedFeatures(dir.resolve("composed")))

        // A map<K, V> (#71) is not.
        Decomposer.decompose(fixtures.resolve("map-type.wasm"), dir.resolve("map"))
        assertEquals(
            listOf("export 'size' uses a type not supported by this backend: Map(TypeMapIndex(0))"),
            PlanCheck.unsupportedFeatures(dir.resolve("map")),
        )
    }

    @Test
    fun `a component with an unsupported type still instantiates and fails only when it is used`(@TempDir dir: Path) {
        Decomposer.decompose(fixtures.resolve("map-type.wasm"), dir)
        wasm2kt.runtime.chicory.ChicoryComponent.fromDirectory(dir).instantiate(null).use { c ->
            assertEquals(42, c.invoke(c.resolve("answer"), wasm2kt.runtime.WitWriter().toReader()).int())
            val e = runCatching { c.invoke(c.resolve("size"), wasm2kt.runtime.WitWriter().apply { length(0) }.toReader()) }.exceptionOrNull()
            assertTrue(e is UnsupportedOperationException && "not supported by the Chicory backend" in e.message!!, "got $e")
            assertEquals(42, c.invoke(c.resolve("answer"), wasm2kt.runtime.WitWriter().toReader()).int(), "the instance stays usable")
        }
    }

    @Test
    fun `nested components pass resources through the fused adapters`(@TempDir dir: Path) {
        // Wasmtime 48's adapters move the resource between the children's handle tables (#70).
        Decomposer.decompose(fixtures.resolve("composed-res.wasm"), dir)
        wasm2kt.runtime.chicory.ChicoryComponent.fromDirectory(dir).instantiate(null).use { c ->
            assertEquals(1, c.invoke(c.resolve("g"), wasm2kt.runtime.WitWriter().toReader()).int())
        }
    }

    @Test
    fun `unsupported types are reported with the function that uses them`(@TempDir dir: Path) {
        Files.writeString(
            dir.resolve("plan.json"),
            """
            {"version": 5,
             "options": [{"index": 0, "async": false}, {"index": 1, "async": true}],
             "imports": [{"index": 0, "name": "my:pkg/io", "path": ["read"]}],
             "streamTables": [
               {"index": 2, "instance": 0, "element": {"k": "unsupported", "detail": "Map(0)"}}
             ],
             "futureTables": [
               {"index": 3, "instance": 0, "element": {"k": "list", "element": {"k": "unsupported", "detail": "FixedLengthList(1)"}}}
             ],
             "trampolines": [
               {"index": 0, "kind": "lower-import", "import": 0, "options": 0,
                "type": {"params": [], "results": [{"k": "unsupported", "detail": "Future(0)"}]}},
               {"index": 1, "kind": "task-return", "instance": 0, "options": 0,
                "results": [{"k": "unsupported", "detail": "Map(1)"}]}
             ],
             "exports": [
               {"instance": null, "name": "run", "options": 1, "type": {"params": [], "results": []}},
               {"instance": "my:pkg/api", "name": "stream", "options": 0,
                "type": {"params": [{"name": "s", "type": {"k": "list", "element": {"k": "unsupported", "detail": "Stream(0)"}}}], "results": []}}
             ]}
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                "export 'my:pkg/api#stream' uses a type not supported by this backend: Stream(0)",
                "import 'my:pkg/io#read' uses a type not supported by this backend: Future(0)",
                "task.return uses a type not supported by this backend: Map(1)",
                "stream table 2 uses a type not supported by this backend: Map(0)",
                "future table 3 uses a type not supported by this backend: FixedLengthList(1)",
            ),
            PlanCheck.unsupportedFeatures(dir),
        )
    }

    @Test
    fun `decomposer errors are reported`(@TempDir dir: Path) {
        val notAComponent = dir.resolve("bad.wasm")
        Files.write(notAComponent, byteArrayOf(0, 'a'.code.toByte(), 's'.code.toByte(), 'm'.code.toByte(), 1, 0, 0, 0))
        val e = runCatching { Decomposer.decompose(notAComponent, dir.resolve("out")) }.exceptionOrNull()
        assertTrue(e is IllegalStateException && e.message!!.contains("wasm2kt-decompose failed"), "got $e")
    }

    @Test
    fun `generated component runs AOT-compiled roundtrip and counter worlds`(@TempDir dir: Path) {
        val roundtrip = ComponentBuild.run(fixtures.resolve("roundtrip.wasm"), "roundtrip", "com.example.rt", dir.resolve("rt"))
        val counter = ComponentBuild.run(fixtures.resolve("counter.wasm"), "counter", "com.example.ct", dir.resolve("ct"))

        // AOT output: one machine class per core module, plus .meta and plan resources.
        assertTrue(Files.exists(roundtrip.classesDir.resolve("com/example/rt/wasm2kt/roundtrip/Core0Machine.class")))
        assertTrue(Files.exists(roundtrip.resourcesDir.resolve("com/example/rt/wasm2kt/roundtrip/plan.json")))

        val harness = dir.resolve("Harness.kt")
        Files.writeString(
            harness,
            """
            package harness

            import com.example.rt.*
            import com.example.rt.example.roundtrip.env_provider.EnvProvider
            import com.example.rt.exports.example.roundtrip.math_api.scale
            import com.example.ct.CounterComponent
            import com.example.ct.exports.example.counter.counter_api.Counter

            fun run(): String {
                val imports = imports(
                    exampleRoundtripEnvProvider = {
                        object : EnvProvider {
                            override fun getMultiplier(): Int = 6
                            override fun formatTag(prefix: String, code: UInt): String = "${'$'}prefix#${'$'}code"
                        }
                    },
                )
                val rt = withSession(RoundtripComponent, imports) {
                    val scaled = scale(7)
                    val tag = describeCode("ERR", 404u)
                    val u64 = echoU64(ULong.MAX_VALUE) == ULong.MAX_VALUE
                    "scaled=${'$'}scaled,tag=${'$'}tag,u64Max=${'$'}u64"
                }
                val ct = com.example.ct.withSession(CounterComponent) {
                    Counter(10).use { c ->
                        c.increment()
                        c.add(5)
                        "counter=${'$'}{c.getValue()}"
                    }
                }
                return "${'$'}rt;${'$'}ct"
            }
            """.trimIndent(),
        )

        val out = dir.resolve("out")
        val sources = (listOf(roundtrip.kotlinDir, counter.kotlinDir).flatMap { root ->
            Files.walk(root).use { s -> s.filter { it.toString().endsWith(".kt") }.toList() }
        } + listOf(harness)).map { it.toString() }
        val classpath = listOf(System.getProperty("java.class.path"), roundtrip.classesDir, counter.classesDir)
            .joinToString(java.io.File.pathSeparator)
        val exit = org.jetbrains.kotlin.cli.jvm.K2JVMCompiler().exec(
            System.err,
            *(listOf("-Xcontext-parameters", "-jvm-target", "17", "-no-stdlib", "-classpath", classpath, "-d", out.toString()) + sources).toTypedArray(),
        )
        assertEquals(org.jetbrains.kotlin.cli.common.ExitCode.OK, exit, "generated sources must compile")

        val loader = java.net.URLClassLoader(
            listOf(out, roundtrip.classesDir, roundtrip.resourcesDir, counter.classesDir, counter.resourcesDir)
                .map { it.toUri().toURL() }.toTypedArray(),
            javaClass.classLoader,
        )
        val result = loader.loadClass("harness.HarnessKt").getDeclaredMethod("run").invoke(null)
        assertEquals("scaled=42,tag=ERR#404,u64Max=true;counter=16", result)
    }

    @Test
    fun `regenerate-fixtures script lists all decomposed test components without drift`(@TempDir dir: Path) {
        val repoRoot = fixtures.parent
        val componentsDir = repoRoot.resolve("runtime-chicory/src/test/resources/components")
        val regenerateScript = Files.readString(repoRoot.resolve("decomposer/regenerate-fixtures.sh"))

        val discovered = componentsDir.listDirectoryEntries()
            .filter { Files.isDirectory(it) }
            .map { it.name }
            .sorted()
        val match = Regex("""for\s+name\s+in\s+([^;]+);""").find(regenerateScript)
            ?: error("Missing component loop in decomposer/regenerate-fixtures.sh")
        val listed = match.groupValues[1].trim().split(Regex("""\s+""")).sorted()
        assertEquals(discovered, listed, "Every pre-decomposed test component directory must be listed in decomposer/regenerate-fixtures.sh")

        for (name in listOf("async-pipeline", "wasi-03", "wasi-cli")) {
            val out = dir.resolve(name)
            Decomposer.decompose(fixtures.resolve("$name.wasm"), out)
            val expectedDir = componentsDir.resolve(name)
            val actualFiles = out.listDirectoryEntries().map { it.name }.sorted()
            val expectedFiles = expectedDir.listDirectoryEntries().map { it.name }.sorted()
            assertEquals(expectedFiles, actualFiles, "Decomposed file list drifted for $name")
            for (fileName in expectedFiles) {
                assertTrue(
                    Files.readAllBytes(expectedDir.resolve(fileName)).contentEquals(Files.readAllBytes(out.resolve(fileName))),
                    "$name/$fileName drifted from decomposed fixtures/$name.wasm",
                )
            }
        }
    }

    @Test
    fun `PlanCheck usesAsync matches ComponentPlan usesAsync for all decomposed fixtures`() {
        val componentsDir = fixtures.parent.resolve("runtime-chicory/src/test/resources/components")
        val componentDirs = componentsDir.listDirectoryEntries().filter { Files.isDirectory(it) }.sortedBy { it.name }
        assertTrue(componentDirs.isNotEmpty())
        for (dir in componentDirs) {
            val planJson = Files.readString(dir.resolve("plan.json"))
            val planUsesAsync = wasm2kt.runtime.chicory.ComponentPlan.parse(planJson).usesAsync
            assertEquals(
                planUsesAsync,
                PlanCheck.usesAsync(dir),
                "usesAsync mismatch between ComponentPlan and PlanCheck for ${dir.name}",
            )
        }
    }

    @Test
    fun `ComponentBuild run with AOT backend emits class and data outputs without plan or meta`(@TempDir dir: Path) {
        val output = ComponentBuild.run(
            component = fixtures.resolve("counter.wasm"),
            componentName = "counter-aot",
            packageName = "com.example.aotbuild",
            workDir = dir,
            backend = BuildBackend.AOT,
        )
        assertTrue(Files.exists(output.kotlinDir.resolve("com/example/aotbuild/CounterAotComponent.kt")))
        val classFiles = Files.walk(output.classesDir).use { s -> s.filter { it.toString().endsWith(".class") }.toList() }
        assertTrue(classFiles.isNotEmpty())
        val resourceFiles = if (Files.exists(output.resourcesDir)) {
            Files.walk(output.resourcesDir).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
        } else emptyList()
        assertTrue(resourceFiles.none { it.toString().endsWith("plan.json") || it.toString().endsWith(".meta") })
    }
}
