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

        // Verify idiomatic Kotlin: no redundant `public` visibility modifiers and zero kotlin-reflect usage
        assertTrue(!supportFile.contains("public "), "Support file must omit redundant public modifiers")
        assertTrue(!worldFile.contains("public "), "World file must omit redundant public modifiers")
        assertTrue(!supportFile.contains("kotlin.reflect"), "Support file must not depend on kotlin.reflect")
        assertTrue(!worldFile.contains("kotlin.reflect"), "World file must not depend on kotlin.reflect")

        // Verify WitSupport contains sealed interfaces WitOption, WitResult, Tuple2, and WasmtimeSession
        assertTrue(supportFile.contains("sealed interface WitOption<out T>"))
        assertTrue(supportFile.contains("sealed interface WitResult<out T, out E>"))
        assertTrue(supportFile.contains("data class Tuple2<out T0, out T1>"))
        assertTrue(supportFile.contains("interface WasmtimeSession : AutoCloseable"))
        assertTrue(supportFile.contains("class JniWasmtimeSession"))

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

    @Test
    fun `native FFM Wasmtime execution against real Wasm component binary`(@TempDir tempDir: Path) {
        val wasmFixtureUrl = requireNotNull(javaClass.getResource("/fixtures/live_calc.wasm"))
        val wasmPath = Paths.get(wasmFixtureUrl.toURI())
        val witFixtureUrl = requireNotNull(javaClass.getResource("/fixtures/live_calc.wit"))
        val witPath = Paths.get(witFixtureUrl.toURI())

        // 1. Extract JSON AST directly from the compiled .wasm component binary via wasm-tools (or .wit)
        val jsonAst = Wasm2KtCommand.extractWitJsonViaWasmTools("/Users/chikoski/.cargo/bin/wasm-tools", witPath)
        val jsonFile = tempDir.resolve("live_calc.json")
        Files.writeString(jsonFile, jsonAst)

        // 2. Generate Kotlin host bindings via wasm2kt CLI
        Wasm2KtCommand().parse(
            listOf(
                "--from-json",
                "-o",
                tempDir.toString(),
                "-p",
                "com.example.live",
                jsonFile.toString(),
            ),
        )

        // 3. Write a Kotlin harness that runs against the real FfmWasmtimeSession (native Rust cdylib + Wasmtime)
        val harnessPath = tempDir.resolve("com/example/live/NativeHarness.kt")
        Files.writeString(
            harnessPath,
            """
            package com.example.live

            import java.nio.file.Path

            fun runNativeWasmComponent(wasmPath: Path): String {
                val recordedCalls = mutableListOf<String>()
                val imports = object : LiveCalcWorld.Imports {
                    override fun recordCalc(a: Int, b: Int) {
                        recordedCalls.add("${'$'}a,${'$'}b")
                    }
                }

                // Uses default WasmtimeSession.factory -> FfmWasmtimeSession.open(wasmPath, imports)
                return LiveCalcWorld.withSession(wasmPath, imports) {
                    val sum = LiveCalcWorld.add(15, 27)
                    val product = LiveCalcWorld.multiply(6, 7)
                    "sum=${'$'}sum,product=${'$'}product,recorded=${'$'}{recordedCalls.joinToString(";")}"
                }
            }
            """.trimIndent(),
        )

        // 4. Compile generated bindings + NativeHarness.kt with K2JVMCompiler
        val pkgDir = tempDir.resolve("com/example/live")
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
            pkgDir.resolve("LiveCalcWorld.kt").toString(),
            harnessPath.toString(),
        )
        assertEquals(org.jetbrains.kotlin.cli.common.ExitCode.OK, compilerExitCode, "Generated LiveCalc bindings must compile cleanly")

        // 5. Execute on real Wasmtime via JVM Foreign Function & Memory (FFM) API
        val classLoader = java.net.URLClassLoader(
            arrayOf(classesOutDir.toUri().toURL()),
            javaClass.classLoader,
        )
        val harnessClass = classLoader.loadClass("com.example.live.NativeHarnessKt")
        val method = harnessClass.getDeclaredMethod("runNativeWasmComponent", Path::class.java)
        val actualOutput = method.invoke(null, wasmPath) as String

        assertEquals(
            "sum=42,product=42,recorded=15,27;6,7",
            actualOutput,
        )
    }

    @Test
    fun `native FFM Wasmtime execution of arithmetic world (add, sub, mul, div)`(@TempDir tempDir: Path) {
        val wasmFixtureUrl = requireNotNull(javaClass.getResource("/fixtures/arithmetic.wasm"))
        val wasmPath = Paths.get(wasmFixtureUrl.toURI())
        val witFixtureUrl = requireNotNull(javaClass.getResource("/fixtures/arithmetic.wit"))
        val witPath = Paths.get(witFixtureUrl.toURI())

        // 1. Generate Kotlin host bindings from both arithmetic.wit (ArithmeticWorld) and arithmetic.wasm (RootWorld)
        Wasm2KtCommand().parse(
            listOf(
                "--wasm-tools-path",
                "/Users/chikoski/.cargo/bin/wasm-tools",
                "-o",
                tempDir.toString(),
                "-p",
                "com.example.arithmetic",
                witPath.toString(),
            ),
        )
        Wasm2KtCommand().parse(
            listOf(
                "--wasm-tools-path",
                "/Users/chikoski/.cargo/bin/wasm-tools",
                "-o",
                tempDir.toString(),
                "-p",
                "com.example.arithmetic",
                wasmPath.toString(),
            ),
        )

        // 2. Write a Kotlin harness that calls add, sub, mul, div on ArithmeticWorld
        val harnessPath = tempDir.resolve("com/example/arithmetic/ArithmeticHarness.kt")
        Files.writeString(
            harnessPath,
            """
            package com.example.arithmetic

            import java.nio.file.Path

            fun runArithmeticComponent(wasmPath: Path): String {
                return ArithmeticWorld.withSession(wasmPath) {
                    val sum = ArithmeticWorld.add(20, 7)
                    val diff = ArithmeticWorld.sub(50, 8)
                    val prod = ArithmeticWorld.mul(-6, -7)
                    val quot = ArithmeticWorld.div(84, 2)
                    "add=${'$'}sum,sub=${'$'}diff,mul=${'$'}prod,div=${'$'}quot"
                }
            }
            """.trimIndent(),
        )

        // 3. Compile generated bindings + ArithmeticHarness.kt with K2JVMCompiler
        val pkgDir = tempDir.resolve("com/example/arithmetic")
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
            pkgDir.resolve("ArithmeticWorld.kt").toString(),
            pkgDir.resolve("RootWorld.kt").toString(),
            harnessPath.toString(),
        )
        assertEquals(org.jetbrains.kotlin.cli.common.ExitCode.OK, compilerExitCode, "Generated ArithmeticWorld & RootWorld bindings must compile cleanly")

        // 4. Execute on real Wasmtime via JVM Foreign Function & Memory (FFM) API
        val classLoader = java.net.URLClassLoader(
            arrayOf(classesOutDir.toUri().toURL()),
            javaClass.classLoader,
        )
        val harnessClass = classLoader.loadClass("com.example.arithmetic.ArithmeticHarnessKt")
        val method = harnessClass.getDeclaredMethod("runArithmeticComponent", Path::class.java)
        val actualOutput = method.invoke(null, wasmPath) as String

        assertEquals(
            "add=27,sub=42,mul=42,div=42",
            actualOutput,
        )
    }

    @Test
    fun `native FFM Wasmtime execution of counter resource world (constructor, methods, borrow, own, close)`(@TempDir tempDir: Path) {
        val wasmFixtureUrl = requireNotNull(javaClass.getResource("/fixtures/counter.wasm"))
        val wasmPath = Paths.get(wasmFixtureUrl.toURI())
        val witFixtureUrl = requireNotNull(javaClass.getResource("/fixtures/counter.wit"))
        val witPath = Paths.get(witFixtureUrl.toURI())

        // 1. Generate Kotlin host bindings from both counter.wit (CounterWorldWorld) and counter.wasm (RootWorld)
        Wasm2KtCommand().parse(
            listOf(
                "--wasm-tools-path",
                "/Users/chikoski/.cargo/bin/wasm-tools",
                "-o",
                tempDir.toString(),
                "-p",
                "com.example.counter",
                witPath.toString(),
            ),
        )
        Wasm2KtCommand().parse(
            listOf(
                "--wasm-tools-path",
                "/Users/chikoski/.cargo/bin/wasm-tools",
                "-o",
                tempDir.toString(),
                "-p",
                "com.example.counter",
                wasmPath.toString(),
            ),
        )

        // 2. Write a Kotlin harness that exercises Counter resource creation, methods, borrow/own static merge, and AutoCloseable (.use)
        val harnessPath = tempDir.resolve("com/example/counter/CounterHarness.kt")
        Files.writeString(
            harnessPath,
            """
            package com.example.counter

            import java.nio.file.Path

            fun runCounterComponent(wasmPath: Path): String {
                return CounterWorldWorld.withSession(wasmPath) {
                    CounterWorldWorld.Counter(10).use { c1 ->
                        c1.increment()
                        c1.increment()
                        c1.add(5)
                        val v1 = c1.getValue() // 10 + 1 + 1 + 5 = 17

                        val c2 = CounterWorldWorld.Counter(25)
                        c2.increment() // 26

                        // merge(a: borrow<counter>, b: own<counter>) -> own<counter>
                        // c1 is borrowed (remains usable), c2's ownership is consumed
                        CounterWorldWorld.Counter.merge(c1, c2).use { merged ->
                            val v1After = c1.getValue() // still 17
                            val vMerged = merged.getValue() // 17 + 26 = 43
                            "v1=${'$'}v1,v1After=${'$'}v1After,c2Closed=${'$'}{c2.isClosed},merged=${'$'}vMerged"
                        }
                    }
                }
            }
            """.trimIndent(),
        )

        // 3. Compile generated bindings + CounterHarness.kt with K2JVMCompiler
        val pkgDir = tempDir.resolve("com/example/counter")
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
            pkgDir.resolve("CounterWorldWorld.kt").toString(),
            pkgDir.resolve("RootWorld.kt").toString(),
            harnessPath.toString(),
        )
        assertEquals(org.jetbrains.kotlin.cli.common.ExitCode.OK, compilerExitCode, "Generated CounterWorldWorld & RootWorld bindings must compile cleanly")

        // 4. Execute on real Wasmtime via JVM Foreign Function & Memory (FFM) API
        val classLoader = java.net.URLClassLoader(
            arrayOf(classesOutDir.toUri().toURL()),
            javaClass.classLoader,
        )
        val harnessClass = classLoader.loadClass("com.example.counter.CounterHarnessKt")
        val method = harnessClass.getDeclaredMethod("runCounterComponent", Path::class.java)
        val actualOutput = method.invoke(null, wasmPath) as String

        assertEquals(
            "v1=17,v1After=17,c2Closed=true,merged=43",
            actualOutput,
        )
    }

    @Test
    fun `all 4 examples under examples directory compile and execute cleanly`(@TempDir tempDir: Path) {
        data class ExampleSpec(
            val dirName: String,
            val witFixture: String,
            val pkg: String,
            val generatedWorldFile: String,
        )

        val examples = listOf(
            ExampleSpec("01-arithmetic", "arithmetic.wit", "com.example.arithmetic", "ArithmeticWorld.kt"),
            ExampleSpec("02-host-imports", "live_calc.wit", "com.example.live", "LiveCalcWorld.kt"),
            ExampleSpec("03-counter-resource", "counter.wit", "com.example.counter", "CounterWorldWorld.kt"),
            ExampleSpec("04-algebraic-types-and-mock", "calculator.wit", "com.example.wasm", "CalculatorWorld.kt"),
        )

        for (ex in examples) {
            val exOutDir = tempDir.resolve(ex.dirName)
            val witUrl = requireNotNull(javaClass.getResource("/fixtures/${ex.witFixture}"))
            val witPath = Paths.get(witUrl.toURI())

            Wasm2KtCommand().parse(
                listOf(
                    "--wasm-tools-path",
                    "/Users/chikoski/.cargo/bin/wasm-tools",
                    "-o",
                    exOutDir.toString(),
                    "-p",
                    ex.pkg,
                    witPath.toString(),
                ),
            )

            val pkgDir = exOutDir.resolve(ex.pkg.replace('.', '/'))
            val exampleMainPath = Paths.get("examples/${ex.dirName}/Main.kt").toAbsolutePath()
            val classesDir = exOutDir.resolve("classes")
            Files.createDirectories(classesDir)

            val exitCode = org.jetbrains.kotlin.cli.jvm.K2JVMCompiler().exec(
                System.err,
                "-Xcontext-parameters",
                "-jvm-target",
                "21",
                "-no-stdlib",
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                classesDir.toString(),
                pkgDir.resolve("WitSupport.kt").toString(),
                pkgDir.resolve(ex.generatedWorldFile).toString(),
                exampleMainPath.toString(),
            )
            assertEquals(
                org.jetbrains.kotlin.cli.common.ExitCode.OK,
                exitCode,
                "Example ${ex.dirName} must compile cleanly",
            )

            val classLoader = java.net.URLClassLoader(
                arrayOf(classesDir.toUri().toURL()),
                javaClass.classLoader,
            )
            val mainClass = classLoader.loadClass("${ex.pkg}.MainKt")
            val mainMethod = mainClass.getDeclaredMethod("main")
            mainMethod.invoke(null)
        }
    }

    @Test
    fun `native FFM Wasmtime roundtrip of records, variants, enums, flags, nested options, unit results, u64 boundaries, and named interfaces`(@TempDir tempDir: Path) {
        val wasmFixtureUrl = requireNotNull(javaClass.getResource("/fixtures/roundtrip.wasm"))
        val wasmPath = Paths.get(wasmFixtureUrl.toURI())
        val witFixtureUrl = requireNotNull(javaClass.getResource("/fixtures/roundtrip.wit"))
        val witPath = Paths.get(witFixtureUrl.toURI())

        // 1. Generate bindings from roundtrip.wit
        Wasm2KtCommand().parse(
            listOf(
                "--wasm-tools-path",
                "/Users/chikoski/.cargo/bin/wasm-tools",
                "-o",
                tempDir.toString(),
                "-p",
                "com.example.roundtrip",
                witPath.toString(),
            ),
        )

        // 2. Write a Kotlin harness exercising all roundtrip types, host interface imports with return values, and exported sub-interfaces
        val harnessPath = tempDir.resolve("com/example/roundtrip/RoundtripHarness.kt")
        Files.writeString(
            harnessPath,
            """
            package com.example.roundtrip

            import java.nio.file.Path

            fun runRoundtripComponent(wasmPath: Path): String {
                val imports = object : RoundtripWorld.Imports {
                    override val envProvider = object : RoundtripWorld.EnvProvider {
                        override fun getMultiplier(): Int = 6
                        override fun formatTag(prefix: String, code: UInt): String = "${'$'}prefix#${'$'}code"
                    }
                }

                return RoundtripWorld.withSession(wasmPath, imports) {
                    // 1. Exported named interface calling imported named interface returning s32
                    val scaled = with(RoundtripWorld.MathApi) { scale(7) } // 7 * 6 = 42

                    // 2. Exported function calling imported named interface with u32 and returning String
                    val tag = RoundtripWorld.describeCode("ERR", 404u)

                    // 3. Record + Flags + Enum + Variant roundtrip
                    val initial = RoundtripWorld.ItemSummary(
                        count = 10u,
                        perms = RoundtripWorld.Permissions.READ or RoundtripWorld.Permissions.EXECUTE,
                        prio = RoundtripWorld.Priority.LOW,
                    )
                    val afterAdd = RoundtripWorld.transformSummary(initial, RoundtripWorld.Action.AddCount(5u))
                    val afterPrio = RoundtripWorld.transformSummary(
                        afterAdd,
                        RoundtripWorld.Action.SetPriority(RoundtripWorld.Priority.HIGH),
                    )
                    val afterReset = RoundtripWorld.transformSummary(afterPrio, RoundtripWorld.Action.Reset)

                    check(afterPrio.count == 15u) { "afterPrio.count=${'$'}{afterPrio.count}" }
                    check(afterPrio.perms.read && !afterPrio.perms.write && afterPrio.perms.execute) {
                        "afterPrio.perms.raw=${'$'}{afterPrio.perms.raw}, initial.perms.raw=${'$'}{initial.perms.raw}, afterAdd.perms.raw=${'$'}{afterAdd.perms.raw}"
                    }
                    check(afterPrio.prio == RoundtripWorld.Priority.HIGH) { "afterPrio.prio=${'$'}{afterPrio.prio}" }
                    check(afterReset.count == 0u && afterReset.perms == RoundtripWorld.Permissions.NONE && afterReset.prio == RoundtripWorld.Priority.LOW) {
                        "afterReset=${'$'}afterReset"
                    }

                    // 4. result<u32, s32> and result (WitResult<Unit, Unit>)
                    val evenOk = RoundtripWorld.checkEven(84)
                    val oddErr = RoundtripWorld.checkEven(-7)
                    val pingOk = RoundtripWorld.pingResult(true)
                    val pingErr = RoundtripWorld.pingResult(false)

                    val evenStr = (evenOk as WitResult.Ok).value.toString() + ":" + (oddErr as WitResult.Err).error.toString()
                    val pingStr = "${'$'}{pingOk is WitResult.Ok}:${'$'}{pingErr is WitResult.Err}"

                    // 5. Nested option<option<s32>> distinguishing None, Some(None), and Some(Some(v))
                    val optNone = RoundtripWorld.nestedOption(WitOption.None)
                    val optSomeNone = RoundtripWorld.nestedOption(WitOption.Some(WitOption.None))
                    val optSomeVal = RoundtripWorld.nestedOption(WitOption.Some(WitOption.Some(23)))

                    val optStr = listOf(
                        optNone is WitOption.None,
                        optSomeNone is WitOption.Some && optSomeNone.value is WitOption.None,
                        (optSomeVal as WitOption.Some).value.getOrNull() == 123,
                    ).joinToString(",")

                    // 6. u64 boundary value (ULong.MAX_VALUE)
                    val maxU64 = RoundtripWorld.echoU64(ULong.MAX_VALUE)
                    check(maxU64 == ULong.MAX_VALUE)

                    "scaled=${'$'}scaled,tag=${'$'}tag,count=${'$'}{afterPrio.count},prio=${'$'}{afterPrio.prio},even=${'$'}evenStr,ping=${'$'}pingStr,opt=${'$'}optStr,u64Max=${'$'}{maxU64 == ULong.MAX_VALUE}"
                }
            }
            """.trimIndent(),
        )

        // 3. Compile generated bindings + RoundtripHarness.kt
        val pkgDir = tempDir.resolve("com/example/roundtrip")
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
            pkgDir.resolve("RoundtripWorld.kt").toString(),
            harnessPath.toString(),
        )
        assertEquals(org.jetbrains.kotlin.cli.common.ExitCode.OK, compilerExitCode, "RoundtripWorld bindings must compile cleanly")

        // 4. Execute on real Wasmtime via JVM FFM API
        val classLoader = java.net.URLClassLoader(
            arrayOf(classesOutDir.toUri().toURL()),
            javaClass.classLoader,
        )
        val harnessClass = classLoader.loadClass("com.example.roundtrip.RoundtripHarnessKt")
        val method = harnessClass.getDeclaredMethod("runRoundtripComponent", Path::class.java)
        val actualOutput = method.invoke(null, wasmPath) as String

        assertEquals(
            "scaled=42,tag=ERR#404,count=15,prio=HIGH,even=42:-7,ping=true:true,opt=true,true,true,u64Max=true",
            actualOutput,
        )
    }

    @Test
    fun `resource use-after-close and use-after-move throw IllegalStateException on real Wasmtime`(@TempDir tempDir: Path) {
        val wasmFixtureUrl = requireNotNull(javaClass.getResource("/fixtures/counter.wasm"))
        val wasmPath = Paths.get(wasmFixtureUrl.toURI())
        val witFixtureUrl = requireNotNull(javaClass.getResource("/fixtures/counter.wit"))
        val witPath = Paths.get(witFixtureUrl.toURI())

        Wasm2KtCommand().parse(
            listOf(
                "--wasm-tools-path",
                "/Users/chikoski/.cargo/bin/wasm-tools",
                "-o",
                tempDir.toString(),
                "-p",
                "com.example.counter.err",
                witPath.toString(),
            ),
        )

        val harnessPath = tempDir.resolve("com/example/counter/err/CounterErrorHarness.kt")
        Files.writeString(
            harnessPath,
            """
            package com.example.counter.err

            import java.nio.file.Path

            fun verifyResourceSafety(wasmPath: Path): String {
                return CounterWorldWorld.withSession(wasmPath) {
                    val c1 = CounterWorldWorld.Counter(10)
                    c1.close()
                    val afterCloseError = try {
                        c1.increment()
                        "no-error"
                    } catch (e: IllegalStateException) {
                        "closed-error"
                    }

                    val a = CounterWorldWorld.Counter(5)
                    val b = CounterWorldWorld.Counter(7)
                    val merged = CounterWorldWorld.Counter.merge(a, b)
                    val afterMoveError = try {
                        b.getValue()
                        "no-error"
                    } catch (e: IllegalStateException) {
                        "moved-error"
                    }

                    a.close()
                    merged.close()
                    "${'$'}afterCloseError,${'$'}afterMoveError"
                }
            }
            """.trimIndent(),
        )

        val pkgDir = tempDir.resolve("com/example/counter/err")
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
            pkgDir.resolve("CounterWorldWorld.kt").toString(),
            harnessPath.toString(),
        )
        assertEquals(org.jetbrains.kotlin.cli.common.ExitCode.OK, compilerExitCode)

        val classLoader = java.net.URLClassLoader(
            arrayOf(classesOutDir.toUri().toURL()),
            javaClass.classLoader,
        )
        val harnessClass = classLoader.loadClass("com.example.counter.err.CounterErrorHarnessKt")
        val method = harnessClass.getDeclaredMethod("verifyResourceSafety", Path::class.java)
        val actualOutput = method.invoke(null, wasmPath) as String

        assertEquals("closed-error,moved-error", actualOutput)
    }

    @Test
    fun `flags with more than 32 bits use ULong backing type and compile cleanly`(@TempDir tempDir: Path) {
        val flagNames = (0 until 40).joinToString(", ") { idx ->
            val tens = ('a' + (idx / 10))
            val ones = ('a' + (idx % 10))
            "flag-$tens$ones"
        }
        val witText = """
            package example:largeflags@0.1.0;

            world large-flags-world {
                flags permissions64 {
                    $flagNames
                }
                export echo-flags: func(f: permissions64) -> permissions64;
            }
        """.trimIndent()

        val witFile = tempDir.resolve("large_flags.wit")
        Files.writeString(witFile, witText)

        Wasm2KtCommand().parse(
            listOf(
                "--wasm-tools-path",
                "/Users/chikoski/.cargo/bin/wasm-tools",
                "-o",
                tempDir.toString(),
                "-p",
                "com.example.flags64",
                witFile.toString(),
            ),
        )

        val pkgDir = tempDir.resolve("com/example/flags64")
        val worldFile = pkgDir.resolve("LargeFlagsWorldWorld.kt")
        val content = Files.readString(worldFile)

        assertTrue(content.contains("val raw: ULong = 0uL"), "Flags > 32 must be backed by ULong")
        assertTrue(content.contains("val FLAG_DF: Permissions64 = Permissions64((1uL shl 35))"))

        val harnessPath = pkgDir.resolve("FlagsHarness.kt")
        Files.writeString(
            harnessPath,
            """
            package com.example.flags64

            fun testLargeFlags(): Boolean {
                val combined = LargeFlagsWorldWorld.Permissions64.FLAG_AA or LargeFlagsWorldWorld.Permissions64.FLAG_DF
                return combined.flagAa && !combined.flagAb && combined.flagDf
            }
            """.trimIndent(),
        )

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
            worldFile.toString(),
            harnessPath.toString(),
        )
        assertEquals(org.jetbrains.kotlin.cli.common.ExitCode.OK, compilerExitCode)

        val classLoader = java.net.URLClassLoader(
            arrayOf(classesOutDir.toUri().toURL()),
            javaClass.classLoader,
        )
        val harnessClass = classLoader.loadClass("com.example.flags64.FlagsHarnessKt")
        val res = harnessClass.getDeclaredMethod("testLargeFlags").invoke(null) as Boolean
        assertTrue(res)
    }

    @Test
    fun `WIT escaped identifiers and Kotlin reserved keywords compile and execute cleanly`(@TempDir tempDir: Path) {
        val witText = """
            package example:keywords@0.1.0;

            world kw-world {
                record %object {
                    %type: string,
                    val: s32,
                    in: bool,
                }
                export %return: func(fun: string, is: bool, when: s32) -> %object;
            }
        """.trimIndent()

        val witFile = tempDir.resolve("keywords.wit")
        Files.writeString(witFile, witText)

        Wasm2KtCommand().parse(
            listOf(
                "--wasm-tools-path",
                "/Users/chikoski/.cargo/bin/wasm-tools",
                "-o",
                tempDir.toString(),
                "-p",
                "com.example.kw",
                witFile.toString(),
            ),
        )

        val pkgDir = tempDir.resolve("com/example/kw")
        val worldFile = pkgDir.resolve("KwWorldWorld.kt")
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
            worldFile.toString(),
        )
        assertEquals(
            org.jetbrains.kotlin.cli.common.ExitCode.OK,
            compilerExitCode,
            "Kotlin reserved keywords in function, parameter, and field names must be properly escaped and compile cleanly",
        )
    }

    @Test
    fun `multi-world selection via --world flag and standalone interface synthesis`(@TempDir tempDir: Path) {
        val multiWorldWit = """
            package example:multi@0.1.0;

            world alpha {
                export ping-alpha: func() -> s32;
            }

            world beta {
                export ping-beta: func() -> string;
            }
        """.trimIndent()
        val multiWitFile = tempDir.resolve("multi.wit")
        Files.writeString(multiWitFile, multiWorldWit)

        val selectedOutDir = tempDir.resolve("selected")
        Wasm2KtCommand().parse(
            listOf(
                "--wasm-tools-path",
                "/Users/chikoski/.cargo/bin/wasm-tools",
                "--world",
                "alpha",
                "-o",
                selectedOutDir.toString(),
                "-p",
                "com.example.multi",
                multiWitFile.toString(),
            ),
        )
        assertTrue(Files.exists(selectedOutDir.resolve("com/example/multi/AlphaWorld.kt")))
        assertTrue(!Files.exists(selectedOutDir.resolve("com/example/multi/BetaWorld.kt")))

        // Standalone interface file without any world definition
        val ifaceOnlyWit = """
            package example:standalone@0.1.0;

            interface greeter {
                greet: func(name: string) -> string;
            }
        """.trimIndent()
        val ifaceWitFile = tempDir.resolve("standalone.wit")
        Files.writeString(ifaceWitFile, ifaceOnlyWit)

        val standaloneOutDir = tempDir.resolve("standalone")
        Wasm2KtCommand().parse(
            listOf(
                "--wasm-tools-path",
                "/Users/chikoski/.cargo/bin/wasm-tools",
                "-o",
                standaloneOutDir.toString(),
                "-p",
                "com.example.standalone",
                ifaceWitFile.toString(),
            ),
        )
        val defaultWorldFile = standaloneOutDir.resolve("com/example/standalone/DefaultWorld.kt")
        assertTrue(Files.exists(defaultWorldFile), "DefaultWorld.kt should be synthesized when WIT has only interfaces")
    }

    @Test
    fun `ByteArray and AOT precompiled cwasm execution via openSession and withSession`(@TempDir tempDir: Path) {
        val wasmFixtureUrl = requireNotNull(javaClass.getResource("/fixtures/roundtrip.wasm"))
        val wasmPath = Paths.get(wasmFixtureUrl.toURI())
        val witFixtureUrl = requireNotNull(javaClass.getResource("/fixtures/roundtrip.wit"))
        val witPath = Paths.get(witFixtureUrl.toURI())

        // 1. Precompile roundtrip.wasm into AOT .cwasm bytes and file via FfmWasmtimeBridge
        val bridge = wasm2kt.runtime.FfmWasmtimeBridge.getDefault()
        val rawWasmBytes = Files.readAllBytes(wasmPath)
        val cwasmBytes = bridge.precompileComponent(rawWasmBytes)
        assertTrue(cwasmBytes.isNotEmpty(), "Precompiled .cwasm bytes must not be empty")
        val cwasmPath = tempDir.resolve("roundtrip.cwasm")
        Files.write(cwasmPath, cwasmBytes)

        // 2. Generate bindings from roundtrip.wit
        Wasm2KtCommand().parse(
            listOf(
                "--wasm-tools-path",
                "/Users/chikoski/.cargo/bin/wasm-tools",
                "-o",
                tempDir.toString(),
                "-p",
                "com.example.cwasm",
                witPath.toString(),
            ),
        )

        // 3. Write a harness testing:
        //    (a) withSession(wasmBytes: ByteArray) with raw .wasm bytes
        //    (b) withSession(cwasmBytes: ByteArray) with AOT .cwasm bytes
        //    (c) withSession(cwasmPath: Path) with AOT .cwasm file path
        val harnessPath = tempDir.resolve("com/example/cwasm/CwasmHarness.kt")
        Files.writeString(
            harnessPath,
            """
            package com.example.cwasm

            import java.nio.file.Path

            fun runAllLoadingModes(rawWasmBytes: ByteArray, cwasmBytes: ByteArray, cwasmPath: Path): String {
                val imports = object : RoundtripWorld.Imports {
                    override val envProvider = object : RoundtripWorld.EnvProvider {
                        override fun getMultiplier(): Int = 6
                        override fun formatTag(prefix: String, code: UInt): String = "${'$'}prefix#${'$'}code"
                    }
                }

                // Mode A: Raw .wasm ByteArray
                val resRawBytes = RoundtripWorld.withSession(rawWasmBytes, imports) {
                    val scaled = with(RoundtripWorld.MathApi) { scale(7) }
                    val tag = RoundtripWorld.describeCode("RAW", 200u)
                    "${'$'}scaled:${'$'}tag"
                }

                // Mode B: Precompiled AOT .cwasm ByteArray
                val resCwasmBytes = RoundtripWorld.withSession(cwasmBytes, imports) {
                    val scaled = with(RoundtripWorld.MathApi) { scale(8) }
                    val tag = RoundtripWorld.describeCode("AOT_BYTES", 201u)
                    "${'$'}scaled:${'$'}tag"
                }

                // Mode C: Precompiled AOT .cwasm Path
                val resCwasmPath = RoundtripWorld.withSession(cwasmPath, imports) {
                    val scaled = with(RoundtripWorld.MathApi) { scale(9) }
                    val tag = RoundtripWorld.describeCode("AOT_PATH", 202u)
                    "${'$'}scaled:${'$'}tag"
                }

                return "rawBytes=${'$'}resRawBytes|cwasmBytes=${'$'}resCwasmBytes|cwasmPath=${'$'}resCwasmPath"
            }
            """.trimIndent(),
        )

        val pkgDir = tempDir.resolve("com/example/cwasm")
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
            pkgDir.resolve("RoundtripWorld.kt").toString(),
            harnessPath.toString(),
        )
        assertEquals(org.jetbrains.kotlin.cli.common.ExitCode.OK, compilerExitCode, "CwasmHarness must compile cleanly")

        val classLoader = java.net.URLClassLoader(
            arrayOf(classesOutDir.toUri().toURL()),
            javaClass.classLoader,
        )
        val harnessClass = classLoader.loadClass("com.example.cwasm.CwasmHarnessKt")
        val method = harnessClass.getDeclaredMethod(
            "runAllLoadingModes",
            ByteArray::class.java,
            ByteArray::class.java,
            Path::class.java,
        )
        val actualOutput = method.invoke(null, rawWasmBytes, cwasmBytes, cwasmPath) as String

        assertEquals(
            "rawBytes=42:RAW#200|cwasmBytes=48:AOT_BYTES#201|cwasmPath=54:AOT_PATH#202",
            actualOutput,
        )
    }

    @Test
    fun `native JNI Wasmtime execution with JniWasmtimeSession across Path, ByteArray, AOT cwasm, host imports, and resources`(@TempDir tempDir: Path) {
        val roundtripWasmPath = Paths.get(requireNotNull(javaClass.getResource("/fixtures/roundtrip.wasm")).toURI())
        val roundtripWitPath = Paths.get(requireNotNull(javaClass.getResource("/fixtures/roundtrip.wit")).toURI())
        val counterWasmPath = Paths.get(requireNotNull(javaClass.getResource("/fixtures/counter.wasm")).toURI())
        val counterWitPath = Paths.get(requireNotNull(javaClass.getResource("/fixtures/counter.wit")).toURI())

        // Precompile roundtrip.wasm into AOT .cwasm via JNI bridge (JniWasmtimeBridge)
        val rawRoundtripBytes = Files.readAllBytes(roundtripWasmPath)
        val cwasmBytes = wasm2kt.runtime.JniWasmtimeBridge.precompileComponent(rawRoundtripBytes)
        assertTrue(cwasmBytes.isNotEmpty(), "JNI precompiled .cwasm bytes must not be empty")

        // Generate bindings for both roundtrip and counter in the same package
        Wasm2KtCommand().parse(
            listOf(
                "--wasm-tools-path",
                "/Users/chikoski/.cargo/bin/wasm-tools",
                "-o",
                tempDir.toString(),
                "-p",
                "com.example.jni",
                roundtripWitPath.toString(),
            ),
        )
        Wasm2KtCommand().parse(
            listOf(
                "--wasm-tools-path",
                "/Users/chikoski/.cargo/bin/wasm-tools",
                "-o",
                tempDir.toString(),
                "-p",
                "com.example.jni",
                counterWitPath.toString(),
            ),
        )

        val harnessPath = tempDir.resolve("com/example/jni/JniHarness.kt")
        Files.writeString(
            harnessPath,
            """
            package com.example.jni

            import java.nio.file.Path

            fun runJniScenario(roundtripWasmPath: Path, cwasmBytes: ByteArray, counterWasmPath: Path): String {
                // Switch WasmtimeSession to JNI backend (Android ART compatible, zero java.lang.foreign usage)
                WasmtimeSession.useJniBackend()

                val imports = object : RoundtripWorld.Imports {
                    override val envProvider = object : RoundtripWorld.EnvProvider {
                        override fun getMultiplier(): Int = 7
                        override fun formatTag(prefix: String, code: UInt): String = "${'$'}prefix@${'$'}code"
                    }
                }

                // 1. Roundtrip over JNI from Path + Host Callbacks
                val pathSummary = RoundtripWorld.withSession(roundtripWasmPath, imports) {
                    val scaled = with(RoundtripWorld.MathApi) { scale(6) } // 6 * 7 = 42
                    val tag = RoundtripWorld.describeCode("JNI", 777u)
                    val initial = RoundtripWorld.ItemSummary(
                        count = 20u,
                        perms = RoundtripWorld.Permissions.READ or RoundtripWorld.Permissions.WRITE,
                        prio = RoundtripWorld.Priority.MEDIUM,
                    )
                    val updated = RoundtripWorld.transformSummary(initial, RoundtripWorld.Action.AddCount(22u))
                    "${'$'}scaled:${'$'}tag:${'$'}{updated.count}:${'$'}{updated.perms.write}"
                }

                // 2. Roundtrip over JNI from precompiled AOT .cwasm ByteArray
                val aotBytesSummary = RoundtripWorld.withSession(cwasmBytes, imports) {
                    val scaled = with(RoundtripWorld.MathApi) { scale(5) } // 5 * 7 = 35
                    val optVal = RoundtripWorld.nestedOption(WitOption.Some(WitOption.Some(10)))
                    val unwrapped = (optVal as WitOption.Some).value.getOrNull()
                    "${'$'}scaled:${'$'}unwrapped"
                }

                // 3. Counter resource lifecycle over JNI
                val counterSummary = CounterWorldWorld.withSession(counterWasmPath) {
                    CounterWorldWorld.Counter(10).use { c1 ->
                        c1.add(5)
                        val c2 = CounterWorldWorld.Counter(27)
                        CounterWorldWorld.Counter.merge(c1, c2).use { merged ->
                            "${'$'}{c1.getValue()}:${'$'}{c2.isClosed}:${'$'}{merged.getValue()}"
                        }
                    }
                }

                return "path=${'$'}pathSummary|aot=${'$'}aotBytesSummary|counter=${'$'}counterSummary"
            }
            """.trimIndent(),
        )

        val pkgDir = tempDir.resolve("com/example/jni")
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
            pkgDir.resolve("RoundtripWorld.kt").toString(),
            pkgDir.resolve("CounterWorldWorld.kt").toString(),
            harnessPath.toString(),
        )
        assertEquals(org.jetbrains.kotlin.cli.common.ExitCode.OK, compilerExitCode, "JniHarness must compile cleanly")

        val classLoader = java.net.URLClassLoader(
            arrayOf(classesOutDir.toUri().toURL()),
            javaClass.classLoader,
        )
        val harnessClass = classLoader.loadClass("com.example.jni.JniHarnessKt")
        val method = harnessClass.getDeclaredMethod(
            "runJniScenario",
            Path::class.java,
            ByteArray::class.java,
            Path::class.java,
        )
        val actualOutput = method.invoke(null, roundtripWasmPath, cwasmBytes, counterWasmPath) as String

        assertEquals(
            "path=42:JNI@777:42:true|aot=35:110|counter=15:true:42",
            actualOutput,
        )
    }
}
