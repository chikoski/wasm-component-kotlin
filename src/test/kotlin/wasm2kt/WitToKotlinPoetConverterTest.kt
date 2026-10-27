package wasm2kt

import com.github.ajalt.clikt.core.parse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import wasm2kt.ast.WitResolve
import wasm2kt.cli.Wasm2KtCommand
import wasm2kt.generator.WitToKotlinPoetConverter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WitToKotlinPoetConverterTest {
    private fun loadFixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) {
            "Fixture not found: $name"
        }.bufferedReader().readText()

    @Test
    fun `parses wasm-tools JSON AST and generates World, types, resources, and context functions`() {
        val jsonText = loadFixture("calculator.json")
        val resolve = WitResolve.parse(jsonText)

        assertEquals(1, resolve.worlds.size)
        assertEquals("calculator", resolve.worlds.first().name)
        assertEquals(1, resolve.interfaces.size)
        assertEquals("types", resolve.interfaces.first().name)

        val converter = WitToKotlinPoetConverter(
            resolve = resolve,
            targetPackage = "com.example.wasm",
        )
        val files = converter.generate()
        assertEquals(2, files.size)

        val supportFile = files.first { it.name == "WitSupport" }.toString()
        val worldFile = files.first { it.name == "CalculatorWorld" }.toString()

        // Verify idiomatic Kotlin: no redundant `public` visibility modifiers
        assertTrue(!supportFile.contains("public "), "Support file must omit redundant public modifiers")
        assertTrue(!worldFile.contains("public "), "World file must omit redundant public modifiers")

        // Verify WitSupport contains sealed interfaces WitOption, WitResult, Tuple2, and WasmtimeSession
        assertTrue(supportFile.contains("sealed interface WitOption<out T>"))
        assertTrue(supportFile.contains("sealed interface WitResult<out T, out E>"))
        assertTrue(supportFile.contains("data class Tuple2<out T0, out T1>"))
        assertTrue(supportFile.contains("interface WasmtimeSession : AutoCloseable"))

        // Verify World singleton object and nested types
        assertTrue(worldFile.contains("object CalculatorWorld"))
        assertTrue(worldFile.contains("data class Point("))
        assertTrue(worldFile.contains("enum class Color"))
        assertTrue(worldFile.contains("value class Permissions("))
        assertTrue(worldFile.contains("sealed interface Status"))
        assertTrue(worldFile.contains("typealias UserId = ULong"))

        // Verify Resource class & AutoCloseable
        assertTrue(worldFile.contains("class Counter internal constructor("))
        assertTrue(worldFile.contains("override fun close()"))
        assertTrue(worldFile.contains("operator fun invoke(`init`: UInt): Counter"))
        assertTrue(worldFile.contains("fun increment()"))
        assertTrue(worldFile.contains("fun getValue(): UInt"))
        assertTrue(worldFile.contains("fun merge(a: Counter, b: Counter): Counter"))

        // Verify Context Parameter extension functions on CalculatorWorld
        assertTrue(worldFile.contains("context(session: CalculatorWorld.Session)"))
        assertTrue(worldFile.contains("fun CalculatorWorld.add(a: Int, b: Int): Int"))
        assertTrue(worldFile.contains("fun CalculatorWorld.compute("))
        assertTrue(worldFile.contains("opt: WitOption<String>"))
        assertTrue(worldFile.contains("WitResult<CalculatorWorld.Status, String>"))
    }

    @Test
    fun `CLI generates files from JSON fixture onto disk`(@TempDir tempDir: Path) {
        val jsonFixtureUrl = requireNotNull(javaClass.getResource("/fixtures/calculator.json"))
        val jsonPath = Paths.get(jsonFixtureUrl.toURI())

        val cmd = Wasm2KtCommand()
        cmd.parse(
            listOf(
                "--from-json",
                "-o",
                tempDir.toString(),
                "-p",
                "com.example.wasm",
                jsonPath.toString(),
            ),
        )

        val pkgDir = tempDir.resolve("com/example/wasm")
        val supportPath = pkgDir.resolve("WitSupport.kt")
        val worldPath = pkgDir.resolve("CalculatorWorld.kt")

        assertTrue(Files.exists(supportPath), "WitSupport.kt should be generated")
        assertTrue(Files.exists(worldPath), "CalculatorWorld.kt should be generated")

        // Verify that the generated Kotlin files compile cleanly with the Kotlin 2.2 compiler (-Xcontext-parameters)
        val classesOutDir = tempDir.resolve("classes")
        Files.createDirectories(classesOutDir)
        val compilerExitCode = org.jetbrains.kotlin.cli.jvm.K2JVMCompiler().exec(
            System.err,
            "-Xcontext-parameters",
            "-jvm-target",
            "21",
            "-no-stdlib",
            "-classpath",
            System.getProperty("java.class.path"),
            "-d",
            classesOutDir.toString(),
            supportPath.toString(),
            worldPath.toString(),
        )
        assertEquals(org.jetbrains.kotlin.cli.common.ExitCode.OK, compilerExitCode, "Generated Kotlin files must compile cleanly")
    }

    @Test
    fun `end-to-end execution of generated bindings with InMemoryWasmtimeSession`(@TempDir tempDir: Path) {
        val jsonFixtureUrl = requireNotNull(javaClass.getResource("/fixtures/calculator.json"))
        val jsonPath = Paths.get(jsonFixtureUrl.toURI())

        // 1. Generate bindings via CLI
        Wasm2KtCommand().parse(
            listOf(
                "--from-json",
                "-o",
                tempDir.toString(),
                "-p",
                "com.example.wasm",
                jsonPath.toString(),
            ),
        )

        // 2. Write a Kotlin harness that exercises withSession, functions, records, variants, flags, options, results, and resource lifecycle
        val harnessPath = tempDir.resolve("com/example/wasm/Harness.kt")
        Files.writeString(
            harnessPath,
            """
            package com.example.wasm

            import java.nio.file.Paths

            fun runEndToEndScenario(): String {
                val logs = mutableListOf<String>()
                var activeSession: InMemoryWasmtimeSession? = null

                WasmtimeSession.factory = { _, imports ->
                    val hostImports = imports as CalculatorWorld.Imports
                    InMemoryWasmtimeSession(imports).apply {
                        activeSession = this
                        registerFunction("add") { args ->
                            val a = args[0] as Int
                            val b = args[1] as Int
                            hostImports.log("add(${'$'}a, ${'$'}b)")
                            a + b
                        }
                        registerFunction("compute") { args ->
                            val p = args[0] as CalculatorWorld.Point
                            val opt = args[1] as WitOption<String>
                            val bytes = args[2] as List<UByte>
                            val tup = args[3] as Tuple2<UInt, String>
                            val label = opt.getOrNull() ?: "none"
                            if (p.x < 0f) {
                                WitResult.Err("negative x")
                            } else {
                                WitResult.Ok(CalculatorWorld.Status.Code(tup.f0 + bytes.size.toUInt()))
                            }
                        }
                        registerResourceConstructor("counter") { args ->
                            (args[0] as UInt).toInt()
                        }
                        registerMethod("[method]counter.increment") { handle, _ ->
                            val current = getResourceState(handle) as Int
                            setResourceState(handle, current + 1)
                        }
                        registerMethod("[method]counter.get-value") { handle, _ ->
                            (getResourceState(handle) as Int).toUInt()
                        }
                        registerFunction("[static]counter.merge") { args ->
                            // args[0] is borrow<counter> handle, args[1] is own<counter> handle
                            val handleA = args[0] as Int
                            val handleB = args[1] as Int
                            val valA = getResourceState(handleA) as Int
                            val valB = getResourceState(handleB) as Int
                            // own<counter> transfers ownership to guest, which drops handleB after merging
                            dropResource(handleB)
                            newResource("counter", (valA + valB).toUInt())
                        }
                    }
                }

                val imports = object : CalculatorWorld.Imports {
                    override fun log(msg: String) {
                        logs.add(msg)
                    }
                }

                val resultSummary = CalculatorWorld.withSession(Paths.get("dummy.wasm"), imports) {
                    val sum = CalculatorWorld.add(10, 32)
                    val perms = CalculatorWorld.Permissions.READ or CalculatorWorld.Permissions.EXECUTE
                    check(perms.read && !perms.write && perms.execute)

                    val computeRes = CalculatorWorld.compute(
                        p = CalculatorWorld.Point(1.5f, 2.5f),
                        opt = WitOption.Some("hello"),
                        listData = listOf(1u, 2u, 3u),
                        tup = Tuple2(100u, "tag"),
                    )
                    val codeVal = when (computeRes) {
                        is WitResult.Ok -> when (val s = computeRes.value) {
                            is CalculatorWorld.Status.Code -> s.value
                            else -> 0u
                        }
                        is WitResult.Err -> 0u
                    }

                    // Test Resource creation, method calls, borrow vs own, and AutoCloseable (.use)
                    val mergedValue = CalculatorWorld.Counter(10u).use { c1 ->
                        c1.increment()
                        c1.increment()
                        val c2 = CalculatorWorld.Counter(5u)
                        // merge borrows c1 and takes ownership of c2
                        CalculatorWorld.Counter.merge(c1, c2).use { merged ->
                            // c2 is now consumed (isClosed == true), so closing c2 again is a no-op
                            c2.close()
                            merged.getValue()
                        }
                    }

                    "sum=${'$'}sum,code=${'$'}codeVal,merged=${'$'}mergedValue,logs=${'$'}{logs.joinToString(";")}"
                }

                val dropped = activeSession?.droppedHandles.orEmpty()
                val closed = activeSession?.isClosed == true
                return "${'$'}resultSummary,dropped=${'$'}{dropped.size},sessionClosed=${'$'}closed"
            }
            """.trimIndent(),
        )

        // 3. Compile generated files + Harness.kt with K2JVMCompiler
        val pkgDir = tempDir.resolve("com/example/wasm")
        val classesOutDir = tempDir.resolve("classes")
        Files.createDirectories(classesOutDir)

        val compilerExitCode = org.jetbrains.kotlin.cli.jvm.K2JVMCompiler().exec(
            System.err,
            "-Xcontext-parameters",
            "-jvm-target",
            "21",
            "-no-stdlib",
            "-classpath",
            System.getProperty("java.class.path"),
            "-d",
            classesOutDir.toString(),
            pkgDir.resolve("WitSupport.kt").toString(),
            pkgDir.resolve("CalculatorWorld.kt").toString(),
            harnessPath.toString(),
        )
        assertEquals(org.jetbrains.kotlin.cli.common.ExitCode.OK, compilerExitCode, "Generated files + Harness must compile cleanly")

        // 4. Load compiled classes and invoke runEndToEndScenario()
        val classLoader = java.net.URLClassLoader(
            arrayOf(classesOutDir.toUri().toURL()),
            javaClass.classLoader,
        )
        val harnessClass = classLoader.loadClass("com.example.wasm.HarnessKt")
        val method = harnessClass.getDeclaredMethod("runEndToEndScenario")
        val actualOutput = method.invoke(null) as String

        assertEquals(
            "sum=42,code=103,merged=17,logs=add(10, 32),dropped=3,sessionClosed=true",
            actualOutput,
        )
    }
}
