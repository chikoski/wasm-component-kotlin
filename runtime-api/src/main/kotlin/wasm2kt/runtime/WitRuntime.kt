package wasm2kt.runtime

/**
 * The host implementation of one imported function: reads the arguments from [args] and writes the
 * results to [results], in the format of [WitWriter] (ADR-0015).
 */
fun interface HostFunction {
    fun call(args: WitReader, results: WitWriter)
}

/**
 * Looks up host imports by key (ADR-0011). Backends look each key up once, when the component first
 * calls the import, and call the [HostFunction] directly afterwards.
 */
interface WitHostImportDispatcher {
    /** The implementation of [key], or null when the host does not provide it. */
    fun hostFunction(key: String): HostFunction?
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
class ComponentTrapException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
