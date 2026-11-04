package wasm2kt

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import wasm2kt.ast.WitResolve
import wasm2kt.tooling.TypedBridge
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertTrue

/** The typed bridge builds with the bundled Cargo.lock as is (#79); needs cargo, like the bridge tests. */
class TypedBridgeLockTest {
    @Test
    fun `a Cargo lock that no longer matches the crate fails the build`(@TempDir dir: Path) {
        val wit = Paths.get("runtime-chicory/src/test/resources/components/arithmetic/wit.json")
        TypedBridge.generateCrate(listOf(TypedBridge.Component("arithmetic", WitResolve.parse(Files.readString(wit)))), dir)
        // Drop a dependency of the bundled core: the lock now lists a package nothing needs.
        val core = dir.resolve("core/Cargo.toml")
        Files.writeString(core, Files.readString(core).lines().filterNot { it.startsWith("jni") }.joinToString("\n"))
        val e = runCatching { TypedBridge.cargoBuild(dir, targetDir = dir.resolve("target")) }.exceptionOrNull()
        assertTrue(e is IllegalStateException && "--locked" in e.message!!, "got $e")
    }
}
