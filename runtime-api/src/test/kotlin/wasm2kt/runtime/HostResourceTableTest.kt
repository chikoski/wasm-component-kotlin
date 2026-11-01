package wasm2kt.runtime

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Ownership rules of [HostResourceTable] (#37): the host can only move or lend what it still owns. */
class HostResourceTableTest {
    private class Res : WitHostResource {
        var closed = 0
        override fun close() {
            closed++
        }
    }

    @Test
    fun `an object moved to the component cannot be moved again`() {
        HostResourceTable().use { table ->
            val r = Res()
            table.bind { table.lower(r) }
            val e = assertThrows<IllegalStateException> { table.bind { table.lower(r) } }
            assertTrue("already moved" in e.message!!)
        }
    }

    @Test
    fun `an object cannot be moved twice in one call`() {
        HostResourceTable().use { table ->
            val r = Res()
            table.bind {
                table.lower(r)
                assertThrows<IllegalStateException> { table.lower(r) }
            }
        }
    }

    @Test
    fun `an object moved to the component cannot be lent`() {
        HostResourceTable().use { table ->
            val r = Res()
            table.bind { table.lower(r) }
            assertThrows<IllegalStateException> { table.bind { table.lend(r) } }
        }
    }

    @Test
    fun `an object lent to a call in progress cannot be moved`() {
        HostResourceTable().use { table ->
            val r = Res()
            table.bind {
                table.lend(r)
                assertThrows<IllegalStateException> { table.lower(r) }
            }
            // The lend ended with the call, so the host owns it again and may move it.
            table.bind { table.lower(r) }
        }
    }

    @Test
    fun `lending the same object twice in a call is allowed`() {
        HostResourceTable().use { table ->
            val r = Res()
            table.bind { assertEquals(table.lend(r), table.lend(r)) }
            assertEquals(0, table.size)
        }
    }

    @Test
    fun `an object handed back by the component can be moved again`() {
        HostResourceTable().use { table ->
            val r = Res()
            val rep = table.bind { table.lower(r) }
            assertSame(r, table.take(rep, Res::class.java))
            table.bind { table.lower(r) }
            assertEquals(1, table.size)
        }
    }

    @Test
    fun `dropping closes the object once and forgets it`() {
        val r = Res()
        HostResourceTable().use { table ->
            val rep = table.bind { table.lower(r) }
            table.drop(rep)
            assertEquals(1, r.closed)
            assertThrows<IllegalStateException> { table.drop(rep) }
        }
        assertEquals(1, r.closed)
    }
}
