package wasm2kt.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Wasm2KtPluginFunctionalTest {
    private val fixtures: Path = Paths.get(System.getProperty("wasm2kt.fixtures"))
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
        dir.resolve("settings.gradle.kts").writeText("""rootProject.name = "consumer"""")
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
        val warned = runner(dir, "wasm2ktDecomposeComposed").build()
        assertTrue("composed.wasm uses features the Chicory backend does not support yet" in warned.output, warned.output)
        assertTrue("ResourceTransferOwn" in warned.output, warned.output)

        // Opted in: the same check fails the build.
        buildScript(fail = true)
        val failed = runner(dir, "wasm2ktDecomposeComposed").buildAndFail()
        assertTrue("ResourceTransferOwn" in failed.output, failed.output)
        assertEquals(TaskOutcome.FAILED, failed.task(":wasm2ktDecomposeComposed")?.outcome)
    }
}
