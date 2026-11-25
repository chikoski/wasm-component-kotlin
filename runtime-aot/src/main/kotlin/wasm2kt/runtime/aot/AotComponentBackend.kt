@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.runtime.aot

import wasm2kt.runtime.ComponentBackend
import wasm2kt.runtime.HostFunction as WitHostFunction
import wasm2kt.runtime.WitHostImportDispatcher
import wasm2kt.runtime.WitReader
import wasm2kt.runtime.WitWriter
import wasm2kt.runtime.canonical.AbiContext
import wasm2kt.runtime.canonical.BorrowScope
import wasm2kt.runtime.canonical.CanonicalAbi
import wasm2kt.runtime.canonical.CanonicalMemory
import wasm2kt.runtime.canonical.ComponentGuard
import wasm2kt.runtime.canonical.FuncType
import wasm2kt.runtime.canonical.HandleTable
import wasm2kt.runtime.canonical.InternalWasm2KtApi
import wasm2kt.runtime.canonical.Lifter
import wasm2kt.runtime.canonical.Lowerer
import wasm2kt.runtime.canonical.ResourceHandle
import wasm2kt.runtime.canonical.ResourceTable
import wasm2kt.runtime.canonical.StringEncoding
import wasm2kt.runtime.canonical.UnsupportedWitTypeException
import wasm2kt.runtime.canonical.WitType
import wasm2kt.runtime.canonical.shortInterfaceName
import wasm2kt.runtime.canonical.trap
import wasm2kt.runtime.canonical.unversionedInterfaceName

/** Invokes an AOT-compiled core Wasm export or trampoline using flat `LongArray` ABI values. */
@InternalWasm2KtApi
fun interface AotCoreFunction {
    fun call(args: LongArray): LongArray?
}

/** Invokes a core module's `cabi_realloc` export during Canonical ABI lowering. */
@InternalWasm2KtApi
fun interface AotRealloc {
    fun realloc(oldPtr: Int, oldSize: Int, align: Int, newSize: Int): Int
}

/** Invokes a core module's `post_return` export after lifting results. */
@InternalWasm2KtApi
fun interface AotPostReturn {
    fun postReturn(results: LongArray)
}

/** Invokes a component-defined resource destructor for representation [rep]. */
@InternalWasm2KtApi
fun interface AotResourceDtor {
    fun drop(rep: Int)
}

/**
 * Canonical ABI options built during generated component instantiation. Memory, realloc, and
 * post-return targets are wired in initializer order as each core module is instantiated.
 */
@InternalWasm2KtApi
class AotCanonOptions(
    val stringEncoding: StringEncoding,
    val instance: Int = 0,
) {
    var memory: CanonicalMemory? = null
    var realloc: AotRealloc? = null
    var postReturn: AotPostReturn? = null
}

/** Static descriptor of a resource table referenced by a compiled component plan. */
@InternalWasm2KtApi
class AotResourceTableDesc(
    val index: Int,
    val resource: Int,
    val imported: Boolean,
    val instance: Int,
    val definedInInstance: Int,
    val dropKey: String? = null,
)

/** Static descriptor of an exported component function wired by generated code. */
@InternalWasm2KtApi
class AotExportDesc(
    val instance: String?,
    val name: String,
    val type: FuncType,
    val options: AotCanonOptions,
    val func: AotCoreFunction,
) {
    val qualifiedName: String = instance?.let { "$it#$name" } ?: name
}

/**
 * Phase 1 [ComponentBackend] runtime compatibility layer for statically wired AOT components
 * (ADR-0023).
 *
 * Delegates Canonical ABI lowering/lifting, handle management, string transcoding, and trap/poison
 * checks to `:runtime-canonical`, while eliminating runtime `plan.json`, `.meta`, Chicory, and
 * `kotlinx.serialization` dependencies.
 */
@InternalWasm2KtApi
class AotComponentBackend(
    private val imports: WitHostImportDispatcher?,
    resourceTables: Array<AotResourceTableDesc>,
) : ComponentBackend {
    private val guard = ComponentGuard()
    private val resourceTablesByIndex = HashMap<Int, AotResourceTableDesc>(resourceTables.size).apply {
        for (t in resourceTables) put(t.index, t)
    }
    private val handleTables = HashMap<Int, HandleTable>()
    private val resourceDtors = HashMap<Int, AotResourceDtor>()
    private val hostFunctions = HashMap<String, WitHostFunction>()

    private class HostHandle(val table: Int, val rep: Int) {
        var lends: Int = 0
    }

    private val hostHandles = HashMap<Int, HostHandle>()
    private var nextHostHandle = 1

    private class SyncCallFrame : BorrowScope {
        override var numBorrows: Int = 0
        val lenders = ArrayList<ResourceHandle>(2)
    }

    private val syncCallStack = ArrayList<SyncCallFrame>(4)

    private var exports: Array<AotExportDesc> = emptyArray()
    private val resolved = ArrayList<AotExportDesc>()
    private val resolvedIndices = HashMap<String, Int>()
    private val resultsWriter = WitWriter()

    /** Wraps generated core module instantiation (`start` and active segments) in [ComponentGuard.guest]. */
    fun initComponent(block: () -> Unit) {
        bind { guard.guest { block() } }
    }

    fun registerResourceDtor(resource: Int, dtor: AotResourceDtor) {
        resourceDtors[resource] = dtor
    }

    fun setExports(exports: Array<AotExportDesc>) {
        this.exports = exports
    }

    @Synchronized
    override fun resolve(functionName: String): Int {
        guard.checkUsable()
        resolvedIndices[functionName]?.let { return it }
        val export = resolveExport(functionName)
        var index = -1
        for (i in resolved.indices) {
            if (resolved[i] === export) {
                index = i
                break
            }
        }
        if (index < 0) {
            index = resolved.size
            resolved.add(export)
        }
        resolvedIndices[functionName] = index
        return index
    }

    @Synchronized
    private fun resolvedExport(function: Int): AotExportDesc =
        resolved.getOrNull(function) ?: throw IllegalArgumentException("Unknown function index $function")

    override fun invoke(function: Int, args: WitReader): WitReader {
        guard.checkUsable()
        val export = resolvedExport(function)
        val functionName = export.qualifiedName
        if (guard.entered) {
            guard.poison("Cannot reenter component instance: '$functionName' was called while another call into it is in progress")
        }
        val type = export.type
        val cx = Context(export.options, Direction.HOST_TO_GUEST)
        val syncDepth = syncCallStack.size
        guard.entered = true
        try {
            val lowerer = Lowerer(cx, args)
            val paramTypes = type.paramTypes
            val flatParamCount = mapAotUnsupported { type.flatParamCount }
            val coreArgs = try {
                mapAotUnsupported {
                    if (flatParamCount > CanonicalAbi.MAX_FLAT_PARAMS) {
                        longArrayOf(lowerer.storeTuple(type.paramTuple).toLong())
                    } else if (flatParamCount == 0) {
                        CanonicalAbi.EMPTY_LONG_ARRAY
                    } else {
                        val out = LongArray(flatParamCount)
                        var pos = 0
                        for (i in paramTypes.indices) {
                            pos = lowerer.lowerFlat(paramTypes[i], out, pos)
                        }
                        out
                    }
                }.also {
                    if (args.remaining != 0) {
                        throw IllegalArgumentException("${args.remaining} unread bytes after the arguments of '$functionName'")
                    }
                }
            } catch (e: Throwable) {
                cx.rollbackLowering()
                throw e
            }
            cx.commitMoves()

            val coreResults = guard.guest { export.func.call(coreArgs) ?: CanonicalAbi.EMPTY_LONG_ARRAY }
            cx.checkBorrowsDropped()

            resultsWriter.reset()
            if (type.results.isNotEmpty()) {
                guard.guest {
                    val lifter = Lifter(cx, resultsWriter)
                    mapAotUnsupported {
                        if (type.flatResultCount > CanonicalAbi.MAX_FLAT_RESULTS) {
                            lifter.loadTuple(coreResults[0].toInt(), type.resultTuple)
                        } else {
                            val reader = Lifter.FlatReader(coreResults)
                            for (i in type.results.indices) {
                                lifter.liftFlat(type.results[i], reader)
                            }
                        }
                    }
                }
            }
            export.options.postReturn?.let { pr ->
                guard.guest { guard.withoutLeaving { pr.postReturn(coreResults) } }
            }
            return if (resultsWriter.size > 64 * 1024) {
                val bytes = resultsWriter.toByteArray()
                resultsWriter.reset()
                WitReader(bytes)
            } else {
                resultsWriter.toReader()
            }
        } finally {
            cleanupSyncCalls(syncDepth)
            cx.releaseLends()
            guard.entered = false
        }
    }

    override fun dropResource(handle: Int) {
        guard.checkUsable()
        val h = hostHandles[handle] ?: throw IllegalStateException("Resource handle $handle not found or already consumed")
        check(h.lends == 0) { "Resource handle $handle is borrowed by a call in progress and cannot be dropped" }
        if (guard.entered && hasDestructor(h.table)) {
            guard.poison("Cannot reenter component instance: dropping resource handle $handle runs its destructor during another call")
        }
        hostHandles.remove(handle)
        guard.guest { destroy(h.table, h.rep) }
    }

    override fun close() {
        guard.closed = true
        hostHandles.clear()
        syncCallStack.clear()
    }

    // ---------------------------------------------------------------- trampoline & intrinsic helpers

    fun callHost(dispatchKey: String, type: FuncType, opts: AotCanonOptions, coreArgs: LongArray): LongArray? {
        guard.checkMayLeave("host import '$dispatchKey'")
        val cx = Context(opts, Direction.GUEST_TO_HOST)
        val args = WitWriter()
        val lifter = Lifter(cx, args)
        val paramTypes = type.paramTypes
        val flatParamCount = mapAotUnsupported { type.flatParamCount }
        val flatResultCount = mapAotUnsupported { type.flatResultCount }

        mapAotUnsupported {
            if (flatParamCount > CanonicalAbi.MAX_FLAT_PARAMS) {
                lifter.loadTuple(coreArgs[0].toInt(), type.paramTuple)
            } else if (paramTypes.isNotEmpty()) {
                val reader = Lifter.FlatReader(coreArgs)
                for (i in paramTypes.indices) {
                    lifter.liftFlat(paramTypes[i], reader)
                }
            }
        }

        val results = WitWriter()
        bind { guard.hostImport { hostFunction(dispatchKey).call(args.toReader(), results) } }
        val reader = results.toReader()

        val lowerer = Lowerer(cx, reader)
        val lowered = mapAotUnsupported {
            if (flatResultCount > CanonicalAbi.MAX_FLAT_RESULTS) {
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
        if (reader.remaining != 0) {
            throw IllegalStateException("${reader.remaining} unread bytes after the results of host import '$dispatchKey'")
        }
        return lowered
    }

    fun resourceNew(tableIndex: Int, rep: Int): Int {
        guard.checkMayLeave("resource.new")
        return resourceTable(tableIndex).insert(rep)
    }

    fun resourceRep(tableIndex: Int, handle: Int): Int =
        resourceTable(tableIndex).rep(handle)

    fun resourceDrop(tableIndex: Int, guestHandle: Int) {
        guard.checkMayLeave("resource.drop")
        val t = resourceTable(tableIndex)
        val slot = t.slotOrNull(guestHandle) ?: trap("Unknown resource handle $guestHandle")
        if (slot.numLends != 0) trap("Cannot drop resource handle $guestHandle while it is lent to a call in progress")
        t.remove(guestHandle)
        if (!slot.own) {
            slot.borrowScope?.let { it.numBorrows-- }
            return
        }
        destroy(tableIndex, slot.rep)
    }

    fun adapterTrap(code: Int, messages: Array<String?>): Nothing {
        trap(messages.getOrNull(code) ?: "wasm trap: adapter trap code $code")
    }

    fun unsupportedTrampoline(detail: String): Nothing {
        throw UnsupportedOperationException("Component intrinsic not supported by the AOT backend: $detail")
    }

    fun enterSyncCall() {
        syncCallStack.add(SyncCallFrame())
    }

    fun exitSyncCall() {
        if (syncCallStack.isEmpty()) throw IllegalStateException("exit-sync-call without enter-sync-call")
        val frame = syncCallStack.removeAt(syncCallStack.size - 1)
        try {
            if (frame.numBorrows > 0) trap("borrow handles still remain at the end of the call")
        } finally {
            for (i in frame.lenders.indices) {
                frame.lenders[i].numLends--
            }
        }
    }

    fun resourceTransferOwn(handle: Int, srcTable: Int, dstTable: Int): Int {
        val t = resourceTable(srcTable)
        val h = t.slotOrNull(handle) ?: trap("Unknown resource handle $handle")
        if (!h.own) trap("Cannot pass borrowed resource handle $handle as own")
        if (h.numLends != 0) trap("Cannot pass resource handle $handle as own while it is lent")
        t.remove(handle)
        return resourceTable(dstTable).insert(h.rep, own = true)
    }

    fun resourceTransferBorrow(handle: Int, srcTable: Int, dstTable: Int): Int {
        val h = resourceTable(srcTable).slotOrNull(handle) ?: trap("Unknown resource handle $handle")
        val scope = if (syncCallStack.isEmpty()) null else syncCallStack[syncCallStack.size - 1]
        if (h.own && scope != null) {
            h.numLends++
            scope.lenders.add(h)
        }
        val dst = resourceTablesByIndex[dstTable]
        if (dst != null && !dst.imported && dst.definedInInstance >= 0 && dst.definedInInstance == dst.instance) {
            return h.rep
        }
        scope?.let { it.numBorrows++ }
        return resourceTable(dstTable).insert(h.rep, own = false, scope = scope)
    }

    private fun cleanupSyncCalls(baseDepth: Int) {
        while (syncCallStack.size > baseDepth) {
            val frame = syncCallStack.removeAt(syncCallStack.size - 1)
            for (i in frame.lenders.indices) {
                frame.lenders[i].numLends--
            }
        }
    }

    // ---------------------------------------------------------------- internals & export lookup

    private fun handleTable(instanceIndex: Int): HandleTable =
        handleTables.getOrPut(instanceIndex) { HandleTable() }

    private fun resourceTable(tableIndex: Int): ResourceTable {
        val instIdx = resourceTablesByIndex[tableIndex]?.instance ?: 0
        return ResourceTable(handleTable(instIdx), tableIndex)
    }

    private fun isDefinedHere(tableIndex: Int): Boolean = resourceTablesByIndex[tableIndex]?.imported == false

    private fun isImported(tableIndex: Int): Boolean = resourceTablesByIndex[tableIndex]?.imported == true

    private fun hasDestructor(tableIndex: Int): Boolean =
        resourceTablesByIndex[tableIndex]?.let { !it.imported && it.resource >= 0 && resourceDtors.containsKey(it.resource) } == true

    private fun destroy(tableIndex: Int, rep: Int) {
        val info = resourceTablesByIndex[tableIndex] ?: return
        if (info.imported) {
            val key = info.dropKey ?: return
            guard.hostImport { hostFunction(key).call(WitWriter(4).apply { int(rep) }.toReader(), WitWriter(0)) }
        } else if (info.resource >= 0) {
            resourceDtors[info.resource]?.drop(rep)
        }
    }

    private fun hostFunction(key: String): WitHostFunction = hostFunctions.getOrPut(key) {
        val dispatcher = imports ?: throw IllegalStateException("Component imports '$key' but no host imports were provided")
        dispatcher.hostFunction(key) ?: throw IllegalStateException("Unknown host import: $key")
    }

    private inline fun <T> mapAotUnsupported(block: () -> T): T = try {
        block()
    } catch (e: UnsupportedWitTypeException) {
        throw UnsupportedOperationException("WIT type is not supported by the AOT backend yet: ${e.detail}", e)
    }

    private fun resolveExport(spec: String): AotExportDesc {
        val hash = spec.indexOf('#')
        if (hash >= 0) {
            val iface = spec.substring(0, hash)
            val fn = spec.substring(hash + 1)
            val members = exports.filter { it.name == fn && it.instance != null }
            members.firstOrNull { it.instance == iface }?.let { return it }
            val candidates = members.filter {
                unversionedInterfaceName(it.instance!!) == iface || shortInterfaceName(it.instance) == iface
            }
            return unique(spec, candidates) { "Exported function '$fn' not found in interface '$iface'" }
        }
        exports.firstOrNull { it.instance == null && it.name == spec }?.let { return it }
        return unique(spec, exports.filter { it.name == spec }) { "Exported function '$spec' not found" }
    }

    private fun unique(spec: String, candidates: List<AotExportDesc>, notFound: () -> String): AotExportDesc = when (candidates.size) {
        0 -> throw IllegalArgumentException(notFound())
        1 -> candidates[0]
        else -> throw IllegalArgumentException(
            "Function name '$spec' is ambiguous; use one of: " +
                candidates.joinToString { "${it.instance}#${it.name}" },
        )
    }

    private enum class Direction { HOST_TO_GUEST, GUEST_TO_HOST }

    private inner class Context(
        private val opts: AotCanonOptions,
        private val direction: Direction,
    ) : AbiContext {
        override val memory: CanonicalMemory
            get() = opts.memory
                ?: throw IllegalStateException("This function needs linear memory but its canonical options declare none")
        override val encoding: StringEncoding get() = opts.stringEncoding

        private var moves: LinkedHashSet<Int>? = null
        private var lent: MutableList<HostHandle>? = null
        private var borrowsIn: MutableList<Triple<Int, Int, ResourceHandle>>? = null
        private var inserted: MutableList<Pair<Int, Int>>? = null

        private fun insert(table: Int, rep: Int, own: Boolean): Int =
            resourceTable(table).insert(rep, own, scope = null).also {
                val list = inserted ?: ArrayList<Pair<Int, Int>>(2).also { inserted = it }
                list.add(table to it)
            }

        fun rollbackLowering() {
            inserted?.let { list ->
                for (i in list.size - 1 downTo 0) {
                    val (t, h) = list[i]
                    resourceTable(t).remove(h).borrowScope?.let { it.numBorrows-- }
                }
                list.clear()
            }
            borrowsIn?.clear()
            moves?.clear()
            releaseLends()
        }

        fun checkBorrowsDropped() {
            borrowsIn?.let { list ->
                for (i in list.indices) {
                    val (t, h, slot) = list[i]
                    if (resourceTable(t).slotOrNull(h) === slot) {
                        guard.poison("Borrowed resource handle $h was not dropped before the call returned")
                    }
                }
                list.clear()
            }
        }

        override fun realloc(align: Int, size: Int): Int {
            val f = opts.realloc
                ?: throw IllegalStateException("This function needs cabi_realloc but its canonical options declare none")
            return guard.guest { guard.withoutLeaving { f.realloc(0, 0, align, size) } }
        }

        fun commitMoves() {
            moves?.let { set ->
                for (id in set) hostHandles.remove(id)
                set.clear()
            }
            inserted?.clear()
        }

        fun releaseLends() {
            lent?.let { list ->
                for (i in list.indices) list[i].lends--
                list.clear()
            }
        }

        override fun lowerOwn(table: Int, id: Int): Int {
            if (isImported(table)) return insert(table, id, own = true)
            requireDefinedResource(table)
            val h = hostHandle(id, table)
            check(h.lends == 0) { "Cannot move resource handle $id while it is borrowed by the same call" }
            val set = moves ?: LinkedHashSet<Int>(2).also { moves = it }
            check(set.add(id)) { "Resource handle $id is moved twice in one call" }
            return insert(table, h.rep, own = true)
        }

        override fun lowerBorrow(table: Int, id: Int): Int {
            if (isImported(table)) {
                check(direction == Direction.HOST_TO_GUEST) { "Host imports cannot return borrow<T>" }
                val handle = insert(table, id, own = false)
                val list = borrowsIn ?: ArrayList<Triple<Int, Int, ResourceHandle>>(2).also { borrowsIn = it }
                list.add(Triple(table, handle, checkNotNull(resourceTable(table).slotOrNull(handle))))
                return handle
            }
            requireDefinedResource(table)
            val h = hostHandle(id, table)
            h.lends++
            val list = lent ?: ArrayList<HostHandle>(2).also { lent = it }
            list.add(h)
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
                val t = resourceTable(table)
                val slot = t.slotOrNull(handle)
                if (slot?.own == false) trap("Cannot pass borrowed resource handle $handle as own")
                if (slot != null && slot.numLends != 0) trap("Cannot pass resource handle $handle as own while it is lent")
                return t.remove(handle).rep
            }
            requireDefinedResource(table)
            check(direction == Direction.HOST_TO_GUEST) { "Host imports receiving own<T> are not supported yet" }
            val t = resourceTable(table)
            val slot = t.slotOrNull(handle)
            if (slot?.own == false) trap("Cannot pass borrowed resource handle $handle as own")
            if (slot != null && slot.numLends != 0) trap("Cannot pass resource handle $handle as own while it is lent")
            val rep = t.remove(handle).rep
            val hostHandle = nextHostHandle++
            hostHandles[hostHandle] = HostHandle(table, rep)
            return hostHandle
        }

        override fun liftBorrow(table: Int, handle: Int): Int {
            if (isImported(table)) return resourceTable(table).rep(handle)
            throw UnsupportedOperationException("Host imports receiving borrow<T> of a component-defined resource are not supported by the AOT backend yet")
        }

        private fun requireDefinedResource(table: Int) {
            if (!isDefinedHere(table)) {
                throw UnsupportedOperationException("Imported (host-defined) resources are not supported by the AOT backend yet")
            }
        }
    }
}
