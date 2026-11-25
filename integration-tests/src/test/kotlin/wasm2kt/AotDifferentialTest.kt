package wasm2kt

import kotlinx.serialization.json.JsonArray
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Test
import wasm2kt.runtime.ComponentBackendFactory
import wasm2kt.testing.JsonBackend
import wasm2kt.testing.JsonHostImports
import wasm2kt.testing.PlanTypes
import wasm2kt.tooling.ComponentCodegen
import wasm2kt.tooling.Decomposer
import wasm2kt.tooling.aot.AotComponentCodegen
import wasm2kt.tooling.aot.AotComponentCompiler
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals

/**
 * Runs the differential test suite of [ChicoryDifferentialTest] using the Phase 1 AOT static
 * component wiring backend instead of Chicory, plus multi-component resource transfer checks.
 */
class AotDifferentialTest : ChicoryDifferentialTest() {
    override fun pureJvmBackend(name: String, imports: JsonImports?): JsonBackend {
        val types = types(name)
        val factory = compiledFactory(name, decomposedDirFor(name))
        return JsonBackend(factory.instantiate(imports?.let { JsonHostImports(types, it) }), types)
    }

    @Test
    fun `composed resources match Wasmtime`() {
        val wasm = fixture("composed-res")
        val decomposed = workRoot.resolve("composed-res-decomposed")
        if (!Files.exists(decomposed.resolve("plan.json"))) {
            Decomposer.decompose(wasm, decomposed)
        }
        val types = PlanTypes.fromDirectory(decomposed)
        val aotFactory = compiledFactory("composed-res", decomposed)
        val expected = JsonBackend(WasmtimeBackend(wasm, null, null, jni), types).use {
            it.invoke("g", JsonArray(emptyList()))
        }
        val actual = JsonBackend(aotFactory.instantiate(null), types).use {
            it.invoke("g", JsonArray(emptyList()))
        }
        assertEquals(expected.toString(), actual.toString())
    }

    private fun decomposedDirFor(name: String): Path =
        Paths.get("runtime-chicory/src/test/resources/components/$name").toAbsolutePath()

    private companion object {
        val workRoot: Path = Paths.get("integration-tests/build/aot-differential").toAbsolutePath()
        val factoryCache = ConcurrentHashMap<String, ComponentBackendFactory>()

        fun compiledFactory(name: String, decomposedDir: Path): ComponentBackendFactory =
            factoryCache.computeIfAbsent(name) {
                val base = workRoot.resolve(name)
                val classesDir = base.resolve("classes")
                val resourcesDir = base.resolve("resources")
                val kotlinDir = base.resolve("kotlin")
                val compiledKtDir = base.resolve("compiled-kotlin")
                base.toFile().deleteRecursively()

                val pkg = "wasm2kt.aot.diff." + name.lowercase().replace(Regex("[^a-z0-9]+"), "_")
                val internalPkg = "$pkg.wasm2kt"
                val compiled = AotComponentCompiler.compile(decomposedDir, classesDir, resourcesDir, internalPkg)
                AotComponentCodegen.generateComponentOnly(kotlinDir, name, pkg, compiled, internalPkg)

                val sources = Files.walk(kotlinDir).use { s ->
                    s.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.map { it.toString() }.toList()
                }
                Files.createDirectories(compiledKtDir)
                val cp = listOf(System.getProperty("java.class.path"), classesDir.toString()).joinToString(File.pathSeparator)
                val exit = K2JVMCompiler().exec(
                    System.err,
                    "-Xcontext-parameters", "-jvm-target", "17", "-no-stdlib",
                    "-classpath", cp, "-d", compiledKtDir.toString(),
                    *sources.toTypedArray(),
                )
                assertEquals(ExitCode.OK, exit, "Generated AOT component '$name' must compile")

                val urls = arrayOf(
                    compiledKtDir.toUri().toURL(),
                    classesDir.toUri().toURL(),
                    resourcesDir.toUri().toURL(),
                )
                val loader = URLClassLoader(urls, AotDifferentialTest::class.java.classLoader)
                val factoryClassName = "$pkg.${ComponentCodegen.componentObjectName(name)}"
                loader.loadClass(factoryClassName).getField("INSTANCE").get(null) as ComponentBackendFactory
            }
    }
}
