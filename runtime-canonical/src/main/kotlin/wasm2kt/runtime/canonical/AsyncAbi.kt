@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.runtime.canonical

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import wasm2kt.runtime.AsyncHostFunction
import wasm2kt.runtime.ComponentBackend
import wasm2kt.runtime.ComponentTrapException
import wasm2kt.runtime.HostFunction
import wasm2kt.runtime.HostResourceTable
import wasm2kt.runtime.WitReader
import wasm2kt.runtime.WitStreamChunk
import wasm2kt.runtime.WitWriter
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resumeWithException

/**
 * The Component Model async ABI (#16, ADR-0018, ADR-0023 Phase 3): host calls into a component
 * that uses it run as tasks on [store], and the async built-ins and fused adapters' async
 * intrinsics are implemented here symmetrically for Chicory and AOT via [AsyncBackendHost].
 */
@InternalWasm2KtApi
class AsyncAbi(private val host: AsyncBackendHost) {
    private val errorMessageTuple = WitType.Tuple(listOf(WitType.Str))

    val store = AsyncStore()

    private val states = HashMap<Int, ComponentInstanceState>()

    /** Ends of streams and futures, and error contexts, the host holds: host handle -> object. */
    private val hostObjects = HashMap<Int, Any>()
    private var nextHostObject = 1

    fun state(instance: Int): ComponentInstanceState = states.getOrPut(instance) { ComponentInstanceState(instance, store) }

    // ------------------------------------------------------------------- canon lift

    /**
     * The reference's `canon_lift`: creates the task for a call of [callee] and runs it until it
     * blocks or exits. [callback] is the lift's `callback`, [postReturn] its `post-return`.
     */
    fun canonLift(
        inst: ComponentInstanceState,
        ftAsync: Boolean,
        optsAsync: Boolean,
        callee: (LongArray) -> LongArray,
        callback: ((LongArray) -> LongArray)?,
        postReturn: ((LongArray) -> Unit)?,
        checkReturn: (AsyncTrampolineSpec, AsyncCanonOptions) -> Boolean,
        onStart: () -> LongArray,
        onResolve: (LongArray?) -> Unit,
        createdTask: (Task) -> Unit = {},
    ): Task {
        val task = Task(inst, ftAsync, optsAsync, callback != null, checkReturn, onStart, onResolve)
        createdTask(task)
        lateinit var thread: GuestThread
        thread = GuestThread(task) {
            if (task.enterImplicitThread(thread)) runImplicitThread(task, thread, callee, callback, postReturn)
        }
        thread.resume()
        if (!ftAsync) {
            syncLoops++
            try {
                while (task.state != Task.State.RESOLVED) {
                    // The instance's ready threads, in the order they started waiting.
                    // A synchronous call it made into another instance continues this call, too.
                    val candidate = store.waiting.firstOrNull { (it.task.inst === inst || it.root.task === task) && it.ready() }
                    when {
                        candidate != null -> candidate.resume()
                        pumpHostWriters() -> Unit
                        // Waiting for the host (an import, a stream): run its coroutines here.
                        onLoopThread() && (hostWork > 0 || loop.hasQueued()) -> loop.runOne()
                        else -> trap("deadlock detected: a synchronous call cannot complete because every thread of the instance is waiting")
                    }
                }
            } finally {
                syncLoops--
            }
        }
        return task
    }

    private fun runImplicitThread(
        task: Task,
        thread: GuestThread,
        callee: (LongArray) -> LongArray,
        callback: ((LongArray) -> LongArray)?,
        postReturn: ((LongArray) -> Unit)?,
    ) {
        val inst = task.inst
        val flatArgs = try {
            task.start()
        } catch (e: HostValueException) {
            if (e.handled) return else throw e
        }
        if (!task.optsAsync) {
            val flatResults = callee(flatArgs)
            task.return_(flatResults)
            store.onSwitch(thread)
            postReturn?.let { pr -> host.withoutLeaving { pr(flatResults) } }
            task.exitImplicitThread()
            return
        }
        if (callback == null) {
            callee(flatArgs)
            task.exitImplicitThread()
            return
        }
        var packed = callee(flatArgs)[0].toInt()
        var code = unpackCode(packed)
        while (code != CALLBACK_EXIT) {
            val event: Event
            if (task.deliverPendingCancel()) {
                event = Event(EventCode.TASK_CANCELLED, 0, 0)
            } else {
                inst.exclusiveThread = null
                thread.cancellable = true
                event = when (code) {
                    CALLBACK_YIELD -> {
                        thread.yieldUntil { inst.exclusiveThread == null }
                        if (task.deliverPendingCancel()) Event(EventCode.TASK_CANCELLED, 0, 0) else Event(EventCode.NONE, 0, 0)
                    }
                    else -> {
                        val wset = inst.handles.get(packed ushr 4) as? WaitableSet ?: trap("The callback did not return a waitable set to wait on")
                        wset.waitFromCallback(thread)
                    }
                }
                thread.cancellable = false
                inst.exclusiveThread = thread
            }
            packed = callback(longArrayOf(event.code.toLong(), event.p1.toLong(), event.p2.toLong()))[0].toInt()
            code = unpackCode(packed)
        }
        task.exitImplicitThread()
    }

    private fun unpackCode(packed: Int): Int {
        val code = packed and 0xf
        if (code > CALLBACK_WAIT) trap("unsupported callback code: $code")
        return code
    }

    // ------------------------------------------------------------------- the session's thread

    /**
     * Runs the scheduler (ADR-0019): every operation on the async state happens on this one thread.
     * It resumes guest threads, runs the coroutines of host imports and host streams, and resumes
     * the suspended host calls. A synchronous call waiting for the host can run its queue inline.
     */
    private inner class SessionLoop : CoroutineDispatcher() {
        private val queue = LinkedBlockingQueue<Runnable>()

        @Volatile
        var stopped = false

        /** The thread running the queue; it exits when idle, so an unclosed instance can be collected. */
        @Volatile
        var thread: Thread? = null
            private set

        private fun loop() {
            while (true) {
                val r = try {
                    queue.poll(IDLE_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: InterruptedException) {
                    null
                }
                if (r == null) {
                    synchronized(this) {
                        if (queue.isEmpty() || stopped) {
                            thread = null
                            return
                        }
                    }
                    continue
                }
                runCatching { r.run() }
            }
        }

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            // Once closed, finish what is left on the caller's thread so no coroutine hangs.
            if (stopped) return block.run()
            synchronized(this) {
                queue.add(block)
                if (thread == null) {
                    thread = Thread(null, ::loop, "wasm2kt-session", 8L shl 20).apply {
                        isDaemon = true
                        start()
                    }
                }
            }
        }

        fun hasQueued(): Boolean = queue.isNotEmpty()

        /** Runs one queued task on this thread (from a synchronous call that waits for the host). */
        fun runOne() {
            queue.take().run()
        }

        fun stop() {
            stopped = true
            while (true) (queue.poll() ?: break).run()
        }
    }

    @Volatile
    private var started: SessionLoop? = null

    /** Started on first use: components are often opened and never call anything async. */
    private val loop: SessionLoop
        get() = started ?: synchronized(this) { started ?: SessionLoop().also { started = it } }

    /** The coroutines of host imports and host streams; cancelled when the instance closes. */
    private val scope by lazy {
        // Failures of host imports and host streams are handled where they complete (a trap, or the
        // end of the stream), not by the default handler (which would crash an Android app).
        CoroutineScope(SupervisorJob() + loop + kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> })
    }

    private fun onLoopThread(): Boolean = started?.thread.let { it != null && it === Thread.currentThread() }

    /** Whether the caller runs the async state: the session's thread or a guest thread it resumed. */
    fun isScheduling(): Boolean = onLoopThread() || StackSwitching.currentHandler() != null

    /** Runs [block] on the session's thread and waits for it (at once if already there). */
    fun <T> onSession(block: () -> T): T {
        if (isScheduling()) return block()
        val task = FutureTask(block)
        loop.dispatch(EmptyCoroutineContext, task)
        try {
            return task.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** Runs [block] on the session's thread later. */
    private fun post(block: () -> Unit) = loop.dispatch(EmptyCoroutineContext, Runnable(block))

    /** A host call of an export in progress. */
    private class PendingCall(val name: String, val cont: CancellableContinuation<WitReader>) {
        /** The host cancelled the call: the task runs until it resolves, but nobody waits for its result. */
        var cancelled = false
    }

    private val pendingCalls = ArrayList<PendingCall>()

    /** Host imports, streams and futures whose coroutine may still produce something. */
    private var hostWork = 0

    /** Nested in a synchronous call's loop: that loop picks the threads to run (see [canonLift]). */
    private var syncLoops = 0

    /**
     * Runs the guest threads that are ready while the host waits for a call or a read, and resumes
     * what it waits for. Traps when the host waits but nothing can make progress any more. Called on
     * the session's thread.
     */
    private fun drain() {
        if (syncLoops > 0) return
        // Guest threads resumed from here see the session's host resources (ADR-0008) and backend.
        val previous = HostResourceTable.currentOrNull()
        val previousBackend = ComponentBackend.currentOrNull()
        hostResources?.let { HostResourceTable.setCurrent(it) }
        ComponentBackend.setCurrent(host.backend)
        try {
            while (true) {
                completeHostReads()
                // As in Wasmtime, tasks run while the host waits for something: a task that keeps
                // yielding after its call returned waits for the next call instead of spinning.
                if (pendingCalls.isEmpty() && hostReads.isEmpty()) {
                    pumpHostWriters()
                    return
                }
                if (host.guest { store.tick() }) {
                    // Let queued work (a resumed call, a host import that finished) have its turn.
                    if (loop.hasQueued()) {
                        post { drain() }
                        return
                    }
                    continue
                }
                if (pumpHostWriters()) continue
                break
            }
            if ((pendingCalls.isNotEmpty() || hostReads.isNotEmpty()) && hostWork == 0 && !loop.hasQueued()) {
                // A cancelled task that cannot finish is nobody's problem any more.
                pendingCalls.removeAll { it.cancelled }
                if (pendingCalls.isEmpty() && hostReads.isEmpty()) return
            }
            // Readable ends whose peer writer is actively COPYING still await host reads or drop
            // between `witStream` chunk calls; exclude ends already tracked in `hostReads` so an
            // active read cannot mask itself when `hostReads` is non-empty. Trade-off: if the host
            // leaks an unread returned end while awaiting a dependent call, the wait remains
            // suspended until cancelled or closed rather than trapping immediately as a deadlock.
            val hostCanRead = hostObjects.values.any {
                it is End && it.isReadable && it.other?.state == End.EndState.COPYING && hostReads.none { r -> r.end === it }
            }
            if ((pendingCalls.isNotEmpty() || hostReads.isNotEmpty()) && hostWork == 0 && !loop.hasQueued() && !hostCanRead) {
                val waiting = pendingCalls.joinToString { "'${it.name}'" }.ifEmpty { "a stream or future the host reads" }
                host.poison("deadlock detected: $waiting cannot finish because every task is waiting")
            }
        } catch (e: Throwable) {
            failAll(if (e is HostValueException) e.cause!! else e)
        } finally {
            HostResourceTable.setCurrent(previous)
            ComponentBackend.setCurrent(previousBackend)
        }
    }

    /** The session's host resource table, as the first call that had one bound saw it. */
    @Volatile
    private var hostResources: HostResourceTable? = null

    /** A trap: every host call in progress fails with it (the instance is poisoned). */
    private fun failAll(e: Throwable) {
        val calls = pendingCalls.toList()
        pendingCalls.clear()
        val reads = hostReads.toList()
        hostReads.clear()
        for (call in calls) if (!call.cancelled) call.cont.resumeWithException(e)
        for (read in reads) read.cont.resumeWithException(e)
    }

    /** After a trap: guest threads still blocked can never run again; host calls in progress fail with the trap. */
    fun abandon() {
        store.abandon()
        cleanupSyncCalls(0)
    }

    fun close() {
        val l = started
        if (l == null) {
            store.abandon()
            hostObjects.clear()
            hostWriters.clear()
            return
        }
        onSession {
            if (pendingCalls.isNotEmpty() || hostReads.isNotEmpty()) failAll(IllegalStateException("The component instance was closed"))
            store.abandon()
            scope.cancel()
            hostObjects.clear()
            hostWriters.clear()
        }
        l.stop()
    }

    // ------------------------------------------------------------------- host calls

    /**
     * A host call of an export of a component that uses the async ABI (ADR-0019): suspends until the
     * task returns. Cancelling the coroutine requests the task's cancellation.
     */
    suspend fun invokeSuspending(export: AsyncExportSpec, args: WitReader): WitReader = withContext(loop + host.asContextElement()) {
        host.checkUsable()
        if (hostResources == null) hostResources = HostResourceTable.currentOrNull()
        val functionName = export.qualifiedName
        suspendCancellableCoroutine { cont ->
            val call = PendingCall(functionName, cont)
            pendingCalls += call
            val task = try {
                host.guest {
                    startExport(export, args) { result ->
                        if (pendingCalls.remove(call)) cont.resumeWith(result)
                    }
                }
            } catch (e: Throwable) {
                pendingCalls.remove(call)
                throw e
            }
            cont.invokeOnCancellation {
                post {
                    if (call in pendingCalls && !call.cancelled) {
                        call.cancelled = true
                        try {
                            host.guest { if (task.state != Task.State.RESOLVED) task.requestCancellation() }
                        } catch (e: Throwable) {
                            failAll(e)
                        }
                        drain()
                    }
                }
            }
            drain()
        }
    }

    /** A blocking host call ([wasm2kt.runtime.ComponentBackend.invoke]): [invokeSuspending] on this thread. */
    fun invoke(export: AsyncExportSpec, args: WitReader): WitReader {
        val functionName = export.qualifiedName
        // A synchronous host import calling back into the component (the spec's may_enter).
        if (StackSwitching.currentHandler() != null) host.poison("Cannot reenter component instance: '$functionName' was called while another call into it is in progress")
        check(!onLoopThread()) { "'$functionName' was called synchronously from a suspending host import; call it from the coroutine instead" }
        return runBlocking { invokeSuspending(export, args) }
    }

    /** Starts the task of a host call; [done] receives its results once it returns. */
    private fun startExport(export: AsyncExportSpec, args: WitReader, done: (Result<WitReader>) -> Unit): Task {
        val functionName = export.qualifiedName
        val type = export.type
        val opts = host.canonOptions(export.optionsIndex)
        val inst = state(opts.instance)
        lateinit var task: Task
        val cx = host.context(export.optionsIndex, AbiBoundaryDirection.HOST_TO_GUEST) { task }
        val paramTypes = type.paramTypes
        val flatParamCount = host.mapUnsupported { type.flatParamCount }
        val onStart = {
            // Lowering the host's arguments: errors in the values are the host's, not traps (ADR-0007).
            val coreArgs: LongArray
            try {
                val lowerer = Lowerer(cx, args)
                coreArgs = host.mapUnsupported {
                    if (flatParamCount > CanonicalAbi.MAX_FLAT_PARAMS) {
                        longArrayOf(lowerer.storeTuple(type.paramTuple).toLong())
                    } else if (flatParamCount == 0) {
                        CanonicalAbi.EMPTY_LONG_ARRAY
                    } else {
                        LongArray(flatParamCount).also { out ->
                            var pos = 0
                            for (pt in paramTypes) pos = lowerer.lowerFlat(pt, out, pos)
                        }
                    }
                }
                if (args.remaining != 0) throw IllegalArgumentException("${args.remaining} unread bytes after the arguments of '$functionName'")
            } catch (e: ComponentTrapException) {
                runCatching {
                    cx.rollbackLowering()
                    task.releaseLenders()
                }.onFailure { e.addSuppressed(it) }
                throw e
            } catch (e: RuntimeException) {
                if (e !is IllegalArgumentException && e !is IllegalStateException) throw e
                runCatching {
                    cx.rollbackLowering()
                    task.releaseLenders()
                }.onFailure { e.addSuppressed(it) }
                // The task never started: it leaves the instance as if it had not been called.
                task.state = Task.State.RESOLVED
                task.exitImplicitThread()
                done(Result.failure(e))
                throw HostValueException(e, handled = true)
            }
            cx.commitMoves()
            coreArgs
        }
        val onResolve = { flat: LongArray? ->
            cx.releaseLends()
            if (flat == null) {
                done(Result.failure(CancellationException("'$functionName' was cancelled")))
            } else {
                val results = writerFor(type.results)
                val lifter = Lifter(cx, results)
                val limit = if (opts.async) CanonicalAbi.MAX_FLAT_PARAMS else CanonicalAbi.MAX_FLAT_RESULTS
                host.mapUnsupported {
                    if (type.flatResultCount > limit) {
                        lifter.loadTuple(flat[0].toInt(), type.resultTuple)
                    } else {
                        val reader = Lifter.FlatReader(flat)
                        type.results.forEach { lifter.liftFlat(it, reader) }
                    }
                }
                done(Result.success(results.toReader()))
            }
        }
        return canonLift(
            inst, type.async, opts.async,
            callee = export.callee,
            callback = opts.callback,
            postReturn = opts.postReturn,
            checkReturn = { tr, trOpts -> tr.results == type.results && sameLiftOptions(trOpts, opts) },
            onStart = onStart,
            onResolve = onResolve,
            createdTask = { task = it },
        )
    }

    /** As in Wasmtime: a `task.return` that needs no memory may leave it out. */
    private fun sameLiftOptions(a: AsyncCanonOptions, b: AsyncCanonOptions) =
        a.stringEncoding == b.stringEncoding && (a.memoryIndex == null || a.memoryIndex == b.memoryIndex)

    // ------------------------------------------------------------------- host imports

    /**
     * Calls the suspending host function [fn] for the guest's call of an `async func` import, in a
     * coroutine on the session's thread with the session's host resources (ADR-0019). [onDone]
     * runs on the session's thread once it finished (null cause), failed or was cancelled.
     */
    private fun launchHostImport(fn: AsyncHostFunction, args: WitReader, results: WitWriter, onDone: (Throwable?) -> Unit): Job {
        val resources = HostResourceTable.currentOrNull()
        val context = host.asContextElement() + (resources?.asContextElement() ?: EmptyCoroutineContext)
        hostWork++
        val job = scope.launch(context) { fn.call(args, results) }
        job.invokeOnCompletion { cause ->
            post {
                hostWork--
                onDone(cause)
                drain()
            }
        }
        return job
    }

    private fun writerFor(types: List<*>): WitWriter =
        if (types.isEmpty()) WitWriter(0) else WitWriter()

    /** The guest's arguments for a call of a host import, lifted into the binary format. */
    private fun liftImportArgs(type: FuncType, cx: AbiContext, coreArgs: LongArray, maxFlat: Int): WitWriter {
        val args = writerFor(type.params)
        val lifter = Lifter(cx, args)
        val paramTypes = type.paramTypes
        if (type.flatParamCount > maxFlat) {
            lifter.loadTuple(coreArgs[0].toInt(), type.paramTuple)
        } else {
            val reader = Lifter.FlatReader(coreArgs)
            paramTypes.forEach { lifter.liftFlat(it, reader) }
        }
        return args
    }

    private fun checkImportResults(reader: WitReader, key: String) {
        if (reader.remaining != 0) throw IllegalStateException("${reader.remaining} unread bytes after the results of host import '$key'")
    }

    /** A host import failed: the component cannot continue (ADR-0019). */
    private fun importFailed(key: String, cause: Throwable): Nothing {
        if (cause is ComponentTrapException) throw cause
        throw ComponentTrapException("Host import '$key' failed: $cause", cause)
    }

    /**
     * A guest calling a host import with `async` lowering. A suspending host function runs in a
     * coroutine while the guest goes on; a blocking one completes the call at once.
     */
    fun callHostAsync(importIndex: Int, type: FuncType, optionsIndex: Int, coreArgs: LongArray): LongArray {
        val cx = host.context(optionsIndex, AbiBoundaryDirection.GUEST_TO_HOST)
        val key = host.importDispatchKey(importIndex)
        val args = liftImportArgs(type, cx, coreArgs, CanonicalAbi.MAX_FLAT_ASYNC_PARAMS)
        val retPtr = if (type.results.isNotEmpty()) coreArgs[coreArgs.size - 1].toInt() else null
        val fn = if (type.async) host.asyncHostImport(importIndex) else null
        if (fn == null) {
            val results = writerFor(type.results)
            host.bind { host.guard.hostImport { host.hostImport(importIndex).call(args.toReader(), results) } }
            val reader = results.toReader()
            if (retPtr != null) Lowerer(cx, reader).storeTupleAt(type.resultTuple, retPtr)
            checkImportResults(reader, key)
            return longArrayOf(Subtask.State.RETURNED.toLong())
        }
        val caller = currentThread()
        val subtask = Subtask()
        subtask.state = Subtask.State.STARTED
        val subtaski = caller.task.inst.handles.add(subtask)
        val results = writerFor(type.results)
        val job = launchHostImport(fn, args.toReader(), results) { cause ->
            if (subtask.resolved()) return@launchHostImport
            when {
                cause == null -> {
                    try {
                        host.guest {
                            val reader = results.toReader()
                            if (retPtr != null) Lowerer(cx, reader).storeTupleAt(type.resultTuple, retPtr)
                            checkImportResults(reader, key)
                        }
                    } catch (e: Throwable) {
                        failAll(host.asTrap(e))
                        return@launchHostImport
                    }
                    subtask.resolve(Subtask.State.RETURNED, LongArray(0))
                }
                cause is CancellationException && subtask.cancellationRequested ->
                    subtask.resolve(Subtask.State.CANCELLED_BEFORE_RETURNED, LongArray(0))
                else -> {
                    failAll(runCatching { host.guest { importFailed(key, cause) } }.exceptionOrNull()!!)
                    return@launchHostImport
                }
            }
            subtask.setPendingEvent {
                if (subtask.resolved()) subtask.deliverResolve()
                Event(EventCode.SUBTASK, subtaski, subtask.state)
            }
        }
        subtask.onCancel = { job.cancel() }
        return longArrayOf((Subtask.State.STARTED or (subtaski shl 4)).toLong())
    }

    /**
     * A guest calling a host import synchronously. For an `async func` with a suspending host
     * function, the guest's thread waits (other tasks run meanwhile); a task that may not block runs
     * the host function to completion on its thread instead. Returns false when suspending dispatch
     * does not apply.
     */
    fun callHostSync(importIndex: Int, type: FuncType, invoke: (HostFunction) -> Unit): Boolean {
        if (!type.async) return false
        val fn = host.asyncHostImport(importIndex) ?: return false
        val thread = currentThreadOrNull()
        if (thread == null || !thread.task.mayBlock()) {
            invoke(HostFunction { a, r ->
                val context = host.asContextElement() + (HostResourceTable.currentOrNull()?.asContextElement() ?: EmptyCoroutineContext)
                runBlocking(context) { fn.call(a, r) }
            })
            return true
        }
        invoke(
            HostFunction { a, r ->
                var outcome: Throwable? = null
                var finished = false
                launchHostImport(fn, a, r) { cause ->
                    outcome = cause
                    finished = true
                }
                thread.waitUntil { finished }
                outcome?.let { importFailed(host.importDispatchKey(importIndex), it) }
            },
        )
        return true
    }

    // ------------------------------------------------------------------- intrinsics

    fun contextGet(slot: Int): Int = currentThreadOrNull()?.storage?.get(slot) ?: 0

    fun contextSet(slot: Int, value: Int) {
        currentThreadOrNull()?.storage?.set(slot, value)
    }

    /**
     * A task of a non-`async` function may not block before it returns (ADR-0018), unless another
     * thread of its instance is ready to run in the meantime.
     */
    private fun checkBlocking() {
        val thread = currentThread()
        if (thread.task.mayBlock()) return
        val inst = thread.task.inst
        if (store.waiting.none { it !== thread && it.task.inst === inst && it.ready() }) {
            trap("cannot block a synchronous task before returning")
        }
    }

    private fun checkMayLeave(what: String) = host.checkMayLeave(what)

    private fun memoryOf(options: Int?): CanonicalMemory {
        val opts = options?.let { host.canonOptions(it) } ?: throw IllegalStateException("This built-in needs canonical options")
        return opts.memory() ?: throw IllegalStateException("This built-in needs a memory")
    }

    fun invokeTrampoline(t: AsyncTrampolineSpec, instance: AsyncCoreInstance?, args: LongArray): LongArray? = when (t.kind) {
        "backpressure-inc" -> {
            val inst = state(t.instance!!)
            if (inst.backpressure == 0xFFFF) trap("backpressure counter overflow")
            inst.backpressure++
            null
        }
        "backpressure-dec" -> {
            val inst = state(t.instance!!)
            if (inst.backpressure == 0) trap("backpressure counter underflow")
            inst.backpressure--
            null
        }
        "task-return" -> {
            val task = currentTask()
            checkMayLeave("task.return")
            if (!task.optsAsync) trap("task.return can only be called by an async-lifted export")
            if (!task.checkReturn(t, host.canonOptions(t.options!!))) trap("invalid task.return signature and/or options for the current task")
            task.return_(args)
            store.onSwitch(currentThreadOrNull())
            null
        }
        "task-cancel" -> {
            val task = currentTask()
            checkMayLeave("task.cancel")
            if (!task.optsAsync) trap("task.cancel can only be called by an async-lifted export")
            task.cancel()
            null
        }
        "waitable-set-new" -> {
            checkMayLeave("waitable-set.new")
            longArrayOf(state(t.instance!!).handles.add(WaitableSet()).toLong())
        }
        "waitable-set-wait" -> {
            checkMayLeave("waitable-set.wait")
            val inst = state(t.instance!!)
            val wset = inst.handles.get(args[0].toInt()) as? WaitableSet ?: trap("Handle is not a waitable set")
            if (!wset.hasPendingEvent()) checkBlocking()
            val thread = currentThread()
            thread.cancellable = t.cancellable
            val event = wset.waitFor()
            thread.cancellable = false
            unpackEvent(memoryOf(t.options), args[1].toInt(), event)
        }
        "waitable-set-poll" -> {
            checkMayLeave("waitable-set.poll")
            val inst = state(t.instance!!)
            val wset = inst.handles.get(args[0].toInt()) as? WaitableSet ?: trap("Handle is not a waitable set")
            val task = currentTask()
            val event = if (t.cancellable && task.deliverPendingCancel()) Event(EventCode.TASK_CANCELLED, 0, 0) else wset.poll()
            unpackEvent(memoryOf(t.options), args[1].toInt(), event)
        }
        "waitable-set-drop" -> {
            checkMayLeave("waitable-set.drop")
            val inst = state(t.instance!!)
            val wset = inst.handles.getOrNull(args[0].toInt()) as? WaitableSet ?: trap("Handle is not a waitable set")
            wset.drop()
            inst.handles.remove(args[0].toInt())
            null
        }
        "waitable-join" -> {
            checkMayLeave("waitable.join")
            val inst = state(t.instance!!)
            val w = inst.handles.get(args[0].toInt()) as? Waitable ?: trap("Handle is not a waitable")
            if (w.hasSyncWaiter) trap("Cannot join a waitable that a synchronous call is waiting on")
            val si = args[1].toInt()
            if (si == 0) {
                w.join(null)
            } else {
                w.join(inst.handles.get(si) as? WaitableSet ?: trap("Handle is not a waitable set"))
            }
            null
        }
        "subtask-drop" -> {
            checkMayLeave("subtask.drop")
            val inst = state(t.instance!!)
            val s = inst.handles.get(args[0].toInt()) as? Subtask ?: trap("Handle is not a subtask")
            s.drop()
            inst.handles.remove(args[0].toInt())
            null
        }
        "subtask-cancel" -> longArrayOf(subtaskCancel(t, args[0].toInt()).toLong())
        "stream-new", "future-new" -> {
            checkMayLeave(t.kind.replace('-', '.'))
            val stream = t.kind == "stream-new"
            val table = t.table!!
            val inst = state(transmitTable(table, stream).instance)
            val (r, w) = newEnds(stream, table, inst)
            r.index = inst.handles.add(r)
            w.index = inst.handles.add(w)
            longArrayOf(unsigned(r.index!!) or (unsigned(w.index!!) shl 32))
        }
        "stream-read", "stream-write", "future-read", "future-write" -> {
            val stream = t.kind.startsWith("stream")
            val read = t.kind.endsWith("read")
            longArrayOf(unsigned(copy(t, stream, read, args[0].toInt(), args[1].toInt(), if (stream) args[2].toInt() else 1)))
        }
        "stream-cancel-read", "stream-cancel-write", "future-cancel-read", "future-cancel-write" -> {
            longArrayOf(unsigned(cancelCopy(t, t.kind.startsWith("stream"), t.kind.endsWith("read"), args[0].toInt())))
        }
        "stream-drop-readable", "stream-drop-writable", "future-drop-readable", "future-drop-writable" -> {
            dropEnd(t, t.kind.startsWith("stream"), t.kind.endsWith("readable"), args[0].toInt())
            null
        }
        "error-context-new" -> {
            checkMayLeave("error-context.new")
            val w = WitWriter()
            Lifter(host.context(t.options!!, AbiBoundaryDirection.GUEST_TO_HOST), w)
                .liftFlat(WitType.Str, Lifter.FlatReader(longArrayOf(args[0], args[1])))
            val message = w.toReader().string()
            longArrayOf(state(t.instance!!).handles.add(ErrorContext(message)).toLong())
        }
        "error-context-debug-message" -> {
            checkMayLeave("error-context.debug-message")
            val e = state(t.instance!!).handles.get(args[0].toInt()) as? ErrorContext ?: trap("Handle is not an error context")
            val w = WitWriter().apply { string(e.debugMessage) }
            Lowerer(host.context(t.options!!, AbiBoundaryDirection.GUEST_TO_HOST), w.toReader())
                .storeTupleAt(errorMessageTuple, args[1].toInt())
            null
        }
        "error-context-drop" -> {
            checkMayLeave("error-context.drop")
            val inst = state(t.instance!!)
            if (inst.handles.getOrNull(args[0].toInt()) !is ErrorContext) trap("Handle is not an error context")
            inst.handles.remove(args[0].toInt())
            null
        }
        "thread-index" -> {
            checkMayLeave("thread.index")
            longArrayOf((currentThread().index ?: 0).toLong())
        }
        "thread-new-indirect" -> longArrayOf(threadNewIndirect(t, args[0].toInt(), args[1]))
        "thread-resume-later" -> {
            checkMayLeave("thread.resume-later")
            val other = otherThread(args[0].toInt())
            if (!other.suspended()) trap("thread.resume-later: the thread is not suspended")
            other.resumeLater()
            null
        }
        "thread-yield" -> {
            checkMayLeave("thread.yield")
            val thread = currentThread()
            suspension(t) { thread.yield() }
            longArrayOf(cancelled(t))
        }
        "thread-suspend" -> {
            checkMayLeave("thread.suspend")
            val thread = currentThread()
            checkBlocking()
            suspension(t) { thread.suspend() }
            longArrayOf(cancelled(t))
        }
        "thread-suspend-then-resume", "thread-yield-then-resume" -> {
            checkMayLeave(t.kind.replace("thread-", "thread."))
            val thread = currentThread()
            val other = otherThread(args[0].toInt())
            if (other === thread) trap("A thread cannot switch to itself")
            if (!other.suspended()) trap("${t.kind}: the thread is not suspended")
            if (t.kind == "thread-suspend-then-resume") {
                // Switching to another thread is not blocking.
                suspension(t) { thread.suspendThenResume(other) }
            } else {
                suspension(t) { thread.yieldThenResume(other) }
            }
            longArrayOf(cancelled(t))
        }
        "thread-suspend-then-promote", "thread-yield-then-promote" -> {
            checkMayLeave(t.kind.replace("thread-", "thread."))
            val thread = currentThread()
            if (args[0].toInt() == thread.index) trap("A thread cannot switch to itself")
            val other = otherThread(args[0].toInt())
            if (t.kind == "thread-suspend-then-promote") {
                if (!other.ready()) checkBlocking()
                suspension(t) { thread.suspendThenPromote(other) }
            } else {
                suspension(t) { thread.yieldThenPromote(other) }
            }
            longArrayOf(cancelled(t))
        }
        "prepare-call" -> {
            prepareCall(t, args)
            null
        }
        "sync-start-call" -> startCall(t, instance!!, args[0], args[1].toInt(), 0, 0, sync = true)
        "async-start-call" -> startCall(t, instance!!, args[0], args[1].toInt(), args[2].toInt(), args[3].toInt(), sync = false)
        "enter-sync-call" -> {
            enterSyncCall(args[1].toInt() != 0, args[2].toInt())
            null
        }
        "exit-sync-call" -> {
            exitSyncCall()
            null
        }
        "stream-transfer", "future-transfer" -> {
            val stream = t.kind == "stream-transfer"
            val src = args[1].toInt()
            val dst = args[2].toInt()
            val end = liftAsyncValue(state(transmitTable(src, stream).instance), src, args[0].toInt(), stream)
            longArrayOf(lowerAsyncValue(end, dst, stream).toLong())
        }
        "error-context-transfer" -> {
            val e = state(errorContextInstance(args[1].toInt())).handles.get(args[0].toInt()) as? ErrorContext
                ?: trap("Handle is not an error context")
            longArrayOf(state(errorContextInstance(args[2].toInt())).handles.add(e).toLong())
        }
        "resource-transfer-own" -> {
            val rep = liftOwn(args[1].toInt(), args[0].toInt())
            longArrayOf(host.resourceTable(args[2].toInt()).insert(rep, own = true).toLong())
        }
        "resource-transfer-borrow" -> longArrayOf(transferBorrow(args[0].toInt(), args[1].toInt(), args[2].toInt()).toLong())
        else -> throw UnsupportedAsyncIntrinsicException(t.kind)
    }

    private fun unpackEvent(memory: CanonicalMemory, ptr: Int, e: Event): LongArray {
        checkRange(memory, ptr, 8, 4, "event pointer")
        memory.writeInt(ptr, e.p1)
        memory.writeInt(ptr + 4, e.p2)
        return longArrayOf(e.code.toLong())
    }

    private fun otherThread(index: Int): GuestThread {
        val inst = currentTask().inst
        return inst.threads.getOrNull(index) as? GuestThread ?: trap("Unknown thread index ${unsigned(index)}")
    }

    /** Runs a suspension; a `cancellable` one lets a cancellation request resume the thread. */
    private inline fun suspension(t: AsyncTrampolineSpec, block: () -> Unit) {
        val thread = currentThread()
        thread.cancellable = t.cancellable
        try {
            block()
        } finally {
            thread.cancellable = false
        }
    }

    /** The result of a cancellable suspension: 1 when a pending cancellation was delivered. */
    private fun cancelled(t: AsyncTrampolineSpec): Long =
        if (t.cancellable && currentTask().deliverPendingCancel()) 1 else 0

    private fun threadNewIndirect(t: AsyncTrampolineSpec, fi: Int, closure: Long): Long {
        val task = currentTask()
        checkMayLeave("thread.new-indirect")
        val table = host.table(t.table!!) ?: throw IllegalStateException("thread.new-indirect: unknown table ${t.table}")
        val startFn = table.newThreadStart(fi, closure)
        lateinit var newThread: GuestThread
        newThread = GuestThread(task) {
            startFn()
            task.unregisterThread(newThread)
        }
        task.registerThread(newThread)
        return newThread.index!!.toLong()
    }

    // ------------------------------------------------------------------- subtasks

    private fun subtaskCancel(t: AsyncTrampolineSpec, i: Int): Int {
        val thread = currentThread()
        checkMayLeave("subtask.cancel")
        val subtask = thread.task.inst.handles.get(i) as? Subtask ?: trap("Handle is not a subtask")
        if (subtask.resolveDelivered()) trap("subtask.cancel: the subtask has already returned")
        if (subtask.cancellationRequested) trap("subtask.cancel: cancellation was already requested")
        if (subtask.inWaitableSet()) trap("subtask.cancel: the subtask is in a waitable set")
        if (!subtask.resolved()) {
            subtask.cancellationRequested = true
            subtask.hasSyncWaiter = true
            subtask.onCancel!!()
            if (!t.async && !subtask.resolved()) {
                checkBlocking()
                thread.waitUntil(subtask::resolved)
            }
            subtask.hasSyncWaiter = false
            if (!subtask.resolved()) return BLOCKED
        }
        subtask.getPendingEvent()
        return subtask.state
    }

    /** A call prepared by `prepare-call`, started by the next `start-call` on the same thread. */
    private class PreparedCall(
        val start: Long,
        val return_: Long,
        val calleeInstance: Int,
        val taskReturnType: Int,
        val calleeAsync: Boolean,
        val stringEncoding: Int,
        val memory: Int?,
        val resultCountOrMax: Int,
        val params: LongArray,
    )

    private val prepared = HashMap<GuestThread, PreparedCall>()

    private fun prepareCall(t: AsyncTrampolineSpec, args: LongArray) {
        val thread = currentThread()
        prepared[thread] = PreparedCall(
            start = args[0], return_ = args[1], calleeInstance = args[3].toInt(), taskReturnType = args[4].toInt(),
            calleeAsync = args[5].toInt() != 0, stringEncoding = args[6].toInt(), memory = t.memory,
            resultCountOrMax = args[7].toInt(), params = args.copyOfRange(8, args.size),
        )
    }

    /**
     * Starts a call prepared by `prepare-call` (a guest-to-guest call through a fused adapter whose
     * caller lowered it `async` or whose callee lifted it `async`): the reference's `canon_lower` on
     * the caller's side and `canon_lift` on the callee's.
     */
    private fun startCall(t: AsyncTrampolineSpec, instance: AsyncCoreInstance, callee: Long, paramCount: Int, resultCount: Int, flags: Int, sync: Boolean): LongArray {
        val caller = currentThread()
        val p = prepared.remove(caller) ?: throw IllegalStateException("start-call without prepare-call")
        val subtask = Subtask()
        // Where the caller wants the results: a return pointer, or (sync, one flat result) the stack.
        val asyncCaller = !sync
        val heapResults: Int? = when {
            asyncCaller && p.resultCountOrMax == PREPARE_ASYNC_WITH_RESULT -> p.params.last().toInt()
            asyncCaller -> null
            p.resultCountOrMax > CanonicalAbi.MAX_FLAT_RESULTS -> p.params.last().toInt()
            else -> null
        }
        val callerParams = if (asyncCaller && heapResults != null) p.params.copyOf(p.params.size - 1) else p.params
        var syncResult = LongArray(0)
        var onProgress: () -> Unit = {}
        val onStart = {
            onProgress()
            subtask.state = Subtask.State.STARTED
            // The adapter's `start` lifts the caller's arguments and lowers them for the callee.
            callRef(instance, p.start, callerParams).copyOf(paramCount)
        }
        val onResolve = { flat: LongArray? ->
            onProgress()
            if (flat == null) {
                subtask.resolve(
                    if (subtask.state == Subtask.State.STARTING) Subtask.State.CANCELLED_BEFORE_STARTED else Subtask.State.CANCELLED_BEFORE_RETURNED,
                    LongArray(0),
                )
            } else {
                // The adapter's `return` lifts the callee's results and lowers them for the caller,
                // as the caller (whose realloc may run).
                val h = StackSwitching.currentHandler()
                val previous = h?.currentThread
                h?.currentThread = caller
                try {
                    syncResult = callRef(instance, p.return_, if (heapResults != null) flat + heapResults.toLong() else flat)
                } finally {
                    if (h != null && previous != null) h.currentThread = previous
                }
                subtask.resolve(Subtask.State.RETURNED, LongArray(0))
            }
        }
        val calleeInst = state(p.calleeInstance)
        val calleeAsyncLift = sync || (flags and START_FLAG_ASYNC_CALLEE) != 0
        val callback = t.callback?.let { host.callback(it) }
        val postReturn = t.postReturn?.let { host.postReturn(it) }
        val task = canonLift(
            calleeInst, p.calleeAsync, if (sync) true else calleeAsyncLift,
            callee = { a -> callRef(instance, callee, a) },
            callback = callback,
            postReturn = postReturn,
            checkReturn = { tr, opts ->
                tr.resultsIndex == p.taskReturnType && (opts.memoryIndex == null || opts.memoryIndex == p.memory) && encodingCode(opts.stringEncoding) == p.stringEncoding
            },
            onStart = onStart,
            onResolve = onResolve,
        )
        subtask.onCancel = task::requestCancellation
        if (sync) {
            if (!subtask.resolved()) {
                if (p.calleeAsync) checkBlocking()
                caller.waitUntil(subtask::resolved)
            }
            subtask.deliverResolve()
            return syncResult
        }
        if (subtask.resolved()) {
            subtask.deliverResolve()
            return longArrayOf(Subtask.State.RETURNED.toLong())
        }
        val subtaski = caller.task.inst.handles.add(subtask)
        onProgress = {
            subtask.setPendingEvent {
                if (subtask.resolved()) subtask.deliverResolve()
                Event(EventCode.SUBTASK, subtaski, subtask.state)
            }
        }
        return longArrayOf((subtask.state or (subtaski shl 4)).toLong())
    }

    private fun callRef(instance: AsyncCoreInstance, ref: Long, args: LongArray): LongArray =
        instance.callRef(ref.toInt(), args)

    private fun encodingCode(e: StringEncoding): Int = when (e) {
        StringEncoding.UTF8 -> 0
        StringEncoding.UTF16 -> 1
        StringEncoding.LATIN1_UTF16 -> 2
    }

    private class SyncCallFrame(val caller: GuestThread?, val callee: GuestThread)

    /** The synchronous guest-to-guest calls in progress on this JVM thread. */
    private val syncCalls = ThreadLocal.withInitial { ArrayList<SyncCallFrame>() }

    /** Fused adapters call this around a synchronous guest-to-guest call: the callee's task. */
    private fun enterSyncCall(calleeAsync: Boolean, calleeInstance: Int) {
        val task = Task(state(calleeInstance), calleeAsync, false, false, { _, _ -> false }, { LongArray(0) }, {})
        val thread = GuestThread(task, null)
        val existing = StackSwitching.currentHandler()
        val caller = existing?.currentThread
        val handler = existing ?: StackSwitching.installHostHandler(thread)
        syncCalls.get() += SyncCallFrame(caller, thread)
        if (caller != null) thread.syncCaller = caller
        handler.currentThread = thread
        store.onSwitch(thread)
        if (!task.enterImplicitThread(thread)) trap("The call was cancelled before it started")
        task.state = Task.State.STARTED
    }

    private fun finishSyncCall(frame: SyncCallFrame, handler: Handler?) {
        val thread = frame.callee
        val task = thread.task
        try {
            task.releaseLenders()
            task.state = Task.State.RESOLVED
            if (task.implicitThread === thread && thread in task.threads) {
                task.exitImplicitThread()
            }
        } finally {
            thread.running = false
            val caller = frame.caller
            if (caller == null) {
                // The host's own stack: nothing ran below the call.
                StackSwitching.removeHostHandler()
                store.onSwitch(null)
            } else {
                handler?.currentThread = caller
                store.onSwitch(caller)
            }
        }
    }

    private fun exitSyncCall() {
        val handler = StackSwitching.currentHandler() ?: throw IllegalStateException("exit-sync-call without enter-sync-call")
        val frames = syncCalls.get()
        if (frames.isEmpty()) throw IllegalStateException("exit-sync-call without enter-sync-call")
        val frame = frames.removeAt(frames.size - 1)
        try {
            if (frame.callee.task.numBorrows > 0) trap("borrow handles still remain at the end of the call")
        } finally {
            finishSyncCall(frame, handler)
        }
    }

    fun syncCallDepth(): Int = syncCalls.get().size

    fun cleanupSyncCalls(baseDepth: Int) {
        val frames = syncCalls.get()
        while (frames.size > baseDepth) {
            finishSyncCall(frames.removeAt(frames.size - 1), StackSwitching.currentHandler())
        }
    }

    // ------------------------------------------------------------------- resources between instances

    fun liftOwn(table: Int, handle: Int): Int {
        val t = host.resourceTable(table)
        val h = t.slotOrNull(handle) ?: trap("Unknown resource handle $handle")
        if (!h.own) trap("Cannot pass borrowed resource handle $handle as own")
        if (h.numLends != 0) trap("Cannot pass resource handle $handle as own while it is lent")
        t.remove(handle)
        return h.rep
    }

    private fun transferBorrow(handle: Int, srcTable: Int, dstTable: Int): Int {
        val h = host.resourceTable(srcTable).slotOrNull(handle) ?: trap("Unknown resource handle $handle")
        val scope = currentThreadOrNull()?.task
        if (h.own && scope != null) {
            h.numLends++
            scope.lenders.add(h)
        }
        val dst = host.resourceTableInfo(dstTable)
        // Borrowing into the instance that defines the resource passes its representation.
        if (dst != null && !dst.imported && dst.definedInInstance >= 0 && dst.definedInInstance == dst.instance) return h.rep
        scope?.let { it.numBorrows++ }
        return host.resourceTable(dstTable).insert(h.rep, own = false, scope = scope)
    }

    // ------------------------------------------------------------------- streams and futures

    private fun transmitTable(table: Int, stream: Boolean): AsyncTransmitTableInfo =
        host.transmitTableInfo(table, stream)

    private fun errorContextInstance(table: Int): Int = host.errorContextInstance(table)

    private inner class GuestEnd(stream: Boolean, readable: Boolean, table: Int, owner: ComponentInstanceState?) : End(stream, readable, table, owner) {
        override val elementIsPlain: Boolean
            get() = transmitTable(table, isStream).element?.let(::isPlain) ?: true
    }

    private fun isPlain(t: WitType): Boolean = when (t) {
        WitType.Bool, WitType.S8, WitType.U8, WitType.S16, WitType.U16, WitType.S32, WitType.U32,
        WitType.S64, WitType.U64, WitType.F32, WitType.F64, WitType.Char -> true
        else -> false
    }

    private fun newEnds(stream: Boolean, table: Int, owner: ComponentInstanceState?): Pair<End, End> {
        val r = GuestEnd(stream, true, table, owner)
        val w = GuestEnd(stream, false, table, owner)
        r.other = w
        w.other = r
        return r to w
    }

    private fun endOf(t: AsyncTrampolineSpec, stream: Boolean, readable: Boolean, i: Int): Pair<ComponentInstanceState, End> {
        val inst = state(transmitTable(t.table!!, stream).instance)
        val end = inst.handles.get(i) as? End ?: trap("Handle is not a ${if (stream) "stream" else "future"} end")
        if (end.isStream != stream || end.isReadable != readable) trap("Handle is not the ${if (readable) "readable" else "writable"} end of a ${if (stream) "stream" else "future"}")
        if (end.table != t.table) trap("The ${if (stream) "stream" else "future"} has a different type")
        return inst to end
    }

    private fun copy(t: AsyncTrampolineSpec, stream: Boolean, read: Boolean, i: Int, ptr: Int, length: Int): Int {
        checkMayLeave(t.kind.replace('-', '.'))
        val (inst, end) = endOf(t, stream, read, i)
        if (end.state != End.EndState.IDLE) {
            trap(if (end.state == End.EndState.DONE) "the other end was dropped or the future was already written" else "a copy is already in progress")
        }
        val opts = host.canonOptions(t.options!!)
        if (end.inWaitableSet() && !opts.async) trap("A synchronous copy cannot use an end that is in a waitable set")
        val element = transmitTable(t.table!!, stream).element
        val buffer = GuestBuffer(element, opts, inst, ptr, length)
        end.copy(buffer)
        if (!end.hasPendingEvent()) {
            if (!opts.async) {
                checkBlocking()
                end.waitForPendingEvent()
            } else {
                return BLOCKED
            }
        }
        val e = end.getPendingEvent()
        check(e.p1 == i || e.p1 == 0)
        return e.p2
    }

    private fun cancelCopy(t: AsyncTrampolineSpec, stream: Boolean, readable: Boolean, i: Int): Int {
        checkMayLeave(t.kind.replace('-', '.'))
        val (_, end) = endOf(t, stream, readable, i)
        if (end.state != End.EndState.COPYING) trap("No copy is in progress")
        if (end.hasSyncWaiter) trap("A synchronous copy is in progress")
        if (end.inWaitableSet() && !t.async) trap("A synchronous cancel cannot use an end that is in a waitable set")
        end.cancel()
        if (!end.hasPendingEvent()) {
            if (!t.async) {
                checkBlocking()
                end.waitForPendingEvent()
            } else {
                return BLOCKED
            }
        }
        return end.getPendingEvent().p2
    }

    private fun dropEnd(t: AsyncTrampolineSpec, stream: Boolean, readable: Boolean, i: Int) {
        checkMayLeave(t.kind.replace('-', '.'))
        val (inst, end) = endOf(t, stream, readable, i)
        if (end.copyingOrCancelling()) trap("Cannot drop an end while a copy is in progress")
        if (!stream && !readable && end.state != End.EndState.DONE) trap("Cannot drop the writable end of a future before a value is written")
        inst.handles.remove(i)
        end.drop()
    }

    /** The reference's `lift_async_value`: takes the readable end [i] out of [inst]'s table. */
    private fun liftAsyncValue(inst: ComponentInstanceState, table: Int, i: Int, stream: Boolean): End {
        val end = inst.handles.get(i) as? End ?: trap("Handle is not a ${if (stream) "stream" else "future"} end")
        if (end.isStream != stream || !end.isReadable) trap("Only the readable end of a ${if (stream) "stream" else "future"} can be passed")
        if (end.table != table) trap("The ${if (stream) "stream" else "future"} has a different type")
        if (end.state != End.EndState.IDLE) trap("Cannot pass an end while a copy is in progress or after it is done")
        if (end.inWaitableSet()) trap("Cannot pass an end that is in a waitable set")
        inst.handles.remove(i)
        end.owner = null
        end.index = null
        return end
    }

    /** The reference's `lower_async_value`: adds [end] to the table of [table]'s instance. */
    private fun lowerAsyncValue(end: End, table: Int, stream: Boolean): Int {
        val inst = state(transmitTable(table, stream).instance)
        end.owner = inst
        end.table = table
        end.index = inst.handles.add(end)
        return end.index!!
    }

    fun liftEndToHost(table: Int, handle: Int, stream: Boolean): Int {
        val end = liftAsyncValue(state(transmitTable(table, stream).instance), table, handle, stream)
        val id = nextHostObject++
        hostObjects[id] = end
        return id
    }

    fun lowerEndFromHost(table: Int, id: Int, stream: Boolean, onRollback: ((() -> Unit) -> Unit)? = null): Int {
        val what = if (stream) "stream" else "future"
        val held = hostObjects[id]
        val instState = state(transmitTable(table, stream).instance)
        if (held is End && held.isReadable && held.isStream == stream) {
            // A readable end the component returned, passed back as is.
            if (held.table != table && transmitTable(held.table, stream).element != transmitTable(table, stream).element) {
                throw IllegalArgumentException("Handle $id is a $what of another type")
            }
            if (held.state != End.EndState.IDLE) throw IllegalStateException("The $what $id was already read to its end")
            val prevTable = held.table
            hostObjects.remove(id)
            val h = lowerAsyncValue(held, table, stream)
            onRollback?.invoke {
                instState.handles.remove(h)
                held.owner = null
                held.index = null
                held.table = prevTable
                hostObjects[id] = held
            }
            return h
        }
        val source = held as? HostSource ?: throw IllegalArgumentException("Unknown $what handle $id")
        require(source.stream == stream) { "Handle $id is not a $what" }
        val element = transmitTable(table, stream).element
        if (element != null) require(!containsHandles(element)) { "A $what of resources, streams, futures or error contexts cannot be passed from the host" }
        hostObjects.remove(id)
        val (r, w) = newEnds(stream, table, null)
        val writer = HostWriter(w, element)
        hostWriters += writer
        startHostWriter(writer, source)
        val h = lowerAsyncValue(r, table, stream)
        onRollback?.invoke {
            instState.handles.remove(h)
            r.owner = null
            r.index = null
            w.drop()
            r.drop()
            if (writer.producing) {
                writer.producing = false
                hostWork--
            }
            writer.rolledBack = true
            writer.finished = true
            writer.job?.cancel()
            hostWriters.remove(writer)
            hostObjects[id] = source
        }
        return h
    }

    /** Values the host passes in a stream or future it created ([newHostStream] / [newHostFuture]). */
    private class HostSource(val stream: Boolean, val chunks: Flow<WitStreamChunk>)

    /**
     * The host's writable end of a stream or future it passed: its coroutine collects the chunks
     * one at a time, each once the component has read the previous one (ADR-0019).
     */
    private class HostWriter(val end: End, val element: WitType?) {
        var buffer: HostReadableBuffer? = null
        var consumed: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var producing = true
        @Volatile
        var rolledBack = false
        var finished = false
        var job: Job? = null
    }

    /** Writable ends the host holds; dropped once the component has read every value. */
    private val hostWriters = ArrayList<HostWriter>()

    private fun startHostWriter(writer: HostWriter, source: HostSource) {
        val resources = HostResourceTable.currentOrNull()
        val context = host.asContextElement() + (resources?.asContextElement() ?: EmptyCoroutineContext)
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        fun closeSource() {
            if (!writer.rolledBack && closed.compareAndSet(false, true)) {
                (source.chunks as? AutoCloseable)?.close()
            }
        }
        hostWork++
        writer.job = scope.launch(context) {
            try {
                source.chunks.collect { chunk ->
                    writer.producing = false
                    hostWork--
                    if (chunk.count == 0) {
                        writer.producing = true
                        hostWork++
                        return@collect
                    }
                    if (writer.end.other == null) throw CancellationException("The component dropped the stream")
                    val consumed = kotlinx.coroutines.CompletableDeferred<Unit>()
                    writer.consumed = consumed
                    val buffer = HostReadableBuffer(chunk, writer.element)
                    writer.buffer = buffer
                    try {
                        host.guest {
                            if (writer.end.hasPendingEvent()) writer.end.getPendingEvent()
                            writer.end.copy(buffer)
                        }
                    } catch (e: Throwable) {
                        failAll(e)
                        throw CancellationException("trapped")
                    }
                    drain()
                    consumed.await()
                }
            } finally {
                if (writer.producing) hostWork--
                writer.producing = false
                writer.finished = true
                closeSource()
                post { drain() }
            }
        }.also { job ->
            job.invokeOnCompletion { closeSource() }
        }
    }

    /**
     * Lets the component see what the host's writable ends did: the next chunk is collected once a
     * chunk was read; an end whose source finished is dropped (once the reader took its last
     * event), so a reader waiting for more sees the stream end. Returns whether anything changed.
     */
    private fun pumpHostWriters(): Boolean {
        var changed = false
        val it = hostWriters.iterator()
        while (it.hasNext()) {
            val w = it.next()
            if (w.end.hasPendingEvent()) w.end.getPendingEvent()
            val reader = w.end.other
            if (reader == null) {
                // The component dropped its end: stop collecting.
                w.job?.cancel()
                it.remove()
                continue
            }
            val buffer = w.buffer
            if (buffer != null && buffer.remain() > 0 && w.end.buffer == null && w.end.state == End.EndState.IDLE) {
                // A copy took part of the chunk: offer the rest, as a guest writes again.
                host.guest { w.end.copy(buffer) }
                changed = true
                continue
            }
            if (buffer != null && buffer.remain() == 0 && w.end.buffer == null) {
                w.buffer = null
                if (!w.finished) {
                    w.producing = true
                    hostWork++
                }
                w.consumed?.complete(Unit)
                changed = true
            }
            if (w.finished && w.buffer == null && !reader.hasPendingEvent() && w.end.buffer == null) {
                w.end.drop()
                it.remove()
                changed = true
            }
        }
        return changed
    }

    fun newHostStream(values: ByteArray, count: Int): Int {
        require(count >= 0) { "Negative stream length $count" }
        return newHostStream(kotlinx.coroutines.flow.flowOf(WitStreamChunk(count, values.copyOf())))
    }

    fun newHostStream(chunks: Flow<WitStreamChunk>): Int {
        val id = nextHostObject++
        hostObjects[id] = HostSource(true, chunks)
        return id
    }

    fun newHostFuture(value: ByteArray): Int {
        val copy = value.copyOf()
        return newHostFuture { copy }
    }

    fun newHostFuture(value: suspend () -> ByteArray): Int {
        val id = nextHostObject++
        hostObjects[id] = HostSource(false, kotlinx.coroutines.flow.flow { emit(WitStreamChunk(1, value())) })
        return id
    }

    /** A host read in progress of a stream or future the component returned. */
    private class HostRead(
        val handle: Int,
        val end: End,
        val buffer: HostWritableBuffer,
        val stream: Boolean,
        val cont: CancellableContinuation<WitReader?>,
    )

    private val hostReads = ArrayList<HostRead>()

    /** Resumes the host reads whose copy finished. */
    private fun completeHostReads() {
        val done = hostReads.filter { it.end.hasPendingEvent() }
        for (read in done) {
            hostReads.remove(read)
            read.end.getPendingEvent()
            read.cont.resumeWith(Result.success(finishRead(read.handle, read.end, read.buffer, read.stream)))
        }
    }

    private fun finishRead(handle: Int, end: End, buffer: HostWritableBuffer, stream: Boolean): WitReader? {
        if (buffer.progress == 0 && end.state == End.EndState.DONE) {
            hostObjects.remove(handle)
            end.drop()
            return null
        }
        if (!stream) {
            hostObjects.remove(handle)
            end.drop()
            return WitReader(buffer.bytes())
        }
        return WitWriter().apply {
            length(buffer.progress)
            val b = buffer.bytes()
            raw(b, 0, b.size)
        }.toReader()
    }

    /**
     * The host reading the readable end [handle] of a stream or future the component returned:
     * suspends until the component writes values or drops its end (ADR-0019).
     */
    suspend fun hostReadSuspending(handle: Int, maxCount: Int, stream: Boolean): WitReader? = withContext(loop) {
        host.checkUsable()
        val what = if (stream) "stream" else "future"
        val end = hostObjects[handle] as? End ?: throw IllegalArgumentException("Unknown $what handle $handle")
        require(end.isStream == stream && end.isReadable) { "Handle $handle is not the readable end of a $what" }
        require(!stream || maxCount > 0) { "maxCount must be positive" }
        check(hostReads.none { it.handle == handle }) { "The $what $handle is already being read" }
        val element = transmitTable(end.table, stream).element
        if (element != null && containsHandles(element)) {
            throw UnsupportedOperationException("Reading a $what of resources, streams, futures or error contexts on the host is not supported")
        }
        if (end.state == End.EndState.DONE) {
            hostObjects.remove(handle)
            end.drop()
            return@withContext null
        }
        val buffer = HostWritableBuffer(if (stream) maxCount else 1)
        suspendCancellableCoroutine { cont ->
            hostReads += HostRead(handle, end, buffer, stream, cont)
            try {
                host.guest { end.copy(buffer) }
            } catch (e: Throwable) {
                hostReads.removeAll { it.cont === cont }
                throw e
            }
            cont.invokeOnCancellation {
                post {
                    if (hostReads.removeAll { it.cont === cont } && end.copyingOrCancelling()) {
                        // Take the buffer back, as `stream.cancel-read` does; values already copied are lost.
                        runCatching { host.guest { end.cancel(); if (end.hasPendingEvent()) end.getPendingEvent() } }
                    }
                }
            }
            drain()
        }
    }

    /** A blocking host read ([wasm2kt.runtime.ComponentBackend.readStream]). */
    fun hostRead(handle: Int, maxCount: Int, stream: Boolean): WitReader? {
        check(!isScheduling()) { "A stream or future was read synchronously from a host import; read it from a coroutine" }
        return runBlocking { hostReadSuspending(handle, maxCount, stream) }
    }

    fun hasActiveHostRead(handle: Int): Boolean = onSession { hostReads.any { it.handle == handle } }

    fun hostDropReadable(handle: Int) = onSession {
        val end = hostObjects[handle] as? End ?: throw IllegalArgumentException("Unknown stream or future handle $handle")
        require(end.isReadable) { "Handle $handle is not a readable end" }
        val cancelled = hostReads.filter { it.handle == handle && it.cont.isCancelled }
        if (cancelled.isNotEmpty()) {
            hostReads.removeAll(cancelled)
            if (end.copyingOrCancelling()) {
                host.guest { end.cancel(); if (end.hasPendingEvent()) end.getPendingEvent() }
            }
        }
        check(hostReads.none { it.handle == handle }) { "The stream or future $handle is being read" }
        hostObjects.remove(handle)
        host.guest { end.drop() }
        drain()
    }

    private fun containsHandles(t: WitType): Boolean = when (t) {
        is WitType.Own, is WitType.Borrow, is WitType.Stream, is WitType.Future, WitType.ErrorContext -> true
        is WitType.Record -> t.fields.any { containsHandles(it.second) }
        is WitType.Tuple -> t.types.any(::containsHandles)
        is WitType.ListOf -> containsHandles(t.element)
        is WitType.Variant -> t.cases.any { c -> c.second?.let(::containsHandles) ?: false }
        else -> false
    }

    /** The host's side of a copy into its own memory: collects the values' bytes. */
    private class HostWritableBuffer(private val length: Int) : WritableBuffer {
        private val out = java.io.ByteArrayOutputStream()
        override var progress = 0
            private set

        override fun remain() = length - progress

        override fun write(vs: CopiedValues) {
            check(vs.objects.isEmpty())
            out.write(vs.bytes)
            progress += vs.count
        }

        fun bytes(): ByteArray = out.toByteArray()
    }

    /** The host's values for a copy out of a stream or future it created. */
    private class HostReadableBuffer(private val source: WitStreamChunk, private val element: WitType?) : ReadableBuffer {
        private val reader = WitReader(source.values)
        override var progress = 0
            private set

        override fun remain() = source.count - progress

        override fun read(n: Int): CopiedValues {
            val start = source.values.size - reader.remaining
            if (element != null) repeat(n) { skip(element, reader) }
            val end = source.values.size - reader.remaining
            progress += n
            return CopiedValues(n, source.values.copyOfRange(start, end), emptyList())
        }

        /** Reads past one value of [t] in the binary format (ADR-0015). */
        private fun skip(t: WitType, r: WitReader) {
            when (t) {
                WitType.Bool, WitType.S8, WitType.U8 -> r.byte()
                WitType.S16, WitType.U16 -> r.short()
                WitType.S32, WitType.U32, WitType.F32, WitType.Char -> r.int()
                WitType.S64, WitType.U64, WitType.F64 -> r.long()
                WitType.Str -> r.string()
                is WitType.ListOf -> repeat(r.length()) { skip(t.element, r) }
                is WitType.Record -> t.fields.forEach { skip(it.second, r) }
                is WitType.Tuple -> t.types.forEach { skip(it, r) }
                is WitType.Flags -> when {
                    t.names.isEmpty() -> Unit
                    t.names.size <= 8 -> r.byte()
                    t.names.size <= 16 -> r.short()
                    else -> repeat((t.names.size + 31) / 32) { r.int() }
                }
                is WitType.Variant -> {
                    val case = when (t.kind) {
                        WitType.VariantKind.OPTION, WitType.VariantKind.RESULT -> r.byte().toInt() and 0xFF
                        else -> when (CanonicalAbi.discriminantSize(t.cases.size)) {
                            1 -> r.byte().toInt() and 0xFF
                            2 -> r.short().toInt() and 0xFFFF
                            else -> r.int()
                        }
                    }
                    val payload = t.cases.getOrNull(case) ?: throw IllegalArgumentException("Invalid case $case")
                    payload.second?.let { skip(it, r) }
                }
                else -> r.int()
            }
        }
    }

    fun liftErrorContextToHost(instance: Int, handle: Int): Int {
        val e = state(instance).handles.get(handle) as? ErrorContext ?: trap("Handle is not an error context")
        val id = nextHostObject++
        hostObjects[id] = e
        return id
    }

    fun lowerErrorContextFromHost(instance: Int, id: Int, onRollback: ((() -> Unit) -> Unit)? = null): Int {
        val e = hostObjects[id] as? ErrorContext ?: throw IllegalArgumentException("Unknown error-context handle $id")
        val instState = state(instance)
        val h = instState.handles.add(e)
        onRollback?.invoke { instState.handles.remove(h) }
        return h
    }

    /**
     * Guest memory a stream or future copy reads from or writes to (the reference's `GuestBuffer`).
     * Values cross between the two buffers of a copy in the binary format; handles they contain
     * (own resources, streams, futures, error contexts) move between the instances' tables.
     */
    private inner class GuestBuffer(
        private val element: WitType?,
        private val opts: AsyncCanonOptions,
        private val inst: ComponentInstanceState,
        private var ptr: Int,
        private val length: Int,
    ) : ReadableBuffer, WritableBuffer {
        override var progress = 0
            private set

        private val size = element?.size ?: 0

        init {
            if (unsigned(length) > HandleTable.MAX_LENGTH) trap("Stream buffer length exceeds the maximum")
            if (element != null) {
                val memory = opts.memory() ?: throw IllegalStateException("This copy needs a memory")
                checkRange(memory, ptr, unsigned(length) * size, element.align, "stream buffer")
            }
        }

        override fun remain(): Int = length - progress

        override fun read(n: Int): CopiedValues {
            val w = WitWriter()
            val objects = ArrayList<Any>()
            if (element != null) {
                val lifter = Lifter(TransferContext(opts, inst, objects, null), w)
                for (k in 0 until n) lifter.load(element, ptr + k * size)
                ptr += n * size
            }
            progress += n
            return CopiedValues(n, w.toByteArray(), objects)
        }

        override fun write(vs: CopiedValues) {
            if (element != null) {
                val lowerer = Lowerer(TransferContext(opts, inst, null, vs.objects), WitReader(vs.bytes))
                for (k in 0 until vs.count) lowerer.store(element, ptr + k * size)
                ptr += vs.count * size
            }
            progress += vs.count
        }
    }

    /**
     * Lifting from (or lowering into) a guest's memory for a stream or future copy: resources move
     * by representation, streams, futures and error contexts through [lifted] / [lowered].
     */
    private inner class TransferContext(
        private val opts: AsyncCanonOptions,
        private val inst: ComponentInstanceState,
        private val lifted: MutableList<Any>?,
        private val lowered: List<Any>?,
    ) : AbiContext {
        override val memory: CanonicalMemory get() = opts.memory() ?: throw IllegalStateException("This copy needs a memory")
        override val encoding: StringEncoding get() = opts.stringEncoding

        override fun realloc(align: Int, size: Int): Int {
            val f = opts.realloc ?: throw IllegalStateException("This copy needs cabi_realloc")
            return host.withoutLeaving { f(align, size) }
        }

        override fun lowerOwn(table: Int, id: Int): Int = host.resourceTable(table).insert(id, own = true)
        override fun lowerBorrow(table: Int, id: Int): Int = trap("A stream or future cannot carry borrow handles")
        override fun liftOwn(table: Int, handle: Int): Int = this@AsyncAbi.liftOwn(table, handle)
        override fun liftBorrow(table: Int, handle: Int): Int = trap("A stream or future cannot carry borrow handles")

        override fun liftStream(table: Int, handle: Int): Int = keep(liftAsyncValue(inst, table, handle, stream = true))
        override fun lowerStream(table: Int, id: Int): Int = lowerAsyncValue(lowered!![id] as End, table, stream = true)
        override fun liftFuture(table: Int, handle: Int): Int = keep(liftAsyncValue(inst, table, handle, stream = false))
        override fun lowerFuture(table: Int, id: Int): Int = lowerAsyncValue(lowered!![id] as End, table, stream = false)
        override fun liftErrorContext(handle: Int): Int = keep(inst.handles.get(handle) as? ErrorContext ?: trap("Handle is not an error context"))
        override fun lowerErrorContext(id: Int): Int = inst.handles.add(lowered!![id])

        private fun keep(o: Any): Int {
            lifted!!.add(o)
            return lifted.size - 1
        }
    }

    companion object {
        /** Seconds the session's thread waits for work before it exits. */
        const val IDLE_SECONDS = 2L
        const val CALLBACK_EXIT = 0
        const val CALLBACK_YIELD = 1
        const val CALLBACK_WAIT = 2
        const val PREPARE_ASYNC_NO_RESULT = -1
        const val PREPARE_ASYNC_WITH_RESULT = -2
        const val START_FLAG_ASYNC_CALLEE = 1
    }
}
