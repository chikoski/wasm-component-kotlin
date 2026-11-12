package wasm2kt.runtime.chicory

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasm2kt.runtime.ComponentBackend
import wasm2kt.runtime.WitReader
import wasm2kt.runtime.WitWriter
import kotlin.test.assertEquals

/**
 * The async ABI on the host boundary (#16, ADR-0018), on the hand-written `async-stream` fixture: a
 * callback-ABI export, stackful exports, and streams and futures in both directions. The Component
 * Model reference tests (ConformanceTest) cover the async ABI inside components.
 */
class AsyncAbiTest {
    private fun open(): ComponentBackend = ChicoryComponent.fromDirectory(TestComponent("async-stream").dir).instantiate(null)

    private fun ComponentBackend.call(name: String, write: WitWriter.() -> Unit): WitReader =
        invoke(resolve(name), WitWriter().apply(write).toReader())

    private fun ComponentBackend.readAll(stream: Int, chunk: Int): List<Int> {
        val values = mutableListOf<Int>()
        while (true) {
            val r = readStream(stream, chunk) ?: return values
            val n = r.length()
            assert(n in 1..chunk) { "read $n values with a chunk of $chunk" }
            repeat(n) { values += r.int() }
            r.requireEnd()
        }
    }

    @Test
    fun `a callback-ABI export yields, then returns`() {
        open().use { c ->
            assertEquals(42, c.call("double") { int(21) }.int())
            assertEquals(14, c.call("double") { int(7) }.int())
        }
    }

    @Test
    fun `the host reads a stream the component writes after returning`() {
        for (chunk in listOf(1, 3, 100)) {
            open().use { c ->
                val stream = c.call("count-up") { int(10) }.int()
                assertEquals((0 until 10).toList(), c.readAll(stream, chunk), "chunk $chunk")
                assertThrows<IllegalArgumentException>("the handle is freed at the end") { c.readStream(stream, chunk) }
            }
        }
    }

    @Test
    fun `an empty stream ends at once`() {
        open().use { c ->
            val stream = c.call("count-up") { int(0) }.int()
            assertEquals(emptyList(), c.readAll(stream, 4))
        }
    }

    @Test
    fun `the component reads a stream the host passes`() {
        open().use { c ->
            for (n in listOf(0, 1, 4, 9)) {
                val values = WitWriter().apply { repeat(n) { int(it + 1) } }
                val stream = c.newStream(values.toByteArray(), n)
                assertEquals((1..n).sum(), c.call("sum") { int(stream) }.int(), "n = $n")
            }
        }
    }

    @Test
    fun `a stream returned by the component can be passed back to it`() {
        open().use { c ->
            val stream = c.call("count-up") { int(5) }.int()
            assertEquals(0 + 1 + 2 + 3 + 4, c.call("sum") { int(stream) }.int())
            assertThrows<IllegalArgumentException>("the host gave the stream away") { c.readStream(stream, 1) }
        }
    }

    @Test
    fun `futures in both directions`() {
        open().use { c ->
            val future = c.call("later") { int(41) }.int()
            assertEquals(42, c.readFuture(future)!!.int())
            val mine = c.newFuture(WitWriter().apply { int(5) }.toByteArray())
            assertEquals(50, c.call("times-ten") { int(mine) }.int())
        }
    }

    @Test
    fun `dropping a stream the host did not read to its end`() {
        open().use { c ->
            val stream = c.call("count-up") { int(10) }.int()
            assertEquals(1, c.readStream(stream, 1)!!.length())
            c.dropReadable(stream)
            assertThrows<IllegalArgumentException> { c.readStream(stream, 1) }
            // The writer saw the drop and the instance stays usable.
            assertEquals(4, c.call("double") { int(2) }.int())
        }
    }
}
