@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.runtime.canonical

/**
 * Borrow scope owning borrowed resource handles created during a call or Component Model task.
 * Tracks [numBorrows] so returning/cancelling while borrows remain traps per Canonical ABI.
 */
@InternalWasm2KtApi
interface BorrowScope {
    var numBorrows: Int
}

/**
 * A component instance's table of handles (the reference's `Table`): index 0 is never used, and
 * freed indices are reused most recently freed first.
 */
@InternalWasm2KtApi
class HandleTable {
    private val array = ArrayList<Any?>().apply { add(null) }
    private val free = ArrayList<Int>()

    fun getOrNull(i: Int): Any? = if (i <= 0 || i >= array.size) null else array[i]

    fun get(i: Int): Any = getOrNull(i) ?: trap("Unknown handle index ${unsigned(i)}")

    fun add(e: Any): Int {
        if (free.isNotEmpty()) {
            val i = free.removeAt(free.size - 1)
            array[i] = e
            return i
        }
        val i = array.size
        if (i > MAX_LENGTH) trap("Handle table is full")
        array += e
        return i
    }

    fun remove(i: Int): Any {
        val e = get(i)
        array[i] = null
        free += i
        return e
    }

    fun values(): List<Any> = array.filterNotNull()

    companion object {
        const val MAX_LENGTH = (1 shl 28) - 1
    }
}

/**
 * A resource handle in a component instance's table. [borrowScope] is the scope a `borrow` handle
 * was lent to (null for an `own` handle, and for borrows lent by a synchronous host call).
 */
@InternalWasm2KtApi
class ResourceHandle(
    val table: Int,
    val rep: Int,
    val own: Boolean,
    val borrowScope: BorrowScope? = null,
) {
    var numLends: Int = 0
}

/**
 * A resource table of the plan, as a view of its component instance's [HandleTable], which also
 * holds that instance's waitables, waitable sets and error contexts (the spec's `inst.handles`).
 * A handle of another resource type, or one that is not a resource, is reported as unknown.
 */
@InternalWasm2KtApi
class ResourceTable(private val handles: HandleTable, private val table: Int) {
    fun insert(rep: Int, own: Boolean = true, scope: BorrowScope? = null): Int =
        handles.add(ResourceHandle(table, rep, own, scope))

    fun rep(handle: Int): Int = (slotOrNull(handle) ?: trap("Unknown resource handle $handle")).rep

    fun slotOrNull(handle: Int): ResourceHandle? =
        (handles.getOrNull(handle) as? ResourceHandle)?.takeIf { it.table == table }

    fun remove(handle: Int): ResourceHandle {
        val slot = slotOrNull(handle) ?: trap("Unknown resource handle $handle")
        handles.remove(handle)
        return slot
    }
}
