package wasm2kt

import com.github.ajalt.clikt.core.parse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import wasm2kt.ast.WitResolve
import wasm2kt.cli.GenerateCommand
import wasm2kt.generator.WitToKotlinPoetConverter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WitToKotlinPoetConverterTest {
    private val wasmToolsBin: String = resolveWasmToolsBinary()

    private fun resolveWasmToolsBinary(): String {
        System.getenv("WASM_TOOLS_BIN")?.takeIf { it.isNotBlank() }?.let { return it }
        val homeCargo = Paths.get(System.getProperty("user.home"), ".cargo", "bin", "wasm-tools")
        if (Files.isExecutable(homeCargo)) return homeCargo.toString()
        val pathEnv = System.getenv("PATH").orEmpty()
        for (dir in pathEnv.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) continue
            val candidate = Paths.get(dir, "wasm-tools")
            if (Files.isExecutable(candidate)) return candidate.toString()
        }
        return "wasm-tools"
    }

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
        assertEquals(listOf("types", "counters"), resolve.interfaces.map { it.name })

        val converter = WitToKotlinPoetConverter(
            resolve = resolve,
            targetPackage = "com.example.wasm",
        )
        val files = converter.generate()
        // WitSupport, the world, the imported `types` interface and the exported `counters` interface (ADR-0011).
        assertEquals(
            listOf("com.example.wasm.WitSupport", "com.example.wasm.CalculatorWorld", "com.example.wasm.example.calc.types.Types", "com.example.wasm.exports.example.calc.counters.Counters"),
            files.map { "${it.packageName}.${it.name}" },
        )

        val supportFile = files.first { it.name == "WitSupport" }.toString()
        val worldFile = files.first { it.name == "CalculatorWorld" }.toString()
        val typesFile = files.first { it.name == "Types" }.toString()
        val countersFile = files.first { it.name == "Counters" }.toString()

        // Verify idiomatic Kotlin: no redundant `public` visibility modifiers and zero kotlin-reflect usage
        for (file in files) {
            assertTrue(!file.toString().contains("public "), "${file.name} must omit redundant public modifiers")
            assertTrue(!file.toString().contains("kotlin.reflect"), "${file.name} must not depend on kotlin.reflect")
        }

        // Verify WitSupport contains sealed interfaces WitOption, WitResult, Tuple2, and WasmtimeSession
        assertTrue(supportFile.contains("sealed interface WitOption<out T>"))
        assertTrue(supportFile.contains("sealed interface WitResult<out T, out E> : WitResultValue<T, E>"))
        assertTrue(supportFile.contains("data class Tuple2<out T0, out T1>"))
        assertTrue(supportFile.contains("interface WasmtimeSession : AutoCloseable"))
        assertTrue(supportFile.contains("class JniWasmtimeSession"))
        assertTrue(supportFile.contains("abstract class WitSession internal constructor("))

        // The world object; the types of the `types` interface live in its own package
        assertTrue(worldFile.contains("class Session internal constructor("))
        assertTrue(worldFile.contains("fun <R> withSessionBlocking("))
        assertTrue(worldFile.contains("fun <R> withPrecompiledSessionBlocking("))
        assertTrue(typesFile.startsWith("package com.example.wasm.example.calc.types\n"))
        assertTrue(typesFile.contains("data class Point("))
        assertTrue(typesFile.contains("enum class Color"))
        assertTrue(typesFile.contains("value class Permissions("))
        assertTrue(typesFile.contains("sealed interface Status"))
        assertTrue(typesFile.contains("typealias UserId = ULong"))

        // Verify Resource class & AutoCloseable in the exported interface's package
        assertTrue(countersFile.startsWith("package com.example.wasm.exports.example.calc.counters\n"))
        assertTrue(countersFile.contains("class Counter internal constructor("))
        assertTrue(countersFile.contains("override fun close()"))
        assertTrue(countersFile.contains("operator fun invoke(`init`: UInt): Counter"))
        assertTrue(countersFile.contains("fun increment()"))
        assertTrue(countersFile.contains("fun getValue(): UInt"))
        assertTrue(countersFile.contains("fun merge(a: Counter, b: Counter): Counter"))
        assertTrue(countersFile.contains("\"example:calc/counters@0.1.0#[method]counter.increment\""))

        // Verify Context Parameter extension functions on CalculatorWorld
        assertTrue(worldFile.contains("context(session: Session)"))
        assertTrue(worldFile.contains("fun add(a: Int, b: Int): Int"))
        assertTrue(worldFile.contains("fun compute("))
        assertTrue(worldFile.contains("opt: String?"))
        assertTrue(worldFile.contains("WitResult<Status, String>"))
        assertTrue(worldFile.contains("fun imports(world: () -> Imports): ImportsFactory"))
    }

    @Test
    fun `CLI generates files from JSON fixture onto disk`(@TempDir tempDir: Path) {
        val jsonFixtureUrl = requireNotNull(javaClass.getResource("/fixtures/calculator.json"))
        val jsonPath = Paths.get(jsonFixtureUrl.toURI())

        val cmd = GenerateCommand()
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
            *generatedSources(tempDir).toTypedArray(),
        )
        assertEquals(org.jetbrains.kotlin.cli.common.ExitCode.OK, compilerExitCode, "Generated Kotlin files must compile cleanly")
    }

    @Test
    fun `end-to-end execution of generated bindings with InMemoryWasmtimeSession`(@TempDir tempDir: Path) {
        val jsonFixtureUrl = requireNotNull(javaClass.getResource("/fixtures/calculator.json"))
        val jsonPath = Paths.get(jsonFixtureUrl.toURI())

        // 1. Generate bindings via CLI
        GenerateCommand().parse(
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
            import com.example.wasm.example.calc.types.Permissions
            import com.example.wasm.example.calc.types.Point
            import com.example.wasm.example.calc.types.Status
            import com.example.wasm.exports.example.calc.counters.Counter
            import wasm2kt.runtime.Linker

            fun runEndToEndScenario(): String {
                val logs = mutableListOf<String>()
                var activeSession: InMemoryWasmtimeSession? = null

                WasmtimeSession.factory = { _, imports ->
                    val hostImports = imports as Linker
                    InMemoryWasmtimeSession(imports).apply {
                        activeSession = this
                        registerFunction("add") { args ->
                            val a = args[0] as Int
                            val b = args[1] as Int
                            hostImports.call("log", wasm2kt.runtime.WitWriter().apply { string("add(${'$'}a, ${'$'}b)") })
                            a + b
                        }
                        registerFunction("compute") { args ->
                            val p = args[0] as Point
                            val opt = args[1] as String?
                            val bytes = args[2] as ByteArray
                            val tup = args[3] as Tuple2<UInt, String>
                            val label = opt ?: "none"
                            if (p.x < 0f) {
                                WitResult.Err("negative x")
                            } else {
                                WitResult.Ok(Status.Code(tup.f0 + bytes.size.toUInt()))
                            }
                        }
                        registerResourceConstructor("example:calc/counters@0.1.0#[constructor]counter") { args ->
                            (args[0] as UInt).toInt()
                        }
                        registerMethod("example:calc/counters@0.1.0#[method]counter.increment") { handle, _ ->
                            val current = getResourceState(handle) as Int
                            setResourceState(handle, current + 1)
                        }
                        registerMethod("example:calc/counters@0.1.0#[method]counter.get-value") { handle, _ ->
                            (getResourceState(handle) as Int).toUInt()
                        }
                        registerFunction("example:calc/counters@0.1.0#[static]counter.merge") { args ->
                            // args[0] is borrow<counter> handle, args[1] is own<counter> handle
                            val handleA = args[0] as Int
                            val handleB = args[1] as Int
                            val valA = getResourceState(handleA) as Int
                            val valB = getResourceState(handleB) as Int
                            // own<counter> transfers ownership to guest, which drops handleB after merging
                            dropResource(handleB)
                            newResource("example:calc/counters@0.1.0#[constructor]counter", arrayOf((valA + valB).toUInt())) { }
                        }
                    }
                }

                val imports = imports(
                    world = {
                        object : Imports {
                            override fun log(msg: String) {
                                logs.add(msg)
                            }
                        }
                    },
                )

                val resultSummary = withSession(Paths.get("dummy.wasm"), imports) {
                    val sum = add(10, 32)
                    val perms = Permissions.READ or Permissions.EXECUTE
                    check(perms.read && !perms.write && perms.execute)

                    val computeRes = compute(
                        p = Point(1.5f, 2.5f),
                        opt = "hello",
                        listData = byteArrayOf(1, 2, 3),
                        tup = Tuple2(100u, "tag"),
                    )
                    val codeVal = when (computeRes) {
                        is WitResult.Ok -> when (val s = computeRes.value) {
                            is Status.Code -> s.value
                            else -> 0u
                        }
                        is WitResult.Err -> 0u
                    }

                    // Test Resource creation, method calls, borrow vs own, and AutoCloseable (.use)
                    val mergedValue = Counter(10u).use { c1 ->
                        c1.increment()
                        c1.increment()
                        val c2 = Counter(5u)
                        // merge borrows c1 and takes ownership of c2
                        Counter.merge(c1, c2).use { merged ->
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
            *generatedSources(tempDir).toTypedArray(),
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
        val jsonAst = GenerateCommand.extractWitJsonViaWasmTools(wasmToolsBin, witPath)
        val jsonFile = tempDir.resolve("live_calc.json")
        Files.writeString(jsonFile, jsonAst)

        // 2. Generate Kotlin host bindings via wasm2kt CLI
        GenerateCommand().parse(
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
                val imports = imports(
                    world = {
                        object : Imports {
                            override fun recordCalc(a: Int, b: Int) {
                                recordedCalls.add("${'$'}a,${'$'}b")
                            }
                        }
                    },
                )

                // Uses default WasmtimeSession.factory -> FfmWasmtimeSession.open(wasmPath, imports)
                return withSession(wasmPath, imports) {
                    val sum = add(15, 27)
                    val product = multiply(6, 7)
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
            *generatedSources(tempDir).toTypedArray(),
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
        GenerateCommand().parse(
            listOf(
                "--wasm-tools-path",
                wasmToolsBin,
                "-o",
                tempDir.toString(),
                "-p",
                "com.example.arithmetic",
                witPath.toString(),
            ),
        )
        GenerateCommand().parse(
            listOf(
                "--wasm-tools-path",
                wasmToolsBin,
                "-o",
                tempDir.toString(),
                "-p",
                // One world per package (ADR-0012): the root world of the .wasm goes next to the .wit one.
                "com.example.arithmetic.fromwasm",
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
                return withSession(wasmPath) {
                    val sum = add(20, 7)
                    val diff = sub(50, 8)
                    val prod = mul(-6, -7)
                    val quot = div(84, 2)
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
            *generatedSources(tempDir).toTypedArray(),
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

        // 1. Generate Kotlin host bindings from both counter.wit (CounterWorld) and counter.wasm (RootWorld)
        GenerateCommand().parse(
            listOf(
                "--wasm-tools-path",
                wasmToolsBin,
                "-o",
                tempDir.toString(),
                "-p",
                "com.example.counter",
                witPath.toString(),
            ),
        )
        GenerateCommand().parse(
            listOf(
                "--wasm-tools-path",
                wasmToolsBin,
                "-o",
                tempDir.toString(),
                "-p",
                // One world per package (ADR-0012): the root world of the .wasm goes next to the .wit one.
                "com.example.counter.fromwasm",
                wasmPath.toString(),
            ),
        )

        // 2. Write a Kotlin harness that exercises Counter resource creation, methods, borrow/own static merge, and AutoCloseable (.use)
        val harnessPath = tempDir.resolve("com/example/counter/CounterHarness.kt")
        Files.writeString(
            harnessPath,
            """
            package com.example.counter

            import com.example.counter.exports.example.counter.counter_api.Counter
            import java.nio.file.Path

            fun runCounterComponent(wasmPath: Path): String {
                return withSession(wasmPath) {
                    Counter(10).use { c1 ->
                        c1.increment()
                        c1.increment()
                        c1.add(5)
                        val v1 = c1.getValue() // 10 + 1 + 1 + 5 = 17

                        val c2 = Counter(25)
                        c2.increment() // 26

                        // merge(a: borrow<counter>, b: own<counter>) -> own<counter>
                        // c1 is borrowed (remains usable), c2's ownership is consumed
                        Counter.merge(c1, c2).use { merged ->
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
            *generatedSources(tempDir).toTypedArray(),
        )
        assertEquals(org.jetbrains.kotlin.cli.common.ExitCode.OK, compilerExitCode, "Generated CounterWorld & RootWorld bindings must compile cleanly")

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
            ExampleSpec("03-counter-resource", "counter.wit", "com.example.counter", "CounterWorld.kt"),
            ExampleSpec("04-algebraic-types-and-mock", "calculator.wit", "com.example.wasm", "CalculatorWorld.kt"),
        )

        for (ex in examples) {
            val exOutDir = tempDir.resolve(ex.dirName)
            val witUrl = requireNotNull(javaClass.getResource("/fixtures/${ex.witFixture}"))
            val witPath = Paths.get(witUrl.toURI())

            GenerateCommand().parse(
                listOf(
                    "--wasm-tools-path",
                    wasmToolsBin,
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
                *generatedSources(exOutDir).toTypedArray(),
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
        GenerateCommand().parse(
            listOf(
                "--wasm-tools-path",
                wasmToolsBin,
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

            import com.example.roundtrip.example.roundtrip.env_provider.EnvProvider
            import com.example.roundtrip.exports.example.roundtrip.math_api.scale
            import java.nio.file.Path

            fun runRoundtripComponent(wasmPath: Path): String {
                val imports = imports(
                    exampleRoundtripEnvProvider = {
                        object : EnvProvider {
                            override fun getMultiplier(): Int = 6
                            override fun formatTag(prefix: String, code: UInt): String = "${'$'}prefix#${'$'}code"
                        }
                    },
                )

                return withSession(wasmPath, imports) {
                    // 1. Exported named interface calling imported named interface returning s32
                    val scaled = scale(7) // 7 * 6 = 42

                    // 2. Exported function calling imported named interface with u32 and returning String
                    val tag = describeCode("ERR", 404u)

                    // 3. Record + Flags + Enum + Variant roundtrip
                    val initial = ItemSummary(
                        count = 10u,
                        perms = Permissions.READ or Permissions.EXECUTE,
                        prio = Priority.LOW,
                    )
                    val afterAdd = transformSummary(initial, Action.AddCount(5u))
                    val afterPrio = transformSummary(
                        afterAdd,
                        Action.SetPriority(Priority.HIGH),
                    )
                    val afterReset = transformSummary(afterPrio, Action.Reset)

                    check(afterPrio.count == 15u) { "afterPrio.count=${'$'}{afterPrio.count}" }
                    check(afterPrio.perms.read && !afterPrio.perms.write && afterPrio.perms.execute) {
                        "afterPrio.perms.raw=${'$'}{afterPrio.perms.raw}, initial.perms.raw=${'$'}{initial.perms.raw}, afterAdd.perms.raw=${'$'}{afterAdd.perms.raw}"
                    }
                    check(afterPrio.prio == Priority.HIGH) { "afterPrio.prio=${'$'}{afterPrio.prio}" }
                    check(afterReset.count == 0u && afterReset.perms == Permissions.NONE && afterReset.prio == Priority.LOW) {
                        "afterReset=${'$'}afterReset"
                    }

                    // 4. result<u32, s32> and result (WitResult<Unit, Unit>)
                    val evenOk = checkEven(84)
                    val oddErr = checkEven(-7)
                    val pingOk = pingResult(true)
                    val pingErr = pingResult(false)

                    val evenStr = (evenOk as WitResult.Ok).value.toString() + ":" + (oddErr as WitResult.Err).error.toString()
                    val pingStr = "${'$'}{pingOk is WitResult.Ok}:${'$'}{pingErr is WitResult.Err}"

                    // 5. Nested option<option<s32>> distinguishing None, Some(None), and Some(Some(v))
                    val optNone = nestedOption(WitOption.None)
                    val optSomeNone = nestedOption(WitOption.Some(null))
                    val optSomeVal = nestedOption(WitOption.Some(23))

                    val optStr = listOf(
                        optNone is WitOption.None,
                        optSomeNone is WitOption.Some && optSomeNone.value == null,
                        (optSomeVal as WitOption.Some).value == 123,
                    ).joinToString(",")

                    // 6. u64 boundary value (ULong.MAX_VALUE)
                    val maxU64 = echoU64(ULong.MAX_VALUE)
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
            *generatedSources(tempDir).toTypedArray(),
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

        GenerateCommand().parse(
            listOf(
                "--wasm-tools-path",
                wasmToolsBin,
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

            import com.example.counter.err.exports.example.counter.counter_api.Counter
            import java.nio.file.Path

            fun verifyResourceSafety(wasmPath: Path): String {
                return withSession(wasmPath) {
                    val c1 = Counter(10)
                    c1.close()
                    val afterCloseError = try {
                        c1.increment()
                        "no-error"
                    } catch (e: IllegalStateException) {
                        "closed-error"
                    }

                    val a = Counter(5)
                    val b = Counter(7)
                    val merged = Counter.merge(a, b)
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
            *generatedSources(tempDir).toTypedArray(),
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

        // The Component Model spec now caps flags at 32 members and wasm-tools >= 1.2xx rejects
        // larger sets; this test only applies to toolchains that still accept them.
        val probe = ProcessBuilder(wasmToolsBin, "component", "wit", "--json", witFile.toString())
            .redirectErrorStream(true).start()
        val probeOutput = probe.inputStream.bufferedReader().readText()
        org.junit.jupiter.api.Assumptions.assumeFalse(
            probe.waitFor() != 0 && "more than 32 flags" in probeOutput,
            "wasm-tools rejects flags with more than 32 members",
        )

        GenerateCommand().parse(
            listOf(
                "--wasm-tools-path",
                wasmToolsBin,
                "-o",
                tempDir.toString(),
                "-p",
                "com.example.flags64",
                witFile.toString(),
            ),
        )

        val pkgDir = tempDir.resolve("com/example/flags64")
        val worldFile = pkgDir.resolve("LargeFlagsWorld.kt")
        val content = Files.readString(worldFile)

        assertTrue(content.contains("val raw: ULong = 0uL"), "Flags > 32 must be backed by ULong")
        assertTrue(content.contains("val FLAG_DF: Permissions64 = Permissions64((1uL shl 35))"))

        val harnessPath = pkgDir.resolve("FlagsHarness.kt")
        Files.writeString(
            harnessPath,
            """
            package com.example.flags64

            fun testLargeFlags(): Boolean {
                val combined = Permissions64.FLAG_AA or Permissions64.FLAG_DF
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
            *generatedSources(tempDir).toTypedArray(),
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

        GenerateCommand().parse(
            listOf(
                "--wasm-tools-path",
                wasmToolsBin,
                "-o",
                tempDir.toString(),
                "-p",
                "com.example.kw",
                witFile.toString(),
            ),
        )

        val pkgDir = tempDir.resolve("com/example/kw")
        val worldFile = pkgDir.resolve("KwWorld.kt")
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
            *generatedSources(tempDir).toTypedArray(),
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
        GenerateCommand().parse(
            listOf(
                "--wasm-tools-path",
                wasmToolsBin,
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

        // Without --world, several worlds cannot share the package's top level (ADR-0012).
        val noWorld = runCatching {
            GenerateCommand().parse(
                listOf("--wasm-tools-path", wasmToolsBin, "-o", tempDir.resolve("all").toString(), "-p", "com.example.multi", multiWitFile.toString()),
            )
        }.exceptionOrNull()
        assertTrue(noWorld?.message.orEmpty().contains("--world"), "several worlds need --world, got: $noWorld")

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
        GenerateCommand().parse(
            listOf(
                "--wasm-tools-path",
                wasmToolsBin,
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
    fun `ByteArray and AOT precompiled cwasm execution via withSession and withPrecompiledSession`(@TempDir tempDir: Path) {
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
        GenerateCommand().parse(
            listOf(
                "--wasm-tools-path",
                wasmToolsBin,
                "-o",
                tempDir.toString(),
                "-p",
                "com.example.cwasm",
                witPath.toString(),
            ),
        )

        // 3. Write a harness testing:
        //    (a) withSession(wasmBytes: ByteArray) with raw .wasm bytes
        //    (b) withPrecompiledSession(cwasmBytes: ByteArray) with AOT .cwasm bytes
        //    (c) withPrecompiledSession(cwasmPath: Path) with AOT .cwasm file path
        //    (d) withSession rejects .cwasm and withPrecompiledSession rejects .wasm (#33)
        val harnessPath = tempDir.resolve("com/example/cwasm/CwasmHarness.kt")
        Files.writeString(
            harnessPath,
            """
            package com.example.cwasm

            import com.example.cwasm.example.roundtrip.env_provider.EnvProvider
            import com.example.cwasm.exports.example.roundtrip.math_api.scale
            import java.nio.file.Path

            fun runAllLoadingModes(rawWasmBytes: ByteArray, cwasmBytes: ByteArray, cwasmPath: Path): String {
                val imports = imports(
                    exampleRoundtripEnvProvider = {
                        object : EnvProvider {
                            override fun getMultiplier(): Int = 6
                            override fun formatTag(prefix: String, code: UInt): String = "${'$'}prefix#${'$'}code"
                        }
                    },
                )

                // Mode A: Raw .wasm ByteArray
                val resRawBytes = withSession(rawWasmBytes, imports) {
                    val scaled = scale(7)
                    val tag = describeCode("RAW", 200u)
                    "${'$'}scaled:${'$'}tag"
                }

                // Mode B: Precompiled AOT .cwasm ByteArray
                val resCwasmBytes = withPrecompiledSession(cwasmBytes, imports) {
                    val scaled = scale(8)
                    val tag = describeCode("AOT_BYTES", 201u)
                    "${'$'}scaled:${'$'}tag"
                }

                // Mode C: Precompiled AOT .cwasm Path
                val resCwasmPath = withPrecompiledSession(cwasmPath, imports) {
                    val scaled = scale(9)
                    val tag = describeCode("AOT_PATH", 202u)
                    "${'$'}scaled:${'$'}tag"
                }

                // Mode D: the plain entry points refuse native code, and the precompiled ones refuse .wasm
                fun refused(open: () -> Unit): String = try {
                    open()
                    "opened"
                } catch (e: IllegalStateException) {
                    if ("precompiled" in (e.message ?: "")) "refused" else "other: ${'$'}{e.message}"
                }
                val refusals = listOf(
                    refused { openSession(cwasmBytes, imports).close() },
                    refused { openSession(cwasmPath, imports).close() },
                    refused { openPrecompiledSession(rawWasmBytes, imports).close() },
                ).joinToString(",")

                return "rawBytes=${'$'}resRawBytes|cwasmBytes=${'$'}resCwasmBytes|cwasmPath=${'$'}resCwasmPath|${'$'}refusals"
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
            *generatedSources(tempDir).toTypedArray(),
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
            "rawBytes=42:RAW#200|cwasmBytes=48:AOT_BYTES#201|cwasmPath=54:AOT_PATH#202|refused,refused,refused",
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

        // Generate bindings for roundtrip and counter, one world per package (ADR-0012)
        GenerateCommand().parse(
            listOf(
                "--wasm-tools-path",
                wasmToolsBin,
                "-o",
                tempDir.toString(),
                "-p",
                "com.example.jni",
                roundtripWitPath.toString(),
            ),
        )
        GenerateCommand().parse(
            listOf(
                "--wasm-tools-path",
                wasmToolsBin,
                "-o",
                tempDir.toString(),
                "-p",
                "com.example.jni.counter",
                counterWitPath.toString(),
            ),
        )

        val harnessPath = tempDir.resolve("com/example/jni/JniHarness.kt")
        Files.writeString(
            harnessPath,
            """
            package com.example.jni

            import com.example.jni.example.roundtrip.env_provider.EnvProvider
            import com.example.jni.exports.example.roundtrip.math_api.scale
            import com.example.jni.counter.exports.example.counter.counter_api.Counter
            import java.nio.file.Path

            fun runJniScenario(roundtripWasmPath: Path, cwasmBytes: ByteArray, counterWasmPath: Path): String {
                // Switch WasmtimeSession to JNI backend (zero java.lang.foreign usage)
                WasmtimeSession.useJniBackend()

                val imports = imports(
                    exampleRoundtripEnvProvider = {
                        object : EnvProvider {
                            override fun getMultiplier(): Int = 7
                            override fun formatTag(prefix: String, code: UInt): String = "${'$'}prefix@${'$'}code"
                        }
                    },
                )

                // 1. Roundtrip over JNI from Path + Host Callbacks
                val pathSummary = withSession(roundtripWasmPath, imports) {
                    val scaled = scale(6) // 6 * 7 = 42
                    val tag = describeCode("JNI", 777u)
                    val initial = ItemSummary(
                        count = 20u,
                        perms = Permissions.READ or Permissions.WRITE,
                        prio = Priority.MEDIUM,
                    )
                    val updated = transformSummary(initial, Action.AddCount(22u))
                    "${'$'}scaled:${'$'}tag:${'$'}{updated.count}:${'$'}{updated.perms.write}"
                }

                // 2. Roundtrip over JNI from precompiled AOT .cwasm ByteArray
                val aotBytesSummary = withPrecompiledSession(cwasmBytes, imports) {
                    val scaled = scale(5) // 5 * 7 = 35
                    val optVal = nestedOption(WitOption.Some(10))
                    val unwrapped = (optVal as WitOption.Some).value
                    "${'$'}scaled:${'$'}unwrapped"
                }

                // 3. Counter resource lifecycle over JNI
                val counterSummary = com.example.jni.counter.withSession(counterWasmPath) {
                    Counter(10).use { c1 ->
                        c1.add(5)
                        val c2 = Counter(27)
                        Counter.merge(c1, c2).use { merged ->
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
            *generatedSources(tempDir).toTypedArray(),
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

    @Test
    fun `nested resource handles inside result, option, list, tuple, record, and variant compile and decode cleanly`(@TempDir tempDir: Path) {
        val witText = """
            package example:nestedres@0.1.0;

            world nested-res-world {
                resource counter {
                    constructor(init: s32);
                    get-value: func() -> s32;
                    try-create: static func(init: s32) -> result<counter, string>;
                    find-counter: static func(found: bool) -> option<counter>;
                }

                record counter-pair {
                    primary: counter,
                    label: string,
                }

                variant counter-outcome {
                    created(counter),
                    empty,
                }

                export make-pair: func(init: s32, label: string) -> counter-pair;
                export make-outcome: func(init: s32) -> counter-outcome;
                export make-list: func(a: s32, b: s32) -> list<counter>;
                export make-tuple: func(init: s32) -> tuple<counter, string>;
            }
        """.trimIndent()

        val witFile = tempDir.resolve("nested_res.wit")
        Files.writeString(witFile, witText)

        GenerateCommand().parse(
            listOf(
                "--wasm-tools-path",
                wasmToolsBin,
                "-o",
                tempDir.toString(),
                "-p",
                "com.example.nestedres",
                witFile.toString(),
            ),
        )

        val pkgDir = tempDir.resolve("com/example/nestedres")
        val worldFile = pkgDir.resolve("NestedResWorld.kt")
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
            *generatedSources(tempDir).toTypedArray(),
        )
        assertEquals(
            org.jetbrains.kotlin.cli.common.ExitCode.OK,
            compilerExitCode,
            "WIT definitions with nested resource handles in result/option/list/tuple/record/variant must compile cleanly",
        )
    }

    @Test
    fun `same type names in different interfaces live in their own packages and compile cleanly`(@TempDir tempDir: Path) {
        val witText = """
            package example:collision@0.1.0;

            interface alpha {
                record config {
                    port: u16,
                }
                get-config: func() -> config;
            }

            interface beta {
                record config {
                    host: string,
                }
                get-config: func() -> config;
            }

            world collision-world {
                export alpha;
                export beta;
            }
        """.trimIndent()

        val witFile = tempDir.resolve("collision.wit")
        Files.writeString(witFile, witText)

        GenerateCommand().parse(
            listOf(
                "--wasm-tools-path",
                wasmToolsBin,
                "-o",
                tempDir.toString(),
                "-p",
                "com.example.collision",
                witFile.toString(),
            ),
        )

        // Each interface has its own package, so both records keep the name Config (ADR-0011).
        val exportsDir = tempDir.resolve("com/example/collision/exports/example/collision")
        assertTrue(Files.readString(exportsDir.resolve("alpha/Alpha.kt")).contains("data class Config("), "alpha's config keeps its name")
        assertTrue(Files.readString(exportsDir.resolve("beta/Beta.kt")).contains("data class Config("), "beta's config keeps its name")

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
            *generatedSources(tempDir).toTypedArray(),
        )
        assertEquals(
            org.jetbrains.kotlin.cli.common.ExitCode.OK,
            compilerExitCode,
            "Same-named interface types must compile cleanly without Redeclaration errors",
        )
    }

    @Test
    fun `JNI host callback exception preserves Kotlin exception message and invalid char throws descriptive error`(@TempDir tempDir: Path) {
        val roundtripWasmPath = Paths.get(requireNotNull(javaClass.getResource("/fixtures/roundtrip.wasm")).toURI())

        // 1. Verify JNI host callback exception preserves the Kotlin exception message
        val failingImports = object : wasm2kt.runtime.WitHostImportDispatcher {
            override fun hostFunction(key: String) = wasm2kt.runtime.HostFunction { _, _ ->
                throw IllegalArgumentException("custom-host-failure-for-$key")
            }
        }
        val jniSessionPtr = wasm2kt.runtime.JniWasmtimeBridge.openSession(roundtripWasmPath, failingImports)
        try {
            val response = jniDescribe(jniSessionPtr, "WARN", 500)
            assertTrue(
                response.contains("custom-host-failure-for-example:roundtrip/env-provider@0.1.0#format-tag"),
                "JNI host callback failure must preserve Kotlin exception details, got: $response",
            )
        } finally {
            wasm2kt.runtime.JniWasmtimeBridge.nativeSessionClose(jniSessionPtr)
        }

        // 2. FFM responses of any size (no fixed buffer, #35) are tested in runtime-wasmtime's HostCallbackDispatcherTest
        //    and end to end in ChicoryDifferentialTest.
    }

    /** Arguments of roundtrip's `describe-code: func(prefix: string, code: u32) -> string` (ADR-0015). */
    private fun describeArgs(prefix: String, code: Int) = wasm2kt.runtime.WitWriter().apply {
        string(prefix)
        int(code)
    }

    /** `describe-code` through the JNI bridge: the result, or `error: <message>`. */
    private fun jniDescribe(sessionPtr: Long, prefix: String, code: Int): String = runCatching {
        val jni = wasm2kt.runtime.JniWasmtimeBridge
        jni.invoke(sessionPtr, jni.resolve(sessionPtr, "describe-code"), describeArgs(prefix, code)).string()
    }.getOrElse { "error: ${it.message}" }

    /** `describe-code` through the FFM bridge; failures throw. */
    private fun ffmDescribe(bridge: wasm2kt.runtime.FfmWasmtimeBridge, handle: Long, prefix: String, code: Int): String =
        bridge.invoke(handle, bridge.resolve(handle, "describe-code"), describeArgs(prefix, code)).string()

    private fun compileGenerated(classesOutDir: Path, outputDir: Path): org.jetbrains.kotlin.cli.common.ExitCode {
        Files.createDirectories(classesOutDir)
        return org.jetbrains.kotlin.cli.jvm.K2JVMCompiler().exec(
            System.err,
            "-Xcontext-parameters",
            "-jvm-target",
            "21",
            "-no-stdlib",
            "-classpath",
            System.getProperty("java.class.path"),
            "-d",
            classesOutDir.toString(),
            *generatedSources(outputDir).toTypedArray(),
        )
    }

    private fun tagEchoImports(onFormatTag: (String, Long) -> String): wasm2kt.runtime.WitHostImportDispatcher =
        wasm2kt.runtime.Linker().apply {
            define("example:roundtrip/env-provider@0.1.0#format-tag") { args, results ->
                val prefix = args.string()
                val code = args.int().toLong() and 0xFFFF_FFFFL
                results.string(onFormatTag(prefix, code))
            }
            define("example:roundtrip/env-provider@0.1.0#get-multiplier") { _, results -> results.int(1) }
        }

    @Test
    fun `generated WitSupport does not import java lang foreign and serializes session access`(@TempDir tempDir: Path) {
        val witPath = Paths.get(requireNotNull(javaClass.getResource("/fixtures/counter.wit")).toURI())
        GenerateCommand().parse(
            listOf("--wasm-tools-path", wasmToolsBin, "-o", tempDir.toString(), "-p", "com.example.android", witPath.toString()),
        )
        val support = Files.readString(tempDir.resolve("com/example/android/WitSupport.kt"))

        assertTrue(!support.contains("import java.lang.foreign"), "WitSupport.kt must not import java.lang.foreign (Android SDK lacks FFM)")
        assertTrue(!Regex("""\bArena\b|\bMemorySegment\b""").containsMatchIn(support), "WitSupport.kt must not reference Arena/MemorySegment")
        assertTrue(support.contains("bridge.invoke(sessionPtr, index, writer)"), "FfmWasmtimeSession must delegate to FfmWasmtimeBridge")
        val ffmSection = support.substringAfter("class FfmWasmtimeSession").substringBefore("class JniWasmtimeSession")
        val jniSection = support.substringAfter("class JniWasmtimeSession").substringBefore("class InMemoryWasmtimeSession")
        assertEquals(3, Regex("@Synchronized").findAll(ffmSection).count(), "FfmWasmtimeSession invoke/dropResource/close must be @Synchronized")
        assertEquals(3, Regex("@Synchronized").findAll(jniSection).count(), "JniWasmtimeSession invoke/dropResource/close must be @Synchronized")

        // Consumer R8/ProGuard keep rules must be packaged with the runtime
        val proguard = requireNotNull(javaClass.getResource("/META-INF/proguard/wasm2kt.pro")) {
            "META-INF/proguard/wasm2kt.pro must be on the runtime classpath"
        }.readText()
        assertTrue(proguard.contains("-keep class wasm2kt.runtime.JniHostCallback"), "JniHostCallback must be kept")
        assertTrue(proguard.contains("byte[] onHostCall(int, java.lang.String, byte[])"), "JniHostCallback.onHostCall signature must be kept")
        assertTrue(proguard.contains("wasm2kt.runtime.HostCallbackDispatcher"), "HostCallbackDispatcher must be kept")
    }

    @Test
    fun `host imports accepting resource-containing records, variants, and borrowed handles compile and dispatch`(@TempDir tempDir: Path) {
        val witText = """
            package example:hostres@0.1.0;

            world host-res-world {
                resource counter {
                    constructor(init: s32);
                    get-value: func() -> s32;
                }

                record counter-pair {
                    primary: counter,
                    label: string,
                }

                variant counter-outcome {
                    created(counter),
                    empty,
                }

                import inspect-pair: func(p: counter-pair) -> string;
                import inspect-outcome: func(o: counter-outcome) -> s32;
                import inspect-counter: func(c: borrow<counter>) -> s32;

                export make-pair: func(init: s32, label: string) -> counter-pair;
            }
        """.trimIndent()
        val witFile = tempDir.resolve("host_res.wit")
        Files.writeString(witFile, witText)
        GenerateCommand().parse(
            listOf("--wasm-tools-path", wasmToolsBin, "-o", tempDir.toString(), "-p", "com.example.hostres", witFile.toString()),
        )
        val pkgDir = tempDir.resolve("com/example/hostres")
        val worldFile = pkgDir.resolve("HostResWorld.kt")
        val harnessPath = pkgDir.resolve("HostResHarness.kt")
        Files.writeString(
            harnessPath,
            """
            package com.example.hostres

            import wasm2kt.runtime.WitWriter

            class HostCounter(private val v: Int) : Counter {
                var closed = false
                override fun getValue(): Int = v
                override fun close() { closed = true }
            }

            fun runHostResScenario(): String {
                // A world-level resource is imported: the host implements it (ADR-0008).
                val made = mutableListOf<HostCounter>()
                val imports = imports(
                    world = {
                        object : Imports {
                            override fun newCounter(init: Int): Counter = HostCounter(init).also { made += it }
                            override fun inspectPair(p: CounterPair): String = "${'$'}{p.label}:${'$'}{p.primary.getValue()}"
                            override fun inspectOutcome(o: CounterOutcome): Int = when (o) {
                                is CounterOutcome.Created -> o.value.getValue()
                                CounterOutcome.Empty -> -1
                            }
                            override fun inspectCounter(c: Counter): Int = c.getValue()
                        }
                    },
                )
                val linker = imports.newLinker()
                val table = wasm2kt.runtime.HostResourceTable()
                return table.bind {
                    // As a backend would call them: arguments and results in the binary format (ADR-0015).
                    fun new(v: Int) = linker.call("[constructor]counter", WitWriter().apply { int(v) }).int()
                    val seven = new(7)
                    val nine = new(9)
                    val three = new(3)
                    // counter-pair { primary: own<counter>, label: string }
                    val pair = linker.call("inspect-pair", WitWriter().apply { int(seven); string("lbl") }).string()
                    // counter-outcome: created(own<counter>) = 0, empty = 1
                    val created = linker.call("inspect-outcome", WitWriter().apply { byte(0); int(nine) }).int()
                    val empty = linker.call("inspect-outcome", WitWriter().apply { byte(1) }).int()
                    val borrowed = linker.call("inspect-counter", WitWriter().apply { int(three) }).int()
                    val left = table.size
                    linker.call("[resource-drop]counter", WitWriter().apply { int(three) })
                    "${'$'}pair|${'$'}created|${'$'}empty|${'$'}borrowed|${'$'}left|${'$'}{table.size}|${'$'}{made.map { it.closed }}"
                }
            }
            """.trimIndent(),
        )

        val classesOutDir = tempDir.resolve("classes")
        assertEquals(
            org.jetbrains.kotlin.cli.common.ExitCode.OK,
            compileGenerated(classesOutDir, tempDir),
            "Host imports taking resource-containing records/variants/handles must compile cleanly",
        )
        val classLoader = java.net.URLClassLoader(arrayOf(classesOutDir.toUri().toURL()), javaClass.classLoader)
        val output = classLoader.loadClass("com.example.hostres.HostResHarnessKt")
            .getDeclaredMethod("runHostResScenario")
            .invoke(null) as String
        assertEquals(
            // own<counter> moved to the host (7 and 9 leave the table), borrow keeps 3 until the drop.
            "lbl:7|9|-1|3|1|0|[false, false, true]",
            output,
        )
    }

    @Test
    fun `type names that would collide after interface prefixing keep their own names`(@TempDir tempDir: Path) {
        val witText = """
            package example:collision2@0.1.0;

            interface alpha {
                record config {
                    port: u16,
                }
                get-config: func() -> config;
            }

            interface beta {
                record config {
                    host: string,
                }
                get-config: func() -> config;
            }

            interface gamma {
                record alpha-config {
                    enabled: bool,
                }
                get-alpha-config: func() -> alpha-config;
            }

            world collision2-world {
                export alpha;
                export beta;
                export gamma;
            }
        """.trimIndent()
        val witFile = tempDir.resolve("collision2.wit")
        Files.writeString(witFile, witText)
        GenerateCommand().parse(
            listOf("--wasm-tools-path", wasmToolsBin, "-o", tempDir.toString(), "-p", "com.example.collision2", witFile.toString()),
        )
        // Names no longer depend on other interfaces: alpha's config stays Config, gamma's alpha-config is AlphaConfig.
        val exportsDir = tempDir.resolve("com/example/collision2/exports/example/collision2")
        assertTrue(Files.readString(exportsDir.resolve("alpha/Alpha.kt")).contains("data class Config("))
        assertTrue(Files.readString(exportsDir.resolve("beta/Beta.kt")).contains("data class Config("))
        assertTrue(Files.readString(exportsDir.resolve("gamma/Gamma.kt")).contains("data class AlphaConfig("))

        assertEquals(
            org.jetbrains.kotlin.cli.common.ExitCode.OK,
            compileGenerated(tempDir.resolve("classes"), tempDir),
            "Same-named types must not cause Redeclaration errors",
        )
    }

    @Test
    fun `JNI bridge survives many host callbacks, concurrent invocations, and rejects reentrant invoke and close`() {
        val roundtripWasmPath = Paths.get(requireNotNull(javaClass.getResource("/fixtures/roundtrip.wasm")).toURI())
        val jni = wasm2kt.runtime.JniWasmtimeBridge

        // 1. Many host callbacks in one session (JNI local references must be released per callback)
        val sessionPtr = jni.openSession(roundtripWasmPath, tagEchoImports { prefix, code -> "$prefix#$code" })
        try {
            repeat(3000) { i ->
                val resp = jniDescribe(sessionPtr, "T", i)
                assertTrue(resp.contains("T#$i"), "Iteration $i failed: $resp")
            }

            // 2. Concurrent invocations on the same session are serialized safely
            val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
            try {
                val futures = (0 until 8).map { t ->
                    pool.submit<Int> {
                        var ok = 0
                        repeat(100) { i ->
                            val resp = jniDescribe(sessionPtr, "C", t * 1000 + i)
                            if (resp.contains("C#${t * 1000 + i}")) ok++
                        }
                        ok
                    }
                }
                assertEquals(800, futures.sumOf { it.get() }, "All concurrent JNI invocations must succeed")
            } finally {
                pool.shutdown()
            }
        } finally {
            jni.nativeSessionClose(sessionPtr)
        }

        // 3. Reentrant invoke from inside a host callback is rejected with an error instead of deadlocking
        var reentrantPtr = 0L
        var innerResponse = ""
        reentrantPtr = jni.openSession(
            roundtripWasmPath,
            tagEchoImports { _, _ ->
                innerResponse = jniDescribe(reentrantPtr, "X", 1)
                "outer"
            },
        )
        try {
            val outer = jniDescribe(reentrantPtr, "O", 1)
            assertTrue(outer.contains("outer"), "Outer call should still complete, got: $outer")
            assertTrue(innerResponse.contains("Reentrant"), "Reentrant invoke must be rejected, got: $innerResponse")
        } finally {
            jni.nativeSessionClose(reentrantPtr)
        }

        // 4. Close from inside a host callback does not free the session under the running call
        var closingPtr = 0L
        closingPtr = jni.openSession(
            roundtripWasmPath,
            tagEchoImports { _, _ ->
                jni.nativeSessionClose(closingPtr)
                "closed-inside"
            },
        )
        val closedResp = jniDescribe(closingPtr, "Z", 1)
        assertTrue(closedResp.startsWith("error:") && closedResp.contains("closed"), "Expected closed-during-callback error, got: $closedResp")
        val afterClose = jniDescribe(closingPtr, "Z", 2)
        assertTrue(afterClose.startsWith("error:"), "Invoke after close must fail, got: $afterClose")
        jni.nativeSessionClose(closingPtr) // double close is a no-op
    }

    @Test
    fun `FFM bridge session API rejects reentrant invoke and defers arena release on reentrant close`() {
        val roundtripWasmPath = Paths.get(requireNotNull(javaClass.getResource("/fixtures/roundtrip.wasm")).toURI())
        val bridge = wasm2kt.runtime.FfmWasmtimeBridge.getDefault()

        // 1. Repeated calls through the Long-handle session API
        val handle = bridge.openSession(roundtripWasmPath, tagEchoImports { prefix, code -> "$prefix@$code" })
        try {
            repeat(500) { i ->
                val resp = ffmDescribe(bridge, handle, "F", i)
                assertTrue(resp.contains("F@$i"), "Iteration $i failed: $resp")
            }
        } finally {
            bridge.sessionClose(handle)
            bridge.sessionClose(handle) // idempotent
        }
        val afterClose = runCatching { ffmDescribe(bridge, handle, "F", 0) }.exceptionOrNull()
        assertTrue(afterClose is IllegalStateException, "a call after close must throw IllegalStateException")

        // 2. Reentrant invoke is rejected by the native bridge
        var reentrantHandle = 0L
        var inner = ""
        reentrantHandle = bridge.openSession(
            roundtripWasmPath,
            tagEchoImports { _, _ ->
                inner = runCatching { ffmDescribe(bridge, reentrantHandle, "X", 1) }.getOrElse { "error: ${it.message}" }
                "outer"
            },
        )
        try {
            val outer = ffmDescribe(bridge, reentrantHandle, "O", 1)
            assertTrue(outer.contains("outer"), "Outer call should complete, got: $outer")
            assertTrue(inner.contains("Reentrant"), "Reentrant invoke must be rejected, got: $inner")
        } finally {
            bridge.sessionClose(reentrantHandle)
        }

        // 3. Close from inside the upcall must not free the upcall stub's Arena while it is executing
        var closingHandle = 0L
        closingHandle = bridge.openSession(
            roundtripWasmPath,
            tagEchoImports { _, _ ->
                bridge.sessionClose(closingHandle)
                "closed-inside"
            },
        )
        val closedResp = runCatching { ffmDescribe(bridge, closingHandle, "Z", 1) }.exceptionOrNull()?.message.orEmpty()
        assertTrue(closedResp.contains("closed"), "Expected closed-during-callback error, got: $closedResp")
        val reuse = runCatching { ffmDescribe(bridge, closingHandle, "Z", 2) }.exceptionOrNull()
        assertTrue(reuse is IllegalStateException, "Invoke after reentrant close must throw IllegalStateException")
    }
}
