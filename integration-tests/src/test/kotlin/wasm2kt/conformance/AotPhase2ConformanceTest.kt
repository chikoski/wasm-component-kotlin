package wasm2kt.conformance

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.opentest4j.TestAbortedException
import wasm2kt.ast.WitResolve
import wasm2kt.ast.WitTypeDefKind
import wasm2kt.ast.WitTypeRef
import wasm2kt.generator.AotTypeInfo
import wasm2kt.generator.AotWorldModel
import wasm2kt.generator.WitToKotlinPoetConverter
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name

/** Uniform invocation handle shared by Phase 2 direct-wired bridges and Phase 1 fallbacks in tests. */
interface Phase2WastInstance : AutoCloseable {
    fun invoke(field: String, args: JsonArray): JsonArray
}

/**
 * Component Model reference tests (`values/` and `resources/`) executed through the Phase 2
 * direct static AOT World DSL (`DirectCanonicalAbi` + `<Name>Component` / `openSession`).
 *
 * Components that use Component Model async intrinsics (e.g. `values/variants.wast`)
 * or have no valid WIT representation (`wit-error.txt`) match Gradle plugin behavior by falling
 * back to Phase 1 `ComponentBackend`.
 */
class AotPhase2ConformanceTest {
    private companion object {
        val CREATES = setOf("module", "module_definition", "module_instance")
        val COMPILE_TARGETS = setOf("module", "module_definition", "assert_uninstantiable")
        const val TIMEOUT_MILLIS = 20_000L
        val phase2ComponentCount = AtomicInteger(0)
    }

    private val root: Path = Paths.get("conformance").toAbsolutePath()
    private val suite: Path = root.resolve("component-model/test")
    private val work: Path = Paths.get("integration-tests/build/aot-phase2-conformance").toAbsolutePath()
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
    fun `synchronous conformance suites pass on Phase 2 AOT`(): List<DynamicNode> {
        val nodes = directories.flatMap { dir ->
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
        val verifyCoverage = DynamicTest.dynamicTest("verify non-trivial Phase 2 direct-wired coverage") {
            assertTrue(
                phase2ComponentCount.get() >= 30,
                "Expected at least 30 Phase 2 direct-wired components across values/ and resources/, got ${phase2ComponentCount.get()}",
            )
        }
        return nodes + verifyCoverage
    }

    private sealed interface PreparedComponent {
        class Ready(
            val decomposedDir: Path,
            val isDirectPhase2: Boolean,
            val entryClassName: String,
        ) : PreparedComponent

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
        private val instances = HashMap<String, InstanceHandle>()
        private var current: InstanceHandle? = null
        private var activeCommandId: Long = 0L

        @Volatile
        private var broken: String? = null

        private inner class InstanceHandle(filename: String) : AutoCloseable {
            val instance: Phase2WastInstance

            init {
                val entry = prepared[filename] ?: error("Component '$filename' was not prepared")
                when (entry) {
                    is PreparedComponent.Failed -> throw entry.error
                    is PreparedComponent.Ready -> {
                        val loader = classLoader ?: error("ClassLoader not initialized")
                        val cls = loader.loadClass(entry.entryClassName)
                        if (entry.isDirectPhase2) {
                            instance = cls.getDeclaredConstructor().newInstance() as Phase2WastInstance
                        } else {
                            val types = PlanTypes.fromDirectory(entry.decomposedDir)
                            val factory = cls.getField("INSTANCE").get(null) as ComponentBackendFactory
                            val backend: ComponentBackend = factory.instantiate(null)
                            instance = object : Phase2WastInstance {
                                override fun invoke(field: String, args: JsonArray): JsonArray {
                                    val type = types.exportType(field) ?: error("No export '$field'")
                                    val w = WitWriter()
                                    args.forEachIndexed { i, a -> WastValues.write(a, type.params[i], w) }
                                    val r = backend.invoke(backend.resolve(field), w.toReader())
                                    val results = type.results.map { WastValues.read(it, r) }
                                    r.requireEnd()
                                    return JsonArray(results)
                                }

                                override fun close() = backend.close()
                            }
                        }
                    }
                }
            }

            override fun close() = instance.close()
        }

        override fun close() {
            val toClose = synchronized(this) {
                ++activeCommandId
                LinkedHashSet<InstanceHandle>().apply {
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

            val pkgPrefix = "wasm2kt.aot.p2conf." + rel.removeSuffix(".wast").lowercase().replace(Regex("[^a-z0-9]+"), "_")
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
                    val witPath = decompDir.resolve("wit.json")
                    if (!compiled.plan.usesAsync && Files.exists(witPath)) {
                        val resolve = WitResolve.parse(Files.readString(witPath))
                        val converter = WitToKotlinPoetConverter(
                            resolve = resolve,
                            targetPackage = pkg,
                            emitWasmtimeSessions = false,
                            aotDirectMode = true,
                        )
                        converter.generate()
                        val model = requireNotNull(converter.aotModel)
                        check(!model.hasImports) { "Unexpected imports in conformance component $rel/$file: ${model.imports.keys}" }
                        AotComponentCodegen.generate(
                            decomposedDir = decompDir,
                            outputDir = kotlinDir,
                            componentName = compName,
                            packageName = pkg,
                            compiled = compiled,
                        )
                        emitPhase2Bridge(kotlinDir, pkg, compName, resolve, model)
                        Files.walk(kotlinDir.resolve(pkg.replace('.', '/'))).use { s ->
                            s.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                                .map { it.toString() }
                                .forEach { ktSources += it }
                        }
                        prepared[file] = PreparedComponent.Ready(decompDir, isDirectPhase2 = true, entryClassName = "$pkg.WastPhase2Bridge")
                    } else {
                        if (!Files.exists(witPath)) {
                            check(Files.exists(decompDir.resolve("wit-error.txt"))) {
                                "Missing both wit.json and wit-error.txt in $decompDir"
                            }
                        }
                        AotComponentCodegen.generateComponentOnly(kotlinDir, compName, pkg, compiled, internalPkg)
                        val factoryName = "$pkg.${ComponentCodegen.componentObjectName(compName)}"
                        ktSources += kotlinDir.resolve("${pkg.replace('.', '/')}/${ComponentCodegen.componentObjectName(compName)}.kt").toString()
                        prepared[file] = PreparedComponent.Ready(decompDir, isDirectPhase2 = false, entryClassName = factoryName)
                    }
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
                    val directCount = prepared.values.count { it is PreparedComponent.Ready && it.isDirectPhase2 }
                    phase2ComponentCount.addAndGet(directCount)
                    classLoader = URLClassLoader(
                        arrayOf(compiledKtDir.toUri().toURL(), classesDir.toUri().toURL(), resourcesDir.toUri().toURL()),
                        AotPhase2ConformanceTest::class.java.classLoader,
                    )
                } else {
                    for (file in filesToBuild) {
                        if (prepared[file] is PreparedComponent.Ready) {
                            prepared[file] = PreparedComponent.Failed(IllegalStateException("Phase 2 conformance AOT Kotlin sources failed to compile (exit=$exit)"))
                        }
                    }
                }
            }
        }

        private fun withTimeout(block: (Long) -> Unit) {
            val cmdId = synchronized(this) { activeCommandId }
            val failure = AtomicReference<Throwable?>(null)
            val thread = Thread {
                try {
                    block(cmdId)
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }.apply {
                name = "aot-p2-conf-$rel"
                isDaemon = true
                start()
            }
            thread.join(TIMEOUT_MILLIS)
            if (thread.isAlive) {
                close()
                thread.interrupt()
                thread.join(1_000)
                throw AssertionError("Timed out after ${TIMEOUT_MILLIS}ms")
            }
            failure.get()?.let { throw it }
        }

        private fun installInstance(cmdId: Long, name: String?, instance: InstanceHandle) {
            val obsolete = synchronized(this) {
                if (cmdId != activeCommandId) {
                    true
                } else {
                    current = instance
                    if (name != null) instances[name] = instance
                    false
                }
            }
            if (obsolete) {
                runCatching { instance.close() }
                throw TestAbortedException("command timed out before completing")
            }
        }

        private fun replaceCurrent(instance: InstanceHandle?): InstanceHandle? = synchronized(this) {
            val prev = current
            current = instance
            prev
        }

        private fun convert(): List<JsonObject> {
            out.toFile().deleteRecursively()
            Files.createDirectories(out)
            val p = ProcessBuilder(wasmToolsBin, "json-from-wast", wast.toString(), "--pretty", "--wasm-dir", out.toString())
                .redirectErrorStream(true)
                .start()
            val json = out.resolve(wast.name + ".json")
            p.inputStream.copyTo(Files.newOutputStream(json))
            check(p.waitFor() == 0) { "wasm-tools json-from-wast failed for $rel" }
            return Json.parseToJsonElement(Files.readString(json)).jsonObject.getValue("commands").jsonArray.map { it.jsonObject }
        }

        private fun run(c: JsonObject, cmdId: Long) {
            when (val type = c.getValue("type").jsonPrimitive.content) {
                "module" -> {
                    replaceCurrent(null)
                    installInstance(cmdId, null, InstanceHandle(c.getValue("filename").jsonPrimitive.content))
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
                    val inst = InstanceHandle(file)
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
                    val e = runCatching { InstanceHandle(file).close() }.exceptionOrNull()
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
            val args = action["args"]?.jsonArray.orEmpty()
            return instance.instance.invoke(field, JsonArray(args))
        }
    }
}

private fun emitPhase2Bridge(
    kotlinDir: Path,
    pkg: String,
    compName: String,
    resolve: WitResolve,
    model: AotWorldModel,
) {
    val bridgeVar = AtomicInteger(0)
    fun tmp(prefix: String) = "${prefix}_${bridgeVar.getAndIncrement()}"

    fun fromJsonExpr(ref: WitTypeRef, expr: CodeBlock, out: CodeBlock.Builder): CodeBlock {
        when (ref) {
            is WitTypeRef.Primitive -> return when (ref.name) {
                "bool" -> CodeBlock.of("(%L.%M.getValue(\"value\").%M.%M)", expr, JSON_OBJECT_FN, JSON_PRIMITIVE_FN, JSON_BOOLEAN_FN)
                "s8" -> CodeBlock.of("(%L.%M.getValue(\"value\").%M.content.toLong().toByte())", expr, JSON_OBJECT_FN, JSON_PRIMITIVE_FN)
                "u8" -> CodeBlock.of("(%L.%M.getValue(\"value\").%M.content.toLong().toUByte())", expr, JSON_OBJECT_FN, JSON_PRIMITIVE_FN)
                "s16" -> CodeBlock.of("(%L.%M.getValue(\"value\").%M.content.toLong().toShort())", expr, JSON_OBJECT_FN, JSON_PRIMITIVE_FN)
                "u16" -> CodeBlock.of("(%L.%M.getValue(\"value\").%M.content.toLong().toUShort())", expr, JSON_OBJECT_FN, JSON_PRIMITIVE_FN)
                "s32" -> CodeBlock.of("(%L.%M.getValue(\"value\").%M.content.toLong().toInt())", expr, JSON_OBJECT_FN, JSON_PRIMITIVE_FN)
                "u32" -> CodeBlock.of("(%L.%M.getValue(\"value\").%M.content.toLong().toUInt())", expr, JSON_OBJECT_FN, JSON_PRIMITIVE_FN)
                "s64" -> CodeBlock.of("(%L.%M.getValue(\"value\").%M.content.toLong())", expr, JSON_OBJECT_FN, JSON_PRIMITIVE_FN)
                "u64" -> CodeBlock.of("(%L.%M.getValue(\"value\").%M.content.toULong())", expr, JSON_OBJECT_FN, JSON_PRIMITIVE_FN)
                "f32" -> CodeBlock.of("java.lang.Float.intBitsToFloat(%L.%M.getValue(\"value\").%M.content.toLong().toInt())", expr, JSON_OBJECT_FN, JSON_PRIMITIVE_FN)
                "f64" -> CodeBlock.of("java.lang.Double.longBitsToDouble(%L.%M.getValue(\"value\").%M.content.toULong().toLong())", expr, JSON_OBJECT_FN, JSON_PRIMITIVE_FN)
                "char" -> CodeBlock.of("%T(%L.%M.getValue(\"value\").%M.content.codePointAt(0))", com.squareup.kotlinpoet.ClassName(pkg, "CodePoint"), expr, JSON_OBJECT_FN, JSON_PRIMITIVE_FN)
                "string" -> CodeBlock.of("(%L.%M.getValue(\"value\").%M.content)", expr, JSON_OBJECT_FN, JSON_PRIMITIVE_FN)
                else -> error("Unsupported WIT primitive: ${ref.name}")
            }
            is WitTypeRef.Id -> {
                val info = model.typeCatalog.get(ref.index, exportContext = true)
                return when (info) {
                    is AotTypeInfo.TypeAliasInfo -> fromJsonExpr(info.target, expr, out)
                    is AotTypeInfo.EnumInfo -> {
                        val kind = resolve.types[ref.index].kind as WitTypeDefKind.Enum
                        val v = tmp("enumVal")
                        val c = tmp("enumStr")
                        out.addStatement("val %L = %L.%M.getValue(\"value\").%M.content", c, expr, JSON_OBJECT_FN, JSON_PRIMITIVE_FN)
                        out.beginControlFlow("val %L = when (%L)", v, c)
                        kind.cases.forEachIndexed { i, ec ->
                            out.addStatement("%S -> %T.%L", ec.name, info.className, info.entryNames[i])
                        }
                        out.addStatement("else -> error(\"Unknown enum case: \$%L\")", c)
                        out.endControlFlow()
                        CodeBlock.of("%L", v)
                    }
                    is AotTypeInfo.FlagsInfo -> {
                        val kind = resolve.types[ref.index].kind as WitTypeDefKind.Flags
                        val v = tmp("flagsVal")
                        val rawVar = tmp("flagsRaw")
                        val zero = if (info.useULong) "0uL" else "0u"
                        val one = if (info.useULong) "1uL" else "1u"
                        out.addStatement("var %L = %L", rawVar, zero)
                        out.beginControlFlow("for (item in %L.%M.getValue(\"value\").%M)", expr, JSON_OBJECT_FN, JSON_ARRAY_FN)
                        out.beginControlFlow("when (item.%M.content)", JSON_PRIMITIVE_FN)
                        kind.flags.forEachIndexed { i, fl ->
                            out.addStatement("%S -> %L = %L or (%L shl %L)", fl.name, rawVar, rawVar, one, i)
                        }
                        out.addStatement("else -> error(\"Unknown flag: \${item.%M.content}\")", JSON_PRIMITIVE_FN)
                        out.endControlFlow()
                        out.endControlFlow()
                        out.addStatement("val %L = %T(%L)", v, info.className, rawVar)
                        CodeBlock.of("%L", v)
                    }
                    is AotTypeInfo.RecordInfo -> {
                        val mapVar = tmp("recMap")
                        out.addStatement("val %L = %L.%M.getValue(\"value\").%M.associate { it.%M[0].%M.content to it.%M[1] }",
                            mapVar, expr, JSON_OBJECT_FN, JSON_ARRAY_FN, JSON_ARRAY_FN, JSON_PRIMITIVE_FN, JSON_ARRAY_FN)
                        val kind = resolve.types[ref.index].kind as WitTypeDefKind.Record
                        val args = kind.fields.mapIndexed { idx, wf ->
                            val fv = tmp("field")
                            out.addStatement("val %L = %L[%S] ?: error(\"Missing field %L\")", fv, mapVar, wf.name, wf.name)
                            fromJsonExpr(info.fields[idx].type, CodeBlock.of("%L", fv), out)
                        }
                        val resVar = tmp("rec")
                        out.add("val %L = %T(", resVar, info.className)
                        args.forEachIndexed { i, a ->
                            if (i > 0) out.add(", ")
                            out.add("%L", a)
                        }
                        out.add(")\n")
                        CodeBlock.of("%L", resVar)
                    }
                    is AotTypeInfo.TupleInfo -> {
                        val arrVar = tmp("tupArr")
                        out.addStatement("val %L = %L.%M.getValue(\"value\").%M", arrVar, expr, JSON_OBJECT_FN, JSON_ARRAY_FN)
                        val els = info.types.mapIndexed { i, et ->
                            fromJsonExpr(et, CodeBlock.of("%L[%L]", arrVar, i), out)
                        }
                        val resVar = tmp("tup")
                        out.add("val %L = %T(", resVar, info.tupleClass)
                        els.forEachIndexed { i, a ->
                            if (i > 0) out.add(", ")
                            out.add("%L", a)
                        }
                        out.add(")\n")
                        CodeBlock.of("%L", resVar)
                    }
                    is AotTypeInfo.ListInfo -> {
                        val arrVar = tmp("listArr")
                        out.addStatement("val %L = %L.%M.getValue(\"value\").%M", arrVar, expr, JSON_OBJECT_FN, JSON_ARRAY_FN)
                        val resVar = tmp("listRes")
                        if (info.isByteArray) {
                            out.addStatement("val %L = ByteArray(%L.size) { idx -> %L[idx].%M.getValue(\"value\").%M.content.toLong().toByte() }",
                                resVar, arrVar, arrVar, JSON_OBJECT_FN, JSON_PRIMITIVE_FN)
                        } else {
                            out.beginControlFlow("val %L = %L.map { item ->", resVar, arrVar)
                            val inner = CodeBlock.builder()
                            val elExpr = fromJsonExpr(info.element, CodeBlock.of("item"), inner)
                            out.add(inner.build())
                            out.addStatement("%L", elExpr)
                            out.endControlFlow()
                        }
                        CodeBlock.of("%L", resVar)
                    }
                    is AotTypeInfo.VariantInfo -> {
                        val kind = resolve.types[ref.index].kind as WitTypeDefKind.Variant
                        val objVar = tmp("varObj")
                        val caseVar = tmp("varCase")
                        val resVar = tmp("varRes")
                        out.addStatement("val %L = %L.%M.getValue(\"value\").%M", objVar, expr, JSON_OBJECT_FN, JSON_OBJECT_FN)
                        out.addStatement("val %L = %L.getValue(\"case\").%M.content", caseVar, objVar, JSON_PRIMITIVE_FN)
                        out.beginControlFlow("val %L: %T = when (%L)", resVar, info.className, caseVar)
                        kind.cases.forEachIndexed { i, vc ->
                            val ci = info.cases[i]
                            if (ci.isObject) {
                                out.addStatement("%S -> %T", vc.name, ci.className)
                            } else {
                                out.beginControlFlow("%S ->", vc.name)
                                val inner = CodeBlock.builder()
                                val payload = fromJsonExpr(checkNotNull(ci.payloadType), CodeBlock.of("%L.getValue(\"payload\")", objVar), inner)
                                out.add(inner.build())
                                out.addStatement("%T(%L)", ci.className, payload)
                                out.endControlFlow()
                            }
                        }
                        out.addStatement("else -> error(\"Unknown variant case: \$%L\")", caseVar)
                        out.endControlFlow()
                        CodeBlock.of("%L", resVar)
                    }
                    is AotTypeInfo.OptionInfo -> {
                        val valVar = tmp("optVal")
                        val resVar = tmp("optRes")
                        out.addStatement("val %L = %L.%M[\"value\"] ?: %T", valVar, expr, JSON_OBJECT_FN, JSON_NULL_CLASS)
                        if (info.isWitOption) {
                            out.beginControlFlow("val %L = if (%L is %T) %T.None else", resVar, valVar, JSON_NULL_CLASS, info.witOptionClass)
                            val inner = CodeBlock.builder()
                            val vExpr = fromJsonExpr(info.inner, CodeBlock.of("%L", valVar), inner)
                            out.add(inner.build())
                            out.addStatement("%T.Some(%L)", info.witOptionClass, vExpr)
                            out.endControlFlow()
                        } else {
                            out.beginControlFlow("val %L = if (%L is %T) null else", resVar, valVar, JSON_NULL_CLASS)
                            val inner = CodeBlock.builder()
                            val vExpr = fromJsonExpr(info.inner, CodeBlock.of("%L", valVar), inner)
                            out.add(inner.build())
                            out.addStatement("%L", vExpr)
                            out.endControlFlow()
                        }
                        CodeBlock.of("%L", resVar)
                    }
                    is AotTypeInfo.ResultInfo -> {
                        val objVar = tmp("resObj")
                        val resVar = tmp("resultVal")
                        out.addStatement("val %L = %L.%M.getValue(\"value\").%M", objVar, expr, JSON_OBJECT_FN, JSON_OBJECT_FN)
                        out.beginControlFlow("val %L = if (\"Ok\" in %L)", resVar, objVar)
                        if (info.ok == null) {
                            out.addStatement("%T.Ok(Unit)", info.witResultClass)
                        } else {
                            val inner = CodeBlock.builder()
                            val okExpr = fromJsonExpr(info.ok!!, CodeBlock.of("%L.getValue(\"Ok\")", objVar), inner)
                            out.add(inner.build())
                            out.addStatement("%T.Ok(%L)", info.witResultClass, okExpr)
                        }
                        out.nextControlFlow("else")
                        if (info.err == null) {
                            out.addStatement("%T.Err(Unit)", info.witResultClass)
                        } else {
                            val inner = CodeBlock.builder()
                            val errExpr = fromJsonExpr(info.err!!, CodeBlock.of("%L.getValue(\"Err\")", objVar), inner)
                            out.add(inner.build())
                            out.addStatement("%T.Err(%L)", info.witResultClass, errExpr)
                        }
                        out.endControlFlow()
                        CodeBlock.of("%L", resVar)
                    }
                    is AotTypeInfo.HandleInfo, is AotTypeInfo.ResourceInfo, is AotTypeInfo.AsyncUnsupportedInfo -> {
                        out.addStatement("throw UnsupportedOperationException(\"Unsupported .wast argument type: %L\")", info)
                        CodeBlock.of("error(\"unreachable\")")
                    }
                }
            }
        }
    }

    fun toJsonExpr(ref: WitTypeRef, expr: CodeBlock, out: CodeBlock.Builder): CodeBlock {
        fun typed(typeName: String, valExpr: CodeBlock): CodeBlock =
            CodeBlock.of("%T(mapOf(\"type\" to %T(%S), \"value\" to %L))", JSON_OBJECT_CLASS, JSON_PRIMITIVE_CLASS, typeName, valExpr)
        when (ref) {
            is WitTypeRef.Primitive -> return when (ref.name) {
                "bool" -> typed("bool", CodeBlock.of("%T(%L)", JSON_PRIMITIVE_CLASS, expr))
                "s8" -> typed("s8", CodeBlock.of("%T(%L.toString())", JSON_PRIMITIVE_CLASS, expr))
                "u8" -> typed("u8", CodeBlock.of("%T(%L.toString())", JSON_PRIMITIVE_CLASS, expr))
                "s16" -> typed("s16", CodeBlock.of("%T(%L.toString())", JSON_PRIMITIVE_CLASS, expr))
                "u16" -> typed("u16", CodeBlock.of("%T(%L.toString())", JSON_PRIMITIVE_CLASS, expr))
                "s32" -> typed("s32", CodeBlock.of("%T(%L.toString())", JSON_PRIMITIVE_CLASS, expr))
                "u32" -> typed("u32", CodeBlock.of("%T(%L.toString())", JSON_PRIMITIVE_CLASS, expr))
                "s64" -> typed("s64", CodeBlock.of("%T(%L.toString())", JSON_PRIMITIVE_CLASS, expr))
                "u64" -> typed("u64", CodeBlock.of("%T(%L.toString())", JSON_PRIMITIVE_CLASS, expr))
                "f32" -> typed("f32", CodeBlock.of("%T((java.lang.Float.floatToRawIntBits(%L).toLong() and 0xFFFF_FFFFL).toString())", JSON_PRIMITIVE_CLASS, expr))
                "f64" -> typed("f64", CodeBlock.of("%T(java.lang.Double.doubleToRawLongBits(%L).toULong().toString())", JSON_PRIMITIVE_CLASS, expr))
                "char" -> typed("char", CodeBlock.of("%T(%L.toString())", JSON_PRIMITIVE_CLASS, expr))
                "string" -> typed("string", CodeBlock.of("%T(%L)", JSON_PRIMITIVE_CLASS, expr))
                else -> error("Unsupported WIT primitive: ${ref.name}")
            }
            is WitTypeRef.Id -> {
                val info = model.typeCatalog.get(ref.index, exportContext = true)
                return when (info) {
                    is AotTypeInfo.TypeAliasInfo -> toJsonExpr(info.target, expr, out)
                    is AotTypeInfo.EnumInfo -> {
                        val kind = resolve.types[ref.index].kind as WitTypeDefKind.Enum
                        val nameVar = tmp("enumJson")
                        out.beginControlFlow("val %L = when (%L)", nameVar, expr)
                        kind.cases.forEachIndexed { i, ec ->
                            out.addStatement("%T.%L -> %S", info.className, info.entryNames[i], ec.name)
                        }
                        out.endControlFlow()
                        typed("enum", CodeBlock.of("%T(%L)", JSON_PRIMITIVE_CLASS, nameVar))
                    }
                    is AotTypeInfo.FlagsInfo -> {
                        val kind = resolve.types[ref.index].kind as WitTypeDefKind.Flags
                        val v = tmp("flagsJson")
                        val one = if (info.useULong) "1uL" else "1u"
                        val zero = if (info.useULong) "0uL" else "0u"
                        out.addStatement("val %L = mutableListOf<%T>()", v, JSON_PRIMITIVE_CLASS)
                        kind.flags.forEachIndexed { i, fl ->
                            out.addStatement("if ((%L.raw and (%L shl %L)) != %L) %L += %T(%S)", expr, one, i, zero, v, JSON_PRIMITIVE_CLASS, fl.name)
                        }
                        typed("flags", CodeBlock.of("%T(%L)", JSON_ARRAY_CLASS, v))
                    }
                    is AotTypeInfo.RecordInfo -> {
                        val kind = resolve.types[ref.index].kind as WitTypeDefKind.Record
                        val v = tmp("recJson")
                        val src = tmp("recSrc")
                        out.addStatement("val %L = %L", src, expr)
                        out.addStatement("val %L = mutableListOf<%T>()", v, JSON_ELEMENT_CLASS)
                        kind.fields.forEachIndexed { idx, wf ->
                            val fi = info.fields[idx]
                            val inner = CodeBlock.builder()
                            val fJson = toJsonExpr(fi.type, CodeBlock.of("%L.%N", src, fi.kotlinName), inner)
                            out.add(inner.build())
                            out.addStatement("%L += %T(listOf(%T(%S), %L))", v, JSON_ARRAY_CLASS, JSON_PRIMITIVE_CLASS, wf.name, fJson)
                        }
                        typed("record", CodeBlock.of("%T(%L)", JSON_ARRAY_CLASS, v))
                    }
                    is AotTypeInfo.TupleInfo -> {
                        val v = tmp("tupJson")
                        val src = tmp("tupSrc")
                        out.addStatement("val %L = %L", src, expr)
                        out.addStatement("val %L = mutableListOf<%T>()", v, JSON_ELEMENT_CLASS)
                        info.types.forEachIndexed { i, et ->
                            val inner = CodeBlock.builder()
                            val elJson = toJsonExpr(et, CodeBlock.of("%L.f%L", src, i), inner)
                            out.add(inner.build())
                            out.addStatement("%L += %L", v, elJson)
                        }
                        typed("tuple", CodeBlock.of("%T(%L)", JSON_ARRAY_CLASS, v))
                    }
                    is AotTypeInfo.ListInfo -> {
                        val v = tmp("listJson")
                        val src = tmp("listSrc")
                        out.addStatement("val %L = %L", src, expr)
                        if (info.isByteArray) {
                            out.addStatement("val %L = %L.map { b -> %T(mapOf(\"type\" to %T(\"u8\"), \"value\" to %T((b.toInt() and 0xFF).toString()))) }",
                                v, src, JSON_OBJECT_CLASS, JSON_PRIMITIVE_CLASS, JSON_PRIMITIVE_CLASS)
                        } else {
                            out.beginControlFlow("val %L = %L.map { item ->", v, src)
                            val inner = CodeBlock.builder()
                            val elJson = toJsonExpr(info.element, CodeBlock.of("item"), inner)
                            out.add(inner.build())
                            out.addStatement("%L", elJson)
                            out.endControlFlow()
                        }
                        typed("list", CodeBlock.of("%T(%L)", JSON_ARRAY_CLASS, v))
                    }
                    is AotTypeInfo.VariantInfo -> {
                        val kind = resolve.types[ref.index].kind as WitTypeDefKind.Variant
                        val src = tmp("varSrc")
                        val obj = tmp("varJsonObj")
                        out.addStatement("val %L = %L", src, expr)
                        out.beginControlFlow("val %L = when (%L)", obj, src)
                        kind.cases.forEachIndexed { i, vc ->
                            val ci = info.cases[i]
                            if (ci.isObject) {
                                out.addStatement("is %T -> %T(mapOf(\"case\" to %T(%S)))", ci.className, JSON_OBJECT_CLASS, JSON_PRIMITIVE_CLASS, vc.name)
                            } else {
                                out.beginControlFlow("is %T ->", ci.className)
                                val inner = CodeBlock.builder()
                                val pJson = toJsonExpr(checkNotNull(ci.payloadType), CodeBlock.of("%L.value", src), inner)
                                out.add(inner.build())
                                out.addStatement("%T(mapOf(\"case\" to %T(%S), \"payload\" to %L))", JSON_OBJECT_CLASS, JSON_PRIMITIVE_CLASS, vc.name, pJson)
                                out.endControlFlow()
                            }
                        }
                        out.endControlFlow()
                        typed("variant", CodeBlock.of("%L", obj))
                    }
                    is AotTypeInfo.OptionInfo -> {
                        val src = tmp("optSrc")
                        val v = tmp("optJson")
                        out.addStatement("val %L = %L", src, expr)
                        if (info.isWitOption) {
                            out.beginControlFlow("val %L = when (%L)", v, src)
                            out.addStatement("is %T.None -> %T", info.witOptionClass, JSON_NULL_CLASS)
                            out.beginControlFlow("is %T.Some ->", info.witOptionClass)
                            val inner = CodeBlock.builder()
                            val innerJson = toJsonExpr(info.inner, CodeBlock.of("%L.value", src), inner)
                            out.add(inner.build())
                            out.addStatement("%L", innerJson)
                            out.endControlFlow()
                            out.endControlFlow()
                        } else {
                            out.beginControlFlow("val %L = if (%L == null) %T else", v, src, JSON_NULL_CLASS)
                            val inner = CodeBlock.builder()
                            val innerJson = toJsonExpr(info.inner, CodeBlock.of("%L", src), inner)
                            out.add(inner.build())
                            out.addStatement("%L", innerJson)
                            out.endControlFlow()
                        }
                        typed("option", CodeBlock.of("%L", v))
                    }
                    is AotTypeInfo.ResultInfo -> {
                        val src = tmp("resSrc")
                        val obj = tmp("resJsonObj")
                        out.addStatement("val %L = %L", src, expr)
                        out.beginControlFlow("val %L = when (%L)", obj, src)
                        out.beginControlFlow("is %T.Ok ->", info.witResultClass)
                        if (info.ok == null) {
                            out.addStatement("%T(mapOf(\"Ok\" to %T))", JSON_OBJECT_CLASS, JSON_NULL_CLASS)
                        } else {
                            val inner = CodeBlock.builder()
                            val okJson = toJsonExpr(info.ok!!, CodeBlock.of("%L.value", src), inner)
                            out.add(inner.build())
                            out.addStatement("%T(mapOf(\"Ok\" to %L))", JSON_OBJECT_CLASS, okJson)
                        }
                        out.endControlFlow()
                        out.beginControlFlow("is %T.Err ->", info.witResultClass)
                        if (info.err == null) {
                            out.addStatement("%T(mapOf(\"Err\" to %T))", JSON_OBJECT_CLASS, JSON_NULL_CLASS)
                        } else {
                            val inner = CodeBlock.builder()
                            val errJson = toJsonExpr(info.err!!, CodeBlock.of("%L.error", src), inner)
                            out.add(inner.build())
                            out.addStatement("%T(mapOf(\"Err\" to %L))", JSON_OBJECT_CLASS, errJson)
                        }
                        out.endControlFlow()
                        out.endControlFlow()
                        typed("result", CodeBlock.of("%L", obj))
                    }
                    is AotTypeInfo.HandleInfo, is AotTypeInfo.ResourceInfo, is AotTypeInfo.AsyncUnsupportedInfo -> {
                        out.addStatement("throw UnsupportedOperationException(\"Unsupported .wast result type: %L\")", info)
                        CodeBlock.of("error(\"unreachable\")")
                    }
                }
            }
        }
    }

    val compObjClass = com.squareup.kotlinpoet.ClassName(pkg, ComponentCodegen.componentObjectName(compName))
    val aotImportsClass = model.aotImportsClassName
    val sessionClass = model.sessionClassName

    val invokeBody = CodeBlock.builder()
    invokeBody.beginControlFlow("return when (field)")
    for ((exportKey, export) in model.exports) {
        invokeBody.beginControlFlow("%S ->", exportKey)
        val argExprs = export.params.mapIndexed { i, p ->
            fromJsonExpr(p.type, CodeBlock.of("args[%L]", i), invokeBody)
        }
        val callBlock = CodeBlock.builder()
        callBlock.add("session.%L(", export.methodName)
        argExprs.forEachIndexed { i, a ->
            if (i > 0) callBlock.add(", ")
            callBlock.add("%L", a)
        }
        callBlock.add(")")
        when (export.resultTypes.size) {
            0 -> {
                invokeBody.addStatement("%L", callBlock.build())
                invokeBody.addStatement("%T(emptyList())", JSON_ARRAY_CLASS)
            }
            1 -> {
                val resVar = tmp("out")
                invokeBody.addStatement("val %L = %L", resVar, callBlock.build())
                val j = toJsonExpr(export.resultTypes.single(), CodeBlock.of("%L", resVar), invokeBody)
                invokeBody.addStatement("%T(listOf(%L))", JSON_ARRAY_CLASS, j)
            }
            else -> {
                val resVar = tmp("out")
                invokeBody.addStatement("val %L = %L", resVar, callBlock.build())
                val elems = export.resultTypes.mapIndexed { i, rt ->
                    toJsonExpr(rt, CodeBlock.of("%L.f%L", resVar, i), invokeBody)
                }
                invokeBody.add("%T(listOf(", JSON_ARRAY_CLASS)
                elems.forEachIndexed { i, e ->
                    if (i > 0) invokeBody.add(", ")
                    invokeBody.add("%L", e)
                }
                invokeBody.add("))\n")
            }
        }
        invokeBody.endControlFlow()
    }
    invokeBody.addStatement("else -> error(\"No export '\$field'\")")
    invokeBody.endControlFlow()

    val bridgeClass = TypeSpec.classBuilder("WastPhase2Bridge")
        .addSuperinterface(Phase2WastInstance::class)
        .addProperty(
            com.squareup.kotlinpoet.PropertySpec.builder("session", sessionClass, KModifier.PRIVATE)
                .initializer("%T.instantiate(%T())", compObjClass, aotImportsClass)
                .build(),
        )
        .addFunction(
            FunSpec.builder("invoke")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter(ParameterSpec.builder("field", String::class).build())
                .addParameter(ParameterSpec.builder("args", JSON_ARRAY_CLASS).build())
                .returns(JSON_ARRAY_CLASS)
                .addCode(invokeBody.build())
                .build(),
        )
        .addFunction(
            FunSpec.builder("close")
                .addModifiers(KModifier.OVERRIDE)
                .addStatement("session.close()")
                .build(),
        )
        .build()

    FileSpec.builder(pkg, "WastPhase2Bridge")
        .addType(bridgeClass)
        .build()
        .writeTo(kotlinDir)
}

private val JSON_OBJECT_CLASS = JsonObject::class.asClassName()
private val JSON_ARRAY_CLASS = JsonArray::class.asClassName()
private val JSON_PRIMITIVE_CLASS = JsonPrimitive::class.asClassName()
private val JSON_ELEMENT_CLASS = JsonElement::class.asClassName()
private val JSON_NULL_CLASS = JsonNull::class.asClassName()
private val JSON_OBJECT_FN = com.squareup.kotlinpoet.MemberName("kotlinx.serialization.json", "jsonObject")
private val JSON_ARRAY_FN = com.squareup.kotlinpoet.MemberName("kotlinx.serialization.json", "jsonArray")
private val JSON_PRIMITIVE_FN = com.squareup.kotlinpoet.MemberName("kotlinx.serialization.json", "jsonPrimitive")
private val JSON_BOOLEAN_FN = com.squareup.kotlinpoet.MemberName("kotlinx.serialization.json", "boolean")
