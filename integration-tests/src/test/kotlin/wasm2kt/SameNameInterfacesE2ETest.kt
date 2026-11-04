package wasm2kt

import com.github.ajalt.clikt.core.parse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import wasm2kt.cli.Wasm2KtCommand
import wasm2kt.runtime.ComponentBackendFactory
import wasm2kt.runtime.Linker
import wasm2kt.runtime.WitWriter
import wasm2kt.runtime.chicory.ChicoryComponent
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Interfaces with the same short name (#32, ADR-0011): `example:http/types` and `example:fs/types`
 * are both imported (implemented by the host) and exported (implemented by the guest). Keys, Kotlin
 * packages and `imports(...)` parameters must keep all four apart, on both backends.
 * The fixture is built from fixtures/src/same-name.
 */
class SameNameInterfacesE2ETest {
    private val wasmToolsBin: String = System.getenv("WASM_TOOLS_BIN")?.takeIf { it.isNotBlank() }
        ?: Paths.get(System.getProperty("user.home"), ".cargo", "bin", "wasm-tools").takeIf { Files.isExecutable(it) }?.toString()
        ?: "wasm-tools"

    private val pkg = "com.example.samename"

    private val harness = """
        package $pkg

        import java.nio.file.Path
        import wasm2kt.runtime.ComponentBackendFactory
        import wasm2kt.runtime.Linker
import wasm2kt.runtime.WitWriter
        import $pkg.example.fs.types.ErrorCode as FsErrorCode
        import $pkg.example.fs.types.Types as FsTypes
        import $pkg.example.http.types.ErrorCode as HttpErrorCode
        import $pkg.example.http.types.Tally as HostTally
        import $pkg.example.http.types.Types as HttpTypes
        import $pkg.exports.example.fs.types.ErrorCode as GuestFsErrorCode
        import $pkg.exports.example.fs.types.describe as describeFs
        import $pkg.exports.example.http.types.ErrorCode as GuestHttpErrorCode
        import $pkg.exports.example.http.types.Tally as GuestTally
        import $pkg.exports.example.http.types.describe as describeHttp

        class Tally(private var value: UInt) : HostTally {
            override fun get(): UInt { value += 10u; return value }
            override fun close() {}
        }

        val imports = imports(
            exampleHttpTypes = {
                object : HttpTypes {
                    override fun newTally(start: UInt): HostTally = Tally(start)
                    override fun describe(e: HttpErrorCode): String = "host-http:${'$'}{e.code}"
                }
            },
            exampleFsTypes = {
                object : FsTypes {
                    override fun describe(e: FsErrorCode): String = when (e) {
                        is FsErrorCode.Missing -> "host-fs:missing:${'$'}{e.value}"
                        FsErrorCode.Denied -> "host-fs:denied"
                    }
                }
            },
        )

        context(session: Session)
        fun exercise(): String {
            val run = run(7u)
            val http = describeHttp(GuestHttpErrorCode(3u))
            val fs = describeFs(GuestFsErrorCode.Missing("x")) + "," + describeFs(GuestFsErrorCode.Denied)
            val tally = GuestTally(5u).use { it.get(); it.get() }
            return "${'$'}run;${'$'}http;${'$'}fs;tally=${'$'}tally"
        }

        fun runWasmtime(wasm: Path): String = withSession(wasm, imports) { exercise() }

        fun runChicory(component: ComponentBackendFactory): String = withSession(component, imports) { exercise() }

        /** Opens a session with an empty linker: the missing imports must be reported before instantiation. */
        fun openWithoutImports(component: ComponentBackendFactory) {
            openSession(component, Linker()).close()
        }
    """.trimIndent()

    private fun compile(tempDir: Path): ClassLoader {
        val wit = Paths.get(requireNotNull(javaClass.getResource("/fixtures/same-name.wit")).toURI())
        Wasm2KtCommand().parse(listOf("--wasm-tools-path", wasmToolsBin, "-o", tempDir.toString(), "-p", pkg, wit.toString()))
        val pkgDir = tempDir.resolve(pkg.replace('.', '/'))
        // One package per interface and direction (ADR-0011).
        for (file in listOf("example/http/types/Types.kt", "example/fs/types/Types.kt", "exports/example/http/types/Types.kt", "exports/example/fs/types/Types.kt")) {
            assertTrue(Files.exists(pkgDir.resolve(file)), "missing $file")
        }
        Files.writeString(pkgDir.resolve("Harness.kt"), harness)
        val classes = tempDir.resolve("classes")
        Files.createDirectories(classes)
        val exit = org.jetbrains.kotlin.cli.jvm.K2JVMCompiler().exec(
            System.err,
            *(listOf("-Xcontext-parameters", "-jvm-target", "21", "-no-stdlib", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()) + generatedSources(tempDir)).toTypedArray(),
        )
        assertEquals(org.jetbrains.kotlin.cli.common.ExitCode.OK, exit, "Generated bindings + harness must compile")
        return java.net.URLClassLoader(arrayOf(classes.toUri().toURL()), javaClass.classLoader)
    }

    private fun chicoryComponent() =
        ChicoryComponent.fromDirectory(Paths.get("runtime-chicory/src/test/resources/components/same-name").toAbsolutePath())

    private val expected =
        "host-http:7|host-fs:missing:f7|host-fs:denied|tally=17;guest-http:3;guest-fs:missing:x,guest-fs:denied;tally=7"

    @Test
    fun `same short names stay apart on Wasmtime`(@TempDir tempDir: Path) {
        val loader = compile(tempDir)
        val wasm = Paths.get(requireNotNull(javaClass.getResource("/fixtures/same-name.wasm")).toURI())
        assertEquals(expected, loader.loadClass("$pkg.HarnessKt").getDeclaredMethod("runWasmtime", Path::class.java).invoke(null, wasm))
    }

    @Test
    fun `same short names stay apart on Chicory`(@TempDir tempDir: Path) {
        val loader = compile(tempDir)
        val result = loader.loadClass("$pkg.HarnessKt").getDeclaredMethod("runChicory", ComponentBackendFactory::class.java)
            .invoke(null, chicoryComponent())
        assertEquals(expected, result)
    }

    @Test
    fun `missing host imports are reported when the session opens`(@TempDir tempDir: Path) {
        val loader = compile(tempDir)
        val e = assertThrows<InvocationTargetException> {
            loader.loadClass("$pkg.HarnessKt").getDeclaredMethod("openWithoutImports", ComponentBackendFactory::class.java)
                .invoke(null, chicoryComponent())
        }.targetException
        assertTrue(e is IllegalArgumentException, "got $e")
        val message = e.message.orEmpty()
        assertTrue("example:http/types@0.1.0 (" in message && "example:fs/types@0.1.0 (describe)" in message, message)
    }

    @Test
    fun `ambiguous short names are rejected by both backends`() {
        val wasm = Paths.get(requireNotNull(javaClass.getResource("/fixtures/same-name.wasm")).toURI())
        val linker = Linker().apply {
            define("example:http/types@0.1.0#describe") { _, results -> results.string("h") }
            define("example:fs/types@0.1.0#describe") { _, results -> results.string("f") }
            define("example:http/types@0.1.0#[constructor]tally") { _, results -> results.int(1) }
            define("example:http/types@0.1.0#[method]tally.get") { _, results -> results.int(0) }
            define("example:http/types@0.1.0#[resource-drop]tally") { _, _ -> }
        }
        // describe(error-code { code: 1 })
        fun httpError() = WitWriter().apply { int(1) }

        val chicory = chicoryComponent().instantiate(linker)
        chicory.use { c ->
            for (spec in listOf("example:http/types@0.1.0#describe", "example:http/types#describe")) {
                assertEquals("guest-http:1", c.invoke(c.resolve(spec), httpError().toReader()).string())
            }
            for (spec in listOf("types#describe", "describe")) {
                val e = assertThrows<IllegalArgumentException> { c.resolve(spec) }
                assertTrue("ambiguous" in e.message.orEmpty(), e.message)
            }
        }

        val bridge = wasm2kt.runtime.FfmWasmtimeBridge.getDefault()
        val session = bridge.openSession(wasm, linker)
        try {
            val index = bridge.resolve(session, "example:http/types@0.1.0#describe")
            assertEquals("guest-http:1", bridge.invoke(session, index, httpError()).string())
            for (spec in listOf("types#describe", "describe")) {
                val e = assertThrows<IllegalArgumentException> { bridge.resolve(session, spec) }
                assertTrue("ambiguous" in e.message.orEmpty(), e.message)
            }
        } finally {
            bridge.sessionClose(session)
        }
    }
}
