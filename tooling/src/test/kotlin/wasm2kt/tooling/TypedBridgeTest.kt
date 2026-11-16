package wasm2kt.tooling

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import wasm2kt.ast.WitResolve
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TypedBridgeTest {
    private val components: Path = Paths.get(System.getProperty("wasm2kt.fixtures")).resolve("../runtime-chicory/src/test/resources/components")

    private fun component(name: String) =
        TypedBridge.Component(name, WitResolve.parse(Files.readString(components.resolve("$name/wit.json"))))

    @Test
    fun `the crate has the core, the lock file and a typed module per component`(@TempDir tmp: Path) {
        // WASM2KT_TYPED_BRIDGE_OUT keeps the crate, e.g. to build it by hand.
        val dir = System.getenv("WASM2KT_TYPED_BRIDGE_OUT")?.let { Paths.get(it) } ?: tmp
        val names = listOf("roundtrip", "types", "host-res", "counter", "abi-edge", "res-edge", "same-name", "arithmetic")
        TypedBridge.generateCrate(names.map(::component), dir)

        assertTrue(Files.exists(dir.resolve("core/src/lib.rs")))
        assertTrue(Files.exists(dir.resolve("core/src/exports.rs")))
        assertTrue("name = \"wasm2kt-bridge-core\"" in Files.readString(dir.resolve("Cargo.lock")))
        val lib = Files.readString(dir.resolve("src/lib.rs"))
        assertTrue("export_bridge!(&[&c0::Component" in lib, lib)
        names.forEachIndexed { i, name ->
            val module = Files.readString(dir.resolve("src/c$i.rs"))
            assertTrue("\"$name\"" in module)
            // Every function of these fixtures has a typed call.
            assertTrue("Called through the generic path" !in module, module.lines().take(20).joinToString("\n"))
        }
    }

    @Test
    fun `component IDs must be unique`(@TempDir dir: Path) {
        val e = runCatching { TypedBridge.generateCrate(listOf(component("types"), component("types")), dir) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException && "unique" in e.message!!, "got $e")
    }

    @Test
    fun `library file names follow the target`() {
        assertEquals("libwasm2kt_wasmtime_bridge.so", TypedBridge.libraryFileName("x86_64-unknown-linux-gnu"))
        assertEquals("libwasm2kt_wasmtime_bridge.dylib", TypedBridge.libraryFileName("aarch64-apple-darwin"))
        assertEquals("wasm2kt_wasmtime_bridge.dll", TypedBridge.libraryFileName("x86_64-pc-windows-msvc"))
    }

    @Test
    fun `built libraries are copied where runtime-wasmtime looks on the class path`(@TempDir dir: Path) {
        assertEquals("linux-x86_64", TypedBridge.platform("x86_64-unknown-linux-gnu"))
        assertEquals("linux-aarch64", TypedBridge.platform("aarch64-unknown-linux-gnu"))
        assertEquals("macos-aarch64", TypedBridge.platform("aarch64-apple-darwin"))
        assertEquals("windows-x86_64", TypedBridge.platform("x86_64-pc-windows-msvc"))
        assertTrue(runCatching { TypedBridge.platform("aarch64-linux-android") }.isFailure)

        val lib = Files.writeString(dir.resolve("libwasm2kt_typed.so"), "elf")
        val copy = TypedBridge.copyToResources(lib, dir.resolve("res"), "x86_64-unknown-linux-gnu")
        assertEquals(dir.resolve("res/META-INF/wasm2kt/native/linux-x86_64/libwasm2kt_wasmtime_bridge.so"), copy)
        assertEquals("elf", Files.readString(copy))
    }

    /** A stand-in for cargo that records its arguments and then runs [body]. */
    private fun fakeCargo(dir: Path, body: String): Path {
        val script = dir.resolve("cargo")
        Files.writeString(script, "#!/bin/sh\necho \"$@\" > \"${dir.resolve("args.txt")}\"\n$body\n")
        script.toFile().setExecutable(true)
        return script
    }

    @Test
    fun `cargo builds with --locked`(@TempDir dir: Path) {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name").lowercase().startsWith("win"))
        val cargo = fakeCargo(dir, "exit 0")
        TypedBridge.cargoBuild(dir.resolve("crate"), cargo = cargo.toString())
        val args = Files.readString(dir.resolve("args.txt"))
        assertTrue(args.startsWith("build --release --locked --manifest-path "), args)
    }

    @Test
    fun `a cargo build that does not finish is stopped`(@TempDir dir: Path) {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name").lowercase().startsWith("win"))
        // Keeps its output open and never exits, like a cargo waiting on a lock.
        val cargo = fakeCargo(dir, "echo compiling; exec sleep 600")
        val started = System.nanoTime()
        val e = runCatching {
            TypedBridge.cargoBuild(dir.resolve("crate"), cargo = cargo.toString(), timeout = java.time.Duration.ofSeconds(2))
        }.exceptionOrNull()
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue(e is IllegalStateException && "did not finish within" in e.message!! && "compiling" in e.message!!, "got $e")
        assertTrue(seconds < 30, "took $seconds s")
    }

    @Test
    fun `a failed cargo build reports its output`(@TempDir dir: Path) {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name").lowercase().startsWith("win"))
        val cargo = fakeCargo(dir, "echo 'error: the lock file x/Cargo.lock needs to be updated but --locked was passed to prevent this'; exit 101")
        val e = runCatching { TypedBridge.cargoBuild(dir.resolve("crate"), cargo = cargo.toString()) }.exceptionOrNull()
        assertTrue(e is IllegalStateException && "needs to be updated" in e.message!! && "does not match its dependencies" in e.message!!, "got $e")
    }

    @Test
    fun `world TypeRef and transitive host resources are registered as imports in typed bridges`(@TempDir dir: Path) {
        val resolve = WitResolve(
            packages = listOf(wasm2kt.ast.WitPackage("example:res@0.1.0", mapOf("types" to 0, "mid" to 1))),
            interfaces = listOf(
                wasm2kt.ast.WitInterface(
                    name = "types",
                    types = mapOf("blob" to 0, "hidden" to 1),
                    `package` = 0,
                ),
                wasm2kt.ast.WitInterface(
                    name = "mid",
                    types = mapOf("hidden" to 2),
                    functions = mapOf(
                        "ping" to wasm2kt.ast.WitFunction(
                            name = "ping",
                            kind = wasm2kt.ast.WitFunctionKind.Freestanding(),
                            params = listOf(wasm2kt.ast.WitParam("h", wasm2kt.ast.WitTypeRef.Id(3))),
                        ),
                    ),
                    `package` = 0,
                ),
            ),
            types = listOf(
                wasm2kt.ast.WitTypeDef("blob", wasm2kt.ast.WitTypeDefKind.Resource, wasm2kt.ast.WitTypeOwner(`interface` = 0)),
                wasm2kt.ast.WitTypeDef("hidden", wasm2kt.ast.WitTypeDefKind.Resource, wasm2kt.ast.WitTypeOwner(`interface` = 0)),
                wasm2kt.ast.WitTypeDef("hidden", wasm2kt.ast.WitTypeDefKind.TypeAlias(wasm2kt.ast.WitTypeRef.Id(1)), wasm2kt.ast.WitTypeOwner(`interface` = 1)),
                wasm2kt.ast.WitTypeDef(null, wasm2kt.ast.WitTypeDefKind.Handle(wasm2kt.ast.HandleKind.BORROW, 1), null),
                wasm2kt.ast.WitTypeDef("blob", wasm2kt.ast.WitTypeDefKind.TypeAlias(wasm2kt.ast.WitTypeRef.Id(0)), wasm2kt.ast.WitTypeOwner(world = 0)),
                wasm2kt.ast.WitTypeDef(null, wasm2kt.ast.WitTypeDefKind.Handle(wasm2kt.ast.HandleKind.BORROW, 0), null),
            ),
            worlds = listOf(
                wasm2kt.ast.WitWorld(
                    name = "app",
                    imports = mapOf(
                        "blob" to wasm2kt.ast.WitWorldItem.TypeRef(4),
                        "mid" to wasm2kt.ast.WitWorldItem.InterfaceRef(1),
                    ),
                    exports = mapOf(
                        "use-blob" to wasm2kt.ast.WitWorldItem.FunctionItem(
                            wasm2kt.ast.WitFunction(
                                name = "use-blob",
                                kind = wasm2kt.ast.WitFunctionKind.Freestanding(),
                                params = listOf(wasm2kt.ast.WitParam("b", wasm2kt.ast.WitTypeRef.Id(5))),
                            ),
                        ),
                    ),
                ),
            ),
        )
        TypedBridge.generateCrate(listOf(TypedBridge.Component("type-ref-app", resolve)), dir)
        val generated = Files.readString(dir.resolve("src/c0.rs"))
        assertTrue("Called through the generic path" !in generated, generated)
        assertTrue("host_payload(cx, \"example:res/types@0.1.0#blob\")" in generated, generated)
        assertTrue("\"example:res/mid@0.1.0#ping\" => inst.func_wrap(" in generated, generated)
        assertTrue("cx.data_mut().borrowed_handles.clear();" in generated, generated)
        assertTrue("cx.data_mut().moved_handles.clear();" in generated, generated)

        val transitiveOnly = WitResolve(
            packages = listOf(wasm2kt.ast.WitPackage("example:res@0.1.0", mapOf("types" to 0, "mid" to 1))),
            interfaces = listOf(
                wasm2kt.ast.WitInterface(
                    name = "types",
                    types = mapOf("hidden" to 0),
                    `package` = 0,
                ),
                wasm2kt.ast.WitInterface(
                    name = "mid",
                    types = mapOf("hidden" to 1),
                    functions = mapOf(
                        "ping" to wasm2kt.ast.WitFunction(
                            name = "ping",
                            kind = wasm2kt.ast.WitFunctionKind.Freestanding(),
                            params = listOf(wasm2kt.ast.WitParam("h", wasm2kt.ast.WitTypeRef.Id(2))),
                        ),
                    ),
                    `package` = 0,
                ),
            ),
            types = listOf(
                wasm2kt.ast.WitTypeDef("hidden", wasm2kt.ast.WitTypeDefKind.Resource, wasm2kt.ast.WitTypeOwner(`interface` = 0)),
                wasm2kt.ast.WitTypeDef("hidden", wasm2kt.ast.WitTypeDefKind.TypeAlias(wasm2kt.ast.WitTypeRef.Id(0)), wasm2kt.ast.WitTypeOwner(`interface` = 1)),
                wasm2kt.ast.WitTypeDef(null, wasm2kt.ast.WitTypeDefKind.Handle(wasm2kt.ast.HandleKind.BORROW, 1), null),
            ),
            worlds = listOf(
                wasm2kt.ast.WitWorld(
                    name = "app",
                    imports = mapOf("mid" to wasm2kt.ast.WitWorldItem.InterfaceRef(1)),
                    exports = mapOf(
                        "use-hidden" to wasm2kt.ast.WitWorldItem.FunctionItem(
                            wasm2kt.ast.WitFunction(
                                name = "use-hidden",
                                kind = wasm2kt.ast.WitFunctionKind.Freestanding(),
                                params = listOf(wasm2kt.ast.WitParam("h", wasm2kt.ast.WitTypeRef.Id(2))),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val transitiveDir = dir.resolve("transitive")
        TypedBridge.generateCrate(listOf(TypedBridge.Component("transitive-app", transitiveOnly)), transitiveDir)
        val transitiveGenerated = Files.readString(transitiveDir.resolve("src/c0.rs"))
        assertTrue("Called through the generic path" !in transitiveGenerated, transitiveGenerated)
        assertTrue("host_payload(cx, \"example:res/types@0.1.0#hidden\")" in transitiveGenerated, transitiveGenerated)
        assertTrue("\"example:res/mid@0.1.0#ping\" => inst.func_wrap(" in transitiveGenerated, transitiveGenerated)
    }
}
