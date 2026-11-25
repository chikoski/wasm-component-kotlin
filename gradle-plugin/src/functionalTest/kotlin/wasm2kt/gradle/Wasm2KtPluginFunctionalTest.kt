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
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Wasm2KtPluginFunctionalTest {
    private val fixtures: Path = Paths.get(System.getProperty("wasm2kt.fixtures"))
    private val examples: Path = Paths.get(System.getProperty("wasm2kt.examples"))
    private val testRepo = System.getProperty("wasm2kt.testRepo")
    private val wasm2ktVersion = System.getProperty("wasm2kt.version")
    private val kotlinVersion = System.getProperty("wasm2kt.kotlinVersion")
    private val agpVersion = System.getProperty("wasm2kt.agpVersion")

    private fun runner(dir: File, vararg args: String, withPluginClasspath: Boolean = true): GradleRunner = GradleRunner.create()
        .withProjectDir(dir)
        .apply { if (withPluginClasspath) withPluginClasspath() }
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
        Files.copy(fixtures.resolve("map-type.wasm"), dir.resolve("composed.wasm").toPath())
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
        val warned = runner(dir, "wasm2ktCheckComposed", "--build-cache").build()
        assertTrue("composed.wasm uses features this backend does not support yet" in warned.output, warned.output)
        assertTrue("Map(TypeMapIndex(0))" in warned.output, warned.output)
        // The warning comes again when checking is UP-TO-DATE, and when it comes FROM-CACHE (#78).
        val again = runner(dir, "wasm2ktCheckComposed", "--build-cache").build()
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":wasm2ktCheckComposed")?.outcome)
        assertTrue("composed.wasm uses features this backend does not support yet" in again.output, again.output)
        dir.resolve("build/wasm2kt").deleteRecursively() // this project has no `clean` task
        val cached = runner(dir, "wasm2ktCheckComposed", "--build-cache").build()
        assertEquals(TaskOutcome.FROM_CACHE, cached.task(":wasm2ktCheckComposed")?.outcome)
        assertTrue("composed.wasm uses features this backend does not support yet" in cached.output, cached.output)

        // Opted in: the same check fails the build.
        buildScript(fail = true)
        val failed = runner(dir, "wasm2ktCheckComposed").buildAndFail()
        assertTrue("Map(TypeMapIndex(0))" in failed.output, failed.output)
        assertEquals(TaskOutcome.FAILED, failed.task(":wasm2ktCheckComposed")?.outcome)
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
        val result = runExample(dir, "05-chicory-gradle-plugin", "roundtrip.wasm")
        assertTrue("scaled = 42, tag = ERR#404, count = 15" in result.output, "unexpected output:\n${result.output}")
        assertEquals(TaskOutcome.SUCCESS, result.task(":wasm2ktCompileRoundtrip")?.outcome)
    }

    @Test
    fun `example 06-async-streams builds and runs with the plugin`(@TempDir dir: File) {
        val result = runExample(dir, "06-async-streams", "async-pipeline.wasm")
        assertTrue(
            "numbers = [10, 11, 12, 13], doubledSum = 92, count = 3, formatted = [#1:ALPHA, #2:BETA, #3:GAMMA]" in result.output,
            "unexpected output:\n${result.output}",
        )
        assertEquals(TaskOutcome.SUCCESS, result.task(":wasm2ktCompileAsyncPipeline")?.outcome)
    }

    @Test
    fun `example 07-wasi-cli builds and runs with the plugin`(@TempDir dir: File) {
        val result = runExample(dir, "07-wasi-cli", "wasi-cli.wasm")
        val normalized = result.output.replace("\r\n", "\n")
        assertTrue("exitCode = 0" in normalized, "unexpected output:\n${result.output}")
        assertTrue(
            "stdout = wrote 3 line(s) from input.txt to output.txt tag=cli" in normalized,
            "unexpected output:\n${result.output}",
        )
        assertTrue("stderr =" !in normalized, "unexpected output:\n${result.output}")
        assertTrue(
            """
            outputFile =
            [cli] 1: first item
            [cli] 2: second item
            [cli] 3: third item
            # footer: generated by 07-wasi-cli
            """.trimIndent() in normalized,
            "unexpected output:\n${result.output}",
        )
        assertEquals(TaskOutcome.SUCCESS, result.task(":wasm2ktCompileWasiCli")?.outcome)
    }

    private fun runExample(dir: File, exampleName: String, wasmFile: String) = run {
        val exampleDir = examples.resolve(exampleName)
        Files.copy(fixtures.resolve(wasmFile), dir.resolve(wasmFile).toPath())
        Files.copy(exampleDir.resolve("build.gradle.kts"), dir.resolve("build.gradle.kts").toPath())
        Files.copy(exampleDir.resolve("Main.kt"), dir.resolve("Main.kt").toPath())
        dir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "$exampleName"
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
        runner(dir, "run", "--configuration-cache").build()
    }

    @Test
    fun `oversized wasm module splits functions and reports compile warnings across cached builds`(@TempDir dir: File) {
        Files.copy(fixtures.resolve("heavy.wasm"), dir.resolve("heavy.wasm").toPath())
        dir.resolve("settings.gradle.kts").writeText("rootProject.name = \"consumer\"\n$ISOLATED_BUILD_CACHE")
        dir.resolve("build.gradle.kts").writeText(
            """
            plugins { id("io.github.wasm2kt") }
            repositories {
                maven(url = uri("${testRepo.replace("\\", "/")}"))
                mavenCentral()
            }
            wasm2kt {
                components {
                    register("heavy") {
                        source.set(file("heavy.wasm"))
                        packageName.set("com.example.heavy")
                    }
                }
            }
            """.trimIndent(),
        )

        val first = runner(dir, "wasm2ktCompileHeavy", "--build-cache", "--info").build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":wasm2ktCompileHeavy")?.outcome)
        assertTrue("wasm2kt: split " in first.output, first.output)
        assertTrue("1 function(s) of core module 0 are too large for a JVM method and will be interpreted" in first.output, first.output)
        assertTrue("exceed ART's compiler instruction limit" !in first.output, first.output)
        assertTrue("exceed HotSpot's 8,000-byte JIT limit" in first.output, first.output)

        val again = runner(dir, "wasm2ktCompileHeavy", "--build-cache").build()
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":wasm2ktCompileHeavy")?.outcome)
        assertTrue("1 function(s) of core module 0 are too large for a JVM method and will be interpreted" in again.output, again.output)
        assertTrue("exceed ART's compiler instruction limit" !in again.output, again.output)
        assertTrue("exceed HotSpot's 8,000-byte JIT limit" in again.output, again.output)

        dir.resolve("build/wasm2kt").deleteRecursively()
        val fromCache = runner(dir, "wasm2ktCompileHeavy", "--build-cache").build()
        assertEquals(TaskOutcome.FROM_CACHE, fromCache.task(":wasm2ktCompileHeavy")?.outcome)
        assertTrue("1 function(s) of core module 0 are too large for a JVM method and will be interpreted" in fromCache.output, fromCache.output)
        assertTrue("exceed ART's compiler instruction limit" !in fromCache.output, fromCache.output)
        assertTrue("exceed HotSpot's 8,000-byte JIT limit" in fromCache.output, fromCache.output)
    }

    @Test
    fun `addWasiDependency wires runtime-wasi and runs WASI 0_3 component`(@TempDir dir: File) {
        Files.copy(fixtures.resolve("wasi-03.wasm"), dir.resolve("wasi-03.wasm").toPath())
        val workDir = dir.resolve("work").apply { mkdirs() }
        workDir.resolve("input.txt").writeText("gradle wasi")
        dir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "wasi-consumer"
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
                addWasiDependency.set(true)
                components {
                    register("wasi-app") {
                        source.set(file("wasi-03.wasm"))
                        packageName.set("com.example.wasiapp")
                    }
                }
            }
            application { mainClass.set("com.example.MainKt") }
            """.trimIndent(),
        )
        val src = dir.resolve("src/main/kotlin/com/example").apply { mkdirs() }
        src.resolve("Main.kt").writeText(
            """
            package com.example

            import com.example.wasiapp.WasiAppComponent
            import com.example.wasiapp.example.wasi_app.audit.Audit
            import com.example.wasiapp.imports
            import com.example.wasiapp.runSummary
            import com.example.wasiapp.withSession
            import kotlinx.coroutines.runBlocking
            import wasm2kt.wasi.wasi03Imports
            import java.io.ByteArrayOutputStream
            import java.nio.file.Paths

            fun main() {
                val stdoutBuf = ByteArrayOutputStream()
                val steps = mutableListOf<String>()
                val wasiBase = wasi03Imports {
                    args("wasi-app", "--from-gradle")
                    env("WASI_PLUGIN" to "enabled")
                    stdin("hi-plugin".encodeToByteArray())
                    stdout = stdoutBuf
                    mounts {
                        readWrite("/work", Paths.get("${workDir.absolutePath.replace("\\", "\\\\")}"))
                    }
                }
                val appImports = imports(
                    base = wasiBase,
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
                println("SUMMARY=" + summary)
                println("STDOUT=" + stdoutBuf.toString(Charsets.UTF_8))
                println("STEPS=" + steps.joinToString(","))
            }
            """.trimIndent(),
        )

        val result = runner(dir, "run", "-q").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":run")?.outcome)
        val generatedPkg = dir.resolve("build/wasm2kt/wasi-app/kotlin/com/example/wasiapp")
        assertTrue(generatedPkg.resolve("RootWorld.kt").isFile, generatedPkg.list().orEmpty().joinToString())
        assertTrue(!generatedPkg.resolve("wasi").exists(), "WASI interfaces must come from runtime-wasi")
        assertTrue("fs=[mount=/work,read_ok=true,write_ok=true,data=gradle wasi!]" in result.output, result.output)
        assertTrue("STDOUT=out:hi-plugin" in result.output, result.output)
        assertTrue("STEPS=start,done" in result.output, result.output)
        assertEquals("gradle wasi!", workDir.resolve("output.txt").readText())
    }

    @Test
    fun `addWasiDependency is independent of addRuntimeDependency and warns when useLibraryInterfaces is false`(@TempDir dir: File) {
        Files.copy(fixtures.resolve("wasi-03.wasm"), dir.resolve("wasi-03.wasm").toPath())
        dir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "wasi-flags"
            pluginManagement {
                repositories { gradlePluginPortal(); mavenCentral() }
                plugins { kotlin("jvm") version "$kotlinVersion" }
            }
            $ISOLATED_BUILD_CACHE
            """.trimIndent(),
        )
        dir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                kotlin("jvm")
                id("io.github.wasm2kt")
            }
            repositories {
                maven(url = uri("${testRepo.replace("\\", "/")}"))
                mavenCentral()
            }
            wasm2kt {
                addRuntimeDependency.set(false)
                addWasiDependency.set(true)
                useLibraryInterfaces.set(false)
                components {
                    register("app") {
                        source.set(file("wasi-03.wasm"))
                        packageName.set("com.example.wasiapp")
                    }
                }
            }
            """.trimIndent(),
        )

        val depsResult = runner(dir, "dependencies", "--configuration", "implementation").build()
        assertEquals(TaskOutcome.SUCCESS, depsResult.task(":dependencies")?.outcome)
        assertTrue(
            "wasm2kt: addWasiDependency is enabled while useLibraryInterfaces is false" in depsResult.output,
            depsResult.output,
        )
        assertTrue("io.github.wasm2kt:runtime-wasi:" in depsResult.output, depsResult.output)
        assertTrue("io.github.wasm2kt:runtime-chicory:" !in depsResult.output, depsResult.output)

        val genResult = runner(dir, "wasm2ktGenerateApp").build()
        assertEquals(TaskOutcome.SUCCESS, genResult.task(":wasm2ktGenerateApp")?.outcome)
        val generatedPkg = dir.resolve("build/wasm2kt/app/kotlin/com/example/wasiapp")
        assertTrue(generatedPkg.resolve("RootWorld.kt").isFile, generatedPkg.list().orEmpty().joinToString())
        assertTrue(generatedPkg.resolve("wasi").isDirectory, generatedPkg.list().orEmpty().joinToString())
    }

    @Test
    fun `android library project compiles components and runs unit tests with configuration cache`(@TempDir dir: File) {
        val androidSdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        check(!androidSdk.isNullOrBlank()) {
            "ANDROID_HOME (or ANDROID_SDK_ROOT) must be set to run the Android functional test (requires Android SDK Platform 35)"
        }
        Files.copy(fixtures.resolve("roundtrip.wasm"), dir.resolve("roundtrip.wasm").toPath())
        Files.copy(fixtures.resolve("counter.wasm"), dir.resolve("counter.wasm").toPath())
        Files.copy(fixtures.resolve("wasi-03.wasm"), dir.resolve("wasi-03.wasm").toPath())
        val workDir = dir.resolve("work").apply { mkdirs() }
        val escapedRepo = testRepo.replace("\\", "/")
        dir.resolve("gradle.properties").writeText("org.gradle.jvmargs=-Xmx2g\n")
        dir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "android-consumer"
            pluginManagement {
                repositories {
                    maven(url = uri("$escapedRepo"))
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
                repositories {
                    maven(url = uri("$escapedRepo"))
                    google()
                    mavenCentral()
                }
            }
            $ISOLATED_BUILD_CACHE
            """.trimIndent(),
        )
        dir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.library") version "$agpVersion"
                // Pins AGP 9's built-in Kotlin compiler plugin to this repo's Kotlin version so it can read runtime-chicory metadata.
                id("org.jetbrains.kotlin.jvm") version "$kotlinVersion" apply false
                id("io.github.wasm2kt") version "$wasm2ktVersion"
            }
            android {
                namespace = "com.example.androidapp"
                compileSdk = 35
                defaultConfig {
                    minSdk = 26
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }
            kotlin {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                }
            }
            wasm2kt {
                addWasiDependency.set(true)
                components {
                    register("roundtrip") {
                        source.set(file("roundtrip.wasm"))
                        packageName.set("com.example.rt")
                    }
                    register("counter") {
                        source.set(file("counter.wasm"))
                        packageName.set("com.example.ct")
                    }
                    register("wasi-app") {
                        source.set(file("wasi-03.wasm"))
                        packageName.set("com.example.wasiapp")
                    }
                }
            }
            dependencies {
                testImplementation("junit:junit:4.13.2")
            }
            """.trimIndent(),
        )
        val escapedWorkDir = workDir.absolutePath.replace("\\", "\\\\")
        val testSrc = dir.resolve("src/test/kotlin/com/example/androidapp").apply { mkdirs() }
        testSrc.resolve("AndroidComponentTest.kt").writeText(
            """
            package com.example.androidapp

            import com.example.ct.CounterComponent
            import com.example.ct.exports.example.counter.counter_api.Counter
            import com.example.rt.RoundtripComponent
            import com.example.rt.describeCode
            import com.example.rt.example.roundtrip.env_provider.EnvProvider
            import com.example.rt.exports.example.roundtrip.math_api.scale
            import com.example.rt.imports
            import com.example.rt.withSession
            import com.example.wasiapp.WasiAppComponent
            import com.example.wasiapp.example.wasi_app.audit.Audit
            import com.example.wasiapp.imports as wasiImports
            import com.example.wasiapp.runSummary
            import com.example.wasiapp.withSession as withWasiSession
            import kotlinx.coroutines.runBlocking
            import org.junit.Assert.assertEquals
            import org.junit.Assert.assertTrue
            import org.junit.Test
            import wasm2kt.wasi.wasi03Imports
            import java.io.ByteArrayOutputStream
            import java.io.File

            class AndroidComponentTest {
                @Test
                fun runsCompiledComponents() {
                    val rtImports = imports(
                        exampleRoundtripEnvProvider = {
                            object : EnvProvider {
                                override fun getMultiplier(): Int = 6
                                override fun formatTag(prefix: String, code: UInt): String = "${'$'}prefix#${'$'}code"
                            }
                        },
                    )
                    val rt = withSession(RoundtripComponent, rtImports) {
                        "scaled=${'$'}{scale(7)},tag=${'$'}{describeCode("ERR", 404u)}"
                    }
                    val ct = com.example.ct.withSession(CounterComponent) {
                        Counter(41).use { c -> c.increment(); "counter=${'$'}{c.getValue()}" }
                    }
                    assertEquals("scaled=42,tag=ERR#404;counter=42", "${'$'}rt;${'$'}ct")

                    val work = File("$escapedWorkDir")
                    work.mkdirs()
                    File(work, "input.txt").writeText("android wasi")
                    File(work, "output.txt").delete()
                    val stdoutBuf = ByteArrayOutputStream()
                    val steps = mutableListOf<String>()
                    val wasiBase = wasi03Imports {
                        args("wasi-app", "--from-android")
                        env("WASI_PLUGIN" to "enabled")
                        stdin("hi-android".encodeToByteArray())
                        stdout = stdoutBuf
                        mounts {
                            readWrite("/work", work.toPath())
                        }
                    }
                    val appImports = wasiImports(
                        base = wasiBase,
                        exampleWasiAppAudit = {
                            object : Audit {
                                override fun recordStep(tag: String) {
                                    steps += tag
                                }
                            }
                        },
                    )
                    val summary = withWasiSession(WasiAppComponent, appImports) {
                        runBlocking { runSummary() }
                    }
                    assertTrue(
                        "unexpected summary: ${'$'}summary",
                        "fs=[mount=/work,read_ok=true,write_ok=true,data=android wasi!]" in summary,
                    )
                    assertEquals("out:hi-android", stdoutBuf.toString(Charsets.UTF_8))
                    assertEquals(listOf("start", "done"), steps)
                    assertEquals("android wasi!", File(work, "output.txt").readText())
                }
            }
            """.trimIndent(),
        )

        val wasm2ktTasks = listOf(
            ":wasm2ktDecomposeRoundtrip",
            ":wasm2ktCompileRoundtrip",
            ":wasm2ktGenerateRoundtripDebug",
            ":wasm2ktDecomposeCounter",
            ":wasm2ktCompileCounter",
            ":wasm2ktGenerateCounterDebug",
            ":wasm2ktDecomposeWasiApp",
            ":wasm2ktCompileWasiApp",
            ":wasm2ktGenerateWasiAppDebug",
        )

        val first = runner(
            dir,
            "testDebugUnitTest",
            "--configuration-cache",
            "--build-cache",
            withPluginClasspath = false,
        ).build()
        for (task in wasm2ktTasks + ":testDebugUnitTest") {
            assertEquals(TaskOutcome.SUCCESS, first.task(task)?.outcome, "$task should succeed")
        }
        val wasiGenerated = dir.resolve("build/generated")
            .walkTopDown()
            .filter { it.isDirectory && it.invariantSeparatorsPath.endsWith("com/example/wasiapp") }
            .toList()
        assertTrue(wasiGenerated.isNotEmpty(), "variant.sources.java should contain generated WasiApp sources")
        assertTrue(
            wasiGenerated.none { it.resolve("wasi").exists() },
            "Android compileClasspath should reuse runtime-wasi interfaces instead of regenerating wasi/",
        )

        val second = runner(
            dir,
            "testDebugUnitTest",
            "--configuration-cache",
            "--build-cache",
            withPluginClasspath = false,
        ).build()
        assertTrue(
            "Reusing configuration cache" in second.output || "Configuration cache entry reused" in second.output,
            second.output,
        )
        for (task in wasm2ktTasks + ":testDebugUnitTest") {
            assertEquals(TaskOutcome.UP_TO_DATE, second.task(task)?.outcome, "$task should be up to date")
        }

        val third = runner(
            dir,
            "clean",
            "testDebugUnitTest",
            "--configuration-cache",
            "--build-cache",
            withPluginClasspath = false,
        ).build()
        for (task in wasm2ktTasks) {
            assertEquals(TaskOutcome.FROM_CACHE, third.task(task)?.outcome, "$task should come from build cache after clean")
        }
        assertEquals(TaskOutcome.FROM_CACHE, third.task(":testDebugUnitTest")?.outcome, ":testDebugUnitTest should come from build cache after clean")
    }

    @Test
    fun `AOT backend opt-in runs components without runtime-chicory or plan resources`(@TempDir dir: File) {
        Files.copy(fixtures.resolve("roundtrip.wasm"), dir.resolve("roundtrip.wasm").toPath())
        Files.copy(fixtures.resolve("counter.wasm"), dir.resolve("counter.wasm").toPath())
        dir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "aot-consumer"
            pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
            $ISOLATED_BUILD_CACHE
            """.trimIndent(),
        )
        dir.resolve("build.gradle.kts").writeText(
            """
            import wasm2kt.gradle.Wasm2KtBackend

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
                backend.set(Wasm2KtBackend.AOT)
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
            tasks.register("printClasspath") {
                val cp = configurations.runtimeClasspath.map { files -> files.map { it.name }.sorted() }
                doLast { println("CP=" + cp.get().joinToString(",")) }
            }
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
                println("AOT_RESULT ${'$'}rt;${'$'}ct")
            }
            """.trimIndent(),
        )

        val result = runner(dir, "run", "printClasspath", "--configuration-cache", "--build-cache").build()
        assertTrue("AOT_RESULT scaled=42,tag=ERR#404;counter=42" in result.output, result.output)
        val cpLine = result.output.lines().first { it.startsWith("CP=") }
        assertTrue("runtime-aot" in cpLine, "Expected runtime-aot on classpath: $cpLine")
        assertFalse("runtime-chicory" in cpLine, "Unexpected runtime-chicory on AOT-only classpath: $cpLine")
        assertFalse("chicory" in cpLine, "Unexpected Chicory artifact on AOT-only classpath: $cpLine")

        val rtJar = dir.resolve("build/wasm2kt/roundtrip/wasm2kt-roundtrip.jar")
        assertTrue(rtJar.isFile)
        ZipFile(rtJar).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertTrue(names.any { it.endsWith(".class") }, "Expected AOT .class entries in jar: $names")
            assertFalse(names.any { it.endsWith("plan.json") || it.endsWith(".meta") }, "Unexpected Chicory resources in AOT jar: $names")
        }
    }

    @Test
    fun `mixed components use separate backends and share decompose cache`(@TempDir dir: File) {
        Files.copy(fixtures.resolve("roundtrip.wasm"), dir.resolve("roundtrip.wasm").toPath())
        Files.copy(fixtures.resolve("counter.wasm"), dir.resolve("counter.wasm").toPath())
        dir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "mixed-consumer"
            pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
            $ISOLATED_BUILD_CACHE
            """.trimIndent(),
        )
        fun writeBuild(roundtripBackend: String) = dir.resolve("build.gradle.kts").writeText(
            """
            import wasm2kt.gradle.Wasm2KtBackend

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
                        backend.set(Wasm2KtBackend.$roundtripBackend)
                    }
                    register("counter") {
                        source.set(file("counter.wasm"))
                        packageName.set("com.example.ct")
                        backend.set(Wasm2KtBackend.AOT)
                    }
                }
            }
            application { mainClass.set("MainKt") }
            tasks.register("printClasspath") {
                val cp = configurations.runtimeClasspath.map { files -> files.map { it.name }.sorted() }
                doLast { println("CP=" + cp.get().joinToString(",")) }
            }
            """.trimIndent(),
        )
        writeBuild("CHICORY")
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
                val rt = withSession(RoundtripComponent, imports) { scale(7) }
                val ct = com.example.ct.withSession(CounterComponent) { Counter(41).use { c -> c.increment(); c.getValue() } }
                println("MIXED ${'$'}rt:${'$'}ct")
            }
            """.trimIndent(),
        )

        val mixed = runner(dir, "run", "printClasspath", "--build-cache").build()
        assertTrue("MIXED 42:42" in mixed.output, mixed.output)
        val cpLine = mixed.output.lines().first { it.startsWith("CP=") }
        assertTrue("runtime-chicory" in cpLine && "runtime-aot" in cpLine, "Expected both runtimes on mixed classpath: $cpLine")
        ZipFile(dir.resolve("build/wasm2kt/roundtrip/wasm2kt-roundtrip.jar")).use { zip ->
            assertTrue(zip.entries().asSequence().any { it.name.endsWith("plan.json") })
        }

        // Switching roundtrip to AOT keeps wasm2ktDecomposeRoundtrip UP_TO_DATE while re-running check/compile/generate.
        writeBuild("AOT")
        val switched = runner(dir, "run", "--build-cache").build()
        assertTrue("MIXED 42:42" in switched.output, switched.output)
        assertEquals(TaskOutcome.UP_TO_DATE, switched.task(":wasm2ktDecomposeRoundtrip")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, switched.task(":wasm2ktCheckRoundtrip")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, switched.task(":wasm2ktCompileRoundtrip")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, switched.task(":wasm2ktGenerateRoundtrip")?.outcome)
        ZipFile(dir.resolve("build/wasm2kt/roundtrip/wasm2kt-roundtrip.jar")).use { zip ->
            assertFalse(zip.entries().asSequence().any { it.name.endsWith("plan.json") || it.name.endsWith(".meta") })
        }
    }

    @Test
    fun `AOT backend rejects async components even when failOnUnsupportedFeatures is false`(@TempDir dir: File) {
        Files.copy(fixtures.resolve("async-stream.wasm"), dir.resolve("async-stream.wasm").toPath())
        dir.resolve("settings.gradle.kts").writeText("rootProject.name = \"async-aot\"\n$ISOLATED_BUILD_CACHE")
        dir.resolve("build.gradle.kts").writeText(
            """
            import wasm2kt.gradle.Wasm2KtBackend

            plugins { id("io.github.wasm2kt") }
            repositories {
                maven(url = uri("${testRepo.replace("\\", "/")}"))
                mavenCentral()
            }
            wasm2kt {
                backend.set(Wasm2KtBackend.AOT)
                failOnUnsupportedFeatures.set(false)
                components {
                    register("async-app") {
                        source.set(file("async-stream.wasm"))
                        packageName.set("com.example.async")
                    }
                }
            }
            """.trimIndent(),
        )

        val failed = runner(dir, "wasm2ktGenerateAsyncApp").buildAndFail()
        assertEquals(TaskOutcome.FAILED, failed.task(":wasm2ktCheckAsyncApp")?.outcome)
        assertTrue("AOT backend does not support" in failed.output && "async" in failed.output, failed.output)
    }

    @Test
    fun `addWasiDependency warns when all components use AOT backend`(@TempDir dir: File) {
        Files.copy(fixtures.resolve("roundtrip.wasm"), dir.resolve("roundtrip.wasm").toPath())
        dir.resolve("settings.gradle.kts").writeText("rootProject.name = \"wasi-aot-warn\"\n$ISOLATED_BUILD_CACHE")
        dir.resolve("build.gradle.kts").writeText(
            """
            import wasm2kt.gradle.Wasm2KtBackend

            plugins {
                kotlin("jvm") version "$kotlinVersion"
                id("io.github.wasm2kt")
            }
            repositories {
                maven(url = uri("${testRepo.replace("\\", "/")}"))
                mavenCentral()
            }
            wasm2kt {
                backend.set(Wasm2KtBackend.AOT)
                addWasiDependency.set(true)
                components {
                    register("roundtrip") {
                        source.set(file("roundtrip.wasm"))
                        packageName.set("com.example.rt")
                    }
                }
            }
            """.trimIndent(),
        )

        val result = runner(dir, "help").build()
        assertTrue("addWasiDependency is enabled while all components use the AOT backend" in result.output, result.output)
    }

    @Test
    fun `android application AOT release build shrinks cleanly without Chicory or plan resources`(@TempDir dir: File) {
        Files.copy(fixtures.resolve("roundtrip.wasm"), dir.resolve("roundtrip.wasm").toPath())
        Files.copy(fixtures.resolve("counter.wasm"), dir.resolve("counter.wasm").toPath())
        val escapedRepo = testRepo.replace("\\", "/")
        dir.resolve("gradle.properties").writeText("org.gradle.jvmargs=-Xmx2g\n")
        dir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "android-aot-app"
            pluginManagement {
                repositories {
                    maven(url = uri("$escapedRepo"))
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
                repositories {
                    maven(url = uri("$escapedRepo"))
                    google()
                    mavenCentral()
                }
            }
            $ISOLATED_BUILD_CACHE
            """.trimIndent(),
        )
        dir.resolve("build.gradle.kts").writeText(
            """
            import wasm2kt.gradle.Wasm2KtBackend

            plugins {
                id("com.android.application") version "$agpVersion"
                id("org.jetbrains.kotlin.jvm") version "$kotlinVersion" apply false
                id("io.github.wasm2kt") version "$wasm2ktVersion"
            }
            android {
                namespace = "com.example.aotapp"
                compileSdk = 35
                defaultConfig {
                    applicationId = "com.example.aotapp"
                    minSdk = 26
                    targetSdk = 35
                }
                buildTypes {
                    release {
                        isMinifyEnabled = true
                        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
                    }
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }
            kotlin {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                }
            }
            wasm2kt {
                backend.set(Wasm2KtBackend.AOT)
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
            dependencies {
                testImplementation("junit:junit:4.13.2")
            }
            """.trimIndent(),
        )
        dir.resolve("src/main").apply { mkdirs() }.resolve("AndroidManifest.xml").writeText("<manifest />\n")
        dir.resolve("proguard-rules.pro").writeText("-keep class com.example.aotapp.AotEntry { *; }\n")
        val mainSrc = dir.resolve("src/main/kotlin/com/example/aotapp").apply { mkdirs() }
        mainSrc.resolve("AotEntry.kt").writeText(
            """
            package com.example.aotapp

            import com.example.ct.CounterComponent
            import com.example.ct.exports.example.counter.counter_api.Counter
            import com.example.rt.RoundtripComponent
            import com.example.rt.describeCode
            import com.example.rt.example.roundtrip.env_provider.EnvProvider
            import com.example.rt.exports.example.roundtrip.math_api.scale
            import com.example.rt.imports
            import com.example.rt.withSession

            object AotEntry {
                fun runSample(): String {
                    val imports = imports(
                        exampleRoundtripEnvProvider = {
                            object : EnvProvider {
                                override fun getMultiplier(): Int = 6
                                override fun formatTag(prefix: String, code: UInt): String = "${'$'}prefix#${'$'}code"
                            }
                        },
                    )
                    val rt = withSession(RoundtripComponent, imports) {
                        "${'$'}{scale(7)}:${'$'}{describeCode("AOT", 200u)}"
                    }
                    val ct = com.example.ct.withSession(CounterComponent) {
                        Counter(41).use { c -> c.increment(); c.getValue() }
                    }
                    return "${'$'}rt:${'$'}ct"
                }
            }
            """.trimIndent(),
        )
        val testSrc = dir.resolve("src/test/kotlin/com/example/aotapp").apply { mkdirs() }
        testSrc.resolve("AotEntryTest.kt").writeText(
            """
            package com.example.aotapp

            import org.junit.Assert.assertEquals
            import org.junit.Test

            class AotEntryTest {
                @Test
                fun runsAotComponents() {
                    assertEquals("42:AOT#200:42", AotEntry.runSample())
                }
            }
            """.trimIndent(),
        )

        val aotTasks = listOf(
            ":wasm2ktDecomposeRoundtrip",
            ":wasm2ktCheckRoundtrip",
            ":wasm2ktCompileRoundtrip",
            ":wasm2ktGenerateRoundtripDebug",
            ":wasm2ktGenerateRoundtripRelease",
            ":wasm2ktDecomposeCounter",
            ":wasm2ktCheckCounter",
            ":wasm2ktCompileCounter",
            ":wasm2ktGenerateCounterDebug",
            ":wasm2ktGenerateCounterRelease",
        )
        val first = runner(
            dir,
            "testDebugUnitTest",
            "assembleRelease",
            "--configuration-cache",
            "--build-cache",
            withPluginClasspath = false,
        ).build()
        for (task in aotTasks + ":testDebugUnitTest" + ":assembleRelease") {
            val outcome = first.task(task)?.outcome
            if (":wasm2ktGenerate" in task) {
                assertTrue(
                    outcome == TaskOutcome.SUCCESS || outcome == TaskOutcome.FROM_CACHE,
                    "$task should succeed or reuse sibling variant cache entry (was $outcome)",
                )
            } else {
                assertEquals(TaskOutcome.SUCCESS, outcome, "$task should succeed")
            }
        }

        val apk = dir.resolve("build/outputs/apk/release")
            .walkTopDown()
            .firstOrNull { it.isFile && it.extension == "apk" }
            ?: error("Expected release APK under build/outputs/apk/release")
        ZipFile(apk).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertFalse(names.any { it.endsWith("plan.json") || it.endsWith(".meta") }, "Unexpected Chicory resources in release APK: $names")
        }

        val second = runner(
            dir,
            "testDebugUnitTest",
            "assembleRelease",
            "--configuration-cache",
            "--build-cache",
            withPluginClasspath = false,
        ).build()
        assertTrue(
            "Reusing configuration cache" in second.output || "Configuration cache entry reused" in second.output,
            second.output,
        )
        for (task in aotTasks + ":testDebugUnitTest" + ":assembleRelease") {
            assertEquals(TaskOutcome.UP_TO_DATE, second.task(task)?.outcome, "$task should be UP_TO_DATE")
        }

        val third = runner(
            dir,
            "clean",
            "testDebugUnitTest",
            "assembleRelease",
            "--configuration-cache",
            "--build-cache",
            withPluginClasspath = false,
        ).build()
        for (task in aotTasks) {
            assertEquals(TaskOutcome.FROM_CACHE, third.task(task)?.outcome, "$task should come FROM_CACHE")
        }
    }
}

/**
 * A build cache of the test project's own: TestKit's default one outlives a test run, so a later run
 * would find the outputs of an earlier one and see FROM-CACHE where the test expects a real build.
 */
private const val ISOLATED_BUILD_CACHE = "buildCache { local { directory = file(\"build-cache\") } }"
