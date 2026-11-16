package wasm2kt.runtime

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import java.util.IdentityHashMap
import kotlin.coroutines.CoroutineContext

/**
 * A Kotlin object backing a resource that the component imports (a host-defined resource).
 * Generated bindings declare one sub-interface per imported resource; the host implements it.
 *
 * [close] is called when the component drops its last owning handle, or when the session closes
 * while the component still owns the resource.
 */
interface WitHostResource : AutoCloseable {
    override fun close() {}
}

/**
 * Maps host resource objects to the integer representations ("reps") a component holds, for one
 * session. Backends only ever see reps; generated bindings convert between reps and objects.
 *
 * Ownership follows the Canonical ABI:
 * * passing an object as `own` (or inside any other value) moves it to the component; the table
 *   keeps it until the component drops it ([drop], which calls [WitHostResource.close]) or hands
 *   it back as `own` ([take])
 * * passing an object as `borrow` lends it for one call ([lend]); it leaves the table when that
 *   call returns unless the component took ownership meanwhile
 *
 * Generated code finds the table of the call in progress through [current]: sessions [bind] it
 * around instantiation and every call into the component, so host imports the component calls
 * meanwhile (synchronously, on the same thread) see it too. *
 * Synchronous calls bind it on their thread ([bind]); suspending calls (ADR-0019) carry it in their
 * coroutine context ([bindSuspending]), each with its own lends, so several can be in progress at
 * once. Its methods are synchronized; the generated session also serializes synchronous calls (#75).
 */
class HostResourceTable : AutoCloseable {
    private class Entry(val resource: WitHostResource, var owned: Boolean)

    private val entries = HashMap<Int, Entry>()
    private val reps = IdentityHashMap<WitHostResource, Int>()
    private var nextRep = 1

    /** Active synchronous and suspending lend scopes on this table. */
    private val activeScopes = ArrayList<LendScope>()

    /** The lends of the call whose thread or coroutine runs on this table. */
    private val currentScope = ThreadLocal<LendScope?>()

    /**
     * Moves [resource] to the component and returns its rep.
     *
     * The host must still own [resource]: one the component already owns (moved earlier and not
     * handed back) or one lent to a call in progress cannot be moved, as in the spec's `lift_own`
     * (`trap_if(h.num_lends != 0)`). Both are host errors and fail here, before the component
     * could end up with two owning handles for one object.
     */
    @Synchronized
    fun lower(resource: WitHostResource): Int {
        val rep = repOf(resource)
        val entry = entries.getValue(rep)
        check(!entry.owned) { "${describe(resource)} was already moved to the component and cannot be passed as own again" }
        check(activeScopes.none { rep in it.reps }) {
            "${describe(resource)} is lent to a call in progress and cannot be moved"
        }
        entry.owned = true
        return rep
    }

    /**
     * Lends [resource] to the component for the current call. Lending the same object twice in a
     * call is fine; lending one the component already owns is a host error.
     */
    @Synchronized
    fun lend(resource: WitHostResource): Int {
        val scope = currentScope.get()
        val known = reps[resource]
        if (known != null) {
            check(!entries.getValue(known).owned) { "${describe(resource)} was moved to the component and cannot be lent" }
            if (scope != null && known !in scope.reps) scope.reps.add(known)
            return known
        }
        val rep = repOf(resource)
        scope?.reps?.add(rep)
        return rep
    }

    /** The object behind a `borrow` the component passed to the host. */
    @Synchronized
    fun <T : WitHostResource> borrow(rep: Int, type: Class<T>): T = cast(entry(rep).resource, type, rep)

    /** The object behind an `own` the component handed back; the host owns it again. */
    @Synchronized
    fun <T : WitHostResource> take(rep: Int, type: Class<T>): T {
        val e = entry(rep)
        val resource = cast(e.resource, type, rep)
        forget(rep)
        return resource
    }

    /** The component dropped its owning handle: forget [rep] and close the object. */
    fun drop(rep: Int) {
        val e = synchronized(this) {
            entry(rep).also { forget(rep) }
        }
        e.resource.close()
    }

    /** Number of objects currently held for the component. */
    val size: Int @Synchronized get() = entries.size

    /** Closes every object the component still owns. */
    override fun close() {
        val owned = synchronized(this) {
            entries.values.filter { it.owned }.map { it.resource }.also {
                entries.clear()
                reps.clear()
            }
        }
        owned.forEach { runCatching { it.close() } }
    }

    /** Runs [block] with this table as [current]; lends made inside end with it. */
    fun <R> bind(block: () -> R): R {
        val previous = CURRENT.get()
        val previousScope = currentScope.get()
        val scope = LendScope()
        synchronized(this) { activeScopes += scope }
        CURRENT.set(this)
        currentScope.set(scope)
        try {
            return block()
        } finally {
            synchronized(this) {
                activeScopes.remove(scope)
                endLends(scope.reps)
            }
            if (previousScope == null) currentScope.remove() else currentScope.set(previousScope)
            if (previous == null) CURRENT.remove() else CURRENT.set(previous)
        }
    }

    /**
     * Runs the suspending [block] with this table as [current] wherever the coroutine runs; lends
     * made inside end with it (ADR-0019). Other calls may be in progress meanwhile.
     */
    suspend fun <R> bindSuspending(block: suspend () -> R): R {
        val scope = LendScope()
        synchronized(this) { activeScopes += scope }
        try {
            return withContext(asContextElement() + currentScope.asContextElement(scope)) { block() }
        } finally {
            synchronized(this) {
                activeScopes.remove(scope)
                endLends(scope.reps)
            }
        }
    }

    /** Makes this table [current] in a coroutine wherever it runs, e.g. a host import's (ADR-0019). */
    fun asContextElement(): CoroutineContext.Element = CURRENT.asContextElement(this)

    private fun endLends(lends: List<Int>) {
        for (rep in lends) {
            if (entries[rep]?.owned == false && activeScopes.none { rep in it.reps }) {
                forget(rep)
            }
        }
    }

    private fun repOf(resource: WitHostResource): Int = reps.getOrPut(resource) {
        val rep = nextRep++
        entries[rep] = Entry(resource, owned = false)
        rep
    }

    private fun describe(resource: WitHostResource) = "Host resource ${resource.javaClass.name}"

    private fun entry(rep: Int): Entry = entries[rep] ?: throw IllegalStateException("Unknown host resource rep $rep")

    private fun forget(rep: Int) {
        entries.remove(rep)?.let { reps.remove(it.resource) }
    }

    private fun <T> cast(resource: WitHostResource, type: Class<T>, rep: Int): T =
        if (type.isInstance(resource)) type.cast(resource)
        else throw IllegalStateException("Host resource rep $rep is a ${resource.javaClass.name}, not a ${type.name}")

    private class LendScope {
        val reps = mutableListOf<Int>()
    }

    companion object {
        private val CURRENT = ThreadLocal<HostResourceTable?>()

        /**
         * The table bound on this thread, or null. For a backend that runs guest code on threads of
         * its own (the Chicory backend's async ABI, ADR-0018), to carry the binding over with [setCurrent].
         */
        @JvmStatic
        fun currentOrNull(): HostResourceTable? = CURRENT.get()

        /** Binds [table] on this thread (null unbinds), without opening a lend scope; see [currentOrNull]. */
        @JvmStatic
        fun setCurrent(table: HostResourceTable?) {
            if (table == null) CURRENT.remove() else CURRENT.set(table)
        }

        /** The table of the session whose call is in progress on this thread. */
        @JvmStatic
        fun current(): HostResourceTable = CURRENT.get()
            ?: throw IllegalStateException("Host resources can only be passed during a call on an open session")
    }
}

/** Marks a host resource passed as `borrow`: encoded with [HostResourceTable.lend] instead of moved. */
class WitBorrowed(val resource: WitHostResource)
