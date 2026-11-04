package wasm2kt

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import wasm2kt.ast.WitResolve
import wasm2kt.runtime.FfmWasmtimeBridge
import wasm2kt.tooling.TypedBridge
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.assertTrue

/**
 * The differential tests of [ChicoryDifferentialTest] with the Wasmtime sessions opened on a typed
 * bridge (ADR-0016) built from every decomposed fixture, so the typed calls must match Chicory too.
 *
 * Runs in the `typedBridgeTest` task (its own JVM, needs cargo): the typed library is the only
 * bridge loaded there.
 */
@Tag("typed-bridge")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TypedBridgeDifferentialTest : ChicoryDifferentialTest() {
    override val typedBridge: Boolean = true

    @BeforeAll
    fun buildTypedBridge() = TypedBridgeFixtures.use()

    @Test
    fun `every fixture function has a typed call`() {
        for (module in TypedBridgeFixtures.crate.resolve("src").listDirectoryEntries("c*.rs")) {
            val source = Files.readString(module)
            assertTrue("Called through the generic path" !in source, "${module.name}:\n" + source.lines().take(12).joinToString("\n"))
        }
    }
}

/** Generated bindings opened on the typed bridge (`--bridge-id types`) pass the same harness. */
@Tag("typed-bridge")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TypedTypeMappingE2ETest : TypeMappingE2ETest() {
    override val bridgeId: String = "types"

    @BeforeAll
    fun buildTypedBridge() = TypedBridgeFixtures.use()
}

/** A typed bridge with every decomposed fixture, each with its directory's name as ID. */
object TypedBridgeFixtures {
    private val components: Path = Paths.get("runtime-chicory/src/test/resources/components").toAbsolutePath()
    val crate: Path = Paths.get("integration-tests/build/typed-bridge").toAbsolutePath()

    /** Makes the typed library the bridge of this JVM (before any session opens). */
    fun use() {
        System.setProperty("wasm2kt.bridge.lib", library.toString())
        check(FfmWasmtimeBridge.resolveBridgeLibraryPath() == library) { "Another bridge library was loaded first" }
    }

    /** Built once per JVM; Cargo's target directory is shared with rust-bridge so Wasmtime is compiled once. */
    private val library: Path by lazy {
        val fixtures = components.listDirectoryEntries().filter { Files.exists(it.resolve("wit.json")) }.sortedBy { it.name }
        TypedBridge.generateCrate(
            fixtures.map { TypedBridge.Component(it.name, WitResolve.parse(Files.readString(it.resolve("wit.json")))) },
            crate,
            libraryName = "wasm2kt_typed_bridge_test",
        )
        TypedBridge.cargoBuild(crate, targetDir = Paths.get("rust-bridge/target").toAbsolutePath(), libraryName = "wasm2kt_typed_bridge_test")
    }
}
