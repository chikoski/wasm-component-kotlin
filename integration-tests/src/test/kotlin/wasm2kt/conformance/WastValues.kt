package wasm2kt.conformance

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import wasm2kt.runtime.WitReader
import wasm2kt.runtime.WitWriter
import wasm2kt.testing.JsonWitType

/**
 * Component values as `wasm-tools json-from-wast` writes them (`{"type": "u32", "value": "42"}`,
 * floats as their bits), to and from the binary format (ADR-0015), typed by the component's plan.
 */
object WastValues {
    fun write(v: JsonElement, t: JsonWitType, w: WitWriter) {
        val value = v.jsonObject["value"] ?: JsonNull
        when (t) {
            is JsonWitType.Primitive -> primitive(value, t.name, w)
            is JsonWitType.Record -> {
                val fields = value.jsonArray.associate { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1] }
                t.fields.forEach { (name, ft) -> write(fields[name] ?: error("Missing field '$name' in $v"), ft, w) }
            }
            is JsonWitType.Tuple -> t.types.forEachIndexed { i, et -> write(value.jsonArray[i], et, w) }
            is JsonWitType.ListOf -> {
                w.length(value.jsonArray.size)
                value.jsonArray.forEach { write(it, t.element, w) }
            }
            is JsonWitType.Flags -> {
                val on = value.jsonArray.map { it.jsonPrimitive.content }.toSet()
                val words = IntArray(maxOf(1, (t.names.size + 31) / 32))
                t.names.forEachIndexed { i, name -> if (name in on) words[i / 32] = words[i / 32] or (1 shl (i % 32)) }
                when (t.names.size) {
                    0 -> Unit
                    in 1..8 -> w.byte(words[0])
                    in 9..16 -> w.short(words[0])
                    else -> words.forEach { w.int(it) }
                }
            }
            is JsonWitType.Enum -> discriminant(t.names.size, t.names.indexOf(value.jsonPrimitive.content).also { require(it >= 0) }, w)
            is JsonWitType.Variant -> {
                val o = value.jsonObject
                val i = t.cases.indexOfFirst { it.first == o.getValue("case").jsonPrimitive.content }.also { require(it >= 0) { "Unknown case in $v" } }
                discriminant(t.cases.size, i, w)
                t.cases[i].second?.let { write(o.getValue("payload"), it, w) }
            }
            is JsonWitType.Option -> if (value is JsonNull) w.byte(0) else { w.byte(1); write(value, t.type, w) }
            is JsonWitType.Result -> {
                val o = value.jsonObject
                if ("Ok" in o) { w.byte(0); t.ok?.let { write(o.getValue("Ok"), it, w) } }
                else { w.byte(1); t.err?.let { write(o.getValue("Err"), it, w) } }
            }
            is JsonWitType.Handle -> throw UnsupportedOperationException("Handles cannot be written in a .wast value")
        }
    }

    fun read(t: JsonWitType, r: WitReader): JsonElement = when (t) {
        is JsonWitType.Primitive -> typed(t.name, primitive(t.name, r))
        is JsonWitType.Record -> typed("record", JsonArray(t.fields.map { (name, ft) -> JsonArray(listOf(JsonPrimitive(name), read(ft, r))) }))
        is JsonWitType.Tuple -> typed("tuple", JsonArray(t.types.map { read(it, r) }))
        is JsonWitType.ListOf -> typed("list", JsonArray(List(r.length()) { read(t.element, r) }))
        is JsonWitType.Flags -> {
            val words = when (t.names.size) {
                0 -> intArrayOf(0)
                in 1..8 -> intArrayOf(r.byte().toInt() and 0xFF)
                in 9..16 -> intArrayOf(r.short().toInt() and 0xFFFF)
                else -> IntArray((t.names.size + 31) / 32) { r.int() }
            }
            typed("flags", JsonArray(t.names.filterIndexed { i, _ -> words[i / 32] and (1 shl (i % 32)) != 0 }.map { JsonPrimitive(it) }))
        }
        is JsonWitType.Enum -> typed("enum", JsonPrimitive(t.names[discriminant(t.names.size, r)]))
        is JsonWitType.Variant -> {
            val (name, payload) = t.cases[discriminant(t.cases.size, r)]
            typed("variant", JsonObject(buildMap { put("case", JsonPrimitive(name)); payload?.let { put("payload", read(it, r)) } }))
        }
        is JsonWitType.Option -> typed("option", if (r.bool()) read(t.type, r) else JsonNull)
        is JsonWitType.Result -> typed(
            "result",
            if (r.bool()) JsonObject(mapOf("Err" to (t.err?.let { read(it, r) } ?: JsonNull)))
            else JsonObject(mapOf("Ok" to (t.ok?.let { read(it, r) } ?: JsonNull))),
        )
        is JsonWitType.Handle -> typed(if (t.own) "own" else "borrow", JsonPrimitive(r.int().toString()))
    }

    /** Whether [actual] (from [read]) is the [expected] value; NaN patterns match any NaN. */
    fun matches(expected: JsonElement, actual: JsonElement): Boolean {
        if (expected is JsonNull || actual is JsonNull) return expected is JsonNull && actual is JsonNull
        if (expected is JsonArray && actual is JsonArray) {
            return expected.size == actual.size && expected.indices.all { matches(expected[it], actual[it]) }
        }
        if (expected !is JsonObject || actual !is JsonObject) return expected == actual
        val type = expected["type"]?.jsonPrimitive?.content
        if (type == null) {
            // A variant's {case, payload} or a result's {Ok | Err}.
            val keys = (expected.keys + actual.keys).filter { (expected[it] ?: JsonNull) != JsonNull || (actual[it] ?: JsonNull) != JsonNull }
            return keys.all { matches(expected[it] ?: JsonNull, actual[it] ?: JsonNull) }
        }
        if (type != actual["type"]?.jsonPrimitive?.content) return false
        val e = expected["value"] ?: JsonNull
        val a = actual["value"] ?: JsonNull
        return when (type) {
            "f32" -> floatMatches(e.jsonPrimitive.content, a.jsonPrimitive.content) { java.lang.Float.intBitsToFloat(it.toLong().toInt()).isNaN() }
            "f64" -> floatMatches(e.jsonPrimitive.content, a.jsonPrimitive.content) { java.lang.Double.longBitsToDouble(it.toULong().toLong()).isNaN() }
            "flags" -> e.jsonArray.map { it.jsonPrimitive.content }.toSet() == a.jsonArray.map { it.jsonPrimitive.content }.toSet()
            else -> matches(e, a)
        }
    }

    private fun floatMatches(expected: String, actual: String, isNaN: (String) -> Boolean): Boolean =
        if (expected.startsWith("nan")) isNaN(actual) else expected == actual

    private fun typed(type: String, value: JsonElement) = JsonObject(mapOf("type" to JsonPrimitive(type), "value" to value))

    private fun primitive(v: JsonElement, name: String, w: WitWriter) {
        val p = v.jsonPrimitive
        when (name) {
            "bool" -> w.bool(p.boolean)
            "s8", "u8" -> w.byte(p.content.toLong().toInt())
            "s16", "u16" -> w.short(p.content.toLong().toInt())
            "s32", "u32" -> w.int(p.content.toLong().toInt())
            "s64" -> w.long(p.content.toLong())
            "u64" -> w.long(p.content.toULong().toLong())
            "f32" -> w.int(p.content.toLong().toInt())
            "f64" -> w.long(p.content.toULong().toLong())
            "char" -> w.codePoint(p.content.codePointAt(0))
            "string" -> w.string(p.content)
            else -> throw UnsupportedOperationException("WIT type $name")
        }
    }

    private fun primitive(name: String, r: WitReader): JsonElement = when (name) {
        "bool" -> JsonPrimitive(r.bool())
        "s8" -> JsonPrimitive(r.byte().toString())
        "u8" -> JsonPrimitive((r.byte().toInt() and 0xFF).toString())
        "s16" -> JsonPrimitive(r.short().toString())
        "u16" -> JsonPrimitive((r.short().toInt() and 0xFFFF).toString())
        "s32" -> JsonPrimitive(r.int().toString())
        "u32" -> JsonPrimitive((r.int().toLong() and 0xFFFF_FFFFL).toString())
        "s64" -> JsonPrimitive(r.long().toString())
        "u64" -> JsonPrimitive(r.long().toULong().toString())
        "f32" -> JsonPrimitive((r.int().toLong() and 0xFFFF_FFFFL).toString())
        "f64" -> JsonPrimitive(r.long().toULong().toString())
        "char" -> JsonPrimitive(String(Character.toChars(r.codePoint())))
        "string" -> JsonPrimitive(r.string())
        else -> throw UnsupportedOperationException("WIT type $name")
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
}
