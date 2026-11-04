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
 * Component-defined resources passed as arguments (#76): a closed or moved object, or one of another
 * session, is refused before the call reaches the component, and an `own` argument is given up only
 * once the call returned. The same harness runs on both backends.
 */
class GuestResourceArgumentsE2ETest {
    private val wasmToolsBin: String = System.getenv("WASM_TOOLS_BIN")?.takeIf { it.isNotBlank() }
        ?: Paths.get(System.getProperty("user.home"), ".cargo", "bin", "wasm-tools").takeIf { Files.isExecutable(it) }?.toString()
        ?: "wasm-tools"

    private val pkg = "com.example.guestres"

    private val harness = """
        package $pkg

        import java.nio.file.Path
        import wasm2kt.runtime.ComponentBackendFactory
        import $pkg.exports.example.counter.counter_api.Counter

        private fun failure(block: () -> Any?): String =
            runCatching { block() }.exceptionOrNull()?.let { "${'$'}{it.javaClass.simpleName}: ${'$'}{it.message}" } ?: "no error"

        fun exercise(openSession: () -> Session): String {
            val out = mutableListOf<String>()
            openSession().use { a ->
                openSession().use { b ->
                    context(a) {
                        // A closed resource passed as borrow: refused, and the own argument stays usable.
                        val closed = Counter(1).also { it.close() }
                        val kept = Counter(2)
                        out += "merge(closed, kept): " + failure { Counter.merge(closed, kept) }
                        out += "kept after: " + kept.getValue() + ", closed=" + kept.isClosed
                        // A successful call gives up the own argument.
                        val keeper = Counter(3)
                        Counter.merge(keeper, kept).use { merged -> out += "merged: " + merged.getValue() + ", kept closed=" + kept.isClosed }
                        keeper.close()
                    }
                    // Both sessions number their handles from 1: the resource of session b is refused in a.
                    val fromB = context(b) { Counter(99) }
                    context(a) {
                        val mine = Counter(10)
                        out += "merge(mine, fromB): " + failure { Counter.merge(mine, fromB) }
                        out += "merge(fromB, mine): " + failure { Counter.merge(fromB, mine) }
                        out += "mine after: " + mine.getValue()
                        mine.close()
                    }
                    context(b) { out += "fromB after: " + fromB.getValue(); fromB.close() }
                }
            }
            return out.joinToString("\n")
        }

        fun runWasmtime(wasm: Path): String = exercise { openSession(wasm) }

        fun runChicory(component: ComponentBackendFactory): String = exercise { openSession(component) }
    """.trimIndent()

    private val expected = listOf(
        "merge(closed, kept): IllegalStateException: Resource Counter(handle=1) is already closed or moved",
        "kept after: 2, closed=false",
        "merged: 5, kept closed=true",
        "merge(mine, fromB): IllegalStateException: Resource Counter(handle=1) belongs to another session",
        "merge(fromB, mine): IllegalStateException: Resource Counter(handle=1) belongs to another session",
        "mine after: 10",
        "fromB after: 99",
    ).joinToString("\n")

    private fun compile(tempDir: Path): ClassLoader {
        val wit = Paths.get(requireNotNull(javaClass.getResource("/fixtures/counter.wit")).toURI())
        GenerateCommand().parse(listOf("--wasm-tools-path", wasmToolsBin, "-o", tempDir.toString(), "-p", pkg, wit.toString()))
        Files.writeString(tempDir.resolve(pkg.replace('.', '/')).resolve("Harness.kt"), harness)
        val classes = tempDir.resolve("classes")
        Files.createDirectories(classes)
        val exit = org.jetbrains.kotlin.cli.jvm.K2JVMCompiler().exec(
            System.err,
            *(listOf("-Xcontext-parameters", "-jvm-target", "21", "-no-stdlib", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()) + generatedSources(tempDir)).toTypedArray(),
        )
        assertEquals(org.jetbrains.kotlin.cli.common.ExitCode.OK, exit, "Generated bindings + harness must compile")
        return java.net.URLClassLoader(arrayOf(classes.toUri().toURL()), javaClass.classLoader)
    }

    @Test
    fun `guest resource arguments are checked on Wasmtime`(@TempDir tempDir: Path) {
        val wasm = Paths.get(requireNotNull(javaClass.getResource("/fixtures/counter.wasm")).toURI())
        val result = compile(tempDir).loadClass("$pkg.HarnessKt").getDeclaredMethod("runWasmtime", Path::class.java).invoke(null, wasm)
        assertEquals(expected, result)
    }

    @Test
    fun `guest resource arguments are checked on Chicory`(@TempDir tempDir: Path) {
        val component = ChicoryComponent.fromDirectory(Paths.get("runtime-chicory/src/test/resources/components/counter").toAbsolutePath())
        val result = compile(tempDir).loadClass("$pkg.HarnessKt").getDeclaredMethod("runChicory", ComponentBackendFactory::class.java).invoke(null, component)
        assertEquals(expected, result)
    }
}
