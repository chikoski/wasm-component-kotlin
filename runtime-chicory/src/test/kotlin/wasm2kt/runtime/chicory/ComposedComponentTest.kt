package wasm2kt.runtime.chicory

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasm2kt.testing.JsonBackend
import wasm2kt.runtime.ComponentTrapException
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Components whose child components have their own memories and pass strings and lists to each
 * other (#47): the fused adapters import both memories and copy strings through transcoders.
 * Uses the generated `composed-str` fixture; ChicoryDifferentialTest compares it with Wasmtime.
 */
class ComposedComponentTest {
    private fun open(): JsonBackend = TestComponent("composed-str").open()

    private fun JsonBackend.call(name: String, vararg args: JsonElement): JsonElement = invoke(name, JsonArray(args.toList()))

    private val encodings = listOf("utf8", "utf16", "compact")

    /** ASCII, Latin-1 only, BMP beyond Latin-1, a surrogate pair, mixed, empty and long strings. */
    private val samples = listOf(
        "", "a", "hello", "é ü ÿ", "日本語", "🎌", "a🎌é日",
        "x".repeat(5000), ("éa" + "日🎌").repeat(700), "é".repeat(3000) + "日",
    )

    @Test
    fun `strings cross between children with every pair of encodings`() {
        open().use { c ->
            for (caller in encodings) for (callee in encodings) for (s in samples) {
                val echoed = c.call("echo-$caller-$callee", JsonPrimitive(s)).jsonPrimitive.content
                assertEquals(s, echoed, "echo-$caller-$callee changed a string of ${s.length} chars")
            }
        }
    }

    @Test
    fun `lists are copied between the children's memories`() {
        open().use { c ->
            val items = (0 until 1000).map { JsonPrimitive(it * 7) }
            assertEquals((0 until 1000).sumOf { it * 7 }, c.call("sum", JsonArray(items)).jsonPrimitive.int)
            assertEquals(items.map { it.int }, c.call("echo-list", JsonArray(items)).jsonArray.map { it.jsonPrimitive.int })
            assertEquals(0, c.call("sum", JsonArray(emptyList())).jsonPrimitive.int)
        }
    }

    @Test
    fun `an invalid string from the callee traps and poisons the instance`() {
        for (bad in listOf("utf8", "utf16")) for (caller in encodings) {
            open().use { c ->
                val e = assertThrows<ComponentTrapException>("bad-$bad-via-$caller") { c.call("bad-$bad-via-$caller") }
                assertTrue("invalid $bad encoding" in e.message!!, "bad-$bad-via-$caller: ${e.message}")
                assertThrows<ComponentTrapException> { c.call("echo-utf8-utf8", JsonPrimitive("x")) }
            }
        }
    }
}
