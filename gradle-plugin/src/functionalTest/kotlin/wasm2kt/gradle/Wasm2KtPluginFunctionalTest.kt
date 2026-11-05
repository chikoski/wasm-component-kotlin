package wasm2kt.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import wasm2kt.ast.WitResolve
import wasm2kt.generator.WitToKotlinPoetConverter
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Wasm2KtPluginFunctionalTest {
    private val fixtures: Path = Paths.get(System.getProperty("wasm2kt.fixtures"))
    private val examples: Path = Paths.get(System.getProperty("wasm2kt.examples"))
    private val testRepo = System.getProperty("wasm2kt.testRepo")
    private val kotlinVersion = System.getProperty("wasm2kt.kotlinVersion")

    private fun runner(dir: File, vararg args: String): GradleRunner = GradleRunner.create()
        .withProjectDir(dir)
        .withPluginClasspath()
        .withArguments(*args, "--stacktrace")
        .forwardOutput()
        .apply { System.getProperty("wasm2kt.testKitDir")?.takeIf { it.isNotBlank() }?.let { withTestKitDir(File(it)) } }

    @Test
    fun `kotlin jvm project runs a component with AOT-compiled core modules`(@TempDir dir: File) {
        Files.copy(fixtures.resolve("roundtrip.wasm"), dir.resolve("roundtrip.wasm").toPath())
        Files.copy(fixtures.resolve("counter.wasm"), dir.resolve("counter.wasm").toPath())
        dir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "consumer"
            pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
            $ISOLATED_BUILD_CACHE
            """.trimIndent(),
        )
        dir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm") version "$kotlinVersion"
                id("io.github.wasm2kt")
                application
            }
            repositories {
                maven(url = uri("${testRepo.replace("\\", "/")}"))
                mavenCentral()
            }
            wasm2kt {
                components {
                    register("roundtrip") {
                        source.set(file("roundtrip.wasm"))
                        packageName.set("com.example.rt")
                    }
                    register("counter") {
                        source.set(file("counter.wasm"))
                        packageName.set("com.example.ct")
                    }
                }
            }
            application { mainClass.set("MainKt") }
            """.trimIndent(),
        )
        val src = dir.resolve("src/main/kotlin").apply { mkdirs() }
        src.resolve("Main.kt").writeText(
            """
            import com.example.rt.*
            import com.example.rt.example.roundtrip.env_provider.EnvProvider
            import com.example.rt.exports.example.roundtrip.math_api.scale
            import com.example.ct.CounterComponent
            import com.example.ct.exports.example.counter.counter_api.Counter

            fun main() {
                val imports = imports(
                    exampleRoundtripEnvProvider = {
                        object : EnvProvider {
                            override fun getMultiplier(): Int = 6
                            override fun formatTag(prefix: String, code: UInt): String = "${'$'}prefix#${'$'}code"
                        }
                    },
                )
                val rt = withSession(RoundtripComponent, imports) {
                    "scaled=${'$'}{scale(7)},tag=${'$'}{describeCode("ERR", 404u)}"
                }
                val ct = com.example.ct.withSession(CounterComponent) {
                    Counter(41).use { c -> c.increment(); "counter=${'$'}{c.getValue()}" }
                }
                println("RESULT ${'$'}rt;${'$'}ct")
            }
            """.trimIndent(),
        )

        val first = runner(dir, "run", "--configuration-cache", "--build-cache").build()
        assertTrue("RESULT scaled=42,tag=ERR#404;counter=42" in first.output, "unexpected output:\n${first.output}")
        assertEquals(TaskOutcome.SUCCESS, first.task(":wasm2ktCompileRoundtrip")?.outcome)

        // Unchanged inputs: everything is up to date, and the configuration cache is reused.
        val second = runner(dir, "run", "--configuration-cache", "--build-cache").build()
        assertTrue("Reusing configuration cache" in second.output || "Configuration cache entry reused" in second.output, second.output)
        for (task in listOf(":wasm2ktDecomposeRoundtrip", ":wasm2ktCompileRoundtrip", ":wasm2ktGenerateRoundtrip")) {
            assertEquals(TaskOutcome.UP_TO_DATE, second.task(task)?.outcome, "$task should be up to date")
        }

        // After a clean, outputs come back from the build cache instead of being recomputed.
        val third = runner(dir, "clean", "run", "--configuration-cache", "--build-cache").build()
        assertEquals(TaskOutcome.FROM_CACHE, third.task(":wasm2ktCompileRoundtrip")?.outcome)
        assertTrue("RESULT scaled=42,tag=ERR#404;counter=42" in third.output)
    }

    @Test
    fun `unsupported features are reported at build time`(@TempDir dir: File) {
        Files.copy(fixtures.resolve("composed-res.wasm"), dir.resolve("composed.wasm").toPath())
        dir.resolve("settings.gradle.kts").writeText("rootProject.name = \"consumer\"\n$ISOLATED_BUILD_CACHE")
        fun buildScript(fail: Boolean) = dir.resolve("build.gradle.kts").writeText(
            """
            plugins { id("io.github.wasm2kt") }
            repositories {
                maven(url = uri("${testRepo.replace("\\", "/")}"))
                mavenCentral()
            }
            wasm2kt {
                failOnUnsupportedFeatures.set($fail)
                components {
                    register("composed") {
                        source.set(file("composed.wasm"))
                        packageName.set("com.example.composed")
                    }
                }
            }
            """.trimIndent(),
        )

        // Default: a warning naming what will fail at run time, and the build goes on.
        buildScript(fail = false)
        val warned = runner(dir, "wasm2ktDecomposeComposed", "--build-cache").build()
        assertTrue("composed.wasm uses features the Chicory backend does not support yet" in warned.output, warned.output)
        assertTrue("ResourceTransferOwn" in warned.output, warned.output)
        // The warning comes again when decomposing is UP-TO-DATE, and when it comes FROM-CACHE (#78).
        val again = runner(dir, "wasm2ktDecomposeComposed", "--build-cache").build()
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":wasm2ktDecomposeComposed")?.outcome)
        assertTrue("composed.wasm uses features the Chicory backend does not support yet" in again.output, again.output)
        dir.resolve("build/wasm2kt").deleteRecursively() // this project has no `clean` task
        val cached = runner(dir, "wasm2ktDecomposeComposed", "--build-cache").build()
        assertEquals(TaskOutcome.FROM_CACHE, cached.task(":wasm2ktDecomposeComposed")?.outcome)
        assertTrue("composed.wasm uses features the Chicory backend does not support yet" in cached.output, cached.output)

        // Opted in: the same check fails the build.
        buildScript(fail = true)
        val failed = runner(dir, "wasm2ktDecomposeComposed").buildAndFail()
        assertTrue("ResourceTransferOwn" in failed.output, failed.output)
        assertEquals(TaskOutcome.FAILED, failed.task(":wasm2ktDecomposeComposed")?.outcome)
    }

    @Test
    fun `interfaces provided by a library project are used instead of generated`(@TempDir dir: File) {
        Files.copy(fixtures.resolve("same-name.wasm"), dir.resolve("same-name.wasm").toPath())
        dir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "consumer"
            pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
            include("hostlib", "app")
            """.trimIndent(),
        )
        val repos = """
            repositories {
                maven(url = uri("${testRepo.replace("\\", "/")}"))
                mavenCentral()
            }
        """.trimIndent()
        dir.resolve("build.gradle.kts").writeText("""plugins { kotlin("jvm") version "$kotlinVersion" apply false }""")

        // The library: the imported interfaces of same-name, generated with --interfaces-only.
        val lib = dir.resolve("hostlib").apply { mkdirs() }
        lib.resolve("build.gradle.kts").writeText(
            """
            plugins { kotlin("jvm") }
            $repos
            dependencies { implementation("io.github.wasm2kt:runtime-api:${System.getProperty("wasm2kt.version")}") }
            """.trimIndent(),
        )
        val converter = WitToKotlinPoetConverter(
            WitResolve.parse(Files.readString(fixtures.resolve("same-name.json"))),
            "com.example.hostlib",
            interfacesOnly = true,
        )
        converter.generate().forEach { it.writeTo(lib.resolve("src/main/kotlin").toPath()) }
        checkNotNull(converter.manifest).writeTo(lib.resolve("src/main/resources").toPath())

        val app = dir.resolve("app").apply { mkdirs() }
        Files.move(dir.resolve("same-name.wasm").toPath(), app.resolve("same-name.wasm").toPath())
        app.resolve("build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm")
                id("io.github.wasm2kt")
                application
            }
            $repos
            dependencies { implementation(project(":hostlib")) }
            wasm2kt {
                components {
                    register("samename") {
                        source.set(file("same-name.wasm"))
                        packageName.set("com.example.app")
                    }
                }
            }
            application { mainClass.set("MainKt") }
            """.trimIndent(),
        )
        app.resolve("src/main/kotlin").apply { mkdirs() }.resolve("Main.kt").writeText(
            """
            import com.example.app.*
            import com.example.hostlib.example.fs.types.ErrorCode as FsErrorCode
            import com.example.hostlib.example.fs.types.Types as FsTypes
            import com.example.hostlib.example.http.types.ErrorCode as HttpErrorCode
            import com.example.hostlib.example.http.types.Tally
            import com.example.hostlib.example.http.types.Types as HttpTypes

            class CountingTally(private var value: UInt) : Tally {
                override fun get(): UInt { value += 10u; return value }
                override fun close() {}
            }

            fun main() {
                val imports = imports(
                    exampleHttpTypes = {
                        object : HttpTypes {
                            override fun newTally(start: UInt): Tally = CountingTally(start)
                            override fun describe(e: HttpErrorCode): String = "http:${'$'}{e.code}"
                        }
                    },
                    exampleFsTypes = {
                        object : FsTypes {
                            override fun describe(e: FsErrorCode): String = if (e is FsErrorCode.Missing) "missing:${'$'}{e.value}" else "denied"
                        }
                    },
                )
                println("RESULT " + withSession(SamenameComponent, imports) { run(7u) })
            }
            """.trimIndent(),
        )

        val result = runner(dir, ":app:run", "--configuration-cache").build()
        assertTrue("RESULT http:7|missing:f7|denied|tally=17" in result.output, result.output)
        val generated = app.resolve("build/wasm2kt/samename/kotlin/com/example/app")
        assertTrue(generated.resolve("RootWorld.kt").isFile, generated.list().orEmpty().joinToString())
        assertTrue(!generated.resolve("example").exists(), "the library's interfaces must not be generated again")
    }

    @Test
    fun `example 05-chicory-gradle-plugin builds and runs with the plugin`(@TempDir dir: File) {
        val exampleDir = examples.resolve("05-chicory-gradle-plugin")
        Files.copy(fixtures.resolve("roundtrip.wasm"), dir.resolve("roundtrip.wasm").toPath())
        Files.copy(exampleDir.resolve("build.gradle.kts"), dir.resolve("build.gradle.kts").toPath())
        Files.copy(exampleDir.resolve("Main.kt"), dir.resolve("Main.kt").toPath())
        dir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "05-chicory-gradle-plugin"
            pluginManagement {
                repositories { gradlePluginPortal(); mavenCentral() }
                plugins { kotlin("jvm") version "$kotlinVersion" }
            }
            dependencyResolutionManagement {
                repositories {
                    maven(url = uri("${testRepo.replace("\\", "/")}"))
                    mavenCentral()
                }
            }
            $ISOLATED_BUILD_CACHE
            """.trimIndent(),
        )

        val result = runner(dir, "run", "--configuration-cache").build()
        assertTrue("scaled = 42, tag = ERR#404, count = 15" in result.output, "unexpected output:\n${result.output}")
        assertEquals(TaskOutcome.SUCCESS, result.task(":wasm2ktCompileRoundtrip")?.outcome)
    }
}

/**
 * A build cache of the test project's own: TestKit's default one outlives a test run, so a later run
 * would find the outputs of an earlier one and see FROM-CACHE where the test expects a real build.
 */
private const val ISOLATED_BUILD_CACHE = "buildCache { local { directory = file(\"build-cache\") } }"
