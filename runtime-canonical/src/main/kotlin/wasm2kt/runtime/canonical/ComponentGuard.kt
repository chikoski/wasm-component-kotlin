@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.runtime.canonical

import wasm2kt.runtime.ComponentExitException
import wasm2kt.runtime.ComponentTrapException

/** Wraps an error in the host's own values found while running on a guest thread (ADR-0007). */
@InternalWasm2KtApi
class HostValueException(cause: Throwable, val handled: Boolean = false) : RuntimeException(cause)

/**
 * Wraps an exception thrown by a host import during guest execution so [ComponentGuard.guest] can
 * poison the instance and rethrow the original exception type unchanged (ADR-0007).
 */
@InternalWasm2KtApi
class HostImportException(cause: Throwable) : RuntimeException(cause)

/** Records why a component instance is poisoned and whether it came from an intentional exit. */
@InternalWasm2KtApi
class PoisonState(val message: String, val isExit: Boolean)

/**
 * Tracks component instance usability, reentrancy (`may_enter`), leaving (`may_leave`), and trap
 * poisoning per ADR-0007.
 */
@InternalWasm2KtApi
class ComponentGuard {
    var closed: Boolean = false
    var poisoned: PoisonState? = null
    var entered: Boolean = false
    var mayLeave: Boolean = true

    fun checkUsable() {
        check(!closed) { "Component instance is already closed" }
        poisoned?.let { state ->
            if (state.isExit) {
                throw ComponentTrapException("Component instance has already exited (${state.message}); open a new session")
            } else {
                throw ComponentTrapException("Component instance is unusable after an earlier trap (${state.message}); open a new session")
            }
        }
    }

    /** Marks the instance poisoned if not already poisoned; returns true when newly poisoned. */
    fun markPoisoned(message: String, isExit: Boolean = false): Boolean {
        if (poisoned != null) return false
        poisoned = PoisonState(message, isExit)
        return true
    }

    /** Traps and poisons the instance. */
    inline fun poison(message: String, onPoison: () -> Unit = {}): Nothing {
        if (markPoisoned(message)) onPoison()
        throw ComponentTrapException(message)
    }

    inline fun checkMayLeave(what: String, onPoison: () -> Unit = {}) {
        if (!mayLeave) poison("Cannot leave component instance: $what was called during cabi_realloc or post-return", onPoison)
    }

    inline fun <T> withoutLeaving(block: () -> T): T {
        val previous = mayLeave
        mayLeave = false
        try {
            return block()
        } finally {
            mayLeave = previous
        }
    }

    /**
     * Guards entry into a component export: verifies the instance is usable, rejects reentrant
     * calls (`may_enter`), and clears [entered] when [block] completes or throws.
     */
    inline fun <T> enter(
        functionName: String,
        onPoison: () -> Unit = {},
        block: () -> T,
    ): T {
        checkUsable()
        if (entered) {
            poison("Cannot reenter component instance: '$functionName' was called while another call into it is in progress", onPoison)
        }
        entered = true
        return try {
            block()
        } finally {
            entered = false
        }
    }

    /**
     * Wraps a host import invocation so non-trap exceptions thrown by host code preserve their
     * original type when rethrown by [guest] while still poisoning the instance.
     * [VirtualMachineError]s (such as [StackOverflowError]) are not wrapped so [guest] can map
     * them per ADR-0007.
     */
    inline fun <T> hostImport(block: () -> T): T = try {
        block()
    } catch (e: HostValueException) {
        throw e
    } catch (e: HostImportException) {
        throw e
    } catch (e: ComponentTrapException) {
        throw e
    } catch (e: VirtualMachineError) {
        throw e
    } catch (e: Throwable) {
        throw HostImportException(e)
    }

    /**
     * Runs guest code (or validates guest-produced values). Anything thrown poisons the instance,
     * mapping core traps, division-by-zero [ArithmeticException], and [StackOverflowError] to
     * [ComponentTrapException] while preserving host import exception types.
     */
    inline fun <T> guest(
        mapExternalTrap: (Throwable) -> Throwable? = { null },
        onPoison: () -> Unit = {},
        block: () -> T,
    ): T = try {
        block()
    } catch (e: HostValueException) {
        // The host's own arguments were wrong (ADR-0007): not a trap.
        throw e.cause!!
    } catch (e: HostImportException) {
        val original = e.cause!!
        if (markPoisoned(original.message ?: original.javaClass.name, original is ComponentExitException)) {
            onPoison()
        }
        throw original
    } catch (e: Throwable) {
        val surfaced = mapTrap(e, mapExternalTrap)
        if (markPoisoned(surfaced.message ?: surfaced.javaClass.name, surfaced is ComponentExitException)) {
            onPoison()
        }
        throw surfaced
    }

    inline fun mapTrap(e: Throwable, mapExternalTrap: (Throwable) -> Throwable? = { null }): Throwable {
        var c: Throwable? = e
        while (c != null) {
            if (c is ComponentTrapException) return c
            c = c.cause
        }
        mapExternalTrap(e)?.let { return it }
        return when (e) {
            is ArithmeticException -> ComponentTrapException("wasm trap: ${e.message ?: "integer arithmetic trap"}", e)
            is StackOverflowError -> ComponentTrapException("wasm trap: call stack exhausted", e)
            else -> e
        }
    }
}
