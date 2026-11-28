package wasm2kt.tooling

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import wasm2kt.ast.WitResolve
import wasm2kt.generator.WitToKotlinPoetConverter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URI
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies in the fast JVM CI job (without native Wasmtime or external wasm-tools) that every
 * `examples/<dir>/Main.kt` compiles against its generated bindings and that the pure-JVM examples
 * (`04-algebraic-types-and-mock` through `07-wasi-cli`) execute and produce expected output.
 */
class ExamplesBuildTest {
    private val fixtures: Path = Paths.get(System.getProperty("wasm2kt.fixtures"))
    private val examplesDir: Path = Paths.get(System.getProperty("wasm2kt.examples"))

    @Test
    fun `all examples under examples directory compile and pure-JVM examples execute`(@TempDir tempDir: Path) {
        val discoveredDirs = examplesDir.listDirectoryEntries()
            .filter { it.isDirectory() }
            .map { it.name }
            .sorted()

        val expectedDirs = listOf(
            "01-arithmetic",
            "02-host-imports",
            "03-counter-resource",
            "04-algebraic-types-and-mock",
            "05-chicory-gradle-plugin",
            "06-async-streams",
            "07-wasi-cli",
        )
        assertEquals(
            expectedDirs,
            discoveredDirs,
            "Every directory under examples/ must be registered in ExamplesBuildTest",
        )

        for (dirName in discoveredDirs) {
            val dir = examplesDir.resolve(dirName)
            assertTrue(Files.isRegularFile(dir.resolve("Main.kt")), "Missing Main.kt in examples/$dirName")
            assertTrue(Files.isRegularFile(dir.resolve("README.md")), "Missing README.md in examples/$dirName")
        }

        // 01..03: Wasmtime CLI examples — decompose .wasm on Chicory to obtain wit.json, generate bindings, and compile Main.kt
        val wasmtimeExamples = listOf(
            Triple("01-arithmetic", "arithmetic.wasm", "com.example.arithmetic"),
            Triple("02-host-imports", "live_calc.wasm", "com.example.live"),
            Triple("03-counter-resource", "counter.wasm", "com.example.counter"),
        )
        for ((dirName, wasmFile, pkg) in wasmtimeExamples) {
            val caseDir = tempDir.resolve(dirName)
            val decomposed = caseDir.resolve("decomposed")
            Decomposer.decompose(fixtures.resolve(wasmFile), decomposed)
            val srcDir = caseDir.resolve("src")
            val classesDir = caseDir.resolve("classes")
            generateBindingsFromWitJson(decomposed.resolve("wit.json"), pkg, srcDir)
            compileSources(
                sources = collectKotlinSources(srcDir) + listOf(examplesDir.resolve("$dirName/Main.kt")),
                classpath = listOf(System.getProperty("java.class.path")),
                classesDir = classesDir,
                label = dirName,
            )
        }

        // 04: InMemoryWasmtimeSession example — generate bindings from calculator.json, compile, and execute Main.kt
        run {
            val dirName = "04-algebraic-types-and-mock"
            val pkg = "com.example.wasm"
            val caseDir = tempDir.resolve(dirName)
            val srcDir = caseDir.resolve("src")
            val classesDir = caseDir.resolve("classes")
            generateBindingsFromWitJson(fixtures.resolve("calculator.json"), pkg, srcDir)
            compileSources(
                sources = collectKotlinSources(srcDir) + listOf(examplesDir.resolve("$dirName/Main.kt")),
                classpath = listOf(System.getProperty("java.class.path")),
                classesDir = classesDir,
                label = dirName,
            )
            val output = invokeMain(listOf(classesDir), "$pkg.MainKt")
            assertTrue("Permissions: read=true, write=false, execute=true" in output, "04 output:\n$output")
            assertTrue("Status Code: 103" in output, "04 output:\n$output")
            assertTrue("Session closed properly: true" in output, "04 output:\n$output")
        }

        // 05: Gradle plugin example on default AOT backend
        val output05 = runPluginExample(tempDir, "05-chicory-gradle-plugin", "roundtrip.wasm", "roundtrip", "com.example.chicory")
        assertTrue("scaled = 42, tag = ERR#404, count = 15" in output05, "05 output:\n$output05")

        // 06: Async streams, futures, and suspending host imports on default AOT backend
        val output06 = runPluginExample(tempDir, "06-async-streams", "async-pipeline.wasm", "async-pipeline", "com.example.asyncpipeline")
        assertTrue(
            "numbers = [10, 11, 12, 13], doubledSum = 92, count = 3, formatted = [#1:ALPHA, #2:BETA, #3:GAMMA]" in output06,
            "06 output:\n$output06",
        )

        // 07: Standard WASI 0.3 CLI export (`wasi:cli/run`) with mounted directory read/write on default AOT backend
        val output07 = runPluginExample(
            tempDir = tempDir,
            dirName = "07-wasi-cli",
            wasmFile = "wasi-cli.wasm",
            componentName = "wasi-cli",
            pkg = "com.example.wasicli",
            libraryClasspath = listOf(wasiLibraryEntry()),
        ).replace("\r\n", "\n")
        assertTrue("exitCode = 0" in output07, "07 output:\n$output07")
        assertTrue("stdout = wrote 3 line(s) from input.txt to output.txt tag=cli" in output07, "07 output:\n$output07")
        assertTrue("stderr =" !in output07, "07 output:\n$output07")
        assertTrue(
            """
            outputFile =
            [cli] 1: first item
            [cli] 2: second item
            [cli] 3: third item
            # footer: generated by 07-wasi-cli
            """.trimIndent() in output07,
            "07 output:\n$output07",
        )
    }

    @Test
    fun `async-pipeline partial stream consumption and wasi-cli error exit and mount cases run on Chicory`(@TempDir tempDir: Path) {
        val asyncBuilt = ComponentBuild.run(
            component = fixtures.resolve("async-pipeline.wasm"),
            componentName = "async-pipeline",
            packageName = "com.example.asyncedge",
            workDir = tempDir.resolve("async-build"),
        )
        val cliBuilt = ComponentBuild.run(
            component = fixtures.resolve("wasi-cli.wasm"),
            componentName = "wasi-cli",
            packageName = "com.example.cliedge",
            workDir = tempDir.resolve("cli-build"),
            libraryClasspath = listOf(wasiLibraryEntry()),
        )

        val harness = tempDir.resolve("EdgeHarness.kt")
        Files.writeString(
            harness,
            """
            package harness

            import com.example.asyncedge.AsyncPipelineComponent
            import com.example.asyncedge.WitResult as AsyncWitResult
            import com.example.asyncedge.example.async_pipeline.enricher.Enricher
            import com.example.asyncedge.imports as asyncImports
            import com.example.asyncedge.processItems
            import com.example.asyncedge.produceNumbers
            import com.example.asyncedge.sumStream
            import com.example.asyncedge.withSessionBlocking as withAsyncSessionBlocking
            import com.example.cliedge.WasiCliComponent
            import com.example.cliedge.exports.wasi.cli.run.run as cliRun
            import com.example.cliedge.openSession as openCliSession
            import com.example.cliedge.withSessionBlocking as withCliSessionBlocking
            import kotlinx.coroutines.async
            import kotlinx.coroutines.flow.flowOf
            import kotlinx.coroutines.flow.take
            import kotlinx.coroutines.flow.toList
            import kotlinx.coroutines.runBlocking
            import wasm2kt.runtime.ComponentExitException
            import wasm2kt.runtime.ComponentTrapException
            import wasm2kt.runtime.WitResultException
            import wasm2kt.runtime.WitResultValue
            import wasm2kt.wasi.WasiExitException
            import wasm2kt.wasi.runWasiCommand
            import wasm2kt.wasi.wasi03Imports
            import java.io.ByteArrayOutputStream
            import java.nio.file.Files

            fun main() {
                // 0. WitResultValue runtime contract & Ok/Err behavior on generated WitResult
                val okResult: WitResultValue<Int, String> = AsyncWitResult.Ok(42)
                check(okResult.isOk && !okResult.isErr)
                check(okResult.getOrNull() == 42 && okResult.errorOrNull() == null)
                check(okResult.getOrThrow() == 42)
                val errResult: WitResultValue<Int, String> = AsyncWitResult.Err("boom")
                check(!errResult.isOk && errResult.isErr)
                check(errResult.getOrNull() == null && errResult.errorOrNull() == "boom")
                val errThrown = runCatching { errResult.getOrThrow() }.exceptionOrNull()
                check(errThrown is WitResultException && errThrown.error == "boom") { "unexpected errThrown: ${'$'}errThrown" }

                // 1. async-pipeline partial consumption: take(2) cancels output stream cleanly, countDeferred resolves to 2,
                //    and subsequent calls on the same session still succeed.
                val enricher = asyncImports(
                    exampleAsyncPipelineEnricher = {
                        object : Enricher {
                            override suspend fun formatItem(id: UInt, payload: String): String =
                                "#${'$'}id:${'$'}{payload.uppercase()}"
                        }
                    },
                )
                withAsyncSessionBlocking(AsyncPipelineComponent, enricher) {
                    val (outFlow, countDeferred) = processItems(flowOf("one", "two", "three", "four"))
                    val collectedJob = async { outFlow.take(2).toList() }
                    val countJob = async { countDeferred.await() }
                    val collected = collectedJob.await()
                    val count = countJob.await()
                    check(collected == listOf("#1:ONE", "#2:TWO")) { "partial stream mismatch: ${'$'}collected" }
                    check(count == 2u) { "partial count mismatch: ${'$'}count" }

                    check(produceNumbers(1u, 3u).toList() == listOf(1u, 2u, 3u)) { "same session produceNumbers failed" }
                    check(sumStream(flowOf(5u, 10u, 15u)) == 30u) { "same session sumStream failed" }
                }

                // 2. wasi-cli missing arguments
                val missingArgsErr = ByteArrayOutputStream()
                val missingArgsCode = runWasiCommand {
                    withCliSessionBlocking(
                        WasiCliComponent,
                        wasi03Imports {
                            args("wasi-cli")
                            stderr = missingArgsErr
                        },
                    ) { cliRun() }
                }
                check(missingArgsCode == 1) { "expected exit 1 for missing args, got ${'$'}missingArgsCode" }
                check("usage:" in missingArgsErr.toString(Charsets.UTF_8)) {
                    "unexpected missing-args stderr: ${'$'}{missingArgsErr.toString(Charsets.UTF_8)}"
                }

                // 3. wasi-cli missing input file
                val emptyWork = Files.createTempDirectory("wasm2kt-cli-empty")
                try {
                    val missingInputErr = ByteArrayOutputStream()
                    val missingInputCode = runWasiCommand {
                        withCliSessionBlocking(
                            WasiCliComponent,
                            wasi03Imports {
                                args("wasi-cli", "missing.txt", "out.txt")
                                stderr = missingInputErr
                                mounts { readWrite("/work", emptyWork) }
                            },
                        ) { cliRun() }
                    }
                    check(missingInputCode == 1) { "expected exit 1 for missing input, got ${'$'}missingInputCode" }
                    check("failed to open" in missingInputErr.toString(Charsets.UTF_8)) {
                        "unexpected missing-input stderr: ${'$'}{missingInputErr.toString(Charsets.UTF_8)}"
                    }
                } finally {
                    emptyWork.toFile().deleteRecursively()
                }

                // 4. wasi-cli no preopened directory
                val noMountErr = ByteArrayOutputStream()
                val noMountCode = runWasiCommand {
                    withCliSessionBlocking(
                        WasiCliComponent,
                        wasi03Imports {
                            args("wasi-cli", "in.txt", "out.txt")
                            stderr = noMountErr
                        },
                    ) { cliRun() }
                }
                check(noMountCode == 1) { "expected exit 1 for no mount, got ${'$'}noMountCode" }
                check("no preopened directory" in noMountErr.toString(Charsets.UTF_8)) {
                    "unexpected no-mount stderr: ${'$'}{noMountErr.toString(Charsets.UTF_8)}"
                }

                // 5. wasi-cli guest fixture `resolve_path` longest-prefix routing across `/`, `/work`, `/out`
                val rootDir = Files.createTempDirectory("wasm2kt-cli-root")
                val workDir = Files.createTempDirectory("wasm2kt-cli-work")
                val outDir = Files.createTempDirectory("wasm2kt-cli-out")
                try {
                    Files.writeString(workDir.resolve("in.txt"), "alpha\nbeta\n")
                    val multiOut = ByteArrayOutputStream()
                    val multiCode = runWasiCommand {
                        withCliSessionBlocking(
                            WasiCliComponent,
                            wasi03Imports {
                                args("wasi-cli", "/work/in.txt", "/out/result.txt")
                                env("TAG" to "multi")
                                stdout = multiOut
                                mounts {
                                    readWrite("/", rootDir)
                                    readWrite("/work", workDir)
                                    readWrite("/out", outDir)
                                }
                            },
                        ) { cliRun() }
                    }
                    check(multiCode == 0) { "expected exit 0 for multi-mount, got ${'$'}multiCode" }
                    check(Files.exists(outDir.resolve("result.txt"))) { "result.txt missing in /out mount" }
                    check(!Files.exists(rootDir.resolve("out"))) { "root mount should not receive /out/result.txt" }
                    val written = Files.readString(outDir.resolve("result.txt"))
                    check(written == "[multi] 1: alpha\n[multi] 2: beta\n") { "unexpected multi-mount output: ${'$'}written" }
                    check("wrote 2 line(s) from /work/in.txt to /out/result.txt tag=multi" in multiOut.toString(Charsets.UTF_8)) {
                        "unexpected multi-mount stdout: ${'$'}{multiOut.toString(Charsets.UTF_8)}"
                    }

                    // Prefix boundary check: `/work` must not match `/workshop/in.txt`
                    val boundaryErr = ByteArrayOutputStream()
                    val boundaryCode = runWasiCommand {
                        withCliSessionBlocking(
                            WasiCliComponent,
                            wasi03Imports {
                                args("wasi-cli", "/workshop/in.txt", "/out/result.txt")
                                stderr = boundaryErr
                                mounts {
                                    readOnly("/work", workDir)
                                    readWrite("/out", outDir)
                                }
                            },
                        ) { cliRun() }
                    }
                    check(boundaryCode == 1) { "expected exit 1 on prefix boundary mismatch, got ${'$'}boundaryCode" }
                    check("no preopened directory matches '/workshop/in.txt'" in boundaryErr.toString(Charsets.UTF_8)) {
                        "unexpected prefix-boundary stderr: ${'$'}{boundaryErr.toString(Charsets.UTF_8)}"
                    }

                    // Trailing slash mounts: `/work/` and `/out/`
                    val slashCode = runWasiCommand {
                        withCliSessionBlocking(
                            WasiCliComponent,
                            wasi03Imports {
                                args("wasi-cli", "/work/in.txt", "/out/slash.txt")
                                env("TAG" to "slash")
                                mounts {
                                    readOnly("/work/", workDir)
                                    readWrite("/out/", outDir)
                                }
                            },
                        ) { cliRun() }
                    }
                    check(slashCode == 0) { "expected exit 0 for trailing-slash mounts, got ${'$'}slashCode" }
                    check(Files.readString(outDir.resolve("slash.txt")) == "[slash] 1: alpha\n[slash] 2: beta\n")
                } finally {
                    rootDir.toFile().deleteRecursively()
                    workDir.toFile().deleteRecursively()
                    outDir.toFile().deleteRecursively()
                }

                // 6. wasi-cli `--exit 0`, `--exit 42`, and post-exit reuse diagnostics
                val exit0Imports = wasi03Imports { args("wasi-cli", "--exit", "0") }
                check(runWasiCommand { withCliSessionBlocking(WasiCliComponent, exit0Imports) { cliRun() } } == 0)
                openCliSession(WasiCliComponent, exit0Imports).use { session ->
                    context(session) {
                        val first = runBlocking { runCatching { cliRun() }.exceptionOrNull() }
                        check(first is ComponentExitException && first.statusCode == 0) { "expected ComponentExitException(0), got ${'$'}first" }
                        check(first is WasiExitException && first.statusCode == 0) { "expected WasiExitException(0), got ${'$'}first" }
                        val reuse = runBlocking { runCatching { cliRun() }.exceptionOrNull() }
                        check(reuse is ComponentTrapException && reuse.message!!.contains("has already exited")) {
                            "expected post-exit message, got ${'$'}reuse"
                        }
                        check(!reuse.message!!.contains("earlier trap")) { "unexpected trap wording: ${'$'}{reuse.message}" }
                    }
                }

                val exit42Imports = wasi03Imports { args("wasi-cli", "--exit", "42") }
                check(runWasiCommand { withCliSessionBlocking(WasiCliComponent, exit42Imports) { cliRun() } } == 42)

                // 7. wasi-cli `--spawn-exit 19` from spawned guest task + post-exit reuse
                val spawnExitImports = wasi03Imports { args("wasi-cli", "--spawn-exit", "19") }
                check(runWasiCommand { withCliSessionBlocking(WasiCliComponent, spawnExitImports) { cliRun() } } == 19)
                openCliSession(WasiCliComponent, spawnExitImports).use { session ->
                    context(session) {
                        val first = runBlocking { runCatching { cliRun() }.exceptionOrNull() }
                        check(first is WasiExitException && first.statusCode == 19) { "expected WasiExitException(19), got ${'$'}first" }
                        val reuse = runBlocking { runCatching { cliRun() }.exceptionOrNull() }
                        check(reuse is ComponentTrapException && reuse.message!!.contains("has already exited")) {
                            "expected post-exit message after spawn exit, got ${'$'}reuse"
                        }
                    }
                }
            }
            """.trimIndent(),
        )

        val classesDir = tempDir.resolve("edge-classes")
        val sources = collectKotlinSources(asyncBuilt.kotlinDir) +
            collectKotlinSources(cliBuilt.kotlinDir) +
            listOf(harness)
        compileSources(
            sources = sources,
            classpath = listOf(
                System.getProperty("java.class.path"),
                asyncBuilt.classesDir.toString(),
                cliBuilt.classesDir.toString(),
            ),
            classesDir = classesDir,
            label = "edge-cases",
        )
        invokeMain(
            classpathDirs = listOf(
                classesDir,
                asyncBuilt.classesDir,
                asyncBuilt.resourcesDir,
                cliBuilt.classesDir,
                cliBuilt.resourcesDir,
            ),
            mainClassName = "harness.EdgeHarnessKt",
        )
    }

    private fun runPluginExample(
        tempDir: Path,
        dirName: String,
        wasmFile: String,
        componentName: String,
        pkg: String,
        libraryClasspath: List<Path> = emptyList(),
    ): String {
        val caseDir = tempDir.resolve(dirName)
        val built = ComponentBuild.run(
            component = fixtures.resolve(wasmFile),
            componentName = componentName,
            packageName = pkg,
            workDir = caseDir.resolve("build"),
            libraryClasspath = libraryClasspath,
        )
        val classesDir = caseDir.resolve("classes")
        compileSources(
            sources = collectKotlinSources(built.kotlinDir) + listOf(examplesDir.resolve("$dirName/Main.kt")),
            classpath = listOf(System.getProperty("java.class.path"), built.classesDir.toString()),
            classesDir = classesDir,
            label = dirName,
        )
        return invokeMain(listOf(classesDir, built.classesDir, built.resourcesDir), "$pkg.MainKt")
    }

    private fun wasiLibraryEntry(): Path {
        val manifestUrl = javaClass.getResource("/META-INF/wasm2kt/interfaces/wasm2kt.wasi.json")
            ?: error("runtime-wasi interface manifest missing on test classpath")
        return when (manifestUrl.protocol) {
            "jar" -> Paths.get(URI(manifestUrl.path.substringBefore("!")))
            "file" -> Paths.get(manifestUrl.toURI()).parent.parent.parent.parent
            else -> error("Unsupported manifest URL protocol: $manifestUrl")
        }
    }

    private fun generateBindingsFromWitJson(witJsonPath: Path, pkg: String, outDir: Path) {
        Files.createDirectories(outDir)
        val resolve = WitResolve.parse(Files.readString(witJsonPath))
        WitToKotlinPoetConverter(resolve = resolve, targetPackage = pkg).generate().forEach {
            it.writeTo(outDir)
        }
    }

    private fun collectKotlinSources(root: Path): List<Path> =
        Files.walk(root).use { stream -> stream.filter { it.toString().endsWith(".kt") }.sorted().toList() }

    private fun compileSources(sources: List<Path>, classpath: List<String>, classesDir: Path, label: String) {
        Files.createDirectories(classesDir)
        val args = listOf(
            "-Xcontext-parameters",
            "-jvm-target",
            "21",
            "-no-stdlib",
            "-classpath",
            classpath.joinToString(File.pathSeparator),
            "-d",
            classesDir.toString(),
        ) + sources.map { it.toString() }
        val exit = K2JVMCompiler().exec(System.err, *args.toTypedArray())
        assertEquals(ExitCode.OK, exit, "Example $label failed to compile")
    }

    private fun invokeMain(classpathDirs: List<Path>, mainClassName: String): String {
        val urls = classpathDirs.map { it.toUri().toURL() }.toTypedArray()
        val originalOut = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
        try {
            URLClassLoader(urls, javaClass.classLoader).use { loader ->
                val mainClass = loader.loadClass(mainClassName)
                mainClass.getDeclaredMethod("main").invoke(null)
            }
        } finally {
            System.setOut(originalOut)
        }
        return buffer.toString(Charsets.UTF_8)
    }
}
