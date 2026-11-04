package wasm2kt.testing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import wasm2kt.runtime.ComponentBackend
import wasm2kt.runtime.HostFunction
import wasm2kt.runtime.WitHostImportDispatcher
import wasm2kt.runtime.WitReader
import wasm2kt.runtime.WitWriter
import java.nio.file.Files
import java.nio.file.Path

/**
 * A WIT type as a component's `plan.json` writes it (`{"k": "u32"}`, `{"k": "record", "fields": ...}`).
 * Only for tests: generated bindings encode statically (ADR-0015).
 */
sealed interface JsonWitType {
    data class Primitive(val name: String) : JsonWitType
    data class Record(val fields: List<Pair<String, JsonWitType>>) : JsonWitType
    data class Tuple(val types: List<JsonWitType>) : JsonWitType
    data class ListOf(val element: JsonWitType) : JsonWitType
    data class Flags(val names: List<String>) : JsonWitType
    data class Enum(val names: List<String>) : JsonWitType
    data class Variant(val cases: List<Pair<String, JsonWitType?>>) : JsonWitType
    data class Option(val type: JsonWitType) : JsonWitType
    data class Result(val ok: JsonWitType?, val err: JsonWitType?) : JsonWitType
    data class Handle(val own: Boolean) : JsonWitType

    companion object {
        fun parse(o: JsonObject): JsonWitType = when (val k = o.getValue("k").jsonPrimitive.content) {
            "record" -> Record(o.getValue("fields").jsonArray.map { f -> f.jsonObject.let { it.str("name") to parse(it.obj("type")) } })
            "tuple" -> Tuple(o.getValue("types").jsonArray.map { parse(it.jsonObject) })
            "list" -> ListOf(parse(o.obj("element")))
            "flags" -> Flags(o.getValue("names").jsonArray.map { it.jsonPrimitive.content })
            "enum" -> Enum(o.getValue("names").jsonArray.map { it.jsonPrimitive.content })
            "variant" -> Variant(o.getValue("cases").jsonArray.map { c -> c.jsonObject.let { it.str("name") to it.optType("type") } })
            "option" -> Option(parse(o.obj("type")))
            "result" -> Result(o.optType("ok"), o.optType("err"))
            "own" -> Handle(own = true)
            "borrow" -> Handle(own = false)
            else -> Primitive(k)
        }

        private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
        private fun JsonObject.obj(key: String) = getValue(key).jsonObject
        private fun JsonObject.optType(key: String): JsonWitType? = this[key]?.takeUnless { it is JsonNull }?.let { parse(it.jsonObject) }
    }
}

class JsonFuncType(val params: List<JsonWitType>, val results: List<JsonWitType>) {
    companion object {
        fun parse(o: JsonObject) = JsonFuncType(
            o.getValue("params").jsonArray.map { JsonWitType.parse(it.jsonObject.getValue("type").jsonObject) },
            o.getValue("results").jsonArray.map { JsonWitType.parse(it.jsonObject) },
        )
    }
}

/**
 * Converts JSON-written values to and from the binary format (ADR-0015), in the JSON shapes the
 * backends used before it (records as objects, variants as `{"case", "value"}`, options as `null`, a
 * bare value or `{"option": "some" | "none", "value"}`, results as `{"result": "ok" | "err", ...}`,
 * flags as a bitmask or a list of names, u64 as a number or a decimal string).
 */
object JsonWire {
    fun write(v: JsonElement, t: JsonWitType, w: WitWriter) {
        when (t) {
            is JsonWitType.Primitive -> primitive(v, t.name, w)
            is JsonWitType.Record -> {
                val o = v as? JsonObject ?: throw IllegalArgumentException("Expected a record object, got $v")
                for ((name, ft) in t.fields) write(o[name] ?: throw IllegalArgumentException("Missing record field '$name'"), ft, w)
            }
            is JsonWitType.Tuple -> {
                val a = v as? JsonArray ?: throw IllegalArgumentException("Expected a tuple, got $v")
                require(a.size == t.types.size) { "Expected a tuple of ${t.types.size}, got ${a.size} elements" }
                t.types.forEachIndexed { i, et -> write(a[i], et, w) }
            }
            is JsonWitType.ListOf -> {
                val a = v as? JsonArray ?: throw IllegalArgumentException("Expected a list, got $v")
                w.length(a.size)
                a.forEach { write(it, t.element, w) }
            }
            is JsonWitType.Flags -> {
                val bits: Long = if (v is JsonArray) {
                    v.fold(0L) { acc, name ->
                        val i = t.names.indexOf(name.jsonPrimitive.content)
                        require(i >= 0) { "Unknown flag '${name.jsonPrimitive.content}'" }
                        acc or (1L shl i)
                    }
                } else {
                    val b = v.jsonPrimitive.content.toULongOrNull() ?: throw IllegalArgumentException("Expected flags, got $v")
                    require(t.names.size >= 64 || b shr t.names.size == 0uL) { "Flags value $b sets bits beyond the ${t.names.size} declared flags" }
                    b.toLong()
                }
                when (t.names.size) {
                    in 0..0 -> Unit
                    in 1..8 -> w.byte(bits.toInt())
                    in 9..16 -> w.short(bits.toInt())
                    else -> repeat((t.names.size + 31) / 32) { word -> w.int((bits ushr (32 * word)).toInt()) }
                }
            }
            is JsonWitType.Enum -> {
                val name = string(v)
                val i = t.names.indexOf(name).also { require(it >= 0) { "Unknown case '$name'" } }
                discriminant(t.names.size, i, w)
            }
            is JsonWitType.Variant -> {
                val o = v as? JsonObject ?: throw IllegalArgumentException("Expected a variant object, got $v")
                val name = string(o["case"] ?: throw IllegalArgumentException("Missing 'case' in variant $v"))
                val i = t.cases.indexOfFirst { it.first == name }.also { require(it >= 0) { "Unknown case '$name'" } }
                discriminant(t.cases.size, i, w)
                t.cases[i].second?.let { write(o["value"] ?: JsonNull, it, w) }
            }
            is JsonWitType.Option -> when {
                v is JsonNull -> w.byte(0)
                isTaggedOption(v, t.type) -> when ((v as JsonObject).getValue("option").jsonPrimitive.content) {
                    "none" -> w.byte(0)
                    else -> { w.byte(1); write(v["value"] ?: JsonNull, t.type, w) }
                }
                else -> { w.byte(1); write(v, t.type, w) }
            }
            is JsonWitType.Result -> {
                val o = v.jsonObject
                when (val tag = o.getValue("result").jsonPrimitive.content) {
                    "ok" -> { w.byte(0); t.ok?.let { write(o["value"] ?: JsonNull, it, w) } }
                    "err" -> { w.byte(1); t.err?.let { write(o["error"] ?: JsonNull, it, w) } }
                    else -> throw IllegalArgumentException("Invalid result tag '$tag'")
                }
            }
            is JsonWitType.Handle -> w.int(if (v is JsonObject) v.getValue("handle").jsonPrimitive.int else v.jsonPrimitive.int)
        }
    }

    fun read(t: JsonWitType, r: WitReader): JsonElement = when (t) {
        is JsonWitType.Primitive -> when (t.name) {
            "bool" -> JsonPrimitive(r.bool())
            "s8" -> JsonPrimitive(r.byte())
            "u8" -> JsonPrimitive(r.byte().toInt() and 0xFF)
            "s16" -> JsonPrimitive(r.short())
            "u16" -> JsonPrimitive(r.short().toInt() and 0xFFFF)
            "s32" -> JsonPrimitive(r.int())
            "u32" -> JsonPrimitive(r.int().toLong() and 0xFFFF_FFFFL)
            "s64" -> JsonPrimitive(r.long())
            "u64" -> r.long().let { if (it >= 0) JsonPrimitive(it) else JsonPrimitive(it.toULong().toString()) }
            "f32" -> JsonPrimitive(r.float())
            "f64" -> JsonPrimitive(r.double())
            "char" -> JsonPrimitive(String(Character.toChars(r.codePoint())))
            "string" -> JsonPrimitive(r.string())
            else -> throw UnsupportedOperationException("WIT type ${t.name}")
        }
        is JsonWitType.Record -> JsonObject(t.fields.associate { (name, ft) -> name to read(ft, r) })
        is JsonWitType.Tuple -> JsonArray(t.types.map { read(it, r) })
        is JsonWitType.ListOf -> JsonArray(List(r.length()) { read(t.element, r) })
        is JsonWitType.Flags -> {
            val bits: ULong = when (t.names.size) {
                0 -> 0uL
                in 1..8 -> (r.byte().toInt() and 0xFF).toULong()
                in 9..16 -> (r.short().toInt() and 0xFFFF).toULong()
                else -> (0 until (t.names.size + 31) / 32).fold(0uL) { acc, word ->
                    val v = r.int().toULong() and 0xFFFF_FFFFuL
                    if (word < 2) acc or (v shl (32 * word)) else acc
                }
            }
            if (bits <= Long.MAX_VALUE.toULong()) JsonPrimitive(bits.toLong()) else JsonPrimitive(bits.toString())
        }
        is JsonWitType.Enum -> JsonPrimitive(t.names[discriminant(t.names.size, r)])
        is JsonWitType.Variant -> {
            val (name, payload) = t.cases[discriminant(t.cases.size, r)]
            JsonObject(mapOf("case" to JsonPrimitive(name), "value" to (payload?.let { read(it, r) } ?: JsonNull)))
        }
        is JsonWitType.Option ->
            if (r.bool()) JsonObject(mapOf("option" to JsonPrimitive("some"), "value" to read(t.type, r)))
            else JsonObject(mapOf("option" to JsonPrimitive("none")))
        is JsonWitType.Result ->
            if (r.bool()) JsonObject(mapOf("result" to JsonPrimitive("err"), "error" to (t.err?.let { read(it, r) } ?: JsonNull)))
            else JsonObject(mapOf("result" to JsonPrimitive("ok"), "value" to (t.ok?.let { read(it, r) } ?: JsonNull)))
        is JsonWitType.Handle -> JsonPrimitive(r.int())
    }

    /** The arguments as the function's binary arguments; a wrong count is a host error. */
    fun writeArgs(args: JsonArray, type: JsonFuncType): WitWriter {
        require(args.size == type.params.size) { "Expected ${type.params.size} arguments, got ${args.size}" }
        val w = WitWriter()
        args.forEachIndexed { i, a -> write(a, type.params[i], w) }
        return w
    }

    /** Zero results are `null`, one is the value, several a JSON array (the old JSON boundary). */
    fun readResults(type: JsonFuncType, r: WitReader): JsonElement {
        val values = type.results.map { read(it, r) }
        r.requireEnd()
        return when (values.size) {
            0 -> JsonNull
            1 -> values[0]
            else -> JsonArray(values)
        }
    }

    private fun primitive(v: JsonElement, name: String, w: WitWriter) {
        fun p(string: Boolean = false): JsonPrimitive {
            val p = v as? JsonPrimitive
            if (p == null || p is JsonNull || p.isString != string) throw IllegalArgumentException("Expected $name, got $v")
            return p
        }
        fun integer(range: LongRange): Long {
            val n = p().content.toLongOrNull() ?: throw IllegalArgumentException("Expected $name, got $v")
            require(n in range) { "Value $n is out of range for $name" }
            return n
        }
        when (name) {
            "bool" -> w.bool(p().content.toBooleanStrictOrNull() ?: throw IllegalArgumentException("Expected bool, got $v"))
            "s8" -> w.byte(integer(-128L..127L).toInt())
            "u8" -> w.byte(integer(0L..0xFFL).toInt())
            "s16" -> w.short(integer(-32768L..32767L).toInt())
            "u16" -> w.short(integer(0L..0xFFFFL).toInt())
            "s32" -> w.int(integer(Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()).toInt())
            "u32" -> w.int(integer(0L..0xFFFF_FFFFL).toInt())
            "s64" -> w.long(p().content.toLongOrNull() ?: throw IllegalArgumentException("Expected s64, got $v"))
            "u64" -> {
                val q = v as? JsonPrimitive
                if (q == null || q is JsonNull) throw IllegalArgumentException("Expected u64, got $v")
                w.long((q.content.toULongOrNull() ?: throw IllegalArgumentException("Expected u64, got $v")).toLong())
            }
            "f32" -> w.float(p().content.toFloatOrNull() ?: throw IllegalArgumentException("Expected f32, got $v"))
            "f64" -> w.double(p().content.toDoubleOrNull() ?: throw IllegalArgumentException("Expected f64, got $v"))
            "char" -> {
                val s = p(string = true).content
                require(s.isNotEmpty() && s.codePointCount(0, s.length) == 1) { "Expected a single char, got \"$s\"" }
                w.codePoint(s.codePointAt(0))
            }
            "string" -> w.string(p(string = true).content)
            else -> throw UnsupportedOperationException("WIT type $name")
        }
    }

    private fun string(v: JsonElement): String {
        val p = v as? JsonPrimitive
        if (p == null || !p.isString) throw IllegalArgumentException("Expected string, got $v")
        return p.content
    }

    private fun discriminant(count: Int, index: Int, w: WitWriter) = when {
        count <= 256 -> w.byte(index)
        count <= 65536 -> w.short(index)
        else -> w.int(index)
    }

    private fun discriminant(count: Int, r: WitReader): Int {
        val d = when {
            count <= 256 -> r.byte().toInt() and 0xFF
            count <= 65536 -> r.short().toInt() and 0xFFFF
            else -> r.int()
        }
        check(d in 0 until count) { "Discriminant $d out of range for $count cases" }
        return d
    }

    private fun isTaggedOption(v: JsonElement, payload: JsonWitType): Boolean {
        if (v !is JsonObject) return false
        val tag = v["option"] as? JsonPrimitive ?: return false
        if (!tag.isString || (tag.content != "some" && tag.content != "none")) return false
        if (!setOf("option", "value").containsAll(v.keys)) return false
        val fields = (payload as? JsonWitType.Record)?.fields?.map { it.first } ?: return true
        return !setOf("option", "value").containsAll(fields)
    }
}

/**
 * The function types of a component, from its decomposed `plan.json`, to drive a backend with
 * JSON-written values in tests. Function names are matched like the backends do (ADR-0011): the
 * first match wins, so an ambiguous name still reaches the backend, which rejects it.
 */
class PlanTypes(planJson: String) {
    private class Export(val instance: String?, val name: String, val type: JsonFuncType)

    private val exports: List<Export>
    private val imports = HashMap<String, JsonFuncType>()

    init {
        val plan = Json.parseToJsonElement(planJson).jsonObject
        exports = plan.getValue("exports").jsonArray.map { e ->
            val o = e.jsonObject
            Export(o["instance"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content, o.getValue("name").jsonPrimitive.content, JsonFuncType.parse(o.getValue("type").jsonObject))
        }
        val importKeys = plan.getValue("imports").jsonArray.associate { i ->
            val o = i.jsonObject
            val name = o.getValue("name").jsonPrimitive.content
            val path = o.getValue("path").jsonArray.map { it.jsonPrimitive.content }
            o.getValue("index").jsonPrimitive.int to (if (path.isEmpty()) name else "$name#${path.joinToString("#")}")
        }
        for (t in plan.getValue("trampolines").jsonArray) {
            val o = t.jsonObject
            if (o.getValue("kind").jsonPrimitive.content != "lower-import") continue
            importKeys[o.getValue("import").jsonPrimitive.int]?.let { imports[it] = JsonFuncType.parse(o.getValue("type").jsonObject) }
        }
    }

    fun exportType(spec: String): JsonFuncType? {
        val hash = spec.indexOf('#')
        val match = if (hash >= 0) {
            val iface = spec.substring(0, hash)
            val fn = spec.substring(hash + 1)
            exports.firstOrNull { it.instance == iface && it.name == fn }
                ?: exports.firstOrNull { e -> e.name == fn && e.instance != null && e.instance.substringBefore('@').let { it == iface || it.substringAfter('/') == iface } }
        } else {
            exports.firstOrNull { it.instance == null && it.name == spec } ?: exports.firstOrNull { it.name == spec }
        }
        return match?.type
    }

    /** A host import's type; `[resource-drop]` keys take the rep. */
    fun importType(key: String): JsonFuncType? =
        imports[key] ?: if ("[resource-drop]" in key) JsonFuncType(listOf(JsonWitType.Primitive("u32")), emptyList()) else null

    companion object {
        fun fromDirectory(dir: Path) = PlanTypes(String(Files.readAllBytes(dir.resolve("plan.json")), Charsets.UTF_8))
    }
}

/** Calls [functionName] with JSON-written arguments and returns the results as JSON (tests only). */
fun ComponentBackend.invokeJson(types: PlanTypes, functionName: String, args: JsonArray): JsonElement {
    val index = resolve(functionName)
    val type = types.exportType(functionName) ?: throw IllegalArgumentException("No type for '$functionName' in the plan")
    return JsonWire.readResults(type, invoke(index, JsonWire.writeArgs(args, type).toReader()))
}

/** Host imports answered by [handler] with JSON-written values, typed by the plan (tests only). */
class JsonHostImports(private val types: PlanTypes, private val handler: (key: String, args: JsonArray) -> JsonElement) : WitHostImportDispatcher {
    override fun hostFunction(key: String): HostFunction? {
        val type = types.importType(key) ?: return null
        return HostFunction { args, results ->
            val values = JsonArray(type.params.map { JsonWire.read(it, args) })
            args.requireEnd()
            val response = handler(key, values)
            val list = when (type.results.size) {
                0 -> emptyList()
                1 -> listOf(response)
                else -> response.jsonArray
            }
            list.forEachIndexed { i, v -> JsonWire.write(v, type.results[i], results) }
        }
    }
}

/** A backend driven with JSON-written values, typed by the component's plan (tests only). */
class JsonBackend(val backend: ComponentBackend, val types: PlanTypes) : AutoCloseable {
    fun invoke(functionName: String, args: JsonArray): JsonElement = backend.invokeJson(types, functionName, args)

    fun dropResource(handle: Int) = backend.dropResource(handle)

    override fun close() = backend.close()
}
