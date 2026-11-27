@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.tooling.aot

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import wasm2kt.runtime.aot.WasmValKind
import wasm2kt.runtime.canonical.InternalWasm2KtApi
import wasm2kt.tooling.ir.IrExportKind
import wasm2kt.tooling.ir.IrFuncType
import wasm2kt.tooling.ir.IrGlobalType
import wasm2kt.tooling.ir.IrMemoryType
import wasm2kt.tooling.ir.IrMutability
import wasm2kt.tooling.ir.IrTableType
import wasm2kt.tooling.ir.IrValType
import java.io.ByteArrayOutputStream

/** Options controlling Core Wasm to JVM bytecode compilation and pre-emission splitting. */
data class AotCompileOptions(
    val targetMethodBytes: Int = DEFAULT_TARGET_METHOD_BYTES,
    val maxFuncsPerGroup: Int = 100,
    val maxConstPoolBudget: Int = 24_000,
    val maxDispatchBucketSize: Int = 96,
    val emitLineNumbers: Boolean = true,
    val verifyClasses: Boolean = false,
    val emitRawCallDispatch: Boolean = false,
) {
    companion object {
        const val DEFAULT_TARGET_METHOD_BYTES: Int = 7_500
    }
}

/** Exact JVM method target holding a compiled Core Wasm function (or import bridge). */
data class CompiledFunctionTarget(
    val funcIndex: Int,
    val ownerInternalName: String,
    val methodName: String,
    val methodDescriptor: String,
    val funcType: IrFuncType,
    val overflowParamCount: Int,
    val multiValueClassInternalName: String?,
)

/** Exported entity exposed by a compiled Core Wasm module instance. */
sealed interface CompiledExport {
    val name: String
    val kind: IrExportKind
    val index: Int

    data class Function(
        override val name: String,
        override val index: Int,
        val target: CompiledFunctionTarget,
    ) : CompiledExport {
        override val kind: IrExportKind get() = IrExportKind.FUNCTION
    }

    data class Table(
        override val name: String,
        override val index: Int,
        val fieldName: String,
        val tableType: IrTableType,
    ) : CompiledExport {
        override val kind: IrExportKind get() = IrExportKind.TABLE
    }

    data class Memory(
        override val name: String,
        override val index: Int,
        val fieldName: String,
        val memoryType: IrMemoryType,
    ) : CompiledExport {
        override val kind: IrExportKind get() = IrExportKind.MEMORY
    }

    data class Global(
        override val name: String,
        override val index: Int,
        val fieldName: String,
        val globalType: IrGlobalType,
        val isBoxedCell: Boolean,
    ) : CompiledExport {
        override val kind: IrExportKind get() = IrExportKind.GLOBAL
    }
}

/** Compilation and method-size statistics for a compiled Core Wasm module. */
data class AotCompileStats(
    val moduleIndex: Int,
    val definedFunctions: Int,
    val generatedClasses: Int,
    val splitFunctions: Int,
    val generatedSplitHelpers: Int,
    val interpretedFunctions: Int,
    val hotspotOversizedMethods: Int,
    val artOversizedMethods: Int,
    val maxMethodCodeBytes: Int,
    val maxMethodName: String?,
    val maxConstantPoolCount: Int,
    val dataResourceSizeBytes: Int,
)

/** Result of [AotCoreCompiler.verifyAll] checking all emitted classfiles. */
data class VerificationReport(
    val verifiedClasses: Int,
    val totalMethods: Int,
    val maxMethodCodeBytes: Int,
    val maxMethodName: String?,
    val maxConstantPoolCount: Int,
    val hotspotOversizedMethods: Int,
    val artOversizedMethods: Int,
)

/** Compiled JVM classfiles, optional packed data resource, and metadata for one Core Wasm module. */
class CompiledAotModule(
    val moduleIndex: Int,
    val packageName: String,
    val baseSimpleName: String,
    val instanceInternalName: String,
    val importsInterfaceInternalName: String?,
    val dataResourcePath: String?,
    val dataResourceBytes: ByteArray?,
    val classes: Map<String, ByteArray>,
    val exports: List<CompiledExport>,
    val functionTargets: Map<Int, CompiledFunctionTarget>,
    val methodCodeBytes: Map<String, Int>,
    val constantPoolCounts: Map<String, Int>,
    val stats: AotCompileStats,
) {
    val instanceBinaryName: String get() = instanceInternalName.replace('/', '.')
}

internal object AotNames {
    const val RUNTIME_PACKAGE = "wasm2kt/runtime/aot"
    const val LINEAR_MEMORY_INTERNAL = "$RUNTIME_PACKAGE/LinearMemory"
    const val LINEAR_MEMORY_DESC = "L$LINEAR_MEMORY_INTERNAL;"
    const val WASM_TABLE_INTERNAL = "$RUNTIME_PACKAGE/WasmTable"
    const val WASM_TABLE_DESC = "L$WASM_TABLE_INTERNAL;"
    const val ELEM_STORE_INTERNAL = "$RUNTIME_PACKAGE/ElemSegmentStore"
    const val ELEM_STORE_DESC = "L$ELEM_STORE_INTERNAL;"
    const val DATA_STORE_INTERNAL = "$RUNTIME_PACKAGE/DataSegmentStore"
    const val DATA_STORE_DESC = "L$DATA_STORE_INTERNAL;"
    const val FUNC_REF_INTERNAL = "$RUNTIME_PACKAGE/FuncRef"
    const val FUNC_REF_CORE_INTERNAL = "$FUNC_REF_INTERNAL\$Core"
    const val FUNC_REF_DESC = "L$FUNC_REF_INTERNAL;"
    const val SPILL_FRAME_INTERNAL = "$RUNTIME_PACKAGE/AotSpillFrame"
    const val SPILL_FRAME_DESC = "L$SPILL_FRAME_INTERNAL;"
    const val CORE_INSTANCE_IFACE_INTERNAL = "$RUNTIME_PACKAGE/CoreModuleInstance"
    const val RAW_CALLABLE_CORE_INSTANCE_IFACE_INTERNAL = "$RUNTIME_PACKAGE/RawCallableCoreModule"
    const val DISPATCH_SUPPORT_INTERNAL = "$RUNTIME_PACKAGE/AotDispatchSupport"
    const val TRAP_SUPPORT_INTERNAL = "$RUNTIME_PACKAGE/AotTrapSupport"
    const val NUMERICS_INTERNAL = "$RUNTIME_PACKAGE/WasmNumerics"
    const val OPS_INTERNAL = "$RUNTIME_PACKAGE/AotOps"

    const val MAX_DIRECT_JVM_PARAM_SLOTS = 240

    fun irValKind(t: IrValType): WasmValKind = when (t) {
        IrValType.I32 -> WasmValKind.I32
        IrValType.I64 -> WasmValKind.I64
        IrValType.F32 -> WasmValKind.F32
        IrValType.F64 -> WasmValKind.F64
        IrValType.FUNCREF -> WasmValKind.FUNCREF
        IrValType.EXTERNREF -> WasmValKind.EXTERNREF
    }

    fun canonicalSignature(type: IrFuncType): String = type.compactSignature

    fun jvmType(t: IrValType): Type = when (t) {
        IrValType.I32 -> Type.INT_TYPE
        IrValType.I64 -> Type.LONG_TYPE
        IrValType.F32 -> Type.FLOAT_TYPE
        IrValType.F64 -> Type.DOUBLE_TYPE
        IrValType.FUNCREF -> Type.getObjectType(FUNC_REF_INTERNAL)
        IrValType.EXTERNREF -> Type.getType(Any::class.java)
    }

    fun jvmDesc(t: IrValType): String = when (t) {
        IrValType.I32 -> "I"
        IrValType.I64 -> "J"
        IrValType.F32 -> "F"
        IrValType.F64 -> "D"
        IrValType.FUNCREF -> FUNC_REF_DESC
        IrValType.EXTERNREF -> "Ljava/lang/Object;"
    }

    fun slotSize(t: IrValType): Int = when (t) {
        IrValType.I64, IrValType.F64 -> 2
        else -> 1
    }

    fun isRef(t: IrValType): Boolean = t == IrValType.FUNCREF || t == IrValType.EXTERNREF

    fun boxedGlobalInternalName(t: IrValType): String = when (t) {
        IrValType.I32 -> "$RUNTIME_PACKAGE/I32Global"
        IrValType.I64 -> "$RUNTIME_PACKAGE/I64Global"
        IrValType.F32 -> "$RUNTIME_PACKAGE/F32Global"
        IrValType.F64 -> "$RUNTIME_PACKAGE/F64Global"
        IrValType.FUNCREF -> "$RUNTIME_PACKAGE/FuncRefGlobal"
        IrValType.EXTERNREF -> "$RUNTIME_PACKAGE/ExternRefGlobal"
    }

    fun boxedGlobalDesc(t: IrValType): String = "L${boxedGlobalInternalName(t)};"

    fun globalSetHelperName(t: IrValType): String = when (t) {
        IrValType.I32 -> "setI32Global"
        IrValType.I64 -> "setI64Global"
        IrValType.F32 -> "setF32Global"
        IrValType.F64 -> "setF64Global"
        IrValType.FUNCREF -> "setFuncRefGlobal"
        IrValType.EXTERNREF -> "setExternRefGlobal"
    }

    fun loadOpcode(t: IrValType): Int = when (t) {
        IrValType.I32 -> Opcodes.ILOAD
        IrValType.I64 -> Opcodes.LLOAD
        IrValType.F32 -> Opcodes.FLOAD
        IrValType.F64 -> Opcodes.DLOAD
        IrValType.FUNCREF, IrValType.EXTERNREF -> Opcodes.ALOAD
    }

    fun storeOpcode(t: IrValType): Int = when (t) {
        IrValType.I32 -> Opcodes.ISTORE
        IrValType.I64 -> Opcodes.LSTORE
        IrValType.F32 -> Opcodes.FSTORE
        IrValType.F64 -> Opcodes.DSTORE
        IrValType.FUNCREF, IrValType.EXTERNREF -> Opcodes.ASTORE
    }

    fun returnOpcode(t: IrValType): Int = when (t) {
        IrValType.I32 -> Opcodes.IRETURN
        IrValType.I64 -> Opcodes.LRETURN
        IrValType.F32 -> Opcodes.FRETURN
        IrValType.F64 -> Opcodes.DRETURN
        IrValType.FUNCREF, IrValType.EXTERNREF -> Opcodes.ARETURN
    }

    fun multiValueKey(results: List<IrValType>): String =
        buildString(results.size) {
            for (r in results) append(r.signatureCode)
        }

    fun multiValueInternalName(instanceInternalName: String, results: List<IrValType>): String =
        "$instanceInternalName\$Result_${multiValueKey(results)}"

    fun returnTypeDesc(instanceInternalName: String, results: List<IrValType>): String = when (results.size) {
        0 -> "V"
        1 -> jvmDesc(results[0])
        else -> "L${multiValueInternalName(instanceInternalName, results)};"
    }

    fun staticFuncDescriptor(
        instanceInternalName: String,
        funcType: IrFuncType,
        directParamCount: Int = countDirectParams(funcType.params),
    ): String = buildString {
        append('(')
        for (i in 0 until directParamCount) {
            append(jvmDesc(funcType.params[i]))
        }
        append('L')
        append(instanceInternalName)
        append(';')
        append(')')
        append(returnTypeDesc(instanceInternalName, funcType.results))
    }

    fun countDirectParams(params: List<IrValType>): Int {
        var slots = 0
        var count = 0
        for (p in params) {
            val w = slotSize(p)
            if (slots + w > MAX_DIRECT_JVM_PARAM_SLOTS) break
            slots += w
            count++
        }
        return count
    }

    fun importMethodDesc(params: List<IrValType>, results: List<IrValType>, instanceInternalName: String): String =
        buildString {
            append('(')
            val direct = countDirectParams(params)
            for (i in 0 until direct) append(jvmDesc(params[i]))
            if (direct < params.size) {
                append(SPILL_FRAME_DESC)
            }
            append(')')
            append(returnTypeDesc(instanceInternalName, results))
        }

    fun memoryFieldName(index: Int): String = "mem_$index"
    fun tableFieldName(index: Int): String = "tab_$index"
    fun globalFieldName(index: Int): String = "glob_$index"
    fun funcMethodName(index: Int): String = "func_$index"
    fun importBridgeName(index: Int): String = "imp_$index"
}

/** Packs a module's data segments in `[count: i32 LE] ([len: i32 LE] [bytes])*` format. */
internal object DataSegmentPacker {
    fun pack(segments: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        writeIntLE(out, segments.size)
        for (seg in segments) {
            writeIntLE(out, seg.size)
            out.writeBytes(seg)
        }
        return out.toByteArray()
    }

    private fun writeIntLE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }
}
