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
 * (`04-algebraic-types-and-mock` and `05-chicory-gradle-plugin`) execute and produce expected output.
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

        // 05: Chicory + Gradle plugin example — run ComponentBuild, compile Main.kt, and execute on Chicory AOT
        run {
            val dirName = "05-chicory-gradle-plugin"
            val pkg = "com.example.chicory"
            val caseDir = tempDir.resolve(dirName)
            val built = ComponentBuild.run(
                component = fixtures.resolve("roundtrip.wasm"),
                componentName = "roundtrip",
                packageName = pkg,
                workDir = caseDir.resolve("build"),
            )
            val classesDir = caseDir.resolve("classes")
            compileSources(
                sources = collectKotlinSources(built.kotlinDir) + listOf(examplesDir.resolve("$dirName/Main.kt")),
                classpath = listOf(System.getProperty("java.class.path"), built.classesDir.toString()),
                classesDir = classesDir,
                label = dirName,
            )
            val output = invokeMain(listOf(classesDir, built.classesDir, built.resourcesDir), "$pkg.MainKt")
            assertTrue("scaled = 42, tag = ERR#404, count = 15" in output, "05 output:\n$output")
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
