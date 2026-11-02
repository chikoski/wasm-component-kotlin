package wasm2kt.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Host imports keyed by fully qualified interface names (ADR-0011). */
class LinkerTest {
    @Test
    fun `dispatches by fully qualified key so same short names do not collide`() {
        val linker = Linker()
        linker.define("wasi:http/types@0.2.0#add") { JsonPrimitive("http") }
        linker.define("wasi:filesystem/types@0.2.0#add") { JsonPrimitive("fs") }
        assertEquals(JsonPrimitive("http"), linker.dispatchHostImport("wasi:http/types@0.2.0#add", JsonArray(emptyList())))
        assertEquals(JsonPrimitive("fs"), linker.dispatchHostImport("wasi:filesystem/types@0.2.0#add", JsonArray(emptyList())))
    }

    @Test
    fun `defining a key twice is rejected`() {
        val linker = Linker()
        linker.define("a:b/c#f") { JsonPrimitive(1) }
        assertThrows<IllegalArgumentException> { linker.define("a:b/c#f") { JsonPrimitive(2) } }
    }

    @Test
    fun `unknown keys fail at dispatch`() {
        assertThrows<IllegalStateException> { Linker().dispatchHostImport("a:b/c#f", JsonArray(emptyList())) }
    }

    @Test
    fun `requireDefined lists the missing keys by interface`() {
        val linker = Linker()
        linker.define("a:b/c@1.0.0#f") { JsonPrimitive(1) }
        linker.requireDefined(listOf("a:b/c@1.0.0#f"))
        val e = assertThrows<IllegalArgumentException> {
            linker.requireDefined(listOf("a:b/c@1.0.0#f", "a:b/c@1.0.0#g", "x:y/z#h", "root-fn"))
        }
        val message = e.message!!
        assertTrue("a:b/c@1.0.0 (g)" in message, message)
        assertTrue("x:y/z (h)" in message, message)
        assertTrue("world (root-fn)" in message, message)
    }
}
