@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.runtime.chicory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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
import wasm2kt.runtime.HostResourceTable
import wasm2kt.runtime.WitHostImportDispatcher
import wasm2kt.runtime.WitStreamChunk
import wasm2kt.runtime.WitWriter
import wasm2kt.runtime.canonical.GuestThread
import wasm2kt.runtime.canonical.InternalWasm2KtApi
import wasm2kt.runtime.witFuture
import wasm2kt.runtime.witStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Bounded concurrency model checks for [SessionLoop], [StackSwitching], [witFuture], [witStream],
 * lazy [ComponentBackend.newStream], deadlock detection, and thread reclamation (#161, ADR-0018,
 * ADR-0019).
 */
class AsyncConcurrencyModelTest {
    private fun openBackend(name: String, imports: WitHostImportDispatcher? = null): ComponentBackend =
        ChicoryComponent.fromDirectory(TestComponent(name).dir).instantiate(imports)

    @Test
    fun `at most one root guest thread runs at a time and HostResourceTable binding survives continuations`() = runBlocking {
        val table = HostResourceTable()
        val activeRootLock = Any()
        var currentActiveRoot: GuestThread? = null
        val switchTransitions = AtomicInteger(0)
        val switchViolation = AtomicReference<String?>(null)
        val importSawTable = mutableListOf<String>()

        val imports = object : WitHostImportDispatcher {
            override fun hostFunction(key: String): HostFunction? = null
            override fun asyncHostFunction(key: String): AsyncHostFunction? =
                if (key == "fetch") {
                    AsyncHostFunction { a, r ->
                        val x = a.int()
                        val b = HostResourceTable.currentOrNull()
                        delay(if (x % 2 == 0) 5 else 1)
                        val aft = HostResourceTable.currentOrNull()
                        synchronized(importSawTable) {
                            importSawTable += "x=$x before=${b === table}($b) after=${aft === table}($aft)"
                        }
                        r.int(x * 3)
                    }
                } else {
                    null
                }
        }

        table.use {
            openBackend("async-import", imports).use { c ->
                val inst = c as ChicoryComponentInstance
                inst.async.store.onSwitch = { next ->
                    synchronized(activeRootLock) {
                        if (next == null) {
                            currentActiveRoot = null
                        } else {
                            val root = next.root
                            val active = currentActiveRoot
                            if (active != null && active !== root) {
                                switchViolation.compareAndSet(
                                    null,
                                    "Two distinct root guest threads became active concurrently: $active vs $root",
                                )
                            }
                            if (active == null) switchTransitions.incrementAndGet()
                            currentActiveRoot = root
                        }
                    }
                }

                table.bindSuspending {
                    assertSame(table, HostResourceTable.currentOrNull(), "table must be bound inside bindSuspending")
                    coroutineScope {
                        val calls = (1..3).map { i ->
                            async {
                                assertSame(table, HostResourceTable.currentOrNull(), "table must be bound inside async child $i")
                                val fn = if (i == 2) "fetch-sync-plus-two" else "fetch-plus-one"
                                val expected = if (i == 2) i * 3 + 2 else i * 3 + 1
                                val got = c.invokeSuspending(c.resolve(fn), WitWriter().apply { int(i) }.toReader()).int()
                                assertEquals(expected, got)
                            }
                        }
                        calls.awaitAll()
                    }
                }

                assertNull(switchViolation.get())
                assertTrue(switchTransitions.get() >= 3, "Expected >= 3 root guest switch transitions, got ${switchTransitions.get()}")
                synchronized(importSawTable) {
                    assertEquals(3, importSawTable.size)
                    assertTrue(importSawTable.all { "before=true" in it && "after=true" in it }, "Unexpected table bindings: $importSawTable")
                }
                synchronized(activeRootLock) {
                    assertNull(currentActiveRoot)
                }
            }
        }
    }

    @Test
    fun `witFuture cancellation across pre-start, mid-read, and completion race drops handle without leak`() = runBlocking {
        for (mode in listOf("pre-start", "mid-read", "completion-race")) {
            val gate = CompletableDeferred<Unit>()
            val enricher = object : WitHostImportDispatcher {
                override fun hostFunction(key: String): HostFunction? = null
                override fun asyncHostFunction(key: String): AsyncHostFunction? =
                    if (key == "example:async-pipeline/enricher@0.1.0#format-item") {
                        AsyncHostFunction { a, r ->
                            val id = a.int()
                            val payload = a.string()
                            if (mode != "pre-start") gate.await()
                            r.string("#$id:$payload")
                        }
                    } else {
                        null
                    }
            }

            openBackend("async-pipeline", enricher).use { c ->
                val inst = c as ChicoryComponentInstance
                val items = c.newStream(
                    flow {
                        emit(WitStreamChunk(1, WitWriter().apply { string("a") }.toByteArray()))
                    },
                )
                val res = c.invokeSuspending(c.resolve("process-items"), WitWriter().apply { int(items) }.toReader())
                val outHandle = res.int()
                val countHandle = res.int()

                val future = witFuture(c, countHandle) { it.int() }
                when (mode) {
                    "pre-start" -> {
                        future.cancel()
                        future.join()
                    }
                    "mid-read" -> {
                        val reader = launch(Dispatchers.Default) { future.await() }
                        withTimeout(5_000) {
                            while (!inst.async.hasActiveHostRead(countHandle)) yield()
                        }
                        future.cancel()
                        reader.join()
                    }
                    "completion-race" -> {
                        val reader = launch(Dispatchers.Default) {
                            runCatching { future.await() }
                        }
                        withTimeout(5_000) {
                            while (!inst.async.hasActiveHostRead(countHandle)) yield()
                        }
                        // Drain outHandle so producer tries to resolve countHandle while consumer cancels concurrently.
                        val drainOut = launch(Dispatchers.Default) {
                            runCatching {
                                while (c.readStreamSuspending(outHandle, 1) != null) Unit
                            }
                        }
                        gate.complete(Unit)
                        future.cancel()
                        reader.join()
                        drainOut.join()
                    }
                }

                assertFalse(inst.async.hasActiveHostRead(countHandle), "mode=$mode left active host read")
                assertThrows<IllegalArgumentException>("mode=$mode leaked readable future handle") {
                    c.dropReadable(countHandle)
                }
                if (mode != "completion-race") {
                    c.dropReadable(outHandle)
                    gate.complete(Unit)
                }

                // Instance stays usable after future cancellation.
                val verifyStream = c.newStream(WitWriter().apply { int(3); int(4) }.toByteArray(), 2)
                assertEquals(7, c.invokeSuspending(c.resolve("sum-stream"), WitWriter().apply { int(verifyStream) }.toReader()).int())
            }
        }
    }

    @Test
    fun `witStream close and mid-stream cancellation release handles at all bounded positions`() = runBlocking {
        // 1. Closing witStream before collection drops the readable handle cleanly.
        openBackend("async-stream").use { c ->
            val streamHandle = c.invokeSuspending(c.resolve("count-up"), WitWriter().apply { int(3) }.toReader()).int()
            val flow = witStream(c, streamHandle) { r -> List(r.length()) { r.int() } }
            (flow as AutoCloseable).close()
            assertThrows<IllegalArgumentException> { c.dropReadable(streamHandle) }
            assertEquals(emptyList(), flow.toList())
            assertEquals(8, c.invokeSuspending(c.resolve("double"), WitWriter().apply { int(4) }.toReader()).int())
        }

        // 2. Cancelling witStream collection after the 1st of 3 items drops the remaining handle.
        openBackend("async-stream").use { c ->
            val streamHandle = c.invokeSuspending(c.resolve("count-up"), WitWriter().apply { int(3) }.toReader()).int()
            val flow = witStream(c, streamHandle) { r -> List(r.length()) { r.int() } }
            assertEquals(listOf(0), flow.take(1).toList())
            assertThrows<IllegalArgumentException> { c.dropReadable(streamHandle) }
            assertEquals(10, c.invokeSuspending(c.resolve("double"), WitWriter().apply { int(5) }.toReader()).int())
        }

        // 3. Cancelling witStream while suspended waiting for chunk 2 drops the handle without leaving an active read.
        val firstChunkDone = CompletableDeferred<Unit>()
        val secondChunkGate = CompletableDeferred<Unit>()
        val enricher = object : WitHostImportDispatcher {
            override fun hostFunction(key: String): HostFunction? = null
            override fun asyncHostFunction(key: String): AsyncHostFunction? =
                if (key == "example:async-pipeline/enricher@0.1.0#format-item") {
                    AsyncHostFunction { a, r ->
                        val id = a.int()
                        val payload = a.string()
                        if (id >= 2) secondChunkGate.await()
                        r.string("#$id:$payload")
                    }
                } else {
                    null
                }
        }
        openBackend("async-pipeline", enricher).use { c ->
            val inst = c as ChicoryComponentInstance
            val input = c.newStream(
                flow {
                    emit(WitStreamChunk(1, WitWriter().apply { string("one") }.toByteArray()))
                    emit(WitStreamChunk(1, WitWriter().apply { string("two") }.toByteArray()))
                },
            )
            val res = c.invokeSuspending(c.resolve("process-items"), WitWriter().apply { int(input) }.toReader())
            val outHandle = res.int()
            val countHandle = res.int()

            val stream = witStream(c, outHandle) { r -> List(r.length()) { r.string() } }
            val collected = mutableListOf<String>()
            val collector = launch(Dispatchers.Default) {
                stream.collect { v ->
                    collected += v
                    firstChunkDone.complete(Unit)
                }
            }
            withTimeout(5_000) {
                firstChunkDone.await()
                while (!inst.async.hasActiveHostRead(outHandle)) yield()
            }
            collector.cancel()
            collector.join()

            assertEquals(listOf("#1:one"), collected)
            assertFalse(inst.async.hasActiveHostRead(outHandle))
            assertThrows<IllegalArgumentException> { c.dropReadable(outHandle) }
            secondChunkGate.complete(Unit)
            assertEquals(1, c.readFutureSuspending(countHandle)!!.int())
        }
    }

    @Test
    fun `lazy newStream closes AutoCloseable source on early guest drop and session close`() = runBlocking {
        // 1. Guest stops reading after the host drops the downstream end: producer flow stops and closes once.
        val enricher = object : WitHostImportDispatcher {
            override fun hostFunction(key: String): HostFunction? = null
            override fun asyncHostFunction(key: String): AsyncHostFunction? =
                if (key == "example:async-pipeline/enricher@0.1.0#format-item") {
                    AsyncHostFunction { a, r ->
                        val id = a.int()
                        val payload = a.string()
                        r.string("#$id:$payload")
                    }
                } else {
                    null
                }
        }
        openBackend("async-pipeline", enricher).use { c ->
            val emitted = AtomicInteger(0)
            val closed = AtomicInteger(0)
            val gate = CompletableDeferred<Unit>()
            val source = c.newStream(
                flow {
                    emitted.incrementAndGet()
                    emit(WitStreamChunk(1, WitWriter().apply { string("first") }.toByteArray()))
                    gate.await()
                    emitted.incrementAndGet()
                    emit(WitStreamChunk(1, WitWriter().apply { string("second") }.toByteArray()))
                },
                AutoCloseable { closed.incrementAndGet() },
            )
            val res = c.invokeSuspending(c.resolve("process-items"), WitWriter().apply { int(source) }.toReader())
            val outHandle = res.int()
            val countHandle = res.int()

            val chunk = c.readStreamSuspending(outHandle, 1)!!
            assertEquals(1, chunk.length())
            assertEquals("#1:first", chunk.string())
            c.dropReadable(outHandle)
            gate.complete(Unit)

            // Wait for the guest task to exit its write loop, drop `source`, and resolve `countHandle`.
            assertEquals(1, c.readFutureSuspending(countHandle)!!.int())
            withTimeout(5_000) {
                while (closed.get() < 1) yield()
            }
            assertEquals(1, closed.get())
            assertEquals(2, emitted.get())
        }

        // 2. Closing a component while a lazy host stream is blocked closes its AutoCloseable source once.
        val closedOnSessionClose = AtomicInteger(0)
        val started = CompletableDeferred<Unit>()
        openBackend("async-stream").use { c ->
            val blockedStream = c.newStream(
                flow {
                    started.complete(Unit)
                    awaitCancellation()
                    @Suppress("UNREACHABLE_CODE")
                    emit(WitStreamChunk(1, ByteArray(4)))
                },
                AutoCloseable { closedOnSessionClose.incrementAndGet() },
            )
            val call = launch(Dispatchers.Default) {
                runCatching {
                    c.invokeSuspending(c.resolve("sum"), WitWriter().apply { int(blockedStream) }.toReader())
                }
            }
            withTimeout(5_000) { started.await() }
            c.close()
            call.join()
        }
        assertEquals(1, closedOnSessionClose.get())
    }

    @Test
    fun `deadlock detection avoids false positives on cancelled tasks and cleans up live threads on trap and idle`() = runBlocking {
        // 1. Cancelled call waiting behind backpressure does not trigger false deadlock poison.
        val echoImports = object : WitHostImportDispatcher {
            override fun hostFunction(key: String): HostFunction? = null
            override fun asyncHostFunction(key: String): AsyncHostFunction? =
                if (key == "fetch") AsyncHostFunction { a, r -> r.int(a.int()) } else null
        }
        openBackend("async-import", echoImports).use { c ->
            val inst = c as ChicoryComponentInstance
            inst.async.onSession { inst.async.state(0).backpressure = 1 }
            val cancelledCall = launch {
                c.invokeSuspending(c.resolve("fetch-plus-one"), WitWriter().apply { int(1) }.toReader())
            }
            withTimeout(5_000) {
                while (inst.async.onSession { inst.async.state(0).numWaitingToEnter < 1 }) yield()
            }
            cancelledCall.cancel()
            cancelledCall.join()
            inst.async.onSession { inst.async.state(0).backpressure = 0 }
            assertEquals(11, c.invokeSuspending(c.resolve("fetch-plus-one"), WitWriter().apply { int(10) }.toReader()).int())
        }

        // 2. Closing an instance while a guest thread is blocked in an async import abandons all live guest threads immediately.
        val blockedInImport = CompletableDeferred<Unit>()
        val imports = object : WitHostImportDispatcher {
            override fun hostFunction(key: String): HostFunction? = null
            override fun asyncHostFunction(key: String): AsyncHostFunction? =
                if (key == "fetch") {
                    AsyncHostFunction { a, r ->
                        a.int()
                        blockedInImport.complete(Unit)
                        awaitCancellation()
                        @Suppress("UNREACHABLE_CODE")
                        r.int(0)
                    }
                } else {
                    null
                }
        }
        openBackend("async-import", imports).use { c ->
            val inst = c as ChicoryComponentInstance
            // Blocking inside a sync-lowered async import suspends on a GuestThread.
            var blockedErr: Throwable? = null
            val blockedGuestThreadCall = launch {
                blockedErr = runCatching {
                    c.invokeSuspending(c.resolve("fetch-sync-plus-two"), WitWriter().apply { int(1) }.toReader())
                }.exceptionOrNull()
            }
            withTimeout(5_000) { blockedInImport.await() }
            assertTrue(inst.async.store.liveThreads.isNotEmpty())

            // Close abandons all live guest threads immediately and is idempotent.
            c.close()
            c.close()
            withTimeout(5_000) { blockedGuestThreadCall.join() }
            assertTrue(
                blockedErr is IllegalStateException && "closed" in (blockedErr?.message ?: ""),
                "Expected IllegalStateException(closed), got $blockedErr",
            )
            withTimeout(5_000) {
                while (inst.async.store.liveThreads.isNotEmpty()) yield()
            }
        }

        // 3. Dedicated true deadlock check on async-stream with backpressure > 0 and no hostWork.
        val capturedSessionThread = AtomicReference<Thread?>(null)
        openBackend("async-stream").use { c ->
            val inst = c as ChicoryComponentInstance
            inst.async.onSession {
                capturedSessionThread.compareAndSet(null, Thread.currentThread())
                inst.async.state(0).backpressure = 1
            }
            val err = assertThrows<ComponentTrapException> {
                c.invokeSuspending(c.resolve("double"), WitWriter().apply { int(5) }.toReader())
            }
            assertTrue("deadlock" in err.message!!, err.message)
            withTimeout(5_000) {
                while (inst.async.store.liveThreads.isNotEmpty()) yield()
            }
        }

        // 4. After closing all instances, the captured SessionLoop daemon thread exits after IDLE_SECONDS.
        val sessionThread = capturedSessionThread.get()!!
        withTimeout(6_000) {
            while (sessionThread.isAlive) {
                delay(100)
            }
        }
    }
}
