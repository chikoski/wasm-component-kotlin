@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.runtime.aot

import wasm2kt.runtime.canonical.InternalWasm2KtApi

/**
 * Contract implemented by each generated `CoreNInstance` class so cross-module `call_indirect` can
 * invoke an exported or table-registered function owned by another module without reflection.
 */
@InternalWasm2KtApi
interface CoreModuleInstance {
    fun invokeIndirect(funcIndex: Int, args: Array<Any?>): Array<Any?>?
}

/**
 * Fallback dispatch support when a `call_indirect` table entry points to a foreign core module
 * instance or a [HostTrampoline].
 */
@InternalWasm2KtApi
object AotDispatchSupport {
    @JvmStatic
    fun invokeExternal(ref: FuncRef, args: Array<Any?>): Array<Any?>? = when (ref) {
        is FuncRef.Core -> {
            val owner = ref.owner as? CoreModuleInstance
                ?: error("Invalid core function owner ${ref.owner.javaClass.name}")
            owner.invokeIndirect(ref.funcIndex, args)
        }
        is FuncRef.Host -> invokeHost(ref, args)
    }

    private fun invokeHost(ref: FuncRef.Host, args: Array<Any?>): Array<Any?>? {
        val sig = ref.signature
        val closeParen = sig.indexOf(')')
        if (closeParen < 1) error("Malformed FuncRef signature '$sig'")
        val paramCodes = sig.substring(1, closeParen)
        val resultCodes = sig.substring(closeParen + 1)
        if (args.size != paramCodes.length) {
            error("Indirect host call argument count mismatch: expected ${paramCodes.length}, got ${args.size}")
        }
        val packed = LongArray(paramCodes.length)
        for (i in paramCodes.indices) {
            packed[i] = when (paramCodes[i]) {
                'I' -> (args[i] as Int).toLong()
                'J' -> args[i] as Long
                'F' -> java.lang.Float.floatToRawIntBits(args[i] as Float).toLong()
                'D' -> java.lang.Double.doubleToRawLongBits(args[i] as Double)
                'R', 'E' -> {
                    if (args[i] == null) 0L
                    else error("HostTrampoline cannot marshal non-null reference arguments for signature '$sig'")
                }
                else -> error("Unsupported parameter signature code '${paramCodes[i]}' in '$sig'")
            }
        }
        val rawResults = ref.trampoline.call(packed)
        if (resultCodes == "V" || resultCodes.isEmpty()) return null
        if (rawResults == null || rawResults.size != resultCodes.length) {
            error("Indirect host call result count mismatch for signature '$sig'")
        }
        val out = arrayOfNulls<Any?>(resultCodes.length)
        for (i in resultCodes.indices) {
            out[i] = when (resultCodes[i]) {
                'I' -> rawResults[i].toInt()
                'J' -> rawResults[i]
                'F' -> java.lang.Float.intBitsToFloat(rawResults[i].toInt())
                'D' -> java.lang.Double.longBitsToDouble(rawResults[i])
                'R', 'E' -> if (rawResults[i] == 0L) null else error("HostTrampoline cannot return non-null references")
                else -> error("Unsupported result signature code '${resultCodes[i]}' in '$sig'")
            }
        }
        return out
    }
}
