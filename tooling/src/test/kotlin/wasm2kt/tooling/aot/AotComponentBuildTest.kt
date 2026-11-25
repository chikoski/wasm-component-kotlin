@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.tooling.aot

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import wasm2kt.runtime.ComponentBackend
import wasm2kt.runtime.ComponentBackendFactory
import wasm2kt.runtime.WitHostImportDispatcher
import wasm2kt.runtime.WitReader
import wasm2kt.runtime.WitWriter
import wasm2kt.runtime.canonical.InternalWasm2KtApi
import wasm2kt.runtime.chicory.ComponentPlan
import wasm2kt.tooling.PlanCheck
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AotComponentBuildTest {
    private val fixtures: Path = Paths.get(System.getProperty("wasm2kt.fixtures"))
    private val componentsDir: Path = fixtures.parent.resolve("runtime-chicory/src/test/resources/components")

    @Test
    fun `BuildPlan version and usesAsync match ComponentPlan and PlanCheck for all decomposed fixtures`() {
        assertEquals(ComponentPlan.SUPPORTED_VERSION, BuildPlan.SUPPORTED_VERSION)
        val dirs = componentsDir.listDirectoryEntries().filter { Files.isDirectory(it) }.sortedBy { it.name }
        assertTrue(dirs.isNotEmpty())
        for (dir in dirs) {
            val planJson = Files.readString(dir.resolve("plan.json"))
            val chicoryPlan = ComponentPlan.parse(planJson)
            val buildPlan = BuildPlan.fromDirectory(dir)
            assertEquals(chicoryPlan.usesAsync, buildPlan.usesAsync, "BuildPlan.usesAsync drifted for ${dir.name}")
            assertEquals(chicoryPlan.usesAsync, PlanCheck.usesAsync(dir), "PlanCheck.usesAsync drifted for ${dir.name}")
            if (buildPlan.trampolines.values.any { it is BuildTrampoline.Unsupported }) {
                assertTrue(PlanCheck.unsupportedFeatures(dir).isNotEmpty(), "Expected PlanCheck to report unsupported features for ${dir.name}")
            }
            assertTrue(buildPlan.modules.isNotEmpty(), "Expected modules in ${dir.name}")
            assertTrue(buildPlan.modules.all { it.file.endsWith(".wasm") }, "Expected .wasm filenames in ${dir.name}")
        }
    }

    @Test
    fun `AotComponentCompiler rejects async components at build time`(@TempDir dir: Path) {
        for (asyncName in listOf("async-stream", "async-import", "async-pipeline")) {
            val e = assertFailsWith<IllegalStateException>(asyncName) {
                AotComponentCompiler.compile(
                    decomposedDir = componentsDir.resolve(asyncName),
                    classesDir = dir.resolve("$asyncName-classes"),
                    resourcesDir = dir.resolve("$asyncName-resources"),
                    packageName = "com.example.async.$asyncName".replace('-', '_'),
                )
            }
            assertTrue("async" in e.message!!.lowercase(), "Expected async rejection message for $asyncName, got ${e.message}")
            assertTrue("AOT" in e.message!!, "Expected AOT rejection message for $asyncName, got ${e.message}")
        }
    }

    @Test
    fun `AotComponentBuild eliminates runtime plan, meta, Chicory, serialization, and reflection dependencies`(@TempDir dir: Path) {
        val forbiddenText = listOf(
            "kotlinx.serialization",
            "com.dylibso.chicory",
            "wasm2kt.runtime.chicory",
            "java.lang.reflect",
            "Class.forName",
            "getDeclaredMethod",
            "getDeclaredField",
        )
        val forbiddenConstantPoolPrefixes = listOf(
            "kotlinx/serialization/",
            "com/dylibso/chicory/",
            "wasm2kt/runtime/chicory/",
            "java/lang/reflect/",
        )

        for ((name, wasmFile) in listOf(
            "roundtrip" to "roundtrip.wasm",
            "counter" to "counter.wasm",
            "hostRes" to "host-res.wasm",
            "composedStr" to "composed-str.wasm",
            "composedRes" to "composed-res.wasm",
            "abiEdge" to "abi-edge.wasm",
        )) {
            val workDir = dir.resolve(name)
            val output = AotComponentBuild.run(
                component = fixtures.resolve(wasmFile),
                componentName = name,
                packageName = "com.example.purity.$name",
                workDir = workDir,
            )

            // No plan.json or .meta in runtime resources.
            val resourceFiles = walkFiles(output.resourcesDir)
            assertTrue(
                resourceFiles.none { it.name == "plan.json" || it.name.endsWith(".meta") },
                "$name resources must not contain plan.json or .meta, found $resourceFiles",
            )

            // Generated Kotlin contains zero Chicory / kotlinx.serialization / reflection references.
            val ktFiles = walkFiles(output.kotlinDir).filter { it.name.endsWith(".kt") }
            assertTrue(ktFiles.isNotEmpty(), "$name must emit Kotlin files")
            for (kt in ktFiles) {
                val text = Files.readString(kt)
                for (token in forbiddenText) {
                    assertTrue(token !in text, "$kt must not reference '$token'")
                }
            }

            // Compile generated Kotlin and inspect every .class constant pool in both core classes and Kotlin wiring.
            val compiledKtDir = workDir.resolve("compiled-kotlin")
            compileKotlin(ktFiles, output.classesDir, compiledKtDir)

            val allClasses = walkFiles(output.classesDir) + walkFiles(compiledKtDir)
                .filter { it.name.endsWith(".class") }
            assertTrue(allClasses.isNotEmpty())
            for (cls in allClasses.filter { it.name.endsWith(".class") }) {
                val utf8Entries = readConstantPoolUtf8s(Files.readAllBytes(cls))
                for (entry in utf8Entries) {
                    for (forbidden in forbiddenConstantPoolPrefixes) {
                        assertTrue(
                            forbidden !in entry,
                            "Class $cls constant pool entry '$entry' references forbidden '$forbidden'",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `generated AOT components execute World DSL, transcoders, host resources, composed resources, and heavy modules`(@TempDir dir: Path) {
        val roundtrip = AotComponentBuild.run(fixtures.resolve("roundtrip.wasm"), "roundtrip", "com.example.aot.rt", dir.resolve("rt"))
        val counter = AotComponentBuild.run(fixtures.resolve("counter.wasm"), "counter", "com.example.aot.ct", dir.resolve("ct"))
        val composedStr = AotComponentBuild.run(fixtures.resolve("composed-str.wasm"), "composed-str", "com.example.aot.cs", dir.resolve("cs"))
        val composedRes = AotComponentBuild.run(fixtures.resolve("composed-res.wasm"), "composed-res", "com.example.aot.cr", dir.resolve("cr"))
        val hostRes = AotComponentBuild.run(fixtures.resolve("host-res.wasm"), "host-res", "com.example.aot.hr", dir.resolve("hr"))
        val heavy = AotComponentBuild.run(fixtures.resolve("heavy.wasm"), "heavy", "com.example.aot.hv", dir.resolve("hv"))

        assertTrue(Files.exists(roundtrip.classesDir.resolve("com/example/aot/rt/wasm2kt/roundtrip/Core0Instance.class")))

        val harness = dir.resolve("AotHarness.kt")
        Files.writeString(
            harness,
            """
            package harness

            import com.example.aot.rt.RoundtripComponent
            import com.example.aot.rt.describeCode
            import com.example.aot.rt.echoU64
            import com.example.aot.rt.example.roundtrip.env_provider.EnvProvider
            import com.example.aot.rt.exports.example.roundtrip.math_api.scale
            import com.example.aot.ct.CounterComponent
            import com.example.aot.ct.exports.example.counter.counter_api.Counter
            import com.example.aot.cs.ComposedStrComponent
            import com.example.aot.cr.ComposedResComponent
            import com.example.aot.hr.HostResComponent
            import com.example.aot.hr.Imports as HostResImports
            import com.example.aot.hr.Ticket
            import com.example.aot.hr.example.host_res.store.Blob
            import com.example.aot.hr.example.host_res.store.Store
            import com.example.aot.hr.exports.example.host_res.guest_api.Counter as GuestCounter
            import com.example.aot.hr.makeAndMeasure
            import com.example.aot.hr.ticketId
            import com.example.aot.hv.HeavyComponent
            import wasm2kt.runtime.WitWriter

            fun run(): String {
                val rtImports = com.example.aot.rt.imports(
                    exampleRoundtripEnvProvider = {
                        object : EnvProvider {
                            override fun getMultiplier(): Int = 6
                            override fun formatTag(prefix: String, code: UInt): String = "${'$'}prefix#${'$'}code"
                        }
                    },
                )
                val rtOut = com.example.aot.rt.withSession(RoundtripComponent, rtImports) {
                    val scaled = scale(7)
                    val tag = describeCode("ERR", 404u)
                    val u64 = echoU64(ULong.MAX_VALUE) == ULong.MAX_VALUE
                    "${'$'}scaled,${'$'}tag,${'$'}u64"
                }

                val ctOut = com.example.aot.ct.withSession(CounterComponent) {
                    Counter(10).use { c ->
                        c.increment()
                        c.add(5)
                        c.getValue().toString()
                    }
                }

                val csOut = ComposedStrComponent.instantiate(null).use { b ->
                    val s = "aé日🎌".repeat(50)
                    listOf("utf8-utf16", "utf16-utf8", "compact-utf8", "utf8-compact").all { pair ->
                        val fn = b.resolve("echo-${'$'}pair")
                        b.invoke(fn, WitWriter().apply { string(s) }.toReader()).string() == s
                    }
                }

                val crOut = ComposedResComponent.instantiate(null).use { b ->
                    b.invoke(b.resolve("g"), WitWriter().toReader()).int()
                }

                var closedBlobs = 0
                val hrImports = com.example.aot.hr.imports(
                    exampleHostResStore = {
                        object : Store {
                            override fun newBlob(init: ByteArray): Blob = object : Blob {
                                private var bytes = init.copyOf()
                                override fun size(): UInt = bytes.size.toUInt()
                                override fun append(data: ByteArray) { bytes += data }
                                override fun read(): ByteArray = bytes
                                override fun close() { closedBlobs++ }
                            }
                            override fun blobMerge(a: Blob, b: Blob): Blob {
                                val merged = a.read() + b.read()
                                b.close()
                                return newBlob(merged)
                            }
                            override fun log(msg: String) {}
                            override fun find(name: String): Blob? = null
                        }
                    },
                    world = {
                        object : HostResImports {
                            override fun newTicket(id: UInt): Ticket = object : Ticket {
                                override fun id(): UInt = id
                                override fun close() {}
                            }
                        }
                    },
                )
                val hrOut = com.example.aot.hr.withSession(HostResComponent, hrImports) {
                    val sz = makeAndMeasure(byteArrayOf(1, 2), byteArrayOf(3, 4))
                    val t = ticketId(77u)
                    GuestCounter(10).use { gc ->
                        gc.poke()
                        "${'$'}sz:${'$'}t:${'$'}{gc.value()}:${'$'}closedBlobs"
                    }
                }

                val hvOut = com.example.aot.hv.withSession(HeavyComponent) {
                    com.example.aot.hv.lcgSum(1u, 100u).toString()
                }

                return "${'$'}rtOut;${'$'}ctOut;${'$'}csOut;${'$'}crOut;${'$'}hrOut;${'$'}hvOut"
            }
            """.trimIndent(),
        )

        val compiledOut = dir.resolve("compiled-harness")
        val allOutputs = listOf(roundtrip, counter, composedStr, composedRes, hostRes, heavy)
        val ktFiles = allOutputs.flatMap { out -> walkFiles(out.kotlinDir).filter { it.name.endsWith(".kt") } } + listOf(harness)
        val extraClasspath = allOutputs.map { it.classesDir }
        compileKotlin(ktFiles, extraClasspath, compiledOut)

        val urls = (listOf(compiledOut) + allOutputs.flatMap { listOf(it.classesDir, it.resourcesDir) })
            .map { it.toUri().toURL() }
            .toTypedArray()
        val result = URLClassLoader(urls, javaClass.classLoader).use { loader ->
            loader.loadClass("harness.AotHarnessKt").getDeclaredMethod("run").invoke(null) as String
        }
        val parts = result.split(";")
        assertEquals(listOf("42,ERR#404,true", "16", "true", "1", "4:77:11:1"), parts.dropLast(1))
        assertTrue(parts.last().toULong() > 0uL, "Expected positive lcgSum result, got ${parts.last()}")
    }

    @Test
    fun `AOT component with an unsupported type instantiates and fails only when invoked`(@TempDir dir: Path) {
        val decomposed = dir.resolve("decomposed")
        val classesDir = dir.resolve("classes")
        val resourcesDir = dir.resolve("resources")
        val kotlinDir = dir.resolve("kotlin")
        wasm2kt.tooling.Decomposer.decompose(fixtures.resolve("map-type.wasm"), decomposed)
        val compiled = AotComponentCompiler.compile(decomposed, classesDir, resourcesDir, "com.example.aot.map.wasm2kt")
        AotComponentCodegen.generateComponentOnly(
            outputDir = kotlinDir,
            componentName = "map-type",
            packageName = "com.example.aot.map",
            compiled = compiled,
            internalPackage = "com.example.aot.map.wasm2kt",
        )
        val ktFiles = walkFiles(kotlinDir).filter { it.name.endsWith(".kt") }
        val compiledOut = dir.resolve("compiled")
        compileKotlin(ktFiles, listOf(classesDir), compiledOut)

        val urls = arrayOf(compiledOut.toUri().toURL(), classesDir.toUri().toURL(), resourcesDir.toUri().toURL())
        URLClassLoader(urls, javaClass.classLoader).use { loader ->
            val factoryClass = loader.loadClass("com.example.aot.map.MapTypeComponent")
            val factory = factoryClass.getField("INSTANCE").get(null) as ComponentBackendFactory
            factory.instantiate(null as WitHostImportDispatcher?).use { backend ->
                assertEquals(42, backend.invoke(backend.resolve("answer"), WitWriter().toReader()).int())
                val e = runCatching {
                    backend.invoke(backend.resolve("size"), WitWriter().apply { length(0) }.toReader())
                }.exceptionOrNull()
                assertTrue(
                    e is UnsupportedOperationException && "not supported by the AOT backend" in (e.message ?: ""),
                    "Expected UnsupportedOperationException mentioning AOT backend, got $e",
                )
                assertEquals(42, backend.invoke(backend.resolve("answer"), WitWriter().toReader()).int(), "the instance stays usable")
            }
        }
    }

    private fun compileKotlin(sources: List<Path>, extraClassesDirs: Path, outDir: Path) =
        compileKotlin(sources, listOf(extraClassesDirs), outDir)

    private fun compileKotlin(sources: List<Path>, extraClassesDirs: List<Path>, outDir: Path) {
        Files.createDirectories(outDir)
        val cp = (listOf(System.getProperty("java.class.path")) + extraClassesDirs.map { it.toString() })
            .joinToString(File.pathSeparator)
        val args = listOf(
            "-Xcontext-parameters",
            "-jvm-target",
            "17",
            "-no-stdlib",
            "-classpath",
            cp,
            "-d",
            outDir.toString(),
        ) + sources.map { it.toString() }
        val exit = K2JVMCompiler().exec(System.err, *args.toTypedArray())
        assertEquals(ExitCode.OK, exit, "Generated AOT sources must compile")
    }

    private fun walkFiles(root: Path): List<Path> =
        if (!Files.exists(root)) emptyList()
        else Files.walk(root).use { s -> s.filter { Files.isRegularFile(it) }.toList().sorted() }

    /** Reads every `CONSTANT_Utf8` entry in a JVM `.class` file constant pool. */
    private fun readConstantPoolUtf8s(bytes: ByteArray): List<String> {
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        check(magic == 0xCAFEBABE.toInt()) { "Not a class file" }
        dis.readUnsignedShort() // minor
        dis.readUnsignedShort() // major
        val cpCount = dis.readUnsignedShort()
        val utf8s = ArrayList<String>()
        var i = 1
        while (i < cpCount) {
            when (val tag = dis.readUnsignedByte()) {
                1 -> utf8s.add(dis.readUTF())
                3, 4 -> dis.readInt()
                5, 6 -> {
                    dis.readLong()
                    i++ // 8-byte constants occupy two CP slots
                }
                7, 8, 16, 19, 20 -> dis.readUnsignedShort()
                9, 10, 11, 12, 17, 18 -> {
                    dis.readUnsignedShort()
                    dis.readUnsignedShort()
                }
                15 -> {
                    dis.readUnsignedByte()
                    dis.readUnsignedShort()
                }
                else -> error("Unknown constant pool tag $tag at index $i")
            }
            i++
        }
        return utf8s
    }
}
