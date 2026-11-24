package wasm2kt.runtime.canonical

/**
 * Pure, `kotlinx.serialization`-free classification rules for determining whether a decomposed
 * `plan.json` uses the Component Model async ABI. Shared between `:runtime-chicory` and `:tooling`.
 */
@InternalWasm2KtApi
object PlanAsyncRules {
    /** Trampoline kinds that belong to synchronous component wiring or diagnostics. */
    val SYNC_TRAMPOLINE_KINDS: Set<String> = setOf(
        "lower-import",
        "resource-new",
        "resource-rep",
        "resource-drop",
        "trap",
        "transcode",
        "unsupported",
    )

    /** Intrinsics fused adapters use for synchronous calls too (moving resources between child components). */
    val SYNC_ADAPTER_KINDS: Set<String> = setOf(
        "resource-transfer-own",
        "resource-transfer-borrow",
        "enter-sync-call",
        "exit-sync-call",
    )

    /** Returns true if a trampoline [kind] requires the async ABI scheduler. */
    fun isAsyncTrampolineKind(kind: String): Boolean =
        kind !in SYNC_TRAMPOLINE_KINDS && kind !in SYNC_ADAPTER_KINDS

    /** Single source of truth for whether a component plan uses the async ABI. */
    fun usesAsync(
        trampolineKinds: Iterable<String>,
        hasAsyncOptionOrCallback: Boolean,
        hasAsyncExport: Boolean,
        hasExtractCallback: Boolean,
    ): Boolean =
        hasAsyncOptionOrCallback ||
            hasAsyncExport ||
            hasExtractCallback ||
            trampolineKinds.any(::isAsyncTrampolineKind)
}

/** `pkg:ns/iface@1.0` -> `iface`; a bare name is returned as is. */
@InternalWasm2KtApi
fun shortInterfaceName(qualified: String): String =
    qualified.substringAfter('/', qualified).substringBefore('@')

/** `pkg:ns/iface@1.0` -> `pkg:ns/iface`. */
@InternalWasm2KtApi
fun unversionedInterfaceName(qualified: String): String = qualified.substringBefore('@')
