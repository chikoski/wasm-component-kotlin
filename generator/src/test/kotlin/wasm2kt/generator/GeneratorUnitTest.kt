package wasm2kt.generator

import org.junit.jupiter.api.Test
import wasm2kt.ast.WitResolve
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Generator checks that need neither wasm-tools nor the native bridge, so they run in every build
 * (the compile-and-run E2E tests live in integration-tests). Inputs are the WIT JSON fixtures
 * (`wasm-tools component wit <name>.wit --json`, see fixtures/README.md).
 */
class GeneratorUnitTest {
    private fun resolve(name: String): WitResolve {
        val dir = Paths.get(requireNotNull(System.getProperty("wasm2kt.fixtures")) { "wasm2kt.fixtures is not set" })
        return WitResolve.parse(Files.readString(dir.resolve("$name.json")))
    }

    private fun generate(name: String, emitWasmtimeSessions: Boolean = true): Map<String, String> =
        WitToKotlinPoetConverter(resolve(name), "com.example.gen", emitWasmtimeSessions = emitWasmtimeSessions)
            .generate()
            .associate { it.name to it.content }

    @Test
    fun `world names follow ADR-0006`() {
        val files = generate("types")
        assertTrue("TypesWorld" in files, "files: ${files.keys}")
        assertFalse(files.keys.any { it.endsWith("WorldWorld") }, "files: ${files.keys}")
    }

    @Test
    fun `option maps to a nullable type unless the payload is nullable`() {
        val world = generate("types").getValue("TypesWorld")
        assertTrue(Regex("""fun TypesWorld\.echoName\(v: String\?\): String\?""").containsMatchIn(world), "echoName signature")
        assertTrue(Regex("""fun TypesWorld\.nested\(v: WitOption<String\?>\): WitOption<String\?>""").containsMatchIn(world), "nested signature")
        assertTrue(Regex("""fun TypesWorld\.echoBytes\(v: ByteArray\): ByteArray""").containsMatchIn(world), "list<u8> maps to ByteArray")
    }

    @Test
    fun `host import dispatch keys match the backends`() {
        val roundtrip = generate("roundtrip").getValue("RoundtripWorld")
        assertTrue("\"env-provider#format-tag\" ->" in roundtrip)
        assertTrue("\"env-provider#get-multiplier\" ->" in roundtrip)

        val hostRes = generate("host-res").getValue("HostResWorld")
        for (key in listOf("store#[constructor]blob", "store#[method]blob.read", "store#[static]blob.merge", "store#[resource-drop]blob", "[resource-drop]ticket")) {
            assertTrue("\"$key\" ->" in hostRes, "missing dispatch branch for $key")
        }
    }

    @Test
    fun `bindings without Wasmtime sessions do not reference the bridge`() {
        val files = generate("roundtrip", emitWasmtimeSessions = false)
        for ((name, content) in files) {
            for (forbidden in listOf("FfmWasmtimeSession", "JniWasmtimeSession", "FfmWasmtimeBridge", "java.lang.foreign")) {
                assertFalse(forbidden in content, "$name mentions $forbidden")
            }
        }
        val withSessions = generate("roundtrip").values.joinToString("\n")
        assertTrue("FfmWasmtimeSession" in withSessions && "JniWasmtimeSession" in withSessions)
    }

    @Test
    fun `generation is deterministic`() {
        for (name in listOf("calculator", "types", "host-res", "roundtrip")) {
            assertEquals(generate(name), generate(name), "$name generated differently on a second run")
        }
    }
}
