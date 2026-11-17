package wasm2kt.runtime.chicory

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasm2kt.runtime.AsyncHostFunction
import wasm2kt.runtime.ComponentBackend
import wasm2kt.runtime.ComponentExitException
import wasm2kt.runtime.ComponentTrapException
import wasm2kt.runtime.HostFunction
import wasm2kt.runtime.WitHostImportDispatcher
import wasm2kt.runtime.WitReader
import wasm2kt.runtime.WitStreamChunk
import wasm2kt.runtime.WitWriter
import wasm2kt.runtime.witFuture
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

    @Test
    fun `ComponentExitException distinguishes post-exit reuse from an earlier trap`(): Unit = runBlocking {
        // Suspending host import exiting with status 0 (`launchHostImport` -> `importFailed`)
        open("async-import", Imports { throw ComponentExitException(0, "exit 0") }).use { c ->
            val exit = assertThrows<ComponentExitException> { runBlocking { c.call("fetch-plus-one", 1) } }
            assertEquals(0, exit.statusCode)
            val reuse = assertThrows<ComponentTrapException> { runBlocking { c.call("fetch-plus-one", 1) } }
            assertTrue("has already exited" in reuse.message!!, reuse.message)
            assertTrue("earlier trap" !in reuse.message!!, reuse.message)
        }

        // Suspending host import exiting with non-zero status via synchronous invocation
        open("async-import", Imports { throw ComponentExitException(9, "exit 9") }).use { c ->
            val exit = assertThrows<ComponentExitException> {
                c.invoke(c.resolve("blocking-fetch"), WitWriter().apply { int(1) }.toReader())
            }
            assertEquals(9, exit.statusCode)
            val reuse = assertThrows<ComponentTrapException> {
                c.invoke(c.resolve("blocking-fetch"), WitWriter().apply { int(1) }.toReader())
            }
            assertTrue("has already exited" in reuse.message!!, reuse.message)
        }

        // Normal trap keeps "earlier trap" diagnostic and stays trap-poisoned even if an exit import throws later
        var throwExit = false
        val trapImports = Imports {
            if (throwExit) throw ComponentExitException(1) else throw IllegalStateException("no network")
        }
        open("async-import", trapImports).use { c ->
            val trap = assertThrows<ComponentTrapException> { runBlocking { c.call("fetch-plus-one", 1) } }
            assertTrue(trap !is ComponentExitException)
            throwExit = true
            val reuse = assertThrows<ComponentTrapException> { runBlocking { c.call("fetch-plus-one", 1) } }
            assertTrue("earlier trap" in reuse.message!!, reuse.message)
            assertTrue("has already exited" !in reuse.message!!, reuse.message)
        }
    }

    @Test
    fun `a component returning both a stream and a future does not falsely deadlock between stream reads`(): Unit = runBlocking {
        val enricher = object : WitHostImportDispatcher {
            override fun hostFunction(key: String): HostFunction? = null

            override fun asyncHostFunction(key: String): AsyncHostFunction? =
                if (key == "example:async-pipeline/enricher@0.1.0#format-item") {
                    AsyncHostFunction { a, r ->
                        val id = a.int()
                        val payload = a.string()
                        r.string("#$id:${payload.uppercase()}")
                    }
                } else {
                    null
                }
        }
        open("async-pipeline", enricher).use { c ->
            val items = flow {
                for (value in listOf("one", "two", "three", "four")) {
                    emit(WitStreamChunk(1, WitWriter().apply { string(value) }.toByteArray()))
                    delay(1)
                }
            }
            val stream = c.newStream(items)
            val result = c.invokeSuspending(c.resolve("process-items"), WitWriter().apply { int(stream) }.toReader())
            val outHandle = result.int()
            val countHandle = result.int()

            val futureJob = async(Dispatchers.Default) { c.readFutureSuspending(countHandle)!!.int() }
            val readJob = async(Dispatchers.Default) {
                val collected = mutableListOf<String>()
                repeat(2) {
                    val reader = c.readStreamSuspending(outHandle, 1)!!
                    repeat(reader.length()) { collected += reader.string() }
                    delay(10)
                }
                c.dropReadable(outHandle)
                collected
            }

            assertEquals(listOf("#1:ONE", "#2:TWO"), readJob.await())
            assertEquals(2, futureJob.await())

            val sumInput = c.newStream(
                flow {
                    emit(WitStreamChunk(2, WitWriter().apply { int(5); int(10) }.toByteArray()))
                },
            )
            assertEquals(15, c.invokeSuspending(c.resolve("sum-stream"), WitWriter().apply { int(sumInput) }.toReader()).int())
        }
    }

    @Test
    fun `argument lowering failure rolls back lowered stream and future handles`(): Unit = runBlocking {
        open("async-stream").use { c ->
            var collections = 0
            var closed = 0
            val stream = c.newStream(
                flow {
                    check(collections == 0) { "cold stream was collected $collections times before rollback" }
                    collections++
                    emit(WitStreamChunk(2, WitWriter().apply { int(5); int(10) }.toByteArray()))
                },
                AutoCloseable { closed++ },
            )
            assertThrows<IllegalArgumentException> {
                c.invokeSuspending(c.resolve("sum"), WitWriter().apply { int(stream); int(999) }.toReader())
            }
            assertEquals(0, collections)
            assertEquals(0, closed)
            assertEquals(15, c.invokeSuspending(c.resolve("sum"), WitWriter().apply { int(stream) }.toReader()).int())
            assertEquals(1, collections)
            assertEquals(1, closed)

            val future = c.newFuture(WitWriter().apply { int(4) }.toByteArray())
            assertThrows<IllegalArgumentException> {
                c.invokeSuspending(c.resolve("times-ten"), WitWriter().apply { int(future); int(999) }.toReader())
            }
            assertEquals(40, c.invokeSuspending(c.resolve("times-ten"), WitWriter().apply { int(future) }.toReader()).int())

            val guestStream = c.invokeSuspending(c.resolve("count-up"), WitWriter().apply { int(4) }.toReader()).int()
            assertThrows<IllegalArgumentException> {
                c.invokeSuspending(c.resolve("sum"), WitWriter().apply { int(guestStream); int(999) }.toReader())
            }
            assertEquals(6, c.invokeSuspending(c.resolve("sum"), WitWriter().apply { int(guestStream) }.toReader()).int())
        }
    }

    @Test
    fun `cancelling witFuture mid-read drops Chicory readable handle without race`(): Unit = runBlocking {
        val enricher = object : WitHostImportDispatcher {
            override fun hostFunction(key: String): HostFunction? = null

            override fun asyncHostFunction(key: String): AsyncHostFunction? =
                if (key == "example:async-pipeline/enricher@0.1.0#format-item") {
                    AsyncHostFunction { a, r ->
                        val id = a.int()
                        val payload = a.string()
                        r.string("#$id:${payload.uppercase()}")
                    }
                } else {
                    null
                }
        }
        open("async-pipeline", enricher).use { c ->
            val gate = CompletableDeferred<Unit>()
            val items = flow {
                emit(WitStreamChunk(1, WitWriter().apply { string("one") }.toByteArray()))
                gate.await()
            }
            val stream = c.newStream(items)
            val result = c.invokeSuspending(c.resolve("process-items"), WitWriter().apply { int(stream) }.toReader())
            val outHandle = result.int()
            val countHandle = result.int()

            val firstChunk = c.readStreamSuspending(outHandle, 1)!!
            assertEquals(1, firstChunk.length())
            assertEquals("#1:ONE", firstChunk.string())

            val inst = c as ChicoryComponentInstance
            val future = witFuture(c, countHandle) { it.int() }
            val reader = launch(Dispatchers.Default) { future.await() }
            withTimeout(5_000) {
                while (!inst.async.hasActiveHostRead(countHandle)) yield()
            }

            future.cancel()
            reader.join()
            assertTrue(future.isCancelled)
            // witFuture cancellation must already drop the Chicory readable handle.
            assertThrows<IllegalArgumentException> { c.dropReadable(countHandle) }

            c.dropReadable(outHandle)
            gate.complete(Unit)

            val sumInput = c.newStream(
                flowOf(WitStreamChunk(2, WitWriter().apply { int(5); int(10) }.toByteArray())),
            )
            assertEquals(15, c.invokeSuspending(c.resolve("sum-stream"), WitWriter().apply { int(sumInput) }.toReader()).int())
        }
    }

    @Test
    fun `delayed argument lowering failure behind backpressure fails only that call`(): Unit = runBlocking {
        val enteredFirst = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        open("async-import", Imports { x ->
            if (x == 10) {
                enteredFirst.complete(Unit)
                gate.await()
            }
            x
        }).use { c ->
            val inst = c as ChicoryComponentInstance
            supervisorScope {
                val first = async { c.call("fetch-plus-one", 10) }
                withTimeout(5_000) { enteredFirst.await() }

                inst.async.onSession { inst.async.state(0).backpressure = 1 }
                val invalidDelayed = async {
                    c.invokeSuspending(c.resolve("fetch-plus-one"), WitWriter().apply { int(2); int(999) }.toReader())
                }
                val invalidSyncLiftDelayed = async {
                    c.invokeSuspending(c.resolve("fetch-sync-plus-two"), WitWriter().apply { int(3); int(999) }.toReader())
                }
                val validDelayed = async { c.call("fetch-plus-one", 20) }

                withTimeout(5_000) {
                    while (inst.async.onSession { inst.async.state(0).numWaitingToEnter < 3 }) yield()
                }

                inst.async.onSession { inst.async.state(0).backpressure = 0 }
                gate.complete(Unit)

                assertThrows<IllegalArgumentException> { invalidDelayed.await() }
                assertThrows<IllegalArgumentException> { invalidSyncLiftDelayed.await() }
                assertEquals(11, first.await())
                assertEquals(21, validDelayed.await())
            }
            assertEquals(6, c.call("fetch-plus-one", 5))
        }
    }
}
