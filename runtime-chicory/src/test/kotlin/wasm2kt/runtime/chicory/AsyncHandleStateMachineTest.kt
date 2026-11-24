@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.runtime.chicory

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
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
import wasm2kt.runtime.WitStreamChunk
import wasm2kt.runtime.WitWriter
import wasm2kt.runtime.canonical.InternalWasm2KtApi
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * State-machine property tests for async ABI handle rollback, host/guest error separation, and
 * poison transitions (#160, #131, ADR-0007).
 */
class AsyncHandleStateMachineTest {
    private fun openBackend(name: String, imports: WitHostImportDispatcher? = null): ComponentBackend =
        ChicoryComponent.fromDirectory(TestComponent(name).dir).instantiate(imports)

    @Test
    fun `state machine tests stream and future rollback across seeds without closing or losing handles`() = runBlocking {
        for (seed in 1..20) {
            val rng = Random(seed * 7919 + 3)
            openBackend("async-stream").use { c ->
                repeat(6) { opIdx ->
                    val rollbackAttempts = rng.nextInt(1, 4)
                    when (rng.nextInt(4)) {
                        0 -> {
                            val collections = AtomicInteger()
                            val closed = AtomicInteger()
                            val values = listOf(seed + opIdx, 10, 20)
                            val handle = c.newStream(
                                flow {
                                    check(collections.get() == 0) { "rolled-back cold flow was collected early" }
                                    collections.incrementAndGet()
                                    for (v in values) {
                                        emit(WitStreamChunk(1, WitWriter().apply { int(v) }.toByteArray()))
                                    }
                                },
                                AutoCloseable { closed.incrementAndGet() },
                            )
                            repeat(rollbackAttempts) {
                                assertThrows<IllegalArgumentException> {
                                    c.invokeSuspending(c.resolve("sum"), WitWriter().apply { int(handle); int(999) }.toReader())
                                }
                                assertEquals(0, collections.get(), "seed=$seed rollback collected stream")
                                assertEquals(0, closed.get(), "seed=$seed rollback closed stream")
                            }
                            val result = c.invokeSuspending(c.resolve("sum"), WitWriter().apply { int(handle) }.toReader()).int()
                            assertEquals(values.sum(), result)
                            assertEquals(1, collections.get())
                            assertEquals(1, closed.get())
                        }
                        1 -> {
                            val evaluated = AtomicInteger()
                            val base = seed * 10 + opIdx
                            val handle = c.newFuture {
                                evaluated.incrementAndGet()
                                WitWriter().apply { int(base) }.toByteArray()
                            }
                            repeat(rollbackAttempts) {
                                assertThrows<IllegalArgumentException> {
                                    c.invokeSuspending(c.resolve("times-ten"), WitWriter().apply { int(handle); int(-1) }.toReader())
                                }
                                assertEquals(0, evaluated.get(), "seed=$seed rollback evaluated lazy future")
                            }
                            val result = c.invokeSuspending(c.resolve("times-ten"), WitWriter().apply { int(handle) }.toReader()).int()
                            assertEquals(base * 10, result)
                            assertEquals(1, evaluated.get())
                        }
                        2 -> {
                            val n = rng.nextInt(1, 6)
                            val guestStream = c.invokeSuspending(c.resolve("count-up"), WitWriter().apply { int(n) }.toReader()).int()
                            repeat(rollbackAttempts) {
                                assertThrows<IllegalArgumentException> {
                                    c.invokeSuspending(c.resolve("sum"), WitWriter().apply { int(guestStream); int(123) }.toReader())
                                }
                            }
                            val result = c.invokeSuspending(c.resolve("sum"), WitWriter().apply { int(guestStream) }.toReader()).int()
                            assertEquals((0 until n).sum(), result)
                        }
                        3 -> {
                            val base = rng.nextInt(1, 50)
                            val guestFuture = c.invokeSuspending(c.resolve("later"), WitWriter().apply { int(base) }.toReader()).int()
                            repeat(rollbackAttempts) {
                                assertThrows<IllegalArgumentException> {
                                    c.invokeSuspending(c.resolve("times-ten"), WitWriter().apply { int(guestFuture); int(77) }.toReader())
                                }
                            }
                            val result = c.invokeSuspending(c.resolve("times-ten"), WitWriter().apply { int(guestFuture) }.toReader()).int()
                            assertEquals((base + 1) * 10, result)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `delayed lowering rollback under backpressure preserves streams and futures without disturbing active calls`() = runBlocking {
        for (seed in 1..8) {
            openBackend("async-stream").use { c ->
                val inst = c as ChicoryComponentInstance
                val gate = CompletableDeferred<Unit>()
                val slowCollected = CompletableDeferred<Unit>()
                val slowStream = c.newStream(
                    flow {
                        slowCollected.complete(Unit)
                        gate.await()
                        emit(WitStreamChunk(2, WitWriter().apply { int(seed); int(40) }.toByteArray()))
                    },
                )
                val invalidStreamCollections = AtomicInteger()
                val invalidStreamClosed = AtomicInteger()
                val invalidStream = c.newStream(
                    flow {
                        invalidStreamCollections.incrementAndGet()
                        emit(WitStreamChunk(2, WitWriter().apply { int(7); int(8) }.toByteArray()))
                    },
                    AutoCloseable { invalidStreamClosed.incrementAndGet() },
                )
                val invalidFutureEvaluated = AtomicInteger()
                val invalidFuture = c.newFuture {
                    invalidFutureEvaluated.incrementAndGet()
                    WitWriter().apply { int(6) }.toByteArray()
                }
                val validDelayedStream = c.newStream(WitWriter().apply { int(10); int(20) }.toByteArray(), 2)

                supervisorScope {
                    val activeCall = async {
                        c.invokeSuspending(c.resolve("sum"), WitWriter().apply { int(slowStream) }.toReader()).int()
                    }
                    withTimeout(5_000) { slowCollected.await() }

                    inst.async.onSession { inst.async.state(0).backpressure = 1 }
                    val badStreamCall = async {
                        c.invokeSuspending(c.resolve("sum"), WitWriter().apply { int(invalidStream); int(999) }.toReader())
                    }
                    val badFutureCall = async {
                        c.invokeSuspending(c.resolve("times-ten"), WitWriter().apply { int(invalidFuture); int(999) }.toReader())
                    }
                    val goodQueuedCall = async {
                        c.invokeSuspending(c.resolve("sum"), WitWriter().apply { int(validDelayedStream) }.toReader()).int()
                    }

                    try {
                        withTimeout(5_000) {
                            while (inst.async.onSession { inst.async.state(0).numWaitingToEnter < 3 }) yield()
                        }
                    } finally {
                        inst.async.onSession { inst.async.state(0).backpressure = 0 }
                        gate.complete(Unit)
                    }

                    assertThrows<IllegalArgumentException> { badStreamCall.await() }
                    assertThrows<IllegalArgumentException> { badFutureCall.await() }
                    assertEquals(seed + 40, activeCall.await())
                    assertEquals(30, goodQueuedCall.await())
                }

                assertEquals(0, invalidStreamCollections.get())
                assertEquals(0, invalidStreamClosed.get())
                assertEquals(0, invalidFutureEvaluated.get())

                // Rolled-back handles remain intact and succeed on valid calls.
                assertEquals(15, c.invokeSuspending(c.resolve("sum"), WitWriter().apply { int(invalidStream) }.toReader()).int())
                assertEquals(1, invalidStreamCollections.get())
                assertEquals(1, invalidStreamClosed.get())
                assertEquals(60, c.invokeSuspending(c.resolve("times-ten"), WitWriter().apply { int(invalidFuture) }.toReader()).int())
                assertEquals(1, invalidFutureEvaluated.get())
            }
        }
    }

    @Test
    fun `error-context and mixed async handle rollback removes guest handles and keeps host objects reusable`() = runBlocking {
        openBackend("async-stream").use { c ->
            val inst = c as ChicoryComponentInstance
            for (seed in 1..20) {
                val rng = Random(seed * 131 + 11)
                val streamCollected = AtomicInteger()
                val streamClosed = AtomicInteger()
                val streamId = c.newStream(
                    flow {
                        streamCollected.incrementAndGet()
                        emit(WitStreamChunk(1, WitWriter().apply { int(seed) }.toByteArray()))
                    },
                    AutoCloseable { streamClosed.incrementAndGet() },
                )
                val futureId = c.newFuture(WitWriter().apply { int(seed + 5) }.toByteArray())
                val errorMessage = "diagnostic-$seed"

                val errorId = inst.async.onSession {
                    val state = inst.async.state(0)
                    val guestHandle = state.handles.add(ErrorContext(errorMessage))
                    inst.async.liftErrorContextToHost(0, guestHandle)
                }

                val kindOrder = listOf(0, 1, 2).shuffled(rng)
                val sumExport = inst.plan.exports.first { it.name == "sum" }
                val opts = inst.plan.options.getValue(sumExport.options)

                inst.async.onSession {
                    val cx = inst.context(opts, ChicoryComponentInstance.Direction.HOST_TO_GUEST)
                    val lowered = mutableListOf<Pair<Int, Int>>()
                    for (kind in kindOrder) {
                        val h = when (kind) {
                            0 -> cx.lowerStream(0, streamId)
                            1 -> cx.lowerFuture(0, futureId)
                            else -> cx.lowerErrorContext(errorId)
                        }
                        lowered += kind to h
                    }
                    cx.rollbackLowering()

                    val handles = inst.async.state(0).handles
                    for ((_, h) in lowered) {
                        assertNull(handles.getOrNull(h), "seed=$seed handle $h was not rolled back")
                    }
                }

                assertEquals(0, streamCollected.get())
                assertEquals(0, streamClosed.get())

                // Re-lowering the rolled-back error-context succeeds and preserves debugMessage.
                val preservedMsg = inst.async.onSession {
                    val cx2 = inst.context(opts, ChicoryComponentInstance.Direction.HOST_TO_GUEST)
                    val ecHandle2 = cx2.lowerErrorContext(errorId)
                    cx2.commitMoves()
                    val obj = inst.async.state(0).handles.remove(ecHandle2) as ErrorContext
                    obj.debugMessage
                }
                assertEquals(errorMessage, preservedMsg)

                // The rolled-back stream and future also remain usable in real calls.
                assertEquals(seed, c.invokeSuspending(c.resolve("sum"), WitWriter().apply { int(streamId) }.toReader()).int())
                assertEquals(1, streamCollected.get())
                assertEquals(1, streamClosed.get())
                assertEquals((seed + 5) * 10, c.invokeSuspending(c.resolve("times-ten"), WitWriter().apply { int(futureId) }.toReader()).int())
            }
        }
    }

    @Test
    fun `poison transition is irreversible on guest traps while host errors keep instance usable`() = runBlocking {
        // 1. Synchronous component: host errors keep instance usable; guest trap poisons irreversibly.
        for (seed in 1..15) {
            val rng = Random(seed * 419)
            openBackend("abi-edge").use { c ->
                repeat(10) {
                    if (rng.nextBoolean()) {
                        val code = if (rng.nextBoolean()) 0x41 else 0x1F38C
                        assertEquals(code, c.invoke(c.resolve("char-from"), WitWriter().apply { int(code) }.toReader()).int())
                    } else {
                        assertThrows<IllegalArgumentException> {
                            c.invoke(c.resolve("char-from"), WitWriter().apply { int(0x41); int(1) }.toReader())
                        }
                    }
                }
                // Trigger guest trap by returning surrogate 0xD800 as WIT char.
                assertThrows<ComponentTrapException> {
                    c.invoke(c.resolve("char-from"), WitWriter().apply { int(0xD800) }.toReader())
                }
                // Instance remains irreversibly poisoned for every subsequent call.
                repeat(3) {
                    val after = assertThrows<ComponentTrapException> {
                        c.invoke(c.resolve("char-from"), WitWriter().apply { int(0x41) }.toReader())
                    }
                    assertTrue("earlier trap" in after.message!! && "unusable" in after.message!!, after.message)
                }
            }
        }

        // 2. Async component: concurrent host errors never poison; guest/import failure or exit poisons all endpoints.
        for (mode in listOf("trap", "exit")) {
            val shouldFail = AtomicBoolean(false)
            val imports = object : WitHostImportDispatcher {
                override fun hostFunction(key: String): HostFunction? = null
                override fun asyncHostFunction(key: String): AsyncHostFunction? =
                    if (key == "fetch") {
                        AsyncHostFunction { a, r ->
                            val x = a.int()
                            if (shouldFail.get()) {
                                if (mode == "exit") throw ComponentExitException(7, "done")
                                else throw IllegalStateException("import exploded")
                            }
                            r.int(x)
                        }
                    } else {
                        null
                    }
            }
            openBackend("async-import", imports).use { c ->
                supervisorScope {
                    val ok = async {
                        c.invokeSuspending(c.resolve("fetch-plus-one"), WitWriter().apply { int(10) }.toReader()).int()
                    }
                    val bad = async {
                        c.invokeSuspending(c.resolve("fetch-plus-one"), WitWriter().apply { int(10); int(99) }.toReader())
                    }
                    assertThrows<IllegalArgumentException> { bad.await() }
                    assertEquals(11, ok.await())
                }
                assertEquals(21, c.invokeSuspending(c.resolve("fetch-plus-one"), WitWriter().apply { int(20) }.toReader()).int())

                shouldFail.set(true)
                if (mode == "exit") {
                    val exit = assertThrows<ComponentExitException> {
                        c.invokeSuspending(c.resolve("fetch-plus-one"), WitWriter().apply { int(1) }.toReader())
                    }
                    assertEquals(7, exit.statusCode)
                } else {
                    assertThrows<ComponentTrapException> {
                        c.invokeSuspending(c.resolve("fetch-plus-one"), WitWriter().apply { int(1) }.toReader())
                    }
                }

                // All async and non-async entrypoints remain poisoned after trap/exit.
                val reuseCall = assertThrows<ComponentTrapException> {
                    c.invokeSuspending(c.resolve("fetch-plus-one"), WitWriter().apply { int(1) }.toReader())
                }
                val reuseStream = assertThrows<ComponentTrapException> {
                    c.newStream(ByteArray(0), 0)
                }
                val reuseFuture = assertThrows<ComponentTrapException> {
                    c.newFuture(WitWriter().apply { int(1) }.toByteArray())
                }
                val expectedPhrase = if (mode == "exit") "has already exited" else "earlier trap"
                assertTrue(expectedPhrase in reuseCall.message!!, reuseCall.message)
                assertTrue(expectedPhrase in reuseStream.message!!, reuseStream.message)
                assertTrue(expectedPhrase in reuseFuture.message!!, reuseFuture.message)
            }
        }
    }
}
