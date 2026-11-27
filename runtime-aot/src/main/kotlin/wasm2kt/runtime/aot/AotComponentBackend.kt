@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.runtime.aot

import kotlinx.coroutines.flow.Flow
import wasm2kt.runtime.AsyncHostFunction as WitAsyncHostFunction
import wasm2kt.runtime.ComponentBackend
import wasm2kt.runtime.HostFunction as WitHostFunction
import wasm2kt.runtime.WitHostImportDispatcher
import wasm2kt.runtime.WitReader
import wasm2kt.runtime.WitStreamChunk
import wasm2kt.runtime.WitWriter
import wasm2kt.runtime.canonical.AbiBoundaryDirection
import wasm2kt.runtime.canonical.AsyncAbi
import wasm2kt.runtime.canonical.AsyncAbiContext
import wasm2kt.runtime.canonical.AsyncBackendHost
import wasm2kt.runtime.canonical.AsyncCanonOptions
import wasm2kt.runtime.canonical.AsyncCoreInstance
import wasm2kt.runtime.canonical.AsyncExportSpec
import wasm2kt.runtime.canonical.AsyncResourceTableInfo
import wasm2kt.runtime.canonical.AsyncTable
import wasm2kt.runtime.canonical.AsyncTrampolineSpec
import wasm2kt.runtime.canonical.AsyncTransmitTableInfo
import wasm2kt.runtime.canonical.BorrowScope
import wasm2kt.runtime.canonical.CanonicalAbi
import wasm2kt.runtime.canonical.CanonicalMemory
import wasm2kt.runtime.canonical.FuncType
import wasm2kt.runtime.canonical.HandleTable
import wasm2kt.runtime.canonical.InternalWasm2KtApi
import wasm2kt.runtime.canonical.Lifter
import wasm2kt.runtime.canonical.Lowerer
import wasm2kt.runtime.canonical.ResourceTable
import wasm2kt.runtime.canonical.StringEncoding
import wasm2kt.runtime.canonical.UnsupportedAsyncIntrinsicException
import wasm2kt.runtime.canonical.UnsupportedWitTypeException
import wasm2kt.runtime.canonical.WitType
import wasm2kt.runtime.canonical.shortInterfaceName
import wasm2kt.runtime.canonical.trap
import wasm2kt.runtime.canonical.unversionedInterfaceName
import java.util.IdentityHashMap
import kotlin.coroutines.CoroutineContext

/** Invokes an AOT-compiled core Wasm export or trampoline using flat `LongArray` ABI values. */
@InternalWasm2KtApi
fun interface AotCoreFunction {
    fun call(args: LongArray): LongArray?
}

/** Invokes a core module's `post_return` export after lifting results. */
@InternalWasm2KtApi
fun interface AotPostReturn {
    fun postReturn(results: LongArray)
}

/**
 * Canonical ABI options built during generated component instantiation (Phase 1/3
 * [AotComponentBackend] and ADR-0014 [AotMappedImportBridge]).
 */
@InternalWasm2KtApi
class AotCanonOptions(
    val stringEncoding: StringEncoding,
    val instance: Int = 0,
    val memoryIndex: Int? = null,
    val async: Boolean = false,
) {
    var memory: CanonicalMemory? = null
    var realloc: AotRealloc? = null
    var postReturn: AotPostReturn? = null
    var callback: AotCoreFunction? = null
}

/** Static descriptor of a stream or future table referenced by an async AOT component. */
@InternalWasm2KtApi
class AotTransmitTableDesc(
    val index: Int,
    val instance: Int,
    val element: WitType?,
)

/** Static descriptor of an error-context table referenced by an async AOT component. */
@InternalWasm2KtApi
class AotErrorContextTableDesc(
    val index: Int,
    val instance: Int,
)

/** Static descriptor of a host import referenced by an async AOT component. */
@InternalWasm2KtApi
class AotImportDesc(
    val index: Int,
    val dispatchKey: String,
)

/** Static descriptor of an exported component function wired by [AotComponentBackend]. */
@InternalWasm2KtApi
class AotExportDesc(
    val instance: String?,
    val name: String,
    val type: FuncType,
    val options: AotCanonOptions,
    val func: AotCoreFunction,
    val optionsIndex: Int = -1,
) {
    val qualifiedName: String = instance?.let { "$it#$name" } ?: name
}

/**
 * [ComponentBackend] runtime layer for statically wired AOT components (ADR-0023 Phase 1 & Phase 3).
 *
 * Extends [AotComponentRuntime] with binary wire-format [WitWriter]/[WitReader] dispatch for
 * synchronous compatibility tests and Component Model async / WASI 0.3 execution via shared
 * `:runtime-canonical` [AsyncAbi].
 */
@InternalWasm2KtApi
class AotComponentBackend(
    private val imports: WitHostImportDispatcher?,
    resourceTables: Array<AotResourceTableDesc>,
    private val usesAsync: Boolean = false,
    streamTables: Array<AotTransmitTableDesc> = emptyArray(),
    futureTables: Array<AotTransmitTableDesc> = emptyArray(),
    errorContextTables: Array<AotErrorContextTableDesc> = emptyArray(),
    importDescs: Array<AotImportDesc> = emptyArray(),
) : AotComponentRuntime(resourceTables, hasHostResources = false), ComponentBackend {
    private val hostFunctions = HashMap<String, WitHostFunction>()
    private val asyncHostFunctions = HashMap<String, WitAsyncHostFunction?>()

    private val streamTablesByIndex = HashMap<Int, AotTransmitTableDesc>(streamTables.size).apply {
        for (t in streamTables) put(t.index, t)
    }
    private val futureTablesByIndex = HashMap<Int, AotTransmitTableDesc>(futureTables.size).apply {
        for (t in futureTables) put(t.index, t)
    }
    private val errorContextTablesByIndex = HashMap<Int, Int>(errorContextTables.size).apply {
        for (t in errorContextTables) put(t.index, t.instance)
    }
    private val importKeysByIndex = HashMap<Int, String>(importDescs.size).apply {
        for (d in importDescs) put(d.index, d.dispatchKey)
    }

    private val optionsByIndex = HashMap<Int, AotCanonOptions>()
    private val memoriesByIndex = HashMap<Int, CanonicalMemory>()
    private val callbacksByIndex = HashMap<Int, AotCoreFunction>()
    private val postReturnsByIndex = HashMap<Int, AotPostReturn>()
    private val tablesByIndex = HashMap<Int, WasmTable<FuncRef>>()
    private val asyncTrampolinesByIndex = HashMap<Int, AsyncTrampolineSpec>()
    private val marshaledFuncRefs = IdentityHashMap<FuncRef, Int>()
    private val funcRefById = ArrayList<FuncRef>()
    private val asyncCanonOptionsCache = HashMap<Int, AsyncCanonOptions>()
    private val asyncTablesCache = HashMap<Int, AsyncTable>()

    internal val async: AsyncAbi? = if (usesAsync) AsyncAbi(AotAsyncHost()) else null

    private var exports: Array<AotExportDesc> = emptyArray()
    private val resolved = ArrayList<AotExportDesc>()
    private val resolvedAsyncExports = ArrayList<AsyncExportSpec>()
    private val resolvedIndices = HashMap<String, Int>()
    private val resultsWriter = WitWriter()

    fun setExports(exports: Array<AotExportDesc>) {
        this.exports = exports
    }

    fun registerOptions(index: Int, opts: AotCanonOptions) {
        optionsByIndex[index] = opts
    }

    fun registerMemory(index: Int, memory: CanonicalMemory) {
        memoriesByIndex[index] = memory
    }

    fun registerCallback(index: Int, callback: AotCoreFunction) {
        callbacksByIndex[index] = callback
    }

    fun registerPostReturn(index: Int, postReturn: AotPostReturn) {
        postReturnsByIndex[index] = postReturn
    }

    @Suppress("UNCHECKED_CAST")
    fun registerTable(index: Int, table: WasmTable<*>) {
        tablesByIndex[index] = table as WasmTable<FuncRef>
    }

    fun registerAsyncTrampoline(index: Int, spec: AsyncTrampolineSpec) {
        asyncTrampolinesByIndex[index] = spec
    }

    override fun handleTableForInstance(instanceIndex: Int): HandleTable =
        async?.state(instanceIndex)?.handles ?: super.handleTableForInstance(instanceIndex)

    override fun onPoison() {
        async?.abandon()
    }

    @Synchronized
    override fun resolve(functionName: String): Int {
        guard.checkUsable()
        resolvedIndices[functionName]?.let { return it }
        val export = resolveExport(functionName)
        var index = -1
        for (i in resolved.indices) {
            if (resolved[i] === export) {
                index = i
                break
            }
        }
        if (index < 0) {
            index = resolved.size
            resolved.add(export)
            if (usesAsync) {
                resolvedAsyncExports.add(
                    AsyncExportSpec(
                        qualifiedName = export.qualifiedName,
                        type = export.type,
                        optionsIndex = export.optionsIndex,
                        callee = { args -> export.func.call(args) ?: CanonicalAbi.EMPTY_LONG_ARRAY },
                    ),
                )
            }
        }
        resolvedIndices[functionName] = index
        return index
    }

    @Synchronized
    private fun resolvedExport(function: Int): AotExportDesc =
        resolved.getOrNull(function) ?: throw IllegalArgumentException("Unknown function index $function")

    @Synchronized
    private fun resolvedAsyncExport(function: Int): AsyncExportSpec =
        resolvedAsyncExports.getOrNull(function) ?: throw IllegalArgumentException("Unknown function index $function")

    override suspend fun invokeSuspending(function: Int, args: WitReader): WitReader {
        val asyncAbi = async ?: return invoke(function, args)
        guard.checkUsable()
        return asyncAbi.invokeSuspending(resolvedAsyncExport(function), args)
    }

    override fun invoke(function: Int, args: WitReader): WitReader {
        guard.checkUsable()
        val asyncAbi = async
        if (asyncAbi != null) {
            return asyncAbi.invoke(resolvedAsyncExport(function), args)
        }
        val export = resolvedExport(function)
        val functionName = export.qualifiedName
        if (guard.entered) {
            guard.poison("Cannot reenter component instance: '$functionName' was called while another call into it is in progress", ::onPoison)
        }
        val type = export.type
        val rc = newResourceContext(AbiDirection.HOST_TO_GUEST)
        val cx = BackendAbiContext(export.options, rc)
        val syncDepth = syncDepthNow
        guard.entered = true
        try {
            val lowerer = Lowerer(cx, args)
            val paramTypes = type.paramTypes
            val flatParamCount = mapAotUnsupported { type.flatParamCount }
            val coreArgs = try {
                mapAotUnsupported {
                    if (flatParamCount > CanonicalAbi.MAX_FLAT_PARAMS) {
                        longArrayOf(lowerer.storeTuple(type.paramTuple).toLong())
                    } else if (flatParamCount == 0) {
                        CanonicalAbi.EMPTY_LONG_ARRAY
                    } else {
                        val out = LongArray(flatParamCount)
                        var pos = 0
                        for (i in paramTypes.indices) {
                            pos = lowerer.lowerFlat(paramTypes[i], out, pos)
                        }
                        out
                    }
                }.also {
                    if (args.remaining != 0) {
                        throw IllegalArgumentException("${args.remaining} unread bytes after the arguments of '$functionName'")
                    }
                }
            } catch (e: Throwable) {
                rc.rollbackLowering()
                throw e
            }
            rc.commitMoves()

            val coreResults = guard.guest(onPoison = ::onPoison) { export.func.call(coreArgs) ?: CanonicalAbi.EMPTY_LONG_ARRAY }
            rc.checkBorrowsDropped()

            resultsWriter.reset()
            if (type.results.isNotEmpty()) {
                guard.guest(onPoison = ::onPoison) {
                    val lifter = Lifter(cx, resultsWriter)
                    mapAotUnsupported {
                        if (type.flatResultCount > CanonicalAbi.MAX_FLAT_RESULTS) {
                            lifter.loadTuple(coreResults[0].toInt(), type.resultTuple)
                        } else {
                            val reader = Lifter.FlatReader(coreResults)
                            for (i in type.results.indices) {
                                lifter.liftFlat(type.results[i], reader)
                            }
                        }
                    }
                }
            }
            export.options.postReturn?.let { pr ->
                guard.guest(onPoison = ::onPoison) { guard.withoutLeaving { pr.postReturn(coreResults) } }
            }
            return if (resultsWriter.size > 64 * 1024) {
                val bytes = resultsWriter.toByteArray()
                resultsWriter.reset()
                WitReader(bytes)
            } else {
                resultsWriter.toReader()
            }
        } finally {
            cleanupSyncCalls(syncDepth)
            rc.releaseLends()
            guard.entered = false
        }
    }

    override fun dropResource(handle: Int) {
        val asyncAbi = async
        if (asyncAbi != null) {
            asyncAbi.onSession { dropHostHandle(handle) }
        } else {
            dropHostHandle(handle)
        }
    }

    override fun readStream(handle: Int, maxCount: Int): WitReader? = usable {
        val asyncAbi = async ?: return@usable super.readStream(handle, maxCount)
        asyncAbi.hostRead(handle, maxCount, stream = true)
    }

    override suspend fun readStreamSuspending(handle: Int, maxCount: Int): WitReader? {
        guard.checkUsable()
        val asyncAbi = async ?: return super.readStreamSuspending(handle, maxCount)
        return asyncAbi.hostReadSuspending(handle, maxCount, stream = true)
    }

    override fun readFuture(handle: Int): WitReader? = usable {
        val asyncAbi = async ?: return@usable super.readFuture(handle)
        asyncAbi.hostRead(handle, 1, stream = false)
    }

    override suspend fun readFutureSuspending(handle: Int): WitReader? {
        guard.checkUsable()
        val asyncAbi = async ?: return super.readFutureSuspending(handle)
        return asyncAbi.hostReadSuspending(handle, 1, stream = false)
    }

    override fun dropReadable(handle: Int) = usable {
        val asyncAbi = async ?: return@usable super.dropReadable(handle)
        asyncAbi.hostDropReadable(handle)
    }

    override fun newStream(values: ByteArray, count: Int): Int = usable {
        val asyncAbi = async ?: return@usable super.newStream(values, count)
        asyncAbi.onSession { asyncAbi.newHostStream(values, count) }
    }

    override fun newStream(chunks: Flow<WitStreamChunk>): Int = usable {
        val asyncAbi = async ?: return@usable super.newStream(chunks)
        asyncAbi.onSession { asyncAbi.newHostStream(chunks) }
    }

    override fun newFuture(value: ByteArray): Int = usable {
        val asyncAbi = async ?: return@usable super.newFuture(value)
        asyncAbi.onSession { asyncAbi.newHostFuture(value) }
    }

    override fun newFuture(value: suspend () -> ByteArray): Int = usable {
        val asyncAbi = async ?: return@usable super.newFuture(value)
        asyncAbi.onSession { asyncAbi.newHostFuture(value) }
    }

    private inline fun <T> usable(block: () -> T): T {
        guard.checkUsable()
        return block()
    }

    override fun close() {
        guard.closed = true
        clearHostHandles()
        async?.close()
        super.close()
    }

    fun callHost(dispatchKey: String, type: FuncType, opts: AotCanonOptions, coreArgs: LongArray): LongArray? {
        guard.checkMayLeave("host import '$dispatchKey'", ::onPoison)
        return invokeHostWithFn(dispatchKey, type, opts, coreArgs, hostFunction(dispatchKey))
    }

    fun callHostAsyncAware(importIndex: Int, type: FuncType, optsIndex: Int, coreArgs: LongArray): LongArray? {
        val key = importKeysByIndex.getValue(importIndex)
        val opts = optionsByIndex.getValue(optsIndex)
        val asyncAbi = async ?: return callHost(key, type, opts, coreArgs)
        guard.checkMayLeave("host import '$key'", ::onPoison)
        if (opts.async) {
            return asyncAbi.callHostAsync(importIndex, type, optsIndex, coreArgs)
        }
        var results: LongArray? = null
        if (asyncAbi.callHostSync(importIndex, type) { fn -> results = invokeHostWithFn(key, type, opts, coreArgs, fn) }) {
            return results
        }
        return invokeHostWithFn(key, type, opts, coreArgs, hostFunction(key))
    }

    private fun invokeHostWithFn(
        dispatchKey: String,
        type: FuncType,
        opts: AotCanonOptions,
        coreArgs: LongArray,
        fn: WitHostFunction,
    ): LongArray? {
        val rc = newResourceContext(AbiDirection.GUEST_TO_HOST)
        val cx = BackendAbiContext(opts, rc)
        val args = WitWriter()
        val lifter = Lifter(cx, args)
        val paramTypes = type.paramTypes
        val flatParamCount = mapAotUnsupported { type.flatParamCount }
        val flatResultCount = mapAotUnsupported { type.flatResultCount }

        mapAotUnsupported {
            if (flatParamCount > CanonicalAbi.MAX_FLAT_PARAMS) {
                lifter.loadTuple(coreArgs[0].toInt(), type.paramTuple)
            } else if (paramTypes.isNotEmpty()) {
                val reader = Lifter.FlatReader(coreArgs)
                for (i in paramTypes.indices) {
                    lifter.liftFlat(paramTypes[i], reader)
                }
            }
        }

        val results = WitWriter()
        bind { guard.hostImport { fn.call(args.toReader(), results) } }
        val reader = results.toReader()

        val lowerer = Lowerer(cx, reader)
        return try {
            val lowered = mapAotUnsupported {
                if (flatResultCount > CanonicalAbi.MAX_FLAT_RESULTS) {
                    val retPtr = coreArgs[coreArgs.size - 1].toInt()
                    lowerer.storeTupleAt(type.resultTuple, retPtr)
                    null
                } else if (type.results.isEmpty()) {
                    null
                } else {
                    val out = LongArray(flatResultCount)
                    lowerer.lowerFlat(type.results[0], out, 0)
                    out
                }
            }
            if (reader.remaining != 0) {
                throw IllegalStateException("${reader.remaining} unread bytes after the results of host import '$dispatchKey'")
            }
            rc.commitMoves()
            lowered
        } catch (t: Throwable) {
            rc.rollbackLowering()
            throw t
        } finally {
            rc.releaseLends()
        }
    }

    fun packFuncRef(ref: FuncRef?): Long {
        if (ref == null) return 0L
        synchronized(marshaledFuncRefs) {
            return marshaledFuncRefs.getOrPut(ref) {
                val id = funcRefById.size + 1
                funcRefById.add(ref)
                id
            }.toLong()
        }
    }

    private fun invokeMarshaledFuncRef(id: Int, args: LongArray): LongArray {
        val ref = if (id > 0) synchronized(marshaledFuncRefs) { funcRefById.getOrNull(id - 1) } else null
        if (ref == null) trap("Async call reference handle $id is null or invalid")
        return when (ref) {
            is FuncRef.Host -> ref.trampoline.call(args) ?: CanonicalAbi.EMPTY_LONG_ARRAY
            is FuncRef.Core -> {
                when (val owner = ref.owner) {
                    is RawCallableCoreModule -> owner.invokeRaw(ref.funcIndex, args) ?: CanonicalAbi.EMPTY_LONG_ARRAY
                    is CoreModuleInstance -> invokeCoreViaIndirect(owner, ref.funcIndex, ref.signature, args)
                    else -> error("Invalid core function owner ${owner.javaClass.name}")
                }
            }
        }
    }

    private fun invokeCoreViaIndirect(
        owner: CoreModuleInstance,
        funcIndex: Int,
        sig: String,
        args: LongArray,
    ): LongArray {
        val closeParen = sig.indexOf(')')
        if (closeParen < 1) error("Malformed FuncRef signature '$sig'")
        val paramCodes = sig.substring(1, closeParen)
        val resultCodes = sig.substring(closeParen + 1)
        if (args.size != paramCodes.length) {
            error("Raw indirect call argument count mismatch: expected ${paramCodes.length}, got ${args.size}")
        }
        val boxed = arrayOfNulls<Any?>(paramCodes.length)
        for (i in paramCodes.indices) {
            boxed[i] = when (paramCodes[i]) {
                'I' -> args[i].toInt()
                'J' -> args[i]
                'F' -> java.lang.Float.intBitsToFloat(args[i].toInt())
                'D' -> java.lang.Double.longBitsToDouble(args[i])
                'R', 'E' -> {
                    if (args[i] == 0L) null
                    else error("CoreModuleInstance cannot receive non-null reference argument for signature '$sig'")
                }
                else -> error("Unsupported parameter signature code '${paramCodes[i]}' in '$sig'")
            }
        }
        val rawResults = owner.invokeIndirect(funcIndex, boxed)
        if (resultCodes == "V" || resultCodes.isEmpty()) return CanonicalAbi.EMPTY_LONG_ARRAY
        if (rawResults == null || rawResults.size != resultCodes.length) {
            error("Raw indirect call result count mismatch for signature '$sig'")
        }
        val out = LongArray(resultCodes.length)
        for (i in resultCodes.indices) {
            out[i] = when (resultCodes[i]) {
                'I' -> (rawResults[i] as Int).toLong()
                'J' -> rawResults[i] as Long
                'F' -> java.lang.Float.floatToRawIntBits(rawResults[i] as Float).toLong()
                'D' -> java.lang.Double.doubleToRawLongBits(rawResults[i] as Double)
                'R', 'E' -> if (rawResults[i] == null) 0L else error("CoreModuleInstance cannot return non-null references")
                else -> error("Unsupported result signature code '${resultCodes[i]}' in '$sig'")
            }
        }
        return out
    }

    private val asyncCoreDispatcher = AsyncCoreInstance { id, a -> invokeMarshaledFuncRef(id, a) }

    fun callAsyncTrampoline(trampolineIndex: Int, args: LongArray): LongArray? {
        val spec = asyncTrampolinesByIndex.getValue(trampolineIndex)
        val asyncAbi = async ?: unsupportedTrampoline(spec.kind)
        return try {
            asyncAbi.invokeTrampoline(spec, asyncCoreDispatcher, args)
        } catch (_: UnsupportedAsyncIntrinsicException) {
            unsupportedTrampoline(spec.kind)
        }
    }

    fun contextGet(slot: Int): Int = async?.contextGet(slot) ?: 0

    fun contextSet(slot: Int, value: Int) {
        async?.contextSet(slot, value)
    }

    override fun dropImportedResource(info: AotResourceTableDesc, rep: Int) {
        val key = info.dropKey ?: return
        guard.hostImport { hostFunction(key).call(WitWriter(4).apply { int(rep) }.toReader(), WitWriter(0)) }
    }

    private fun hostFunction(key: String): WitHostFunction = hostFunctions.getOrPut(key) {
        val dispatcher = imports ?: throw IllegalStateException("Component imports '$key' but no host imports were provided")
        dispatcher.hostFunction(key) ?: throw IllegalStateException("Unknown host import: $key")
    }

    private fun asyncHostImport(key: String): WitAsyncHostFunction? =
        asyncHostFunctions.getOrPut(key) { imports?.asyncHostFunction(key) }

    private inline fun <T> mapAotUnsupported(block: () -> T): T = try {
        block()
    } catch (e: UnsupportedWitTypeException) {
        throw UnsupportedOperationException("WIT type is not supported by the AOT backend yet: ${e.detail}", e)
    }

    private fun asyncCanonOptions(index: Int): AsyncCanonOptions = asyncCanonOptionsCache.getOrPut(index) {
        val opts = optionsByIndex.getValue(index)
        AsyncCanonOptions(
            stringEncoding = opts.stringEncoding,
            memoryIndex = opts.memoryIndex,
            memory = { opts.memory },
            realloc = opts.realloc?.let { r -> { align, size -> r.realloc(0, 0, align, size) } },
            postReturn = opts.postReturn?.let { pr -> { res -> pr.postReturn(res) } },
            callback = opts.callback?.let { cb -> { a -> cb.call(a) ?: CanonicalAbi.EMPTY_LONG_ARRAY } },
            async = opts.async,
            instance = opts.instance,
        )
    }

    private fun resolveExport(spec: String): AotExportDesc {
        val hash = spec.indexOf('#')
        if (hash >= 0) {
            val iface = spec.substring(0, hash)
            val fn = spec.substring(hash + 1)
            val members = exports.filter { it.name == fn && it.instance != null }
            members.firstOrNull { it.instance == iface }?.let { return it }
            val candidates = members.filter {
                unversionedInterfaceName(it.instance!!) == iface || shortInterfaceName(it.instance) == iface
            }
            return unique(spec, candidates) { "Exported function '$fn' not found in interface '$iface'" }
        }
        exports.firstOrNull { it.instance == null && it.name == spec }?.let { return it }
        return unique(spec, exports.filter { it.name == spec }) { "Exported function '$spec' not found" }
    }

    private fun unique(spec: String, candidates: List<AotExportDesc>, notFound: () -> String): AotExportDesc = when (candidates.size) {
        0 -> throw IllegalArgumentException(notFound())
        1 -> candidates[0]
        else -> throw IllegalArgumentException(
            "Function name '$spec' is ambiguous; use one of: " +
                candidates.joinToString { "${it.instance}#${it.name}" },
        )
    }

    private inner class AotAsyncHost : AsyncBackendHost {
        override val backend: ComponentBackend get() = this@AotComponentBackend
        override val guard get() = this@AotComponentBackend.guard

        override fun checkUsable() = guard.checkUsable()
        override fun poison(message: String): Nothing = guard.poison(message, ::onPoison)
        override fun <T> guest(block: () -> T): T = guard.guest(onPoison = ::onPoison, block = block)
        override fun asTrap(e: Throwable): Throwable = guard.mapTrap(e)
        override fun <T> withoutLeaving(block: () -> T): T = guard.withoutLeaving(block)
        override fun checkMayLeave(what: String) = guard.checkMayLeave(what, ::onPoison)
        override fun <T> mapUnsupported(block: () -> T): T = mapAotUnsupported(block)
        override fun <T> bind(block: () -> T): T = this@AotComponentBackend.bind(block)
        override fun asContextElement(): CoroutineContext = this@AotComponentBackend.asContextElement()

        override fun context(
            optionsIndex: Int,
            direction: AbiBoundaryDirection,
            borrowScope: (() -> BorrowScope)?,
        ): AsyncAbiContext {
            val dir = when (direction) {
                AbiBoundaryDirection.HOST_TO_GUEST -> AbiDirection.HOST_TO_GUEST
                AbiBoundaryDirection.GUEST_TO_HOST -> AbiDirection.GUEST_TO_HOST
            }
            return BackendAbiContext(optionsByIndex.getValue(optionsIndex), newResourceContext(dir, borrowScope))
        }

        override fun resourceTable(tableIndex: Int): ResourceTable {
            val instIdx = resourceTableDesc(tableIndex)?.instance ?: 0
            return ResourceTable(handleTableForInstance(instIdx), tableIndex)
        }

        override fun resourceTableInfo(tableIndex: Int): AsyncResourceTableInfo? =
            resourceTableDesc(tableIndex)?.let { desc ->
                AsyncResourceTableInfo(desc.imported, desc.instance, desc.definedInInstance)
            }

        override fun transmitTableInfo(tableIndex: Int, stream: Boolean): AsyncTransmitTableInfo {
            val info = (if (stream) streamTablesByIndex[tableIndex] else futureTablesByIndex[tableIndex])
                ?: throw IllegalStateException("Unknown ${if (stream) "stream" else "future"} table $tableIndex")
            return AsyncTransmitTableInfo(info.instance, info.element)
        }

        override fun errorContextInstance(tableIndex: Int): Int = errorContextTablesByIndex[tableIndex] ?: 0

        override fun canonOptions(optionsIndex: Int): AsyncCanonOptions = asyncCanonOptions(optionsIndex)

        override fun memory(memoryIndex: Int): CanonicalMemory = memoriesByIndex.getValue(memoryIndex)

        override fun callback(callbackIndex: Int): (LongArray) -> LongArray =
            callbacksByIndex.getValue(callbackIndex).let { f -> { a -> f.call(a) ?: CanonicalAbi.EMPTY_LONG_ARRAY } }

        override fun postReturn(postReturnIndex: Int): (LongArray) -> Unit =
            postReturnsByIndex.getValue(postReturnIndex).let { pr -> { r -> pr.postReturn(r) } }

        override fun table(tableIndex: Int): AsyncTable? = asyncTablesCache.getOrPut(tableIndex) {
            val tbl = tablesByIndex[tableIndex] ?: return null
            AsyncTable { fi, closure ->
                if (fi < 0 || fi >= tbl.size()) trap("thread.new-indirect: table index $fi out of bounds")
                val ref = tbl.get(fi) ?: trap("thread.new-indirect: uninitialized table element $fi")
                if (ref.signature != "(I)V") {
                    trap("thread.new-indirect: the start function has the wrong type")
                }
                val runner: () -> Unit = { AotDispatchSupport.invokeExternal(ref, arrayOf(closure.toInt())) }
                runner
            }
        }

        override fun importDispatchKey(importIndex: Int): String = importKeysByIndex.getValue(importIndex)
        override fun hostImport(importIndex: Int): WitHostFunction = hostFunction(importKeysByIndex.getValue(importIndex))
        override fun asyncHostImport(importIndex: Int): WitAsyncHostFunction? =
            this@AotComponentBackend.asyncHostImport(importKeysByIndex.getValue(importIndex))
    }

    private inner class BackendAbiContext(
        private val opts: AotCanonOptions,
        private val rc: DirectResourceContext,
    ) : AsyncAbiContext {
        override val memory: CanonicalMemory
            get() = opts.memory
                ?: throw IllegalStateException("This function needs linear memory but its canonical options declare none")
        override val encoding: StringEncoding get() = opts.stringEncoding

        override fun realloc(align: Int, size: Int): Int {
            val f = opts.realloc
                ?: throw IllegalStateException("This function needs cabi_realloc but its canonical options declare none")
            return guard.guest(onPoison = ::onPoison) { guard.withoutLeaving { f.realloc(0, 0, align, size) } }
        }

        override fun lowerOwn(table: Int, id: Int): Int = rc.lowerOwn(table, id)
        override fun lowerBorrow(table: Int, id: Int): Int = rc.lowerBorrow(table, id)
        override fun liftOwn(table: Int, handle: Int): Int = rc.liftOwn(table, handle)
        override fun liftBorrow(table: Int, handle: Int): Int = rc.liftBorrow(table, handle)

        override fun liftStream(table: Int, handle: Int): Int =
            async?.liftEndToHost(table, handle, stream = true) ?: super.liftStream(table, handle)

        override fun lowerStream(table: Int, id: Int): Int =
            async?.lowerEndFromHost(table, id, stream = true, onRollback = rc::recordRollback) ?: super.lowerStream(table, id)

        override fun liftFuture(table: Int, handle: Int): Int =
            async?.liftEndToHost(table, handle, stream = false) ?: super.liftFuture(table, handle)

        override fun lowerFuture(table: Int, id: Int): Int =
            async?.lowerEndFromHost(table, id, stream = false, onRollback = rc::recordRollback) ?: super.lowerFuture(table, id)

        override fun liftErrorContext(handle: Int): Int =
            async?.liftErrorContextToHost(opts.instance, handle) ?: super.liftErrorContext(handle)

        override fun lowerErrorContext(id: Int): Int =
            async?.lowerErrorContextFromHost(opts.instance, id, onRollback = rc::recordRollback) ?: super.lowerErrorContext(id)

        override fun commitMoves() = rc.commitMoves()
        override fun rollbackLowering() = rc.rollbackLowering()
        override fun releaseLends() = rc.releaseLends()
        override fun checkBorrowsDropped() = rc.checkBorrowsDropped()
    }
}
