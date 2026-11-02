package wasm2kt

import com.github.ajalt.clikt.core.parse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import wasm2kt.cli.Wasm2KtCommand
import wasm2kt.runtime.ComponentBackendFactory
import wasm2kt.runtime.Linker
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
        import $pkg.example.fs.types.ErrorCode as FsErrorCode
        import $pkg.example.fs.types.Types as FsTypes
        import $pkg.example.http.types.ErrorCode as HttpErrorCode
        import $pkg.example.http.types.Tally as HostTally
        import $pkg.example.http.types.Types as HttpTypes
        import $pkg.exports.example.fs.types.Types as GuestFs
        import $pkg.exports.example.fs.types.ErrorCode as GuestFsErrorCode
        import $pkg.exports.example.fs.types.describe as describeFs
        import $pkg.exports.example.http.types.Types as GuestHttp
        import $pkg.exports.example.http.types.ErrorCode as GuestHttpErrorCode
        import $pkg.exports.example.http.types.Tally as GuestTally
        import $pkg.exports.example.http.types.describe as describeHttp

        class Tally(private var value: UInt) : HostTally {
            override fun get(): UInt { value += 10u; return value }
            override fun close() {}
        }

        val imports = SameNameWorld.imports(
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

        context(session: SameNameWorld.Session)
        fun exercise(): String {
            val run = SameNameWorld.run(7u)
            val http = GuestHttp.describeHttp(GuestHttpErrorCode(3u))
            val fs = GuestFs.describeFs(GuestFsErrorCode.Missing("x")) + "," + GuestFs.describeFs(GuestFsErrorCode.Denied)
            val tally = GuestTally(5u).use { it.get(); it.get() }
            return "${'$'}run;${'$'}http;${'$'}fs;tally=${'$'}tally"
        }

        fun runWasmtime(wasm: Path): String = SameNameWorld.withSession(wasm, imports) { exercise() }

        fun runChicory(component: ComponentBackendFactory): String = SameNameWorld.withSession(component, imports) { exercise() }

        /** Opens a session with an empty linker: the missing imports must be reported before instantiation. */
        fun openWithoutImports(component: ComponentBackendFactory) {
            SameNameWorld.openSession(component, Linker()).close()
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
            define("example:http/types@0.1.0#describe") { JsonPrimitive("h") }
            define("example:fs/types@0.1.0#describe") { JsonPrimitive("f") }
            define("example:http/types@0.1.0#[constructor]tally") { JsonPrimitive(1) }
            define("example:http/types@0.1.0#[method]tally.get") { JsonPrimitive(0) }
            define("example:http/types@0.1.0#[resource-drop]tally") { kotlinx.serialization.json.JsonNull }
        }
        val httpError = JsonArray(listOf(JsonObject(mapOf("code" to JsonPrimitive(1)))))

        chicoryComponent().instantiate(linker).use { c ->
            assertEquals("guest-http:1", c.invoke("example:http/types@0.1.0#describe", httpError).jsonPrimitive.content)
            assertEquals("guest-http:1", c.invoke("example:http/types#describe", httpError).jsonPrimitive.content)
            for (spec in listOf("types#describe", "describe")) {
                val e = assertThrows<IllegalArgumentException> { c.invoke(spec, httpError) }
                assertTrue("ambiguous" in e.message.orEmpty(), e.message)
            }
        }

        val bridge = wasm2kt.runtime.FfmWasmtimeBridge.getDefault()
        val session = bridge.openSession(wasm, linker)
        try {
            val ok = bridge.sessionInvoke(session, "example:http/types@0.1.0#describe", httpError.toString())
            assertTrue("guest-http:1" in ok, ok)
            for (spec in listOf("types#describe", "describe")) {
                val response = bridge.sessionInvoke(session, spec, httpError.toString())
                assertTrue("ambiguous" in response, response)
            }
        } finally {
            bridge.sessionClose(session)
        }
    }
}
