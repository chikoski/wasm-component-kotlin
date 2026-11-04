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
import com.dylibso.chicory.wasm.ChicoryException
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.WasmModule
import com.dylibso.chicory.wasm.types.FunctionImport
import com.dylibso.chicory.wasm.types.FunctionType
import com.dylibso.chicory.wasm.types.MutabilityType
import com.dylibso.chicory.wasm.types.Value
import wasm2kt.runtime.ComponentBackend
import wasm2kt.runtime.ComponentBackendFactory
import wasm2kt.runtime.ComponentTrapException
import wasm2kt.runtime.HostFunction as WitHostFunction
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
 *
 * Instance state follows the Canonical ABI: a call may not start while another one is in progress
 * on this instance (`may_enter`; e.g. from inside a host import), the guest may not call out while
 * running `cabi_realloc` or post-return (`may_leave`), and a handle the host lent to a call as
 * `borrow` cannot be moved or dropped until that call returns. Any trap poisons the instance.
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

    /** The task's "may block" flag. Every call here is synchronous, so it stays false (0). */
    private val taskMayBlock = GlobalInstance(Value.i32(0), MutabilityType.Var)

    /** Per resource table: guest handle -> representation. Handle 0 is reserved as invalid. */
    private val guestTables = HashMap<Int, ResourceTable>()

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

    /** Why the instance can no longer be used, once a trap happened. */
    private var poisoned: String? = null

    /** A host call into the instance is in progress (the spec's `may_enter` is false). */
    private var entered = false

    /** The guest may call imports (false during `cabi_realloc` and post-return). */
    private var mayLeave = true

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

    /** Exports resolved so far; [invoke] takes their index. */
    private val resolved = ArrayList<PlanExport>()
    private val resolvedIndices = HashMap<String, Int>()

    override fun resolve(functionName: String): Int {
        checkUsable()
        resolvedIndices[functionName]?.let { return it }
        val export = resolveExport(functionName)
        val index = resolved.indexOf(export).takeIf { it >= 0 } ?: resolved.size.also { resolved += export }
        resolvedIndices[functionName] = index
        return index
    }

    override fun invoke(function: Int, args: WitReader): WitReader {
        checkUsable()
        val export = resolved.getOrNull(function) ?: throw IllegalArgumentException("Unknown function index $function")
        val functionName = export.instance?.let { "$it#${export.name}" } ?: export.name
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

            val results = WitWriter()
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
        checkUsable()
        val h = hostHandles[handle] ?: throw IllegalStateException("Resource handle $handle not found or already consumed")
        check(h.lends == 0) { "Resource handle $handle is borrowed by a call in progress and cannot be dropped" }
        if (entered && hasDestructor(h.table)) {
            poison("Cannot reenter component instance: dropping resource handle $handle runs its destructor during another call")
        }
        hostHandles.remove(handle)
        guest { destroy(h.table, h.rep) }
    }

    override fun close() {
        closed = true
        hostHandles.clear()
        guestTables.clear()
    }

    // ---------------------------------------------------------------- instance state

    private fun checkUsable() {
        check(!closed) { "Component instance is already closed" }
        poisoned?.let { throw ComponentTrapException("Component instance is unusable after an earlier trap ($it); open a new session") }
    }

    /** Traps and poisons the instance. */
    private fun poison(message: String): Nothing {
        if (poisoned == null) poisoned = message
        throw ComponentTrapException(message)
    }

    /**
     * Runs guest code (or validates guest-produced values). Anything thrown poisons the instance,
     * since the guest may be left mid-execution; wasm traps surface as [ComponentTrapException].
     */
    private inline fun <T> guest(block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        val surfaced = asTrap(e)
        if (poisoned == null) poisoned = surfaced.message ?: surfaced.javaClass.name
        throw surfaced
    }

    private fun asTrap(e: Throwable): Throwable {
        var c: Throwable? = e
        while (c != null) {
            if (c is ComponentTrapException) return c
            c = c.cause
        }
        return if (e is ChicoryException) ComponentTrapException("wasm trap: ${e.message}", e) else e
    }

    private inline fun <T> withoutLeaving(block: () -> T): T {
        val previous = mayLeave
        mayLeave = false
        try {
            return block()
        } finally {
            mayLeave = previous
        }
    }

    private fun checkMayLeave(what: String) {
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
        is CoreDef.TaskMayBlock -> error("The task-may-block flag is not callable")
    }

    // ---------------------------------------------------------------- trampolines

    private fun trampoline(index: Int): com.dylibso.chicory.runtime.WasmFunctionHandle =
        when (val t = plan.trampolines[index] ?: error("Unknown trampoline $index")) {
            is Trampoline.LowerImport -> com.dylibso.chicory.runtime.WasmFunctionHandle { _, args ->
                checkMayLeave("host import '${plan.imports.getValue(t.import).dispatchKey}'")
                callHost(t, args)
            }
            is Trampoline.ResourceNew -> com.dylibso.chicory.runtime.WasmFunctionHandle { _, args ->
                checkMayLeave("resource.new")
                longArrayOf(table(t.table).insert(args[0].toInt()).toLong())
            }
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

    /** A guest calling a host import: lift the guest's arguments, call the host, lower the results. */
    private fun callHost(t: Trampoline.LowerImport, coreArgs: LongArray): LongArray? {
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
        hostFunction(import.dispatchKey).call(args.toReader(), results)
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

    private fun table(index: Int): ResourceTable = guestTables.getOrPut(index) { ResourceTable() }

    private fun dropGuestHandle(tableIndex: Int, guestHandle: Int) {
        val slot = table(tableIndex).remove(guestHandle)
        if (!slot.own) return // dropping a borrow handle only ends the borrow
        destroy(tableIndex, slot.rep)
    }

    /** The last owning handle of [rep] is gone: run its destructor, or tell the host that defined it. */
    private fun destroy(tableIndex: Int, rep: Int) {
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

    private fun isImported(tableIndex: Int): Boolean = plan.resourceTables[tableIndex]?.imported == true

    private fun hasDestructor(tableIndex: Int): Boolean =
        plan.resourceTables[tableIndex]?.let { !it.imported && it.resource?.let(resourceDtors::containsKey) == true } == true

    private enum class Direction { HOST_TO_GUEST, GUEST_TO_HOST }

    private inner class Context(private val opts: CanonOptions, private val direction: Direction) : AbiContext {
        override val memory: Memory
            get() = opts.memory?.let { memories.getValue(it) }
                ?: throw IllegalStateException("This function needs linear memory but its canonical options declare none")
        override val encoding: StringEncoding get() = opts.stringEncoding

        /** Host handles passed as `own` in this call; removed only once every argument lowered. */
        private val moves = LinkedHashSet<Int>()

        /** Host handles passed as `borrow` in this call; released when the call returns. */
        private val lent = mutableListOf<HostHandle>()

        /** Borrow handles of host resources created in the guest for this call: (table, handle, slot). */
        private val borrowsIn = mutableListOf<Triple<Int, Int, ResourceTable.Slot>>()

        /** Handles this call added to the component's tables while lowering: (table, handle). */
        private val inserted = mutableListOf<Pair<Int, Int>>()

        private fun insert(table: Int, rep: Int, own: Boolean): Int =
            table(table).insert(rep, own).also { inserted += table to it }

        /** Lowering the arguments failed: take back the handles already added for them. */
        fun rollbackLowering() {
            for ((t, h) in inserted.asReversed()) table(t).remove(h)
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
                val handle = insert(table, id, own = false)
                borrowsIn += Triple(table, handle, checkNotNull(table(table).slotOrNull(handle)))
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
                if (t.slotOrNull(handle)?.own == false) trap("Cannot pass borrowed resource handle $handle as own")
                return t.remove(handle).rep
            }
            requireDefinedResource(table)
            check(direction == Direction.HOST_TO_GUEST) { "Host imports receiving own<T> are not supported yet" }
            // The spec's lift_own: the handle leaves the component's table (trapping if it is
            // unknown or a borrow), so the component can no longer use or drop it.
            val t = table(table)
            if (t.slotOrNull(handle)?.own == false) trap("Cannot pass borrowed resource handle $handle as own")
            val rep = t.remove(handle).rep
            val hostHandle = nextHostHandle++
            hostHandles[hostHandle] = HostHandle(table, rep)
            return hostHandle
        }

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
 * A component-level resource table: guest handles (starting at 1) mapped to representations.
 * Handles of host resources lent for one call are `borrow` handles ([Slot.own] = false).
 * Freed handles are reused most-recently-freed first, like the spec's `Table.free` list.
 */
internal class ResourceTable {
    class Slot(val rep: Int, val own: Boolean)

    private val slots = HashMap<Int, Slot>()
    private val free = ArrayDeque<Int>()
    private var next = 1

    fun insert(rep: Int, own: Boolean = true): Int {
        val h = free.removeLastOrNull() ?: next++
        slots[h] = Slot(rep, own)
        return h
    }

    fun rep(handle: Int): Int = (slots[handle] ?: trap("Unknown resource handle $handle")).rep

    fun slotOrNull(handle: Int): Slot? = slots[handle]

    fun remove(handle: Int): Slot {
        val slot = slots.remove(handle) ?: trap("Unknown resource handle $handle")
        free.addLast(handle)
        return slot
    }
}
