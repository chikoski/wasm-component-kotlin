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
 * resources to the runtime classpath. Supports both synchronous (ADR-0023 Phase 1/2) and async /
 * WASI 0.3 components (Phase 3).
 */
object AotComponentCompiler {
    private val RAW_CALL_TRAMPOLINES = setOf("prepare-call", "sync-start-call", "async-start-call")

    fun compile(
        decomposedDir: Path,
        classesDir: Path,
        resourcesDir: Path,
        packageName: String,
        options: AotCompileOptions = AotCompileOptions(),
    ): CompiledAotComponent {
        Files.createDirectories(classesDir)
        Files.createDirectories(resourcesDir)
        return compileModules(decomposedDir) { ir, baseSimpleName, rawCallable ->
            AotCoreCompiler.compileToDirectory(
                ir = ir,
                classesDir = classesDir,
                resourcesDir = resourcesDir,
                packageName = packageName,
                baseSimpleName = baseSimpleName,
                dataResourcePath = null,
                options = if (rawCallable) options.copy(emitRawCallDispatch = true) else options,
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
    ): CompiledAotComponent = compileModules(decomposedDir) { ir, baseSimpleName, rawCallable ->
        AotCoreCompiler.compile(
            ir = ir,
            packageName = packageName,
            baseSimpleName = baseSimpleName,
            dataResourcePath = null,
            options = if (rawCallable) options.copy(emitRawCallDispatch = true) else options,
        )
    }

    private inline fun compileModules(
        decomposedDir: Path,
        compileModule: (IrModule, String, Boolean) -> CompiledAotModule,
    ): CompiledAotComponent {
        val plan = BuildPlan.fromDirectory(decomposedDir)
        val rawCallableModules = HashSet<Int>()
        for (init in plan.initializers) {
            if (init !is BuildInitializer.Instantiate) continue
            for (arg in init.args) {
                val def = arg.def as? BuildCoreDef.Trampoline ?: continue
                val tr = plan.trampolines[def.index] as? BuildTrampoline.Async ?: continue
                if (tr.kind in RAW_CALL_TRAMPOLINES) {
                    rawCallableModules.add(init.module)
                }
            }
        }
        val compiledModules = LinkedHashMap<Int, CompiledAotModule>(plan.modules.size)
        val irModules = LinkedHashMap<Int, IrModule>(plan.modules.size)
        for (mod in plan.modules) {
            val wasmBytes = Files.readAllBytes(decomposedDir.resolve(mod.file))
            val ir = ChicoryWasmAdapter.convert(wasmBytes, moduleIndex = mod.index)
            irModules[mod.index] = ir
            compiledModules[mod.index] = compileModule(ir, "Core${mod.index}", mod.index in rawCallableModules)
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
