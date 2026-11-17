package wasm2kt.conformance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.opentest4j.TestAbortedException
import wasm2kt.runtime.ComponentBackend
import wasm2kt.runtime.ComponentTrapException
import wasm2kt.runtime.WitWriter
import wasm2kt.runtime.chicory.ChicoryComponent
import wasm2kt.testing.PlanTypes
import wasm2kt.tooling.Decomposer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name

/**
 * The Component Model reference tests (`conformance/component-model`, a copy of WebAssembly/component-model's
 * `test/` at a fixed commit, see conformance/README.md) on the Chicory backend (#30).
 *
 * Each `.wast` file is converted with `wasm-tools json-from-wast`; each component is decomposed with the
 * bundled decomposer and run on Chicory's interpreter. One dynamic test per command, in file order.
 * Known failures are listed with their reason in `conformance/skip.txt` (and upstream's `nyi.txt`); a
 * listed command that passes fails the run, so the list stays current.
 */
class ConformanceTest {
    private companion object {
        val CREATES = setOf("module", "module_definition", "module_instance")
        const val TIMEOUT_MILLIS = 20_000L
    }

    private val root: Path = Paths.get("conformance").toAbsolutePath()
    private val suite: Path = root.resolve("component-model/test")
    private val work: Path = Paths.get("integration-tests/build/conformance").toAbsolutePath()
    private val wasmToolsBin: String = System.getenv("WASM_TOOLS_BIN")?.takeIf { it.isNotBlank() }
        ?: Paths.get(System.getProperty("user.home"), ".cargo", "bin", "wasm-tools").takeIf { Files.isExecutable(it) }?.toString()
        ?: "wasm-tools"

    /** The directories of the suite run so far; validation and linking later. */
    private val directories = listOf("values", "resources", "async")

    private val skips: Map<String, String> = buildMap {
        Files.readAllLines(suite.resolve("nyi.txt")).map { it.trim() }.filter { it.startsWith("./") }
            .forEach { put(it.removePrefix("./"), "upstream nyi.txt") }
        Files.readAllLines(root.resolve("skip.txt")).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
            val key = line.substringBefore('#').trim()
            put(key, line.substringAfter('#', "no reason given").trim())
        }
    }

    private val activeRuns = Collections.synchronizedList(mutableListOf<WastRun>())

    @AfterEach
    fun closeRuns() {
        val snapshot = synchronized(activeRuns) { activeRuns.toList().also { activeRuns.clear() } }
        snapshot.forEach { it.close() }
    }

    @TestFactory
    fun `component model reference tests on Chicory`(): List<DynamicNode> = directories.flatMap { dir ->
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

    /** One `.wast` file: its commands run in order against the current component instance. */
    private inner class WastRun(private val wast: Path, private val rel: String) : AutoCloseable {
        private val out = work.resolve(rel.removeSuffix(".wast"))
        private val definitions = HashMap<String, Path>()
        private val failedDefinitions = HashMap<String, String>()
        private val instances = HashMap<String, Instance>()
        private var current: Instance? = null
        private var activeCommandId: Long = 0L

        /** The command whose component could not be created; later commands on it are skipped, not failed. */
        @Volatile
        private var broken: String? = null

        private inner class Instance(dir: Path) : AutoCloseable {
            val types = PlanTypes.fromDirectory(dir)
            val backend: ComponentBackend = ChicoryComponent.fromDirectory(dir).instantiate(null)
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
        }

        fun tests(): List<DynamicTest> {
            val commands = convert()
            if (commands.isEmpty()) {
                close()
                return emptyList()
            }
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
                            skip != null && failure == null -> throw AssertionError("$rel:$line passes now; remove it from conformance/skip.txt ($skip)")
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

        /**
         * Runs a command on its own thread, failing it if it takes too long: a task that waits
         * forever must fail its test, not hang the run (async, #16).
         */
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

        /**
         * Installs a newly constructed instance for `cmdId`, or closes it immediately if the
         * command timed out or the run was closed in the meantime, also closing any replaced
         * `current` or displaced alias instance.
         */
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

        /** Closes the instance being replaced, unless a named instance still refers to it. */
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

        private fun decompose(file: String): Path {
            val dir = out.resolve(file.removeSuffix(".wasm"))
            if (!Files.exists(dir.resolve("plan.json"))) Decomposer.decompose(out.resolve(file), dir)
            return dir
        }

        private fun run(c: JsonObject, cmdId: Long) {
            when (val type = c.getValue("type").jsonPrimitive.content) {
                "module" -> {
                    replaceCurrent(null)
                    installInstance(cmdId, null, Instance(decompose(c.getValue("filename").jsonPrimitive.content)))
                }
                "module_definition" -> {
                    val name = c.getValue("name").jsonPrimitive.content
                    failedDefinitions[name] = "$rel:${c.getValue("line").jsonPrimitive.content}"
                    definitions[name] = decompose(c.getValue("filename").jsonPrimitive.content)
                    failedDefinitions.remove(name)
                }
                "module_instance" -> {
                    replaceCurrent(null)
                    val name = c.getValue("module").jsonPrimitive.content
                    val def = definitions[name]
                        ?: failedDefinitions[name]?.let { throw TestAbortedException("its component could not be created ($it)") }
                        ?: error("Unknown definition in $c")
                    val inst = Instance(def)
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
                    val e = runCatching { decompose(file) }.exceptionOrNull()
                        ?: throw AssertionError("Expected an invalid component (${c["text"]?.jsonPrimitive?.content}), but it decomposed")
                    check(e !is TestAbortedException) { "$e" }
                }
                "assert_uninstantiable" -> {
                    val dir = decompose(c.getValue("filename").jsonPrimitive.content)
                    val e = runCatching { Instance(dir).close() }.exceptionOrNull()
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
