package wasm2kt.runtime

/**
 * The host implementation of one imported function: reads the arguments from [args] and writes the
 * results to [results], in the format of [WitWriter] (ADR-0015).
 */
fun interface HostFunction {
    fun call(args: WitReader, results: WitWriter)
}

/**
 * The host implementation of an imported `async func` (ADR-0019): like [HostFunction], but it may
 * suspend. The component's other tasks run meanwhile; cancelling the call (`subtask.cancel`)
 * cancels the coroutine.
 */
fun interface AsyncHostFunction {
    suspend fun call(args: WitReader, results: WitWriter)
}

/**
 * Looks up host imports by key (ADR-0011). Backends look each key up once, when the component first
 * calls the import, and call the [HostFunction] directly afterwards.
 */
interface WitHostImportDispatcher {
    /** The implementation of [key], or null when the host does not provide it. */
    fun hostFunction(key: String): HostFunction?

    /**
     * The suspending implementation of [key], an imported `async func`, or null. Backends that
     * support the async ABI prefer it to [hostFunction]; others call [hostFunction].
     */
    fun asyncHostFunction(key: String): AsyncHostFunction? = null
}

/**
 * Shared contract implemented by every generated `WitResult` interface so modules such as
 * `runtime-wasi` can inspect results from any generated binding package.
 */
interface WitResultValue<out T, out E> {
    /** Whether this result is `ok`. */
    val isOk: Boolean

    /** Whether this result is `err`. */
    val isErr: Boolean

    /** The `ok` value, or `null` for `err`. */
    fun getOrNull(): T?

    /** The `err` value, or `null` for `ok`. */
    fun errorOrNull(): E?

    /** The `ok` value; throws [WitResultException] carrying the `err` value otherwise. */
    fun getOrThrow(): T
}

/** Thrown by the generated `WitResult.getOrThrow()` for an `err`; [error] is the WIT error value. */
class WitResultException(val error: Any?) : RuntimeException("WIT result error: $error")

/**
 * A Component Model trap: the guest broke a Canonical ABI rule (an invalid `char`, discriminant or
 * string encoding, an out-of-bounds or misaligned pointer, leaving or re-entering the instance at a
 * forbidden time) or a core wasm instruction trapped.
 *
 * A trap poisons the component instance: every later call on it fails with this exception too, so
 * open a new session to continue. Errors in the host's own arguments (a value out of range, an
 * unknown handle) are reported as [IllegalArgumentException] / [IllegalStateException] instead and
 * leave the instance usable.
 *
 * Extends [IllegalStateException] so that callers written before this type existed keep working.
 * Thrown by the Chicory backend; the Wasmtime backend reports traps as [IllegalStateException].
 */
open class ComponentTrapException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Thrown when a component exits intentionally via a runtime-defined exit import such as
 * `wasi:cli/exit`.
 *
 * Extends [ComponentTrapException] so callers catching traps still observe termination and cannot
 * reuse the instance, while distinguishing an intentional exit (including status `0`) from a trap.
 * Preserved by the Chicory backend and direct host calls; the Wasmtime native bridge currently
 * surfaces guest traps and host import failures as [IllegalStateException] (and WASI 0.3 async
 * exports target Chicory per ADR-0005 and ADR-0018).
 */
open class ComponentExitException(
    val statusCode: Int,
    message: String = "Component exited with status code $statusCode",
    cause: Throwable? = null,
) : ComponentTrapException(message, cause)
