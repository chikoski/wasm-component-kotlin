package wasm2kt.runtime.chicory

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasm2kt.runtime.AsyncHostFunction
import wasm2kt.runtime.ComponentBackend
import wasm2kt.runtime.ComponentTrapException
import wasm2kt.runtime.HostFunction
import wasm2kt.runtime.WitHostImportDispatcher
import wasm2kt.runtime.WitReader
import wasm2kt.runtime.WitStreamChunk
import wasm2kt.runtime.WitWriter
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The suspending host boundary of the async ABI (ADR-0019) on the hand-written `async-import` and
 * `async-stream` fixtures: calls that suspend, overlap and can be cancelled, host `async func`
 * imports implemented as suspending functions, and streams the backend collects lazily.
 */
class SuspendingCallsTest {
    /** Host imports answering `fetch` with [fetch]. */
    private class Imports(val fetch: suspend (Int) -> Int) : WitHostImportDispatcher {
        override fun hostFunction(key: String): HostFunction? =
            if (key == "fetch") HostFunction { a, r -> r.int(runBlocking { fetch(a.int()) }) } else null

        override fun asyncHostFunction(key: String): AsyncHostFunction? =
            if (key == "fetch") AsyncHostFunction { a, r -> r.int(fetch(a.int())) } else null
    }

    private fun open(name: String, imports: WitHostImportDispatcher? = null): ComponentBackend =
        ChicoryComponent.fromDirectory(TestComponent(name).dir).instantiate(imports)

    private suspend fun ComponentBackend.call(name: String, x: Int): Int =
        invokeSuspending(resolve(name), WitWriter().apply { int(x) }.toReader()).int()

    @Test
    fun `a suspending host import runs while the component waits for it`(): Unit = runBlocking {
        open("async-import", Imports { x -> delay(10); x * 10 }).use { c ->
            assertEquals(71, c.call("fetch-plus-one", 7))
            assertEquals(72, c.call("fetch-sync-plus-two", 7))
            assertEquals(70, c.call("cancellable", 7))
        }
    }

    @Test
    fun `a non-async export runs a suspending import to completion`() {
        open("async-import", Imports { x -> delay(5); x + 100 }).use { c ->
            // The blocking API on a component that uses the async ABI.
            val r = c.invoke(c.resolve("blocking-fetch"), WitWriter().apply { int(1) }.toReader())
            assertEquals(104, r.int())
        }
    }

    @Test
    fun `calls overlap while the host imports they wait for are suspended`(): Unit = runBlocking {
        val started = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        open("async-import", Imports { x -> started.incrementAndGet(); gate.await(); x }).use { c ->
            val calls = (1..3).map { i -> async { c.call("fetch-plus-one", i) } }
            withTimeout(5_000) { while (started.get() < 3) yield() }
            gate.complete(Unit)
            assertEquals(listOf(2, 3, 4), calls.awaitAll())
        }
    }

    @Test
    fun `cancelling a call cancels the task and the host import it waits for`(): Unit = runBlocking {
        val fetching = CompletableDeferred<Unit>()
        val importCancelled = CompletableDeferred<Unit>()
        var block = true
        open("async-import", Imports { x ->
            if (block) {
                fetching.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    importCancelled.complete(Unit)
                }
            }
            x
        }).use { c ->
            val call = launch { c.call("cancellable", 1) }
            fetching.await()
            call.cancel()
            withTimeout(5_000) { importCancelled.await() }
            // The component handled the cancellation (task.cancel) and stays usable.
            block = false
            assertEquals(5, c.call("cancellable", 5))
        }
    }

    @Test
    fun `a failing host import traps`(): Unit = runBlocking {
        open("async-import", Imports { throw IllegalStateException("no network") }).use { c ->
            val e = assertThrows<ComponentTrapException> { runBlocking { c.call("fetch-plus-one", 1) } }
            assertTrue("no network" in e.message!!, e.message)
            assertThrows<ComponentTrapException> { runBlocking { c.call("fetch-plus-one", 1) } }
        }
    }

    @Test
    fun `a stream from a flow is collected as the component reads it`(): Unit = runBlocking {
        open("async-stream").use { c ->
            val emitted = AtomicInteger()
            val chunks = flow {
                for (i in 1..10) {
                    emitted.incrementAndGet()
                    emit(WitStreamChunk(1, WitWriter().apply { int(i) }.toByteArray()))
                }
            }
            val stream = c.newStream(chunks)
            assertEquals(55, c.invokeSuspending(c.resolve("sum"), WitWriter().apply { int(stream) }.toReader()).int())
            assertEquals(10, emitted.get())
        }
    }

    @Test
    fun `a future from a suspending function resolves when it returns`(): Unit = runBlocking {
        open("async-stream").use { c ->
            val value = CompletableDeferred<Int>()
            val future = c.newFuture { WitWriter().apply { int(value.await()) }.toByteArray() }
            val call = async { c.invokeSuspending(c.resolve("times-ten"), WitWriter().apply { int(future) }.toReader()).int() }
            delay(20)
            assertTrue(call.isActive, "the component waits for the future")
            value.complete(4)
            assertEquals(40, call.await())
        }
    }

    @Test
    fun `reading a stream suspends until the component writes`(): Unit = runBlocking {
        open("async-stream").use { c ->
            val stream = c.invokeSuspending(c.resolve("count-up"), WitWriter().apply { int(6) }.toReader()).int()
            val values = mutableListOf<Int>()
            while (true) {
                val r: WitReader = c.readStreamSuspending(stream, 4) ?: break
                repeat(r.length()) { values += r.int() }
            }
            assertEquals((0 until 6).toList(), values)
        }
    }
}
