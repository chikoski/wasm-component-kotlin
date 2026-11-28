package wasm2kt.bench

import wasm2kt.tooling.BuildBackend
import wasm2kt.tooling.ComponentBuild
import wasm2kt.tooling.aot.AotComponentCodegen
import wasm2kt.tooling.aot.AotComponentCompiler
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.io.path.name

/**
 * Builds async and WASI 0.3 benchmark components (`async-stream`, `async-pipeline`, `wasi-03`)
 * for both Chicory and AOT backends, and records build-time output size/time stats.
 */
fun main(args: Array<String>) {
    val fixtures = Paths.get(args[0])
    val out = Paths.get(args[1])
    val kotlinOut = out.resolve("kotlin")
    val wasiLibrary = resolveWasiLibraryPath()

    val chicoryStats = StringBuilder("component\tcomponent_bytes\tcore_modules\tcore_bytes\tfunctions\tinterpreted_functions\tclass_files\tclass_bytes\tmeta_bytes\tplan_bytes\tbuild_ms\n")
    val aotStats = StringBuilder("component\tcomponent_bytes\tcore_modules\tcore_bytes\tfunctions\tsplit_functions\tgenerated_helpers\tclass_files\tclass_bytes\tdata_bytes\tmax_method_bytes\tbuild_ms\n")

    for (name in args.drop(2)) {
        val basePkg = "bench.async." + name.replace('-', '_')
        val aotPkg = "${basePkg}_aot"
        val libs = if (name == "wasi-03") listOf(wasiLibrary) else emptyList()
        val wasm = fixtures.resolve("$name.wasm")

        val chicoryWork = out.resolve("work/$name-chicory")
        chicoryWork.toFile().deleteRecursively()
        val cStart = System.nanoTime()
        val cBuilt = ComponentBuild.run(
            component = wasm,
            componentName = name,
            packageName = basePkg,
            workDir = chicoryWork,
            libraryClasspath = libs,
            backend = BuildBackend.CHICORY,
        )
        val cBuildMs = (System.nanoTime() - cStart) / 1_000_000
        copyTree(cBuilt.kotlinDir, kotlinOut)
        copyTree(cBuilt.classesDir, out.resolve("classes"))
        copyTree(cBuilt.resourcesDir, out.resolve("resources"))

        val decomposed = chicoryWork.resolve("decomposed")
        copyTree(decomposed, out.resolve("decomposed/$name"))

        val coreFiles = Files.list(decomposed).use { s -> s.filter { it.name.matches(Regex("core\\d+\\.wasm")) }.toList() }
        val functions = coreFiles.sumOf { f -> com.dylibso.chicory.wasm.Parser.parse(f).functionSection().functionCount() }
        val interpreted = coreFiles.sumOf { f ->
            val cfg = com.dylibso.chicory.build.time.compiler.Config.builder()
                .withWasmFile(f).withName("$basePkg.recount.C").withTargetClassFolder(chicoryWork.resolve("recount/classes"))
                .withTargetSourceFolder(chicoryWork.resolve("recount/src")).withTargetWasmFolder(chicoryWork.resolve("recount/res"))
                .withInterpreterFallback(com.dylibso.chicory.compiler.InterpreterFallback.SILENT).build()
            com.dylibso.chicory.build.time.compiler.Generator(cfg).generateResources().size
        }
        val cClasses = Files.walk(cBuilt.classesDir).use { s -> s.filter { it.toString().endsWith(".class") }.toList() }
        val cMetaBytes = Files.walk(cBuilt.resourcesDir).use { s -> s.filter { it.toString().endsWith(".meta") }.mapToLong { Files.size(it) }.sum() }
        val cPlanBytes = Files.walk(cBuilt.resourcesDir).use { s -> s.filter { it.fileName.toString() == "plan.json" }.mapToLong { Files.size(it) }.sum() }
        chicoryStats.append(
            listOf(
                name, Files.size(wasm), coreFiles.size, coreFiles.sumOf { Files.size(it) }, functions,
                interpreted, cClasses.size, cClasses.sumOf { Files.size(it) }, cMetaBytes, cPlanBytes, cBuildMs,
            ).joinToString("\t"),
        ).append('\n')

        val aotWork = out.resolve("work/$name-aot")
        aotWork.toFile().deleteRecursively()
        val aotClasses = aotWork.resolve("classes")
        val aotResources = aotWork.resolve("resources")
        val aotKotlin = aotWork.resolve("kotlin")
        val aotInternalPkg = "$aotPkg.wasm2kt"
        val aStart = System.nanoTime()
        val aotCompiled = AotComponentCompiler.compile(decomposed, aotClasses, aotResources, aotInternalPkg)
        AotComponentCodegen.generate(
            decomposedDir = decomposed,
            outputDir = aotKotlin,
            componentName = "$name-aot",
            packageName = aotPkg,
            compiled = aotCompiled,
            internalPackage = aotInternalPkg,
            libraryClasspath = libs,
        )
        val aBuildMs = (System.nanoTime() - aStart) / 1_000_000
        copyTree(aotKotlin, kotlinOut)
        copyTree(aotClasses, out.resolve("classes"))
        copyTree(aotResources, out.resolve("resources"))

        val aClassFiles = Files.walk(aotClasses).use { s -> s.filter { it.toString().endsWith(".class") }.toList() }
        val aDataBytes = if (Files.exists(aotResources)) {
            Files.walk(aotResources).use { s -> s.filter { it.toString().endsWith(".data") }.mapToLong { Files.size(it) }.sum() }
        } else 0L
        val splitFns = aotCompiled.modules.values.sumOf { it.stats.splitFunctions }
        val genHelpers = aotCompiled.modules.values.sumOf { it.stats.generatedSplitHelpers }
        val maxMethodBytes = aotCompiled.modules.values.maxOfOrNull { it.stats.maxMethodCodeBytes } ?: 0
        aotStats.append(
            listOf(
                name, Files.size(wasm), coreFiles.size, coreFiles.sumOf { Files.size(it) }, functions,
                splitFns, genHelpers, aClassFiles.size, aClassFiles.sumOf { Files.size(it) },
                aDataBytes, maxMethodBytes, aBuildMs,
            ).joinToString("\t"),
        ).append('\n')

        println("prepared async $name: chicory=${cBuildMs}ms (classes=${cClasses.sumOf { Files.size(it) }}B, meta=${cMetaBytes}B, plan=${cPlanBytes}B), aot=${aBuildMs}ms (classes=${aClassFiles.sumOf { Files.size(it) }}B, data=${aDataBytes}B)")
    }
    Files.writeString(out.resolve("async-build-stats.tsv"), chicoryStats)
    Files.writeString(out.resolve("async-aot-build-stats.tsv"), aotStats)
    print(chicoryStats)
    print(aotStats)
}

private fun resolveWasiLibraryPath(): Path {
    val url = requireNotNull(object {}.javaClass.getResource("/META-INF/wasm2kt/interfaces/wasm2kt.wasi.json"))
    return when (url.protocol) {
        "jar" -> Paths.get(java.net.URI(url.path.substringBefore("!")))
        "file" -> Paths.get(url.toURI()).parent.parent.parent.parent
        else -> error("Unsupported manifest URI: $url")
    }
}

private fun copyTree(from: Path, to: Path) {
    if (!Files.exists(from)) return
    Files.walk(from).use { s ->
        s.forEach { p ->
            val t = to.resolve(from.relativize(p).toString())
            if (Files.isDirectory(p)) Files.createDirectories(t)
            else Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
