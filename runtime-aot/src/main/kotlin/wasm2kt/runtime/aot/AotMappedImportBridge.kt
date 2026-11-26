@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.runtime.aot

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

/**
 * Isolated binary wire-format adapter used by Phase 2 AOT components ONLY when they import at
 * least one ADR-0014 mapped external library interface (`with` / `--interfaces-only`, e.g. WASI).
 *
 * Separating this from [AotComponentRuntime] ensures R8 eliminates `WitWriter`, `WitReader`,
 * `Lowerer`, `Lifter`, `FuncType`, `WitType`, and `Linker` completely when a component only uses
 * local imports and exports.
 */
@InternalWasm2KtApi
class AotMappedImportBridge(
    private val rt: AotComponentRuntime,
    private val dispatcher: WitHostImportDispatcher,
    private val mappedDropKeys: Set<String>,
) : AotImportedResourceDropHandler {
    private val hostFunctions = HashMap<String, WitHostFunction>()

    override fun tryDrop(info: AotResourceTableDesc, rep: Int): Boolean {
        val key = info.dropKey ?: return false
        if (key !in mappedDropKeys) return false
        rt.guardHostImport { hostFunction(key).call(WitWriter(4).apply { int(rep) }.toReader(), WitWriter(0)) }
        return true
    }

    fun callMappedHost(dispatchKey: String, type: FuncType, opts: AotCanonOptions, coreArgs: LongArray): LongArray? {
        rt.checkMayLeave("host import '$dispatchKey'")
        val rc = rt.newResourceContext(AbiDirection.GUEST_TO_HOST)
        val cx = MappedAbiContext(rt, opts, rc)
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
        rt.withHostResources { rt.guardHostImport { hostFunction(dispatchKey).call(args.toReader(), results) } }
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

    private fun hostFunction(key: String): WitHostFunction = hostFunctions.getOrPut(key) {
        dispatcher.hostFunction(key) ?: throw IllegalStateException("Unknown mapped host import: $key")
    }

    private class MappedAbiContext(
        private val rt: AotComponentRuntime,
        private val opts: AotCanonOptions,
        private val rc: AotComponentRuntime.DirectResourceContext,
    ) : AbiContext {
        override val memory: CanonicalMemory
            get() = opts.memory
                ?: throw IllegalStateException("This function needs linear memory but its canonical options declare none")
        override val encoding: StringEncoding get() = opts.stringEncoding

        override fun realloc(align: Int, size: Int): Int {
            val f = opts.realloc
                ?: throw IllegalStateException("This function needs cabi_realloc but its canonical options declare none")
            return rt.guard.guest { rt.guard.withoutLeaving { f.realloc(0, 0, align, size) } }
        }

        override fun lowerOwn(table: Int, id: Int): Int = rc.lowerOwn(table, id)
        override fun lowerBorrow(table: Int, id: Int): Int = rc.lowerBorrow(table, id)
        override fun liftOwn(table: Int, handle: Int): Int = rc.liftOwn(table, handle)
        override fun liftBorrow(table: Int, handle: Int): Int = rc.liftBorrow(table, handle)
    }

    private inline fun <T> mapAotUnsupported(block: () -> T): T = try {
        block()
    } catch (e: UnsupportedWitTypeException) {
        throw UnsupportedOperationException("WIT type is not supported by the AOT backend yet: ${e.detail}", e)
    }
}
