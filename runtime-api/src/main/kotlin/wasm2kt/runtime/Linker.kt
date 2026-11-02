package wasm2kt.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

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
    private val handlers = LinkedHashMap<String, (JsonArray) -> JsonElement>()

    /** The keys defined so far, in definition order. */
    val keys: Set<String>
        get() = handlers.keys

    /**
     * Defines the handler for [key].
     *
     * @throws IllegalArgumentException if [key] is already defined (e.g. the same interface was added twice).
     */
    fun define(key: String, handler: (JsonArray) -> JsonElement) {
        require(key !in handlers) { "Host import '$key' is already defined" }
        handlers[key] = handler
    }

    fun isDefined(key: String): Boolean = key in handlers

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

    override fun dispatchHostImport(importSpec: String, args: JsonArray): JsonElement {
        val handler = handlers[importSpec] ?: error("Unknown host import: $importSpec")
        return handler(args)
    }
}

/**
 * Builds the [Linker] for one session. Generated sessions call it each time they open, so host
 * implementations created inside it are not shared between sessions.
 */
fun interface ImportsFactory {
    fun newLinker(): Linker

    companion object {
        /** For worlds without imports. */
        @JvmField
        val EMPTY: ImportsFactory = ImportsFactory { Linker() }
    }
}
