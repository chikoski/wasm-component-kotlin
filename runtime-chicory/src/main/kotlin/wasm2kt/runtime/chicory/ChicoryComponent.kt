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
import com.dylibso.chicory.wasm.types.FunctionImport
import com.dylibso.chicory.wasm.types.FunctionType
import com.dylibso.chicory.wasm.types.MutabilityType
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
    internal val memories = HashMap<Int, Memory>()
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
    private var closed = false

    private class PoisonState(val message: String, val isExit: Boolean)

    /** Why the instance can no longer be used, once a trap or exit happened. */
    private var poisoned: PoisonState? = null

    /** A host call into the instance is in progress (the spec's `may_enter` is false). */
    private var entered = false

    /** The guest may call imports (false during `cabi_realloc` and post-return). */
    internal var mayLeave = true

    init {
        bind {
            for (init in plan.initializers) {
                when (init) {
                    is Initializer.Instantiate -> instantiateModule(init)
                    is Initializer.ExtractMemory -> memories[init.index] = instance(init.export.instance).exports().memory(init.export.name)
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

    /** Exports resolved so far; [invoke] takes their index. */
    private val resolved = ArrayList<PlanExport>()
    private val resolvedIndices = HashMap<String, Int>()
    private val results = WitWriter()

    @Synchronized
    override fun resolve(functionName: String): Int {
        checkUsable()
        resolvedIndices[functionName]?.let { return it }
        val export = resolveExport(functionName)
        val index = resolved.indexOf(export).takeIf { it >= 0 } ?: resolved.size.also { resolved += export }
        resolvedIndices[functionName] = index
        return index
    }

    @Synchronized
    private fun resolvedExport(function: Int): PlanExport =
        resolved.getOrNull(function) ?: throw IllegalArgumentException("Unknown function index $function")

    private fun PlanExport.qualifiedName(): String = instance?.let { "$it#$name" } ?: name

    /**
     * For a component that uses the async ABI, the call suspends until the component returns, and
     * several can be in progress at once (ADR-0019); otherwise it is [invoke].
     */
    override suspend fun invokeSuspending(function: Int, args: WitReader): WitReader {
        if (!plan.usesAsync) return invoke(function, args)
        checkUsable()
        val export = resolvedExport(function)
        return async.invokeSuspending(export, export.qualifiedName(), args)
    }

    override fun invoke(function: Int, args: WitReader): WitReader {
        checkUsable()
        val export = resolvedExport(function)
        val functionName = export.qualifiedName()
        // Calls into a component that uses the async ABI are tasks on its scheduler (ADR-0019).
        if (plan.usesAsync) return async.invoke(export, functionName, args)
        if (entered) poison("Cannot reenter component instance: '$functionName' was called while another call into it is in progress")
        val type = export.type
        val opts = plan.options.getValue(export.options)
        val cx = Context(opts, Direction.HOST_TO_GUEST)
        entered = true
        try {
            // Lowering the host's arguments: errors in the values are the host's (not traps), but
            // cabi_realloc runs guest code, so a misbehaving realloc does trap (see Context.realloc).
            val lowerer = Lowerer(cx, args)
            val paramTypes = type.params.map { it.second }
            val flatParams = CanonicalAbi.flattenAll(paramTypes)
            val coreArgs = mutableListOf<Long>()
            try {
                if (flatParams.size > CanonicalAbi.MAX_FLAT_PARAMS) {
                    coreArgs += lowerer.storeTuple(paramTypes).toLong()
                } else {
                    paramTypes.forEach { lowerer.lowerFlat(it, coreArgs) }
                }
                if (args.remaining != 0) throw IllegalArgumentException("${args.remaining} unread bytes after the arguments of '$functionName'")
            } catch (e: Throwable) {
                // A bad host argument must not leave handles behind in the component's tables.
                cx.rollbackLowering()
                throw e
            }
            cx.commitMoves()

            val coreResults = guest { coreFunction(export.func).apply(*coreArgs.toLongArray()) ?: LongArray(0) }
            cx.checkBorrowsDropped()

            results.reset()
            guest {
                val lifter = Lifter(cx, results)
                if (CanonicalAbi.flattenAll(type.results).size > CanonicalAbi.MAX_FLAT_RESULTS) {
                    lifter.loadTuple(coreResults[0].toInt(), type.results)
                } else {
                    val reader = Lifter.FlatReader(coreResults)
                    type.results.forEach { lifter.liftFlat(it, reader) }
                }
            }
            opts.postReturn?.let { pr -> guest { withoutLeaving { postReturns.getValue(pr).apply(*coreResults) } } }
            return results.toReader()
        } finally {
            cx.releaseLends()
            entered = false
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
        if (entered && hasDestructor(h.table)) {
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
        closed = true
        hostHandles.clear()
        async.close()
    }

    // ---------------------------------------------------------------- instance state

    internal fun checkUsable() {
        check(!closed) { "Component instance is already closed" }
        poisoned?.let { state ->
            if (state.isExit) {
                throw ComponentTrapException("Component instance has already exited (${state.message}); open a new session")
            } else {
                throw ComponentTrapException("Component instance is unusable after an earlier trap (${state.message}); open a new session")
            }
        }
    }

    /** Traps and poisons the instance. */
    internal fun poison(message: String): Nothing {
        markPoisoned(message)
        throw ComponentTrapException(message)
    }

    private fun markPoisoned(message: String, isExit: Boolean = false) {
        if (poisoned == null) {
            poisoned = PoisonState(message, isExit)
            // Guest threads still blocked can never run again.
            async.abandon()
        }
    }

    /**
     * Runs guest code (or validates guest-produced values). Anything thrown poisons the instance,
     * since the guest may be left mid-execution; wasm traps surface as [ComponentTrapException].
     */
    internal inline fun <T> guest(block: () -> T): T = try {
        block()
    } catch (e: HostValueException) {
        // The host's own arguments were wrong (ADR-0007): not a trap.
        throw e.cause!!
    } catch (e: Throwable) {
        val surfaced = asTrap(e)
        markPoisoned(surfaced.message ?: surfaced.javaClass.name, surfaced is ComponentExitException)
        throw surfaced
    }

    internal fun asTrap(e: Throwable): Throwable {
        var c: Throwable? = e
        while (c != null) {
            if (c is ComponentTrapException) return c
            c = c.cause
        }
        return if (e is ChicoryException) ComponentTrapException("wasm trap: ${e.message}", e) else e
    }

    internal inline fun <T> withoutLeaving(block: () -> T): T {
        val previous = mayLeave
        mayLeave = false
        try {
            return block()
        } finally {
            mayLeave = previous
        }
    }

    internal fun checkMayLeave(what: String) {
        if (!mayLeave) poison("Cannot leave component instance: $what was called during cabi_realloc or post-return")
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
                if (plan.usesAsync) {
                    // Guest threads of one instance interleave (ADR-0018), and Chicory's machines keep
                    // their operand and call stacks per machine: give every JVM thread its own.
                    withMachineFactory(threadConfined(core.machineFactory ?: Function { InterpreterMachine(it) }))
                } else {
                    core.machineFactory?.let { withMachineFactory(it) }
                }
            }
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
                val transcoder by lazy { Transcoder(t.op, memories.getValue(t.from), memories.getValue(t.to)) }
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
        val paramTypes = t.type.params.map { it.second }
        val flatParams = CanonicalAbi.flattenAll(paramTypes)
        val flatResults = CanonicalAbi.flattenAll(t.type.results)

        if (flatParams.size > CanonicalAbi.MAX_FLAT_PARAMS) {
            lifter.loadTuple(coreArgs[0].toInt(), paramTypes)
        } else {
            val reader = Lifter.FlatReader(coreArgs)
            paramTypes.forEach { lifter.liftFlat(it, reader) }
        }

        val results = WitWriter()
        bind { (fn ?: hostFunction(import.dispatchKey)).call(args.toReader(), results) }
        val reader = results.toReader()

        val lowerer = Lowerer(cx, reader)
        val lowered = if (flatResults.size > CanonicalAbi.MAX_FLAT_RESULTS) {
            // The caller passed a return pointer as the last core argument.
            val retPtr = coreArgs[coreArgs.size - 1].toInt()
            lowerer.storeTupleAt(t.type.results, retPtr)
            null
        } else if (t.type.results.isEmpty()) {
            null
        } else {
            val out = mutableListOf<Long>()
            lowerer.lowerFlat(t.type.results[0], out)
            out.toLongArray()
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
            hostFunction(import.resourceDropKey).call(WitWriter(4).apply { int(rep) }.toReader(), WitWriter(0))
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
    ) : AbiContext {
        override val memory: Memory
            get() = opts.memory?.let { memories.getValue(it) }
                ?: throw IllegalStateException("This function needs linear memory but its canonical options declare none")
        override val encoding: StringEncoding get() = opts.stringEncoding

        /** Host handles passed as `own` in this call; removed only once every argument lowered. */
        private val moves = LinkedHashSet<Int>()

        /** Host handles passed as `borrow` in this call; released when the call returns. */
        private val lent = mutableListOf<HostHandle>()

        /** Borrow handles of host resources created in the guest for this call: (table, handle, slot). */
        private val borrowsIn = mutableListOf<Triple<Int, Int, ResourceHandle>>()

        /** Handles this call added to the component's tables while lowering: (table, handle). */
        private val inserted = mutableListOf<Pair<Int, Int>>()

        private fun insert(table: Int, rep: Int, own: Boolean, scope: Task? = null): Int =
            table(table).insert(rep, own, scope).also { inserted += table to it }

        /** Lowering the arguments failed: take back the handles already added for them. */
        fun rollbackLowering() {
            for ((t, h) in inserted.asReversed()) table(t).remove(h).borrowScope?.let { it.numBorrows-- }
            inserted.clear()
            borrowsIn.clear()
            moves.clear()
        }

        /** The spec's `trap_if(num_borrows != 0)` when a call returns: the callee must drop borrows it received. */
        fun checkBorrowsDropped() {
            for ((t, h, slot) in borrowsIn) {
                if (table(t).slotOrNull(h) === slot) poison("Borrowed resource handle $h was not dropped before the call returned")
            }
            borrowsIn.clear()
        }

        override fun realloc(align: Int, size: Int): Int {
            val f = opts.realloc?.let { reallocs.getValue(it) }
                ?: throw IllegalStateException("This function needs cabi_realloc but its canonical options declare none")
            return guest { withoutLeaving { f.apply(0, 0, align.toLong(), size.toLong())[0].toInt() } }
        }

        fun commitMoves() {
            moves.forEach { hostHandles.remove(it) }
            moves.clear()
            inserted.clear()
        }

        fun releaseLends() {
            lent.forEach { it.lends-- }
            lent.clear()
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
            check(moves.add(id)) { "Resource handle $id is moved twice in one call" }
            // The spec's lower_own: the component gets a new owning handle for the rep.
            return insert(table, h.rep, own = true)
        }

        override fun lowerBorrow(table: Int, id: Int): Int {
            if (isImported(table)) {
                check(direction == Direction.HOST_TO_GUEST) { "Host imports cannot return borrow<T>" }
                val scope = borrowScope?.invoke()
                val handle = insert(table, id, own = false, scope = scope)
                if (scope != null) scope.numBorrows++
                else borrowsIn += Triple(table, handle, checkNotNull(table(table).slotOrNull(handle)))
                return handle
            }
            requireDefinedResource(table)
            val h = hostHandle(id, table)
            h.lends++
            lent += h
            // Borrowing into the defining instance passes the representation directly.
            return h.rep
        }

        private fun hostHandle(id: Int, table: Int): HostHandle {
            if (id in moves) throw IllegalStateException("Resource handle $id is moved in this call and cannot be used again")
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
        override fun lowerStream(table: Int, id: Int): Int = async.lowerEndFromHost(table, id, stream = true)
        override fun liftFuture(table: Int, handle: Int): Int = async.liftEndToHost(table, handle, stream = false)
        override fun lowerFuture(table: Int, id: Int): Int = async.lowerEndFromHost(table, id, stream = false)
        override fun liftErrorContext(handle: Int): Int = async.liftErrorContextToHost(opts.instance, handle)
        override fun lowerErrorContext(id: Int): Int = async.lowerErrorContextFromHost(opts.instance, id)

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
 * A resource table of the plan, as a view of its component instance's [HandleTable], which also
 * holds that instance's waitables, waitable sets and error contexts (the spec's `inst.handles`).
 * A handle of another resource type, or one that is not a resource, is reported as unknown.
 */
internal class ResourceTable(private val handles: HandleTable, private val table: Int) {
    fun insert(rep: Int, own: Boolean = true, scope: Task? = null): Int = handles.add(ResourceHandle(table, rep, own, scope))

    fun rep(handle: Int): Int = (slotOrNull(handle) ?: trap("Unknown resource handle $handle")).rep

    fun slotOrNull(handle: Int): ResourceHandle? = (handles.getOrNull(handle) as? ResourceHandle)?.takeIf { it.table == table }

    fun remove(handle: Int): ResourceHandle {
        val slot = slotOrNull(handle) ?: trap("Unknown resource handle $handle")
        handles.remove(handle)
        return slot
    }
}
