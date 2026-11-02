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
 * Host-defined resources (#14, ADR-0008): the host implements `store.blob` and the world-level
 * `ticket`, the component creates, borrows, moves and drops them. The same harness runs on both
 * backends and must produce the same event log, so this doubles as a differential test.
 * The fixture is built from fixtures/src/host-res.
 */
class HostResourceE2ETest {
    private val wasmToolsBin: String = System.getenv("WASM_TOOLS_BIN")?.takeIf { it.isNotBlank() }
        ?: Paths.get(System.getProperty("user.home"), ".cargo", "bin", "wasm-tools").takeIf { Files.isExecutable(it) }?.toString()
        ?: "wasm-tools"

    private val pkg = "com.example.hostres"

    private val harness = """
        package $pkg

        import java.nio.file.Path
        import wasm2kt.runtime.ComponentBackendFactory
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

        class Harness {
            val log = mutableListOf<String>()
            var onLog: (() -> Unit)? = null
            private var created = 0

            private val store = object : Store {
                    override fun newBlob(init: ByteArray): Blob =
                        HostBlob("g${'$'}{created++}", init, log).also { log += "new:${'$'}{it.name}:${'$'}{init.size}" }
                    override fun blobMerge(a: Blob, b: Blob): Blob {
                        a as HostBlob; b as HostBlob
                        log += "merge:${'$'}{a.name}+${'$'}{b.name}"
                        b.close() // the host owns b now
                        return HostBlob("m", a.bytes + b.bytes, log)
                    }
                    override fun log(msg: String) { log += "log:${'$'}msg"; onLog?.invoke() }
                    override fun find(name: String): Blob? =
                        if (name == "none") null else HostBlob("found-${'$'}name", byteArrayOf(1, 2), log)
                }
            private val worldImports = object : Imports {
                override fun newTicket(id: UInt): Ticket = HostTicket(id, log).also { log += "new-ticket:${'$'}id" }
            }
            val imports = imports(exampleHostResStore = { store }, world = { worldImports })

            context(session: Session)
            fun exercise() {
                fun check(label: String, ok: Boolean) { log += if (ok) "ok:${'$'}label" else "FAILED:${'$'}label" }

                // The guest creates a host blob, uses it and drops it.
                check("makeAndMeasure", makeAndMeasure(byteArrayOf(1, 2), byteArrayOf(3)) == 3u)

                // own<blob> returned to the host, then borrowed and moved back.
                val kept = keep(byteArrayOf(9, 9, 9)) as HostBlob
                check("keep", kept.name == "g1" && kept.size() == 3u)
                check("measure(kept)", measure(kept) == 3u)
                check("consume(kept)", consume(kept) == 3u)

                // Objects made by the host itself.
                val local = HostBlob("local", byteArrayOf(1), log)
                check("measure(local)", measure(local) == 1u)
                check("measure(local) again", measure(local) == 1u)
                check("consume(local)", consume(local) == 1u)

                // Static functions with borrow + own parameters.
                check("mergeNew", mergeNew(byteArrayOf(1), byteArrayOf(2, 3)).contentEquals(byteArrayOf(1, 2, 3)))

                // Host resources nested in option / result.
                val found = lookup("x") as HostBlob?
                check("lookup", found?.name == "found-x")
                check("lookup(none)", lookup("none") == null)
                val e = HostBlob("e", byteArrayOf(5), log)
                check("echoBlob(ok)", echoBlob(e).getOrNull() === e)
                check("echoBlob(err)", echoBlob(HostBlob("z", ByteArray(0), log)).errorOrNull() == "empty")

                // A world-level host resource.
                check("ticketId", ticketId(7u) == 7u)

                // A component-defined resource that calls the host while borrowed: the host cannot
                // drop it from inside that call.
                val counter = Counter(5)
                onLog = { log += if (runCatching { counter.close() }.isFailure) "drop-in-call:failed" else "drop-in-call:ok" }
                counter.poke()
                onLog = null
            }
        }

        fun runWasmtime(wasm: Path): String {
            val h = Harness()
            withSession(wasm, h.imports) { h.exercise() }
            return h.log.joinToString(",")
        }

        fun runWasmtimeJni(wasm: Path): String {
            WasmtimeSession.useJniBackend()
            try {
                return runWasmtime(wasm)
            } finally {
                WasmtimeSession.useFfmBackend()
            }
        }

        fun runChicory(component: ComponentBackendFactory): String {
            val h = Harness()
            withSession(component, h.imports) { h.exercise() }
            return h.log.joinToString(",")
        }
    """.trimIndent()

    private fun compile(tempDir: Path): ClassLoader {
        val wit = Paths.get(requireNotNull(javaClass.getResource("/fixtures/host-res.wit")).toURI())
        Wasm2KtCommand().parse(listOf("--wasm-tools-path", wasmToolsBin, "-o", tempDir.toString(), "-p", pkg, wit.toString()))
        val pkgDir = tempDir.resolve(pkg.replace('.', '/'))
        Files.writeString(pkgDir.resolve("Harness.kt"), harness)
        val classes = tempDir.resolve("classes")
        Files.createDirectories(classes)
        val sources = generatedSources(tempDir)
        val exit = org.jetbrains.kotlin.cli.jvm.K2JVMCompiler().exec(
            System.err,
            *(listOf("-Xcontext-parameters", "-jvm-target", "21", "-no-stdlib", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()) + sources).toTypedArray(),
        )
        assertEquals(org.jetbrains.kotlin.cli.common.ExitCode.OK, exit, "Generated bindings + harness must compile")
        return java.net.URLClassLoader(arrayOf(classes.toUri().toURL()), javaClass.classLoader)
    }

    private val expected = listOf(
        "new:g0:2", "close:g0", "ok:makeAndMeasure",
        "new:g1:3", "ok:keep", "ok:measure(kept)", "close:g1", "ok:consume(kept)",
        "ok:measure(local)", "ok:measure(local) again", "close:local", "ok:consume(local)",
        "new:g2:1", "new:g3:2", "merge:g2+g3", "close:g3", "close:m", "close:g2", "ok:mergeNew",
        "ok:lookup", "ok:lookup(none)", "ok:echoBlob(ok)", "close:z", "ok:echoBlob(err)",
        "new-ticket:7", "close-ticket:7", "ok:ticketId",
        "log:poke", "drop-in-call:failed",
    ).joinToString(",")

    @Test
    fun `host resources behave the same on Wasmtime`(@TempDir tempDir: Path) {
        val loader = compile(tempDir)
        val wasm = Paths.get(requireNotNull(javaClass.getResource("/fixtures/host-res.wasm")).toURI())
        val result = loader.loadClass("$pkg.HarnessKt").getDeclaredMethod("runWasmtime", Path::class.java).invoke(null, wasm)
        assertEquals(expected, result)
    }

    @Test
    fun `host resources behave the same on Wasmtime via JNI`(@TempDir tempDir: Path) {
        val loader = compile(tempDir)
        val wasm = Paths.get(requireNotNull(javaClass.getResource("/fixtures/host-res.wasm")).toURI())
        val result = loader.loadClass("$pkg.HarnessKt").getDeclaredMethod("runWasmtimeJni", Path::class.java).invoke(null, wasm)
        assertEquals(expected, result)
    }

    @Test
    fun `host resources behave the same on Chicory`(@TempDir tempDir: Path) {
        val loader = compile(tempDir)
        val component = ChicoryComponent.fromDirectory(Paths.get("runtime-chicory/src/test/resources/components/host-res").toAbsolutePath())
        val result = loader.loadClass("$pkg.HarnessKt").getDeclaredMethod("runChicory", ComponentBackendFactory::class.java).invoke(null, component)
        assertEquals(expected, result)
    }
}
