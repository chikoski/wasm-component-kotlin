package wasm2kt.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Implemented by generated WIT composite types (`Record`, `Variant`, `Enum`, `Flags`, `Resource`,
 * `WitOption`, `WitResult`, `TupleN`) to serialize directly to JSON without reflection.
 */
interface WitJsonSerializable {
    fun toWitJson(): JsonElement
}

/**
 * Implemented by generated `<World>.Imports` interfaces to dispatch host import calls from Wasmtime
 * to Kotlin methods without reflection.
 */
interface WitHostImportDispatcher {
    fun dispatchHostImport(importSpec: String, args: JsonArray): JsonElement =
        error("Unknown host import: $importSpec")
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
