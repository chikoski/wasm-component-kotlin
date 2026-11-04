package wasm2kt.runtime

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Host imports keyed by fully qualified interface names (ADR-0011). */
class LinkerTest {
    private fun stringResult(value: String) = HostFunction { _, results -> results.string(value) }

    @Test
    fun `dispatches by fully qualified key so same short names do not collide`() {
        val linker = Linker()
        linker.define("wasi:http/types@0.2.0#add", stringResult("http"))
        linker.define("wasi:filesystem/types@0.2.0#add", stringResult("fs"))
        assertEquals("http", linker.call("wasi:http/types@0.2.0#add", WitWriter()).string())
        assertEquals("fs", linker.call("wasi:filesystem/types@0.2.0#add", WitWriter()).string())
    }

    @Test
    fun `handlers read their arguments and write their results`() {
        val linker = Linker()
        linker.define("a:b/c#sum") { args, results -> results.int(args.int() + args.int()) }
        val results = linker.call("a:b/c#sum", WitWriter().apply { int(40); int(2) })
        assertEquals(42, results.int())
        results.requireEnd()
    }

    @Test
    fun `defining a key twice is rejected`() {
        val linker = Linker()
        linker.define("a:b/c#f", stringResult("1"))
        assertThrows<IllegalArgumentException> { linker.define("a:b/c#f", stringResult("2")) }
    }

    @Test
    fun `unknown keys have no host function`() {
        assertNull(Linker().hostFunction("a:b/c#f"))
        assertThrows<IllegalStateException> { Linker().call("a:b/c#f", WitWriter()) }
    }

    @Test
    fun `requireDefined lists the missing keys by interface`() {
        val linker = Linker()
        linker.define("a:b/c@1.0.0#f", stringResult("1"))
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
