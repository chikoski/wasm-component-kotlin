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
import wasm2kt.runtime.ComponentTrapException
import wasm2kt.runtime.aot.AotSpillFrame
import wasm2kt.runtime.aot.CoreModuleInstance
import wasm2kt.runtime.aot.ExternRefGlobal
import wasm2kt.runtime.aot.F32Global
import wasm2kt.runtime.aot.F64Global
import wasm2kt.runtime.aot.FuncRef
import wasm2kt.runtime.aot.FuncRefGlobal
import wasm2kt.runtime.aot.HostTrampoline
import wasm2kt.runtime.aot.I32Global
import wasm2kt.runtime.aot.I64Global
import wasm2kt.runtime.aot.LinearMemory
import wasm2kt.runtime.aot.WasmTable
import wasm2kt.runtime.canonical.InternalWasm2KtApi
import wasm2kt.tooling.aot.AotCoreCompiler
import wasm2kt.tooling.aot.CompiledAotModule
import wasm2kt.tooling.aot.CompiledExport
import wasm2kt.tooling.ir.ChicoryWasmAdapter
import wasm2kt.tooling.ir.IrExportKind
import wasm2kt.tooling.ir.IrFuncType
import wasm2kt.tooling.ir.IrImport
import wasm2kt.tooling.ir.IrModule
import wasm2kt.tooling.ir.IrMutability
import wasm2kt.tooling.ir.IrValType
import wasm2kt.tooling.ir.UnsupportedWasmFeatureException
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name

/**
 * Core WebAssembly 2.0 specification reference test runner (`conformance/core-wasm/spec`, excluding
 * `simd/`).
 *
 * Each `.wast` file is lowered via `wasm-tools json-from-wast`; every binary module is lowered
 * through [ChicoryWasmAdapter], compiled to JVM bytecode with [AotCoreCompiler], verified with
 * ASM (`verifyAll`), linked across modules (including `spectest`), and executed directly on the
 * compiled JVM classes. Known limitations must be explicitly listed with reasons in
 * `conformance/core-wasm/skip.txt`.
 */
@OptIn(InternalWasm2KtApi::class)
class CoreWasmConformanceTest {
    private companion object {
        const val TIMEOUT_MILLIS = 30_000L
        val EMPTY_ARGS = emptyArray<Any?>()
    }

    private val root: Path = Paths.get("conformance/core-wasm").toAbsolutePath()
    private val suite: Path = root.resolve("spec")
    private val work: Path = Paths.get("integration-tests/build/conformance-core-wasm").toAbsolutePath()
    private val wasmToolsBin: String = System.getenv("WASM_TOOLS_BIN")?.takeIf { it.isNotBlank() }
        ?: Paths.get(System.getProperty("user.home"), ".cargo", "bin", "wasm-tools").takeIf { Files.isExecutable(it) }?.toString()
        ?: "wasm-tools"

    private val skips: Map<String, String> = buildMap {
        val skipFile = root.resolve("skip.txt")
        if (Files.exists(skipFile)) {
            Files.readAllLines(skipFile)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .forEach { line ->
                    val key = line.substringBefore('#').trim()
                    val reason = line.substringAfter('#', "no reason given").trim()
                    put(key, reason)
                }
        }
    }

    private val activeRuns = Collections.synchronizedList(mutableListOf<WastRun>())

    @AfterEach
    fun cleanupRuns() {
        val snapshot = synchronized(activeRuns) { activeRuns.toList().also { activeRuns.clear() } }
        snapshot.forEach { it.close() }
    }

    @TestFactory
    fun `core wasm 2_0 spec conformance tests on AOT bytecode compiler`(): List<DynamicNode> {
        val wastFiles = Files.list(suite).use { s ->
            s.filter { it.name.endsWith(".wast") }.sorted().toList()
        }
        return wastFiles.map { wast ->
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

    private class LinkageException(message: String) : RuntimeException(message)

    private data class ExternHostRef(val id: String) {
        override fun toString(): String = "externref($id)"
    }

    private sealed interface ExportedItem {
        data class Func(
            val funcType: IrFuncType,
            val canonicalSig: String,
            val invoker: (Array<Any?>) -> Array<Any?>,
            val funcRef: FuncRef,
        ) : ExportedItem

        data class Table(
            val table: WasmTable<Any>,
            val elementType: IrValType,
            val minSize: Long,
            val maxSize: Long?,
        ) : ExportedItem

        data class Memory(
            val memory: LinearMemory,
            val initialPages: Int,
            val maxPages: Int?,
        ) : ExportedItem

        data class Global(
            val valType: IrValType,
            val mutability: IrMutability,
            val reader: () -> Any?,
            val importArgValue: Any?,
        ) : ExportedItem
    }

    private class ModuleRuntime(
        val exports: Map<String, ExportedItem>,
    )

    private class CompiledModuleClassLoader(
        private val compiled: CompiledAotModule,
        parent: ClassLoader = AotCoreCompiler::class.java.classLoader,
    ) : ClassLoader(parent) {
        override fun findClass(name: String): Class<*> {
            val internal = name.replace('.', '/')
            val bytes = compiled.classes[internal] ?: throw ClassNotFoundException(name)
            return defineClass(name, bytes, 0, bytes.size)
        }

        override fun getResourceAsStream(name: String): InputStream? {
            val resPath = compiled.dataResourcePath
            val resBytes = compiled.dataResourceBytes
            if (resPath != null && resBytes != null && name.removePrefix("/") == resPath) {
                return resBytes.inputStream()
            }
            return super.getResourceAsStream(name)
        }
    }

    private inner class WastRun(private val wast: Path, private val rel: String) : AutoCloseable {
        private val out: Path = work.resolve(rel.removeSuffix(".wast"))
        private val registry = HashMap<String, Map<String, ExportedItem>>()
        private val namedModules = HashMap<String, ModuleRuntime>()
        private val namedCompileFailed = HashSet<String>()
        private val externRefs = HashMap<String, ExternHostRef>()
        private var current: ModuleRuntime? = null
        private var brokenReason: String? = null
        private var nextModuleIndex = 0

        init {
            registry["spectest"] = buildSpectestExports()
        }

        override fun close() {
            registry.clear()
            namedModules.clear()
            current = null
        }

        fun tests(): List<DynamicTest> {
            val commands = convertWastToJson()
            if (commands.isEmpty()) {
                close()
                return emptyList()
            }
            return commands.mapIndexed { index, cmd ->
                val line = cmd.getValue("line").jsonPrimitive.content
                val type = cmd.getValue("type").jsonPrimitive.content
                val actionField = cmd["action"]?.jsonObject?.get("field")?.jsonPrimitive?.content
                val displayName = if (actionField != null) "$rel:$line $type ($actionField)" else "$rel:$line $type"
                DynamicTest.dynamicTest(displayName) {
                    try {
                        val skipReason = skips["$rel:$line"]
                        val failure = runCatching { withTimeout { runCommand(cmd) } }.exceptionOrNull()
                        if (type == "module") {
                            if (failure != null) brokenReason = "$rel:$line" else brokenReason = null
                        }
                        when {
                            failure is TestAbortedException -> throw failure
                            skipReason != null && failure == null ->
                                throw AssertionError("$rel:$line passes now; remove it from conformance/core-wasm/skip.txt ($skipReason)")
                            skipReason != null ->
                                throw TestAbortedException("skipped: $skipReason (${failure!!.message})")
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

        private fun withTimeout(block: () -> Unit) {
            val failure = AtomicReference<Throwable?>()
            val t = Thread {
                try {
                    block()
                } catch (e: Throwable) {
                    failure.set(e)
                }
            }
            t.isDaemon = true
            t.start()
            t.join(TIMEOUT_MILLIS)
            if (t.isAlive) {
                t.interrupt()
                throw AssertionError("timed out after ${TIMEOUT_MILLIS / 1000} s")
            }
            failure.get()?.let { throw it }
        }

        private fun convertWastToJson(): List<JsonObject> {
            out.toFile().deleteRecursively()
            Files.createDirectories(out)
            val json = out.resolve("commands.json")
            val p = ProcessBuilder(
                wasmToolsBin,
                "json-from-wast",
                wast.toString(),
                "-o",
                json.toString(),
                "--wasm-dir",
                out.toString(),
            ).redirectErrorStream(true).start()
            val log = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { "wasm-tools json-from-wast failed for $rel:\n$log" }
            return Json.parseToJsonElement(Files.readString(json))
                .jsonObject
                .getValue("commands")
                .jsonArray
                .map { it.jsonObject }
        }

        private fun runCommand(c: JsonObject) {
            when (val type = c.getValue("type").jsonPrimitive.content) {
                "module" -> {
                    val modName = c["name"]?.jsonPrimitive?.content
                    current = null
                    val wasmPath = resolveWasmFile(c)
                    val inst = try {
                        val wasmBytes = Files.readAllBytes(wasmPath)
                        compileAndInstantiate(wasmBytes)
                    } catch (e: Throwable) {
                        if (modName != null) namedCompileFailed.add(modName)
                        throw e
                    }
                    current = inst
                    if (modName != null) {
                        namedCompileFailed.remove(modName)
                        namedModules[modName] = inst
                    }
                }
                "register" -> {
                    val asName = c.getValue("as").jsonPrimitive.content
                    val srcName = c["name"]?.jsonPrimitive?.content
                    val srcMod = if (srcName != null) {
                        if (srcName in namedCompileFailed) {
                            throw TestAbortedException("module $srcName failed earlier")
                        }
                        namedModules[srcName] ?: error("Unknown named module '$srcName'")
                    } else {
                        current ?: brokenReason?.let { throw TestAbortedException("module failed at $it") }
                            ?: error("No current module to register")
                    }
                    registry[asName] = srcMod.exports
                }
                "action" -> {
                    executeAction(c.getValue("action").jsonObject)
                }
                "assert_return" -> {
                    val actual = executeAction(c.getValue("action").jsonObject)
                    val expected = c.getValue("expected").jsonArray
                    if (expected.size != actual.size || !expected.indices.all { i -> matchesValue(expected[i].jsonObject, actual[i]) }) {
                        throw AssertionError("Expected ${expected}, got ${actual.map(::formatActual)}")
                    }
                }
                "assert_trap" -> {
                    val expectedText = c["text"]?.jsonPrimitive?.content
                    val e = runCatching { executeAction(c.getValue("action").jsonObject) }.exceptionOrNull()
                    if (e is TestAbortedException) throw e
                    val trap = e?.let { generateSequence(it) { cur -> cur.cause }.filterIsInstance<ComponentTrapException>().firstOrNull() }
                    if (trap == null) {
                        throw AssertionError("Expected a trap ($expectedText), got ${e ?: "a normal return"}", e)
                    }
                    if (!matchesTrapReason(expectedText, trap.message)) {
                        throw AssertionError("Trap message mismatch for '$expectedText': got '${trap.message}'", trap)
                    }
                }
                "assert_exhaustion" -> {
                    val e = runCatching { executeAction(c.getValue("action").jsonObject) }.exceptionOrNull()
                    if (e is TestAbortedException) throw e
                    val hasStackOverflow = e != null && generateSequence(e) { it.cause }.any { it is StackOverflowError }
                    if (!hasStackOverflow) {
                        throw AssertionError("Expected StackOverflowError (${c["text"]?.jsonPrimitive?.content}), got ${e ?: "a normal return"}", e)
                    }
                }
                "assert_invalid" -> {
                    if (c["module_type"]?.jsonPrimitive?.content == "text") {
                        throw TestAbortedException("text-format assert_invalid is out of AOT binary compiler scope")
                    }
                    val wasmPath = resolveWasmFile(c)
                    val wasmBytes = Files.readAllBytes(wasmPath)
                    val e = runCatching {
                        ChicoryWasmAdapter.convert(wasmBytes, moduleIndex = nextModuleIndex++)
                    }.exceptionOrNull()
                    if (e !is IllegalArgumentException && e !is UnsupportedWasmFeatureException) {
                        throw AssertionError("Expected invalid module (${c["text"]?.jsonPrimitive?.content}) rejected by ChicoryWasmAdapter, got ${e ?: "accepted"}", e)
                    }
                }
                "assert_malformed" -> {
                    val moduleType = c["module_type"]?.jsonPrimitive?.content
                    if (moduleType == "text") {
                        throw TestAbortedException("text-format assert_malformed is out of AOT binary compiler scope")
                    }
                    val fileName = commandFilename(c)
                    val wasmPath = out.resolve(fileName ?: error("Missing filename in $c"))
                    if (!Files.exists(wasmPath)) {
                        error("Missing malformed wasm file $wasmPath")
                    }
                    val wasmBytes = Files.readAllBytes(wasmPath)
                    val e = runCatching {
                        ChicoryWasmAdapter.convert(wasmBytes, moduleIndex = nextModuleIndex++)
                    }.exceptionOrNull()
                    if (e !is IllegalArgumentException && e !is UnsupportedWasmFeatureException) {
                        throw AssertionError("Expected malformed module (${c["text"]?.jsonPrimitive?.content}) rejected by ChicoryWasmAdapter, got ${e ?: "accepted"}", e)
                    }
                }
                "assert_unlinkable" -> {
                    val wasmPath = resolveWasmFile(c)
                    val wasmBytes = Files.readAllBytes(wasmPath)
                    val e = runCatching { compileAndInstantiate(wasmBytes) }.exceptionOrNull()
                    if (e !is LinkageException) {
                        throw AssertionError("Expected unlinkable error (${c["text"]?.jsonPrimitive?.content}), got ${e ?: "instantiated"}", e)
                    }
                }
                "assert_uninstantiable" -> {
                    val wasmPath = resolveWasmFile(c)
                    val wasmBytes = Files.readAllBytes(wasmPath)
                    val e = runCatching { compileAndInstantiate(wasmBytes) }.exceptionOrNull()
                    val hasTrap = e != null && generateSequence(e) { it.cause }.any {
                        it is ComponentTrapException || it is StackOverflowError
                    }
                    if (!hasTrap) {
                        throw AssertionError("Expected uninstantiable trap (${c["text"]?.jsonPrimitive?.content}), got ${e ?: "instantiated"}", e)
                    }
                }
                else -> throw UnsupportedOperationException("Unsupported command type '$type' in $c")
            }
        }

        private fun commandFilename(c: JsonObject): String? =
            (c["binary_filename"] ?: c["filename"])?.jsonPrimitive?.content

        private fun matchesTrapReason(expected: String?, actual: String?): Boolean {
            if (expected == null) return true
            val exp = expected.lowercase()
            val act = actual.orEmpty().lowercase()
            return when {
                exp.contains("divide by zero") -> act.contains("divide by zero")
                exp.contains("integer overflow") -> act.contains("integer overflow")
                exp.contains("invalid conversion to integer") -> act.contains("invalid conversion to integer")
                exp.contains("unreachable") -> act.contains("unreachable")
                exp.contains("indirect call type mismatch") -> act.contains("indirect call type mismatch")
                exp.contains("out of bounds memory access") ->
                    act.contains("out of bounds") && (act.contains("memory") || act.contains("segment"))
                exp.contains("out of bounds table access") ->
                    act.contains("out of bounds") && (act.contains("table") || act.contains("elem") || act.contains("segment"))
                exp.contains("uninitialized element") -> act.contains("uninitialized element")
                exp.contains("undefined element") ->
                    act.contains("uninitialized element") || (act.contains("out of bounds") && act.contains("table"))
                else -> act.contains(exp)
            }
        }

        private fun resolveWasmFile(c: JsonObject): Path {
            val file = commandFilename(c) ?: error("Missing filename in command $c")
            return out.resolve(file)
        }

        private fun executeAction(action: JsonObject): List<Any?> {
            val modName = action["module"]?.jsonPrimitive?.content
            val targetMod = if (modName != null) {
                if (modName in namedCompileFailed) {
                    throw TestAbortedException("module $modName failed earlier")
                }
                namedModules[modName] ?: error("Unknown instance '$modName'")
            } else {
                current ?: brokenReason?.let { throw TestAbortedException("module failed at $it") }
                    ?: error("No current module instance")
            }
            val field = action.getValue("field").jsonPrimitive.content
            return when (val actType = action.getValue("type").jsonPrimitive.content) {
                "invoke" -> {
                    val exp = targetMod.exports[field] as? ExportedItem.Func
                        ?: error("Export '$field' is not a function")
                    val jsonArgs = action["args"]?.jsonArray.orEmpty()
                    val args = Array(jsonArgs.size) { i -> parseArgValue(jsonArgs[i].jsonObject) }
                    exp.invoker(args).toList()
                }
                "get" -> {
                    val exp = targetMod.exports[field] as? ExportedItem.Global
                        ?: error("Export '$field' is not a global")
                    listOf(exp.reader())
                }
                else -> throw UnsupportedOperationException("Unsupported action '$actType'")
            }
        }

        private fun compileAndInstantiate(wasmBytes: ByteArray): ModuleRuntime {
            val modIdx = nextModuleIndex++
            val ir = ChicoryWasmAdapter.convert(wasmBytes, moduleIndex = modIdx)
            val compiled = AotCoreCompiler.compile(ir, "wasm2kt.conformance.core", "Core$modIdx")
            AotCoreCompiler.verifyAll(compiled)

            val resolvedMemories = resolveImportedMemories(ir)
            val resolvedTables = resolveImportedTables(ir)
            val resolvedGlobals = resolveImportedGlobals(ir)
            val resolvedFuncs = resolveImportedFuncs(ir)

            val loader = CompiledModuleClassLoader(compiled)
            val instanceClass = loader.loadClass(compiled.instanceBinaryName)

            val ctorArgs = ArrayList<Any?>()
            for (m in resolvedMemories) ctorArgs += m.memory
            for (t in resolvedTables) ctorArgs += t.table
            for (g in resolvedGlobals) ctorArgs += g.importArgValue

            val importsInterface = compiled.importsInterfaceInternalName?.let { internal ->
                val ifaceClass = loader.loadClass(internal.replace('/', '.'))
                val impByMethod = ir.importedFunctions.indices.associate { idx ->
                    val imp = ir.importedFunctions[idx]
                    "imp_${imp.funcIndex}" to resolvedFuncs[idx]
                }
                Proxy.newProxyInstance(loader, arrayOf(ifaceClass)) { _, method, rawArgs ->
                    val resolved = impByMethod[method.name]
                        ?: error("Unexpected import bridge call: ${method.name}")
                    invokeImportBridge(method, rawArgs, resolved)
                }
            }
            if (importsInterface != null) {
                ctorArgs += importsInterface
            }

            val ctors = instanceClass.constructors
            val funcRefCtor = ctors.firstOrNull { ctor ->
                ctor.parameterCount == ctorArgs.size + 1 &&
                    ctor.parameterTypes.last() == Array<FuncRef>::class.java
            }
            val chosenCtor = if (funcRefCtor != null) {
                ctorArgs.add(Array(resolvedFuncs.size) { idx -> resolvedFuncs[idx].funcRef })
                funcRefCtor
            } else {
                ctors.single { it.parameterCount == ctorArgs.size }
            }

            val instance = try {
                chosenCtor.newInstance(*ctorArgs.toTypedArray())
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }

            val exportMap = buildExportsMap(ir, compiled, loader, instance, resolvedMemories, resolvedTables, resolvedGlobals, resolvedFuncs)
            return ModuleRuntime(exportMap)
        }

        private fun lookupImport(moduleName: String, fieldName: String): ExportedItem {
            val modExports = registry[moduleName]
                ?: throw LinkageException("unknown import: module '$moduleName'")
            return modExports[fieldName]
                ?: throw LinkageException("unknown import: '$moduleName'.'$fieldName'")
        }

        private fun resolveImportedMemories(ir: IrModule): List<ExportedItem.Memory> =
            ir.importedMemories.map { imp ->
                val item = lookupImport(imp.module, imp.name) as? ExportedItem.Memory
                    ?: throw LinkageException("incompatible import type for memory '${imp.module}'.'${imp.name}'")
                val currentPages = item.memory.pages()
                if (currentPages < imp.memoryType.initialPages) {
                    throw LinkageException("incompatible import type: memory min $currentPages < ${imp.memoryType.initialPages}")
                }
                val reqMax = imp.memoryType.maxPages
                if (reqMax != null) {
                    val actMax = item.maxPages
                        ?: throw LinkageException("incompatible import type: memory unbounded max vs required $reqMax")
                    if (actMax > reqMax) {
                        throw LinkageException("incompatible import type: memory max $actMax > $reqMax")
                    }
                }
                item
            }

        private fun resolveImportedTables(ir: IrModule): List<ExportedItem.Table> =
            ir.importedTables.map { imp ->
                val item = lookupImport(imp.module, imp.name) as? ExportedItem.Table
                    ?: throw LinkageException("incompatible import type for table '${imp.module}'.'${imp.name}'")
                if (item.elementType != imp.tableType.elementType) {
                    throw LinkageException("incompatible import type: table elem ${item.elementType} != ${imp.tableType.elementType}")
                }
                val currentSize = item.table.size().toLong()
                if (currentSize < imp.tableType.limits.min) {
                    throw LinkageException("incompatible import type: table min $currentSize < ${imp.tableType.limits.min}")
                }
                val reqMax = imp.tableType.limits.max
                if (reqMax != null) {
                    val actMax = item.maxSize
                        ?: throw LinkageException("incompatible import type: table unbounded max vs required $reqMax")
                    if (actMax > reqMax) {
                        throw LinkageException("incompatible import type: table max $actMax > $reqMax")
                    }
                }
                item
            }

        private fun resolveImportedGlobals(ir: IrModule): List<ExportedItem.Global> =
            ir.importedGlobals.map { imp ->
                val item = lookupImport(imp.module, imp.name) as? ExportedItem.Global
                    ?: throw LinkageException("incompatible import type for global '${imp.module}'.'${imp.name}'")
                if (item.valType != imp.globalType.valueType || item.mutability != imp.globalType.mutability) {
                    throw LinkageException(
                        "incompatible import type for global '${imp.module}'.'${imp.name}': " +
                            "${item.mutability} ${item.valType} vs ${imp.globalType.mutability} ${imp.globalType.valueType}",
                    )
                }
                item
            }

        private fun resolveImportedFuncs(ir: IrModule): List<ExportedItem.Func> =
            ir.importedFunctions.map { imp ->
                val expectedType = ir.types[imp.typeIndex]
                val item = lookupImport(imp.module, imp.name) as? ExportedItem.Func
                    ?: throw LinkageException("incompatible import type for func '${imp.module}'.'${imp.name}'")
                if (item.funcType != expectedType) {
                    throw LinkageException(
                        "incompatible import type for func '${imp.module}'.'${imp.name}': " +
                            "${item.funcType} vs $expectedType",
                    )
                }
                item
            }

        private fun invokeImportBridge(
            method: java.lang.reflect.Method,
            rawArgs: Array<Any?>?,
            target: ExportedItem.Func,
        ): Any? {
            val ft = target.funcType
            val params = ft.params
            val argsList = rawArgs ?: EMPTY_ARGS
            val hasSpill = method.parameterTypes.lastOrNull() == AotSpillFrame::class.java
            val wasmArgs: Array<Any?> = if (!hasSpill) {
                argsList
            } else {
                val directCount = method.parameterCount - 1
                val spill = argsList.last() as AotSpillFrame
                unpackSpilledArgs(params, argsList, directCount, spill)
            }
            val results = target.invoker(wasmArgs)
            return when (ft.results.size) {
                0 -> null
                1 -> results[0]
                else -> {
                    val tupleClass = method.returnType
                    val tupleCtor = tupleClass.constructors.single()
                    tupleCtor.newInstance(*results)
                }
            }
        }

        private fun buildExportsMap(
            ir: IrModule,
            compiled: CompiledAotModule,
            loader: CompiledModuleClassLoader,
            instance: Any,
            resolvedMemories: List<ExportedItem.Memory>,
            resolvedTables: List<ExportedItem.Table>,
            resolvedGlobals: List<ExportedItem.Global>,
            resolvedFuncs: List<ExportedItem.Func>,
        ): Map<String, ExportedItem> {
            val map = LinkedHashMap<String, ExportedItem>(compiled.exports.size)
            val memByIndex = HashMap<Int, ExportedItem.Memory>()
            resolvedMemories.forEachIndexed { idx, m -> memByIndex[idx] = m }
            val tabByIndex = HashMap<Int, ExportedItem.Table>()
            resolvedTables.forEachIndexed { idx, t -> tabByIndex[idx] = t }
            val globByIndex = HashMap<Int, ExportedItem.Global>()
            resolvedGlobals.forEachIndexed { idx, g -> globByIndex[idx] = g }

            for (exp in compiled.exports) {
                when (exp) {
                    is CompiledExport.Function -> {
                        val target = exp.target
                        val ownerClass = loader.loadClass(target.ownerInternalName.replace('/', '.'))
                        val directCount = target.funcType.params.size - target.overflowParamCount
                        val method = ownerClass.methods.single {
                            it.name == target.methodName && it.parameterCount == directCount + 1
                        }
                        val invoker: (Array<Any?>) -> Array<Any?> = { wasmArgs ->
                            if (target.overflowParamCount > 0) {
                                val spill = instance.javaClass.getField("spill").get(instance) as AotSpillFrame
                                packSpilledArgs(target.funcType.params, wasmArgs, directCount, spill)
                            }
                            val callArgs = arrayOfNulls<Any?>(directCount + 1)
                            for (i in 0 until directCount) callArgs[i] = wasmArgs[i]
                            callArgs[directCount] = instance
                            val rawRet = try {
                                method.invoke(null, *callArgs)
                            } catch (e: InvocationTargetException) {
                                throw e.targetException
                            }
                            extractResults(target.funcType, rawRet)
                        }
                        val funcRef: FuncRef = if (exp.index < resolvedFuncs.size) {
                            resolvedFuncs[exp.index].funcRef
                        } else {
                            FuncRef.core(instance as CoreModuleInstance, exp.index, target.funcType.compactSignature)
                        }
                        map[exp.name] = ExportedItem.Func(
                            funcType = target.funcType,
                            canonicalSig = target.funcType.compactSignature,
                            invoker = invoker,
                            funcRef = funcRef,
                        )
                    }
                    is CompiledExport.Memory -> {
                        val mem = instance.javaClass.getField(exp.fieldName).get(instance) as LinearMemory
                        val maxPages = ir.memoryTypeOrNull(exp.index)?.maxPages
                        map[exp.name] = memByIndex[exp.index] ?: ExportedItem.Memory(
                            memory = mem,
                            initialPages = exp.memoryType.initialPages,
                            maxPages = maxPages,
                        )
                    }
                    is CompiledExport.Table -> {
                        @Suppress("UNCHECKED_CAST")
                        val tab = instance.javaClass.getField(exp.fieldName).get(instance) as WasmTable<Any>
                        val tType = ir.tableTypeOrNull(exp.index) ?: exp.tableType
                        map[exp.name] = tabByIndex[exp.index] ?: ExportedItem.Table(
                            table = tab,
                            elementType = tType.elementType,
                            minSize = tType.limits.min,
                            maxSize = tType.limits.max,
                        )
                    }
                    is CompiledExport.Global -> {
                        val field = instance.javaClass.getField(exp.fieldName)
                        val rawVal = field.get(instance)
                        val existing = globByIndex[exp.index]
                        map[exp.name] = existing ?: if (exp.isBoxedCell) {
                            val cell = rawVal!!
                            val valueField = cell.javaClass.getField("value")
                            ExportedItem.Global(
                                valType = exp.globalType.valueType,
                                mutability = exp.globalType.mutability,
                                reader = { valueField.get(cell) },
                                importArgValue = cell,
                            )
                        } else {
                            ExportedItem.Global(
                                valType = exp.globalType.valueType,
                                mutability = exp.globalType.mutability,
                                reader = { field.get(instance) },
                                importArgValue = rawVal,
                            )
                        }
                    }
                }
            }
            return map
        }

        private fun packSpilledArgs(
            params: List<IrValType>,
            wasmArgs: Array<Any?>,
            directCount: Int,
            spill: AotSpillFrame,
        ) {
            var i32 = 0
            var i64 = 0
            var f32 = 0
            var f64 = 0
            var ref = 0
            for (i in directCount until params.size) {
                val v = wasmArgs[i]
                when (params[i]) {
                    IrValType.I32 -> spill.i32[i32++] = v as Int
                    IrValType.I64 -> spill.i64[i64++] = v as Long
                    IrValType.F32 -> spill.f32[f32++] = v as Float
                    IrValType.F64 -> spill.f64[f64++] = v as Double
                    IrValType.FUNCREF, IrValType.EXTERNREF -> spill.refs[ref++] = v
                }
            }
        }

        private fun unpackSpilledArgs(
            params: List<IrValType>,
            directArgs: Array<Any?>,
            directCount: Int,
            spill: AotSpillFrame,
        ): Array<Any?> {
            val out = arrayOfNulls<Any?>(params.size)
            for (i in 0 until directCount) out[i] = directArgs[i]
            var i32 = 0
            var i64 = 0
            var f32 = 0
            var f64 = 0
            var ref = 0
            for (i in directCount until params.size) {
                out[i] = when (params[i]) {
                    IrValType.I32 -> spill.i32[i32++]
                    IrValType.I64 -> spill.i64[i64++]
                    IrValType.F32 -> spill.f32[f32++]
                    IrValType.F64 -> spill.f64[f64++]
                    IrValType.FUNCREF, IrValType.EXTERNREF -> spill.refs[ref++]
                }
            }
            return out
        }

        private fun extractResults(ft: IrFuncType, rawRet: Any?): Array<Any?> = when (ft.results.size) {
            0 -> EMPTY_ARGS
            1 -> arrayOf(rawRet)
            else -> {
                val obj = rawRet!!
                val cls = obj.javaClass
                Array(ft.results.size) { i -> cls.getField("v$i").get(obj) }
            }
        }

        private fun parseArgValue(obj: JsonObject): Any? {
            val type = obj.getValue("type").jsonPrimitive.content
            val value = obj["value"]?.jsonPrimitive?.content
            return when (type) {
                "i32" -> parseI32(value!!)
                "i64" -> parseI64(value!!)
                "f32" -> Float.fromBits(parseI32(value!!))
                "f64" -> Double.fromBits(parseI64(value!!))
                "funcref" -> when (value) {
                    "null" -> null
                    else -> error("Unsupported funcref arg value: $obj")
                }
                "externref" -> when (value) {
                    null -> error("Missing externref arg value: $obj")
                    "null" -> null
                    else -> externRefs.getOrPut(value) { ExternHostRef(value) }
                }
                else -> error("Unsupported arg type '$type' in $obj")
            }
        }

        private fun matchesValue(expected: JsonObject, actual: Any?): Boolean {
            val type = expected.getValue("type").jsonPrimitive.content
            val value = expected["value"]?.jsonPrimitive?.content
            return when (type) {
                "i32" -> actual is Int && actual == parseI32(value!!)
                "i64" -> actual is Long && actual == parseI64(value!!)
                "f32" -> {
                    if (actual !is Float) return false
                    val bits = java.lang.Float.floatToRawIntBits(actual)
                    when (value) {
                        "nan:canonical" -> (bits and 0x7fff_ffff) == 0x7fc0_0000
                        "nan:arithmetic" -> (bits and 0x7fc0_0000) == 0x7fc0_0000
                        else -> bits == parseI32(value!!)
                    }
                }
                "f64" -> {
                    if (actual !is Double) return false
                    val bits = java.lang.Double.doubleToRawLongBits(actual)
                    when (value) {
                        "nan:canonical" -> (bits and 0x7fff_ffff_ffff_ffffL) == 0x7ff8_0000_0000_0000L
                        "nan:arithmetic" -> (bits and 0x7ff8_0000_0000_0000L) == 0x7ff8_0000_0000_0000L
                        else -> bits == parseI64(value!!)
                    }
                }
                "funcref" -> when (value) {
                    null -> actual is FuncRef
                    "null" -> actual == null
                    else -> false
                }
                "externref" -> when (value) {
                    null -> actual != null
                    "null" -> actual == null
                    else -> (actual as? ExternHostRef)?.id == value
                }
                else -> error("Unsupported expected type '$type' in $expected")
            }
        }

        private fun parseI32(s: String): Int =
            s.toIntOrNull() ?: s.toUInt().toInt()

        private fun parseI64(s: String): Long =
            s.toLongOrNull() ?: s.toULong().toLong()

        private fun formatActual(v: Any?): String = when (v) {
            is Float -> "f32(${v}, bits=0x${Integer.toHexString(java.lang.Float.floatToRawIntBits(v))})"
            is Double -> "f64(${v}, bits=0x${java.lang.Long.toHexString(java.lang.Double.doubleToRawLongBits(v))})"
            else -> v.toString()
        }

        private fun buildSpectestExports(): Map<String, ExportedItem> {
            fun hostFunc(params: List<IrValType>, name: String): ExportedItem.Func {
                val ft = IrFuncType(params, emptyList())
                val sig = ft.compactSignature
                val fRef = FuncRef.host(sig, HostTrampoline { null })
                return ExportedItem.Func(
                    funcType = ft,
                    canonicalSig = sig,
                    invoker = { EMPTY_ARGS },
                    funcRef = fRef,
                )
            }

            @Suppress("UNCHECKED_CAST")
            val table = WasmTable<Any>(10, 20)
            val memory = LinearMemory(1, 2)
            return mapOf(
                "print" to hostFunc(emptyList(), "print"),
                "print_i32" to hostFunc(listOf(IrValType.I32), "print_i32"),
                "print_i64" to hostFunc(listOf(IrValType.I64), "print_i64"),
                "print_f32" to hostFunc(listOf(IrValType.F32), "print_f32"),
                "print_f64" to hostFunc(listOf(IrValType.F64), "print_f64"),
                "print_i32_f32" to hostFunc(listOf(IrValType.I32, IrValType.F32), "print_i32_f32"),
                "print_f64_f64" to hostFunc(listOf(IrValType.F64, IrValType.F64), "print_f64_f64"),
                "global_i32" to ExportedItem.Global(IrValType.I32, IrMutability.CONST, { 666 }, 666),
                "global_i64" to ExportedItem.Global(IrValType.I64, IrMutability.CONST, { 666L }, 666L),
                "global_f32" to ExportedItem.Global(IrValType.F32, IrMutability.CONST, { 666.6f }, 666.6f),
                "global_f64" to ExportedItem.Global(IrValType.F64, IrMutability.CONST, { 666.6 }, 666.6),
                "table" to ExportedItem.Table(table, IrValType.FUNCREF, 10L, 20L),
                "memory" to ExportedItem.Memory(memory, 1, 2),
            )
        }
    }
}
