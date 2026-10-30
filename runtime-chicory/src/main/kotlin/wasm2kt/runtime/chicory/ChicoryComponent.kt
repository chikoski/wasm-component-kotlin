package wasm2kt.runtime.chicory

import com.dylibso.chicory.runtime.CompiledModule
import com.dylibso.chicory.runtime.ExportFunction
import com.dylibso.chicory.runtime.GlobalInstance
import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.ImportFunction
import com.dylibso.chicory.runtime.ImportGlobal
import com.dylibso.chicory.runtime.ImportMemory
import com.dylibso.chicory.runtime.ImportTable
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.Machine
import com.dylibso.chicory.runtime.Memory
import com.dylibso.chicory.runtime.TableInstance
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.WasmModule
import com.dylibso.chicory.wasm.types.FunctionImport
import com.dylibso.chicory.wasm.types.FunctionType
import com.dylibso.chicory.wasm.types.MutabilityType
import com.dylibso.chicory.wasm.types.Value
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import wasm2kt.runtime.ComponentBackend
import wasm2kt.runtime.ComponentBackendFactory
import wasm2kt.runtime.WitHostImportDispatcher
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Function

/**
 * A core module of a decomposed component: its parsed form plus, when it was compiled to JVM
 * bytecode at build time, the machine factory that runs that bytecode instead of the interpreter.
 */
class CoreModule(val module: WasmModule, val machineFactory: Function<Instance, Machine>? = null) {
    companion object {
        /** Runs the module with Chicory's interpreter. */
        fun interpreted(bytes: ByteArray) = CoreModule(Parser.parse(bytes))

        /** Runs a module produced by Chicory's build-time compiler. */
        fun compiled(module: CompiledModule) = CoreModule(module.wasmModule(), module.machineFactory())
    }
}

/**
 * A WebAssembly component prepared for Chicory: its instantiation [plan] and core [modules]
 * (indexed like the plan's modules). Each [instantiate] call creates an independent instance.
 */
class ChicoryComponent(
    val plan: ComponentPlan,
    private val modules: Map<Int, CoreModule>,
) : ComponentBackendFactory {
    init {
        for (m in plan.modules) require(m.index in modules) { "Missing core module ${m.index} (${m.file})" }
    }

    override fun instantiate(imports: WitHostImportDispatcher?): ComponentBackend =
        ChicoryComponentInstance(plan, modules, imports)

    companion object {
        /** Loads `plan.json` and `core<N>.wasm` written by `wasm2kt-decompose`, using the interpreter. */
        fun fromDirectory(dir: Path): ChicoryComponent {
            val plan = ComponentPlan.parse(Files.readString(dir.resolve("plan.json")))
            return ChicoryComponent(plan, plan.modules.associate { it.index to CoreModule.interpreted(Files.readAllBytes(dir.resolve(it.file))) })
        }

        /** Like [fromDirectory] but reads the files through [open], e.g. Android assets. */
        fun fromResources(open: (String) -> ByteArray): ChicoryComponent {
            val plan = ComponentPlan.parse(String(open("plan.json"), Charsets.UTF_8))
            return ChicoryComponent(plan, plan.modules.associate { it.index to CoreModule.interpreted(open(it.file)) })
        }
    }
}

/**
 * One instantiated component. Mirrors the Wasmtime bridge's host-facing behaviour: resources
 * returned to the host become integer handles in a single host handle space, which the host
 * passes back for `borrow`/`own` parameters and releases through [dropResource].
 */
internal class ChicoryComponentInstance(
    private val plan: ComponentPlan,
    private val modules: Map<Int, CoreModule>,
    private val imports: WitHostImportDispatcher?,
) : ComponentBackend {
    private val instances = HashMap<Int, Instance>()
    private val memories = HashMap<Int, Memory>()
    private val reallocs = HashMap<Int, ExportFunction>()
    private val postReturns = HashMap<Int, ExportFunction>()
    private val instanceFlags = HashMap<Int, GlobalInstance>()
    private val resourceDtors = HashMap<Int, ExportFunction>()

    /** Per resource table: guest handle -> representation. Handle 0 is reserved as invalid. */
    private val guestTables = HashMap<Int, ResourceTable>()

    /** Host handle -> (resource table, guest handle). */
    private val hostHandles = HashMap<Int, Pair<Int, Int>>()
    private var nextHostHandle = 1
    private var closed = false

    init {
        for (init in plan.initializers) {
            when (init) {
                is Initializer.Instantiate -> instantiateModule(init)
                is Initializer.ExtractMemory -> memories[init.index] = instance(init.export.instance).exports().memory(init.export.name)
                is Initializer.ExtractRealloc -> reallocs[init.index] = coreFunction(init.def)
                is Initializer.ExtractPostReturn -> postReturns[init.index] = coreFunction(init.def)
                is Initializer.ExtractTable -> Unit // tables are only shared between core instances
                is Initializer.Resource -> init.dtor?.let { d ->
                    // The dtor's instance may not exist yet; resolve lazily on first drop.
                    resourceDtors[init.resource] = ExportFunction { args -> coreFunction(d).apply(*args) }
                }
            }
        }
    }

    override fun invoke(functionName: String, args: JsonArray): JsonElement {
        check(!closed) { "Component instance is already closed" }
        val export = resolveExport(functionName)
        val type = export.type
        require(args.size == type.params.size) {
            "Function '$functionName' expected ${type.params.size} arguments, got ${args.size}"
        }
        val opts = plan.options.getValue(export.options)
        val cx = Context(opts, Direction.HOST_TO_GUEST)
        val lowerer = Lowerer(cx)

        val paramTypes = type.params.map { it.second }
        val flatParams = CanonicalAbi.flattenAll(paramTypes)
        val coreArgs = mutableListOf<Long>()
        if (flatParams.size > CanonicalAbi.MAX_FLAT_PARAMS) {
            coreArgs += lowerer.storeTuple(args, paramTypes).toLong()
        } else {
            args.forEachIndexed { i, a -> lowerer.lowerFlat(a, paramTypes[i], coreArgs) }
        }

        val coreResults = coreFunction(export.func).apply(*coreArgs.toLongArray()) ?: LongArray(0)

        val lifter = Lifter(cx)
        val flatResults = CanonicalAbi.flattenAll(type.results)
        val results: List<JsonElement> = if (flatResults.size > CanonicalAbi.MAX_FLAT_RESULTS) {
            lifter.loadTuple(coreResults[0].toInt(), type.results)
        } else {
            val reader = Lifter.FlatReader(coreResults)
            type.results.map { lifter.liftFlat(it, reader) }
        }
        opts.postReturn?.let { postReturns.getValue(it).apply(*coreResults) }

        return when (results.size) {
            0 -> JsonNull
            1 -> results[0]
            else -> JsonArray(results)
        }
    }

    override fun dropResource(handle: Int) {
        check(!closed) { "Component instance is already closed" }
        val (table, guestHandle) = hostHandles.remove(handle)
            ?: throw IllegalStateException("Resource handle $handle not found or already consumed")
        dropGuestHandle(table, guestHandle)
    }

    override fun close() {
        closed = true
        hostHandles.clear()
        guestTables.clear()
    }

    // ---------------------------------------------------------------- instantiation

    private fun instantiateModule(init: Initializer.Instantiate) {
        val core = modules.getValue(init.module)
        val builder = ImportValues.builder()
        for (arg in init.args) {
            val kind = plan.modules.first { it.index == init.module }.imports
                .first { it.module == arg.module && it.name == arg.name }.kind
            when (val def = arg.def) {
                is CoreDef.Export -> {
                    val src = instance(def.ref.instance)
                    when (kind) {
                        "func" -> builder.addFunction(ImportFunction.exportAsImport(arg.module, arg.name, src, def.ref.name))
                        "memory" -> builder.addMemory(ImportMemory(arg.module, arg.name, src.exports().memory(def.ref.name)))
                        "table" -> builder.addTable(ImportTable(arg.module, arg.name, src.exports().table(def.ref.name)))
                        "global" -> builder.addGlobal(ImportGlobal(arg.module, arg.name, src.exports().global(def.ref.name)))
                        else -> throw UnsupportedOperationException("Cannot import a core '$kind'")
                    }
                }
                is CoreDef.Trampoline -> builder.addFunction(
                    HostFunction(arg.module, arg.name, importFunctionType(core.module, arg.module, arg.name), trampoline(def.index)),
                )
                is CoreDef.InstanceFlags -> builder.addGlobal(
                    ImportGlobal(arg.module, arg.name, instanceFlags.getOrPut(def.index) {
                        // may_leave | may_enter, as Wasmtime initializes them.
                        GlobalInstance(Value.i32(3), MutabilityType.Var)
                    }),
                )
            }
        }
        val instance = Instance.builder(core.module)
            .withImportValues(builder.build())
            .apply { core.machineFactory?.let { withMachineFactory(it) } }
            .build()
        instances[init.instance] = instance
    }

    private fun importFunctionType(module: WasmModule, importModule: String, name: String): FunctionType {
        val section = module.importSection()
        for (i in 0 until section.importCount()) {
            val imp = section.getImport(i)
            if (imp is FunctionImport && imp.module() == importModule && imp.name() == name) {
                return module.typeSection().getType(imp.typeIndex())
            }
        }
        error("Core module has no function import $importModule::$name")
    }

    private fun instance(index: Int): Instance =
        instances[index] ?: error("Core instance $index referenced before it was instantiated")

    private fun coreFunction(def: CoreDef): ExportFunction = when (def) {
        is CoreDef.Export -> instance(def.ref.instance).export(def.ref.name)
        is CoreDef.Trampoline -> {
            val handle = trampoline(def.index)
            ExportFunction { args -> handle.apply(null, *args) }
        }
        is CoreDef.InstanceFlags -> error("Instance flags are not callable")
    }

    // ---------------------------------------------------------------- trampolines

    private fun trampoline(index: Int): com.dylibso.chicory.runtime.WasmFunctionHandle =
        when (val t = plan.trampolines[index] ?: error("Unknown trampoline $index")) {
            is Trampoline.LowerImport -> com.dylibso.chicory.runtime.WasmFunctionHandle { _, args -> callHost(t, args) }
            is Trampoline.ResourceNew -> com.dylibso.chicory.runtime.WasmFunctionHandle { _, args ->
                longArrayOf(table(t.table).insert(args[0].toInt()).toLong())
            }
            is Trampoline.ResourceRep -> com.dylibso.chicory.runtime.WasmFunctionHandle { _, args ->
                longArrayOf(table(t.table).rep(args[0].toInt()).toLong())
            }
            is Trampoline.ResourceDrop -> com.dylibso.chicory.runtime.WasmFunctionHandle { _, args ->
                dropGuestHandle(t.table, args[0].toInt())
                null
            }
            is Trampoline.Unsupported -> com.dylibso.chicory.runtime.WasmFunctionHandle { _, _ ->
                throw UnsupportedOperationException("Component intrinsic not supported by the Chicory backend: ${t.detail}")
            }
        }

    /** A guest calling a host import: lift the guest's arguments, dispatch, lower the result. */
    private fun callHost(t: Trampoline.LowerImport, coreArgs: LongArray): LongArray? {
        val import = plan.imports.getValue(t.import)
        val opts = plan.options.getValue(t.options)
        val cx = Context(opts, Direction.GUEST_TO_HOST)
        val lifter = Lifter(cx)
        val paramTypes = t.type.params.map { it.second }
        val flatParams = CanonicalAbi.flattenAll(paramTypes)
        val flatResults = CanonicalAbi.flattenAll(t.type.results)

        val args: List<JsonElement> = if (flatParams.size > CanonicalAbi.MAX_FLAT_PARAMS) {
            lifter.loadTuple(coreArgs[0].toInt(), paramTypes)
        } else {
            val reader = Lifter.FlatReader(coreArgs)
            paramTypes.map { lifter.liftFlat(it, reader) }
        }

        val dispatcher = imports ?: throw IllegalStateException("Component imports '${import.dispatchKey}' but no host imports were provided")
        val response = dispatcher.dispatchHostImport(import.dispatchKey, JsonArray(args))
        val results: List<JsonElement> = when (t.type.results.size) {
            0 -> emptyList()
            1 -> listOf(response)
            else -> response.jsonArray
        }

        val lowerer = Lowerer(cx)
        return if (flatResults.size > CanonicalAbi.MAX_FLAT_RESULTS) {
            // The caller passed a return pointer as the last core argument.
            val retPtr = coreArgs[coreArgs.size - 1].toInt()
            lowerer.store(JsonArray(results), WitType.Tuple(t.type.results), retPtr)
            null
        } else if (results.isEmpty()) {
            null
        } else {
            val out = mutableListOf<Long>()
            lowerer.lowerFlat(results[0], t.type.results[0], out)
            out.toLongArray()
        }
    }

    // ---------------------------------------------------------------- resources

    private fun table(index: Int): ResourceTable = guestTables.getOrPut(index) { ResourceTable() }

    private fun dropGuestHandle(tableIndex: Int, guestHandle: Int) {
        val rep = table(tableIndex).remove(guestHandle)
        val info = plan.resourceTables[tableIndex]
        val dtor = info?.resource?.let { resourceDtors[it] }
        if (info != null && !info.imported && dtor != null) dtor.apply(rep.toLong())
    }

    private fun isDefinedHere(tableIndex: Int): Boolean = plan.resourceTables[tableIndex]?.imported == false

    private enum class Direction { HOST_TO_GUEST, GUEST_TO_HOST }

    private inner class Context(private val opts: CanonOptions, private val direction: Direction) : AbiContext {
        override val memory: Memory
            get() = opts.memory?.let { memories.getValue(it) }
                ?: throw IllegalStateException("This function needs linear memory but its canonical options declare none")
        override val encoding: StringEncoding get() = opts.stringEncoding

        override fun realloc(align: Int, size: Int): Int {
            val f = opts.realloc?.let { reallocs.getValue(it) }
                ?: throw IllegalStateException("This function needs cabi_realloc but its canonical options declare none")
            return f.apply(0, 0, align.toLong(), size.toLong())[0].toInt()
        }

        override fun lowerOwn(table: Int, value: JsonElement): Int {
            requireDefinedResource(table)
            val (t, guestHandle) = hostHandles.remove(handleOf(value))
                ?: throw IllegalStateException("Resource handle ${handleOf(value)} not found or already consumed")
            check(t == table) { "Resource handle ${handleOf(value)} belongs to a different resource type" }
            return guestHandle
        }

        override fun lowerBorrow(table: Int, value: JsonElement): Int {
            requireDefinedResource(table)
            val (t, guestHandle) = hostHandles[handleOf(value)]
                ?: throw IllegalStateException("Resource handle ${handleOf(value)} not found")
            check(t == table) { "Resource handle ${handleOf(value)} belongs to a different resource type" }
            // Borrowing into the defining instance passes the representation directly.
            return table(table).rep(guestHandle)
        }

        override fun liftOwn(table: Int, handle: Int): JsonElement {
            requireDefinedResource(table)
            check(direction == Direction.HOST_TO_GUEST) { "Host imports receiving own<T> are not supported yet" }
            val hostHandle = nextHostHandle++
            hostHandles[hostHandle] = table to handle
            return kotlinx.serialization.json.JsonPrimitive(hostHandle)
        }

        override fun liftBorrow(table: Int, handle: Int): JsonElement =
            throw UnsupportedOperationException("Host imports receiving borrow<T> are not supported by the Chicory backend yet")

        private fun requireDefinedResource(table: Int) {
            if (!isDefinedHere(table)) {
                throw UnsupportedOperationException("Imported (host-defined) resources are not supported by the Chicory backend yet")
            }
        }

        private fun handleOf(value: JsonElement): Int = when (value) {
            is JsonObject -> value.getValue("handle").jsonPrimitive.int
            else -> value.jsonPrimitive.int
        }
    }

    // ---------------------------------------------------------------- export lookup

    private fun resolveExport(spec: String): PlanExport {
        val hash = spec.indexOf('#')
        if (hash >= 0) {
            val iface = spec.substring(0, hash)
            val fn = spec.substring(hash + 1)
            plan.exports.firstOrNull { e ->
                e.name == fn && e.instance != null && (e.instance == iface || shortInterfaceName(e.instance) == iface)
            }?.let { return it }
            throw IllegalArgumentException("Exported function '$fn' not found in interface '$iface'")
        }
        return plan.exports.firstOrNull { it.instance == null && it.name == spec }
            ?: plan.exports.firstOrNull { it.name == spec }
            ?: throw IllegalArgumentException("Exported function '$spec' not found")
    }
}

/** A component-level resource table: guest handles (starting at 1) mapped to representations. */
internal class ResourceTable {
    private val reps = HashMap<Int, Int>()
    private val free = ArrayDeque<Int>()
    private var next = 1

    fun insert(rep: Int): Int {
        val h = free.removeFirstOrNull() ?: next++
        reps[h] = rep
        return h
    }

    fun rep(handle: Int): Int = reps[handle] ?: throw IllegalStateException("Unknown resource handle $handle")

    fun remove(handle: Int): Int {
        val rep = reps.remove(handle) ?: throw IllegalStateException("Unknown resource handle $handle")
        free.addLast(handle)
        return rep
    }
}
