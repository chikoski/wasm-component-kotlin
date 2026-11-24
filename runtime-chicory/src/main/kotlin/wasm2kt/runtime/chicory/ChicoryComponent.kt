@file:OptIn(InternalWasm2KtApi::class)

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
import com.dylibso.chicory.runtime.InterpreterMachine
import com.dylibso.chicory.runtime.Machine
import com.dylibso.chicory.runtime.Memory
import com.dylibso.chicory.runtime.TableInstance
import com.dylibso.chicory.wasm.ChicoryException
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.WasmModule
import com.dylibso.chicory.wasm.types.CodeSection
import com.dylibso.chicory.wasm.types.FunctionBody
import com.dylibso.chicory.wasm.types.FunctionImport
import com.dylibso.chicory.wasm.types.FunctionSection
import com.dylibso.chicory.wasm.types.FunctionType
import com.dylibso.chicory.wasm.types.MutabilityType
import com.dylibso.chicory.wasm.types.Table
import com.dylibso.chicory.wasm.types.TableLimits
import com.dylibso.chicory.wasm.types.TypeSection
import com.dylibso.chicory.wasm.types.ValType
import com.dylibso.chicory.wasm.types.Value
import wasm2kt.runtime.ComponentBackend
import wasm2kt.runtime.ComponentBackendFactory
import wasm2kt.runtime.ComponentExitException
import wasm2kt.runtime.ComponentTrapException
import kotlinx.coroutines.flow.Flow
import wasm2kt.runtime.AsyncHostFunction as WitAsyncHostFunction
import wasm2kt.runtime.HostFunction as WitHostFunction
import wasm2kt.runtime.WitStreamChunk
import wasm2kt.runtime.WitReader
import wasm2kt.runtime.WitWriter
import wasm2kt.runtime.WitHostImportDispatcher
import wasm2kt.runtime.canonical.AbiContext
import wasm2kt.runtime.canonical.CanonicalAbi
import wasm2kt.runtime.canonical.CanonicalMemory
import wasm2kt.runtime.canonical.ComponentGuard
import wasm2kt.runtime.canonical.HostValueException
import wasm2kt.runtime.canonical.InternalWasm2KtApi
import wasm2kt.runtime.canonical.Lifter
import wasm2kt.runtime.canonical.Lowerer
import wasm2kt.runtime.canonical.ResourceHandle
import wasm2kt.runtime.canonical.ResourceTable
import wasm2kt.runtime.canonical.StringEncoding
import wasm2kt.runtime.canonical.Transcoder
import wasm2kt.runtime.canonical.UnsupportedWitTypeException
import wasm2kt.runtime.canonical.shortInterfaceName
import wasm2kt.runtime.canonical.trap
import wasm2kt.runtime.canonical.unversionedInterfaceName
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap
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
            val plan = ComponentPlan.parse(String(Files.readAllBytes(dir.resolve("plan.json")), Charsets.UTF_8))
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
 *
 * Instance state follows the Canonical ABI: a call may not start while another one is in progress
 * on this instance (`may_enter`; e.g. from inside a host import), the guest may not call out while
 * running `cabi_realloc` or post-return (`may_leave`), and a handle the host lent to a call as
 * `borrow` cannot be moved or dropped until that call returns. Any trap poisons the instance.
 */
internal class ChicoryComponentInstance(
    internal val plan: ComponentPlan,
    private val modules: Map<Int, CoreModule>,
    private val imports: WitHostImportDispatcher?,
) : ComponentBackend {
    private val instances = HashMap<Int, Instance>()
    private val adaptedInstances = IdentityHashMap<WasmModule, IdentityHashMap<Instance, Instance>>()
    private val canonicalInstances = IdentityHashMap<Instance, Instance>()
    private val hasSharedTables: Boolean = plan.modules.any { m -> m.imports.any { it.kind == "table" } }
    internal val memories = HashMap<Int, Memory>()
    internal val canonicalMemories = HashMap<Int, CanonicalMemory>()
    internal val reallocs = HashMap<Int, ExportFunction>()
    internal val postReturns = HashMap<Int, ExportFunction>()
    internal val callbacks = HashMap<Int, ExportFunction>()
    internal val tables = HashMap<Int, TableInstance>()
    private val instanceFlags = HashMap<Int, GlobalInstance>()
    private val resourceDtors = HashMap<Int, ExportFunction>()

    /**
     * The current task's "may block" flag, which fused adapters read. Wasmtime's adapters trap when a
     * task that may not block calls an `async` function synchronously; here that is checked where a
     * task actually blocks ([AsyncAbi.checkBlocking]), so it stays 1 for components that use the
     * async ABI. Synchronous components never block, so it stays 0 (ADR-0018).
     */
    private val taskMayBlock = GlobalInstance(Value.i32(if (plan.usesAsync) 1 else 0), MutabilityType.Var)

    /** The async ABI (#16): the scheduler, and every component instance's handle table. */
    internal val async = AsyncAbi(this)

    /**
     * A resource handle owned by the host. As in the spec's `lift_own`, the component's own handle
     * was removed from its table when the resource moved to the host, so only the [rep] is kept;
     * passing it back as `own` creates a fresh handle in the component ([Context.lowerOwn]).
     * [lends] counts calls currently borrowing it.
     */
    private class HostHandle(val table: Int, val rep: Int) {
        var lends = 0
    }

    private val hostHandles = HashMap<Int, HostHandle>()
    private var nextHostHandle = 1

    internal val guard = ComponentGuard()

    init {
        bind {
            for (init in plan.initializers) {
                when (init) {
                    is Initializer.Instantiate -> instantiateModule(init)
                    is Initializer.ExtractMemory -> {
                        val memory = instance(init.export.instance).exports().memory(init.export.name)
                        memories[init.index] = memory
                        canonicalMemories[init.index] = ChicoryMemory(memory)
                    }
                    is Initializer.ExtractRealloc -> reallocs[init.index] = coreFunction(init.def)
                    is Initializer.ExtractPostReturn -> postReturns[init.index] = coreFunction(init.def)
                    is Initializer.ExtractTable -> tables[init.index] = instance(init.export.instance).exports().table(init.export.name)
                    is Initializer.ExtractCallback -> callbacks[init.index] = coreFunction(init.def)
                    is Initializer.Resource -> init.dtor?.let { d ->
                        // The dtor's instance may not exist yet; resolve lazily on first drop.
                        resourceDtors[init.resource] = ExportFunction { args -> coreFunction(d).apply(*args) }
                    }
                }
            }
        }
    }

    private class SyncExport(
        val opts: CanonOptions,
        val func: ExportFunction,
        val memory: CanonicalMemory?,
        val realloc: ExportFunction?,
        val postReturn: ExportFunction?,
    )

    private class ResolvedExport(
        val export: PlanExport,
        val sync: SyncExport?,
    )

    /** Exports resolved so far; [invoke] takes their index. */
    private val resolved = ArrayList<ResolvedExport>()
    private val resolvedIndices = HashMap<String, Int>()
    private val results = WitWriter()

    @Synchronized
    override fun resolve(functionName: String): Int {
        checkUsable()
        resolvedIndices[functionName]?.let { return it }
        val export = resolveExport(functionName)
        var index = -1
        for (i in resolved.indices) {
            if (resolved[i].export === export) {
                index = i
                break
            }
        }
        if (index < 0) {
            index = resolved.size
            val sync = if (plan.usesAsync) {
                null
            } else {
                val opts = plan.options.getValue(export.options)
                SyncExport(
                    opts = opts,
                    func = coreFunction(export.func),
                    memory = opts.memory?.let { canonicalMemories[it] },
                    realloc = opts.realloc?.let { reallocs[it] },
                    postReturn = opts.postReturn?.let { postReturns.getValue(it) },
                )
            }
            resolved += ResolvedExport(export = export, sync = sync)
        }
        resolvedIndices[functionName] = index
        return index
    }

    @Synchronized
    private fun resolvedExport(function: Int): ResolvedExport =
        resolved.getOrNull(function) ?: throw IllegalArgumentException("Unknown function index $function")

    /**
     * For a component that uses the async ABI, the call suspends until the component returns, and
     * several can be in progress at once (ADR-0019); otherwise it is [invoke].
     */
    override suspend fun invokeSuspending(function: Int, args: WitReader): WitReader {
        if (!plan.usesAsync) return invoke(function, args)
        checkUsable()
        val export = resolvedExport(function).export
        return async.invokeSuspending(export, export.qualifiedName, args)
    }

    override fun invoke(function: Int, args: WitReader): WitReader {
        checkUsable()
        val entry = resolvedExport(function)
        val export = entry.export
        val functionName = export.qualifiedName
        // Calls into a component that uses the async ABI are tasks on its scheduler (ADR-0019).
        if (plan.usesAsync) return async.invoke(export, functionName, args)
        if (guard.entered) poison("Cannot reenter component instance: '$functionName' was called while another call into it is in progress")
        val sync = checkNotNull(entry.sync)
        val type = export.type
        val opts = sync.opts
        val cx = Context(opts, Direction.HOST_TO_GUEST, cachedMemory = sync.memory, cachedRealloc = sync.realloc)
        val syncDepth = async.syncCallDepth()
        guard.entered = true
        try {
            // Lowering the host's arguments: errors in the values are the host's (not traps), but
            // cabi_realloc runs guest code, so a misbehaving realloc does trap (see Context.realloc).
            val lowerer = Lowerer(cx, args)
            val paramTypes = type.paramTypes
            val flatParamCount = mapChicoryUnsupported { type.flatParamCount }
            val coreArgs = try {
                mapChicoryUnsupported {
                    if (flatParamCount > CanonicalAbi.MAX_FLAT_PARAMS) {
                        longArrayOf(lowerer.storeTuple(type.paramTuple).toLong())
                    } else if (flatParamCount == 0) {
                        CanonicalAbi.EMPTY_LONG_ARRAY
                    } else {
                        val out = LongArray(flatParamCount)
                        var pos = 0
                        for (pt in paramTypes) pos = lowerer.lowerFlat(pt, out, pos)
                        out
                    }
                }.also {
                    if (args.remaining != 0) throw IllegalArgumentException("${args.remaining} unread bytes after the arguments of '$functionName'")
                }
            } catch (e: Throwable) {
                // A bad host argument must not leave handles behind in the component's tables.
                cx.rollbackLowering()
                throw e
            }
            cx.commitMoves()

            val coreResults = guest { sync.func.apply(*coreArgs) ?: CanonicalAbi.EMPTY_LONG_ARRAY }
            cx.checkBorrowsDropped()

            results.reset()
            if (type.results.isNotEmpty()) {
                guest {
                    val lifter = Lifter(cx, results)
                    mapChicoryUnsupported {
                        if (type.flatResultCount > CanonicalAbi.MAX_FLAT_RESULTS) {
                            lifter.loadTuple(coreResults[0].toInt(), type.resultTuple)
                        } else {
                            val reader = Lifter.FlatReader(coreResults)
                            for (rt in type.results) lifter.liftFlat(rt, reader)
                        }
                    }
                }
            }
            sync.postReturn?.let { pr -> guest { withoutLeaving { pr.apply(*coreResults) } } }
            return if (results.size > 64 * 1024) {
                val bytes = results.toByteArray()
                results.reset()
                WitReader(bytes)
            } else {
                results.toReader()
            }
        } finally {
            async.cleanupSyncCalls(syncDepth)
            cx.releaseLends()
            guard.entered = false
        }
    }

    override fun dropResource(handle: Int) {
        if (plan.usesAsync) return async.onSession { dropHostHandle(handle) }
        dropHostHandle(handle)
    }

    private fun dropHostHandle(handle: Int) {
        checkUsable()
        val h = hostHandles[handle] ?: throw IllegalStateException("Resource handle $handle not found or already consumed")
        check(h.lends == 0) { "Resource handle $handle is borrowed by a call in progress and cannot be dropped" }
        if (guard.entered && hasDestructor(h.table)) {
            poison("Cannot reenter component instance: dropping resource handle $handle runs its destructor during another call")
        }
        hostHandles.remove(handle)
        guest { destroy(h.table, h.rep) }
    }

    // Streams and futures run on the scheduler of the async ABI (ADR-0019).

    override fun readStream(handle: Int, maxCount: Int): WitReader? = usable { async.hostRead(handle, maxCount, stream = true) }

    override suspend fun readStreamSuspending(handle: Int, maxCount: Int): WitReader? {
        checkUsable()
        return async.hostReadSuspending(handle, maxCount, stream = true)
    }

    override fun readFuture(handle: Int): WitReader? = usable { async.hostRead(handle, 1, stream = false) }

    override suspend fun readFutureSuspending(handle: Int): WitReader? {
        checkUsable()
        return async.hostReadSuspending(handle, 1, stream = false)
    }

    override fun dropReadable(handle: Int) = usable { async.hostDropReadable(handle) }

    override fun newStream(values: ByteArray, count: Int): Int = usable { async.onSession { async.newHostStream(values, count) } }

    override fun newStream(chunks: Flow<WitStreamChunk>): Int = usable { async.onSession { async.newHostStream(chunks) } }

    override fun newFuture(value: ByteArray): Int = usable { async.onSession { async.newHostFuture(value) } }

    override fun newFuture(value: suspend () -> ByteArray): Int = usable { async.onSession { async.newHostFuture(value) } }

    private inline fun <T> usable(block: () -> T): T {
        checkUsable()
        return block()
    }

    override fun close() {
        guard.closed = true
        hostHandles.clear()
        async.close()
    }

    // ---------------------------------------------------------------- instance state

    internal fun checkUsable() = guard.checkUsable()

    /** Traps and poisons the instance. */
    internal fun poison(message: String): Nothing = guard.poison(message) { async.abandon() }

    /**
     * Runs guest code (or validates guest-produced values). Anything thrown poisons the instance,
     * since the guest may be left mid-execution; wasm traps surface as [ComponentTrapException].
     */
    internal inline fun <T> guest(block: () -> T): T =
        guard.guest(mapExternalTrap = ::mapChicoryTrap, onPoison = { async.abandon() }, block = block)

    internal fun asTrap(e: Throwable): Throwable = guard.mapTrap(e, ::mapChicoryTrap)

    internal fun mapChicoryTrap(e: Throwable): Throwable? =
        if (e is ChicoryException) ComponentTrapException("wasm trap: ${e.message}", e) else null

    internal inline fun <T> withoutLeaving(block: () -> T): T = guard.withoutLeaving(block)

    internal fun checkMayLeave(what: String) = guard.checkMayLeave(what) { async.abandon() }

    internal inline fun <T> mapChicoryUnsupported(block: () -> T): T = try {
        block()
    } catch (e: UnsupportedWitTypeException) {
        throw UnsupportedOperationException("WIT type is not supported by the Chicory backend yet: ${e.detail}", e)
    }

    // ---------------------------------------------------------------- instantiation

    private fun instantiateModule(init: Initializer.Instantiate) {
        val core = modules.getValue(init.module)
        val builder = ImportValues.builder()
        val moduleTables = ArrayList<SharedTableInstance>()
        for (arg in init.args) {
            val kind = plan.modules.first { it.index == init.module }.imports
                .first { it.module == arg.module && it.name == arg.name }.kind
            when (val def = arg.def) {
                is CoreDef.Export -> {
                    val src = instance(def.ref.instance)
                    when (kind) {
                        "func" -> builder.addFunction(ImportFunction.exportAsImport(arg.module, arg.name, src, def.ref.name))
                        "memory" -> builder.addMemory(ImportMemory(arg.module, arg.name, src.exports().memory(def.ref.name)))
                        "table" -> {
                            val srcTable = src.exports().table(def.ref.name)
                            if (hasSharedTables) {
                                val view = sharedTableView(srcTable, core.module)
                                moduleTables.add(view)
                                builder.addTable(ImportTable(arg.module, arg.name, view))
                            } else {
                                builder.addTable(ImportTable(arg.module, arg.name, srcTable))
                            }
                        }
                        "global" -> builder.addGlobal(ImportGlobal(arg.module, arg.name, src.exports().global(def.ref.name)))
                        else -> throw UnsupportedOperationException("Cannot import a core '$kind'")
                    }
                }
                is CoreDef.Trampoline -> builder.addFunction(
                    HostFunction(arg.module, arg.name, importFunctionType(core.module, arg.module, arg.name), trampoline(def.index)),
                )
                is CoreDef.TaskMayBlock -> builder.addGlobal(ImportGlobal(arg.module, arg.name, taskMayBlock))
                is CoreDef.Intrinsic -> builder.addFunction(
                    HostFunction(arg.module, arg.name, importFunctionType(core.module, arg.module, arg.name), async.intrinsic(def.name)),
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
            .apply {
                if (hasSharedTables) {
                    withTableFactory { table, initVal ->
                        SharedTableInstance(null, initVal, core.module, table, ::adaptInstanceForCaller)
                            .also { moduleTables.add(it) }
                    }
                }
                if (plan.usesAsync) {
                    // Guest threads of one instance interleave (ADR-0018), and Chicory's machines keep
                    // their operand and call stacks per machine: give every JVM thread its own.
                    withMachineFactory(threadConfined(core.machineFactory ?: Function { InterpreterMachine(it) }))
                } else {
                    core.machineFactory?.let { withMachineFactory(it) }
                }
            }
            .build()
        for (t in moduleTables) {
            t.ownerInstance = instance
        }
        instances[init.instance] = instance
    }

    private fun sharedTableView(source: TableInstance, ownerModule: WasmModule): SharedTableInstance {
        val backing = (source as? SharedTableInstance)?.backing ?: source
        val emptyTable = Table(backing.elementType(), TableLimits(0, 0))
        return SharedTableInstance(backing, -1, ownerModule, emptyTable, ::adaptInstanceForCaller)
    }

    /**
     * Builds a lightweight [Instance] view over [raw] whose [TypeSection] and function type
     * indices live in [ownerModule]'s index space. Chicory validates `call_indirect` signatures
     * against `targetInst.module().typeSection()` using the caller's type index (#70).
     */
    private fun adaptInstanceForCaller(ownerModule: WasmModule, raw: Instance): Instance =
        synchronized(adaptedInstances) {
            val canonicalRaw = canonicalInstances[raw] ?: raw
            val byTarget = adaptedInstances.getOrPut(ownerModule) { IdentityHashMap() }
            byTarget.getOrPut(canonicalRaw) {
                val ownerTypes = ownerModule.typeSection()
                val rawTypes = canonicalRaw.module().typeSection()
                val typeBuilder = TypeSection.builder()
                for (i in 0 until ownerTypes.typeCount()) {
                    typeBuilder.addRecType(ownerTypes.getRecType(i))
                }
                // Chicory validates call_indirect by structural canonical equivalence against the target
                // module's type section; a function type with maxParams + 1 parameters cannot match any
                // signature in ownerModule, so unmapped functions still trap with a type mismatch.
                val maxParams = ownerTypes.types().maxOfOrNull { it.params().size } ?: 0
                val unmatchedType = FunctionType.of(List(maxParams + 1) { ValType.I32 }, emptyList())
                val unmatchedTypeIdx = ownerTypes.subTypeCount()
                typeBuilder.addFunctionType(unmatchedType)

                val ownerSubTypeCount = ownerTypes.subTypeCount()
                val fnSection = FunctionSection.builder()
                val codeSection = CodeSection.builder()
                val emptyBody = FunctionBody(emptyList(), emptyList())
                for (funcIdx in 0 until canonicalRaw.functionCount()) {
                    val rawTypeIdx = canonicalRaw.functionType(funcIdx)
                    val mapped = (0 until ownerSubTypeCount).firstOrNull { callerTypeIdx ->
                        TypeSection.crossModuleCanonicallyEquivalent(rawTypes, rawTypeIdx, ownerTypes, callerTypeIdx)
                    } ?: unmatchedTypeIdx
                    fnSection.addFunctionType(mapped)
                    codeSection.addFunctionBody(emptyBody)
                }

                val adaptedModule = WasmModule.builder()
                    .withValidation(false)
                    .setTypeSection(typeBuilder.build())
                    .setFunctionSection(fnSection.build())
                    .setCodeSection(codeSection.build())
                    .build()

                Instance.builder(adaptedModule)
                    .withInitialize(false)
                    .withStart(false)
                    .withMachineFactory(Function { Machine { funcId, args -> canonicalRaw.getMachine().call(funcId, args) } })
                    .build()
                    .also { canonicalInstances[it] = canonicalRaw }
            }
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

    private fun threadConfined(factory: Function<Instance, Machine>): Function<Instance, Machine> = Function { instance ->
        val machines = ThreadLocal.withInitial { factory.apply(instance) }
        Machine { funcId, args -> machines.get().call(funcId, args) }
    }

    private fun instance(index: Int): Instance =
        instances[index] ?: error("Core instance $index referenced before it was instantiated")

    internal fun coreFunction(def: CoreDef): ExportFunction = when (def) {
        is CoreDef.Export -> instance(def.ref.instance).export(def.ref.name)
        is CoreDef.Trampoline -> {
            val handle = trampoline(def.index)
            ExportFunction { args -> handle.apply(null, *args) }
        }
        is CoreDef.InstanceFlags -> error("Instance flags are not callable")
        is CoreDef.TaskMayBlock -> error("The task-may-block flag is not callable")
        is CoreDef.Intrinsic -> {
            val handle = async.intrinsic(def.name)
            ExportFunction { args -> handle.apply(null, *args) }
        }
    }

    // ---------------------------------------------------------------- trampolines

    private fun trampoline(index: Int): com.dylibso.chicory.runtime.WasmFunctionHandle =
        when (val t = plan.trampolines[index] ?: error("Unknown trampoline $index")) {
            is Trampoline.LowerImport -> com.dylibso.chicory.runtime.WasmFunctionHandle { _, args ->
                checkMayLeave("host import '${plan.imports.getValue(t.import).dispatchKey}'")
                when {
                    plan.options.getValue(t.options).async -> async.callHostAsync(t, args)
                    plan.usesAsync -> {
                        // An `async func` the host implements as a suspending function (ADR-0019).
                        var results: LongArray? = null
                        if (async.callHostSync(t) { fn -> results = callHost(t, args, fn) }) results else callHost(t, args)
                    }
                    else -> callHost(t, args)
                }
            }
            is Trampoline.ResourceNew -> com.dylibso.chicory.runtime.WasmFunctionHandle { _, args ->
                checkMayLeave("resource.new")
                longArrayOf(table(t.table).insert(args[0].toInt()).toLong())
            }
            is Trampoline.Async -> async.trampoline(t)
            is Trampoline.ResourceRep -> com.dylibso.chicory.runtime.WasmFunctionHandle { _, args ->
                longArrayOf(table(t.table).rep(args[0].toInt()).toLong())
            }
            is Trampoline.ResourceDrop -> com.dylibso.chicory.runtime.WasmFunctionHandle { _, args ->
                checkMayLeave("resource.drop")
                dropGuestHandle(t.table, args[0].toInt())
                null
            }
            is Trampoline.Trap -> com.dylibso.chicory.runtime.WasmFunctionHandle { _, args ->
                // A fused adapter found a Canonical ABI violation (e.g. leaving the instance or an
                // invalid string) and reports it by code, as Wasmtime's `trap` libcall does.
                val code = args[0].toInt()
                trap(t.messages.getOrNull(code) ?: "wasm trap: adapter trap code $code")
            }
            is Trampoline.Transcode -> {
                // Memories are extracted while instantiating, so look them up on first use.
                val transcoder by lazy { Transcoder(t.op, canonicalMemories.getValue(t.from), canonicalMemories.getValue(t.to)) }
                com.dylibso.chicory.runtime.WasmFunctionHandle { _, args -> transcoder.apply(args) }
            }
            is Trampoline.Unsupported -> com.dylibso.chicory.runtime.WasmFunctionHandle { _, _ ->
                throw UnsupportedOperationException("Component intrinsic not supported by the Chicory backend: ${t.detail}")
            }
        }

    /** Host functions by key, looked up once (ADR-0015). */
    private val hostFunctions = HashMap<String, WitHostFunction>()

    private fun hostFunction(key: String): WitHostFunction = hostFunctions.getOrPut(key) {
        val dispatcher = imports ?: throw IllegalStateException("Component imports '$key' but no host imports were provided")
        dispatcher.hostFunction(key) ?: throw IllegalStateException("Unknown host import: $key")
    }

    internal fun hostImport(import: Int): WitHostFunction = hostFunction(plan.imports.getValue(import).dispatchKey)

    /** A guest calling a host import: lift the guest's arguments, call the host, lower the results. */
    /** Suspending host functions by key, looked up once (ADR-0019). */
    private val asyncHostFunctions = HashMap<String, WitAsyncHostFunction?>()

    internal fun asyncHostImport(import: Int): WitAsyncHostFunction? {
        val key = plan.imports.getValue(import).dispatchKey
        return asyncHostFunctions.getOrPut(key) { imports?.asyncHostFunction(key) }
    }

    private fun callHost(t: Trampoline.LowerImport, coreArgs: LongArray, fn: WitHostFunction? = null): LongArray? {
        val import = plan.imports.getValue(t.import)
        val opts = plan.options.getValue(t.options)
        val cx = Context(opts, Direction.GUEST_TO_HOST)
        val args = WitWriter()
        val lifter = Lifter(cx, args)
        val type = t.type
        val paramTypes = type.paramTypes
        val flatParamCount = mapChicoryUnsupported { type.flatParamCount }
        val flatResultCount = mapChicoryUnsupported { type.flatResultCount }

        mapChicoryUnsupported {
            if (flatParamCount > CanonicalAbi.MAX_FLAT_PARAMS) {
                lifter.loadTuple(coreArgs[0].toInt(), type.paramTuple)
            } else if (paramTypes.isNotEmpty()) {
                val reader = Lifter.FlatReader(coreArgs)
                for (pt in paramTypes) lifter.liftFlat(pt, reader)
            }
        }

        val results = WitWriter()
        bind { guard.hostImport { (fn ?: hostFunction(import.dispatchKey)).call(args.toReader(), results) } }
        val reader = results.toReader()

        val lowerer = Lowerer(cx, reader)
        val lowered = mapChicoryUnsupported {
            if (flatResultCount > CanonicalAbi.MAX_FLAT_RESULTS) {
                // The caller passed a return pointer as the last core argument.
                val retPtr = coreArgs[coreArgs.size - 1].toInt()
                lowerer.storeTupleAt(type.resultTuple, retPtr)
                null
            } else if (type.results.isEmpty()) {
                null
            } else {
                val out = LongArray(flatResultCount)
                lowerer.lowerFlat(type.results[0], out, 0)
                out
            }
        }
        if (reader.remaining != 0) throw IllegalStateException("${reader.remaining} unread bytes after the results of host import '${import.dispatchKey}'")
        return lowered
    }

    // ---------------------------------------------------------------- resources

    /** The resource table [index]: a typed view of its component instance's handle table. */
    internal fun table(index: Int): ResourceTable =
        ResourceTable(async.state(plan.resourceTables[index]?.instance ?: 0).handles, index)

    internal fun dropGuestHandle(tableIndex: Int, guestHandle: Int) {
        val slot = table(tableIndex).slotOrNull(guestHandle) ?: trap("Unknown resource handle $guestHandle")
        if (slot.numLends != 0) trap("Cannot drop resource handle $guestHandle while it is lent to a call in progress")
        table(tableIndex).remove(guestHandle)
        if (!slot.own) {
            // Dropping a borrow handle only ends the borrow.
            slot.borrowScope?.let { it.numBorrows-- }
            return
        }
        destroy(tableIndex, slot.rep)
    }

    /** The last owning handle of [rep] is gone: run its destructor, or tell the host that defined it. */
    internal fun destroy(tableIndex: Int, rep: Int) {
        val info = plan.resourceTables[tableIndex] ?: return
        if (info.imported) {
            // The host defined this resource: tell it the component dropped its owning handle.
            val import = info.import?.let { plan.imports[it] } ?: return
            guard.hostImport { hostFunction(import.resourceDropKey).call(WitWriter(4).apply { int(rep) }.toReader(), WitWriter(0)) }
        } else {
            info.resource?.let { resourceDtors[it] }?.apply(rep.toLong())
        }
    }

    private fun isDefinedHere(tableIndex: Int): Boolean = plan.resourceTables[tableIndex]?.imported == false

    internal fun isImported(tableIndex: Int): Boolean = plan.resourceTables[tableIndex]?.imported == true

    private fun hasDestructor(tableIndex: Int): Boolean =
        plan.resourceTables[tableIndex]?.let { !it.imported && it.resource?.let(resourceDtors::containsKey) == true } == true

    internal enum class Direction { HOST_TO_GUEST, GUEST_TO_HOST }

    internal fun context(opts: CanonOptions, direction: Direction, borrowScope: (() -> Task)? = null) = Context(opts, direction, borrowScope)

    /**
     * Lifting and lowering at the host boundary. [borrowScope] is the task borrows of host resources
     * are lent to, for calls that run as Component Model tasks (#16); otherwise the call checks
     * itself that the borrows were dropped ([checkBorrowsDropped]).
     */
    internal inner class Context(
        private val opts: CanonOptions,
        private val direction: Direction,
        private val borrowScope: (() -> Task)? = null,
        private var cachedMemory: CanonicalMemory? = null,
        private var cachedRealloc: ExportFunction? = null,
    ) : AbiContext {
        override val memory: CanonicalMemory
            get() = cachedMemory ?: opts.memory?.let { canonicalMemories.getValue(it) }?.also { cachedMemory = it }
                ?: throw IllegalStateException("This function needs linear memory but its canonical options declare none")
        override val encoding: StringEncoding get() = opts.stringEncoding

        override fun unsupportedAsync(what: String): Nothing =
            throw UnsupportedOperationException("Passing a $what here is not supported by the Chicory backend")

        // Call-local resource tracking collections stay null until a resource is actually lowered or lifted.
        /** Host handles passed as `own` in this call; removed only once every argument lowered. */
        private var moves: LinkedHashSet<Int>? = null

        /** Host handles passed as `borrow` in this call; released when the call returns. */
        private var lent: MutableList<HostHandle>? = null

        /** Borrow handles of host resources created in the guest for this call: (table, handle, slot). */
        private var borrowsIn: MutableList<Triple<Int, Int, ResourceHandle>>? = null

        /** Handles this call added to the component's tables while lowering: (table, handle). */
        private var inserted: MutableList<Pair<Int, Int>>? = null

        /** Rollback hooks registered while lowering async/error handles for this call. */
        private var rollbackActions: MutableList<() -> Unit>? = null

        private fun recordRollback(action: () -> Unit) {
            val list = rollbackActions ?: ArrayList<() -> Unit>(2).also { rollbackActions = it }
            list += action
        }

        private fun insert(table: Int, rep: Int, own: Boolean, scope: Task? = null): Int =
            table(table).insert(rep, own, scope).also {
                val list = inserted ?: ArrayList<Pair<Int, Int>>(2).also { inserted = it }
                list += table to it
            }

        /** Lowering the arguments failed: take back the handles already added for them. */
        fun rollbackLowering() {
            rollbackActions?.let { list ->
                for (action in list.asReversed()) action()
                list.clear()
            }
            inserted?.let { list ->
                for ((t, h) in list.asReversed()) table(t).remove(h).borrowScope?.let { it.numBorrows-- }
                list.clear()
            }
            borrowsIn?.clear()
            moves?.clear()
            releaseLends()
        }

        /** The spec's `trap_if(num_borrows != 0)` when a call returns: the callee must drop borrows it received. */
        fun checkBorrowsDropped() {
            borrowsIn?.let { list ->
                for ((t, h, slot) in list) {
                    if (table(t).slotOrNull(h) === slot) poison("Borrowed resource handle $h was not dropped before the call returned")
                }
                list.clear()
            }
        }

        override fun realloc(align: Int, size: Int): Int {
            val f = cachedRealloc ?: opts.realloc?.let { reallocs.getValue(it) }?.also { cachedRealloc = it }
                ?: throw IllegalStateException("This function needs cabi_realloc but its canonical options declare none")
            return guest { withoutLeaving { f.apply(0, 0, align.toLong(), size.toLong())[0].toInt() } }
        }

        fun commitMoves() {
            moves?.let { set ->
                for (id in set) hostHandles.remove(id)
                set.clear()
            }
            inserted?.clear()
            rollbackActions?.clear()
        }

        fun releaseLends() {
            lent?.let { list ->
                for (h in list) h.lends--
                list.clear()
            }
        }

        override fun lowerOwn(table: Int, id: Int): Int {
            // A host resource moves into the component as its rep.
            if (isImported(table)) return insert(table, id, own = true)
            requireDefinedResource(table)
            val h = hostHandle(id, table)
            // The spec's lift_own: trap_if(h.num_lends != 0). The handle and the lend are the host's,
            // so this is a host error (ADR-0007): the call does not start and the instance stays
            // usable, as on Wasmtime 48.
            check(h.lends == 0) { "Cannot move resource handle $id while it is borrowed by the same call" }
            val set = moves ?: LinkedHashSet<Int>(2).also { moves = it }
            check(set.add(id)) { "Resource handle $id is moved twice in one call" }
            // The spec's lower_own: the component gets a new owning handle for the rep.
            return insert(table, h.rep, own = true)
        }

        override fun lowerBorrow(table: Int, id: Int): Int {
            if (isImported(table)) {
                check(direction == Direction.HOST_TO_GUEST) { "Host imports cannot return borrow<T>" }
                val scope = borrowScope?.invoke()
                val handle = insert(table, id, own = false, scope = scope)
                if (scope != null) scope.numBorrows++
                else {
                    val list = borrowsIn ?: ArrayList<Triple<Int, Int, ResourceHandle>>(2).also { borrowsIn = it }
                    list += Triple(table, handle, checkNotNull(table(table).slotOrNull(handle)))
                }
                return handle
            }
            requireDefinedResource(table)
            val h = hostHandle(id, table)
            h.lends++
            val list = lent ?: ArrayList<HostHandle>(2).also { lent = it }
            list += h
            // Borrowing into the defining instance passes the representation directly.
            return h.rep
        }

        private fun hostHandle(id: Int, table: Int): HostHandle {
            if (moves?.contains(id) == true) throw IllegalStateException("Resource handle $id is moved in this call and cannot be used again")
            val h = hostHandles[id] ?: throw IllegalStateException("Resource handle $id not found or already consumed")
            check(h.table == table) { "Resource handle $id belongs to a different resource type" }
            return h
        }

        override fun liftOwn(table: Int, handle: Int): Int {
            if (isImported(table)) {
                // The component hands a host resource back: the host owns its rep again.
                val t = table(table)
                val slot = t.slotOrNull(handle)
                if (slot?.own == false) trap("Cannot pass borrowed resource handle $handle as own")
                if (slot != null && slot.numLends != 0) trap("Cannot pass resource handle $handle as own while it is lent")
                return t.remove(handle).rep
            }
            requireDefinedResource(table)
            check(direction == Direction.HOST_TO_GUEST) { "Host imports receiving own<T> are not supported yet" }
            // The spec's lift_own: the handle leaves the component's table (trapping if it is
            // unknown or a borrow), so the component can no longer use or drop it.
            val t = table(table)
            val slot = t.slotOrNull(handle)
            if (slot?.own == false) trap("Cannot pass borrowed resource handle $handle as own")
            if (slot != null && slot.numLends != 0) trap("Cannot pass resource handle $handle as own while it is lent")
            val rep = t.remove(handle).rep
            val hostHandle = nextHostHandle++
            hostHandles[hostHandle] = HostHandle(table, rep)
            return hostHandle
        }

        override fun liftStream(table: Int, handle: Int): Int = async.liftEndToHost(table, handle, stream = true)
        override fun lowerStream(table: Int, id: Int): Int = async.lowerEndFromHost(table, id, stream = true, onRollback = ::recordRollback)
        override fun liftFuture(table: Int, handle: Int): Int = async.liftEndToHost(table, handle, stream = false)
        override fun lowerFuture(table: Int, id: Int): Int = async.lowerEndFromHost(table, id, stream = false, onRollback = ::recordRollback)
        override fun liftErrorContext(handle: Int): Int = async.liftErrorContextToHost(opts.instance, handle)
        override fun lowerErrorContext(id: Int): Int = async.lowerErrorContextFromHost(opts.instance, id, onRollback = ::recordRollback)

        override fun liftBorrow(table: Int, handle: Int): Int {
            if (isImported(table)) return table(table).rep(handle)
            throw UnsupportedOperationException("Host imports receiving borrow<T> of a component-defined resource are not supported by the Chicory backend yet")
        }

        private fun requireDefinedResource(table: Int) {
            if (!isDefinedHere(table)) {
                throw UnsupportedOperationException("Imported (host-defined) resources are not supported by the Chicory backend yet")
            }
        }
    }

    // ---------------------------------------------------------------- export lookup

    /**
     * Resolves a function name like the Wasmtime bridge (ADR-0011). The fully qualified form
     * (`pkg:ns/iface@1.0#fn`) is canonical; an interface written without its version or as its short
     * name (`iface#fn`), and a bare function name searched across exported interfaces, are accepted
     * only when they match exactly one export.
     */
    private fun resolveExport(spec: String): PlanExport {
        val hash = spec.indexOf('#')
        if (hash >= 0) {
            val iface = spec.substring(0, hash)
            val fn = spec.substring(hash + 1)
            val members = plan.exports.filter { it.name == fn && it.instance != null }
            members.firstOrNull { it.instance == iface }?.let { return it }
            val candidates = members.filter {
                unversionedInterfaceName(it.instance!!) == iface || shortInterfaceName(it.instance) == iface
            }
            return unique(spec, candidates) { "Exported function '$fn' not found in interface '$iface'" }
        }
        plan.exports.firstOrNull { it.instance == null && it.name == spec }?.let { return it }
        return unique(spec, plan.exports.filter { it.name == spec }) { "Exported function '$spec' not found" }
    }

    private fun unique(spec: String, candidates: List<PlanExport>, notFound: () -> String): PlanExport = when (candidates.size) {
        0 -> throw IllegalArgumentException(notFound())
        1 -> candidates.single()
        else -> throw IllegalArgumentException(
            "Function name '$spec' is ambiguous; use one of: " +
                candidates.joinToString { "${it.instance}#${it.name}" },
        )
    }
}

/**
 * A [TableInstance] view for a core module that either owns or imports a shared `funcref` table.
 * When `call_indirect` looks up the target [Instance] of an entry defined by another core module,
 * [instance] returns an adapted view whose type table matches [ownerModule]'s indices (#70).
 */
private class SharedTableInstance(
    val backing: TableInstance?,
    initialElement: Int,
    private val ownerModule: WasmModule,
    table: Table,
    private val adapt: (WasmModule, Instance) -> Instance,
) : TableInstance(table, initialElement) {
    var ownerInstance: Instance? = null

    override fun size(): Int = backing?.size() ?: super.size()

    override fun elementType(): ValType = backing?.elementType() ?: super.elementType()

    override fun limits(): TableLimits = backing?.limits() ?: super.limits()

    override fun grow(size: Int, value: Int, instance: Instance?): Int =
        backing?.grow(size, value, instance ?: ownerInstance) ?: super.grow(size, value, instance ?: ownerInstance)

    override fun ref(index: Int): Int = backing?.ref(index) ?: super.ref(index)

    override fun requiredRef(index: Int): Int = backing?.requiredRef(index) ?: super.requiredRef(index)

    override fun setRef(index: Int, value: Int, instance: Instance?) {
        val targetInstance = instance ?: ownerInstance
        if (backing != null) backing.setRef(index, value, targetInstance) else super.setRef(index, value, targetInstance)
    }

    override fun instance(index: Int): Instance? {
        val raw = if (backing != null) backing.instance(index) else super.instance(index)
        val effective = raw ?: (backing as? SharedTableInstance)?.ownerInstance ?: ownerInstance ?: return null
        return if (effective.module() === ownerModule) effective else adapt(ownerModule, effective)
    }

    override fun reset() {
        if (backing == null) super.reset()
    }
}
