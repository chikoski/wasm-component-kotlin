package wasm2kt.tooling

import com.dylibso.chicory.build.time.compiler.Config
import com.dylibso.chicory.build.time.compiler.Generator
import com.dylibso.chicory.compiler.InterpreterFallback
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * Compiles a decomposed component's core modules to JVM bytecode with Chicory's build-time
 * compiler, so nothing is parsed into an interpreter or JIT-compiled on the device (ART cannot load
 * bytecode generated at runtime anyway).
 */
object CoreModuleCompiler {
    /** A compiled core module: `<className>` is a [com.dylibso.chicory.runtime.Machine] with an `(Instance)` constructor. */
    class Compiled(
        val index: Int,
        val machineClassName: String,
        val metaResource: String,
        val interpretedFunctions: Int = 0,
        val splitMethods: Int = 0,
        val hotspotOversizedMethods: Int = 0,
        val artOversizedMethods: Int = 0,
        val maxMethodCodeBytes: Int = 0,
    )

    /** The class and resource names [compile] produces, without compiling (they are deterministic). */
    fun layout(decomposedDir: Path, packageName: String): List<Compiled> =
        PlanFiles.coreModules(decomposedDir).map { (index, _) -> describe(index, packageName) }

    private fun describe(
        index: Int,
        packageName: String,
        interpreted: Int = 0,
        stats: JvmMethodSplitter.MethodSizeStats = JvmMethodSplitter.MethodSizeStats(0, 0, 0, 0, 0),
    ) = Compiled(
        index = index,
        machineClassName = "$packageName.Core${index}Machine",
        metaResource = "/" + packageName.replace('.', '/') + "/Core$index.meta",
        interpretedFunctions = interpreted,
        splitMethods = stats.splitMethods,
        hotspotOversizedMethods = stats.hotspotOversizedMethods,
        artOversizedMethods = stats.artOversizedMethods,
        maxMethodCodeBytes = stats.maxMethodCodeBytes,
    )

    /**
     * Compiles every `core<N>.wasm` listed in [decomposedDir]'s plan.
     *
     * @param classesDir receives the machine classes (and Chicory's helper classes)
     * @param resourcesDir receives `<N>.meta` files: each module minus compiled function bodies,
     *   which Chicory still needs for types, data segments and any interpreter fallback
     * @param packageName JVM package for the generated classes, e.g. `com.example.calc.wasm2kt`
     */
    fun compile(decomposedDir: Path, classesDir: Path, resourcesDir: Path, packageName: String): List<Compiled> {
        Files.createDirectories(classesDir)
        Files.createDirectories(resourcesDir)
        return PlanFiles.coreModules(decomposedDir).map { (index, file) ->
            val baseName = "Core$index"
            val config = Config.builder()
                .withWasmFile(decomposedDir.resolve(file))
                .withName("$packageName.$baseName")
                .withTargetClassFolder(classesDir)
                .withTargetSourceFolder(classesDir.resolveSibling("${classesDir.fileName}-unused-sources"))
                .withTargetWasmFolder(resourcesDir)
                // Functions too large for a JVM method fall back to the interpreter instead of failing the build.
                .withInterpreterFallback(InterpreterFallback.SILENT)
                .build()
            val generator = Generator(config)
            val interpreted = generator.generateResources()
            generator.generateMetaWasm(interpreted)
            val classPrefix = packageName.replace('.', '/') + "/${baseName}Machine"
            val stats = JvmMethodSplitter.processClasses(classesDir, classPrefix)
            describe(index, packageName, interpreted.size, stats)
        }
    }

    /** Formats build-time compiler warnings for core modules that exceed JVM, HotSpot, or ART limits. */
    fun warningsFor(compiled: List<Compiled>): List<String> = buildList {
        for (c in compiled) {
            if (c.interpretedFunctions > 0) {
                add("wasm2kt: ${c.interpretedFunctions} function(s) of core module ${c.index} are too large for a JVM method and will be interpreted")
            }
            if (c.artOversizedMethods > 0) {
                add(
                    "wasm2kt: ${c.artOversizedMethods} method(s) of core module ${c.index} exceed ART's compiler instruction limit (16,383 DEX code units; max ${c.maxMethodCodeBytes} JVM bytes) and will run in ART's interpreter",
                )
            }
            if (c.hotspotOversizedMethods > 0) {
                add(
                    "wasm2kt: ${c.hotspotOversizedMethods} method(s) of core module ${c.index} exceed HotSpot's 8,000-byte JIT limit (DontCompileHugeMethods; max ${c.maxMethodCodeBytes} bytes); pass -XX:-DontCompileHugeMethods on desktop JVM",
                )
            }
        }
    }
}

/** Reads just enough of `plan.json` to find the core module files. */
internal object PlanFiles {
    fun coreModules(decomposedDir: Path): List<Pair<Int, String>> {
        val plan = Json.parseToJsonElement(Files.readString(decomposedDir.resolve("plan.json"))).jsonObject
        return plan.getValue("modules").jsonArray.map {
            val o = it.jsonObject
            o.getValue("index").jsonPrimitive.int to o.getValue("file").jsonPrimitive.content
        }
    }
}
