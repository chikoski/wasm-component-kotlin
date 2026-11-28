@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.runtime.aot

import wasm2kt.runtime.HostResourceTable
import wasm2kt.runtime.WitHostResource
import wasm2kt.runtime.canonical.BorrowScope
import wasm2kt.runtime.canonical.ComponentGuard
import wasm2kt.runtime.canonical.DirectCanonicalAbi
import wasm2kt.runtime.canonical.HandleTable
import wasm2kt.runtime.canonical.InternalWasm2KtApi
import wasm2kt.runtime.canonical.ResourceHandle
import wasm2kt.runtime.canonical.ResourceTable
import wasm2kt.runtime.canonical.StringEncoding
import wasm2kt.runtime.canonical.trap

/** Invokes a core module's `cabi_realloc` export during Canonical ABI lowering. */
@InternalWasm2KtApi
fun interface AotRealloc {
    fun realloc(oldPtr: Int, oldSize: Int, align: Int, newSize: Int): Int
}

/** Invokes a component-defined resource destructor for representation [rep]. */
@InternalWasm2KtApi
fun interface AotResourceDtor {
    fun drop(rep: Int)
}

/** Handles dropping an imported resource for components that mix mapped library interfaces. */
@InternalWasm2KtApi
fun interface AotImportedResourceDropHandler {
    fun tryDrop(info: AotResourceTableDesc, rep: Int): Boolean
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

@InternalWasm2KtApi
enum class AbiDirection {
    HOST_TO_GUEST,
    GUEST_TO_HOST,
}

/**
 * Shared runtime core for AOT-compiled synchronous components (ADR-0023 Phase 2).
 *
 * Strictly free of binary wire format (`WitWriter`, `WitReader`), dynamic `ComponentBackend`,
 * `Lowerer`/`Lifter`, `WitType`/`FuncType`, and `Linker` references so R8 strips those classes
 * completely for pure Phase 2 AOT applications.
 */
@InternalWasm2KtApi
open class AotComponentRuntime(
    resourceTables: Array<AotResourceTableDesc>,
    hasHostResources: Boolean = false,
) {
    val guard: ComponentGuard = ComponentGuard()
    val hostResources: HostResourceTable? = if (hasHostResources) HostResourceTable() else null

    private var mappedDropHandler: AotImportedResourceDropHandler? = null
    private val resourceTablesByIndex = HashMap<Int, AotResourceTableDesc>(resourceTables.size).apply {
        for (t in resourceTables) put(t.index, t)
    }
    private val handleTables = HashMap<Int, HandleTable>()
    private val resourceDtors = HashMap<Int, AotResourceDtor>()

    protected class HostHandle(val table: Int, val rep: Int) {
        var lends: Int = 0
    }

    private val hostHandles = HashMap<Int, HostHandle>()
    private var nextHostHandle = 1

    private class SyncCallFrame : BorrowScope {
        override var numBorrows: Int = 0
        val lenders = ArrayList<ResourceHandle>(2)
    }

    private val syncCallStack = ArrayList<SyncCallFrame>(4)

    fun setMappedDropHandler(handler: AotImportedResourceDropHandler) {
        mappedDropHandler = handler
    }

    /** Hook notified once when the component instance transitions to poisoned. */
    open fun onPoison() {}

    /** Wraps generated core module instantiation (`start` and active segments) in [ComponentGuard.guest]. */
    fun initComponent(block: () -> Unit) {
        withHostResources { guard.guest(onPoison = ::onPoison) { block() } }
    }

    fun registerResourceDtor(resource: Int, dtor: AotResourceDtor) {
        resourceDtors[resource] = dtor
    }

    inline fun <T> withHostResources(crossinline block: () -> T): T {
        val hr = hostResources
        return if (hr != null) hr.bind { block() } else block()
    }

    fun enterDirect(): Int {
        guard.checkUsable()
        if (guard.entered) {
            guard.poison("Cannot reenter component instance: called while another call into it is in progress", ::onPoison)
        }
        guard.entered = true
        return syncCallStack.size
    }

    fun exitDirect(syncDepth: Int) {
        cleanupSyncCalls(syncDepth)
        guard.entered = false
    }

    inline fun <T> guardGuest(block: () -> T): T = guard.guest(onPoison = ::onPoison, block = block)

    inline fun <T> guardHostImport(block: () -> T): T = guard.hostImport(block)

    inline fun runPostReturn(block: () -> Unit) {
        guard.guest(onPoison = ::onPoison) { guard.withoutLeaving(block) }
    }

    fun checkMayLeave(what: String) = guard.checkMayLeave(what, ::onPoison)

    fun poison(message: String): Nothing = guard.poison(message, ::onPoison)

    fun alloc(mem: LinearMemory, realloc: AotRealloc, align: Int, size: Int): Int =
        guard.guest(onPoison = ::onPoison) {
            val ptr = guard.withoutLeaving { realloc.realloc(0, 0, align, size) }
            DirectCanonicalAbi.checkReallocResult(mem, ptr, align, size)
        }

    fun lowerString(mem: LinearMemory, realloc: AotRealloc, encoding: StringEncoding, s: String): Long =
        when (encoding) {
            StringEncoding.UTF8 -> {
                val bytes = DirectCanonicalAbi.encodeUtf8(s)
                val ptr = alloc(mem, realloc, 1, bytes.size)
                if (bytes.isNotEmpty()) mem.writeBytes(ptr, bytes, 0, bytes.size)
                DirectCanonicalAbi.packPtrLen(ptr, bytes.size)
            }
            StringEncoding.UTF16 -> {
                val bytes = DirectCanonicalAbi.encodeUtf16Le(s)
                val ptr = alloc(mem, realloc, 2, bytes.size)
                if (bytes.isNotEmpty()) mem.writeBytes(ptr, bytes, 0, bytes.size)
                DirectCanonicalAbi.packPtrLen(ptr, s.length)
            }
            StringEncoding.LATIN1_UTF16 -> {
                val latin1 = DirectCanonicalAbi.encodeLatin1OrNull(s)
                if (latin1 != null) {
                    val ptr = alloc(mem, realloc, 2, latin1.size)
                    if (latin1.isNotEmpty()) mem.writeBytes(ptr, latin1, 0, latin1.size)
                    DirectCanonicalAbi.packPtrLen(ptr, latin1.size)
                } else {
                    val bytes = DirectCanonicalAbi.encodeUtf16Le(s)
                    val ptr = alloc(mem, realloc, 2, bytes.size)
                    if (bytes.isNotEmpty()) mem.writeBytes(ptr, bytes, 0, bytes.size)
                    DirectCanonicalAbi.packPtrLen(ptr, s.length or DirectCanonicalAbi.UTF16_TAG)
                }
            }
        }

    fun lowerByteArray(mem: LinearMemory, realloc: AotRealloc, bytes: ByteArray): Long {
        val byteLen = DirectCanonicalAbi.checkLowerListLength(bytes.size, 1)
        val ptr = alloc(mem, realloc, 1, byteLen)
        if (byteLen > 0) mem.writeBytes(ptr, bytes, 0, byteLen)
        return DirectCanonicalAbi.packPtrLen(ptr, bytes.size)
    }

    open fun dropResource(handle: Int) = dropHostHandle(handle)

    protected fun dropHostHandle(handle: Int) {
        guard.checkUsable()
        val h = hostHandles[handle] ?: throw IllegalStateException("Resource handle $handle not found or already consumed")
        check(h.lends == 0) { "Resource handle $handle is borrowed by a call in progress and cannot be dropped" }
        if (guard.entered && hasDestructor(h.table)) {
            guard.poison("Cannot reenter component instance: dropping resource handle $handle runs its destructor during another call", ::onPoison)
        }
        hostHandles.remove(handle)
        withHostResources { guard.guest(onPoison = ::onPoison) { destroy(h.table, h.rep) } }
    }

    protected fun clearHostHandles() {
        hostHandles.clear()
    }

    open fun close() {
        guard.closed = true
        hostHandles.clear()
        syncCallStack.clear()
        hostResources?.close()
    }

    // ---------------------------------------------------------------- trampoline & intrinsic helpers

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

    protected val syncDepthNow: Int
        get() = syncCallStack.size

    protected fun cleanupSyncCalls(baseDepth: Int) {
        while (syncCallStack.size > baseDepth) {
            val frame = syncCallStack.removeAt(syncCallStack.size - 1)
            for (i in frame.lenders.indices) {
                frame.lenders[i].numLends--
            }
        }
    }

    // ---------------------------------------------------------------- resource tables & destruction

    protected open fun handleTableForInstance(instanceIndex: Int): HandleTable =
        handleTables.getOrPut(instanceIndex) { HandleTable() }

    protected fun resourceTable(tableIndex: Int): ResourceTable {
        val instIdx = resourceTablesByIndex[tableIndex]?.instance ?: 0
        return ResourceTable(handleTableForInstance(instIdx), tableIndex)
    }

    protected fun resourceTableDesc(tableIndex: Int): AotResourceTableDesc? = resourceTablesByIndex[tableIndex]

    protected fun isDefinedHere(tableIndex: Int): Boolean = resourceTablesByIndex[tableIndex]?.imported == false

    protected fun isImported(tableIndex: Int): Boolean = resourceTablesByIndex[tableIndex]?.imported == true

    private fun hasDestructor(tableIndex: Int): Boolean =
        resourceTablesByIndex[tableIndex]?.let { !it.imported && it.resource >= 0 && resourceDtors.containsKey(it.resource) } == true

    protected open fun dropImportedResource(info: AotResourceTableDesc, rep: Int) {
        if (mappedDropHandler?.tryDrop(info, rep) == true) return
        val table = hostResources
            ?: throw IllegalStateException("Component dropped host resource rep $rep without a HostResourceTable")
        guard.hostImport { table.drop(rep) }
    }

    private fun destroy(tableIndex: Int, rep: Int) {
        val info = resourceTablesByIndex[tableIndex] ?: return
        if (info.imported) {
            dropImportedResource(info, rep)
        } else if (info.resource >= 0) {
            resourceDtors[info.resource]?.drop(rep)
        }
    }

    fun newResourceContext(direction: AbiDirection, borrowScope: (() -> BorrowScope)? = null): DirectResourceContext =
        DirectResourceContext(direction, borrowScope)

    @InternalWasm2KtApi
    inner class DirectResourceContext(
        private val direction: AbiDirection,
        private val borrowScope: (() -> BorrowScope)? = null,
    ) {
        private var moves: LinkedHashSet<Int>? = null
        private var hostMoves: MutableList<Int>? = null
        private var lent: MutableList<HostHandle>? = null
        private var borrowsIn: MutableList<Triple<Int, Int, ResourceHandle>>? = null
        private var inserted: MutableList<Pair<Int, Int>>? = null
        private var rollbackActions: MutableList<() -> Unit>? = null

        fun recordRollback(action: () -> Unit) {
            val list = rollbackActions ?: ArrayList<() -> Unit>(2).also { rollbackActions = it }
            list.add(action)
        }

        private fun insert(table: Int, rep: Int, own: Boolean, scope: BorrowScope? = null): Int =
            resourceTable(table).insert(rep, own, scope = scope).also {
                val list = inserted ?: ArrayList<Pair<Int, Int>>(2).also { inserted = it }
                list.add(table to it)
            }

        fun rollbackLowering() {
            rollbackActions?.let { list ->
                for (i in list.size - 1 downTo 0) list[i]()
                list.clear()
            }
            inserted?.let { list ->
                for (i in list.size - 1 downTo 0) {
                    val (t, h) = list[i]
                    resourceTable(t).remove(h).borrowScope?.let { it.numBorrows-- }
                }
                list.clear()
            }
            hostMoves?.let { reps ->
                val hr = hostResources
                if (hr != null) {
                    for (i in reps.size - 1 downTo 0) {
                        runCatching { hr.take(reps[i], WitHostResource::class.java) }
                    }
                }
                reps.clear()
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
                        guard.poison("Borrowed resource handle $h was not dropped before the call returned", ::onPoison)
                    }
                }
                list.clear()
            }
        }

        fun commitMoves() {
            moves?.let { set ->
                for (id in set) hostHandles.remove(id)
                set.clear()
            }
            hostMoves?.clear()
            inserted?.clear()
            rollbackActions?.clear()
        }

        fun releaseLends() {
            lent?.let { list ->
                for (i in list.indices) list[i].lends--
                list.clear()
            }
        }

        fun lowerHostOwn(table: Int, resource: WitHostResource): Int {
            val hr = checkNotNull(hostResources) { "Host resource passed without HostResourceTable" }
            val rep = hr.lower(resource)
            val hm = hostMoves ?: ArrayList<Int>(2).also { hostMoves = it }
            hm.add(rep)
            return insert(table, rep, own = true)
        }

        fun lowerHostBorrow(table: Int, resource: WitHostResource): Int {
            check(direction == AbiDirection.HOST_TO_GUEST) { "Host imports cannot return borrow<T>" }
            val hr = checkNotNull(hostResources) { "Host resource passed without HostResourceTable" }
            val rep = hr.lend(resource)
            return lowerBorrow(table, rep)
        }

        fun <T : WitHostResource> liftHostOwn(table: Int, coreHandle: Int, type: Class<T>): T {
            val rep = liftOwn(table, coreHandle)
            val hr = checkNotNull(hostResources) { "Host resource lifted without HostResourceTable" }
            return hr.take(rep, type)
        }

        fun <T : WitHostResource> liftHostBorrow(table: Int, coreHandle: Int, type: Class<T>): T {
            val rep = liftBorrow(table, coreHandle)
            val hr = checkNotNull(hostResources) { "Host resource lifted without HostResourceTable" }
            return hr.borrow(rep, type)
        }

        fun lowerOwn(table: Int, id: Int): Int {
            if (isImported(table)) return insert(table, id, own = true)
            requireDefinedResource(table)
            val h = hostHandle(id, table)
            check(h.lends == 0) { "Cannot move resource handle $id while it is borrowed by the same call" }
            val set = moves ?: LinkedHashSet<Int>(2).also { moves = it }
            check(set.add(id)) { "Resource handle $id is moved twice in one call" }
            return insert(table, h.rep, own = true)
        }

        fun lowerBorrow(table: Int, id: Int): Int {
            if (isImported(table)) {
                check(direction == AbiDirection.HOST_TO_GUEST) { "Host imports cannot return borrow<T>" }
                val scope = borrowScope?.invoke()
                val handle = insert(table, id, own = false, scope = scope)
                if (scope != null) {
                    scope.numBorrows++
                } else {
                    val list = borrowsIn ?: ArrayList<Triple<Int, Int, ResourceHandle>>(2).also { borrowsIn = it }
                    list.add(Triple(table, handle, checkNotNull(resourceTable(table).slotOrNull(handle))))
                }
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

        fun liftOwn(table: Int, handle: Int): Int {
            if (isImported(table)) {
                val t = resourceTable(table)
                val slot = t.slotOrNull(handle)
                if (slot?.own == false) trap("Cannot pass borrowed resource handle $handle as own")
                if (slot != null && slot.numLends != 0) trap("Cannot pass resource handle $handle as own while it is lent")
                return t.remove(handle).rep
            }
            requireDefinedResource(table)
            check(direction == AbiDirection.HOST_TO_GUEST) { "Host imports receiving own<T> are not supported yet" }
            val t = resourceTable(table)
            val slot = t.slotOrNull(handle)
            if (slot?.own == false) trap("Cannot pass borrowed resource handle $handle as own")
            if (slot != null && slot.numLends != 0) trap("Cannot pass resource handle $handle as own while it is lent")
            val rep = t.remove(handle).rep
            val hostHandle = nextHostHandle++
            hostHandles[hostHandle] = HostHandle(table, rep)
            return hostHandle
        }

        fun liftBorrow(table: Int, handle: Int): Int {
            if (isImported(table)) return resourceTable(table).rep(handle)
            throw UnsupportedOperationException("Host imports receiving borrow<T> of a component-defined resource are not supported by the AOT backend yet")
        }

        private fun requireDefinedResource(table: Int) {
            if (!isDefinedHere(table)) {
                throw UnsupportedOperationException("Imported (host-defined) resources are not supported by the Phase 1 AOT backend yet")
            }
        }
    }
}
