package wasm2kt.runtime

/**
 * A live, instantiated component that generated bindings can call into.
 *
 * Values cross this boundary in the binary format of [WitWriter] / [WitReader] (ADR-0015), the same
 * format the Wasmtime bridges use. Function names follow ADR-0011: the fully qualified name
 * (`wasi:http/types@0.2.0#[method]fields.get`), the name of a world-level export, or a shorter name
 * when it is unambiguous.
 *
 * Implementations are not required to be thread-safe; callers serialize access per session.
 */
interface ComponentBackend : AutoCloseable {
    /**
     * The index of an exported function, to pass to [invoke]. Resolve each name once per instance.
     *
     * @throws IllegalArgumentException if no export matches, or the name is ambiguous.
     */
    fun resolve(functionName: String): Int

    /**
     * Calls the export [function] with its arguments in [args] and returns a reader over its
     * results (nothing for a function without results).
     */
    fun invoke(function: Int, args: WitReader): WitReader

    /** Drops a resource handle previously returned to the host, running its destructor. */
    fun dropResource(handle: Int)
}

/**
 * Something that can instantiate a [ComponentBackend], e.g. a component whose core modules were
 * compiled at build time. Host imports are dispatched through [imports].
 */
fun interface ComponentBackendFactory {
    fun instantiate(imports: WitHostImportDispatcher?): ComponentBackend
}
