package wasm2kt.runtime

import kotlinx.coroutines.runBlocking

/**
 * Host imports, registered one interface at a time (ADR-0011).
 *
 * Each handler is keyed like the backends dispatch it: `<interface>#<function>` where `<interface>`
 * is the fully qualified import name (`wasi:http/types@0.2.0#[method]fields.get`), or the bare name
 * for world-level functions and inline interfaces. Generated bindings add an extension
 * `fun Linker.add(impl: <Interface>)` in each imported interface's package, and a typed
 * `imports(...)` function per world that builds a complete linker.
 *
 * Define handlers before opening a session; a linker is not safe to modify while a session uses it.
 * A linker is itself an [ImportsFactory] that returns itself, so it can be passed where a world expects
 * its imports; it is then shared by every session opened with it.
 */
class Linker : WitHostImportDispatcher, ImportsFactory {
    private val handlers = LinkedHashMap<String, HostFunction>()
    private val asyncHandlers = HashMap<String, AsyncHostFunction>()

    /** The keys defined so far, in definition order. */
    val keys: Set<String>
        get() = handlers.keys

    /**
     * Defines the handler for [key].
     *
     * @throws IllegalArgumentException if [key] is already defined (e.g. the same interface was added twice).
     */
    fun define(key: String, handler: HostFunction) {
        require(key !in handlers) { "Host import '$key' is already defined" }
        handlers[key] = handler
    }

    /**
     * Defines the suspending handler for [key], an imported `async func` (ADR-0019). A backend
     * without the async ABI calls it as a blocking [HostFunction].
     */
    fun defineAsync(key: String, handler: AsyncHostFunction) {
        define(key) { args, results -> runBlocking { handler.call(args, results) } }
        asyncHandlers[key] = handler
    }

    fun isDefined(key: String): Boolean = key in handlers

    /**
     * Copies all host import handlers from [other] into this linker.
     *
     * When [overwrite] is false (default), any colliding key throws [IllegalArgumentException]
     * without partial mutation. When [overwrite] is true, existing keys replace both synchronous
     * handlers and any stale async handlers from earlier definitions.
     */
    fun addAll(other: Linker, overwrite: Boolean = false): Linker = apply {
        if (other === this) {
            if (!overwrite && handlers.isNotEmpty()) {
                throw IllegalArgumentException("Host import '${handlers.keys.first()}' is already defined")
            }
            return@apply
        }
        if (!overwrite) {
            for (key in other.handlers.keys) {
                require(key !in handlers) { "Host import '$key' is already defined" }
            }
        }
        for ((key, handler) in other.handlers) {
            handlers[key] = handler
            val asyncHandler = other.asyncHandlers[key]
            if (asyncHandler != null) {
                asyncHandlers[key] = asyncHandler
            } else {
                asyncHandlers.remove(key)
            }
        }
    }

    /**
     * Checks that every key in [required] is defined, so a missing host import fails when the session
     * opens rather than when the component first calls it.
     *
     * @throws IllegalArgumentException listing the missing keys grouped by interface.
     */
    fun requireDefined(required: Collection<String>) {
        val missing = required.filterNot { it in handlers }
        if (missing.isEmpty()) return
        val byInterface = missing.groupBy({ key -> key.substringBefore('#', "") }, { key -> key.substringAfter('#') })
        val parts = byInterface.map { (iface, functions) ->
            val owner = if (iface.isEmpty()) "world" else iface
            "$owner (${functions.joinToString()})"
        }
        throw IllegalArgumentException("Host imports are not defined for: ${parts.joinToString("; ")}")
    }

    override fun newLinker(): Linker = this

    override fun hostFunction(key: String): HostFunction? = handlers[key]

    override fun asyncHostFunction(key: String): AsyncHostFunction? = asyncHandlers[key]

    /**
     * Calls the handler of [key] directly, as a backend would (for tests and mocks).
     *
     * @throws IllegalStateException if [key] is not defined.
     */
    fun call(key: String, args: WitWriter): WitReader {
        val handler = handlers[key] ?: throw IllegalStateException("Unknown host import: $key")
        val results = WitWriter()
        handler.call(args.toReader(), results)
        return results.toReader()
    }
}

/**
 * Builds the [Linker] for one session. Generated sessions call it each time they open, so host
 * implementations created inside it are not shared between sessions.
 */
fun interface ImportsFactory {
    fun newLinker(): Linker

    /**
     * Combines this factory and [other] into a new [ImportsFactory]. Each session creates a fresh
     * [Linker] without mutating either operand and rejects colliding keys (ADR-0011, ADR-0022).
     * Per-session isolation of registered handlers depends on each operand [ImportsFactory]
     * allocating per-session state inside [newLinker]; if a [Linker] instance is passed as an
     * operand (whose [newLinker] returns `this`), its handlers remain shared across sessions.
     */
    operator fun plus(other: ImportsFactory): ImportsFactory = ImportsFactory {
        Linker().addAll(this@ImportsFactory.newLinker()).addAll(other.newLinker())
    }

    companion object {
        /** For worlds without imports. */
        @JvmField
        val EMPTY: ImportsFactory = ImportsFactory { Linker() }
    }
}
