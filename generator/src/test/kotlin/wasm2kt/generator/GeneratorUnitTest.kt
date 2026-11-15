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
        assertTrue(Regex("""fun echoName\(v: String\?\): String\?""").containsMatchIn(world), "echoName signature")
        assertTrue(Regex("""fun nested\(v: WitOption<String\?>\): WitOption<String\?>""").containsMatchIn(world), "nested signature")
        assertTrue(Regex("""fun echoBytes\(v: ByteArray\): ByteArray""").containsMatchIn(world), "list<u8> maps to ByteArray")
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
    fun `interfaces with the same short name get their own packages, keys and parameters`() {
        val files = generate("same-name")
        val http = files.getValue("example/http/types/Types")
        val fs = files.getValue("example/fs/types/Types")
        assertTrue("define(\"example:http/types@0.1.0#describe\")" in http, http)
        assertTrue("define(\"example:fs/types@0.1.0#describe\")" in fs, fs)
        // The import copy of `tally` is a host resource, the export copy a component-defined one.
        assertTrue("interface Tally : WitHostResource" in http, http)
        val guestHttp = files.getValue("exports/example/http/types/Types")
        assertTrue("class Tally internal constructor(" in guestHttp, guestHttp)
        assertTrue("\"example:http/types@0.1.0#[constructor]tally\"" in guestHttp, guestHttp)
        assertTrue("exports/example/fs/types/Types" in files)
        val world = files.getValue("SameNameWorld")
        // KotlinPoet aliases the two `Types` imports; the parameter names come from the qualified names.
        assertTrue(Regex("""fun imports\(exampleHttpTypes: \(\) -> \w+, exampleFsTypes: \(\) -> \w+\): ImportsFactory""").containsMatchIn(world), world)
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
        for (name in listOf("calculator", "types", "host-res", "roundtrip", "same-name")) {
            assertEquals(generate(name), generate(name), "$name generated differently on a second run")
        }
    }

    @Test
    fun `records with multiple option and list fields use distinct local variable names in writeTo`() {
        val world = generate("roundtrip").getValue("RoundtripWorld")
        // Verify that a generated writeTo never declares duplicate __o0 or __l0 in the same function scope.
        assertFalse(Regex("""val __l0 =[^\n]*\n(?:.*\n)*?\s*val __l0 =""").containsMatchIn(world), world)
        assertTrue("writer.reset()" in generate("roundtrip").getValue("WitSupport"))
    }

    @Test
    fun `host imports support streams, futures, and nested async values with interfacesOnly`() {
        val fixturesDir = Paths.get(requireNotNull(System.getProperty("wasm2kt.fixtures")) { "wasm2kt.fixtures is not set" })
        val wasiResolve = WitResolve.parse(Files.readString(fixturesDir.parent.resolve("runtime-wasi/wit.json")))
        val converter = WitToKotlinPoetConverter(wasiResolve, "wasm2kt.wasi", interfacesOnly = true)
        val files = converter.generate().associate { file ->
            val rel = file.packageName.removePrefix("wasm2kt.wasi").removePrefix(".").replace('.', '/')
            (if (rel.isEmpty()) "" else "$rel/") + file.name to file.content
        }
        val stdin = files.getValue("wasi/cli/stdin/Stdin")
        assertTrue("ComponentBackend.current().newStream(" in stdin, stdin)
        assertTrue("ComponentBackend.current().newFuture(" in stdin, stdin)
        val stdout = files.getValue("wasi/cli/stdout/Stdout")
        assertTrue("witStream(ComponentBackend.current()," in stdout, stdout)
        assertTrue("ComponentBackend.current().newFuture(" in stdout, stdout)
        assertTrue("wasi:cli/types@0.3.0" in converter.manifest!!.interfaces)
    }

    @Test
    fun `mapped worlds keep primary imports required and add base ImportsFactory overload`() {
        val unmappedWorld = generate("host-res").getValue("HostResWorld")
        assertFalse("base: ImportsFactory" in unmappedWorld, unmappedWorld)

        val resolve = resolve("host-res")
        val mappedFiles = WitToKotlinPoetConverter(
            resolve,
            "com.example.gen",
            interfaceMappings = mapOf("example:host-res/store@0.1.0" to "com.example.hostlib"),
        ).generate().associate { it.name to it.content }
        val world = mappedFiles.getValue("HostResWorld")

        // Primary overload stays non-nullable (ADR-0014 compatibility).
        assertTrue(
            Regex("""fun imports\(exampleHostResStore: \(\) -> Store, world: \(\) -> Imports\): ImportsFactory""").containsMatchIn(world),
            world,
        )
        // Secondary base overload keeps unmapped `world` required and makes mapped `store` optional.
        assertTrue(
            Regex("""fun imports\(\s*base: ImportsFactory,\s*exampleHostResStore: \(\(\) -> Store\)\? = null,\s*world: \(\) -> Imports,?\s*\): ImportsFactory""").containsMatchIn(world),
            world,
        )
        assertTrue("Linker().addAll(base.newLinker()).addAll(__overrides, overwrite = true)" in world, world)
    }

    @Test
    fun `base ImportsFactory overload avoids parameter collision when an import is named base`() {
        val wit = WitResolve(
            worlds = listOf(
                wasm2kt.ast.WitWorld(
                    name = "app",
                    imports = mapOf(
                        "example:lib/api@0.1.0" to wasm2kt.ast.WitWorldItem.InterfaceRef(0),
                        "base" to wasm2kt.ast.WitWorldItem.InterfaceRef(1),
                    ),
                ),
            ),
            interfaces = listOf(
                wasm2kt.ast.WitInterface(
                    name = "api",
                    `package` = 0,
                    functions = mapOf(
                        "ping" to wasm2kt.ast.WitFunction(name = "ping", kind = wasm2kt.ast.WitFunctionKind.Freestanding()),
                    ),
                ),
                wasm2kt.ast.WitInterface(
                    name = null,
                    `package` = null,
                    functions = mapOf(
                        "log" to wasm2kt.ast.WitFunction(name = "log", kind = wasm2kt.ast.WitFunctionKind.Freestanding()),
                    ),
                ),
            ),
            packages = listOf(wasm2kt.ast.WitPackage(name = "example:lib@0.1.0")),
        )
        val manifest = InterfaceManifest("com.example.lib", mapOf("example:lib/api@0.1.0" to "com.example.lib.api"))
        val world = WitToKotlinPoetConverter(wit, "com.example.app", libraryManifests = listOf(manifest))
            .generate()
            .first { it.name == "AppWorld" }
            .content
        assertTrue(
            Regex("""fun imports\(\s*baseImports: ImportsFactory,\s*exampleLibApi: \(\(\) -> Api\)\? = null,\s*base: \(\) -> Base,?\s*\): ImportsFactory""").containsMatchIn(world),
            world,
        )
        assertTrue("Linker().addAll(baseImports.newLinker()).addAll(__overrides, overwrite = true)" in world, world)

        val doubleCollisionWit = wit.copy(
            worlds = listOf(
                wasm2kt.ast.WitWorld(
                    name = "app",
                    imports = mapOf(
                        "example:lib/api@0.1.0" to wasm2kt.ast.WitWorldItem.InterfaceRef(0),
                        "base" to wasm2kt.ast.WitWorldItem.InterfaceRef(1),
                        "base-imports" to wasm2kt.ast.WitWorldItem.InterfaceRef(2),
                    ),
                ),
            ),
            interfaces = wit.interfaces + wasm2kt.ast.WitInterface(
                name = null,
                `package` = null,
                functions = mapOf(
                    "trace" to wasm2kt.ast.WitFunction(name = "trace", kind = wasm2kt.ast.WitFunctionKind.Freestanding()),
                ),
            ),
        )
        val doubleCollisionWorld = WitToKotlinPoetConverter(doubleCollisionWit, "com.example.app", libraryManifests = listOf(manifest))
            .generate()
            .first { it.name == "AppWorld" }
            .content
        assertTrue("baseImports_: ImportsFactory" in doubleCollisionWorld, doubleCollisionWorld)
        assertTrue("Linker().addAll(baseImports_.newLinker()).addAll(__overrides, overwrite = true)" in doubleCollisionWorld, doubleCollisionWorld)
    }
}
