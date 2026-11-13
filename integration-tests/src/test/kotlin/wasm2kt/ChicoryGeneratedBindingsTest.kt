package wasm2kt

import com.github.ajalt.clikt.core.parse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import wasm2kt.cli.GenerateCommand
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
        GenerateCommand().parse(listOf("--wasm-tools-path", wasmToolsBin, "-o", tempDir.toString(), "-p", pkg, witPath.toString()))

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
            *generatedSources(tempDir).toTypedArray(),
        )
        check(generatedSources(tempDir).any { it.endsWith(worldFile) }) { "missing $worldFile" }
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
                withSession(component) {
                    "add=${'$'}{add(20, 7)},sub=${'$'}{sub(50, 8)},mul=${'$'}{mul(-6, -7)},div=${'$'}{div(84, 2)}"
                }
            """,
            component("arithmetic"),
        )
        assertEquals("add=27,sub=42,mul=42,div=42", out)
    }

    @Test
    fun `counter resources run on Chicory`(@TempDir tempDir: Path) {
        val out = generateCompileRun(
            tempDir, "counter", "com.example.chicory.counter", "CounterWorld.kt",
            """
            import com.example.chicory.counter.exports.example.counter.counter_api.Counter

            fun run(component: ComponentBackendFactory): String =
                withSession(component) {
                    Counter(10).use { c1 ->
                        c1.increment()
                        c1.increment()
                        c1.add(5)
                        val v1 = c1.getValue()
                        val c2 = Counter(25)
                        c2.increment()
                        Counter.merge(c1, c2).use { merged ->
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
            import com.example.chicory.roundtrip.example.roundtrip.env_provider.EnvProvider
            import com.example.chicory.roundtrip.exports.example.roundtrip.math_api.scale

            fun run(component: ComponentBackendFactory): String {
                val imports = imports(
                    exampleRoundtripEnvProvider = {
                        object : EnvProvider {
                            override fun getMultiplier(): Int = 6
                            override fun formatTag(prefix: String, code: UInt): String = "${'$'}prefix#${'$'}code"
                        }
                    },
                )
                return withSession(component, imports) {
                    val scaled = scale(7)
                    val tag = describeCode("ERR", 404u)
                    val initial = ItemSummary(
                        count = 10u,
                        perms = Permissions.READ or Permissions.EXECUTE,
                        prio = Priority.LOW,
                    )
                    val afterAdd = transformSummary(initial, Action.AddCount(5u))
                    val afterPrio = transformSummary(afterAdd, Action.SetPriority(Priority.HIGH))
                    val afterReset = transformSummary(afterPrio, Action.Reset)
                    check(afterPrio.perms.read && !afterPrio.perms.write && afterPrio.perms.execute)
                    check(afterReset.count == 0u && afterReset.perms == Permissions.NONE && afterReset.prio == Priority.LOW)
                    val even = (checkEven(84) as WitResult.Ok).value.toString() + ":" + (checkEven(-7) as WitResult.Err).error
                    val ping = "${'$'}{pingResult(true) is WitResult.Ok}:${'$'}{pingResult(false) is WitResult.Err}"
                    val optNone = nestedOption(WitOption.None)
                    val optSomeNone = nestedOption(WitOption.Some(null))
                    val optSomeVal = nestedOption(WitOption.Some(23))
                    val opt = listOf(
                        optNone is WitOption.None,
                        optSomeNone is WitOption.Some && optSomeNone.value == null,
                        (optSomeVal as WitOption.Some).value == 123,
                    ).joinToString(",")
                    // WitResult helpers (ADR-0006)
                    check(checkEven(84).getOrThrow() == 42u)
                    check(checkEven(-7).getOrNull() == null && checkEven(-7).errorOrNull() == -7)
                    check(checkEven(-7).isErr && checkEven(84).isOk)
                    check(checkEven(84).map { it * 2u }.getOrElse { 0u } == 84u)
                    check(checkEven(-7).fold({ "ok" }, { "err:${'$'}it" }) == "err:-7")
                    val thrown = runCatching { checkEven(-7).getOrThrow() }.exceptionOrNull()
                    check(thrown is wasm2kt.runtime.WitResultException && thrown.error == -7)
                    val u64 = echoU64(ULong.MAX_VALUE) == ULong.MAX_VALUE
                    "scaled=${'$'}scaled,tag=${'$'}tag,count=${'$'}{afterPrio.count},prio=${'$'}{afterPrio.prio},even=${'$'}even,ping=${'$'}ping,opt=${'$'}opt,u64Max=${'$'}u64"
                }
            }
            """,
            component("roundtrip"),
        )
        assertEquals("scaled=42,tag=ERR#404,count=15,prio=HIGH,even=42:-7,ping=true:true,opt=true,true,true,u64Max=true", out)
    }

    /** The async ABI (#16): `async func` is a `suspend fun`, `stream<T>` a `Flow<T>`, `future<T>` a `Deferred<T>` (ADR-0006). */
    @Test
    fun `async functions, streams and futures run on Chicory`(@TempDir tempDir: Path) {
        val out = generateCompileRun(
            tempDir, "async-stream", "com.example.chicory.asyncstream", "RootWorld.kt",
            """
            import kotlinx.coroutines.CompletableDeferred
            import kotlinx.coroutines.flow.asFlow
            import kotlinx.coroutines.flow.flowOf
            import kotlinx.coroutines.flow.take
            import kotlinx.coroutines.flow.toList
            import kotlinx.coroutines.runBlocking

            fun run(component: ComponentBackendFactory): String =
                withSession(component) {
                    runBlocking {
                        val doubled = double(21u)
                        val counted = countUp(5u).toList()
                        val summed = sum((1u..10u).asFlow())
                        val empty = sum(flowOf())
                        val roundTrip = sum(countUp(4u))
                        val firstTwo = countUp(100u).take(2).toList()
                        val later = later(41u).await()
                        val tenfold = timesTen(CompletableDeferred(7u))
                        val once = countUp(1u).let { s -> s.toList(); runCatching { s.toList() }.isFailure }
                        "doubled=${'$'}doubled,counted=${'$'}counted,summed=${'$'}summed,empty=${'$'}empty,roundTrip=${'$'}roundTrip," +
                            "firstTwo=${'$'}firstTwo,later=${'$'}later,tenfold=${'$'}tenfold,once=${'$'}once,after=${'$'}{double(1u)}"
                    }
                }
            """,
            component("async-stream"),
        )
        assertEquals(
            "doubled=42,counted=[0, 1, 2, 3, 4],summed=55,empty=0,roundTrip=6,firstTwo=[0, 1],later=42,tenfold=70,once=true,after=2",
            out,
        )
    }

    /**
     * A host `async func` import is a `suspend fun`, and the component's async exports suspend while
     * it runs (ADR-0019): calls overlap, and cancelling one cancels the host import it waits for.
     */
    @Test
    fun `suspending host imports, overlapping calls and cancellation run on Chicory`(@TempDir tempDir: Path) {
        val out = generateCompileRun(
            tempDir, "async-import", "com.example.chicory.asyncimport", "RootWorld.kt",
            """
            import kotlinx.coroutines.CompletableDeferred
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.awaitCancellation
            import kotlinx.coroutines.delay
            import kotlinx.coroutines.runBlocking
            import kotlinx.coroutines.withTimeoutOrNull
            import java.util.concurrent.atomic.AtomicInteger

            fun run(component: ComponentBackendFactory): String {
                val inFlight = AtomicInteger()
                var maxInFlight = 0
                val cancelled = CompletableDeferred<Unit>()
                val host = object : Imports {
                    override suspend fun fetch(x: UInt): UInt {
                        if (x == 999u) {
                            try { awaitCancellation() } finally { cancelled.complete(Unit) }
                        }
                        maxInFlight = maxOf(maxInFlight, inFlight.incrementAndGet())
                        delay(20)
                        inFlight.decrementAndGet()
                        return x * 10u
                    }
                }
                return withSession(component, imports { host }) {
                    runBlocking {
                        val one = fetchPlusOne(4u)
                        val two = fetchSyncPlusTwo(4u)
                        val three = blockingFetch(4u)
                        val overlapping = (1u..3u).map { async { fetchPlusOne(it) } }.awaitAll()
                        val timedOut = withTimeoutOrNull(100) { cancellable(999u) }
                        cancelled.await()
                        val after = cancellable(5u)
                        "one=${'$'}one,two=${'$'}two,three=${'$'}three,overlapping=${'$'}overlapping,maxInFlight=${'$'}maxInFlight," +
                            "timedOut=${'$'}timedOut,after=${'$'}after"
                    }
                }
            }
            """,
            component("async-import"),
        )
        assertEquals("one=41,two=42,three=43,overlapping=[11, 21, 31],maxInFlight=3,timedOut=null,after=50", out)
    }
}
