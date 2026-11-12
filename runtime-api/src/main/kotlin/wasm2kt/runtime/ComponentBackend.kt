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

    // Streams and futures (#16, ADR-0018). In the binary format they are `u32` handles: the host's
    // readable end of one the component returned, or one the host created with [newStream] /
    // [newFuture] to pass to the component. Only the Chicory backend implements them.

    /**
     * Reads up to [maxCount] values from the readable end [handle] of a `stream` the component
     * returned, running the component until it writes some or drops its end. Returns the values as
     * a `list` in the binary format, or null once the stream has ended (and then frees [handle]).
     */
    fun readStream(handle: Int, maxCount: Int): WitReader? = throw UnsupportedOperationException("This backend does not support streams")

    /** Reads the value of the `future` [handle] the component returned (and frees it); null if its writer was dropped. */
    fun readFuture(handle: Int): WitReader? = throw UnsupportedOperationException("This backend does not support futures")

    /** Drops the readable end [handle] of a stream or future the component returned, without reading the rest. */
    fun dropReadable(handle: Int): Unit = throw UnsupportedOperationException("This backend does not support streams or futures")

    /**
     * A `stream` for the host to pass to the component that yields the [count] values in [values]
     * (a `list` of them in the binary format, without the count), then ends.
     */
    fun newStream(values: ByteArray, count: Int): Int = throw UnsupportedOperationException("This backend does not support streams")

    /** A `future` for the host to pass to the component, already resolved to [value] (in the binary format). */
    fun newFuture(value: ByteArray): Int = throw UnsupportedOperationException("This backend does not support futures")
}

/**
 * Something that can instantiate a [ComponentBackend], e.g. a component whose core modules were
 * compiled at build time. Host imports are dispatched through [imports].
 */
fun interface ComponentBackendFactory {
    fun instantiate(imports: WitHostImportDispatcher?): ComponentBackend
}
