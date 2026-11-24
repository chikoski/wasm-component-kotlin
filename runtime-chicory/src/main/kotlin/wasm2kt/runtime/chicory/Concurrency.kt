@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.runtime.chicory

import wasm2kt.runtime.HostResourceTable
import wasm2kt.runtime.canonical.BorrowScope
import wasm2kt.runtime.canonical.HandleTable
import wasm2kt.runtime.canonical.InternalWasm2KtApi
import wasm2kt.runtime.canonical.ResourceHandle
import wasm2kt.runtime.canonical.trap
import wasm2kt.runtime.canonical.unsigned
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// The Component Model's concurrency (the async ABI, #16, ADR-0018), ported from the Canonical ABI's
// reference definitions (`design/mvp/canonical-abi/definitions.py`): component instances, threads,
// tasks, waitables and waitable sets, subtasks, and the ends of streams and futures.
//
// The reference implements stack switching with one Python thread per continuation, of which
// exactly one runs at a time. This port does the same with JVM threads (there is no stack
// switching on JVM 17 or Android): resuming a continuation hands control to its thread and blocks
// the resumer until the continuation blocks or finishes. Every hand-over goes through a
// [Semaphore], so each thread sees the memory written by the previous one.
//
// Where the reference chooses randomly, this port takes one fixed choice (the reference's
// `DETERMINISTIC_PROFILE`, and the first ready thread in waiting order). Where Wasmtime and the
// reference differ, it follows Wasmtime 48, the reference implementation of this project (e.g. a
// synchronous task may not block, ADR-0018).

/** Thrown in a guest thread that is abandoned because its instance trapped or was closed. */
internal class AbandonedThreadError : Error("The component instance was closed or trapped")

// ------------------------------------------------------------------- stack switching

/** A suspended computation: the rest of a guest thread's execution (the reference's `Continuation`). */
internal class Continuation(val store: AsyncStore, internal var start: (() -> GuestThread?)?) {
    val go = Semaphore(0)

    @Volatile
    var handler: Handler? = null
}

/** The resumer's side of a resumed continuation (the reference's `Handler`). */
internal class Handler(var currentThread: GuestThread, val hostResources: HostResourceTable?) {
    val done = Semaphore(0)
    var cont: Continuation? = null
    var result: GuestThread? = null
    var failure: Throwable? = null

    /** The thread that blocked: the one resumed, or a nested one running on its stack. */
    var blocker: GuestThread? = null
}

internal object StackSwitching {
    private val handler = ThreadLocal<Handler?>()

    private val threadIds = AtomicInteger()

    /**
     * Runs continuations. Threads are reused once a continuation finishes; idle ones exit after a
     * while. Daemon threads, so a guest thread left blocked never keeps the JVM alive.
     */
    val executor: ExecutorService = ThreadPoolExecutor(
        0, Int.MAX_VALUE, 30, TimeUnit.SECONDS, java.util.concurrent.SynchronousQueue(),
        ThreadFactory { r ->
            // Compiled guest code runs on the JVM stack; give it as much room as a main thread.
            Thread(null, r, "wasm2kt-chicory-guest-${threadIds.incrementAndGet()}", 8L shl 20).apply { isDaemon = true }
        },
    )

    fun currentHandler(): Handler? = handler.get()

    /**
     * Gives the host's own thread a current thread, for a synchronous guest-to-guest call a fused
     * adapter makes outside any task (a component that does not use the async ABI). Such a thread
     * cannot block.
     */
    fun installHostHandler(thread: GuestThread): Handler = Handler(thread, null).also { handler.set(it) }

    fun removeHostHandler() {
        handler.remove()
    }

    /** The reference's `cont_new`: the continuation starts running [f] when first resumed. */
    fun contNew(store: AsyncStore, f: () -> GuestThread?): Continuation = Continuation(store, f)

    private fun launch(cont: Continuation, f: () -> GuestThread?) {
        val store = cont.store
        executor.execute {
            try {
                acquire(cont.go, store)
            } catch (e: AbandonedThreadError) {
                return@execute
            }
            val root = cont.handler!!.currentThread
            enter(cont.handler!!)
            var result: GuestThread? = null
            var failure: Throwable? = null
            store.liveThreads.add(Thread.currentThread())
            try {
                result = f()
            } catch (e: Throwable) {
                failure = e
            } finally {
                store.liveThreads.remove(Thread.currentThread())
            }
            // Pool threads are reused: an interrupt meant for this store must not reach the next one.
            Thread.interrupted()
            // An abandoned thread hands control to nobody: its resumer is gone too. A thread that
            // trapped (and so abandoned the store) still hands the trap to its resumer.
            val h = handler.get()
            if (h == null || generateSequence(failure) { it.cause }.any { it is AbandonedThreadError }) {
                leave()
                return@execute
            }
            leave()
            h.cont = null
            h.result = result
            h.failure = failure
            h.blocker = root
            h.done.release()
        }
    }

    /**
     * The reference's `resume`: runs [cont] as [current] until it blocks (returning the continuation
     * of the rest and the thread to switch to) or finishes. Rethrows what the continuation threw.
     */
    fun resume(cont: Continuation, current: GuestThread): Handler {
        val h = Handler(current, HostResourceTable.currentOrNull())
        cont.handler = h
        cont.start?.let { f ->
            cont.start = null
            launch(cont, f)
        }
        cont.go.release()
        // The resumer waits whether or not it is a guest thread: control is with the continuation.
        h.done.acquireUninterruptibly()
        h.failure?.let { throw it }
        return h
    }

    /**
     * The reference's `block`: suspends the current continuation, returning control to its resumer
     * (asking it to switch to [switchTo]), and continues once something resumes it again.
     */
    fun block(switchTo: GuestThread? = null) {
        val h = handler.get() ?: trap("Cannot block outside a Component Model task")
        val store = h.currentThread.task.inst.store
        val cont = Continuation(store, null)
        h.cont = cont
        h.result = switchTo
        h.blocker = h.currentThread
        leave()
        h.done.release()
        acquire(cont.go, store)
        enter(cont.handler!!)
    }

    private fun enter(h: Handler) {
        handler.set(h)
        HostResourceTable.setCurrent(h.hostResources)
    }

    private fun leave() {
        handler.remove()
        HostResourceTable.setCurrent(null)
    }

    private fun acquire(s: Semaphore, store: AsyncStore) {
        try {
            s.acquire()
        } catch (e: InterruptedException) {
            throw AbandonedThreadError()
        }
        if (store.abandoned) throw AbandonedThreadError()
    }
}

// ------------------------------------------------------------------- tables

/** The reference's `ErrorContext`. Error contexts are shared, not moved, between instances. */
internal class ErrorContext(val debugMessage: String)

// ------------------------------------------------------------------- instances and the store

/** The reference's `ComponentInstance`: the async state of one (sub)component instance. */
internal class ComponentInstanceState(val index: Int, val store: AsyncStore) {
    val handles = HandleTable()
    val threads = HandleTable()
    var backpressure = 0
    var numWaitingToEnter = 0
    var exclusiveThread: GuestThread? = null
}

/** The reference's `Store`: the threads waiting to run, across the instances of one component. */
internal class AsyncStore {
    val waiting = ArrayList<GuestThread>()

    /** Threads currently running guest code for this store, interrupted when it is abandoned. */
    val liveThreads: MutableSet<Thread> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    @Volatile
    var abandoned = false

    /** Called whenever the current thread changes, e.g. to update the task's "may block" flag. */
    var onSwitch: (GuestThread?) -> Unit = {}

    /** The reference's `tick`: resumes one ready thread; false if none is ready. */
    fun tick(): Boolean {
        val thread = waiting.firstOrNull { it.ready() } ?: return false
        thread.resume()
        return true
    }

    /** Stops every guest thread still blocked; their stacks unwind with [AbandonedThreadError]. */
    fun abandon() {
        abandoned = true
        for (t in liveThreads) if (t !== Thread.currentThread()) t.interrupt()
        waiting.clear()
    }
}

/** The current thread of the running guest code, or null when the host is running. */
internal fun currentThreadOrNull(): GuestThread? = StackSwitching.currentHandler()?.currentThread

internal fun currentThread(): GuestThread = currentThreadOrNull() ?: trap("This built-in can only be called during a Component Model task")

internal fun currentTask(): Task = currentThread().task

// ------------------------------------------------------------------- threads

/**
 * The reference's `Thread`. A thread created for a synchronous guest-to-guest call made by a fused
 * adapter ([nested]) has no continuation of its own: it runs on its caller's stack.
 */
internal class GuestThread(val task: Task, body: (() -> Unit)?) {
    /** The continuation to resume, while the thread is blocked. */
    var cont: Continuation? = body?.let { b -> StackSwitching.contNew(task.inst.store) { b(); null } }

    var running = body == null
    var readyFunc: (() -> Boolean)? = null
    var index: Int? = null
    val storage = IntArray(2)
    var cancellable = false
    val nested = body == null

    private val store get() = task.inst.store

    fun exited() = cont == null && !running
    fun blocked() = !running && cont != null
    fun suspended() = blocked() && readyFunc == null
    fun waiting() = blocked() && readyFunc != null
    fun ready() = waiting() && readyFunc!!()

    fun startWaiting(f: () -> Boolean) {
        check(readyFunc == null)
        readyFunc = f
        store.waiting += this
    }

    fun stopWaiting() {
        readyFunc = null
        store.waiting.remove(this)
    }

    fun resumeLater() {
        startWaiting { true }
    }

    /** Runs this thread, and every thread it switches to, until they block or exit. */
    fun resume() {
        check(blocked()) { "Resuming a thread that is not blocked" }
        if (waiting()) stopWaiting()
        var thread: GuestThread? = this
        while (thread != null) {
            val c = thread.cont!!
            thread.cont = null
            thread.running = true
            val previous = currentThreadOrNull()
            store.onSwitch(thread)
            val h = try {
                StackSwitching.resume(c, thread)
            } finally {
                store.onSwitch(previous)
            }
            val blocker = h.blocker ?: thread
            blocker.cont = h.cont
            blocker.running = false
            thread = h.result
        }
    }

    fun suspend() {
        StackSwitching.block()
    }

    /** Blocks until [f] is true; returns at once when it already is (one of the reference's choices). */
    fun waitUntil(f: () -> Boolean) {
        if (f()) return
        startWaiting(f)
        StackSwitching.block()
    }

    /** Lets every other ready thread run first. */
    fun yield() = yieldUntil { true }

    /** Lets every other ready thread run first, and blocks until [f] is true. */
    fun yieldUntil(f: () -> Boolean) {
        startWaiting(f)
        StackSwitching.block()
    }

    fun suspendThenResume(other: GuestThread) {
        StackSwitching.block(switchTo = other)
    }

    /** For a synchronous guest-to-guest call: the thread whose stack it runs on (see [AsyncAbi]). */
    var syncCaller: GuestThread? = null

    /** The thread with its own continuation that this thread's execution belongs to. */
    val root: GuestThread
        get() {
            var t = this
            while (true) t = t.syncCaller ?: return t
        }

    fun yieldThenResume(other: GuestThread) {
        startWaiting { true }
        StackSwitching.block(switchTo = other)
    }

    fun suspendThenPromote(other: GuestThread) {
        if (other.ready()) {
            other.stopWaiting()
            suspendThenResume(other)
        } else {
            suspend()
        }
    }

    fun yieldThenPromote(other: GuestThread) {
        if (other.ready()) {
            other.stopWaiting()
            yieldThenResume(other)
        } else {
            yield()
        }
    }
}

// ------------------------------------------------------------------- tasks

/**
 * The reference's `Task`: one call of a lifted function. [ftAsync] is whether the function type is
 * `async`, [optsAsync] / [hasCallback] the lift's canonical options. [onStart] returns the callee's
 * flat arguments; [onResolve] receives its flat results (as the core function or `task.return`
 * produced them), or null when the task was cancelled.
 */
internal class Task(
    val inst: ComponentInstanceState,
    val ftAsync: Boolean,
    val optsAsync: Boolean,
    val hasCallback: Boolean,
    /** Checks a `task.return`: its result type and options must match the lift's. */
    val checkReturn: (Trampoline.Async, CanonOptions) -> Boolean,
    val onStart: () -> LongArray,
    val onResolve: (LongArray?) -> Unit,
) : BorrowScope {
    enum class State { INITIAL, STARTED, PENDING_CANCEL, CANCEL_DELIVERED, RESOLVED }

    var state = State.INITIAL
    override var numBorrows = 0
    val lenders = ArrayList<ResourceHandle>()
    var implicitThread: GuestThread? = null
    val threads = ArrayList<GuestThread>()

    fun releaseLenders() {
        for (h in lenders) h.numLends--
        lenders.clear()
    }

    /** Wasmtime's rule (ADR-0018): only a task of an `async` function may block before it returns. */
    fun mayBlock(): Boolean = ftAsync || state == State.RESOLVED

    fun needsExclusive(): Boolean = !optsAsync || hasCallback

    fun enterImplicitThread(thread: GuestThread): Boolean {
        check(state == State.INITIAL)
        implicitThread = thread
        if (ftAsync) {
            val hasBackpressure = { inst.backpressure > 0 || (needsExclusive() && inst.exclusiveThread != null) }
            if (hasBackpressure() || inst.numWaitingToEnter > 0) {
                inst.numWaitingToEnter++
                thread.waitUntil { !hasBackpressure() }
                inst.numWaitingToEnter--
                if (deliverPendingCancel()) {
                    cancel()
                    return false
                }
            }
            if (needsExclusive()) {
                check(inst.exclusiveThread == null)
                inst.exclusiveThread = thread
            }
        }
        registerThread(thread)
        return true
    }

    fun registerThread(thread: GuestThread) {
        threads += thread
        thread.index = inst.threads.add(thread)
    }

    fun exitImplicitThread() {
        val thread = implicitThread!!
        unregisterThread(thread)
        if (ftAsync && needsExclusive() && inst.exclusiveThread === thread) inst.exclusiveThread = null
    }

    fun unregisterThread(thread: GuestThread) {
        threads.remove(thread)
        if (threads.isEmpty()) {
            if (state != State.RESOLVED) trap("The task exited without returning a result (task.return)")
        }
        thread.index?.let { inst.threads.remove(it) }
        thread.index = null
    }

    fun requestCancellation() {
        if (state == State.INITIAL) {
            state = State.PENDING_CANCEL
            implicitThread!!.resume()
        } else {
            check(state == State.STARTED)
            state = State.PENDING_CANCEL
            val t = implicitThread!!
            if (t.cancellable && t.ready()) t.resume()
        }
    }

    fun hasPendingCancel() = state == State.PENDING_CANCEL

    fun deliverPendingCancel(): Boolean {
        if (!hasPendingCancel()) return false
        state = State.CANCEL_DELIVERED
        return true
    }

    fun start(): LongArray {
        check(state == State.INITIAL || state == State.PENDING_CANCEL)
        state = State.STARTED
        return onStart()
    }

    fun return_(result: LongArray) {
        if (state == State.RESOLVED) trap("task.return was already called, or the task was cancelled")
        if (numBorrows > 0) trap("Borrowed resource handles must be dropped before the task returns")
        releaseLenders()
        onResolve(result)
        state = State.RESOLVED
    }

    fun cancel() {
        if (state != State.CANCEL_DELIVERED) trap("task.cancel called without a pending cancellation")
        if (numBorrows > 0) trap("Borrowed resource handles must be dropped before the task is cancelled")
        releaseLenders()
        onResolve(null)
        state = State.RESOLVED
    }
}

// ------------------------------------------------------------------- waitables

internal object EventCode {
    const val NONE = 0
    const val SUBTASK = 1
    const val STREAM_READ = 2
    const val STREAM_WRITE = 3
    const val FUTURE_READ = 4
    const val FUTURE_WRITE = 5
    const val TASK_CANCELLED = 6
}

internal class Event(val code: Int, val p1: Int, val p2: Int)

internal open class Waitable {
    private var pendingEvent: (() -> Event)? = null
    var wset: WaitableSet? = null
    var hasSyncWaiter = false

    fun setPendingEvent(e: () -> Event) {
        pendingEvent = e
    }

    fun hasPendingEvent() = pendingEvent != null

    fun inWaitableSet() = wset != null

    fun waitForPendingEvent() {
        hasSyncWaiter = true
        currentThread().waitUntil(::hasPendingEvent)
        hasSyncWaiter = false
    }

    fun getPendingEvent(): Event {
        val e = pendingEvent!!
        pendingEvent = null
        return e()
    }

    fun join(set: WaitableSet?) {
        wset?.elems?.remove(this)
        wset = set
        set?.elems?.add(this)
    }

    open fun drop() {
        join(null)
    }
}

internal class WaitableSet {
    val elems = ArrayList<Waitable>()
    var numWaiting = 0

    fun hasPendingEvent() = elems.any { it.hasPendingEvent() }

    fun getPendingEvent(): Event = elems.first { it.hasPendingEvent() }.getPendingEvent()

    fun waitFor(): Event {
        numWaiting++
        currentThread().waitUntil(::hasPendingEvent)
        numWaiting--
        return getPendingEvent()
    }

    fun poll(): Event = if (!hasPendingEvent()) Event(EventCode.NONE, 0, 0) else getPendingEvent()

    fun waitFromCallback(thread: GuestThread): Event {
        val task = thread.task
        numWaiting++
        thread.waitUntil { task.inst.exclusiveThread == null && (hasPendingEvent() || task.hasPendingCancel()) }
        numWaiting--
        return if (task.deliverPendingCancel()) Event(EventCode.TASK_CANCELLED, 0, 0) else getPendingEvent()
    }

    fun drop() {
        if (elems.isNotEmpty()) trap("Cannot drop a waitable set that still contains waitables")
        if (numWaiting > 0) trap("Cannot drop a waitable set that is being waited on")
    }
}

/** The reference's `Subtask`: a call the guest made, from the caller's side. */
internal class Subtask : Waitable() {
    object State {
        const val STARTING = 0
        const val STARTED = 1
        const val RETURNED = 2
        const val CANCELLED_BEFORE_STARTED = 3
        const val CANCELLED_BEFORE_RETURNED = 4
    }

    var state = State.STARTING
    var onCancel: (() -> Unit)? = null
    var lenders: MutableList<ResourceHandle>? = ArrayList()
    var cancellationRequested = false
    var flatResults: LongArray = LongArray(0)

    fun resolved() = state >= State.RETURNED

    fun addLender(h: ResourceHandle) {
        h.numLends++
        lenders!!.add(h)
    }

    fun resolve(state: Int, flatResults: LongArray) {
        this.state = state
        this.flatResults = flatResults
    }

    fun deliverResolve() {
        lenders?.forEach { it.numLends-- }
        lenders = null
    }

    fun resolveDelivered() = lenders == null

    override fun drop() {
        if (!resolveDelivered()) trap("Cannot drop a subtask that has not returned or been cancelled")
        super.drop()
    }
}

// ------------------------------------------------------------------- streams and futures

/**
 * Values in flight between two buffers of a stream or future copy: [count] values whose bytes, in
 * the binary format of [wasm2kt.runtime.WitWriter], are [bytes]; handles they contain refer to [objects].
 */
internal class CopiedValues(val count: Int, val bytes: ByteArray, val objects: List<Any>)

internal interface StreamBuffer {
    val progress: Int
    fun remain(): Int
}

internal interface ReadableBuffer : StreamBuffer {
    fun read(n: Int): CopiedValues
}

internal interface WritableBuffer : StreamBuffer {
    fun write(vs: CopiedValues)
}

/**
 * One end of a stream or future (the reference's `End`). [owner] is the instance whose table holds
 * it ([index] in its handles, typed by the plan's stream or future table [table]); null while it is
 * being passed, or when the host holds it.
 */
internal abstract class End(val isStream: Boolean, val isReadable: Boolean, var table: Int, var owner: ComponentInstanceState?) : Waitable() {
    enum class EndState { IDLE, COPYING, CANCELLING_COPY, DONE }

    var state = EndState.IDLE
    var other: End? = null
    var buffer: StreamBuffer? = null
    var index: Int? = null

    /** Whether values of this end's element type contain no handles (a same-instance copy is allowed). */
    abstract val elementIsPlain: Boolean

    val eventCode: Int
        get() = when {
            isStream && isReadable -> EventCode.STREAM_READ
            isStream -> EventCode.STREAM_WRITE
            isReadable -> EventCode.FUTURE_READ
            else -> EventCode.FUTURE_WRITE
        }

    fun copy(buffer: StreamBuffer) {
        check(this.buffer == null)
        state = EndState.COPYING
        val other = other
        if (other == null) {
            check(hasPendingEvent())
        } else if (other.buffer == null) {
            this.buffer = buffer
        } else {
            if (owner != null && owner === other.owner && !elementIsPlain) {
                trap("Cannot copy values containing handles between the ends of a stream or future in the same instance")
            }
            val ob = other.buffer!!
            if (buffer.remain() > 0 && ob.remain() > 0) {
                val n = minOf(buffer.remain(), ob.remain())
                if (isReadable) (buffer as WritableBuffer).write((ob as ReadableBuffer).read(n))
                else (ob as WritableBuffer).write((buffer as ReadableBuffer).read(n))
                notify(buffer.progress)
                other.notify(ob.progress)
                if (ob.remain() == 0) other.buffer = null
            } else if (buffer.remain() > 0 || (isReadable && ob.remain() == 0)) {
                other.notify(0)
                other.buffer = null
                this.buffer = buffer
            } else {
                notify(0)
            }
        }
    }

    fun cancel() {
        check(state == EndState.COPYING)
        state = EndState.CANCELLING_COPY
        if (!hasPendingEvent()) notify(0)
    }

    override fun drop() {
        val other = other
        if (other != null) {
            other.other = null
            if (other.state != EndState.DONE && !other.hasPendingEvent()) other.notify(0)
            this.other = null
        }
        super.drop()
    }

    fun copyingOrCancelling() = state == EndState.COPYING || state == EndState.CANCELLING_COPY

    fun notify(progress: Int) {
        if (isStream) {
            setPendingEvent {
                buffer = null
                val result = when {
                    other == null -> {
                        state = EndState.DONE
                        CopyResult.DROPPED
                    }
                    state == EndState.CANCELLING_COPY -> {
                        state = EndState.IDLE
                        CopyResult.CANCELLED
                    }
                    else -> {
                        state = EndState.IDLE
                        CopyResult.COMPLETED
                    }
                }
                Event(eventCode, index ?: 0, result or (progress shl 4))
            }
        } else {
            setPendingEvent {
                val result = when {
                    progress == 1 -> {
                        state = EndState.DONE
                        CopyResult.COMPLETED
                    }
                    other == null -> {
                        buffer = null
                        state = EndState.DONE
                        CopyResult.DROPPED
                    }
                    else -> {
                        buffer = null
                        state = EndState.IDLE
                        CopyResult.CANCELLED
                    }
                }
                Event(eventCode, index ?: 0, result)
            }
        }
    }

    companion object {
        /** The reference's `End.forward` (`stream.forward` / `future.forward`). */
        fun forward(src: End, dst: End) {
            if (src.other === dst || src.other == null || dst.other == null) {
                src.drop()
                dst.drop()
                return
            }
            val writable = src.other!!
            val readable = dst.other!!
            writable.other = readable
            readable.other = writable
            val wb = writable.buffer
            val rb = readable.buffer
            if (wb != null && rb != null) {
                val smaller = if (rb.remain() > wb.remain()) writable else readable
                val buffer = smaller.buffer!!
                smaller.buffer = null
                smaller.copy(buffer)
            }
        }
    }
}

internal object CopyResult {
    const val COMPLETED = 0
    const val DROPPED = 1
    const val CANCELLED = 2
}

/** The value returned by an async built-in that has not completed yet. */
internal const val BLOCKED = -1
