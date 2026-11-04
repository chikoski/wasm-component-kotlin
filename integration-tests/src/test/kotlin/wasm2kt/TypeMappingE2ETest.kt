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
 * ADR-0006 type mappings (`option<T>` -> `T?` / nested `WitOption`, `list<u8>` -> `ByteArray`,
 * `char` -> `CodePoint`, world naming), exercised end to end on both backends with one harness.
 * The fixture is built from fixtures/src/types.
 */
open class TypeMappingE2ETest {
    /** The Wasmtime sessions open as this typed bridge component (ADR-0016); see [TypedTypeMappingE2ETest]. */
    protected open val bridgeId: String? = null

    private val wasmToolsBin: String = System.getenv("WASM_TOOLS_BIN")?.takeIf { it.isNotBlank() }
        ?: Paths.get(System.getProperty("user.home"), ".cargo", "bin", "wasm-tools").takeIf { Files.isExecutable(it) }?.toString()
        ?: "wasm-tools"

    private val pkg = "com.example.typemapping"

    private val harness = """
        package $pkg

        import java.nio.file.Path
        import wasm2kt.runtime.ComponentBackendFactory

        context(session: Session)
        fun exercise(): String {
            val out = mutableListOf<String>()
            fun check(label: String, ok: Boolean) { out += if (ok) label else "FAILED:${'$'}label" }

            // option<T> -> T?
            check("echoName(null)", echoName(null) == null)
            check("echoName(chiko)", echoName("chiko") == "chiko")
            check("maybeLen", maybeLen("日本🎌") == 3u && maybeLen(null) == null)
            val len = maybeLen("abc")?.toInt() ?: -1
            check("elvis", len == 3)

            // list<u8> -> ByteArray
            val bytes = byteArrayOf(0, 1, 127, -128, -1)
            check("echoBytes", echoBytes(bytes).contentEquals(bytes))
            check("reverseBytes", reverseBytes(bytes).contentEquals(bytes.reversedArray()))
            check("emptyBytes", echoBytes(ByteArray(0)).isEmpty())

            // char -> CodePoint, including a code point outside the BMP
            val flag = CodePoint.of("🎌")
            check("echoChar(bmp)", echoChar(CodePoint.of('あ')) == CodePoint.of('あ'))
            check("echoChar(non-bmp)", echoChar(flag) == flag && echoChar(flag).toString() == "🎌")
            check("nextChar", nextChar(CodePoint.of('a')) == CodePoint.of('b'))

            // records and variants holding ByteArray compare by content
            val blob = Blob(name = "n", data = bytes, tag = 7u)
            check("echoBlob", echoBlob(blob) == blob)
            check("echoBlob(noTag)", echoBlob(blob.copy(tag = null)).tag == null)
            check("blobHash", echoBlob(blob).hashCode() == blob.hashCode())
            check("packetBytes", echoPacket(Packet.Bytes(bytes)) == Packet.Bytes(bytes))
            check("packetEmpty", echoPacket(Packet.Empty) == Packet.Empty)
            check("packetText", echoPacket(Packet.Text("t")) == Packet.Text("t"))

            // list<option<T>> -> List<T?>
            check("compact", compact(listOf(1, null, 3)) == listOf(1, 3))
            check("echoOptionalList", echoOptionalList(listOf(null, 5, null)) == listOf(null, 5, null))

            // option<option<T>> -> WitOption<T?>: outer none, some(none), some(some)
            check("nested(none)", nested(WitOption.None) == WitOption.None)
            check("nested(some(none))", nested(WitOption.Some(null)) == WitOption.Some(null))
            check("nested(some(some))", nested(WitOption.Some("x")) == WitOption.Some("x"))

            // option<record> whose fields look like the tagged option form (#36)
            val looksNone = Flagged(option = "none", value = 1u, note = "n")
            val looksSome = Flagged(option = "some", value = 2u, note = "s")
            check("flagged(none-like)", echoFlagged(looksNone) == looksNone)
            check("flagged(some-like)", echoFlagged(looksSome) == looksSome)
            check("flagged(null)", echoFlagged(null) == null)
            return out.joinToString(",")
        }

        fun runWasmtime(wasm: Path): String = withSession(wasm) { exercise() }

        fun runChicory(component: ComponentBackendFactory): String = withSession(component) { exercise() }
    """.trimIndent()

    private fun compile(tempDir: Path): ClassLoader {
        val wit = Paths.get(requireNotNull(javaClass.getResource("/fixtures/types.wit")).toURI())
        val bridge = bridgeId?.let { listOf("--bridge-id", it) }.orEmpty()
        GenerateCommand().parse(listOf("--wasm-tools-path", wasmToolsBin, "-o", tempDir.toString(), "-p", pkg) + bridge + wit.toString())
        val pkgDir = tempDir.resolve(pkg.replace('.', '/'))
        Files.writeString(pkgDir.resolve("Harness.kt"), harness)
        val classes = tempDir.resolve("classes")
        Files.createDirectories(classes)
        val sources = generatedSources(tempDir)
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
        "flagged(none-like)", "flagged(some-like)", "flagged(null)",
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
