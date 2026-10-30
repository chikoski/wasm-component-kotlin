package wasm2kt.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * A live, instantiated component that generated bindings can call into.
 *
 * Values cross this boundary in the same JSON shape the Wasmtime bridge uses (see [FfmJsonCodec]):
 * records are objects keyed by WIT field name, variants are `{"case", "value"}`, options are
 * `{"option": "some"|"none", "value"}`, results are `{"result": "ok"|"err", "value"|"error"}`,
 * flags are a bitmask, and resources are integer handles owned by the backend.
 *
 * Function names follow the Wasmtime bridge as well: a bare export name (`add`), a resource
 * function name searched across exported interfaces (`[method]counter.increment`), or an
 * interface-qualified name (`counter-api#[constructor]counter`).
 *
 * Implementations are not required to be thread-safe; callers serialize access per session.
 */
interface ComponentBackend : AutoCloseable {
    /** Calls an exported function. Zero results map to `JsonNull`, several to a `JsonArray`. */
    fun invoke(functionName: String, args: JsonArray): JsonElement

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
