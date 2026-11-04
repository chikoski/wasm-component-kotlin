package wasm2kt

import com.github.ajalt.clikt.core.parse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import wasm2kt.ast.WitResolve
import wasm2kt.cli.GenerateCommand
import wasm2kt.generator.InterfaceManifest
import wasm2kt.generator.WitToKotlinPoetConverter
import wasm2kt.runtime.ComponentBackendFactory
import wasm2kt.runtime.chicory.ChicoryComponent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Interface mappings (#54, ADR-0014): the imported interfaces of `same-name` are generated once into
 * a library with `--interfaces-only`, and the world is generated against it, so the host
 * implementation compiled with the library is passed to `imports(...)` as is. Runs on both backends.
 */
class InterfaceMappingE2ETest {
    private val wasmToolsBin: String = System.getenv("WASM_TOOLS_BIN")?.takeIf { it.isNotBlank() }
        ?: Paths.get(System.getProperty("user.home"), ".cargo", "bin", "wasm-tools").takeIf { Files.isExecutable(it) }?.toString()
        ?: "wasm-tools"

    private val libPkg = "com.example.hostlib"
    private val appPkg = "com.example.app"

    private val sameNameWit: Path get() = Paths.get(requireNotNull(javaClass.getResource("/fixtures/same-name.wit")).toURI())

    /** Host implementations compiled into the library, knowing nothing about the app's bindings. */
    private val libraryHosts = """
        package $libPkg

        import $libPkg.example.fs.types.ErrorCode as FsErrorCode
        import $libPkg.example.fs.types.Types as FsTypes
        import $libPkg.example.http.types.ErrorCode as HttpErrorCode
        import $libPkg.example.http.types.Tally
        import $libPkg.example.http.types.Types as HttpTypes

        class CountingTally(private var value: UInt) : Tally {
            override fun get(): UInt { value += 10u; return value }
            override fun close() {}
        }

        class LibraryHttpTypes : HttpTypes {
            override fun newTally(start: UInt): Tally = CountingTally(start)
            override fun describe(e: HttpErrorCode): String = "host-http:${'$'}{e.code}"
        }

        class LibraryFsTypes : FsTypes {
            override fun describe(e: FsErrorCode): String = when (e) {
                is FsErrorCode.Missing -> "host-fs:missing:${'$'}{e.value}"
                FsErrorCode.Denied -> "host-fs:denied"
            }
        }
    """.trimIndent()

    private val appHarness = """
        package $appPkg

        import java.nio.file.Path
        import wasm2kt.runtime.ComponentBackendFactory
        import $libPkg.LibraryFsTypes
        import $libPkg.LibraryHttpTypes
        import $appPkg.exports.example.fs.types.ErrorCode as GuestFsErrorCode
        import $appPkg.exports.example.fs.types.describe as describeFs
        import $appPkg.exports.example.http.types.ErrorCode as GuestHttpErrorCode
        import $appPkg.exports.example.http.types.Tally as GuestTally
        import $appPkg.exports.example.http.types.describe as describeHttp

        val imports = imports(exampleHttpTypes = { LibraryHttpTypes() }, exampleFsTypes = { LibraryFsTypes() })

        context(session: Session)
        fun exercise(): String {
            val run = run(7u)
            val http = describeHttp(GuestHttpErrorCode(3u))
            val fs = describeFs(GuestFsErrorCode.Missing("x")) + "," + describeFs(GuestFsErrorCode.Denied)
            val tally = GuestTally(5u).use { it.get(); it.get() }
            return "${'$'}run;${'$'}http;${'$'}fs;tally=${'$'}tally"
        }

        fun runWasmtime(wasm: Path): String = withSession(wasm, imports) { exercise() }

        fun runChicory(component: ComponentBackendFactory): String = withSession(component, imports) { exercise() }
    """.trimIndent()

    private val expected =
        "host-http:7|host-fs:missing:f7|host-fs:denied|tally=17;guest-http:3;guest-fs:missing:x,guest-fs:denied;tally=7"

    private fun kotlinc(sources: List<String>, classes: Path, classpath: List<Path> = emptyList()) {
        Files.createDirectories(classes)
        val cp = (classpath.map { it.toString() } + System.getProperty("java.class.path")).joinToString(java.io.File.pathSeparator)
        val exit = org.jetbrains.kotlin.cli.jvm.K2JVMCompiler().exec(
            System.err,
            *(listOf("-Xcontext-parameters", "-jvm-target", "21", "-no-stdlib", "-classpath", cp, "-d", classes.toString()) + sources).toTypedArray(),
        )
        assertEquals(org.jetbrains.kotlin.cli.common.ExitCode.OK, exit, "Generated bindings must compile")
    }

    /** Generates and compiles the library; its classes directory holds the manifest as a resource. */
    private fun buildLibrary(tempDir: Path): Path {
        val src = tempDir.resolve("lib-src")
        val classes = tempDir.resolve("lib-classes")
        GenerateCommand().parse(
            listOf("--wasm-tools-path", wasmToolsBin, "--interfaces-only", "-o", src.toString(), "--resources-output", classes.toString(), "-p", libPkg, sameNameWit.toString()),
        )
        val libDir = src.resolve(libPkg.replace('.', '/'))
        assertTrue(Files.exists(libDir.resolve("example/http/types/Types.kt")))
        assertTrue(Files.exists(libDir.resolve("example/fs/types/Types.kt")))
        assertFalse(Files.exists(libDir.resolve("SameNameWorld.kt")), "--interfaces-only must not generate the world")
        assertFalse(Files.exists(libDir.resolve("exports")), "--interfaces-only must not generate exports")
        assertFalse("WitSession" in Files.readString(libDir.resolve("WitSupport.kt")), "a library has no sessions")
        Files.writeString(libDir.resolve("Hosts.kt"), libraryHosts)
        kotlinc(generatedSources(src), classes)
        return classes
    }

    private fun buildApp(tempDir: Path, library: Path, extraArgs: List<String>): ClassLoader {
        val src = tempDir.resolve("app-src")
        GenerateCommand().parse(listOf("--wasm-tools-path", wasmToolsBin, "-o", src.toString(), "-p", appPkg) + extraArgs + sameNameWit.toString())
        val appDir = src.resolve(appPkg.replace('.', '/'))
        assertFalse(Files.exists(appDir.resolve("example")), "mapped interfaces must not be generated again")
        assertTrue(Files.exists(appDir.resolve("exports/example/http/types/Types.kt")), "exports are always generated")
        val world = Files.readString(appDir.resolve("SameNameWorld.kt"))
        assertTrue("import $libPkg.example.http.types.Types as" in world, world)
        Files.writeString(appDir.resolve("Harness.kt"), appHarness)
        val classes = tempDir.resolve("app-classes")
        kotlinc(generatedSources(src), classes, listOf(library))
        return java.net.URLClassLoader(arrayOf(classes.toUri().toURL(), library.toUri().toURL()), javaClass.classLoader)
    }

    private fun chicoryComponent() =
        ChicoryComponent.fromDirectory(Paths.get("runtime-chicory/src/test/resources/components/same-name").toAbsolutePath())

    @Test
    fun `the library manifest lists the generated interfaces`(@TempDir tempDir: Path) {
        val manifest = InterfaceManifest.readAll(listOf(buildLibrary(tempDir))).single()
        assertEquals(libPkg, manifest.basePackage)
        assertEquals(
            mapOf("example:fs/types@0.1.0" to "$libPkg.example.fs.types", "example:http/types@0.1.0" to "$libPkg.example.http.types"),
            manifest.interfaces,
        )
    }

    @Test
    fun `a library's host implementations run on Wasmtime`(@TempDir tempDir: Path) {
        val library = buildLibrary(tempDir)
        val loader = buildApp(tempDir, library, listOf("--library", library.toString()))
        val wasm = Paths.get(requireNotNull(javaClass.getResource("/fixtures/same-name.wasm")).toURI())
        assertEquals(expected, loader.loadClass("$appPkg.HarnessKt").getDeclaredMethod("runWasmtime", Path::class.java).invoke(null, wasm))
    }

    @Test
    fun `explicit mappings run on Chicory`(@TempDir tempDir: Path) {
        val library = buildLibrary(tempDir)
        val loader = buildApp(
            tempDir,
            library,
            listOf(
                "--with", "example:http/types@0.1.0=$libPkg.example.http.types",
                "--with", "example:fs/types@0.1.0=$libPkg.example.fs.types",
            ),
        )
        val result = loader.loadClass("$appPkg.HarnessKt").getDeclaredMethod("runChicory", ComponentBackendFactory::class.java)
            .invoke(null, chicoryComponent())
        assertEquals(expected, result)
    }

    // ------------------------------------------------------------------ generator rules

    private fun resolve(tempDir: Path, wit: String): WitResolve {
        val witFile = tempDir.resolve("input-${wit.hashCode()}.wit")
        Files.writeString(witFile, wit.trimIndent())
        return WitResolve.parse(GenerateCommand.extractWitJsonViaWasmTools(wasmToolsBin, witFile))
    }

    /** `b` uses a type of `a`, and has an alias that names the generation's own `WitResult`. */
    private val layered = """
        package example:app@0.1.0;

        world app {
            import example:lib/b@0.2.0;
            use example:lib/b@0.2.0.{outcome};
            export check: func(n: u32) -> outcome;
        }

        package example:lib@0.2.0 {
            interface a {
                record point { x: u32 }
            }
            interface b {
                use a.{point};
                type outcome = result<point, string>;
                shift: func(p: point) -> outcome;
            }
        }
    """

    private fun libraryManifest(tempDir: Path, wit: String, base: String): InterfaceManifest {
        val converter = WitToKotlinPoetConverter(resolve(tempDir, wit), base, interfacesOnly = true)
        converter.generate()
        return checkNotNull(converter.manifest)
    }

    @Test
    fun `aliases of mapped interfaces are expanded and dependencies come along`(@TempDir tempDir: Path) {
        val libSrc = tempDir.resolve("lib-src")
        val libConverter = WitToKotlinPoetConverter(resolve(tempDir, layered), "lib.layers", interfacesOnly = true)
        libConverter.generate().forEach { it.writeTo(libSrc) }
        val manifest = checkNotNull(libConverter.manifest)
        assertEquals(setOf("example:lib/a@0.2.0", "example:lib/b@0.2.0"), manifest.interfaces.keys)
        val libClasses = tempDir.resolve("lib-classes")
        kotlinc(generatedSources(libSrc), libClasses)

        val appSrc = tempDir.resolve("app-src")
        val files = WitToKotlinPoetConverter(resolve(tempDir, layered), "app.layers", libraryManifests = listOf(manifest)).generate()
        files.forEach { it.writeTo(appSrc) }
        assertEquals(setOf("app.layers"), files.map { it.packageName }.toSet(), "only the world and its support are generated")
        val world = files.single { it.name == "AppWorld" }.content
        // `outcome` is the app's own WitResult of the library's point, not the library's typealias.
        assertTrue(Regex("""fun check\(n: UInt\): WitResult<Point, String>""").containsMatchIn(world), world)
        assertTrue("import lib.layers.example.lib.a.Point" in world, world)
        assertFalse("lib.layers.example.lib.b.Outcome" in world, world)
        kotlinc(generatedSources(appSrc), tempDir.resolve("app-classes"), listOf(libClasses))
    }

    @Test
    fun `a mapped interface needs the interfaces its types use`(@TempDir tempDir: Path) {
        val e = assertThrows<IllegalArgumentException> {
            WitToKotlinPoetConverter(resolve(tempDir, layered), "app", interfaceMappings = mapOf("example:lib/b@0.2.0" to "lib.b")).generate()
        }
        assertTrue("'example:lib/b@0.2.0' is mapped to 'lib.b'" in e.message!! && "'example:lib/a@0.2.0'" in e.message!!, e.message)
    }

    @Test
    fun `explicit mappings must name an imported interface at its version`(@TempDir tempDir: Path) {
        val resolve = resolve(tempDir, sameNameWitText())
        fun generate(key: String) = WitToKotlinPoetConverter(resolve, "app", interfaceMappings = mapOf(key to "lib.x")).generate()

        val version = assertThrows<IllegalArgumentException> { generate("example:http/types@0.2.0") }
        assertTrue("does not match the version" in version.message!! && "example:http/types@0.1.0" in version.message!!, version.message)
        val unknown = assertThrows<IllegalArgumentException> { generate("example:none/types@0.1.0") }
        assertTrue("does not import this interface" in unknown.message!!, unknown.message)
    }

    @Test
    fun `exported interfaces cannot be mapped`(@TempDir tempDir: Path) {
        val wit = """
            package example:exp@0.1.0;
            world w { export api; }
            interface api { ping: func() -> u32; }
        """
        val e = assertThrows<IllegalArgumentException> {
            WitToKotlinPoetConverter(resolve(tempDir, wit), "app", interfaceMappings = mapOf("example:exp/api@0.1.0" to "lib.api")).generate()
        }
        assertTrue("only exports it" in e.message!!, e.message)
    }

    @Test
    fun `a library generated from another version is an error`(@TempDir tempDir: Path) {
        val older = libraryManifest(tempDir, sameNameWitText().replace("0.1.0", "0.0.9"), "lib.old")
        val resolve = resolve(tempDir, sameNameWitText())

        val auto = assertThrows<IllegalArgumentException> {
            WitToKotlinPoetConverter(resolve, "app", libraryManifests = listOf(older)).generate()
        }
        assertTrue("imports 'example:http/types@0.1.0'" in auto.message!! && "provides 'example:http/types@0.0.9'" in auto.message!!, auto.message)

        val explicit = assertThrows<IllegalArgumentException> {
            WitToKotlinPoetConverter(
                resolve,
                "app",
                interfaceMappings = mapOf("example:fs/types@0.1.0" to "lib.old.example.fs.types"),
                libraryManifests = listOf(older),
            ).generate()
        }
        assertTrue("generated from 'example:fs/types@0.0.9'" in explicit.message!!, explicit.message)
    }

    private fun sameNameWitText(): String = Files.readString(sameNameWit)
}
