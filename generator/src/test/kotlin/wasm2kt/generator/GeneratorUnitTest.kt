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

    /** Generated files keyed by their path relative to `com.example.gen` (`TypesWorld`, `example/host_res/store/Store`). */
    private fun generate(name: String, emitWasmtimeSessions: Boolean = true): Map<String, String> =
        WitToKotlinPoetConverter(resolve(name), "com.example.gen", emitWasmtimeSessions = emitWasmtimeSessions)
            .generate()
            .associate { file ->
                val relativePackage = file.packageName.removePrefix("com.example.gen").removePrefix(".").replace('.', '/')
                (if (relativePackage.isEmpty()) "" else "$relativePackage/") + file.name to file.content
            }

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
    fun `host import keys are fully qualified and match the backends`() {
        val envProvider = generate("roundtrip").getValue("example/roundtrip/env_provider/EnvProvider")
        assertTrue("define(\"example:roundtrip/env-provider@0.1.0#format-tag\")" in envProvider, envProvider)
        assertTrue("define(\"example:roundtrip/env-provider@0.1.0#get-multiplier\")" in envProvider, envProvider)

        val files = generate("host-res")
        val store = files.getValue("example/host_res/store/Store")
        for (fn in listOf("[constructor]blob", "[method]blob.read", "[static]blob.merge", "[resource-drop]blob")) {
            assertTrue("define(\"example:host-res/store@0.1.0#$fn\")" in store, "missing host import $fn")
        }
        assertTrue("define(\"[resource-drop]ticket\")" in files.getValue("HostResWorld"), "world-level keys stay bare")
    }

    @Test
    fun `exported interface calls use fully qualified keys`() {
        val mathApi = generate("roundtrip").getValue("exports/example/roundtrip/math_api/MathApi")
        assertTrue("\"example:roundtrip/math-api@0.1.0#scale\"" in mathApi, mathApi)
        val guestApi = generate("host-res").getValue("exports/example/host_res/guest_api/GuestApi")
        assertTrue("\"example:host-res/guest-api@0.1.0#[method]counter.poke\"" in guestApi, guestApi)
        assertTrue("\"example:host-res/guest-api@0.1.0#[constructor]counter\"" in guestApi, guestApi)
    }

    @Test
    fun `interfaces map to packages and worlds take typed imports`() {
        val files = generate("host-res")
        assertTrue(files.getValue("example/host_res/store/Store").startsWith("package com.example.gen.example.host_res.store\n"))
        val world = files.getValue("HostResWorld")
        assertTrue(Regex("""fun imports\(exampleHostResStore: \(\) -> Store, world: \(\) -> Imports\): ImportsFactory""").containsMatchIn(world), world)
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
