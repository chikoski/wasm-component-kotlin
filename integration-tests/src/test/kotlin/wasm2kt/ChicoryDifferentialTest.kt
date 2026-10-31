package wasm2kt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import wasm2kt.runtime.ComponentBackend
import wasm2kt.runtime.FfmWasmtimeBridge
import wasm2kt.runtime.WitHostImportDispatcher
import wasm2kt.runtime.chicory.ChicoryComponent
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals

/**
 * Runs identical call sequences against the Wasmtime bridge (the reference implementation) and the
 * Chicory backend, and requires identical results — including errors — at the JSON boundary.
 */
class ChicoryDifferentialTest {
    private fun fixture(name: String): Path = Paths.get(requireNotNull(javaClass.getResource("/fixtures/$name.wasm")).toURI())

    private fun chicoryPlanDir(name: String): Path =
        Paths.get("runtime-chicory/src/test/resources/components/$name").toAbsolutePath()

    /** Adapts a Wasmtime FFM bridge session to [ComponentBackend] so both can be driven alike. */
    private class WasmtimeBackend(wasm: Path, imports: WitHostImportDispatcher?) : ComponentBackend {
        private val bridge = FfmWasmtimeBridge.getDefault()
        private val session = bridge.openSession(wasm, imports)

        override fun invoke(functionName: String, args: JsonArray): JsonElement =
            unwrap(bridge.sessionInvoke(session, functionName, args.toString()))

        override fun dropResource(handle: Int) {
            unwrap(bridge.sessionDropResource(session, handle))
        }

        override fun close() = bridge.sessionClose(session)

        private fun unwrap(resp: String): JsonElement {
            val o = Json.parseToJsonElement(resp).jsonObject
            if (!o.getValue("ok").jsonPrimitive.boolean) throw IllegalStateException(o["error"]?.jsonPrimitive?.content)
            return o["value"] ?: JsonNull
        }
    }

    private class Call(val fn: String, vararg val args: JsonElement)

    private fun runBoth(name: String, imports: () -> WitHostImportDispatcher?, calls: List<Call>) {
        fun run(backend: ComponentBackend): List<String> = backend.use { b ->
            calls.map { c ->
                try {
                    normalize(b.invoke(c.fn, JsonArray(c.args.toList()))).toString()
                } catch (e: Exception) {
                    "error"
                }
            }
        }
        val expected = run(WasmtimeBackend(fixture(name), imports()))
        val actual = run(ChicoryComponent.fromDirectory(chicoryPlanDir(name)).instantiate(imports()))
        calls.forEachIndexed { i, c ->
            assertEquals(expected[i], actual[i], "Mismatch for ${c.fn}(${c.args.joinToString()})")
        }
    }

    /** Compare semantically: key order and number-vs-string spelling of large u64s may differ. */
    private fun normalize(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { normalize(it.value) }.toSortedMap())
        is JsonArray -> JsonArray(e.map { normalize(it) })
        is JsonPrimitive -> if (e is JsonNull) e else JsonPrimitive(e.content)
    }

    private fun j(text: String): JsonElement = Json.parseToJsonElement(text)

    private val envProvider = {
        object : WitHostImportDispatcher {
            override fun dispatchHostImport(importSpec: String, args: JsonArray): JsonElement = when (importSpec) {
                "env-provider#get-multiplier" -> JsonPrimitive(3)
                "env-provider#format-tag" -> JsonPrimitive("${args[0].jsonPrimitive.content}-${args[1].jsonPrimitive.content}")
                else -> error("Unexpected import: $importSpec")
            }
        }
    }

    @Test
    fun `roundtrip of records, flags, enums, variants, options, results, u64 and strings matches Wasmtime`() {
        val summaries = listOf(
            """{"count":0,"perms":0,"prio":"low"}""",
            """{"count":7,"perms":5,"prio":"medium"}""",
            """{"count":4294967295,"perms":7,"prio":"high"}""",
        )
        val actions = listOf(
            """{"case":"reset"}""",
            """{"case":"add-count","value":10}""",
            """{"case":"set-priority","value":"high"}""",
        )
        val calls = buildList {
            for (s in summaries) for (a in actions) add(Call("transform-summary", j(s), j(a)))
            for (v in listOf(0, 1, -2, -3, Int.MAX_VALUE, Int.MIN_VALUE)) add(Call("check-even", JsonPrimitive(v)))
            add(Call("ping-result", JsonPrimitive(true)))
            add(Call("ping-result", JsonPrimitive(false)))
            for (o in listOf("""{"option":"none"}""", """{"option":"some","value":{"option":"none"}}""", """{"option":"some","value":{"option":"some","value":-5}}""", "null")) {
                add(Call("nested-option", j(o)))
            }
            for (u in listOf("0", "1", "9223372036854775807", "9223372036854775813", "18446744073709551615")) add(Call("echo-u64", JsonPrimitive(u)))
            for ((p, c) in listOf("tag" to 0L, "" to 42L, "日本語🎌" to 4294967295L)) add(Call("describe-code", JsonPrimitive(p), JsonPrimitive(c)))
            for (v in listOf(0, 14, -7)) add(Call("math-api#scale", JsonPrimitive(v)))
            add(Call("scale", JsonPrimitive(5)))
            add(Call("check-even", JsonPrimitive(1), JsonPrimitive(2))) // arity error on both sides
        }
        runBoth("roundtrip", envProvider, calls)
    }

    @Test
    fun `resource lifecycle matches Wasmtime`() {
        // Both backends number host handles from 1 in creation order, so handles line up.
        val calls = listOf(
            Call("[constructor]counter", JsonPrimitive(10)), // handle 1
            Call("[method]counter.increment", JsonPrimitive(1)),
            Call("[method]counter.add", JsonPrimitive(1), JsonPrimitive(-3)),
            Call("[method]counter.get-value", JsonPrimitive(1)),
            Call("[constructor]counter", JsonPrimitive(100)), // handle 2
            Call("[static]counter.merge", JsonPrimitive(1), JsonPrimitive(2)), // consumes 2, returns 3
            Call("[method]counter.get-value", JsonPrimitive(3)),
            Call("[method]counter.get-value", JsonPrimitive(2)), // moved: error
            Call("[method]counter.get-value", JsonPrimitive(1)),
        )
        runBoth("counter", { null }, calls)
    }

    @Test
    fun `host imports see the same arguments under both backends`() {
        fun recorder(log: MutableList<String>) = object : WitHostImportDispatcher {
            override fun dispatchHostImport(importSpec: String, args: JsonArray): JsonElement {
                log += "$importSpec(${args.joinToString { it.jsonPrimitive.int.toString() }})"
                return JsonNull
            }
        }
        val wasmtimeLog = mutableListOf<String>()
        val chicoryLog = mutableListOf<String>()
        val inputs = listOf(15 to 27, 6 to 7, Int.MAX_VALUE to 1)
        WasmtimeBackend(fixture("live_calc"), recorder(wasmtimeLog)).use { b ->
            inputs.forEach { (a, c) -> b.invoke("add", JsonArray(listOf(JsonPrimitive(a), JsonPrimitive(c)))) }
        }
        ChicoryComponent.fromDirectory(chicoryPlanDir("live_calc")).instantiate(recorder(chicoryLog)).use { b ->
            inputs.forEach { (a, c) -> b.invoke("add", JsonArray(listOf(JsonPrimitive(a), JsonPrimitive(c)))) }
        }
        assertEquals(wasmtimeLog, chicoryLog)
        assertEquals(3, chicoryLog.size)
    }
}
