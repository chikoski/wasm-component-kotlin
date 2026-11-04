package wasm2kt.tooling

import com.dylibso.chicory.compiler.MachineFactoryCompiler
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.Machine
import com.dylibso.chicory.wasi.WasiExitException
import com.dylibso.chicory.wasi.WasiOptions
import com.dylibso.chicory.wasi.WasiPreview1
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.WasmModule
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Function

/**
 * Runs `wasm2kt-decompose` — bundled as a WASI module — on Chicory, so splitting a component
 * into core modules + `plan.json` + `wit.json` needs neither Rust nor a native binary.
 */
object Decomposer {
    private const val RESOURCE = "/wasm2kt/tooling/wasm2kt-decompose.wasm"

    private val module: WasmModule by lazy {
        val bytes = ToolingResources.read(RESOURCE)
            ?: error("Bundled decomposer $RESOURCE is missing from the classpath")
        Parser.parse(bytes)
    }

    /**
     * The `wit.json` of a decomposed component, for binding generation. Some valid components have no
     * WIT form (e.g. ones exporting bare types); the decomposer then writes `wit-error.txt` instead.
     */
    fun witJson(decomposedDir: java.nio.file.Path): java.nio.file.Path {
        val wit = decomposedDir.resolve("wit.json")
        if (java.nio.file.Files.exists(wit)) return wit
        val reason = decomposedDir.resolve("wit-error.txt").takeIf { java.nio.file.Files.exists(it) }?.let { java.nio.file.Files.readString(it).trim() }
        throw IllegalStateException("No bindings can be generated: the component's WIT could not be decoded" + (reason?.let { ": $it" } ?: ""))
    }

    /** Compiled once per JVM (i.e. per Gradle daemon) and reused across components. */
    private val machineFactory: Function<Instance, Machine> by lazy { MachineFactoryCompiler.compile(module) }

    /**
     * Decomposes [component] into [outDir], which receives `plan.json`, `wit.json` and
     * `core<N>.wasm`. Existing files in [outDir] are left alone; callers should pass a clean dir.
     */
    @Synchronized
    fun decompose(component: Path, outDir: Path) {
        val input = component.toAbsolutePath()
        require(Files.isRegularFile(input)) { "Component $input does not exist" }
        val output = Files.createDirectories(outDir.toAbsolutePath())
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val options = WasiOptions.builder()
            .withArguments(listOf("wasm2kt-decompose", "/in/${input.fileName}", "/out"))
            .withDirectory("/in", input.parent)
            .withDirectory("/out", output)
            .withStdout(stdout)
            .withStderr(stderr)
            .build()

        val exitCode = WasiPreview1.builder().withOptions(options).build().use { wasi ->
            val instance = Instance.builder(module)
                .withImportValues(ImportValues.builder().addFunction(*wasi.toHostFunctions()).build())
                .withMachineFactory(machineFactory)
                .withStart(false)
                .build()
            try {
                instance.export("_start").apply()
                0
            } catch (e: WasiExitException) {
                e.exitCode()
            } catch (e: com.dylibso.chicory.wasm.ChicoryException) {
                // A Rust panic aborts the wasm build with a trap; its message is on stderr (#47).
                throw IllegalStateException(
                    "wasm2kt-decompose crashed for $input; this is a wasm2kt bug, please report it with the component:\n" +
                        stderr.toString(Charsets.UTF_8).trim(),
                    e,
                )
            }
        }
        if (exitCode != 0) {
            throw IllegalStateException(
                "wasm2kt-decompose failed for $input (exit code $exitCode):\n" + stderr.toString(Charsets.UTF_8).trim(),
            )
        }
    }
}
