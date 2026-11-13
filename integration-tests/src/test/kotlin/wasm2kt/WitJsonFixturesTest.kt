package wasm2kt

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * The WIT JSON fixtures that generator's unit tests read (fixtures/<name>.json) must match their
 * `.wit`; they are committed so those tests need no wasm-tools, and this check catches a `.wit`
 * changed without regenerating its JSON.
 */
class WitJsonFixturesTest {
    private val wasmToolsBin: String = System.getenv("WASM_TOOLS_BIN")?.takeIf { it.isNotBlank() }
        ?: Paths.get(System.getProperty("user.home"), ".cargo", "bin", "wasm-tools").takeIf { Files.isExecutable(it) }?.toString()
        ?: "wasm-tools"

    private val fixtures: Path = Paths.get("fixtures").toAbsolutePath()

    @Test
    fun `committed WIT JSON fixtures match their wit files`() {
        for (name in listOf("calculator", "types", "host-res", "roundtrip")) {
            val process = ProcessBuilder(wasmToolsBin, "component", "wit", fixtures.resolve("$name.wit").toString(), "--json")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "wasm-tools failed for $name.wit:\n$output" }
            assertEquals(
                Json.parseToJsonElement(output),
                Json.parseToJsonElement(Files.readString(fixtures.resolve("$name.json"))),
                "fixtures/$name.json is stale; regenerate it with `wasm-tools component wit fixtures/$name.wit --json > fixtures/$name.json`",
            )
        }
    }

    @Test
    fun `committed WASI 0_3 WIT JSON matches runtime-wasi wit sources`() {
        val wasiDir = Paths.get("runtime-wasi").toAbsolutePath()
        val process = ProcessBuilder(wasmToolsBin, "component", "wit", wasiDir.resolve("wit").toString(), "--json")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "wasm-tools failed for runtime-wasi/wit:\n$output" }
        assertEquals(
            Json.parseToJsonElement(output),
            Json.parseToJsonElement(Files.readString(wasiDir.resolve("wit.json"))),
            "runtime-wasi/wit.json is stale; regenerate it with `wasm-tools component wit runtime-wasi/wit --json > runtime-wasi/wit.json`",
        )
    }
}
