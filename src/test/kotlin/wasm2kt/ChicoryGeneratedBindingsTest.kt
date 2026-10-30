package wasm2kt

import com.github.ajalt.clikt.core.parse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import wasm2kt.cli.Wasm2KtCommand
import wasm2kt.runtime.ComponentBackendFactory
import wasm2kt.runtime.chicory.ChicoryComponent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals

/**
 * End to end: bindings generated from WIT drive a component running on Chicory, through the same
 * world DSL used with Wasmtime — only the `withSession` argument changes.
 */
class ChicoryGeneratedBindingsTest {
    private val wasmToolsBin: String = System.getenv("WASM_TOOLS_BIN")?.takeIf { it.isNotBlank() }
        ?: Paths.get(System.getProperty("user.home"), ".cargo", "bin", "wasm-tools").takeIf { Files.isExecutable(it) }?.toString()
        ?: "wasm-tools"

    private fun component(name: String): ComponentBackendFactory =
        ChicoryComponent.fromDirectory(Paths.get("runtime-chicory/src/test/resources/components/$name").toAbsolutePath())

    /** Generates bindings for [wit] into [pkg], compiles them with [harness], and runs `run(component)`. */
    private fun generateCompileRun(tempDir: Path, wit: String, pkg: String, worldFile: String, harness: String, component: ComponentBackendFactory): String {
        val witPath = Paths.get(requireNotNull(javaClass.getResource("/fixtures/$wit.wit")).toURI())
        Wasm2KtCommand().parse(listOf("--wasm-tools-path", wasmToolsBin, "-o", tempDir.toString(), "-p", pkg, witPath.toString()))

        val pkgDir = tempDir.resolve(pkg.replace('.', '/'))
        val harnessPath = pkgDir.resolve("Harness.kt")
        Files.writeString(harnessPath, "package $pkg\n\nimport wasm2kt.runtime.ComponentBackendFactory\n\n" + harness.trimIndent())

        val classesOutDir = tempDir.resolve("classes")
        Files.createDirectories(classesOutDir)
        val exit = org.jetbrains.kotlin.cli.jvm.K2JVMCompiler().exec(
            System.err,
            "-Xcontext-parameters", "-jvm-target", "21", "-no-stdlib",
            "-classpath", System.getProperty("java.class.path"),
            "-d", classesOutDir.toString(),
            pkgDir.resolve("WitSupport.kt").toString(),
            pkgDir.resolve(worldFile).toString(),
            harnessPath.toString(),
        )
        assertEquals(org.jetbrains.kotlin.cli.common.ExitCode.OK, exit, "Generated bindings + harness must compile")

        val loader = java.net.URLClassLoader(arrayOf(classesOutDir.toUri().toURL()), javaClass.classLoader)
        val method = loader.loadClass("$pkg.HarnessKt").getDeclaredMethod("run", ComponentBackendFactory::class.java)
        return method.invoke(null, component) as String
    }

    @Test
    fun `arithmetic world runs on Chicory`(@TempDir tempDir: Path) {
        val out = generateCompileRun(
            tempDir, "arithmetic", "com.example.chicory.arithmetic", "ArithmeticWorld.kt",
            """
            fun run(component: ComponentBackendFactory): String =
                ArithmeticWorld.withSession(component) {
                    "add=${'$'}{ArithmeticWorld.add(20, 7)},sub=${'$'}{ArithmeticWorld.sub(50, 8)},mul=${'$'}{ArithmeticWorld.mul(-6, -7)},div=${'$'}{ArithmeticWorld.div(84, 2)}"
                }
            """,
            component("arithmetic"),
        )
        assertEquals("add=27,sub=42,mul=42,div=42", out)
    }

    @Test
    fun `counter resources run on Chicory`(@TempDir tempDir: Path) {
        val out = generateCompileRun(
            tempDir, "counter", "com.example.chicory.counter", "CounterWorldWorld.kt",
            """
            fun run(component: ComponentBackendFactory): String =
                CounterWorldWorld.withSession(component) {
                    CounterWorldWorld.Counter(10).use { c1 ->
                        c1.increment()
                        c1.increment()
                        c1.add(5)
                        val v1 = c1.getValue()
                        val c2 = CounterWorldWorld.Counter(25)
                        c2.increment()
                        CounterWorldWorld.Counter.merge(c1, c2).use { merged ->
                            "v1=${'$'}v1,v1After=${'$'}{c1.getValue()},c2Closed=${'$'}{c2.isClosed},merged=${'$'}{merged.getValue()}"
                        }
                    }
                }
            """,
            component("counter"),
        )
        assertEquals("v1=17,v1After=17,c2Closed=true,merged=43", out)
    }

    @Test
    fun `roundtrip types and host interface imports run on Chicory`(@TempDir tempDir: Path) {
        val out = generateCompileRun(
            tempDir, "roundtrip", "com.example.chicory.roundtrip", "RoundtripWorld.kt",
            """
            fun run(component: ComponentBackendFactory): String {
                val imports = object : RoundtripWorld.Imports {
                    override val envProvider = object : RoundtripWorld.EnvProvider {
                        override fun getMultiplier(): Int = 6
                        override fun formatTag(prefix: String, code: UInt): String = "${'$'}prefix#${'$'}code"
                    }
                }
                return RoundtripWorld.withSession(component, imports) {
                    val scaled = with(RoundtripWorld.MathApi) { scale(7) }
                    val tag = RoundtripWorld.describeCode("ERR", 404u)
                    val initial = RoundtripWorld.ItemSummary(
                        count = 10u,
                        perms = RoundtripWorld.Permissions.READ or RoundtripWorld.Permissions.EXECUTE,
                        prio = RoundtripWorld.Priority.LOW,
                    )
                    val afterAdd = RoundtripWorld.transformSummary(initial, RoundtripWorld.Action.AddCount(5u))
                    val afterPrio = RoundtripWorld.transformSummary(afterAdd, RoundtripWorld.Action.SetPriority(RoundtripWorld.Priority.HIGH))
                    val afterReset = RoundtripWorld.transformSummary(afterPrio, RoundtripWorld.Action.Reset)
                    check(afterPrio.perms.read && !afterPrio.perms.write && afterPrio.perms.execute)
                    check(afterReset.count == 0u && afterReset.perms == RoundtripWorld.Permissions.NONE && afterReset.prio == RoundtripWorld.Priority.LOW)
                    val even = (RoundtripWorld.checkEven(84) as WitResult.Ok).value.toString() + ":" + (RoundtripWorld.checkEven(-7) as WitResult.Err).error
                    val ping = "${'$'}{RoundtripWorld.pingResult(true) is WitResult.Ok}:${'$'}{RoundtripWorld.pingResult(false) is WitResult.Err}"
                    val optNone = RoundtripWorld.nestedOption(WitOption.None)
                    val optSomeNone = RoundtripWorld.nestedOption(WitOption.Some(WitOption.None))
                    val optSomeVal = RoundtripWorld.nestedOption(WitOption.Some(WitOption.Some(23)))
                    val opt = listOf(
                        optNone is WitOption.None,
                        optSomeNone is WitOption.Some && optSomeNone.value is WitOption.None,
                        (optSomeVal as WitOption.Some).value.getOrNull() == 123,
                    ).joinToString(",")
                    val u64 = RoundtripWorld.echoU64(ULong.MAX_VALUE) == ULong.MAX_VALUE
                    "scaled=${'$'}scaled,tag=${'$'}tag,count=${'$'}{afterPrio.count},prio=${'$'}{afterPrio.prio},even=${'$'}even,ping=${'$'}ping,opt=${'$'}opt,u64Max=${'$'}u64"
                }
            }
            """,
            component("roundtrip"),
        )
        assertEquals("scaled=42,tag=ERR#404,count=15,prio=HIGH,even=42:-7,ping=true:true,opt=true,true,true,u64Max=true", out)
    }
}
