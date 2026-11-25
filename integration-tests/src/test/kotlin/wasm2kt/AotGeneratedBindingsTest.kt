package wasm2kt

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import wasm2kt.tooling.aot.AotComponentBuild
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals

/**
 * End-to-end World DSL tests driven through the Phase 1 AOT backend (`AotComponentBuild`),
 * exercising arithmetic, guest resources (`counter`), complex WIT value types & host imports
 * (`roundtrip`), host-defined resources (`host-res`), and multi-memory string transcoding
 * (`composed-str`).
 */
class AotGeneratedBindingsTest {
    private fun fixture(name: String): Path =
        Paths.get(requireNotNull(javaClass.getResource("/fixtures/$name.wasm")).toURI())

    private fun buildCompileAndRun(
        tempDir: Path,
        fixtureName: String,
        pkg: String,
        harnessSource: String,
    ): String {
        val output = AotComponentBuild.run(
            component = fixture(fixtureName),
            componentName = fixtureName,
            packageName = pkg,
            workDir = tempDir.resolve("build"),
        )
        val harnessPath = tempDir.resolve("Harness.kt")
        Files.writeString(harnessPath, harnessSource.trimIndent())

        val compiledDir = tempDir.resolve("compiled-kotlin")
        Files.createDirectories(compiledDir)
        val sources = generatedSources(output.kotlinDir) + listOf(harnessPath.toString())
        val classpath = listOf(System.getProperty("java.class.path"), output.classesDir.toString()).joinToString(File.pathSeparator)
        val exit = K2JVMCompiler().exec(
            System.err,
            "-Xcontext-parameters", "-jvm-target", "17", "-no-stdlib",
            "-classpath", classpath,
            "-d", compiledDir.toString(),
            *sources.toTypedArray(),
        )
        assertEquals(ExitCode.OK, exit, "AOT bindings and harness for $fixtureName must compile")

        val urls = arrayOf(
            compiledDir.toUri().toURL(),
            output.classesDir.toUri().toURL(),
            output.resourcesDir.toUri().toURL(),
        )
        return URLClassLoader(urls, javaClass.classLoader).use { loader ->
            loader.loadClass("$pkg.HarnessKt").getDeclaredMethod("run").invoke(null) as String
        }
    }

    @Test
    fun `arithmetic world runs on AOT`(@TempDir tempDir: Path) {
        val pkg = "com.example.aot.bindings.arithmetic"
        val out = buildCompileAndRun(
            tempDir,
            "arithmetic",
            pkg,
            """
            package $pkg

            fun run(): String =
                withSession(ArithmeticComponent) {
                    "add=${'$'}{add(20, 7)},sub=${'$'}{sub(50, 8)},mul=${'$'}{mul(-6, -7)},div=${'$'}{div(84, 2)}"
                }
            """,
        )
        assertEquals("add=27,sub=42,mul=42,div=42", out)
    }

    @Test
    fun `counter resources run on AOT`(@TempDir tempDir: Path) {
        val pkg = "com.example.aot.bindings.counter"
        val out = buildCompileAndRun(
            tempDir,
            "counter",
            pkg,
            """
            package $pkg

            import $pkg.exports.example.counter.counter_api.Counter

            fun run(): String =
                withSession(CounterComponent) {
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
        )
        assertEquals("v1=17,v1After=17,c2Closed=true,merged=43", out)
    }

    @Test
    fun `roundtrip types and host interface imports run on AOT`(@TempDir tempDir: Path) {
        val pkg = "com.example.aot.bindings.roundtrip"
        val out = buildCompileAndRun(
            tempDir,
            "roundtrip",
            pkg,
            """
            package $pkg

            import $pkg.example.roundtrip.env_provider.EnvProvider
            import $pkg.exports.example.roundtrip.math_api.scale

            fun run(): String {
                val imports = imports(
                    exampleRoundtripEnvProvider = {
                        object : EnvProvider {
                            override fun getMultiplier(): Int = 6
                            override fun formatTag(prefix: String, code: UInt): String = "${'$'}prefix#${'$'}code"
                        }
                    },
                )
                return withSession(RoundtripComponent, imports) {
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
                    check(checkEven(84).getOrThrow() == 42u)
                    check(checkEven(-7).isErr && checkEven(84).isOk)
                    val u64 = echoU64(ULong.MAX_VALUE) == ULong.MAX_VALUE
                    "scaled=${'$'}scaled,tag=${'$'}tag,count=${'$'}{afterPrio.count},prio=${'$'}{afterPrio.prio},even=${'$'}even,ping=${'$'}ping,opt=${'$'}opt,u64Max=${'$'}u64"
                }
            }
            """,
        )
        assertEquals("scaled=42,tag=ERR#404,count=15,prio=HIGH,even=42:-7,ping=true:true,opt=true,true,true,u64Max=true", out)
    }

    @Test
    fun `host resources behave identically on AOT`(@TempDir tempDir: Path) {
        val pkg = "com.example.aot.bindings.hostres"
        val out = buildCompileAndRun(
            tempDir,
            "host-res",
            pkg,
            """
            package $pkg

            import $pkg.example.host_res.store.Blob
            import $pkg.example.host_res.store.Store
            import $pkg.exports.example.host_res.guest_api.Counter

            class HostBlob(val name: String, var bytes: ByteArray, private val log: MutableList<String>) : Blob {
                override fun size(): UInt = bytes.size.toUInt()
                override fun append(data: ByteArray) { bytes += data }
                override fun read(): ByteArray = bytes
                override fun close() { log += "close:${'$'}name" }
            }

            class HostTicket(private val value: UInt, private val log: MutableList<String>) : Ticket {
                override fun id(): UInt = value
                override fun close() { log += "close-ticket:${'$'}value" }
            }

            fun run(): String {
                val log = mutableListOf<String>()
                var onLog: (() -> Unit)? = null
                var created = 0
                val store = object : Store {
                    override fun newBlob(init: ByteArray): Blob =
                        HostBlob("g${'$'}{created++}", init, log).also { log += "new:${'$'}{it.name}:${'$'}{init.size}" }
                    override fun blobMerge(a: Blob, b: Blob): Blob {
                        a as HostBlob; b as HostBlob
                        log += "merge:${'$'}{a.name}+${'$'}{b.name}"
                        b.close()
                        return HostBlob("m", a.bytes + b.bytes, log)
                    }
                    override fun log(msg: String) { log += "log:${'$'}msg"; onLog?.invoke() }
                    override fun find(name: String): Blob? =
                        if (name == "none") null else HostBlob("found-${'$'}name", byteArrayOf(1, 2), log)
                }
                val worldImports = object : Imports {
                    override fun newTicket(id: UInt): Ticket = HostTicket(id, log).also { log += "new-ticket:${'$'}id" }
                }
                val imports = imports(exampleHostResStore = { store }, world = { worldImports })
                withSession(HostResComponent, imports) {
                    fun check(label: String, ok: Boolean) { log += if (ok) "ok:${'$'}label" else "FAILED:${'$'}label" }
                    check("makeAndMeasure", makeAndMeasure(byteArrayOf(1, 2), byteArrayOf(3)) == 3u)
                    val kept = keep(byteArrayOf(9, 9, 9)) as HostBlob
                    check("keep", kept.name == "g1" && kept.size() == 3u)
                    check("measure(kept)", measure(kept) == 3u)
                    check("consume(kept)", consume(kept) == 3u)
                    val local = HostBlob("local", byteArrayOf(1), log)
                    check("measure(local)", measure(local) == 1u)
                    check("measure(local) again", measure(local) == 1u)
                    check("consume(local)", consume(local) == 1u)
                    check("mergeNew", mergeNew(byteArrayOf(1), byteArrayOf(2, 3)).contentEquals(byteArrayOf(1, 2, 3)))
                    val found = lookup("x") as HostBlob?
                    check("lookup", found?.name == "found-x")
                    check("lookup(none)", lookup("none") == null)
                    val e = HostBlob("e", byteArrayOf(5), log)
                    check("echoBlob(ok)", echoBlob(e).getOrNull() === e)
                    check("echoBlob(err)", echoBlob(HostBlob("z", ByteArray(0), log)).errorOrNull() == "empty")
                    check("ticketId", ticketId(7u) == 7u)
                    val counter = Counter(5)
                    onLog = { log += if (runCatching { counter.close() }.isFailure) "drop-in-call:failed" else "drop-in-call:ok" }
                    counter.poke()
                    onLog = null
                }
                return log.joinToString(",")
            }
            """,
        )
        val expected = listOf(
            "new:g0:2", "close:g0", "ok:makeAndMeasure",
            "new:g1:3", "ok:keep", "ok:measure(kept)", "close:g1", "ok:consume(kept)",
            "ok:measure(local)", "ok:measure(local) again", "close:local", "ok:consume(local)",
            "new:g2:1", "new:g3:2", "merge:g2+g3", "close:g3", "close:m", "close:g2", "ok:mergeNew",
            "ok:lookup", "ok:lookup(none)", "ok:echoBlob(ok)", "close:z", "ok:echoBlob(err)",
            "new-ticket:7", "close-ticket:7", "ok:ticketId",
            "log:poke", "drop-in-call:failed",
        ).joinToString(",")
        assertEquals(expected, out)
    }

    @Test
    fun `composed string transcoders run on AOT World DSL`(@TempDir tempDir: Path) {
        val pkg = "com.example.aot.bindings.composedstr"
        val out = buildCompileAndRun(
            tempDir,
            "composed-str",
            pkg,
            """
            package $pkg

            fun run(): String =
                withSession(ComposedStrComponent) {
                    val sample = "hello-aé日🎌".repeat(25)
                    val list = listOf(10u, 20u, 30u, 42u)
                    val echoed = listOf(
                        echoUtf8Utf8(sample),
                        echoUtf8Utf16(sample),
                        echoUtf8Compact(sample),
                        echoUtf16Utf8(sample),
                        echoUtf16Utf16(sample),
                        echoUtf16Compact(sample),
                        echoCompactUtf8(sample),
                        echoCompactUtf16(sample),
                        echoCompactCompact(sample),
                    ).all { it == sample }
                    "${'$'}echoed,${'$'}{sum(list)},${'$'}{echoList(list)}"
                }
            """,
        )
        assertEquals("true,102,[10, 20, 30, 42]", out)
    }
}
