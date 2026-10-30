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
