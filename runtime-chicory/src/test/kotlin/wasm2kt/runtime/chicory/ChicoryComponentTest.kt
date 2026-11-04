package wasm2kt.runtime.chicory

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals

class ChicoryComponentTest {
    private fun component(name: String) = TestComponent(name)

    private fun args(vararg values: Int) = JsonArray(values.map { JsonPrimitive(it) })

    @Test
    fun `arithmetic exports are callable`() {
        component("arithmetic").open().use { c ->
            assertEquals(27, c.invoke("add", args(20, 7)).jsonPrimitive.int)
            assertEquals(42, c.invoke("sub", args(50, 8)).jsonPrimitive.int)
            assertEquals(42, c.invoke("mul", args(-6, -7)).jsonPrimitive.int)
            assertEquals(42, c.invoke("div", args(84, 2)).jsonPrimitive.int)
        }
    }

    @Test
    fun `unknown exports and arity mismatches are reported`() {
        component("arithmetic").open().use { c ->
            assertThrows<IllegalArgumentException> { c.invoke("nope", args()) }
            assertThrows<IllegalArgumentException> { c.invoke("add", args(1)) }
        }
    }

    @Test
    fun `host imports receive lifted arguments`() {
        val calls = mutableListOf<Pair<String, List<Int>>>()
        val imports: (String, JsonArray) -> JsonElement = { importSpec, args ->
            calls += importSpec to args.map { it.jsonPrimitive.int }
            JsonNull
        }
        component("live_calc").open(imports).use { c ->
            assertEquals(42, c.invoke("add", args(15, 27)).jsonPrimitive.int)
            assertEquals(42, c.invoke("multiply", args(6, 7)).jsonPrimitive.int)
        }
        assertEquals(listOf("record-calc" to listOf(15, 27), "record-calc" to listOf(6, 7)), calls)
    }

    @Test
    fun `exported resources round-trip through host handles`() {
        component("counter").open().use { c ->
            val a = c.invoke("[constructor]counter", args(10)).jsonPrimitive.int
            c.invoke("[method]counter.increment", args(a))
            c.invoke("[method]counter.add", args(a, 5))
            assertEquals(16, c.invoke("[method]counter.get-value", args(a)).jsonPrimitive.int)

            val b = c.invoke("example:counter/counter-api@0.1.0#[constructor]counter", args(26)).jsonPrimitive.int
            // Short and unversioned interface names are accepted while they match one export (ADR-0011).
            c.invoke("counter-api#[method]counter.increment", args(b))
            c.invoke("example:counter/counter-api#[method]counter.add", args(b, -1))
            val merged = c.invoke("[static]counter.merge", args(a, b)).jsonPrimitive.int
            assertEquals(42, c.invoke("[method]counter.get-value", args(merged)).jsonPrimitive.int)

            // `b` was moved into merge (own<counter>) and can no longer be used by the host.
            assertThrows<IllegalStateException> { c.invoke("[method]counter.get-value", args(b)) }
            c.dropResource(merged)
            c.dropResource(a)
            assertThrows<IllegalStateException> { c.dropResource(a) }
        }
    }
}
