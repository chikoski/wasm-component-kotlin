package wasm2kt.tooling

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * Finds what a decomposed component uses that the Chicory backend cannot run yet, so builds can
 * report it instead of the app failing on the device the first time the feature is used (#39).
 *
 * The runtime fails on the same entries: an `unsupported` trampoline throws when called, an
 * `unsupported` type when a value of it is lifted or lowered. The async ABI is supported (#16).
 */
object PlanCheck {
    /** One human-readable line per problem, in plan order; empty when everything is supported. */
    fun unsupportedFeatures(decomposedDir: Path): List<String> {
        val plan = Json.parseToJsonElement(Files.readString(decomposedDir.resolve("plan.json"))).jsonObject
        val problems = mutableListOf<String>()

        for (e in plan.array("exports")) {
            val o = e.jsonObject
            val name = o.str("instance")?.let { "$it#${o.str("name")}" } ?: o.str("name")
            unsupportedTypes(o["type"]).distinct().forEach { problems += "export '$name' uses a type the Chicory backend does not support: $it" }
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
                    unsupportedTypes(o["type"]).distinct().forEach { problems += "import '$name' uses a type the Chicory backend does not support: $it" }
                }
                "task-return" -> {
                    unsupportedTypes(o["results"]).distinct().forEach { problems += "task.return uses a type the Chicory backend does not support: $it" }
                }
            }
        }
        for (s in plan.array("streamTables")) {
            val o = s.jsonObject
            val idx = o["index"]?.jsonPrimitive?.intOrNull?.toString() ?: "?"
            unsupportedTypes(o["element"]).distinct().forEach { problems += "stream table $idx uses a type the Chicory backend does not support: $it" }
        }
        for (f in plan.array("futureTables")) {
            val o = f.jsonObject
            val idx = o["index"]?.jsonPrimitive?.intOrNull?.toString() ?: "?"
            unsupportedTypes(o["element"]).distinct().forEach { problems += "future table $idx uses a type the Chicory backend does not support: $it" }
        }
        intrinsics.distinct().forEach { problems += "component intrinsic not supported by the Chicory backend: $it" }
        return problems.distinct()
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
