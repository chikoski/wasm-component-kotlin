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
            import com.example.ct.CounterComponent

            fun main() {
                val imports = object : RootWorld.Imports {
                    override val envProvider = object : RootWorld.EnvProvider {
                        override fun getMultiplier(): Int = 6
                        override fun formatTag(prefix: String, code: UInt): String = "${'$'}prefix#${'$'}code"
                    }
                }
                val rt = RootWorld.withSession(RoundtripComponent, imports) {
                    "scaled=${'$'}{with(RootWorld.MathApi) { scale(7) }},tag=${'$'}{RootWorld.describeCode("ERR", 404u)}"
                }
                val ct = com.example.ct.RootWorld.withSession(CounterComponent) {
                    com.example.ct.RootWorld.Counter(41).use { c -> c.increment(); "counter=${'$'}{c.getValue()}" }
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
}
