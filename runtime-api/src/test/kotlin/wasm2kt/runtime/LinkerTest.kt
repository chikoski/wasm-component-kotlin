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

    @Test
    fun `addAll copies handlers without overwrite and stays atomic on collision`() {
        val base = Linker().apply {
            define("a:b/c#first", stringResult("1"))
        }
        val extraAsync = Linker().apply {
            defineAsync("a:b/c#async") { _, results -> results.string("async-ok") }
        }
        base.addAll(extraAsync)
        assertTrue(base.asyncHostFunction("a:b/c#async") != null)
        assertEquals("async-ok", base.call("a:b/c#async", WitWriter()).string())

        val extra = Linker().apply {
            define("a:b/c#second", stringResult("2"))
            define("a:b/c#first", stringResult("collision"))
        }

        assertThrows<IllegalArgumentException> { base.addAll(extra) }
        assertNull(base.hostFunction("a:b/c#second"), "collision must leave receiver unchanged")
        assertEquals("1", base.call("a:b/c#first", WitWriter()).string())
    }

    @Test
    fun `addAll with overwrite replaces sync handlers and clears stale async handlers`() {
        val target = Linker().apply {
            defineAsync("a:b/c#op") { _, results -> results.string("old-async") }
        }
        val syncOverride = Linker().apply {
            define("a:b/c#op", stringResult("new-sync"))
        }

        target.addAll(syncOverride, overwrite = true)
        assertEquals("new-sync", target.call("a:b/c#op", WitWriter()).string())
        assertNull(target.asyncHostFunction("a:b/c#op"), "stale async handler must be removed")

        val asyncOverride = Linker().apply {
            defineAsync("a:b/c#op") { _, results -> results.string("new-async") }
        }
        target.addAll(asyncOverride, overwrite = true)
        assertTrue(target.asyncHostFunction("a:b/c#op") != null)
        assertEquals("new-async", target.call("a:b/c#op", WitWriter()).string())
    }

    @Test
    fun `addAll on self rejects non-empty without overwrite and no-ops with overwrite`() {
        val linker = Linker().apply { define("a:b/c#f", stringResult("v")) }
        assertThrows<IllegalArgumentException> { linker.addAll(linker) }
        linker.addAll(linker, overwrite = true)
        assertEquals("v", linker.call("a:b/c#f", WitWriter()).string())
    }

    @Test
    fun `ImportsFactory plus composes without mutating Linker operands and rejects conflicts`() {
        val sharedLinker = Linker().apply { define("a:b/c#a", stringResult("from-linker")) }
        val extraFactory = ImportsFactory {
            Linker().apply {
                define("a:b/c#b", stringResult("from-factory"))
                defineAsync("a:b/c#async-b") { _, results -> results.string("async-from-factory") }
            }
        }
        val combined = sharedLinker + extraFactory

        val session1 = combined.newLinker()
        val session2 = combined.newLinker()
        assertTrue(session1 !== session2 && session1 !== sharedLinker)
        assertNull(sharedLinker.hostFunction("a:b/c#b"), "plus must not mutate operand Linker")
        assertEquals("from-linker", session1.call("a:b/c#a", WitWriter()).string())
        assertEquals("from-factory", session1.call("a:b/c#b", WitWriter()).string())
        assertTrue(session1.asyncHostFunction("a:b/c#async-b") != null)
        assertEquals("async-from-factory", session1.call("a:b/c#async-b", WitWriter()).string())

        assertEquals("from-linker", (ImportsFactory.EMPTY + sharedLinker).newLinker().call("a:b/c#a", WitWriter()).string())
        assertEquals("from-linker", (sharedLinker + ImportsFactory.EMPTY).newLinker().call("a:b/c#a", WitWriter()).string())

        val colliding = ImportsFactory { Linker().apply { define("a:b/c#a", stringResult("dup")) } }
        assertThrows<IllegalArgumentException> { (sharedLinker + colliding).newLinker() }
    }
}
