package wasm2kt.runtime.chicory

import com.dylibso.chicory.runtime.ExportFunction
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.Memory
import com.dylibso.chicory.runtime.WasmFunctionHandle
import wasm2kt.runtime.ComponentTrapException
import wasm2kt.runtime.WitReader
import wasm2kt.runtime.WitWriter

/** Wraps an error in the host's own values found while running on a guest thread (ADR-0007). */
internal class HostValueException(cause: Throwable) : RuntimeException(cause)

/**
 * The Component Model async ABI for one [ChicoryComponentInstance] (#16, ADR-0018): host calls into a
 * component that uses it run as tasks on [store], and the async built-ins and the fused adapters'
 * async intrinsics are implemented here, following the reference definitions (see [Concurrency.kt]).
 */
internal class AsyncAbi(private val c: ChicoryComponentInstance) {
    private val plan get() = c.plan

    val store = AsyncStore()

    private val states = HashMap<Int, ComponentInstanceState>()

    /** Ends of streams and futures, and error contexts, the host holds: host handle -> object. */
    private val hostObjects = HashMap<Int, Any>()
    private var nextHostObject = 1

    fun state(instance: Int): ComponentInstanceState = states.getOrPut(instance) { ComponentInstanceState(instance, store) }

    fun close() {
        store.abandon()
    }

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
        checkReturn: (Trampoline.Async, CanonOptions) -> Boolean,
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
            while (task.state != Task.State.RESOLVED) {
                // The instance's ready threads, in the order they started waiting.
                // A synchronous call it made into another instance continues this call, too.
                val candidate = store.waiting.firstOrNull { (it.task.inst === inst || it.root.task === task) && it.ready() }
                if (candidate != null) candidate.resume()
                else if (!pumpHostWriters()) trap("deadlock detected: a synchronous call cannot complete because every thread of the instance is waiting")
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
        val flatArgs = task.start()
        if (!task.optsAsync) {
            val flatResults = callee(flatArgs)
            task.return_(flatResults)
            store.onSwitch(thread)
            postReturn?.let { pr -> c.withoutLeaving { pr(flatResults) } }
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

    // ------------------------------------------------------------------- host calls

    /** A host call of an export of a component that uses the async ABI: runs it as a task to completion. */
    fun invoke(export: PlanExport, functionName: String, args: WitReader): WitReader {
        val type = export.type
        val opts = plan.options.getValue(export.options)
        val inst = state(opts.instance)
        lateinit var task: Task
        val cx = c.context(opts, ChicoryComponentInstance.Direction.HOST_TO_GUEST) { task }
        val paramTypes = type.params.map { it.second }
        val results = WitWriter()
        var resolved = false
        var cancelled = false
        val onStart = {
            // Lowering the host's arguments: errors in the values are the host's, not traps (ADR-0007).
            val coreArgs = ArrayList<Long>()
            try {
                val lowerer = Lowerer(cx, args)
                if (CanonicalAbi.flattenAll(paramTypes).size > CanonicalAbi.MAX_FLAT_PARAMS) {
                    coreArgs += lowerer.storeTuple(paramTypes).toLong()
                } else {
                    paramTypes.forEach { lowerer.lowerFlat(it, coreArgs) }
                }
                if (args.remaining != 0) throw IllegalArgumentException("${args.remaining} unread bytes after the arguments of '$functionName'")
            } catch (e: ComponentTrapException) {
                throw e
            } catch (e: IllegalArgumentException) {
                cx.rollbackLowering()
                task.state = Task.State.RESOLVED
                throw HostValueException(e)
            } catch (e: IllegalStateException) {
                cx.rollbackLowering()
                task.state = Task.State.RESOLVED
                throw HostValueException(e)
            }
            cx.commitMoves()
            coreArgs.toLongArray()
        }
        val onResolve = { flat: LongArray? ->
            if (flat == null) {
                cancelled = true
            } else {
                val lifter = Lifter(cx, results)
                val limit = if (opts.async) CanonicalAbi.MAX_FLAT_PARAMS else CanonicalAbi.MAX_FLAT_RESULTS
                if (CanonicalAbi.flattenAll(type.results).size > limit) {
                    lifter.loadTuple(flat[0].toInt(), type.results)
                } else {
                    val reader = Lifter.FlatReader(flat)
                    type.results.forEach { lifter.liftFlat(it, reader) }
                }
            }
            resolved = true
        }
        try {
            c.guest {
                canonLift(
                    inst, type.async, opts.async,
                    callee = c.coreFunction(export.func).let { f -> { a: LongArray -> f.apply(*a) ?: LongArray(0) } },
                    callback = opts.callback?.let { cb -> c.callbacks.getValue(cb).let { f -> { a: LongArray -> f.apply(*a) ?: LongArray(0) } } },
                    postReturn = opts.postReturn?.let { pr -> { r: LongArray -> c.postReturns.getValue(pr).apply(*r); Unit } },
                    checkReturn = { tr, trOpts -> tr.results == type.results && sameLiftOptions(trOpts, opts) },
                    onStart = onStart,
                    onResolve = onResolve,
                    createdTask = { task = it },
                )
                runUntil("'$functionName' has not returned") { resolved }
            }
        } finally {
            cx.releaseLends()
        }
        if (cancelled) throw IllegalStateException("'$functionName' was cancelled")
        return results.toReader()
    }

    /** As in Wasmtime: a `task.return` that needs no memory may leave it out. */
    private fun sameLiftOptions(a: CanonOptions, b: CanonOptions) = a.stringEncoding == b.stringEncoding && (a.memory == null || a.memory == b.memory)

    /**
     * A guest calling a host import with `async` lowering. The host function runs to completion, so
     * the subtask has returned by the time the call does (the reference's eager return).
     */
    fun callHostAsync(t: Trampoline.LowerImport, coreArgs: LongArray): LongArray {
        val opts = plan.options.getValue(t.options)
        val cx = c.context(opts, ChicoryComponentInstance.Direction.GUEST_TO_HOST)
        val args = WitWriter()
        val lifter = Lifter(cx, args)
        val paramTypes = t.type.params.map { it.second }
        if (CanonicalAbi.flattenAll(paramTypes).size > CanonicalAbi.MAX_FLAT_ASYNC_PARAMS) {
            lifter.loadTuple(coreArgs[0].toInt(), paramTypes)
        } else {
            val reader = Lifter.FlatReader(coreArgs)
            paramTypes.forEach { lifter.liftFlat(it, reader) }
        }
        val results = WitWriter()
        c.hostImport(t.import).call(args.toReader(), results)
        val reader = results.toReader()
        if (t.type.results.isNotEmpty()) {
            Lowerer(cx, reader).storeTupleAt(t.type.results, coreArgs[coreArgs.size - 1].toInt())
        }
        if (reader.remaining != 0) throw IllegalStateException("${reader.remaining} unread bytes after the results of a host import")
        return longArrayOf(Subtask.State.RETURNED.toLong())
    }

    // ------------------------------------------------------------------- intrinsics

    /** `context-get-i32-0` and friends: the current thread's context slots. */
    fun intrinsic(name: String): WasmFunctionHandle {
        val slot = name.last() - '0'
        return when {
            name.startsWith("context-get") -> WasmFunctionHandle { _, _ ->
                longArrayOf(unsigned(currentThreadOrNull()?.storage?.get(slot) ?: 0))
            }
            name.startsWith("context-set") -> WasmFunctionHandle { _, args ->
                currentThreadOrNull()?.storage?.set(slot, args[0].toInt())
                null
            }
            else -> WasmFunctionHandle { _, _ -> throw UnsupportedOperationException("Intrinsic '$name' is not supported by the Chicory backend") }
        }
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

    private fun checkMayLeave(what: String) = c.checkMayLeave(what)

    private fun memoryOf(options: Int?): Memory {
        val opts = options?.let { plan.options.getValue(it) } ?: throw IllegalStateException("This built-in needs canonical options")
        return opts.memory?.let { c.memories.getValue(it) } ?: throw IllegalStateException("This built-in needs a memory")
    }

    fun trampoline(t: Trampoline.Async): WasmFunctionHandle = when (t.kind) {
        "backpressure-inc" -> WasmFunctionHandle { _, _ ->
            val inst = state(t.instance!!)
            if (inst.backpressure == 0xFFFF) trap("backpressure counter overflow")
            inst.backpressure++
            null
        }
        "backpressure-dec" -> WasmFunctionHandle { _, _ ->
            val inst = state(t.instance!!)
            if (inst.backpressure == 0) trap("backpressure counter underflow")
            inst.backpressure--
            null
        }
        "task-return" -> WasmFunctionHandle { _, args ->
            val task = currentTask()
            checkMayLeave("task.return")
            if (!task.optsAsync) trap("task.return can only be called by an async-lifted export")
            if (!task.checkReturn(t, plan.options.getValue(t.options!!))) trap("invalid task.return signature and/or options for the current task")
            task.return_(args)
            store.onSwitch(currentThreadOrNull())
            null
        }
        "task-cancel" -> WasmFunctionHandle { _, _ ->
            val task = currentTask()
            checkMayLeave("task.cancel")
            if (!task.optsAsync) trap("task.cancel can only be called by an async-lifted export")
            task.cancel()
            null
        }
        "waitable-set-new" -> WasmFunctionHandle { _, _ ->
            checkMayLeave("waitable-set.new")
            longArrayOf(state(t.instance!!).handles.add(WaitableSet()).toLong())
        }
        "waitable-set-wait" -> WasmFunctionHandle { _, args ->
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
        "waitable-set-poll" -> WasmFunctionHandle { _, args ->
            checkMayLeave("waitable-set.poll")
            val inst = state(t.instance!!)
            val wset = inst.handles.get(args[0].toInt()) as? WaitableSet ?: trap("Handle is not a waitable set")
            val task = currentTask()
            val event = if (t.cancellable && task.deliverPendingCancel()) Event(EventCode.TASK_CANCELLED, 0, 0) else wset.poll()
            unpackEvent(memoryOf(t.options), args[1].toInt(), event)
        }
        "waitable-set-drop" -> WasmFunctionHandle { _, args ->
            checkMayLeave("waitable-set.drop")
            val inst = state(t.instance!!)
            val wset = inst.handles.getOrNull(args[0].toInt()) as? WaitableSet ?: trap("Handle is not a waitable set")
            wset.drop()
            inst.handles.remove(args[0].toInt())
            null
        }
        "waitable-join" -> WasmFunctionHandle { _, args ->
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
        "subtask-drop" -> WasmFunctionHandle { _, args ->
            checkMayLeave("subtask.drop")
            val inst = state(t.instance!!)
            val s = inst.handles.get(args[0].toInt()) as? Subtask ?: trap("Handle is not a subtask")
            s.drop()
            inst.handles.remove(args[0].toInt())
            null
        }
        "subtask-cancel" -> WasmFunctionHandle { _, args -> longArrayOf(subtaskCancel(t, args[0].toInt()).toLong()) }
        "stream-new", "future-new" -> WasmFunctionHandle { _, _ ->
            checkMayLeave(t.kind.replace('-', '.'))
            val stream = t.kind == "stream-new"
            val table = t.table!!
            val inst = state(transmitTable(table, stream).instance)
            val (r, w) = newEnds(stream, table, inst)
            r.index = inst.handles.add(r)
            w.index = inst.handles.add(w)
            longArrayOf(unsigned(r.index!!) or (unsigned(w.index!!) shl 32))
        }
        "stream-read", "stream-write", "future-read", "future-write" -> WasmFunctionHandle { _, args ->
            val stream = t.kind.startsWith("stream")
            val read = t.kind.endsWith("read")
            longArrayOf(unsigned(copy(t, stream, read, args[0].toInt(), args[1].toInt(), if (stream) args[2].toInt() else 1)))
        }
        "stream-cancel-read", "stream-cancel-write", "future-cancel-read", "future-cancel-write" -> WasmFunctionHandle { _, args ->
            longArrayOf(unsigned(cancelCopy(t, t.kind.startsWith("stream"), t.kind.endsWith("read"), args[0].toInt())))
        }
        "stream-drop-readable", "stream-drop-writable", "future-drop-readable", "future-drop-writable" -> WasmFunctionHandle { _, args ->
            dropEnd(t, t.kind.startsWith("stream"), t.kind.endsWith("readable"), args[0].toInt())
            null
        }
        "error-context-new" -> WasmFunctionHandle { _, args ->
            checkMayLeave("error-context.new")
            val opts = plan.options.getValue(t.options!!)
            val w = WitWriter()
            Lifter(c.context(opts, ChicoryComponentInstance.Direction.GUEST_TO_HOST), w)
                .liftFlat(WitType.Str, Lifter.FlatReader(longArrayOf(args[0], args[1])))
            val message = w.toReader().string()
            longArrayOf(state(t.instance!!).handles.add(ErrorContext(message)).toLong())
        }
        "error-context-debug-message" -> WasmFunctionHandle { _, args ->
            checkMayLeave("error-context.debug-message")
            val e = state(t.instance!!).handles.get(args[0].toInt()) as? ErrorContext ?: trap("Handle is not an error context")
            val opts = plan.options.getValue(t.options!!)
            val w = WitWriter().apply { string(e.debugMessage) }
            Lowerer(c.context(opts, ChicoryComponentInstance.Direction.GUEST_TO_HOST), w.toReader())
                .storeTupleAt(listOf(WitType.Str), args[1].toInt())
            null
        }
        "error-context-drop" -> WasmFunctionHandle { _, args ->
            checkMayLeave("error-context.drop")
            val inst = state(t.instance!!)
            if (inst.handles.getOrNull(args[0].toInt()) !is ErrorContext) trap("Handle is not an error context")
            inst.handles.remove(args[0].toInt())
            null
        }
        "thread-index" -> WasmFunctionHandle { _, _ ->
            checkMayLeave("thread.index")
            longArrayOf((currentThread().index ?: 0).toLong())
        }
        "thread-new-indirect" -> WasmFunctionHandle { _, args -> longArrayOf(threadNewIndirect(t, args[0].toInt(), args[1])) }
        "thread-resume-later" -> WasmFunctionHandle { _, args ->
            checkMayLeave("thread.resume-later")
            val other = otherThread(args[0].toInt())
            if (!other.suspended()) trap("thread.resume-later: the thread is not suspended")
            other.resumeLater()
            null
        }
        "thread-yield" -> WasmFunctionHandle { _, _ ->
            checkMayLeave("thread.yield")
            val thread = currentThread()
            suspension(t) { thread.yield() }
            longArrayOf(cancelled(t))
        }
        "thread-suspend" -> WasmFunctionHandle { _, _ ->
            checkMayLeave("thread.suspend")
            val thread = currentThread()
            checkBlocking()
            suspension(t) { thread.suspend() }
            longArrayOf(cancelled(t))
        }
        "thread-suspend-then-resume", "thread-yield-then-resume" -> WasmFunctionHandle { _, args ->
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
        "thread-suspend-then-promote", "thread-yield-then-promote" -> WasmFunctionHandle { _, args ->
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
        "prepare-call" -> WasmFunctionHandle { instance, args ->
            prepareCall(t, instance, args)
            null
        }
        "sync-start-call" -> WasmFunctionHandle { instance, args -> startCall(t, instance, args[0], args[1].toInt(), 0, 0, sync = true) }
        "async-start-call" -> WasmFunctionHandle { instance, args ->
            startCall(t, instance, args[0], args[1].toInt(), args[2].toInt(), args[3].toInt(), sync = false)
        }
        "enter-sync-call" -> WasmFunctionHandle { _, args ->
            enterSyncCall(args[1].toInt() != 0, args[2].toInt())
            null
        }
        "exit-sync-call" -> WasmFunctionHandle { _, _ ->
            exitSyncCall()
            null
        }
        "stream-transfer", "future-transfer" -> WasmFunctionHandle { _, args ->
            val stream = t.kind == "stream-transfer"
            val src = args[1].toInt()
            val dst = args[2].toInt()
            val end = liftAsyncValue(state(transmitTable(src, stream).instance), src, args[0].toInt(), stream)
            longArrayOf(lowerAsyncValue(end, dst, stream).toLong())
        }
        "error-context-transfer" -> WasmFunctionHandle { _, args ->
            val e = state(errorContextInstance(args[1].toInt())).handles.get(args[0].toInt()) as? ErrorContext
                ?: trap("Handle is not an error context")
            longArrayOf(state(errorContextInstance(args[2].toInt())).handles.add(e).toLong())
        }
        "resource-transfer-own" -> WasmFunctionHandle { _, args ->
            val rep = liftOwn(args[1].toInt(), args[0].toInt())
            longArrayOf(c.table(args[2].toInt()).insert(rep, own = true).toLong())
        }
        "resource-transfer-borrow" -> WasmFunctionHandle { _, args -> longArrayOf(transferBorrow(args[0].toInt(), args[1].toInt(), args[2].toInt()).toLong()) }
        else -> WasmFunctionHandle { _, _ ->
            throw UnsupportedOperationException("Component intrinsic not supported by the Chicory backend: ${t.kind}")
        }
    }

    private fun unpackEvent(memory: Memory, ptr: Int, e: Event): LongArray {
        checkRange(memory, ptr, 8, 4, "event pointer")
        memory.writeI32(ptr, e.p1)
        memory.writeI32(ptr + 4, e.p2)
        return longArrayOf(e.code.toLong())
    }

    private fun otherThread(index: Int): GuestThread {
        val inst = currentTask().inst
        return inst.threads.getOrNull(index) as? GuestThread ?: trap("Unknown thread index ${unsigned(index)}")
    }

    /** Runs a suspension; a `cancellable` one lets a cancellation request resume the thread. */
    private inline fun suspension(t: Trampoline.Async, block: () -> Unit) {
        val thread = currentThread()
        thread.cancellable = t.cancellable
        try {
            block()
        } finally {
            thread.cancellable = false
        }
    }

    /** The result of a cancellable suspension: 1 when a pending cancellation was delivered. */
    private fun cancelled(t: Trampoline.Async): Long =
        if (t.cancellable && currentTask().deliverPendingCancel()) 1 else 0

    private fun threadNewIndirect(t: Trampoline.Async, fi: Int, closure: Long): Long {
        val task = currentTask()
        checkMayLeave("thread.new-indirect")
        val table = c.tables[t.table!!] ?: throw IllegalStateException("thread.new-indirect: unknown table ${t.table}")
        if (fi < 0 || fi >= table.size()) trap("thread.new-indirect: table index $fi out of bounds")
        val funcIdx = table.ref(fi)
        if (funcIdx < 0) trap("thread.new-indirect: uninitialized table element $fi")
        val owner = table.instance(fi)
        val type = owner.type(owner.functionType(funcIdx))
        if (type.params().size != 1 || type.returns().isNotEmpty()) trap("thread.new-indirect: the start function has the wrong type")
        lateinit var newThread: GuestThread
        newThread = GuestThread(task) {
            owner.getMachine().call(funcIdx, longArrayOf(closure))
            task.unregisterThread(newThread)
        }
        task.registerThread(newThread)
        return newThread.index!!.toLong()
    }

    // ------------------------------------------------------------------- subtasks

    private fun subtaskCancel(t: Trampoline.Async, i: Int): Int {
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

    private fun prepareCall(t: Trampoline.Async, instance: Instance, args: LongArray) {
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
    private fun startCall(t: Trampoline.Async, instance: Instance, callee: Long, paramCount: Int, resultCount: Int, flags: Int, sync: Boolean): LongArray {
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
        val callback = t.callback?.let { cb -> c.callbacks.getValue(cb).let { f -> { a: LongArray -> f.apply(*a) ?: LongArray(0) } } }
        val postReturn = t.postReturn?.let { pr -> { r: LongArray -> c.postReturns.getValue(pr).apply(*r); Unit } }
        val task = canonLift(
            calleeInst, p.calleeAsync, if (sync) true else calleeAsyncLift,
            callee = { a -> callRef(instance, callee, a) },
            callback = callback,
            postReturn = postReturn,
            checkReturn = { tr, opts ->
                tr.resultsIndex == p.taskReturnType && (opts.memory == null || opts.memory == p.memory) && encodingCode(opts.stringEncoding) == p.stringEncoding
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

    private fun callRef(instance: Instance, ref: Long, args: LongArray): LongArray =
        instance.getMachine().call(ref.toInt(), args) ?: LongArray(0)

    private fun encodingCode(e: StringEncoding): Int = when (e) {
        StringEncoding.UTF8 -> 0
        StringEncoding.UTF16 -> 1
        StringEncoding.LATIN1_UTF16 -> 2
    }

    /** Fused adapters call this around a synchronous guest-to-guest call: the callee's task. */
    private fun enterSyncCall(calleeAsync: Boolean, calleeInstance: Int) {
        val task = Task(state(calleeInstance), calleeAsync, false, false, { _, _ -> false }, { LongArray(0) }, {})
        val thread = GuestThread(task, null)
        val handler = StackSwitching.currentHandler() ?: StackSwitching.installHostHandler(thread)
        syncCallers.get() += handler.currentThread
        if (handler.currentThread !== thread) thread.syncCaller = handler.currentThread
        handler.currentThread = thread
        store.onSwitch(thread)
        if (!task.enterImplicitThread(thread)) trap("The call was cancelled before it started")
        task.state = Task.State.STARTED
    }

    /** The callers of the synchronous guest-to-guest calls in progress on this JVM thread. */
    private val syncCallers = ThreadLocal.withInitial { ArrayList<GuestThread>() }

    private fun exitSyncCall() {
        val handler = StackSwitching.currentHandler() ?: throw IllegalStateException("exit-sync-call without enter-sync-call")
        val thread = handler.currentThread
        val task = thread.task
        if (task.numBorrows > 0) trap("borrow handles still remain at the end of the call")
        task.state = Task.State.RESOLVED
        task.exitImplicitThread()
        thread.running = false
        val callers = syncCallers.get()
        val caller = callers.removeAt(callers.size - 1)
        if (caller === thread) {
            // The host's own stack: nothing ran below the call.
            StackSwitching.removeHostHandler()
            store.onSwitch(null)
        } else {
            handler.currentThread = caller
            store.onSwitch(caller)
        }
    }

    // ------------------------------------------------------------------- resources between instances

    private fun liftOwn(table: Int, handle: Int): Int {
        val t = c.table(table)
        val h = t.slotOrNull(handle) ?: trap("Unknown resource handle $handle")
        if (!h.own) trap("Cannot pass borrowed resource handle $handle as own")
        if (h.numLends != 0) trap("Cannot pass resource handle $handle as own while it is lent")
        t.remove(handle)
        return h.rep
    }

    private fun transferBorrow(handle: Int, srcTable: Int, dstTable: Int): Int {
        val h = c.table(srcTable).slotOrNull(handle) ?: trap("Unknown resource handle $handle")
        val dst = plan.resourceTables[dstTable]
        // Borrowing into the instance that defines the resource passes its representation.
        val definedIn = plan.initializers.filterIsInstance<Initializer.Resource>().firstOrNull { it.resource == dst?.resource }?.instance
        if (dst != null && !dst.imported && definedIn == dst.instance) return h.rep
        val scope = currentThreadOrNull()?.task
        scope?.let { it.numBorrows++ }
        return c.table(dstTable).insert(h.rep, own = false, scope = scope)
    }

    // ------------------------------------------------------------------- streams and futures

    private fun transmitTable(table: Int, stream: Boolean): TransmitTableInfo =
        (if (stream) plan.streamTables[table] else plan.futureTables[table]) ?: throw IllegalStateException("Unknown ${if (stream) "stream" else "future"} table $table")

    private fun errorContextInstance(table: Int): Int = plan.errorContextTables[table] ?: 0

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

    private fun endOf(t: Trampoline.Async, stream: Boolean, readable: Boolean, i: Int): Pair<ComponentInstanceState, End> {
        val inst = state(transmitTable(t.table!!, stream).instance)
        val end = inst.handles.get(i) as? End ?: trap("Handle is not a ${if (stream) "stream" else "future"} end")
        if (end.isStream != stream || end.isReadable != readable) trap("Handle is not the ${if (readable) "readable" else "writable"} end of a ${if (stream) "stream" else "future"}")
        if (end.table != t.table) trap("The ${if (stream) "stream" else "future"} has a different type")
        return inst to end
    }

    private fun copy(t: Trampoline.Async, stream: Boolean, read: Boolean, i: Int, ptr: Int, length: Int): Int {
        checkMayLeave(t.kind.replace('-', '.'))
        val (inst, end) = endOf(t, stream, read, i)
        if (end.state != End.EndState.IDLE) {
            trap(if (end.state == End.EndState.DONE) "the other end was dropped or the future was already written" else "a copy is already in progress")
        }
        val opts = plan.options.getValue(t.options!!)
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

    private fun cancelCopy(t: Trampoline.Async, stream: Boolean, readable: Boolean, i: Int): Int {
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

    private fun dropEnd(t: Trampoline.Async, stream: Boolean, readable: Boolean, i: Int) {
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

    fun lowerEndFromHost(table: Int, id: Int, stream: Boolean): Int {
        val what = if (stream) "stream" else "future"
        val held = hostObjects[id]
        if (held is End && held.isReadable && held.isStream == stream) {
            // A readable end the component returned, passed back as is.
            if (held.table != table && transmitTable(held.table, stream).element != transmitTable(table, stream).element) {
                throw IllegalArgumentException("Handle $id is a $what of another type")
            }
            if (held.state != End.EndState.IDLE) throw IllegalStateException("The $what $id was already read to its end")
            hostObjects.remove(id)
            return lowerAsyncValue(held, table, stream)
        }
        val source = held as? HostSource ?: throw IllegalArgumentException("Unknown $what handle $id")
        require(source.stream == stream) { "Handle $id is not a $what" }
        val element = transmitTable(table, stream).element
        if (element != null) require(!containsHandles(element)) { "A $what of resources, streams, futures or error contexts cannot be passed from the host" }
        hostObjects.remove(id)
        // The host's writable end holds all the values; the component reads them, then the stream ends.
        val (r, w) = newEnds(stream, table, null)
        val writer = HostWriter(w, HostReadableBuffer(source, element))
        w.copy(writer.buffer)
        hostWriters += writer
        return lowerAsyncValue(r, table, stream)
    }

    /** Values the host passes in a stream or future it created ([newStream] / [newFuture]). */
    private class HostSource(val stream: Boolean, val values: ByteArray, val count: Int)

    private class HostWriter(val end: End, val buffer: HostReadableBuffer)

    /** Writable ends the host holds; dropped once the component has read every value. */
    private val hostWriters = ArrayList<HostWriter>()

    /**
     * Lets the component see what the host's writable ends did: an end whose values were all read
     * is dropped (once the reader took its last event), so a reader waiting for more sees the stream
     * end. Returns whether anything changed.
     */
    private fun pumpHostWriters(): Boolean {
        var changed = false
        val it = hostWriters.iterator()
        while (it.hasNext()) {
            val w = it.next()
            if (w.end.hasPendingEvent()) w.end.getPendingEvent()
            val reader = w.end.other
            if (reader == null) {
                it.remove()
                continue
            }
            if (w.buffer.remain() == 0 && !reader.hasPendingEvent() && w.end.buffer == null) {
                if (w.end.isStream) {
                    w.end.drop()
                    changed = true
                }
                it.remove()
            }
        }
        return changed
    }

    /** Runs the scheduler until [done]; traps when nothing can run any more. */
    private fun runUntil(what: String, done: () -> Boolean) {
        while (!done()) {
            if (!store.tick() && !pumpHostWriters()) trap("deadlock detected: $what, but every task is waiting")
        }
    }

    fun newHostStream(values: ByteArray, count: Int): Int {
        require(count >= 0) { "Negative stream length $count" }
        val id = nextHostObject++
        hostObjects[id] = HostSource(true, values.copyOf(), count)
        return id
    }

    fun newHostFuture(value: ByteArray): Int {
        val id = nextHostObject++
        hostObjects[id] = HostSource(false, value.copyOf(), 1)
        return id
    }

    /** The host reading the readable end [handle] of a stream or future the component returned. */
    fun hostRead(handle: Int, maxCount: Int, stream: Boolean): WitReader? {
        val what = if (stream) "stream" else "future"
        val end = hostObjects[handle] as? End ?: throw IllegalArgumentException("Unknown $what handle $handle")
        require(end.isStream == stream && end.isReadable) { "Handle $handle is not the readable end of a $what" }
        require(!stream || maxCount > 0) { "maxCount must be positive" }
        val element = transmitTable(end.table, stream).element
        if (element != null && containsHandles(element)) {
            throw UnsupportedOperationException("Reading a $what of resources, streams, futures or error contexts on the host is not supported")
        }
        if (end.state == End.EndState.DONE) {
            hostObjects.remove(handle)
            end.drop()
            return null
        }
        val buffer = HostWritableBuffer(if (stream) maxCount else 1)
        c.guest {
            end.copy(buffer)
            runUntil("the host is reading a $what") { end.hasPendingEvent() }
            end.getPendingEvent()
        }
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

    fun hostDropReadable(handle: Int) {
        val end = hostObjects[handle] as? End ?: throw IllegalArgumentException("Unknown stream or future handle $handle")
        require(end.isReadable) { "Handle $handle is not a readable end" }
        hostObjects.remove(handle)
        c.guest { end.drop() }
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
    private class HostReadableBuffer(private val source: HostSource, private val element: WitType?) : ReadableBuffer {
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

    fun lowerErrorContextFromHost(instance: Int, id: Int): Int {
        val e = hostObjects[id] as? ErrorContext ?: throw IllegalArgumentException("Unknown error-context handle $id")
        return state(instance).handles.add(e)
    }

    /**
     * Guest memory a stream or future copy reads from or writes to (the reference's `GuestBuffer`).
     * Values cross between the two buffers of a copy in the binary format; handles they contain
     * (own resources, streams, futures, error contexts) move between the instances' tables.
     */
    private inner class GuestBuffer(
        private val element: WitType?,
        private val opts: CanonOptions,
        private val inst: ComponentInstanceState,
        private var ptr: Int,
        private val length: Int,
    ) : ReadableBuffer, WritableBuffer {
        override var progress = 0
            private set

        private val size = element?.let { CanonicalAbi.sizeOf(it) } ?: 0

        init {
            if (unsigned(length) > HandleTable.MAX_LENGTH) trap("Stream buffer length exceeds the maximum")
            if (element != null) {
                val memory = opts.memory?.let { c.memories.getValue(it) } ?: throw IllegalStateException("This copy needs a memory")
                checkRange(memory, ptr, unsigned(length) * size, CanonicalAbi.alignOf(element), "stream buffer")
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
        private val opts: CanonOptions,
        private val inst: ComponentInstanceState,
        private val lifted: MutableList<Any>?,
        private val lowered: List<Any>?,
    ) : AbiContext {
        override val memory: Memory get() = opts.memory?.let { c.memories.getValue(it) } ?: throw IllegalStateException("This copy needs a memory")
        override val encoding: StringEncoding get() = opts.stringEncoding

        override fun realloc(align: Int, size: Int): Int {
            val f: ExportFunction = opts.realloc?.let { c.reallocs.getValue(it) } ?: throw IllegalStateException("This copy needs cabi_realloc")
            return c.withoutLeaving { f.apply(0, 0, align.toLong(), size.toLong())[0].toInt() }
        }

        override fun lowerOwn(table: Int, id: Int): Int = c.table(table).insert(id, own = true)
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
        const val CALLBACK_EXIT = 0
        const val CALLBACK_YIELD = 1
        const val CALLBACK_WAIT = 2
        const val PREPARE_ASYNC_NO_RESULT = -1
        const val PREPARE_ASYNC_WITH_RESULT = -2
        const val START_FLAG_ASYNC_CALLEE = 1
    }
}
