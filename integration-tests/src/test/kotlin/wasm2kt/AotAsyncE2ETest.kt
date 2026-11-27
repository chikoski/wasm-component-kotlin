@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import wasm2kt.runtime.canonical.InternalWasm2KtApi
import wasm2kt.tooling.aot.AotComponentBuild
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end tests exercising Component Model Async (`stream`, `future`, suspending host imports,
 * concurrent cancellation, multi-component async pipelines) and WASI 0.3 (`wasi-03`, `wasi-cli`)
 * compiled and run on the AOT backend (`AotComponentBuild`, #188).
 */
class AotAsyncE2ETest {
    private fun fixture(name: String): Path =
        Paths.get(requireNotNull(javaClass.getResource("/fixtures/$name.wasm")).toURI())

    private fun wasiLibraryPath(): Path {
        val manifestUrl = requireNotNull(javaClass.getResource("/META-INF/wasm2kt/interfaces/wasm2kt.wasi.json"))
        return when (manifestUrl.protocol) {
            "jar" -> Paths.get(java.net.URI(manifestUrl.path.substringBefore("!")))
            "file" -> Paths.get(manifestUrl.toURI()).parent.parent.parent.parent
            else -> error("Unsupported manifest URI: $manifestUrl")
        }
    }

    private fun assertZeroChicoryDependencies(kotlinDir: Path, resourcesDir: Path, name: String) {
        val resources = Files.walk(resourcesDir).use { s ->
            s.filter { Files.isRegularFile(it) }.toList()
        }
        assertTrue(
            resources.none { it.name == "plan.json" || it.name.endsWith(".meta") },
            "$name AOT resources must not contain plan.json or .meta: $resources",
        )
        val ktFiles = Files.walk(kotlinDir).use { s ->
            s.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()
        }
        assertTrue(ktFiles.isNotEmpty(), "$name must emit Kotlin files")
        for (kt in ktFiles) {
            val text = Files.readString(kt)
            assertTrue("com.dylibso.chicory" !in text, "$kt must not reference chicory")
            assertTrue("wasm2kt.runtime.chicory" !in text, "$kt must not reference runtime.chicory")
            assertTrue("kotlinx.serialization" !in text, "$kt must not reference kotlinx.serialization")
        }
    }

    private fun buildCompileAndRun(
        tempDir: Path,
        fixtureName: String,
        componentName: String,
        pkg: String,
        harnessSource: String,
        libraryClasspath: List<Path> = emptyList(),
        args: Array<Any?> = emptyArray(),
        paramTypes: Array<Class<*>> = emptyArray(),
    ): String {
        val output = AotComponentBuild.run(
            component = fixture(fixtureName),
            componentName = componentName,
            packageName = pkg,
            workDir = tempDir.resolve("build"),
            libraryClasspath = libraryClasspath,
        )
        assertZeroChicoryDependencies(output.kotlinDir, output.resourcesDir, fixtureName)

        if (libraryClasspath.isNotEmpty()) {
            val pkgDir = output.kotlinDir.resolve(pkg.replace('.', '/'))
            assertTrue(!Files.exists(pkgDir.resolve("wasi")), "WASI interfaces should map to runtime-wasi")
        }

        val harnessPath = tempDir.resolve("Harness.kt")
        Files.writeString(harnessPath, harnessSource.trimIndent())

        val compiledDir = tempDir.resolve("compiled-kotlin")
        Files.createDirectories(compiledDir)
        val sources = generatedSources(output.kotlinDir) + listOf(harnessPath.toString())
        val classpath = listOf(System.getProperty("java.class.path"), output.classesDir.toString())
            .joinToString(File.pathSeparator)
        val exit = K2JVMCompiler().exec(
            System.err,
            "-Xcontext-parameters", "-jvm-target", "17", "-no-stdlib",
            "-classpath", classpath,
            "-d", compiledDir.toString(),
            *sources.toTypedArray(),
        )
        assertEquals(ExitCode.OK, exit, "AOT async bindings and harness for $fixtureName must compile")

        val urls = arrayOf(
            compiledDir.toUri().toURL(),
            output.classesDir.toUri().toURL(),
            output.resourcesDir.toUri().toURL(),
        )
        return URLClassLoader(urls, javaClass.classLoader).use { loader ->
            loader.loadClass("$pkg.HarnessKt").getDeclaredMethod("run", *paramTypes).invoke(null, *args) as String
        }
    }

    @Test
    fun `async functions, streams and futures run on AOT`(@TempDir tempDir: Path) {
        val pkg = "com.example.aot.asyncstream"
        val out = buildCompileAndRun(
            tempDir = tempDir,
            fixtureName = "async-stream",
            componentName = "async-stream",
            pkg = pkg,
            harnessSource = """
            package $pkg

            import kotlinx.coroutines.CompletableDeferred
            import kotlinx.coroutines.flow.asFlow
            import kotlinx.coroutines.flow.flowOf
            import kotlinx.coroutines.flow.take
            import kotlinx.coroutines.flow.toList
            import kotlinx.coroutines.runBlocking

            fun run(): String =
                withSession(AsyncStreamComponent) {
                    runBlocking {
                        val doubled = double(21u)
                        val counted = countUp(5u).toList()
                        val summed = sum((1u..10u).asFlow())
                        val empty = sum(flowOf())
                        val roundTrip = sum(countUp(4u))
                        val firstTwo = countUp(100u).take(2).toList()
                        val later = later(41u).await()
                        val tenfold = timesTen(CompletableDeferred(7u))
                        val once = countUp(1u).let { s -> s.toList(); runCatching { s.toList() }.isFailure }
                        "doubled=${'$'}doubled,counted=${'$'}counted,summed=${'$'}summed,empty=${'$'}empty,roundTrip=${'$'}roundTrip," +
                            "firstTwo=${'$'}firstTwo,later=${'$'}later,tenfold=${'$'}tenfold,once=${'$'}once,after=${'$'}{double(1u)}"
                    }
                }
            """,
        )
        assertEquals(
            "doubled=42,counted=[0, 1, 2, 3, 4],summed=55,empty=0,roundTrip=6,firstTwo=[0, 1],later=42,tenfold=70,once=true,after=2",
            out,
        )
    }

    @Test
    fun `suspending host imports, overlapping calls and cancellation run on AOT`(@TempDir tempDir: Path) {
        val pkg = "com.example.aot.asyncimport"
        val out = buildCompileAndRun(
            tempDir = tempDir,
            fixtureName = "async-import",
            componentName = "async-import",
            pkg = pkg,
            harnessSource = """
            package $pkg

            import kotlinx.coroutines.CompletableDeferred
            import kotlinx.coroutines.async
            import kotlinx.coroutines.awaitAll
            import kotlinx.coroutines.awaitCancellation
            import kotlinx.coroutines.delay
            import kotlinx.coroutines.runBlocking
            import kotlinx.coroutines.withTimeoutOrNull
            import java.util.concurrent.atomic.AtomicInteger

            fun run(): String {
                val inFlight = AtomicInteger()
                var maxInFlight = 0
                val cancelled = CompletableDeferred<Unit>()
                val host = object : Imports {
                    override suspend fun fetch(x: UInt): UInt {
                        if (x == 999u) {
                            try { awaitCancellation() } finally { cancelled.complete(Unit) }
                        }
                        maxInFlight = maxOf(maxInFlight, inFlight.incrementAndGet())
                        delay(20)
                        inFlight.decrementAndGet()
                        return x * 10u
                    }
                }
                return withSession(AsyncImportComponent, imports { host }) {
                    runBlocking {
                        val one = fetchPlusOne(4u)
                        val two = fetchSyncPlusTwo(4u)
                        val three = blockingFetch(4u)
                        val overlapping = (1u..3u).map { async { fetchPlusOne(it) } }.awaitAll()
                        val timedOut = withTimeoutOrNull(100) { cancellable(999u) }
                        cancelled.await()
                        val after = cancellable(5u)
                        "one=${'$'}one,two=${'$'}two,three=${'$'}three,overlapping=${'$'}overlapping,maxInFlight=${'$'}maxInFlight," +
                            "timedOut=${'$'}timedOut,after=${'$'}after"
                    }
                }
            }
            """,
        )
        assertEquals("one=41,two=42,three=43,overlapping=[11, 21, 31],maxInFlight=3,timedOut=null,after=50", out)
    }

    @Test
    fun `async pipeline with suspending enricher, streams, and futures runs on AOT`(@TempDir tempDir: Path) {
        val pkg = "com.example.aot.asyncpipeline"
        val out = buildCompileAndRun(
            tempDir = tempDir,
            fixtureName = "async-pipeline",
            componentName = "async-pipeline",
            pkg = pkg,
            harnessSource = """
            package $pkg

            import $pkg.example.async_pipeline.enricher.Enricher
            import kotlinx.coroutines.async
            import kotlinx.coroutines.delay
            import kotlinx.coroutines.flow.asFlow
            import kotlinx.coroutines.flow.flowOf
            import kotlinx.coroutines.flow.map
            import kotlinx.coroutines.flow.toList

            fun run(): String {
                val hostImports = imports(
                    exampleAsyncPipelineEnricher = {
                        object : Enricher {
                            override suspend fun formatItem(id: UInt, payload: String): String {
                                delay(10)
                                return "#${'$'}id:${'$'}{payload.uppercase()}"
                            }
                        }
                    },
                )
                return withSessionBlocking(AsyncPipelineComponent, hostImports) {
                    val numbers = produceNumbers(10u, 4u).toList()
                    val doubledSum = sumStream(numbers.asFlow().map { it * 2u })
                    val (formattedFlow, countDeferred) = processItems(flowOf("alpha", "beta", "gamma"))
                    val collected = async { formattedFlow.toList() }
                    val total = async { countDeferred.await() }
                    val formatted = collected.await()
                    val processedCount = total.await()
                    "numbers=${'$'}numbers,doubledSum=${'$'}doubledSum,count=${'$'}processedCount,formatted=${'$'}formatted"
                }
            }
            """,
        )
        assertEquals(
            "numbers=[10, 11, 12, 13],doubledSum=92,count=3,formatted=[#1:ALPHA, #2:BETA, #3:GAMMA]",
            out,
        )
    }

    @Test
    fun `wasi-03 component runs on AOT with runtime-wasi`(@TempDir tempDir: Path) {
        val workDir = Files.createDirectories(tempDir.resolve("work"))
        Files.writeString(workDir.resolve("input.txt"), "hello aot")
        val pkg = "com.example.aot.wasi03"
        val out = buildCompileAndRun(
            tempDir = tempDir,
            fixtureName = "wasi-03",
            componentName = "wasi-app",
            pkg = pkg,
            libraryClasspath = listOf(wasiLibraryPath()),
            args = arrayOf(workDir),
            paramTypes = arrayOf(Path::class.java),
            harnessSource = """
            package $pkg

            import $pkg.example.wasi_app.audit.Audit
            import kotlinx.coroutines.runBlocking
            import wasm2kt.wasi.WasiExitException
            import wasm2kt.wasi.wasi03Imports
            import java.io.ByteArrayOutputStream
            import java.nio.file.Path

            fun run(workDir: Path): String {
                val stdoutBuf = ByteArrayOutputStream()
                val steps = mutableListOf<String>()
                val appImports = imports(
                    base = wasi03Imports {
                        args("wasi-app", "--aot")
                        env("WASI_ENV" to "aot-ok")
                        stdin("hi-aot".encodeToByteArray())
                        stdout = stdoutBuf
                        mounts {
                            readWrite("/work", workDir)
                        }
                    },
                    exampleWasiAppAudit = {
                        object : Audit {
                            override fun recordStep(tag: String) {
                                steps += tag
                            }
                        }
                    },
                )
                val summary = withSession(WasiAppComponent, appImports) {
                    runBlocking { runSummary() }
                }
                val exitCode = try {
                    withSession(WasiAppComponent, appImports) {
                        exitWith(7u)
                    }
                    -1
                } catch (e: WasiExitException) {
                    e.statusCode
                }
                return "${'$'}summary|stdout=${'$'}{stdoutBuf.toString(Charsets.UTF_8)}|steps=${'$'}{steps.joinToString(",")}|exit=${'$'}exitCode"
            }
            """,
        )
        assertTrue("fs=[mount=/work,read_ok=true,write_ok=true,data=hello aot!]" in out, out)
        assertTrue("|stdout=out:hi-aot|steps=start,done|exit=7" in out, out)
        assertEquals("hello aot!", Files.readString(workDir.resolve("output.txt")))
    }

    @Test
    fun `wasi-cli component runs on AOT with runWasiCommand and mounts`(@TempDir tempDir: Path) {
        val workDir = Files.createDirectories(tempDir.resolve("work"))
        Files.writeString(workDir.resolve("input.txt"), "first item\n\nsecond item\nthird item\n")
        val pkg = "com.example.aot.wasicli"
        val out = buildCompileAndRun(
            tempDir = tempDir,
            fixtureName = "wasi-cli",
            componentName = "wasi-cli",
            pkg = pkg,
            libraryClasspath = listOf(wasiLibraryPath()),
            args = arrayOf(workDir),
            paramTypes = arrayOf(Path::class.java),
            harnessSource = """
            package $pkg

            import $pkg.exports.wasi.cli.run.run as cliRun
            import wasm2kt.wasi.runWasiCommand
            import wasm2kt.wasi.wasi03Imports
            import java.io.ByteArrayOutputStream
            import java.nio.file.Path

            fun run(workDir: Path): String {
                val stdoutBuf = ByteArrayOutputStream()
                val stderrBuf = ByteArrayOutputStream()
                val wasiImports = wasi03Imports {
                    args("wasi-cli", "input.txt", "output.txt")
                    env("TAG" to "aot")
                    stdin("generated by aot")
                    stdout = stdoutBuf
                    stderr = stderrBuf
                    mounts {
                        readWrite("/work", workDir)
                    }
                }
                val exitCode = runWasiCommand {
                    withSessionBlocking(WasiCliComponent, wasiImports) {
                        cliRun()
                    }
                }
                return "exit=${'$'}exitCode|stdout=${'$'}{stdoutBuf.toString(Charsets.UTF_8).trimEnd()}|stderr=${'$'}{stderrBuf.toString(Charsets.UTF_8).trimEnd()}"
            }
            """,
        )
        assertTrue("exit=0|" in out, out)
        val written = Files.readString(workDir.resolve("output.txt"))
        assertTrue("generated by aot" in written, written)
        assertTrue("first item" in written && "second item" in written && "third item" in written, written)
    }
}
