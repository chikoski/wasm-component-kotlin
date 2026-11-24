@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.runtime.aot

import wasm2kt.runtime.canonical.ComponentGuard
import wasm2kt.runtime.canonical.InternalWasm2KtApi
import wasm2kt.runtime.canonical.trap as canonicalTrap

/**
 * Trap and poison boundary helpers for compiled AOT core modules per ADR-0007.
 *
 * Wasm traps, integer zero-division [ArithmeticException], and [StackOverflowError] are mapped to
 * [wasm2kt.runtime.ComponentTrapException] and poison the instance; exceptions thrown by host
 * imports preserve their original type while poisoning the instance.
 */
@InternalWasm2KtApi
object AotTrapSupport {
    @JvmStatic
    fun trap(message: String): Nothing = canonicalTrap(message)

    @JvmStatic
    fun unreachable(): Nothing = canonicalTrap("wasm trap: unreachable")

    /** Enters a top-level guest call: checks usability, enforces `may_enter`, and maps traps. */
    inline fun <T> enterGuestCall(guard: ComponentGuard, functionName: String, block: () -> T): T =
        guard.enter(functionName) { guard.guest(block = block) }

    /** Runs nested guest code when already inside an active call (e.g. `cabi_realloc` or destructor). */
    inline fun <T> guestCall(guard: ComponentGuard, block: () -> T): T = guard.guest(block = block)

    inline fun <T> hostImportCall(guard: ComponentGuard, block: () -> T): T = guard.hostImport(block)
}
