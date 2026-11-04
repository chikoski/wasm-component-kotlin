package wasm2kt

import com.github.ajalt.clikt.core.parse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import wasm2kt.cli.BridgeCommand
import wasm2kt.cli.Wasm2KtCommand
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `wasm2kt bridge` writes one crate for several components; `wasm2kt <args>` still means `generate`. */
class BridgeCommandTest {
    private val wasmToolsBin: String = System.getenv("WASM_TOOLS_BIN")?.takeIf { it.isNotBlank() }
        ?: Paths.get(System.getProperty("user.home"), ".cargo", "bin", "wasm-tools").takeIf { Files.isExecutable(it) }?.toString()
        ?: "wasm-tools"

    private fun fixture(name: String) = Paths.get(requireNotNull(javaClass.getResource("/fixtures/$name")).toURI())

    @Test
    fun `bridge generates one crate for the components`(@TempDir dir: Path) {
        BridgeCommand().parse(
            listOf(
                "--wasm-tools-path", wasmToolsBin, "-o", dir.toString(),
                "--component", "types=${fixture("types.wasm")}",
                "--component", "calc=${fixture("calculator.json")}",
            ),
        )
        val lib = Files.readString(dir.resolve("src/lib.rs"))
        assertTrue("mod c0; // types" in lib && "mod c1; // calc" in lib, lib)
        assertTrue("\"calc\"" in Files.readString(dir.resolve("src/c1.rs")))
        assertTrue(Files.exists(dir.resolve("core/src/lib.rs")))
    }

    @Test
    fun `arguments without a subcommand are a generate command`() {
        assertEquals(listOf("generate", "x.wit", "-o", "out"), Wasm2KtCommand.withDefaultSubcommand(listOf("x.wit", "-o", "out")))
        assertEquals(listOf("generate", "--from-json", "-o", "out"), Wasm2KtCommand.withDefaultSubcommand(listOf("--from-json", "-o", "out")))
        assertEquals(listOf("bridge", "-o", "out"), Wasm2KtCommand.withDefaultSubcommand(listOf("bridge", "-o", "out")))
        assertEquals(listOf("--help"), Wasm2KtCommand.withDefaultSubcommand(listOf("--help")))
    }
}
