package wasm2kt.runtime.chicory

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasm2kt.testing.JsonBackend
import wasm2kt.runtime.ComponentTrapException
import java.nio.file.Paths
import kotlin.test.assertEquals

/**
 * Ownership of component-defined resources across the host boundary (#31), following the spec's
 * `lift_own` (the handle leaves the component's table) and `lower_own` (a fresh handle is made).
 * Uses the hand-written `res-edge` component, which exposes raw handle numbers.
 */
class ResourceOwnershipTest {
    private fun open(): JsonBackend = TestComponent("res-edge").open()

    private fun JsonBackend.call(name: String, vararg args: Int): JsonElement =
        invoke("api#$name", JsonArray(args.map { JsonPrimitive(it) }))

    private fun JsonBackend.int(name: String, vararg args: Int): Int = call(name, *args).jsonPrimitive.int

    @Test
    fun `a handle given to the host leaves the component's table`() {
        open().use { c ->
            c.int("make", 7)
            // The component's handle 1 moved to the host; using it from inside must trap.
            assertThrows<ComponentTrapException> { c.call("rep-raw", 1) }
        }
        open().use { c ->
            c.int("make", 7)
            assertThrows<ComponentTrapException> { c.call("drop-raw", 1) }
        }
    }

    @Test
    fun `the component cannot give the same handle away twice`() {
        open().use { c ->
            c.int("make", 7)
            // Before #31 this created a second host handle for the same resource.
            assertThrows<ComponentTrapException> { c.call("same", 1) }
        }
    }

    @Test
    fun `freed handle numbers are reused without aliasing host handles`() {
        open().use { c ->
            val first = c.int("make", 7)
            val second = c.int("make", 8)
            // Both component handles moved out, so the component holds none; dropping the first
            // host handle must destroy exactly its own resource.
            c.dropResource(first)
            assertEquals(7, c.int("last-dropped"))
            c.dropResource(second)
            assertEquals(8, c.int("last-dropped"))
        }
    }

    @Test
    fun `passing own back gives the component a fresh handle`() {
        open().use { c ->
            val host = c.int("make", 7)
            // make() freed handle 1 when the resource moved out, so the new handle reuses it.
            val handle = c.int("take", host)
            assertEquals(1, handle)
            assertEquals(7, c.int("rep-raw", handle))
            // The host's handle was consumed by the move.
            assertThrows<IllegalStateException> { c.dropResource(host) }
            c.call("drop-raw", handle)
            assertEquals(7, c.int("last-dropped"))
        }
    }

    @Test
    fun `borrowing into the defining component passes the rep`() {
        open().use { c ->
            val host = c.int("make", 42)
            assertEquals(42, c.int("peek", host))
            // A borrow does not move the resource: the host still owns it.
            c.dropResource(host)
            assertEquals(42, c.int("last-dropped"))
        }
    }

    @Test
    fun `a failed argument does not leave a handle behind`() {
        open().use { c ->
            val host = c.int("make", 7)
            // The first argument is lowered (a handle is made for it) before the second, unknown
            // one fails; the call never starts, the instance stays usable and the handle is gone.
            assertThrows<IllegalStateException> { c.call("take2", host, host + 100) }
            assertEquals(1, c.int("take", host))
        }
    }
}
