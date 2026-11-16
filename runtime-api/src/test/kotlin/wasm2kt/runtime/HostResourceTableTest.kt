package wasm2kt.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
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

    @Test
    fun `overlapping suspending lends keep rep valid until all scopes finish`(): Unit = runBlocking {
        HostResourceTable().use { table ->
            val r = Res()
            val firstLent = CompletableDeferred<Int>()
            val releaseFirst = CompletableDeferred<Unit>()
            val secondLent = CompletableDeferred<Int>()
            val releaseSecond = CompletableDeferred<Unit>()

            val first = async {
                table.bindSuspending {
                    val rep = table.lend(r)
                    firstLent.complete(rep)
                    releaseFirst.await()
                    rep
                }
            }
            val rep1 = firstLent.await()

            val second = async {
                table.bindSuspending {
                    val rep = table.lend(r)
                    secondLent.complete(rep)
                    releaseSecond.await()
                    rep
                }
            }
            val rep2 = secondLent.await()
            assertEquals(rep1, rep2)

            // Finish the first call while the second call still holds a lend.
            releaseFirst.complete(Unit)
            first.await()

            assertSame(r, table.borrow(rep2, Res::class.java))
            assertEquals(1, table.size)
            assertThrows<IllegalStateException> { table.bind { table.lower(r) } }

            releaseSecond.complete(Unit)
            second.await()

            assertEquals(0, table.size)
            table.bind { table.lower(r) }
        }
    }

    @Test
    fun `nested sync bind lending same resource keeps outer lend alive`() {
        HostResourceTable().use { table ->
            val r = Res()
            table.bind {
                val outerRep = table.lend(r)
                table.bind {
                    val innerRep = table.lend(r)
                    assertEquals(outerRep, innerRep)
                }
                assertSame(r, table.borrow(outerRep, Res::class.java))
                assertThrows<IllegalStateException> { table.lower(r) }
            }
            assertEquals(0, table.size)
            table.bind { table.lower(r) }
        }
    }

    @Test
    fun `cancelling witFuture before await or during suspension drops readable handle once`(): Unit = runBlocking {
        val droppedHandles = mutableListOf<Int>()
        val started = CompletableDeferred<Unit>()
        val backend = object : ComponentBackend {
            override fun resolve(functionName: String): Int = 0
            override fun invoke(function: Int, args: WitReader): WitReader = WitReader(ByteArray(0))
            override fun dropResource(handle: Int) {}
            override fun close() {}
            override suspend fun readFutureSuspending(handle: Int): WitReader? {
                started.complete(Unit)
                awaitCancellation()
            }

            override fun dropReadable(handle: Int) {
                droppedHandles += handle
            }
        }

        val lazyFuture = witFuture(backend, handle = 11) { it.int() }
        lazyFuture.cancel()
        lazyFuture.join()
        assertEquals(listOf(11), droppedHandles)

        val awaitedFuture = witFuture(backend, handle = 22) { it.int() }
        val waiter = launch { awaitedFuture.await() }
        withTimeout(5_000) {
            started.await()
            yield()
        }
        awaitedFuture.cancel()
        waiter.join()
        assertEquals(listOf(11, 22), droppedHandles)
    }

    @Test
    fun `concurrent scopes remove only themselves by identity regardless of equal contents`(): Unit = runBlocking {
        HostResourceTable().use { table ->
            val r = Res()
            val empty1Entered = CompletableDeferred<Unit>()
            val allowEmpty1Lend = CompletableDeferred<Unit>()
            val empty1Lent = CompletableDeferred<Unit>()
            val releaseEmpty1 = CompletableDeferred<Unit>()

            val first = launch {
                table.bindSuspending {
                    empty1Entered.complete(Unit)
                    allowEmpty1Lend.await()
                    table.lend(r)
                    empty1Lent.complete(Unit)
                    releaseEmpty1.await()
                }
            }
            empty1Entered.await()
            // Second scope finishes empty while first is still empty: must not evict first.
            table.bindSuspending { }

            allowEmpty1Lend.complete(Unit)
            empty1Lent.await()
            assertThrows<IllegalStateException> { table.lower(r) }
            releaseEmpty1.complete(Unit)
            first.join()
            assertEquals(0, table.size)

            // Equal non-empty scopes [rep]: second finishes first and must not leave its scope leaked.
            val lent1 = CompletableDeferred<Unit>()
            val release1 = CompletableDeferred<Unit>()
            val r2 = Res()
            val s1 = launch {
                table.bindSuspending {
                    table.lend(r2)
                    lent1.complete(Unit)
                    release1.await()
                }
            }
            lent1.await()
            table.bindSuspending {
                table.lend(r2)
            }
            assertEquals(1, table.size)
            assertThrows<IllegalStateException> { table.lower(r2) }
            release1.complete(Unit)
            s1.join()
            assertEquals(0, table.size)
            table.bind { table.lower(r2) }
        }
    }

    @Test
    fun `nested bind on a different HostResourceTable isolates lend scopes`() {
        HostResourceTable().use { outer ->
            HostResourceTable().use { inner ->
                val r = Res()
                outer.bind {
                    var rep = -1
                    inner.bind {
                        rep = outer.lend(r)
                        assertSame(r, outer.borrow(rep, Res::class.java))
                    }
                    assertSame(r, outer.borrow(rep, Res::class.java))
                    assertThrows<IllegalStateException> { outer.lower(r) }
                }
                assertEquals(0, outer.size)
                outer.bind { outer.lower(r) }
            }
        }
    }

    @Test
    fun `unscoped lend while another coroutine holds a suspending scope is not attached to that scope`(): Unit = runBlocking {
        HostResourceTable().use { table ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val scopedRes = Res()
            val unscopedRes = Res()

            val job = launch {
                table.bindSuspending {
                    table.lend(scopedRes)
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()

            var unscopedRep = -1
            val worker = Thread {
                HostResourceTable.setCurrent(table)
                try {
                    unscopedRep = table.lend(unscopedRes)
                } finally {
                    HostResourceTable.setCurrent(null)
                }
            }
            worker.start()
            worker.join()

            release.complete(Unit)
            job.join()

            assertSame(unscopedRes, table.borrow(unscopedRep, Res::class.java))
            assertEquals(1, table.size)
        }
    }
}
