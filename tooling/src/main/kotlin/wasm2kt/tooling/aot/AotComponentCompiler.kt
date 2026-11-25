package wasm2kt.tooling.aot

import wasm2kt.tooling.ir.ChicoryWasmAdapter
import wasm2kt.tooling.ir.IrModule
import java.nio.file.Files
import java.nio.file.Path

/** Result of compiling all core modules of a decomposed component into AOT JVM `.class` files. */
class CompiledAotComponent internal constructor(
    val plan: BuildPlan,
    val modules: Map<Int, CompiledAotModule>,
    val irModules: Map<Int, IrModule>,
)

/**
 * Build-time AOT compiler driver for a decomposed WebAssembly component (`plan.json` + `coreN.wasm`).
 *
 * Emits `.class` files and raw `.data` binary resources without writing `plan.json` or `.meta`
 * resources to the runtime classpath. Async components (`usesAsync == true`) are rejected at build
 * time per ADR-0023 Phase 1.
 */
object AotComponentCompiler {
    fun compile(
        decomposedDir: Path,
        classesDir: Path,
        resourcesDir: Path,
        packageName: String,
        options: AotCompileOptions = AotCompileOptions(),
    ): CompiledAotComponent {
        Files.createDirectories(classesDir)
        Files.createDirectories(resourcesDir)
        return compileModules(decomposedDir) { ir, baseSimpleName ->
            AotCoreCompiler.compileToDirectory(
                ir = ir,
                classesDir = classesDir,
                resourcesDir = resourcesDir,
                packageName = packageName,
                baseSimpleName = baseSimpleName,
                dataResourcePath = null,
                options = options,
            )
        }
    }

    /**
     * Compiles all core modules of a decomposed component in memory without writing `.class` or
     * `.data` files to disk. Useful when callers only need IR metadata and compilation stats (for
     * example `Wasm2KtGenerateTask`, whose compiled `.class` / `.data` outputs live in `compile`
     * task).
     */
    fun compileInMemory(
        decomposedDir: Path,
        packageName: String,
        options: AotCompileOptions = AotCompileOptions(),
    ): CompiledAotComponent = compileModules(decomposedDir) { ir, baseSimpleName ->
        AotCoreCompiler.compile(
            ir = ir,
            packageName = packageName,
            baseSimpleName = baseSimpleName,
            dataResourcePath = null,
            options = options,
        )
    }

    private inline fun compileModules(
        decomposedDir: Path,
        compileModule: (IrModule, String) -> CompiledAotModule,
    ): CompiledAotComponent {
        val plan = BuildPlan.fromDirectory(decomposedDir)
        if (plan.usesAsync) {
            throw IllegalStateException(
                "The AOT backend does not support async components yet (usesAsync = true); use the CHICORY backend for async components.",
            )
        }
        val compiledModules = LinkedHashMap<Int, CompiledAotModule>(plan.modules.size)
        val irModules = LinkedHashMap<Int, IrModule>(plan.modules.size)
        for (mod in plan.modules) {
            val wasmBytes = Files.readAllBytes(decomposedDir.resolve(mod.file))
            val ir = ChicoryWasmAdapter.convert(wasmBytes, moduleIndex = mod.index)
            irModules[mod.index] = ir
            compiledModules[mod.index] = compileModule(ir, "Core${mod.index}")
        }
        return CompiledAotComponent(plan, compiledModules, irModules)
    }

    fun warningsFor(compiled: CompiledAotComponent): List<String> = buildList {
        for ((index, mod) in compiled.modules) {
            val s = mod.stats
            if (s.artOversizedMethods > 0) {
                add(
                    "wasm2kt (AOT): ${s.artOversizedMethods} method(s) of core module $index exceed ART's compiler instruction limit (max ${s.maxMethodCodeBytes} JVM bytes in ${s.maxMethodName}) and may run in ART's interpreter",
                )
            }
            if (s.hotspotOversizedMethods > 0) {
                add(
                    "wasm2kt (AOT): ${s.hotspotOversizedMethods} method(s) of core module $index exceed HotSpot's 8,000-byte JIT limit (DontCompileHugeMethods; max ${s.maxMethodCodeBytes} bytes in ${s.maxMethodName})",
                )
            }
        }
    }
}
