@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.tooling

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import wasm2kt.runtime.canonical.InternalWasm2KtApi
import wasm2kt.runtime.canonical.PlanAsyncRules
import java.nio.file.Files
import java.nio.file.Path

/**
 * Finds what a decomposed component uses that the runtime backends cannot run yet, so builds can
 * report it instead of the app failing on the device the first time the feature is used (#39).
 *
 * Phase 1 of the AOT backend shares these rules because [wasm2kt.tooling.aot.AotComponentCodegen]
 * lowers the same entries into `unsupportedTrampoline` calls at run time; async rejection for AOT
 * is checked separately via [usesAsync].
 *
 * The runtime fails on the same entries: an `unsupported` trampoline throws when called, an
 * `unsupported` type when a value of it is lifted or lowered. The async ABI is supported (#16) on
 * the Chicory backend.
 */
object PlanCheck {
    /** One human-readable line per problem, in plan order; empty when everything is supported. */
    fun unsupportedFeatures(decomposedDir: Path): List<String> {
        val plan = Json.parseToJsonElement(Files.readString(decomposedDir.resolve("plan.json"))).jsonObject
        val problems = mutableListOf<String>()

        for (e in plan.array("exports")) {
            val o = e.jsonObject
            val name = o.str("instance")?.let { "$it#${o.str("name")}" } ?: o.str("name")
            unsupportedTypes(o["type"]).distinct().forEach { problems += "export '$name' uses a type not supported by this backend: $it" }
        }

        val imports = plan.array("imports").associate { i ->
            val o = i.jsonObject
            o.getValue("index").jsonPrimitive.int to (listOfNotNull(o.str("name")) + (o["path"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList())).joinToString("#")
        }
        val intrinsics = mutableListOf<String>()
        for (t in plan.array("trampolines")) {
            val o = t.jsonObject
            when (o.str("kind")) {
                "unsupported" -> intrinsics += o.str("detail") ?: "unknown"
                "lower-import" -> {
                    val name = imports[o["import"]?.jsonPrimitive?.intOrNull] ?: "import"
                    unsupportedTypes(o["type"]).distinct().forEach { problems += "import '$name' uses a type not supported by this backend: $it" }
                }
                "task-return" -> {
                    unsupportedTypes(o["results"]).distinct().forEach { problems += "task.return uses a type not supported by this backend: $it" }
                }
            }
        }
        for (s in plan.array("streamTables")) {
            val o = s.jsonObject
            val idx = o["index"]?.jsonPrimitive?.intOrNull?.toString() ?: "?"
            unsupportedTypes(o["element"]).distinct().forEach { problems += "stream table $idx uses a type not supported by this backend: $it" }
        }
        for (f in plan.array("futureTables")) {
            val o = f.jsonObject
            val idx = o["index"]?.jsonPrimitive?.intOrNull?.toString() ?: "?"
            unsupportedTypes(o["element"]).distinct().forEach { problems += "future table $idx uses a type not supported by this backend: $it" }
        }
        intrinsics.distinct().forEach { problems += "component intrinsic not supported by this backend: $it" }
        return problems.distinct()
    }

    /** Returns whether the decomposed component in [decomposedDir] uses the async ABI. */
    fun usesAsync(decomposedDir: Path): Boolean =
        usesAsync(Json.parseToJsonElement(Files.readString(decomposedDir.resolve("plan.json"))).jsonObject)

    /** Returns whether [plan] uses the async ABI using the single canonical rule in [PlanAsyncRules]. */
    fun usesAsync(plan: JsonObject): Boolean {
        val trampolineKinds = plan.array("trampolines").mapNotNull { it.jsonObject.str("kind") }
        val hasAsyncOptionOrCallback = plan.array("options").any { elem ->
            val o = elem.jsonObject
            val isAsync = (o["async"] as? JsonPrimitive)?.booleanOrNull == true
            val hasCallback = o["callback"]?.let { it !is JsonNull } == true
            isAsync || hasCallback
        }
        val hasAsyncExport = plan.array("exports").any { elem ->
            val type = elem.jsonObject["type"] as? JsonObject
            (type?.get("async") as? JsonPrimitive)?.content == "true"
        }
        val hasExtractCallback = plan.array("initializers").any { elem ->
            elem.jsonObject.str("op") == "extract-callback"
        }
        return PlanAsyncRules.usesAsync(
            trampolineKinds = trampolineKinds,
            hasAsyncOptionOrCallback = hasAsyncOptionOrCallback,
            hasAsyncExport = hasAsyncExport,
            hasExtractCallback = hasExtractCallback,
        )
    }

    /** Details of every `{"k": "unsupported"}` inside a plan type. */
    private fun unsupportedTypes(e: JsonElement?): List<String> = when (e) {
        is JsonObject ->
            if ((e["k"] as? JsonPrimitive)?.content == "unsupported") listOf((e["detail"] as? JsonPrimitive)?.content ?: "unknown type")
            else e.values.flatMap { unsupportedTypes(it) }
        is JsonArray -> e.flatMap { unsupportedTypes(it) }
        else -> emptyList()
    }

    private fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray) ?: emptyList()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
