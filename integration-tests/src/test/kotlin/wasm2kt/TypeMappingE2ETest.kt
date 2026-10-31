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
 * ADR-0006 type mappings (`option<T>` -> `T?` / nested `WitOption`, `list<u8>` -> `ByteArray`,
 * `char` -> `CodePoint`, world naming), exercised end to end on both backends with one harness.
 * The fixture is built from fixtures/src/types.
 */
class TypeMappingE2ETest {
    private val wasmToolsBin: String = System.getenv("WASM_TOOLS_BIN")?.takeIf { it.isNotBlank() }
        ?: Paths.get(System.getProperty("user.home"), ".cargo", "bin", "wasm-tools").takeIf { Files.isExecutable(it) }?.toString()
        ?: "wasm-tools"

    private val pkg = "com.example.typemapping"

    private val harness = """
        package $pkg

        import java.nio.file.Path
        import wasm2kt.runtime.ComponentBackendFactory

        context(session: TypesWorld.Session)
        fun exercise(): String {
            val out = mutableListOf<String>()
            fun check(label: String, ok: Boolean) { out += if (ok) label else "FAILED:${'$'}label" }

            // option<T> -> T?
            check("echoName(null)", TypesWorld.echoName(null) == null)
            check("echoName(chiko)", TypesWorld.echoName("chiko") == "chiko")
            check("maybeLen", TypesWorld.maybeLen("日本🎌") == 3u && TypesWorld.maybeLen(null) == null)
            val len = TypesWorld.maybeLen("abc")?.toInt() ?: -1
            check("elvis", len == 3)

            // list<u8> -> ByteArray
            val bytes = byteArrayOf(0, 1, 127, -128, -1)
            check("echoBytes", TypesWorld.echoBytes(bytes).contentEquals(bytes))
            check("reverseBytes", TypesWorld.reverseBytes(bytes).contentEquals(bytes.reversedArray()))
            check("emptyBytes", TypesWorld.echoBytes(ByteArray(0)).isEmpty())

            // char -> CodePoint, including a code point outside the BMP
            val flag = CodePoint.of("🎌")
            check("echoChar(bmp)", TypesWorld.echoChar(CodePoint.of('あ')) == CodePoint.of('あ'))
            check("echoChar(non-bmp)", TypesWorld.echoChar(flag) == flag && TypesWorld.echoChar(flag).toString() == "🎌")
            check("nextChar", TypesWorld.nextChar(CodePoint.of('a')) == CodePoint.of('b'))

            // records and variants holding ByteArray compare by content
            val blob = TypesWorld.Blob(name = "n", data = bytes, tag = 7u)
            check("echoBlob", TypesWorld.echoBlob(blob) == blob)
            check("echoBlob(noTag)", TypesWorld.echoBlob(blob.copy(tag = null)).tag == null)
            check("blobHash", TypesWorld.echoBlob(blob).hashCode() == blob.hashCode())
            check("packetBytes", TypesWorld.echoPacket(TypesWorld.Packet.Bytes(bytes)) == TypesWorld.Packet.Bytes(bytes))
            check("packetEmpty", TypesWorld.echoPacket(TypesWorld.Packet.Empty) == TypesWorld.Packet.Empty)
            check("packetText", TypesWorld.echoPacket(TypesWorld.Packet.Text("t")) == TypesWorld.Packet.Text("t"))

            // list<option<T>> -> List<T?>
            check("compact", TypesWorld.compact(listOf(1, null, 3)) == listOf(1, 3))
            check("echoOptionalList", TypesWorld.echoOptionalList(listOf(null, 5, null)) == listOf(null, 5, null))

            // option<option<T>> -> WitOption<T?>: outer none, some(none), some(some)
            check("nested(none)", TypesWorld.nested(WitOption.None) == WitOption.None)
            check("nested(some(none))", TypesWorld.nested(WitOption.Some(null)) == WitOption.Some(null))
            check("nested(some(some))", TypesWorld.nested(WitOption.Some("x")) == WitOption.Some("x"))
            return out.joinToString(",")
        }

        fun runWasmtime(wasm: Path): String = TypesWorld.withSession(wasm) { exercise() }

        fun runChicory(component: ComponentBackendFactory): String = TypesWorld.withSession(component) { exercise() }
    """.trimIndent()

    private fun compile(tempDir: Path): ClassLoader {
        val wit = Paths.get(requireNotNull(javaClass.getResource("/fixtures/types.wit")).toURI())
        Wasm2KtCommand().parse(listOf("--wasm-tools-path", wasmToolsBin, "-o", tempDir.toString(), "-p", pkg, wit.toString()))
        val pkgDir = tempDir.resolve(pkg.replace('.', '/'))
        Files.writeString(pkgDir.resolve("Harness.kt"), harness)
        val classes = tempDir.resolve("classes")
        Files.createDirectories(classes)
        val sources = Files.list(pkgDir).use { s -> s.filter { it.toString().endsWith(".kt") }.map { it.toString() }.toList() }
        // `types-world` must become TypesWorld, not TypesWorldWorld (ADR-0006).
        assert(sources.any { it.endsWith("TypesWorld.kt") }) { "unexpected generated files: $sources" }
        val exit = org.jetbrains.kotlin.cli.jvm.K2JVMCompiler().exec(
            System.err,
            *(listOf("-Xcontext-parameters", "-jvm-target", "21", "-no-stdlib", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()) + sources).toTypedArray(),
        )
        assertEquals(org.jetbrains.kotlin.cli.common.ExitCode.OK, exit, "Generated bindings + harness must compile")
        return java.net.URLClassLoader(arrayOf(classes.toUri().toURL()), javaClass.classLoader)
    }

    private val expected = listOf(
        "echoName(null)", "echoName(chiko)", "maybeLen", "elvis",
        "echoBytes", "reverseBytes", "emptyBytes",
        "echoChar(bmp)", "echoChar(non-bmp)", "nextChar",
        "echoBlob", "echoBlob(noTag)", "blobHash", "packetBytes", "packetEmpty", "packetText",
        "compact", "echoOptionalList",
        "nested(none)", "nested(some(none))", "nested(some(some))",
    ).joinToString(",")

    @Test
    fun `new type mappings round-trip on Wasmtime`(@TempDir tempDir: Path) {
        val loader = compile(tempDir)
        val wasm = Paths.get(requireNotNull(javaClass.getResource("/fixtures/types.wasm")).toURI())
        val result = loader.loadClass("$pkg.HarnessKt").getDeclaredMethod("runWasmtime", Path::class.java).invoke(null, wasm)
        assertEquals(expected, result)
    }

    @Test
    fun `new type mappings round-trip on Chicory`(@TempDir tempDir: Path) {
        val loader = compile(tempDir)
        val component = ChicoryComponent.fromDirectory(Paths.get("runtime-chicory/src/test/resources/components/types").toAbsolutePath())
        val result = loader.loadClass("$pkg.HarnessKt").getDeclaredMethod("runChicory", ComponentBackendFactory::class.java).invoke(null, component)
        assertEquals(expected, result)
    }
}
