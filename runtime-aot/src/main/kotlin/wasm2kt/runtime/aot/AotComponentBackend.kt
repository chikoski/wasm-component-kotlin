@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.runtime.aot

import wasm2kt.runtime.ComponentBackend
import wasm2kt.runtime.HostFunction as WitHostFunction
import wasm2kt.runtime.WitHostImportDispatcher
import wasm2kt.runtime.WitReader
import wasm2kt.runtime.WitWriter
import wasm2kt.runtime.canonical.AbiContext
import wasm2kt.runtime.canonical.CanonicalAbi
import wasm2kt.runtime.canonical.CanonicalMemory
import wasm2kt.runtime.canonical.FuncType
import wasm2kt.runtime.canonical.InternalWasm2KtApi
import wasm2kt.runtime.canonical.Lifter
import wasm2kt.runtime.canonical.Lowerer
import wasm2kt.runtime.canonical.StringEncoding
import wasm2kt.runtime.canonical.UnsupportedWitTypeException
import wasm2kt.runtime.canonical.shortInterfaceName
import wasm2kt.runtime.canonical.unversionedInterfaceName

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
 * Canonical ABI options built during generated component instantiation (Phase 1 [AotComponentBackend]
 * and ADR-0014 [AotMappedImportBridge]).
 */
@InternalWasm2KtApi
class AotCanonOptions(
    val stringEncoding: StringEncoding,
    val instance: Int = 0,
) {
    var memory: CanonicalMemory? = null
    var realloc: AotRealloc? = null
    var postReturn: AotPostReturn? = null
}

/** Static descriptor of an exported component function wired by Phase 1 generated code. */
@InternalWasm2KtApi
class AotExportDesc(
    val instance: String?,
    val name: String,
    val type: FuncType,
    val options: AotCanonOptions,
    val func: AotCoreFunction,
) {
    val qualifiedName: String = instance?.let { "$it#$name" } ?: name
}

/**
 * Phase 1 [ComponentBackend] runtime compatibility layer for statically wired AOT components
 * (ADR-0023).
 *
 * Extends [AotComponentRuntime] and layers the binary wire-format [WitWriter]/[WitReader] dispatch
 * on top so differential tests, conformance tests, and Phase 1 vs Phase 2 comparisons keep working.
 */
@InternalWasm2KtApi
class AotComponentBackend(
    private val imports: WitHostImportDispatcher?,
    resourceTables: Array<AotResourceTableDesc>,
) : AotComponentRuntime(resourceTables, hasHostResources = false), ComponentBackend {
    private val hostFunctions = HashMap<String, WitHostFunction>()

    private var exports: Array<AotExportDesc> = emptyArray()
    private val resolved = ArrayList<AotExportDesc>()
    private val resolvedIndices = HashMap<String, Int>()
    private val resultsWriter = WitWriter()

    fun setExports(exports: Array<AotExportDesc>) {
        this.exports = exports
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
        }
        resolvedIndices[functionName] = index
        return index
    }

    @Synchronized
    private fun resolvedExport(function: Int): AotExportDesc =
        resolved.getOrNull(function) ?: throw IllegalArgumentException("Unknown function index $function")

    override fun invoke(function: Int, args: WitReader): WitReader {
        guard.checkUsable()
        val export = resolvedExport(function)
        val functionName = export.qualifiedName
        if (guard.entered) {
            guard.poison("Cannot reenter component instance: '$functionName' was called while another call into it is in progress")
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

            val coreResults = guard.guest { export.func.call(coreArgs) ?: CanonicalAbi.EMPTY_LONG_ARRAY }
            rc.checkBorrowsDropped()

            resultsWriter.reset()
            if (type.results.isNotEmpty()) {
                guard.guest {
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
                guard.guest { guard.withoutLeaving { pr.postReturn(coreResults) } }
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

    fun callHost(dispatchKey: String, type: FuncType, opts: AotCanonOptions, coreArgs: LongArray): LongArray? {
        guard.checkMayLeave("host import '$dispatchKey'")
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
        bind { guard.hostImport { hostFunction(dispatchKey).call(args.toReader(), results) } }
        val reader = results.toReader()

        val lowerer = Lowerer(cx, reader)
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
        return lowered
    }

    override fun dropImportedResource(info: AotResourceTableDesc, rep: Int) {
        val key = info.dropKey ?: return
        guard.hostImport { hostFunction(key).call(WitWriter(4).apply { int(rep) }.toReader(), WitWriter(0)) }
    }

    private fun hostFunction(key: String): WitHostFunction = hostFunctions.getOrPut(key) {
        val dispatcher = imports ?: throw IllegalStateException("Component imports '$key' but no host imports were provided")
        dispatcher.hostFunction(key) ?: throw IllegalStateException("Unknown host import: $key")
    }

    private inline fun <T> mapAotUnsupported(block: () -> T): T = try {
        block()
    } catch (e: UnsupportedWitTypeException) {
        throw UnsupportedOperationException("WIT type is not supported by the AOT backend yet: ${e.detail}", e)
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

    private inner class BackendAbiContext(
        private val opts: AotCanonOptions,
        private val rc: DirectResourceContext,
    ) : AbiContext {
        override val memory: CanonicalMemory
            get() = opts.memory
                ?: throw IllegalStateException("This function needs linear memory but its canonical options declare none")
        override val encoding: StringEncoding get() = opts.stringEncoding

        override fun realloc(align: Int, size: Int): Int {
            val f = opts.realloc
                ?: throw IllegalStateException("This function needs cabi_realloc but its canonical options declare none")
            return guard.guest { guard.withoutLeaving { f.realloc(0, 0, align, size) } }
        }

        override fun lowerOwn(table: Int, id: Int): Int = rc.lowerOwn(table, id)
        override fun lowerBorrow(table: Int, id: Int): Int = rc.lowerBorrow(table, id)
        override fun liftOwn(table: Int, handle: Int): Int = rc.liftOwn(table, handle)
        override fun liftBorrow(table: Int, handle: Int): Int = rc.liftBorrow(table, handle)
    }
}
