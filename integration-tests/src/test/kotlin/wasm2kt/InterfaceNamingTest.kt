package wasm2kt

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import wasm2kt.ast.WitResolve
import wasm2kt.cli.Wasm2KtCommand
import wasm2kt.generator.WitToKotlinPoetConverter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How WIT interfaces map to Kotlin packages, names and keys (ADR-0011). */
class InterfaceNamingTest {
    private val wasmToolsBin: String = System.getenv("WASM_TOOLS_BIN")?.takeIf { it.isNotBlank() }
        ?: Paths.get(System.getProperty("user.home"), ".cargo", "bin", "wasm-tools").takeIf { Files.isExecutable(it) }?.toString()
        ?: "wasm-tools"

    private val base = "com.example.naming"

    private fun resolve(tempDir: Path, wit: String): WitResolve {
        val witFile = tempDir.resolve("input.wit")
        Files.writeString(witFile, wit.trimIndent())
        return WitResolve.parse(Wasm2KtCommand.extractWitJsonViaWasmTools(wasmToolsBin, witFile))
    }

    /** Generates [wit] into `tempDir/out`, checks that it compiles, and returns the files by relative path. */
    private fun generateAndCompile(tempDir: Path, wit: String): Map<String, String> {
        val files = WitToKotlinPoetConverter(resolve(tempDir, wit), base).generate()
        val out = tempDir.resolve("out")
        files.forEach { it.writeTo(out) }
        val exit = org.jetbrains.kotlin.cli.jvm.K2JVMCompiler().exec(
            System.err,
            *(listOf("-Xcontext-parameters", "-jvm-target", "21", "-no-stdlib", "-classpath", System.getProperty("java.class.path"), "-d", tempDir.resolve("classes").toString()) + generatedSources(out)).toTypedArray(),
        )
        assertEquals(org.jetbrains.kotlin.cli.common.ExitCode.OK, exit, "Generated bindings must compile")
        return files.associate { file ->
            val relativePackage = file.packageName.removePrefix(base).removePrefix(".").replace('.', '/')
            (if (relativePackage.isEmpty()) "" else "$relativePackage/") + file.name to file.content
        }
    }

    @Test
    fun `multi-word and keyword segments become snake case with a trailing underscore`(@TempDir tempDir: Path) {
        val files = generateAndCompile(
            tempDir,
            """
            package my-org:app@0.1.0;

            world app {
                import my-org:in/outgoing-handler@0.1.0;
                import my-org:in/object@0.1.0;
            }

            package my-org:in@0.1.0 {
                interface outgoing-handler {
                    handle: func(url: string) -> u32;
                }
                interface object {
                    name: func() -> string;
                }
            }
            """,
        )
        val handler = files.getValue("my_org/in_/outgoing_handler/OutgoingHandler")
        assertTrue(handler.startsWith("package $base.my_org.in_.outgoing_handler\n"), handler)
        assertTrue("define(\"my-org:in/outgoing-handler@0.1.0#handle\")" in handler, handler)
        assertTrue(files.getValue("my_org/in_/object_/Object").startsWith("package $base.my_org.in_.object_\n"))
        val world = files.getValue("AppWorld")
        assertTrue(Regex("""fun imports\(myOrgInOutgoingHandler: \(\) -> OutgoingHandler, myOrgInObject: \(\) -> Object\)""").containsMatchIn(world), world)
    }

    @Test
    fun `an interface named like one of its types gets the Interface suffix`(@TempDir tempDir: Path) {
        val files = generateAndCompile(
            tempDir,
            """
            package example:io@0.1.0;

            interface error {
                resource error {
                    to-debug-string: func() -> string;
                }
                last: func() -> option<error>;
            }

            world io {
                import error;
            }
            """,
        )
        val error = files.getValue("example/io/error/Error")
        assertTrue("interface Error : WitHostResource" in error, error)
        assertTrue("interface ErrorInterface {" in error, error)
        assertTrue("fun Linker.add(`impl`: ErrorInterface)" in error, error)
    }

    @Test
    fun `inline interfaces keep their world name for the key, package and parameter`(@TempDir tempDir: Path) {
        val files = generateAndCompile(
            tempDir,
            """
            package example:inline@0.1.0;

            world inline {
                import logger: interface {
                    log: func(msg: string);
                }
                export run: func() -> u32;
            }
            """,
        )
        val logger = files.getValue("logger/Logger")
        assertTrue(logger.startsWith("package $base.logger\n"), logger)
        assertTrue("define(\"logger#log\")" in logger, logger)
        assertTrue(Regex("""fun imports\(logger: \(\) -> Logger\)""").containsMatchIn(files.getValue("InlineWorld")))
    }

    @Test
    fun `different versions of one package are rejected`(@TempDir tempDir: Path) {
        val resolve = resolve(
            tempDir,
            """
            package example:app@0.1.0;

            world app {
                import example:dep/a@1.0.0;
                import example:dep/b@2.0.0;
            }

            package example:dep@1.0.0 {
                interface a { f: func(); }
            }

            package example:dep@2.0.0 {
                interface b { g: func(); }
            }
            """,
        )
        val e = assertThrows<IllegalArgumentException> { WitToKotlinPoetConverter(resolve, base).generate() }
        assertTrue("Package 'example:dep' is used at several versions (1.0.0, 2.0.0)" in e.message.orEmpty(), e.message)
    }

    @Test
    fun `an inline interface that takes a qualified interface's parameter name is rejected`(@TempDir tempDir: Path) {
        val resolve = resolve(
            tempDir,
            """
            package example:app@0.1.0;

            world app {
                import example:http/types@0.1.0;
                import example-http-types: interface {
                    f: func();
                }
            }

            package example:http@0.1.0 {
                interface types { g: func(); }
            }
            """,
        )
        val e = assertThrows<IllegalArgumentException> { WitToKotlinPoetConverter(resolve, base).generate() }
        assertTrue("exampleHttpTypes" in e.message.orEmpty(), e.message)
    }
}
