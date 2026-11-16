package wasm2kt.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Stateful property tests and bounded concurrent interleaving checks for [HostResourceTable]
 * (#160, #75, #82, #130).
 */
class HostResourceTableModelTest {
    private open class TrackedRes(val id: Int) : WitHostResource {
        val closedCount = AtomicInteger()
        override fun close() {
            closedCount.incrementAndGet()
        }
    }

    private class AltRes(id: Int) : TrackedRes(id)

    private class RefScope {
        val reps = LinkedHashSet<Int>()
    }

    private class RefEntry(val resource: TrackedRes, var owned: Boolean)

    private class ReferenceTableModel {
        private val entries = HashMap<Int, RefEntry>()
        private val reps = IdentityHashMap<TrackedRes, Int>()
        private var nextRep = 1
        private val activeScopes = ArrayList<RefScope>()
        val closedCounts = IdentityHashMap<TrackedRes, Int>()

        val size: Int get() = entries.size

        fun enterScope(): RefScope = RefScope().also { activeScopes.add(it) }

        fun exitScope(scope: RefScope) {
            val removed = activeScopes.removeAll { it === scope }
            check(removed) { "Scope was not active" }
            for (rep in scope.reps) {
                if (entries[rep]?.owned == false && activeScopes.none { rep in it.reps }) {
                    forget(rep)
                }
            }
        }

        fun lower(resource: TrackedRes): Result<Int> = runCatching {
            val rep = repOf(resource)
            val entry = entries.getValue(rep)
            check(!entry.owned)
            check(activeScopes.none { rep in it.reps })
            entry.owned = true
            rep
        }

        fun lend(resource: TrackedRes, currentScope: RefScope?): Result<Int> = runCatching {
            val known = reps[resource]
            if (known != null) {
                check(!entries.getValue(known).owned)
                if (currentScope != null) currentScope.reps.add(known)
                return@runCatching known
            }
            val rep = repOf(resource)
            currentScope?.reps?.add(rep)
            rep
        }

        fun borrow(rep: Int, type: Class<*>): Result<TrackedRes> = runCatching {
            val e = entries[rep] ?: throw IllegalStateException("Unknown rep $rep")
            if (!type.isInstance(e.resource)) throw IllegalStateException("Wrong type")
            e.resource
        }

        fun take(rep: Int, type: Class<*>): Result<TrackedRes> = runCatching {
            val e = entries[rep] ?: throw IllegalStateException("Unknown rep $rep")
            if (!type.isInstance(e.resource)) throw IllegalStateException("Wrong type")
            forget(rep)
            e.resource
        }

        fun drop(rep: Int): Result<Unit> = runCatching {
            val e = entries[rep] ?: throw IllegalStateException("Unknown rep $rep")
            forget(rep)
            closedCounts[e.resource] = (closedCounts[e.resource] ?: 0) + 1
        }

        fun closeTable() {
            val owned = entries.values.filter { it.owned }.map { it.resource }
            entries.clear()
            reps.clear()
            for (r in owned) {
                closedCounts[r] = (closedCounts[r] ?: 0) + 1
            }
        }

        private fun repOf(resource: TrackedRes): Int = reps.getOrPut(resource) {
            val rep = nextRep++
            entries[rep] = RefEntry(resource, owned = false)
            rep
        }

        private fun forget(rep: Int) {
            entries.remove(rep)?.let { reps.remove(it.resource) }
        }
    }

    private class AsyncSlot(
        val channel: Channel<suspend () -> Unit>,
        val job: Job,
        val refScope: RefScope,
    )

    @Test
    fun `state machine model matches HostResourceTable across interleaved sync and suspending scopes`() = runBlocking {
        for (seed in 1..60) {
            val rng = Random(seed * 9973 + 17)
            val table = HostResourceTable()
            val isolatedOther = HostResourceTable()
            val model = ReferenceTableModel()
            val resources = listOf(TrackedRes(0), TrackedRes(1), TrackedRes(2), AltRes(3))
            val knownReps = mutableListOf(1, 2, 3, 4)
            val slots = arrayOfNulls<AsyncSlot>(3)

            suspend fun runInSlot(slotIdx: Int, op: suspend () -> Unit) {
                val slot = slots[slotIdx]!!
                val done = CompletableDeferred<Unit>()
                withTimeout(5_000) {
                    slot.channel.send {
                        try {
                            op()
                        } finally {
                            done.complete(Unit)
                        }
                    }
                    done.await()
                }
            }

            fun verifyState(step: Int) {
                assertEquals(model.size, table.size, "seed=$seed step=$step size mismatch")
                for (r in resources) {
                    assertEquals(
                        model.closedCounts[r] ?: 0,
                        r.closedCount.get(),
                        "seed=$seed step=$step closed count mismatch for res=${r.id}",
                    )
                }
            }

            fun checkSameOutcome(
                step: Int,
                expected: Result<Any?>,
                actual: Result<Any?>,
            ) {
                assertEquals(expected.isSuccess, actual.isSuccess, "seed=$seed step=$step success mismatch: expected=$expected actual=$actual")
                if (expected.isFailure) {
                    assertTrue(
                        actual.exceptionOrNull() is IllegalStateException,
                        "seed=$seed step=$step expected IllegalStateException, got ${actual.exceptionOrNull()}",
                    )
                } else {
                    val expVal = expected.getOrNull()
                    val actVal = actual.getOrNull()
                    if (expVal is TrackedRes) {
                        assertSame(expVal, actVal, "seed=$seed step=$step resource identity mismatch")
                    } else {
                        assertEquals(expVal, actVal, "seed=$seed step=$step value mismatch")
                    }
                }
            }

            fun executeTableOp(step: Int, currentScope: RefScope?) {
                val r = resources[rng.nextInt(resources.size)]
                val rep = if (knownReps.isNotEmpty() && rng.nextInt(10) < 8) {
                    knownReps[rng.nextInt(knownReps.size)]
                } else {
                    rng.nextInt(1, 15)
                }
                val targetType: Class<out TrackedRes> =
                    if (rng.nextInt(5) == 0) AltRes::class.java else TrackedRes::class.java

                when (rng.nextInt(6)) {
                    0 -> {
                        val sizeBefore = table.size
                        val exp = model.lower(r)
                        val act = runCatching { table.lower(r) }
                        checkSameOutcome(step, exp, act)
                        if (act.isFailure) assertEquals(sizeBefore, table.size, "seed=$seed step=$step failed lower changed size")
                        act.getOrNull()?.let { if (it !in knownReps) knownReps += it }
                    }
                    1 -> {
                        val sizeBefore = table.size
                        val exp = model.lend(r, currentScope)
                        val act = runCatching { table.lend(r) }
                        checkSameOutcome(step, exp, act)
                        if (act.isFailure) assertEquals(sizeBefore, table.size, "seed=$seed step=$step failed lend changed size")
                        act.getOrNull()?.let { if (it !in knownReps) knownReps += it }
                    }
                    2 -> {
                        val exp = model.borrow(rep, targetType)
                        val act = runCatching { table.borrow(rep, targetType) }
                        checkSameOutcome(step, exp, act)
                    }
                    3 -> {
                        val exp = model.take(rep, targetType)
                        val act = runCatching { table.take(rep, targetType) }
                        checkSameOutcome(step, exp, act)
                    }
                    4 -> {
                        val exp = model.drop(rep)
                        val act = runCatching { table.drop(rep) }
                        checkSameOutcome(step, exp, act)
                    }
                    5 -> {
                        // Operations on an isolated table must not perturb `table` or its active scopes.
                        val sizeBefore = table.size
                        val closedBefore = resources.map { it.closedCount.get() }
                        val currentBefore = HostResourceTable.currentOrNull()
                        val otherRes = TrackedRes(99)
                        isolatedOther.bind {
                            assertSame(isolatedOther, HostResourceTable.currentOrNull())
                            val rRep = isolatedOther.lend(otherRes)
                            assertSame(otherRes, isolatedOther.borrow(rRep, TrackedRes::class.java))
                        }
                        assertSame(currentBefore, HostResourceTable.currentOrNull(), "seed=$seed step=$step isolated table altered current binding")
                        assertEquals(0, isolatedOther.size)
                        assertEquals(sizeBefore, table.size, "seed=$seed step=$step isolated table altered primary table size")
                        assertEquals(closedBefore, resources.map { it.closedCount.get() })
                    }
                }
            }

            try {
                for (step in 1..60) {
                    when (rng.nextInt(10)) {
                        0, 1 -> {
                            // Open or close an overlapping suspending lend scope.
                            val idx = rng.nextInt(slots.size)
                            val existing = slots[idx]
                            if (existing == null) {
                                val entered = CompletableDeferred<Unit>()
                                val ch = Channel<suspend () -> Unit>(Channel.RENDEZVOUS)
                                val refScope = model.enterScope()
                                val job = launch {
                                    table.bindSuspending {
                                        entered.complete(Unit)
                                        for (cmd in ch) cmd()
                                    }
                                }
                                withTimeout(5_000) { entered.await() }
                                slots[idx] = AsyncSlot(ch, job, refScope)
                            } else {
                                existing.channel.close()
                                withTimeout(5_000) { existing.job.join() }
                                model.exitScope(existing.refScope)
                                slots[idx] = null
                            }
                        }
                        2, 3, 4 -> {
                            // Execute inside an open suspending scope (or fall back to sync bind if none open).
                            val openIndices = slots.indices.filter { slots[it] != null }
                            if (openIndices.isNotEmpty()) {
                                val idx = openIndices[rng.nextInt(openIndices.size)]
                                val refScope = slots[idx]!!.refScope
                                runInSlot(idx) {
                                    assertSame(table, HostResourceTable.currentOrNull())
                                    if (rng.nextBoolean()) {
                                        val nestedScope = model.enterScope()
                                        table.bind {
                                            executeTableOp(step, nestedScope)
                                        }
                                        model.exitScope(nestedScope)
                                    } else {
                                        executeTableOp(step, refScope)
                                    }
                                }
                            } else {
                                val syncScope = model.enterScope()
                                table.bind {
                                    executeTableOp(step, syncScope)
                                }
                                model.exitScope(syncScope)
                            }
                        }
                        5, 6 -> {
                            // Synchronous bind, potentially nested with another bind on the same table.
                            val outerScope = model.enterScope()
                            table.bind {
                                executeTableOp(step, outerScope)
                                if (rng.nextBoolean()) {
                                    val innerScope = model.enterScope()
                                    table.bind {
                                        executeTableOp(step, innerScope)
                                    }
                                    model.exitScope(innerScope)
                                }
                                executeTableOp(step, outerScope)
                            }
                            model.exitScope(outerScope)
                        }
                        7 -> {
                            // Unscoped lend via setCurrent (no LendScope opened).
                            HostResourceTable.setCurrent(table)
                            try {
                                executeTableOp(step, null)
                            } finally {
                                HostResourceTable.setCurrent(null)
                            }
                        }
                        8 -> {
                            // Direct borrow/take/drop outside any bind scope.
                            executeTableOp(step, null)
                        }
                        9 -> {
                            // Mid-run close resets table state while checking owned closures.
                            table.close()
                            model.closeTable()
                        }
                    }
                    verifyState(step)
                }
            } finally {
                for (i in slots.indices) {
                    val slot = slots[i]
                    if (slot != null) {
                        slot.channel.close()
                        withTimeout(5_000) { slot.job.join() }
                        model.exitScope(slot.refScope)
                        slots[i] = null
                    }
                }
                table.close()
                isolatedOther.close()
                model.closeTable()
                verifyState(999)
                assertEquals(0, table.size)
            }
        }
    }

    @Test
    fun `bounded concurrent threads and coroutines preserve lend isolation and prevent double close`() = runBlocking {
        for (seed in 1..20) {
            val rng = Random(seed * 313 + 7)
            val table = HostResourceTable()
            val resources = listOf(TrackedRes(0), TrackedRes(1), TrackedRes(2))

            // Phase 1: hold a suspending lend on resource 0 while concurrent workers attempt lower and lend.
            val lentReady = CompletableDeferred<Int>()
            val releaseLend = CompletableDeferred<Unit>()
            val holder = launch(Dispatchers.Default) {
                table.bindSuspending {
                    val rep = table.lend(resources[0])
                    lentReady.complete(rep)
                    releaseLend.await()
                }
            }
            val lentRep = withTimeout(5_000) { lentReady.await() }

            val workersReady = CountDownLatch(3)
            val startGate = CountDownLatch(1)
            val failures = mutableListOf<Throwable>()
            val workers = (0 until 3).map { workerId ->
                Thread {
                    workersReady.countDown()
                    try {
                        check(startGate.await(5, TimeUnit.SECONDS)) { "Timed out waiting for startGate" }
                        val localRng = Random(seed * 100 + workerId)
                        repeat(20) {
                            table.bind {
                                // Moving resource 0 while lent by the coroutine must always fail.
                                assertThrows<IllegalStateException> { table.lower(resources[0]) }
                                val target = resources[localRng.nextInt(1, resources.size)]
                                when (localRng.nextInt(3)) {
                                    0 -> runCatching { table.lend(target) }
                                        .onFailure { check(it is IllegalStateException) }
                                    1 -> runCatching { table.borrow(lentRep, TrackedRes::class.java) }
                                        .onFailure { check(it is IllegalStateException) }
                                    2 -> runCatching { table.lower(target) }
                                        .onFailure { check(it is IllegalStateException) }
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        synchronized(failures) { failures += t }
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            }

            val coroutines = (0 until 3).map { coroId ->
                launch(Dispatchers.Default) {
                    val localRng = Random(seed * 1000 + coroId)
                    repeat(20) {
                        table.bindSuspending {
                            assertThrows<IllegalStateException> { table.lower(resources[0]) }
                            val r = resources[localRng.nextInt(resources.size)]
                            val rep = runCatching { table.lend(r) }
                                .onFailure { check(it is IllegalStateException) }
                                .getOrNull()
                            if (rep != null) {
                                runCatching { table.borrow(rep, TrackedRes::class.java) }
                                    .onFailure { check(it is IllegalStateException) }
                            }
                        }
                    }
                }
            }

            assertTrue(workersReady.await(5, TimeUnit.SECONDS))
            startGate.countDown()
            workers.forEach {
                it.join(5_000)
                assertFalse(it.isAlive, "seed=$seed worker thread did not finish in time")
            }
            withTimeout(5_000) { coroutines.joinAll() }
            synchronized(failures) {
                assertEquals(emptyList(), failures.toList())
            }

            // Resource 0 is still lent until holder exits its bindSuspending scope.
            assertThrows<IllegalStateException> { table.bind { table.lower(resources[0]) } }
            releaseLend.complete(Unit)
            withTimeout(5_000) { holder.join() }

            // After all lend scopes close, resource 0 is no longer lent and may be moved and dropped once.
            val rep0 = table.bind { table.lower(resources[0]) }
            table.drop(rep0)
            assertEquals(1, resources[0].closedCount.get())

            // Closing the table closes any remaining owned resources at most once.
            table.close()
            assertEquals(0, table.size)
            assertEquals(1, resources[0].closedCount.get())
            for (r in resources) {
                assertTrue(r.closedCount.get() <= 1, "seed=$seed resource ${r.id} closed ${r.closedCount.get()} times")
            }
            if (rng.nextBoolean()) {
                // Closing an already-empty table is idempotent.
                table.close()
                for (r in resources) {
                    assertTrue(r.closedCount.get() <= 1)
                }
            }
        }
    }

    @Test
    fun `concurrent drop and close race closes each owned resource exactly once`() {
        for (seed in 1..30) {
            val table = HostResourceTable()
            val resources = List(4) { TrackedRes(it) }
            val reps = table.bind { resources.map { table.lower(it) } }
            val ready = CountDownLatch(4)
            val go = CountDownLatch(1)
            val failures = mutableListOf<Throwable>()

            val droppers = (0 until 3).map { worker ->
                Thread {
                    ready.countDown()
                    try {
                        check(go.await(5, TimeUnit.SECONDS))
                        val order = reps.shuffled(Random(seed * 37 + worker))
                        for (rep in order) {
                            runCatching { table.drop(rep) }
                                .onFailure { check(it is IllegalStateException) }
                        }
                    } catch (t: Throwable) {
                        synchronized(failures) { failures += t }
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            }
            val closer = Thread {
                ready.countDown()
                try {
                    check(go.await(5, TimeUnit.SECONDS))
                    table.close()
                } catch (t: Throwable) {
                    synchronized(failures) { failures += t }
                }
            }.apply {
                isDaemon = true
                start()
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS))
            go.countDown()
            (droppers + closer).forEach {
                it.join(5_000)
                assertFalse(it.isAlive, "seed=$seed thread timed out")
            }
            synchronized(failures) {
                assertEquals(emptyList(), failures.toList())
            }
            assertEquals(0, table.size)
            for (r in resources) {
                assertEquals(1, r.closedCount.get(), "seed=$seed resource ${r.id} closed ${r.closedCount.get()} times")
            }
        }
    }
}
