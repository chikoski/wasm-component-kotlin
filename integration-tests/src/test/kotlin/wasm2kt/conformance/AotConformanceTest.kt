package wasm2kt.conformance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.opentest4j.TestAbortedException
import wasm2kt.runtime.ComponentBackend
import wasm2kt.runtime.ComponentBackendFactory
import wasm2kt.runtime.ComponentTrapException
import wasm2kt.runtime.WitWriter
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
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name

/**
 * Component Model reference tests (`conformance/component-model/test/`) running on the Phase 1
 * AOT static component wiring backend (`values/` and `resources/`).
 *
 * Each `.wast` file compiles all of its non-invalid components in a single batched `K2JVMCompiler`
 * invocation for fast execution, then executes all `.wast` commands in file order.
 */
class AotConformanceTest {
    private companion object {
        val CREATES = setOf("module", "module_definition", "module_instance")
        val COMPILE_TARGETS = setOf("module", "module_definition", "assert_uninstantiable")
        const val TIMEOUT_MILLIS = 20_000L
    }

    private val root: Path = Paths.get("conformance").toAbsolutePath()
    private val suite: Path = root.resolve("component-model/test")
    private val work: Path = Paths.get("integration-tests/build/aot-conformance").toAbsolutePath()
    private val wasmToolsBin: String = System.getenv("WASM_TOOLS_BIN")?.takeIf { it.isNotBlank() }
        ?: Paths.get(System.getProperty("user.home"), ".cargo", "bin", "wasm-tools").takeIf { Files.isExecutable(it) }?.toString()
        ?: "wasm-tools"

    private val directories = listOf("values", "resources")

    private val skips: Map<String, String> = buildMap {
        Files.readAllLines(suite.resolve("nyi.txt")).map { it.trim() }.filter { it.startsWith("./") }
            .forEach { put(it.removePrefix("./"), "upstream nyi.txt") }
        val skipAot = root.resolve("skip-aot.txt")
        if (Files.exists(skipAot)) {
            Files.readAllLines(skipAot).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
                val key = line.substringBefore('#').trim()
                put(key, line.substringAfter('#', "no reason given").trim())
            }
        }
    }

    private val activeRuns = Collections.synchronizedList(mutableListOf<WastRun>())

    @AfterEach
    fun closeRuns() {
        val snapshot = synchronized(activeRuns) { activeRuns.toList().also { activeRuns.clear() } }
        snapshot.forEach { it.close() }
    }

    @TestFactory
    fun `component model reference tests on AOT`(): List<DynamicNode> = directories.flatMap { dir ->
        Files.list(suite.resolve(dir)).use { s -> s.filter { it.name.endsWith(".wast") }.sorted().toList() }.map { wast ->
            val rel = suite.relativize(wast).invariantSeparatorsPathString
            val fileSkip = skips[rel]
            if (fileSkip != null) {
                DynamicTest.dynamicTest(rel) { throw TestAbortedException("skipped: $fileSkip") }
            } else {
                val run = WastRun(wast, rel).also { activeRuns.add(it) }
                DynamicContainer.dynamicContainer(rel, run.tests())
            }
        }
    }

    private sealed interface PreparedComponent {
        class Ready(val decomposedDir: Path, val factoryClassName: String) : PreparedComponent
        class Failed(val error: Throwable) : PreparedComponent
    }

    private inner class WastRun(private val wast: Path, private val rel: String) : AutoCloseable {
        private val out = work.resolve(rel.removeSuffix(".wast"))
        private val classesDir = out.resolve("aot-classes")
        private val resourcesDir = out.resolve("aot-resources")
        private val kotlinDir = out.resolve("aot-kotlin")
        private val compiledKtDir = out.resolve("aot-compiled-kotlin")
        private val prepared = HashMap<String, PreparedComponent>()
        @Volatile
        private var classLoader: URLClassLoader? = null

        private val definitions = HashMap<String, String>()
        private val failedDefinitions = HashMap<String, String>()
        private val instances = HashMap<String, Instance>()
        private var current: Instance? = null
        private var activeCommandId: Long = 0L

        @Volatile
        private var broken: String? = null

        private inner class Instance(filename: String) : AutoCloseable {
            val types: PlanTypes
            val backend: ComponentBackend

            init {
                val entry = prepared[filename] ?: error("Component '$filename' was not prepared")
                when (entry) {
                    is PreparedComponent.Failed -> throw entry.error
                    is PreparedComponent.Ready -> {
                        types = PlanTypes.fromDirectory(entry.decomposedDir)
                        val loader = classLoader ?: error("ClassLoader not initialized")
                        val factoryClass = loader.loadClass(entry.factoryClassName)
                        val factory = factoryClass.getField("INSTANCE").get(null) as ComponentBackendFactory
                        backend = factory.instantiate(null)
                    }
                }
            }

            override fun close() = backend.close()
        }

        override fun close() {
            val toClose = synchronized(this) {
                ++activeCommandId
                LinkedHashSet<Instance>().apply {
                    current?.let { add(it) }
                    addAll(instances.values)
                    current = null
                    instances.clear()
                }
            }
            for (inst in toClose) {
                runCatching { inst.close() }
            }
            classLoader?.let { runCatching { it.close() } }
            classLoader = null
        }

        fun tests(): List<DynamicTest> {
            val commands = convert()
            if (commands.isEmpty()) {
                close()
                return emptyList()
            }
            prepareAllComponents(commands)
            return commands.mapIndexed { index, c ->
                val line = c.getValue("line").jsonPrimitive.content
                val type = c.getValue("type").jsonPrimitive.content
                DynamicTest.dynamicTest("$rel:$line $type ${c["action"]?.jsonObject?.get("field")?.jsonPrimitive?.content.orEmpty()}") {
                    try {
                        val skip = skips["$rel:$line"]
                        val failure = runCatching { withTimeout { cmdId -> run(c, cmdId) } }.exceptionOrNull()
                            .also { if (it != null && type in CREATES) broken = "$rel:$line" else if (type in CREATES) broken = null }
                        when {
                            failure is TestAbortedException -> throw failure
                            skip != null && failure == null -> throw AssertionError("$rel:$line passes now; remove it from conformance/skip-aot.txt ($skip)")
                            skip != null -> throw TestAbortedException("skipped: $skip (${failure!!.message})")
                            failure != null -> throw failure
                        }
                    } finally {
                        if (index == commands.lastIndex) {
                            close()
                        }
                    }
                }
            }
        }

        private fun prepareAllComponents(commands: List<JsonObject>) {
            val filesToBuild = LinkedHashSet<String>()
            for (c in commands) {
                val type = c.getValue("type").jsonPrimitive.content
                if (type in COMPILE_TARGETS) {
                    filesToBuild += c.getValue("filename").jsonPrimitive.content
                }
            }
            if (filesToBuild.isEmpty()) return

            Files.createDirectories(classesDir)
            Files.createDirectories(resourcesDir)
            Files.createDirectories(kotlinDir)
            Files.createDirectories(compiledKtDir)

            val pkgPrefix = "wasm2kt.aot.conf." + rel.removeSuffix(".wast").lowercase().replace(Regex("[^a-z0-9]+"), "_")
            val ktSources = ArrayList<String>()
            var idx = 0
            for (file in filesToBuild) {
                val modIdx = idx++
                val decompDir = out.resolve(file.removeSuffix(".wasm"))
                val pkg = "$pkgPrefix.m$modIdx"
                val internalPkg = "$pkg.wasm2kt"
                val compName = "conf-$modIdx"
                try {
                    if (!Files.exists(decompDir.resolve("plan.json"))) {
                        Decomposer.decompose(out.resolve(file), decompDir)
                    }
                    val compiled = AotComponentCompiler.compile(decompDir, classesDir, resourcesDir, internalPkg)
                    AotComponentCodegen.generateComponentOnly(kotlinDir, compName, pkg, compiled, internalPkg)
                    val factoryName = "$pkg.${ComponentCodegen.componentObjectName(compName)}"
                    ktSources += kotlinDir.resolve("${pkg.replace('.', '/')}/${ComponentCodegen.componentObjectName(compName)}.kt").toString()
                    prepared[file] = PreparedComponent.Ready(decompDir, factoryName)
                } catch (t: Throwable) {
                    prepared[file] = PreparedComponent.Failed(t)
                }
            }

            if (ktSources.isNotEmpty()) {
                val cp = listOf(System.getProperty("java.class.path"), classesDir.toString()).joinToString(File.pathSeparator)
                val exit = K2JVMCompiler().exec(
                    System.err,
                    "-Xcontext-parameters", "-jvm-target", "17", "-no-stdlib",
                    "-classpath", cp, "-d", compiledKtDir.toString(),
                    *ktSources.toTypedArray(),
                )
                if (exit == ExitCode.OK) {
                    classLoader = URLClassLoader(
                        arrayOf(compiledKtDir.toUri().toURL(), classesDir.toUri().toURL(), resourcesDir.toUri().toURL()),
                        AotConformanceTest::class.java.classLoader,
                    )
                } else {
                    val err = IllegalStateException("Batched AOT conformance compilation failed for $rel (exit $exit)")
                    for ((k, v) in prepared) {
                        if (v is PreparedComponent.Ready) prepared[k] = PreparedComponent.Failed(err)
                    }
                }
            }
        }

        private fun withTimeout(block: (Long) -> Unit) {
            val cmdId = synchronized(this) { ++activeCommandId }
            val failure = AtomicReference<Throwable?>()
            val t = Thread {
                try {
                    block(cmdId)
                } catch (e: Throwable) {
                    failure.set(e)
                }
            }
            t.isDaemon = true
            t.start()
            t.join(TIMEOUT_MILLIS)
            if (t.isAlive) {
                t.interrupt()
                val timedOut = synchronized(this) {
                    ++activeCommandId
                    val prev = current
                    current = null
                    if (prev != null) instances.entries.removeIf { it.value === prev }
                    prev
                }
                if (timedOut != null) runCatching { timedOut.close() }
                throw AssertionError("timed out after ${TIMEOUT_MILLIS / 1000} s")
            }
            failure.get()?.let { throw it }
        }

        private fun installInstance(cmdId: Long, alias: String?, inst: Instance) {
            val toClose = mutableListOf<Instance>()
            synchronized(this) {
                if (cmdId != activeCommandId) {
                    toClose.add(inst)
                } else {
                    val prevCurrent = current
                    current = inst
                    if (alias != null) {
                        val prevAlias = instances.put(alias, inst)
                        if (prevAlias != null && prevAlias !== current && prevAlias !in instances.values) {
                            toClose.add(prevAlias)
                        }
                    }
                    if (prevCurrent != null && prevCurrent !== current && prevCurrent !in instances.values) {
                        toClose.add(prevCurrent)
                    }
                }
            }
            for (old in toClose) {
                runCatching { old.close() }
            }
        }

        private fun replaceCurrent(next: Instance?) {
            val previous = synchronized(this) {
                val prev = current
                current = next
                if (prev != null && prev !in instances.values) prev else null
            }
            if (previous != null) runCatching { previous.close() }
        }

        private fun convert(): List<JsonObject> {
            out.toFile().deleteRecursively()
            Files.createDirectories(out)
            val json = out.resolve("commands.json")
            val p = ProcessBuilder(wasmToolsBin, "json-from-wast", wast.toString(), "-o", json.toString(), "--wasm-dir", out.toString())
                .redirectErrorStream(true).start()
            val log = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { "wasm-tools json-from-wast failed for $rel:\n$log" }
            return Json.parseToJsonElement(Files.readString(json)).jsonObject.getValue("commands").jsonArray.map { it.jsonObject }
        }

        private fun run(c: JsonObject, cmdId: Long) {
            when (val type = c.getValue("type").jsonPrimitive.content) {
                "module" -> {
                    replaceCurrent(null)
                    installInstance(cmdId, null, Instance(c.getValue("filename").jsonPrimitive.content))
                }
                "module_definition" -> {
                    val name = c.getValue("name").jsonPrimitive.content
                    val file = c.getValue("filename").jsonPrimitive.content
                    failedDefinitions[name] = "$rel:${c.getValue("line").jsonPrimitive.content}"
                    val entry = prepared[file] ?: error("Component '$file' was not prepared")
                    if (entry is PreparedComponent.Failed) throw entry.error
                    definitions[name] = file
                    failedDefinitions.remove(name)
                }
                "module_instance" -> {
                    replaceCurrent(null)
                    val name = c.getValue("module").jsonPrimitive.content
                    val file = definitions[name]
                        ?: failedDefinitions[name]?.let { throw TestAbortedException("its component could not be created ($it)") }
                        ?: error("Unknown definition in $c")
                    val inst = Instance(file)
                    installInstance(cmdId, c["instance"]?.jsonPrimitive?.content, inst)
                }
                "assert_return" -> {
                    val actual = invoke(c.getValue("action").jsonObject)
                    val expected = c.getValue("expected").jsonArray
                    check(expected.size == actual.size && expected.indices.all { WastValues.matches(expected[it], actual[it]) }) {
                        "Expected $expected, got $actual"
                    }
                }
                "assert_trap" -> {
                    val e = runCatching { invoke(c.getValue("action").jsonObject) }.exceptionOrNull()
                    if (e !is ComponentTrapException) {
                        throw AssertionError("Expected a trap (${c["text"]?.jsonPrimitive?.content}), got " + (e?.let { "$it" } ?: "a result"), e)
                    }
                }
                "action" -> invoke(c.getValue("action").jsonObject)
                "assert_invalid" -> {
                    val file = c.getValue("filename").jsonPrimitive.content
                    val dir = out.resolve(file.removeSuffix(".wasm"))
                    val e = runCatching { Decomposer.decompose(out.resolve(file), dir) }.exceptionOrNull()
                        ?: throw AssertionError("Expected an invalid component (${c["text"]?.jsonPrimitive?.content}), but it decomposed")
                    check(e !is TestAbortedException) { "$e" }
                }
                "assert_uninstantiable" -> {
                    val file = c.getValue("filename").jsonPrimitive.content
                    val e = runCatching { Instance(file).close() }.exceptionOrNull()
                    if (generateSequence(e) { it.cause }.none { it is ComponentTrapException }) {
                        throw AssertionError("Expected instantiation to trap (${c["text"]?.jsonPrimitive?.content}), got " + (e?.let { "$it" } ?: "an instance"), e)
                    }
                }
                else -> throw UnsupportedOperationException("Command '$type' is not supported by the conformance harness")
            }
        }

        private fun invoke(action: JsonObject): JsonArray {
            check(action.getValue("type").jsonPrimitive.content == "invoke") { "Unsupported action $action" }
            val instance = synchronized(this) {
                action["module"]?.jsonPrimitive?.content?.let { instances[it] ?: error("Unknown instance $it") }
                    ?: current
            }
                ?: broken?.let { throw TestAbortedException("its component could not be created ($it)") }
                ?: error("No component instance")
            val field = action.getValue("field").jsonPrimitive.content
            val type = instance.types.exportType(field) ?: error("No export '$field'")
            val args = action["args"]?.jsonArray.orEmpty()
            val w = WitWriter()
            args.forEachIndexed { i, a -> WastValues.write(a, type.params[i], w) }
            val r = instance.backend.invoke(instance.backend.resolve(field), w.toReader())
            val results = type.results.map { WastValues.read(it, r) }
            r.requireEnd()
            return JsonArray(results)
        }
    }
}
