@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.runtime.canonical

import wasm2kt.runtime.AsyncHostFunction
import wasm2kt.runtime.ComponentBackend
import wasm2kt.runtime.HostFunction
import kotlin.coroutines.CoroutineContext

@InternalWasm2KtApi
enum class AbiBoundaryDirection {
    HOST_TO_GUEST,
    GUEST_TO_HOST,
}

@InternalWasm2KtApi
fun interface AsyncCoreInstance {
    fun callRef(funcIndex: Int, args: LongArray): LongArray
}

@InternalWasm2KtApi
fun interface AsyncTable {
    /**
     * Validates table element [index] as a `(param i32)`/`()` thread start function and returns a
     * runner invoking it with [closure]. Traps cleanly with `thread.new-indirect` diagnostics.
     */
    fun newThreadStart(index: Int, closure: Long): () -> Unit
}

@InternalWasm2KtApi
class AsyncCanonOptions(
    val stringEncoding: StringEncoding,
    val memoryIndex: Int?,
    val memory: () -> CanonicalMemory?,
    val realloc: ((align: Int, size: Int) -> Int)?,
    val postReturn: ((LongArray) -> Unit)?,
    val callback: ((LongArray) -> LongArray)?,
    val async: Boolean,
    val instance: Int = 0,
)

@InternalWasm2KtApi
class AsyncResourceTableInfo(
    val imported: Boolean,
    val instance: Int,
    val definedInInstance: Int,
)

@InternalWasm2KtApi
class AsyncTransmitTableInfo(
    val instance: Int,
    val element: WitType?,
)

@InternalWasm2KtApi
class AsyncExportSpec(
    val qualifiedName: String,
    val type: FuncType,
    val optionsIndex: Int,
    val callee: (LongArray) -> LongArray,
)

@InternalWasm2KtApi
class AsyncTrampolineSpec(
    val kind: String,
    val instance: Int? = null,
    val table: Int? = null,
    val options: Int? = null,
    val async: Boolean = false,
    val cancellable: Boolean = false,
    val results: List<WitType>? = null,
    val resultsIndex: Int? = null,
    val memory: Int? = null,
    val callback: Int? = null,
    val postReturn: Int? = null,
)

@InternalWasm2KtApi
interface AsyncAbiContext : AbiContext {
    fun commitMoves()
    fun rollbackLowering()
    fun releaseLends()
    fun checkBorrowsDropped()
}

@InternalWasm2KtApi
class UnsupportedAsyncIntrinsicException(val kind: String) :
    UnsupportedOperationException("Component intrinsic not supported by this backend: $kind")

/**
 * Backend-neutral host contract required by [AsyncAbi] so `:runtime-chicory` and `:runtime-aot`
 * share one Component Model async ABI implementation (ADR-0023 Phase 3).
 */
@InternalWasm2KtApi
interface AsyncBackendHost {
    val backend: ComponentBackend
    val guard: ComponentGuard

    fun checkUsable()
    fun poison(message: String): Nothing
    fun <T> guest(block: () -> T): T
    fun asTrap(e: Throwable): Throwable
    fun <T> withoutLeaving(block: () -> T): T
    fun checkMayLeave(what: String)
    fun <T> mapUnsupported(block: () -> T): T
    fun <T> bind(block: () -> T): T
    fun asContextElement(): CoroutineContext
    fun context(
        optionsIndex: Int,
        direction: AbiBoundaryDirection,
        borrowScope: (() -> BorrowScope)? = null,
    ): AsyncAbiContext

    fun resourceTable(tableIndex: Int): ResourceTable
    fun resourceTableInfo(tableIndex: Int): AsyncResourceTableInfo?
    fun transmitTableInfo(tableIndex: Int, stream: Boolean): AsyncTransmitTableInfo
    fun errorContextInstance(tableIndex: Int): Int
    fun canonOptions(optionsIndex: Int): AsyncCanonOptions
    fun memory(memoryIndex: Int): CanonicalMemory
    fun callback(callbackIndex: Int): (LongArray) -> LongArray
    fun postReturn(postReturnIndex: Int): (LongArray) -> Unit
    fun table(tableIndex: Int): AsyncTable?
    fun importDispatchKey(importIndex: Int): String
    fun hostImport(importIndex: Int): HostFunction
    fun asyncHostImport(importIndex: Int): AsyncHostFunction?
}
