package wasm2kt.bench

import wasm2kt.ast.WitResolve
import wasm2kt.generator.WitToKotlinPoetConverter
import wasm2kt.tooling.ComponentBuild
import wasm2kt.tooling.ComponentCodegen
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name

/**
 * Builds the benchmark components the way the Gradle plugin does (decompose, AOT-compile, generate)
 * and records build-time statistics. Usage: `Prepare <fixturesDir> <outDir> <name>...`
 *
 * Writes `<outDir>/kotlin` (full bindings including the Wasmtime sessions, plus `<Name>Component`),
 * `<outDir>/classes` and `<outDir>/resources` (the compiled core modules), `<outDir>/decomposed/<name>`
 * (for the interpreter) and `<outDir>/build-stats.tsv`.
 */
fun main(args: Array<String>) {
    val fixtures = Paths.get(args[0])
    val out = Paths.get(args[1])
    val kotlinOut = out.resolve("kotlin")
    val stats = StringBuilder("component\tcomponent_bytes\tcore_modules\tcore_bytes\tfunctions\tinterpreted_functions\tclass_files\tclass_bytes\tmeta_bytes\tbuild_ms\n")
    for (name in args.drop(2)) {
        val pkg = "bench." + name.replace('-', '_')
        val work = out.resolve("work/$name")
        work.toFile().deleteRecursively()
        val wasm = fixtures.resolve("$name.wasm")
        val started = System.nanoTime()
        val built = ComponentBuild.run(wasm, name, pkg, work)
        val buildMs = (System.nanoTime() - started) / 1_000_000

        // Full bindings (with Wasmtime sessions) so one API drives every backend.
        val decomposed = work.resolve("decomposed")
        val resolve = WitResolve.parse(Files.readString(decomposed.resolve("wit.json")))
        WitToKotlinPoetConverter(resolve = resolve, targetPackage = pkg, emitWasmtimeSessions = true)
            .generate().forEach { it.writeTo(kotlinOut) }
        val componentFile = ComponentCodegen.componentObjectName(name) + ".kt"
        val src = Files.walk(built.kotlinDir).use { s -> s.filter { it.name == componentFile }.findFirst().get() }
        val dst = kotlinOut.resolve(built.kotlinDir.relativize(src))
        Files.createDirectories(dst.parent)
        Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING)

        copyTree(built.classesDir, out.resolve("classes"))
        copyTree(built.resourcesDir, out.resolve("resources"))
        copyTree(decomposed, out.resolve("decomposed/$name"))

        val coreFiles = Files.list(decomposed).use { s -> s.filter { it.name.matches(Regex("core\\d+\\.wasm")) }.toList() }
        val functions: Int = coreFiles.sumOf { f -> com.dylibso.chicory.wasm.Parser.parse(f).functionSection().functionCount() }
        // Recompile to learn which functions fell back to the interpreter (too large for a JVM method).
        val interpretedIdx = coreFiles.flatMap { f ->
            val cfg = com.dylibso.chicory.build.time.compiler.Config.builder()
                .withWasmFile(f).withName("$pkg.recount.C").withTargetClassFolder(work.resolve("recount/classes"))
                .withTargetSourceFolder(work.resolve("recount/src")).withTargetWasmFolder(work.resolve("recount/res"))
                .withInterpreterFallback(com.dylibso.chicory.compiler.InterpreterFallback.SILENT).build()
            com.dylibso.chicory.build.time.compiler.Generator(cfg).generateResources().sorted()
        }
        val interpreted = interpretedIdx.size
        if (interpretedIdx.isNotEmpty()) println("$name: interpreted functions $interpretedIdx")
        val classFiles = Files.walk(built.classesDir).use { s -> s.filter { it.toString().endsWith(".class") }.toList() }
        val metaBytes = Files.walk(built.resourcesDir).use { s -> s.filter { it.toString().endsWith(".meta") }.mapToLong { Files.size(it) }.sum() }
        stats.append(
            listOf(
                name, Files.size(wasm), coreFiles.size, coreFiles.sumOf { Files.size(it) }, functions, interpreted,
                classFiles.size, classFiles.sumOf { Files.size(it) }, metaBytes, buildMs,
            ).joinToString("\t"),
        ).append('\n')
        println("prepared $name in $buildMs ms")
    }
    Files.writeString(out.resolve("build-stats.tsv"), stats)
    print(stats)
}

private fun copyTree(from: Path, to: Path) {
    Files.walk(from).use { s ->
        s.forEach { p ->
            val t = to.resolve(from.relativize(p).toString())
            if (Files.isDirectory(p)) Files.createDirectories(t)
            else Files.copy(p, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
