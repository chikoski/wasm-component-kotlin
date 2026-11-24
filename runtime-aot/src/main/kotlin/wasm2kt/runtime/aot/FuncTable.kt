@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.runtime.aot

import wasm2kt.runtime.canonical.InternalWasm2KtApi
import wasm2kt.runtime.canonical.trap
import java.util.Arrays

/** Host-provided trampoline callable via an indirect function reference or table entry. */
@InternalWasm2KtApi
fun interface HostTrampoline {
    fun call(args: LongArray): LongArray?
}

/** Core WebAssembly value kinds used to form canonical structural function type signatures. */
@InternalWasm2KtApi
enum class WasmValKind(val code: Char) {
    I32('I'),
    I64('J'),
    F32('F'),
    F64('D'),
    FUNCREF('R'),
    EXTERNREF('E'),
}

/**
 * Formats and compares canonical structural type signatures across core modules so shared-table
 * `call_indirect` validates structural equivalence independent of module-local type indices.
 */
@InternalWasm2KtApi
object WasmTypeSignatures {
    fun canonical(params: List<WasmValKind>, results: List<WasmValKind>): String {
        val sb = StringBuilder(params.size + results.size + 2)
        sb.append('(')
        for (p in params) sb.append(p.code)
        sb.append(')')
        if (results.isEmpty()) {
            sb.append('V')
        } else {
            for (r in results) sb.append(r.code)
        }
        return sb.toString()
    }

    fun areEquivalent(sigA: String, sigB: String): Boolean = sigA == sigB
}

/**
 * Immutable `funcref` representation (`null` represents `ref.null func`). Holds the owning core
 * instance reference, function index, and canonical structural type signature identifier.
 */
@InternalWasm2KtApi
sealed class FuncRef {
    abstract val owner: Any?
    abstract val funcIndex: Int
    abstract val signature: String

    fun matchesSignature(expectedSignature: String): Boolean = signature == expectedSignature

    fun requireSignature(expectedSignature: String): FuncRef {
        if (signature != expectedSignature) {
            trap("indirect call type mismatch: expected $expectedSignature, got $signature")
        }
        return this
    }

    /** Function defined by a compiled core module instance [owner]. */
    @InternalWasm2KtApi
    class Core(
        override val owner: Any,
        override val funcIndex: Int,
        override val signature: String,
    ) : FuncRef() {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Core && owner === other.owner && funcIndex == other.funcIndex && signature == other.signature)

        override fun hashCode(): Int {
            var h = System.identityHashCode(owner)
            h = 31 * h + funcIndex
            h = 31 * h + signature.hashCode()
            return h
        }

        override fun toString(): String = "FuncRef.Core(owner=${owner.javaClass.simpleName}, funcIndex=$funcIndex, signature=$signature)"
    }

    /** Dedicated callable reference for a host trampoline registered in a core table. */
    @InternalWasm2KtApi
    class Host(
        override val signature: String,
        val trampoline: HostTrampoline,
        override val owner: Any? = null,
        override val funcIndex: Int = -1,
    ) : FuncRef() {
        override fun equals(other: Any?): Boolean =
            this === other || (
                other is Host &&
                    trampoline === other.trampoline &&
                    owner === other.owner &&
                    funcIndex == other.funcIndex &&
                    signature == other.signature
                )

        override fun hashCode(): Int {
            var h = System.identityHashCode(trampoline)
            h = 31 * h + System.identityHashCode(owner)
            h = 31 * h + funcIndex
            h = 31 * h + signature.hashCode()
            return h
        }

        override fun toString(): String = "FuncRef.Host(funcIndex=$funcIndex, signature=$signature)"
    }

    companion object {
        fun core(owner: Any, funcIndex: Int, signature: String): Core = Core(owner, funcIndex, signature)

        fun host(
            signature: String,
            trampoline: HostTrampoline,
            owner: Any? = null,
            funcIndex: Int = -1,
        ): Host = Host(signature, trampoline, owner, funcIndex)
    }
}

/**
 * Shared reference table supporting `funcref` ([FuncRef]) and `externref` (`Any`), multi-module
 * sharing, delayed `elem` segment patching, and `table.grow` returning `-1` on limit/OOM.
 */
@InternalWasm2KtApi
class WasmTable<T : Any>(
    initialSize: Int,
    maxSize: Int? = null,
    initValue: T? = null,
) {
    val maxSize: Int = run {
        require(initialSize in 0..MAX_TABLE_SIZE) { "initialSize $initialSize out of range 0..$MAX_TABLE_SIZE" }
        if (maxSize != null) {
            require(maxSize >= initialSize) { "maxSize $maxSize < initialSize $initialSize" }
            minOf(maxSize, MAX_TABLE_SIZE)
        } else {
            MAX_TABLE_SIZE
        }
    }

    private var elements: Array<Any?> = arrayOfNulls<Any?>(initialSize).also { arr ->
        if (initValue != null && initialSize > 0) Arrays.fill(arr, initValue)
    }

    fun size(): Int = elements.size

    @Suppress("UNCHECKED_CAST")
    fun get(index: Int): T? {
        val idx = checkIndex(index, "table.get")
        return elements[idx] as T?
    }

    fun getNonNull(index: Int): T =
        get(index) ?: trap("uninitialized element at table index ${index.toLong() and 0xFFFF_FFFFL}")

    fun set(index: Int, value: T?) {
        val idx = checkIndex(index, "table.set")
        elements[idx] = value
    }

    /**
     * Grows the table by [delta] (unsigned 32-bit) entries filled with [fillValue]. Returns the
     * previous table size on success, or `-1` if [maxSize] is exceeded or [OutOfMemoryError] occurs.
     */
    fun grow(delta: Int, fillValue: T?): Int {
        val d = delta.toLong() and 0xFFFF_FFFFL
        val oldSize = elements.size
        if (d == 0L) return oldSize
        val newSizeLong = oldSize.toLong() + d
        if (newSizeLong > maxSize.toLong()) return -1
        val newSize = newSizeLong.toInt()
        val grown = try {
            elements.copyOf(newSize)
        } catch (_: OutOfMemoryError) {
            return -1
        }
        if (fillValue != null) {
            Arrays.fill(grown, oldSize, newSize, fillValue)
        }
        elements = grown
        return oldSize
    }

    /** Wasm `table.fill`. */
    fun fill(dst: Int, value: T?, len: Int) {
        val d = dst.toLong() and 0xFFFF_FFFFL
        val n = len.toLong() and 0xFFFF_FFFFL
        val arr = elements
        if (d + n > arr.size.toLong()) {
            trap("table.fill: [$d, ${d + n}) out of bounds of table size ${arr.size}")
        }
        if (n > 0L) Arrays.fill(arr, d.toInt(), (d + n).toInt(), value)
    }

    /** Wasm `table.copy` from [srcTable] (which may be `this`) into `this`. */
    fun copyFrom(srcTable: WasmTable<T>, dst: Int, src: Int, len: Int) {
        val d = dst.toLong() and 0xFFFF_FFFFL
        val s = src.toLong() and 0xFFFF_FFFFL
        val n = len.toLong() and 0xFFFF_FFFFL
        val srcArr = srcTable.elements
        val dstArr = elements
        if (s + n > srcArr.size.toLong()) {
            trap("table.copy: source [$s, ${s + n}) out of bounds of table size ${srcArr.size}")
        }
        if (d + n > dstArr.size.toLong()) {
            trap("table.copy: destination [$d, ${d + n}) out of bounds of table size ${dstArr.size}")
        }
        if (n > 0L) System.arraycopy(srcArr, s.toInt(), dstArr, d.toInt(), n.toInt())
    }

    /** Wasm `table.init` from a passive element segment array. */
    fun initFrom(segment: Array<out T?>, dst: Int, src: Int, len: Int) {
        val d = dst.toLong() and 0xFFFF_FFFFL
        val s = src.toLong() and 0xFFFF_FFFFL
        val n = len.toLong() and 0xFFFF_FFFFL
        if (s + n > segment.size.toLong()) {
            trap("table.init: segment [$s, ${s + n}) out of bounds of segment size ${segment.size}")
        }
        val dstArr = elements
        if (d + n > dstArr.size.toLong()) {
            trap("table.init: destination [$d, ${d + n}) out of bounds of table size ${dstArr.size}")
        }
        if (n > 0L) System.arraycopy(segment, s.toInt(), dstArr, d.toInt(), n.toInt())
    }

    /**
     * Applies an active `elem` segment at [offset], supporting delayed patching of shared tables
     * across module instantiation order.
     */
    fun patch(offset: Int, entries: Array<out T?>) {
        val off = offset.toLong() and 0xFFFF_FFFFL
        val arr = elements
        if (off + entries.size.toLong() > arr.size.toLong()) {
            trap("elem segment patch [$off, ${off + entries.size}) out of bounds of table size ${arr.size}")
        }
        if (entries.isNotEmpty()) System.arraycopy(entries, 0, arr, off.toInt(), entries.size)
    }

    private fun checkIndex(index: Int, op: String): Int {
        val idx = index.toLong() and 0xFFFF_FFFFL
        if (idx >= elements.size.toLong()) {
            trap("$op: index $idx out of bounds of table size ${elements.size}")
        }
        return idx.toInt()
    }

    companion object {
        const val MAX_TABLE_SIZE = Int.MAX_VALUE - 8
    }
}

/** Manages passive/active element segments for `table.init` and `elem.drop` without reflection. */
@InternalWasm2KtApi
class ElemSegmentStore<T : Any>(segments: Array<Array<T?>>) {
    private val store: Array<Array<Any?>> = Array(segments.size) { i ->
        val src = segments[i]
        Array(src.size) { j -> src[j] }
    }

    @Suppress("UNCHECKED_CAST")
    fun init(table: WasmTable<T>, segmentIndex: Int, dst: Int, src: Int, len: Int) {
        if (segmentIndex !in store.indices) trap("Invalid elem segment index $segmentIndex")
        table.initFrom(store[segmentIndex] as Array<out T?>, dst, src, len)
    }

    fun drop(segmentIndex: Int) {
        if (segmentIndex !in store.indices) trap("Invalid elem segment index $segmentIndex")
        store[segmentIndex] = EMPTY
    }

    private companion object {
        val EMPTY = emptyArray<Any?>()
    }
}

@InternalWasm2KtApi
typealias FuncTable = WasmTable<FuncRef>

@InternalWasm2KtApi
typealias ExternTable = WasmTable<Any>

/**
 * Looks up [index] for `call_indirect`, trapping if out of bounds, null (`ref.null`), or not
 * structurally equivalent to [expectedSignature].
 */
@InternalWasm2KtApi
fun WasmTable<FuncRef>.getChecked(index: Int, expectedSignature: String): FuncRef =
    getNonNull(index).requireSignature(expectedSignature)

/** Registers a [HostTrampoline] at [index] in this shared function table. */
@InternalWasm2KtApi
fun WasmTable<FuncRef>.registerHostTrampoline(
    index: Int,
    signature: String,
    trampoline: HostTrampoline,
    owner: Any? = null,
    funcIndex: Int = -1,
): FuncRef.Host = FuncRef.host(signature, trampoline, owner, funcIndex).also { set(index, it) }
