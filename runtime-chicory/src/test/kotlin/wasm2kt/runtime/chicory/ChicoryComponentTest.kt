package wasm2kt.runtime.chicory

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasm2kt.runtime.WitHostImportDispatcher
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals

class ChicoryComponentTest {
    private fun component(name: String): ChicoryComponent {
        val url = requireNotNull(javaClass.getResource("/components/$name/plan.json")) { "missing test component $name" }
        val dir: Path = Paths.get(url.toURI()).parent
        return ChicoryComponent.fromDirectory(dir)
    }

    private fun args(vararg values: Int) = JsonArray(values.map { JsonPrimitive(it) })

    @Test
    fun `arithmetic exports are callable`() {
        component("arithmetic").instantiate(null).use { c ->
            assertEquals(27, c.invoke("add", args(20, 7)).jsonPrimitive.int)
            assertEquals(42, c.invoke("sub", args(50, 8)).jsonPrimitive.int)
            assertEquals(42, c.invoke("mul", args(-6, -7)).jsonPrimitive.int)
            assertEquals(42, c.invoke("div", args(84, 2)).jsonPrimitive.int)
        }
    }

    @Test
    fun `unknown exports and arity mismatches are reported`() {
        component("arithmetic").instantiate(null).use { c ->
            assertThrows<IllegalArgumentException> { c.invoke("nope", args()) }
            assertThrows<IllegalArgumentException> { c.invoke("add", args(1)) }
        }
    }

    @Test
    fun `host imports receive lifted arguments`() {
        val calls = mutableListOf<Pair<String, List<Int>>>()
        val imports = object : WitHostImportDispatcher {
            override fun dispatchHostImport(importSpec: String, args: JsonArray): JsonElement {
                calls += importSpec to args.map { it.jsonPrimitive.int }
                return JsonNull
            }
        }
        component("live_calc").instantiate(imports).use { c ->
            assertEquals(42, c.invoke("add", args(15, 27)).jsonPrimitive.int)
            assertEquals(42, c.invoke("multiply", args(6, 7)).jsonPrimitive.int)
        }
        assertEquals(listOf("record-calc" to listOf(15, 27), "record-calc" to listOf(6, 7)), calls)
    }

    @Test
    fun `exported resources round-trip through host handles`() {
        component("counter").instantiate(null).use { c ->
            val a = c.invoke("[constructor]counter", args(10)).jsonPrimitive.int
            c.invoke("[method]counter.increment", args(a))
            c.invoke("[method]counter.add", args(a, 5))
            assertEquals(16, c.invoke("[method]counter.get-value", args(a)).jsonPrimitive.int)

            val b = c.invoke("counter-api#[constructor]counter", args(26)).jsonPrimitive.int
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
