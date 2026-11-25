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
        val plan = BuildPlan.fromDirectory(decomposedDir)
        if (plan.usesAsync) {
            throw IllegalStateException(
                "The AOT backend does not support async components yet (usesAsync = true); use the CHICORY backend for async components.",
            )
        }
        Files.createDirectories(classesDir)
        Files.createDirectories(resourcesDir)
        val compiledModules = LinkedHashMap<Int, CompiledAotModule>(plan.modules.size)
        val irModules = LinkedHashMap<Int, IrModule>(plan.modules.size)
        for (mod in plan.modules) {
            val wasmBytes = Files.readAllBytes(decomposedDir.resolve(mod.file))
            val ir = ChicoryWasmAdapter.convert(wasmBytes, moduleIndex = mod.index)
            val compiled = AotCoreCompiler.compileToDirectory(
                ir = ir,
                classesDir = classesDir,
                resourcesDir = resourcesDir,
                packageName = packageName,
                baseSimpleName = "Core${mod.index}",
                dataResourcePath = null,
                options = options,
            )
            irModules[mod.index] = ir
            compiledModules[mod.index] = compiled
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
