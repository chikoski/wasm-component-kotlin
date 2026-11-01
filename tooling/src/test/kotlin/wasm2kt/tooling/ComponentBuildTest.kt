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
    fun `unsupported features are found in the plan`(@TempDir dir: Path) {
        Decomposer.decompose(fixtures.resolve("roundtrip.wasm"), dir.resolve("rt"))
        assertEquals(emptyList(), PlanCheck.unsupportedFeatures(dir.resolve("rt")))

        // Two nested components passing a resource: the fused adapter needs intrinsics Chicory lacks.
        Decomposer.decompose(fixtures.resolve("composed-res.wasm"), dir.resolve("composed"))
        val problems = PlanCheck.unsupportedFeatures(dir.resolve("composed"))
        assertTrue(problems.any { "ResourceTransferOwn" in it }, "got $problems")
        assertTrue(problems.all { it.startsWith("component intrinsic not supported by the Chicory backend: ") }, "got $problems")
        assertEquals(problems.distinct(), problems)
    }

    @Test
    fun `unsupported types and async options are reported with the function that uses them`(@TempDir dir: Path) {
        Files.writeString(
            dir.resolve("plan.json"),
            """
            {"version": 2,
             "options": [{"index": 0, "async": false}, {"index": 1, "async": true}],
             "imports": [{"index": 0, "name": "my:pkg/io", "path": ["read"]}],
             "trampolines": [
               {"index": 0, "kind": "lower-import", "import": 0, "options": 0,
                "type": {"params": [], "results": [{"k": "unsupported", "detail": "Future(0)"}]}}
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
                "export 'run' is async, which the Chicory backend does not support yet",
                "export 'my:pkg/api#stream' uses a type the Chicory backend does not support: Stream(0)",
                "import 'my:pkg/io#read' uses a type the Chicory backend does not support: Future(0)",
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
            import com.example.ct.CounterComponent

            fun run(): String {
                val imports = object : RootWorld.Imports {
                    override val envProvider = object : RootWorld.EnvProvider {
                        override fun getMultiplier(): Int = 6
                        override fun formatTag(prefix: String, code: UInt): String = "${'$'}prefix#${'$'}code"
                    }
                }
                val rt = RootWorld.withSession(RoundtripComponent, imports) {
                    val scaled = with(RootWorld.MathApi) { scale(7) }
                    val tag = RootWorld.describeCode("ERR", 404u)
                    val u64 = RootWorld.echoU64(ULong.MAX_VALUE) == ULong.MAX_VALUE
                    "scaled=${'$'}scaled,tag=${'$'}tag,u64Max=${'$'}u64"
                }
                val ct = com.example.ct.RootWorld.withSession(CounterComponent) {
                    com.example.ct.RootWorld.Counter(10).use { c ->
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
            *(listOf("-Xcontext-parameters", "-jvm-target", "11", "-no-stdlib", "-classpath", classpath, "-d", out.toString()) + sources).toTypedArray(),
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
}
