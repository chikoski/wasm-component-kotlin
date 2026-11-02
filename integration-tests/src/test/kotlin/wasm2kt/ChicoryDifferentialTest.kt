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
                    System.err.println("DIFF ${b.javaClass.simpleName} ${c.fn}(${c.args.joinToString()}): ${e.message}")
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
                "example:roundtrip/env-provider@0.1.0#get-multiplier" -> JsonPrimitive(3)
                "example:roundtrip/env-provider@0.1.0#format-tag" -> JsonPrimitive("${args[0].jsonPrimitive.content}-${args[1].jsonPrimitive.content}")
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
            for (v in listOf(0, 14, -7)) add(Call("example:roundtrip/math-api@0.1.0#scale", JsonPrimitive(v)))
            // Short and unversioned interface names stay accepted while they are unambiguous (ADR-0011).
            add(Call("math-api#scale", JsonPrimitive(3)))
            add(Call("example:roundtrip/math-api#scale", JsonPrimitive(4)))
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
    fun `borrowing and moving the same handle in one call matches Wasmtime`() {
        val calls = listOf(
            Call("[constructor]counter", JsonPrimitive(10)), // handle 1
            // borrow + own of one handle: refused before the call starts; handle 1 stays usable
            Call("[static]counter.merge", JsonPrimitive(1), JsonPrimitive(1)),
            Call("[method]counter.get-value", JsonPrimitive(1)),
            Call("[constructor]counter", JsonPrimitive(5)),
            Call("[method]counter.get-value", JsonPrimitive(1)),
        )
        runBoth("counter", { null }, calls)
        // Matching is not enough (both could fail): the refused call must leave handle 1 usable.
        val backends = listOf(
            WasmtimeBackend(fixture("counter"), null),
            ChicoryComponent.fromDirectory(chicoryPlanDir("counter")).instantiate(null),
        )
        for (b in backends) b.use {
            val one = JsonArray(listOf(JsonPrimitive(1)))
            assertEquals(1, it.invoke("[constructor]counter", JsonArray(listOf(JsonPrimitive(10)))).jsonPrimitive.int)
            kotlin.test.assertFailsWith<IllegalStateException> {
                it.invoke("[static]counter.merge", JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(1))))
            }
            assertEquals(10, it.invoke("[method]counter.get-value", one).jsonPrimitive.int, "${it.javaClass.simpleName} lost the handle")
            it.dropResource(1)
        }
    }

    private val ping = {
        object : WitHostImportDispatcher {
            override fun dispatchHostImport(importSpec: String, args: JsonArray): JsonElement = JsonNull
        }
    }

    private fun u(vararg v: Long) = v.map { JsonPrimitive(it) }.toTypedArray()

    /**
     * Canonical ABI validation (#18): the abi-edge fixture returns raw core values chosen by the
     * test, so each scenario makes the guest hand back something the ABI must reject. Each
     * scenario runs on a fresh instance because a trap poisons the instance (on Wasmtime 48 only
     * a core wasm trap does; see the scenario comments).
     */
    @Test
    fun `canonical ABI traps match Wasmtime`() {
        val mem = 0x10000L
        val scenarios = listOf(
            // char: surrogates and values past U+10FFFF trap
            listOf(Call("char-from", *u(0x41)), Call("char-from", *u(0x10FFFF))),
            listOf(Call("char-from", *u(0xD800))),
            listOf(Call("char-from", *u(0xDFFF))),
            listOf(Call("char-from", *u(0x110000))),
            listOf(Call("char-from", *u(0xFFFF_FFFF))),
            // discriminants out of range trap (flat and in memory)
            listOf(Call("result-from", *u(0)), Call("result-from", *u(1))),
            listOf(Call("result-from", *u(2))),
            listOf(Call("option-from", *u(0)), Call("option-from", *u(1))),
            listOf(Call("option-from", *u(2))),
            listOf(Call("option-from", *u(255))),
            // strings: invalid encodings, bounds and the length limit
            listOf(Call("string-at", *u(0x200, 5)), Call("string-at", *u(0x200, 0)), Call("string-at", *u(mem, 0))),
            listOf(Call("string-at", *u(0x100, 3))),
            listOf(Call("string-at", *u(mem - 1, 2))),
            listOf(Call("string-at", *u(mem + 1, 0))),
            listOf(Call("string-at", *u(0x200, 1L shl 28))),
            listOf(Call("string-at", *u(0xFFFF_FFFF, 2))),
            listOf(Call("utf16-at", *u(0x310, 1)), Call("utf16-at", *u(0x300, 2))),
            listOf(Call("utf16-at", *u(0x310, 2))),
            listOf(Call("utf16-at", *u(0x311, 1))), // misaligned (Wasmtime checks this since 48)
            listOf(Call("utf16-at", *u(mem - 2, 2))),
            // lists: alignment, bounds, and length * size overflow
            listOf(Call("list-at", *u(0x300, 3)), Call("list-at", *u(0x300, 0)), Call("list-at", *u(mem, 0))),
            listOf(Call("list-at", *u(0x302, 1))),
            listOf(Call("list-at", *u(mem - 8, 4))),
            listOf(Call("list-at", *u(0x300, 0x4000_0001))),
            // return pointers: alignment and bounds
            listOf(Call("string-ret", *u(0x40))),
            listOf(Call("string-ret", *u(0x42))),
            listOf(Call("string-ret", *u(mem - 4))),
            // realloc results: misaligned, out of bounds, and leaving the instance during realloc
            listOf(Call("list-len", j("[1,2,3]")), Call("set-mode", *u(0)), Call("list-len", j("[]"))),
            listOf(Call("set-mode", *u(1)), Call("list-len", j("[1,2,3]"))),
            listOf(Call("set-mode", *u(2)), Call("list-len", j("[1,2,3]"))),
            listOf(Call("set-mode", *u(3)), Call("list-len", j("[1,2,3]"))),
            listOf(Call("call-ping")),
            // Whether a trap found while lifting poisons the instance differs: Wasmtime 48 only poisons
            // after a core wasm trap, Chicory after any spec trap (ADR-0007). See
            // CanonicalAbiStrictnessTest for the Chicory side; a core trap is compared here.
            listOf(Call("set-mode", *u(3)), Call("list-len", j("[1,2,3]")), Call("char-from", *u(0x41))),
            // host-side argument errors are not traps and leave the instance usable
            listOf(Call("char-from", JsonPrimitive("x")), Call("char-from", *u(0x41))),
            // integers out of range for the WIT type are rejected, not wrapped (the bridge since #34)
            listOf(Call("char-from", *u(0x1_0000_0041)), Call("char-from", JsonPrimitive(-1)), Call("char-from", *u(0x41))),
            listOf(Call("list-len", j("[4294967296]")), Call("list-len", j("[0, 4294967295]"))),
            listOf(Call("list-len", j("[1,\"x\"]")), Call("list-len", j("[1]"))),
        )
        for (calls in scenarios) runBoth("abi-edge", ping, calls)
    }

    @Test
    fun `host import responses larger than 64 KiB cross the boundary under both backends`() {
        // The FFM bridge used to copy responses into a fixed 64 KiB buffer (#35). merge-new makes two
        // host blobs, merges them and reads the result back through the `blob.read` import, whose
        // JSON response here is about 450 KB.
        fun blobs() = object : WitHostImportDispatcher {
            val data = HashMap<Int, ByteArray>()
            var next = 1
            fun bytes(e: JsonElement) = ByteArray(e.jsonArray.size) { e.jsonArray[it].jsonPrimitive.int.toByte() }
            fun store(b: ByteArray) = next++.also { data[it] = b }
            override fun dispatchHostImport(importSpec: String, args: JsonArray): JsonElement = when (importSpec) {
                "example:host-res/store@0.1.0#[constructor]blob" -> JsonPrimitive(store(bytes(args[0])))
                "example:host-res/store@0.1.0#[static]blob.merge" -> JsonPrimitive(store(data.getValue(args[0].jsonPrimitive.int) + data.getValue(args[1].jsonPrimitive.int)))
                "example:host-res/store@0.1.0#[method]blob.read" -> JsonArray(data.getValue(args[0].jsonPrimitive.int).map { JsonPrimitive(it.toInt() and 0xFF) })
                "example:host-res/store@0.1.0#[resource-drop]blob" -> JsonNull.also { data.remove(args[0].jsonPrimitive.int) }
                else -> error("Unexpected import: $importSpec")
            }
        }
        fun list(n: Int, seed: Int) = JsonArray(List(n) { JsonPrimitive((it * 31 + seed) and 0xFF) })
        runBoth("host-res", ::blobs, listOf(Call("merge-new", list(60_000, 1), list(60_000, 2))))
        WasmtimeBackend(fixture("host-res"), blobs()).use { b ->
            val merged = b.invoke("merge-new", JsonArray(listOf(list(60_000, 1), list(60_000, 2)))).jsonArray
            assertEquals(120_000, merged.size, "the whole response must reach the component")
        }
    }

    @Test
    fun `option payloads shaped like the tagged form and mistyped host values match Wasmtime`() {
        // #36: a bare `some` whose record has `option`/`value` fields is not a tag, and host values
        // must have the JSON type of their WIT type (no `null` or number for a string).
        val flagged = """{"option":"none","value":1,"note":"n"}"""
        runBoth(
            "types",
            { null },
            listOf(
                Call("echo-flagged", j(flagged)),
                Call("echo-flagged", j("""{"option":"some","value":$flagged}""")),
                Call("echo-flagged", j("""{"option":"none"}""")),
                Call("echo-flagged", JsonNull),
                Call("echo-name", JsonPrimitive(5)),
                Call("echo-name", j("""{"option":"some","value":null}""")),
                Call("echo-char", JsonPrimitive(65)),
                Call("echo-blob", j("""{"name":null,"data":[],"tag":null}""")),
                Call("echo-blob", j("""{"name":"ok","data":[1,2],"tag":7}""")),
            ),
        )
        ChicoryComponent.fromDirectory(chicoryPlanDir("types")).instantiate(null).use { c ->
            val result = c.invoke("echo-flagged", JsonArray(listOf(j(flagged)))).jsonObject
            assertEquals("some", result.getValue("option").jsonPrimitive.content)
            assertEquals(j(flagged), result.getValue("value"))
        }
    }

    @Test
    fun `a long error message from a host import is reported in full`() {
        val message = "x".repeat(100_000) + "-end"
        val failing = object : WitHostImportDispatcher {
            override fun dispatchHostImport(importSpec: String, args: JsonArray): JsonElement = throw IllegalStateException(message)
        }
        val e = kotlin.test.assertFailsWith<IllegalStateException> {
            WasmtimeBackend(fixture("roundtrip"), failing).use { b ->
                b.invoke("describe-code", JsonArray(listOf(JsonPrimitive("p"), JsonPrimitive(1))))
            }
        }
        kotlin.test.assertTrue(e.message!!.contains(message), "the host's message was cut short")
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
