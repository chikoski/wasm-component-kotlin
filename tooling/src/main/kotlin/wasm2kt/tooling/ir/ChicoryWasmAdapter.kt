package wasm2kt.tooling.ir

import com.dylibso.chicory.wasm.ChicoryException
import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.WasmModule
import com.dylibso.chicory.wasm.types.ActiveDataSegment
import com.dylibso.chicory.wasm.types.ActiveElement
import com.dylibso.chicory.wasm.types.AnnotatedInstruction
import com.dylibso.chicory.wasm.types.DeclarativeElement
import com.dylibso.chicory.wasm.types.ExternalType
import com.dylibso.chicory.wasm.types.FunctionBody
import com.dylibso.chicory.wasm.types.FunctionImport
import com.dylibso.chicory.wasm.types.GlobalImport
import com.dylibso.chicory.wasm.types.Instruction
import com.dylibso.chicory.wasm.types.MemoryImport
import com.dylibso.chicory.wasm.types.MutabilityType
import com.dylibso.chicory.wasm.types.NameCustomSection
import com.dylibso.chicory.wasm.types.OpCode
import com.dylibso.chicory.wasm.types.PassiveDataSegment
import com.dylibso.chicory.wasm.types.PassiveElement
import com.dylibso.chicory.wasm.types.TableImport
import com.dylibso.chicory.wasm.types.TagImport
import com.dylibso.chicory.wasm.types.ValType

/**
 * Build-time adapter that parses a Core Wasm binary with Chicory and lowers it into
 * parser-independent typed IR ([IrModule]).
 *
 * This file is the sole Chicory parser dependency boundary within `wasm2kt.tooling.ir`.
 */
object ChicoryWasmAdapter {
    fun convert(wasmBytes: ByteArray, moduleIndex: Int = 0): IrModule {
        val raw = parseRawModule(wasmBytes, moduleIndex)
        val rawMeta = RawWasmInspector.inspectMetadata(wasmBytes, moduleIndex)
        return ModuleConverter(wasmBytes, moduleIndex, raw, rawMeta).convert()
    }

    private fun parseRawModule(wasmBytes: ByteArray, moduleIndex: Int): WasmModule =
        try {
            Parser.builder()
                .withValidation(false)
                .build()
                .parse { wasmBytes.inputStream() }
        } catch (e: ChicoryException) {
            throw RawWasmInspector.diagnoseFailure(wasmBytes, moduleIndex, e)
        }
}

private data class ControlFrame(
    val blockId: Int,
    val kind: IrBlockKind,
    val blockType: IrBlockType,
    val params: List<IrValType>,
    val results: List<IrValType>,
    val startInsnIndex: Int,
    var elseInsnIndex: Int? = null,
    val baseHeight: Int,
    var unreachable: Boolean = false,
) {
    val labelTypes: List<IrValType>
        get() = if (kind == IrBlockKind.LOOP) params else results
}

private data class RawModuleMetadata(
    val importTables: List<IrLimits>,
    val importMemories: List<IrLimits>,
    val definedTables: List<IrLimits>,
    val definedMemories: List<IrLimits>,
    val importedFuncCount: Int,
)

private class ModuleConverter(
    private val wasmBytes: ByteArray,
    private val moduleIndex: Int,
    private val raw: WasmModule,
    private val rawMeta: RawModuleMetadata,
) {
    private val names: NameCustomSection? = raw.nameSection()
    private val irTypes: List<IrFuncType> = convertTypes()
    private val irImports: List<IrImport> = convertImports()

    private val importedFuncTypes: List<IrFuncType> =
        irImports.filterIsInstance<IrImport.Func>().map { irTypes[it.typeIndex] }
    private val importedTableTypes: List<IrTableType> =
        irImports.filterIsInstance<IrImport.Table>().map { it.tableType }
    private val importedMemoryTypes: List<IrMemoryType> =
        irImports.filterIsInstance<IrImport.Memory>().map { it.memoryType }
    private val importedGlobalTypes: List<IrGlobalType> =
        irImports.filterIsInstance<IrImport.Global>().map { it.globalType }

    private val allFuncTypes: List<IrFuncType> = buildList {
        addAll(importedFuncTypes)
        val definedCount = raw.functionSection().functionCount()
        for (i in 0 until definedCount) {
            val typeIndex = raw.functionSection().getFunctionType(i)
            add(requireFuncType(typeIndex, null))
        }
    }

    private val irTables: List<IrTable> = convertTables()
    private val irMemories: List<IrMemory> = convertMemories()
    private val irGlobals: List<IrGlobal> = convertGlobals()

    private val allTableTypes: List<IrTableType> = importedTableTypes + irTables.map { it.type }
    private val allMemoryTypes: List<IrMemoryType> = importedMemoryTypes + irMemories.map { it.type }
    private val allGlobalTypes: List<IrGlobalType> = importedGlobalTypes + irGlobals.map { it.type }
    private val irElements: List<IrElementSegment> = convertElements()
    private val irDataSegments: List<IrDataSegment> = convertDataSegments()

    fun convert(): IrModule {
        val tagSec = raw.tagSection()
        if (tagSec.isPresent && tagSec.get().tagCount() > 0) {
            throw UnsupportedWasmFeatureException(
                feature = "exception-handling",
                moduleIndex = moduleIndex,
                detail = "Module defines ${tagSec.get().tagCount()} exception tag(s)",
            )
        }

        val exports = convertExports()
        val exportNameByFunc = exports
            .filter { it.kind == IrExportKind.FUNCTION }
            .associateBy({ it.index }, { it.name })

        val startFn = raw.startSection().map { it.startIndex().toInt() }.orElse(null)
        if (startFn != null) {
            val startType = requireFunctionType(startFn, null)
            if (startType.params.isNotEmpty() || startType.results.isNotEmpty()) {
                throw IllegalArgumentException(
                    "Start function $startFn in core module $moduleIndex must have type () -> (), got ${startType.compactSignature}",
                )
            }
        }

        val dataCount = raw.dataCountSection().map { it.dataCount() }.orElse(null)
        if (dataCount != null && dataCount != irDataSegments.size) {
            throw IllegalArgumentException(
                "DataCount mismatch in core module $moduleIndex: datacount=$dataCount, data segments=${irDataSegments.size}",
            )
        }

        val declaredFuncs = collectDeclaredFuncs(exports)

        val codeSec = raw.codeSection()
        val definedFuncCount = raw.functionSection().functionCount()
        if (codeSec.functionBodyCount() != definedFuncCount) {
            throw IllegalArgumentException(
                "Core module $moduleIndex function/code count mismatch: $definedFuncCount vs ${codeSec.functionBodyCount()}",
            )
        }

        val usesDataCountState = BooleanArray(1)
        val definedFunctions = (0 until definedFuncCount).map { bodyIdx ->
            val funcIndex = importedFuncTypes.size + bodyIdx
            val typeIndex = raw.functionSection().getFunctionType(bodyIdx)
            val fnName = safeNameOfFunction(funcIndex) ?: exportNameByFunc[funcIndex]
            convertFunction(
                funcIndex = funcIndex,
                typeIndex = typeIndex,
                funcType = requireFuncType(typeIndex, funcIndex),
                body = codeSec.getFunctionBody(bodyIdx),
                debugName = fnName,
                declaredFuncs = declaredFuncs,
                usesDataCountState = usesDataCountState,
            )
        }

        if (usesDataCountState[0] && dataCount == null) {
            throw IllegalArgumentException(
                "memory.init or data.drop requires DataCount section in core module $moduleIndex",
            )
        }

        return IrModule(
            moduleIndex = moduleIndex,
            moduleName = names?.moduleName()?.orElse(null),
            types = irTypes,
            imports = irImports,
            definedFunctions = definedFunctions,
            definedTables = irTables,
            definedMemories = irMemories,
            definedGlobals = irGlobals,
            exports = exports,
            startFunctionIndex = startFn,
            elements = irElements,
            dataSegments = irDataSegments,
            dataCount = dataCount,
        )
    }

    private fun collectDeclaredFuncs(exports: List<IrExport>): Set<Int> = buildSet {
        for (e in exports) {
            if (e.kind == IrExportKind.FUNCTION) add(e.index)
        }
        for (g in irGlobals) {
            for (op in g.initExpr.ops) {
                if (op is IrConstOp.RefFunc) add(op.funcIndex)
            }
        }
        for (el in irElements) {
            if (el is IrElementSegment.Active) {
                for (op in el.offset.ops) {
                    if (op is IrConstOp.RefFunc) add(op.funcIndex)
                }
            }
            for (item in el.items) {
                for (op in item.ops) {
                    if (op is IrConstOp.RefFunc) add(op.funcIndex)
                }
            }
        }
    }

    private fun convertTypes(): List<IrFuncType> {
        val typeSec = raw.typeSection()
        val subCount = typeSec.subTypeCount()
        val fnCount = typeSec.typeCount()
        if (subCount > 0 && subCount != fnCount) {
            throw UnsupportedWasmFeatureException(
                feature = "wasm-gc",
                moduleIndex = moduleIndex,
                detail = "Type section defines non-function composite types ($subCount subtypes vs $fnCount function types)",
            )
        }
        return (0 until fnCount).map { idx ->
            if (idx < subCount) {
                val sub = typeSec.getSubType(idx)
                if (!sub.isFinal || sub.typeIdx().isNotEmpty()) {
                    throw UnsupportedWasmFeatureException(
                        feature = "wasm-gc",
                        moduleIndex = moduleIndex,
                        detail = "Subtyping or non-final type $idx is not supported",
                    )
                }
            }
            val rawType = try {
                typeSec.getType(idx)
            } catch (e: RuntimeException) {
                throw UnsupportedWasmFeatureException(
                    feature = "wasm-gc",
                    moduleIndex = moduleIndex,
                    detail = "Composite type $idx is not a supported function type",
                    cause = e,
                )
            } ?: throw UnsupportedWasmFeatureException(
                feature = "wasm-gc",
                moduleIndex = moduleIndex,
                detail = "Composite type $idx is not a supported function type",
            )
            IrFuncType(
                params = rawType.params().map { mapValType(it, null, "type[$idx].param") },
                results = rawType.returns().map { mapValType(it, null, "type[$idx].result") },
            )
        }
    }

    private fun convertImports(): List<IrImport> {
        val impSec = raw.importSection()
        var nextFunc = 0
        var nextTable = 0
        var nextMem = 0
        var nextGlobal = 0
        return (0 until impSec.importCount()).map { i ->
            when (val imp = impSec.getImport(i)) {
                is FunctionImport -> {
                    requireFuncType(imp.typeIndex(), null)
                    IrImport.Func(imp.module(), imp.name(), imp.typeIndex(), nextFunc++)
                }
                is TableImport -> {
                    val tableIdx = nextTable++
                    val limits = rawMeta.importTables.getOrElse(tableIdx) {
                        throw IllegalStateException("Missing raw limits for imported table $tableIdx in core module $moduleIndex")
                    }
                    val tableType = IrTableType(
                        elementType = mapValType(imp.entryType(), null, "import table '${imp.module()}.${imp.name()}'"),
                        limits = limits,
                    )
                    IrImport.Table(imp.module(), imp.name(), tableType, tableIdx)
                }
                is MemoryImport -> {
                    val memIdx = nextMem++
                    val memLimits = rawMeta.importMemories.getOrElse(memIdx) {
                        throw IllegalStateException("Missing raw limits for imported memory $memIdx in core module $moduleIndex")
                    }
                    val memType = IrMemoryType(memLimits.min.toInt(), memLimits.max?.toInt())
                    IrImport.Memory(imp.module(), imp.name(), memType, memIdx)
                }
                is GlobalImport -> {
                    val globalType = IrGlobalType(
                        valueType = mapValType(imp.type(), null, "import global '${imp.module()}.${imp.name()}'"),
                        mutability = mapMutability(imp.mutabilityType()),
                    )
                    IrImport.Global(imp.module(), imp.name(), globalType, nextGlobal++)
                }
                is TagImport -> {
                    throw UnsupportedWasmFeatureException(
                        feature = "exception-handling",
                        moduleIndex = moduleIndex,
                        detail = "Imported exception tag '${imp.module()}.${imp.name()}'",
                    )
                }
                else -> throw IllegalArgumentException("Unsupported import kind in core module $moduleIndex: ${imp::class.simpleName}")
            }
        }
    }

    private fun convertTables(): List<IrTable> {
        val sec = raw.tableSection()
        return (0 until sec.tableCount()).map { i ->
            val rawTable = sec.getTable(i)
            val tableIndex = importedTableTypes.size + i
            val limits = rawMeta.definedTables.getOrElse(i) {
                throw IllegalStateException("Missing raw limits for defined table $i in core module $moduleIndex")
            }
            IrTable(
                index = tableIndex,
                type = IrTableType(
                    elementType = mapValType(rawTable.elementType(), null, "table[$tableIndex]"),
                    limits = limits,
                ),
                debugName = safeName { names?.nameOfTable(tableIndex) },
            )
        }
    }

    private fun convertMemories(): List<IrMemory> {
        val memOpt = raw.memorySection()
        if (memOpt.isEmpty) return emptyList()
        val sec = memOpt.get()
        return (0 until sec.memoryCount()).map { i ->
            val memIndex = importedMemoryTypes.size + i
            val memLimits = rawMeta.definedMemories.getOrElse(i) {
                throw IllegalStateException("Missing raw limits for defined memory $i in core module $moduleIndex")
            }
            IrMemory(
                index = memIndex,
                type = IrMemoryType(memLimits.min.toInt(), memLimits.max?.toInt()),
                debugName = safeName { names?.nameOfMemory(memIndex) },
            )
        }
    }

    private fun convertGlobals(): List<IrGlobal> {
        val sec = raw.globalSection()
        return (0 until sec.globalCount()).map { i ->
            val g = sec.getGlobal(i)
            val globalIdx = importedGlobalTypes.size + i
            val valType = mapValType(g.valueType(), null, "global[$globalIdx]")
            val mut = mapMutability(g.mutabilityType())
            val initExpr = convertConstExpr(g.initInstructions(), valType, "global[$globalIdx].init", importedGlobalTypes)
            val globalType = IrGlobalType(valType, mut)
            IrGlobal(
                index = globalIdx,
                type = globalType,
                initExpr = initExpr,
                debugName = safeName { names?.nameOfGlobal(globalIdx) },
            )
        }
    }

    private fun convertExports(): List<IrExport> {
        val sec = raw.exportSection()
        val seenNames = HashSet<String>(sec.exportCount())
        return (0 until sec.exportCount()).map { i ->
            val e = sec.getExport(i)
            if (!seenNames.add(e.name())) {
                throw IllegalArgumentException(
                    "Duplicate export name '${e.name()}' in core module $moduleIndex",
                )
            }
            val kind = when (e.exportType()) {
                ExternalType.FUNCTION -> {
                    requireFunctionType(e.index(), null)
                    IrExportKind.FUNCTION
                }
                ExternalType.TABLE -> {
                    if (e.index() !in allTableTypes.indices) {
                        throw IllegalArgumentException(
                            "Export '${e.name()}' references out-of-bounds table ${e.index()} in core module $moduleIndex",
                        )
                    }
                    IrExportKind.TABLE
                }
                ExternalType.MEMORY -> {
                    if (e.index() !in allMemoryTypes.indices) {
                        throw IllegalArgumentException(
                            "Export '${e.name()}' references out-of-bounds memory ${e.index()} in core module $moduleIndex",
                        )
                    }
                    IrExportKind.MEMORY
                }
                ExternalType.GLOBAL -> {
                    if (e.index() !in allGlobalTypes.indices) {
                        throw IllegalArgumentException(
                            "Export '${e.name()}' references out-of-bounds global ${e.index()} in core module $moduleIndex",
                        )
                    }
                    IrExportKind.GLOBAL
                }
                ExternalType.TAG -> throw UnsupportedWasmFeatureException(
                    feature = "exception-handling",
                    moduleIndex = moduleIndex,
                    detail = "Exported exception tag '${e.name()}'",
                )
            }
            IrExport(e.name(), kind, e.index())
        }
    }

    private fun convertElements(): List<IrElementSegment> {
        val sec = raw.elementSection()
        return (0 until sec.elementCount()).map { i ->
            val el = sec.getElement(i)
            val elemType = mapElementType(el.type(), "elem[$i]")
            val items = el.initializers().mapIndexed { itemIdx, initInsns ->
                convertConstExpr(initInsns, elemType, "elem[$i].item[$itemIdx]", importedGlobalTypes)
            }
            when (el) {
                is ActiveElement -> {
                    val tableIndex = el.tableIndex()
                    val tableType = allTableTypes.getOrNull(tableIndex)
                        ?: throw IllegalArgumentException(
                            "Active element segment $i references out-of-bounds table $tableIndex in core module $moduleIndex",
                        )
                    if (tableType.elementType != elemType) {
                        throw IllegalArgumentException(
                            "Active element segment $i type ${elemType.mnemonic} mismatches table $tableIndex type ${tableType.elementType.mnemonic} in core module $moduleIndex",
                        )
                    }
                    IrElementSegment.Active(
                        index = i,
                        tableIndex = tableIndex,
                        offset = convertConstExpr(el.offset(), IrValType.I32, "elem[$i].offset", importedGlobalTypes),
                        elementType = elemType,
                        items = items,
                    )
                }
                is PassiveElement -> IrElementSegment.Passive(
                    index = i,
                    elementType = elemType,
                    items = items,
                )
                is DeclarativeElement -> IrElementSegment.Declarative(
                    index = i,
                    elementType = elemType,
                    items = items,
                )
                else -> throw IllegalArgumentException("Unsupported element segment kind in core module $moduleIndex: ${el::class.simpleName}")
            }
        }
    }

    private fun convertDataSegments(): List<IrDataSegment> {
        val sec = raw.dataSection()
        return (0 until sec.dataSegmentCount()).map { i ->
            val ds = sec.getDataSegment(i)
            when (ds) {
                is ActiveDataSegment -> {
                    val memIndex = ds.index().toInt()
                    if (memIndex !in allMemoryTypes.indices) {
                        throw IllegalArgumentException(
                            "Active data segment $i references out-of-bounds memory $memIndex in core module $moduleIndex",
                        )
                    }
                    IrDataSegment.Active(
                        index = i,
                        memoryIndex = memIndex,
                        offset = convertConstExpr(ds.offsetInstructions(), IrValType.I32, "data[$i].offset", importedGlobalTypes),
                        bytes = ds.data(),
                    )
                }
                is PassiveDataSegment -> IrDataSegment.Passive(
                    index = i,
                    bytes = ds.data(),
                )
                else -> throw IllegalArgumentException("Unsupported data segment kind in core module $moduleIndex: ${ds::class.simpleName}")
            }
        }
    }

    private fun convertConstExpr(
        insns: List<Instruction>,
        expectedType: IrValType,
        contextDesc: String,
        visibleGlobals: List<IrGlobalType>,
    ): IrConstExpr {
        val trimmed = if (insns.isNotEmpty() && insns.last().opcode() == OpCode.END) {
            insns.subList(0, insns.size - 1)
        } else {
            insns
        }
        val stack = mutableListOf<IrValType>()
        val ops = mutableListOf<IrConstOp>()
        for (insn in trimmed) {
            when (val opc = insn.opcode()) {
                OpCode.I32_CONST -> {
                    ops += IrConstOp.I32Const(insn.operand(0).toInt())
                    stack += IrValType.I32
                }
                OpCode.I64_CONST -> {
                    ops += IrConstOp.I64Const(insn.operand(0))
                    stack += IrValType.I64
                }
                OpCode.F32_CONST -> {
                    ops += IrConstOp.F32Const(insn.operand(0).toInt())
                    stack += IrValType.F32
                }
                OpCode.F64_CONST -> {
                    ops += IrConstOp.F64Const(insn.operand(0))
                    stack += IrValType.F64
                }
                OpCode.GLOBAL_GET -> {
                    val globalIdx = insn.operand(0).toInt()
                    val gType = visibleGlobals.getOrNull(globalIdx)
                        ?: throw IllegalArgumentException(
                            "Global index $globalIdx out of bounds in $contextDesc of core module $moduleIndex",
                        )
                    if (gType.mutability != IrMutability.CONST) {
                        throw IllegalArgumentException(
                            "Constant expression ($contextDesc) cannot reference mutable global $globalIdx in core module $moduleIndex",
                        )
                    }
                    ops += IrConstOp.GlobalGet(globalIdx, gType.valueType)
                    stack += gType.valueType
                }
                OpCode.REF_NULL -> {
                    val refType = mapRefNullOperand(insn.operand(0), null, opc.name)
                    ops += IrConstOp.RefNull(refType)
                    stack += refType
                }
                OpCode.REF_FUNC -> {
                    val fnIdx = insn.operand(0).toInt()
                    requireFunctionType(fnIdx, null)
                    ops += IrConstOp.RefFunc(fnIdx)
                    stack += IrValType.FUNCREF
                }
                OpCode.I32_ADD, OpCode.I32_SUB, OpCode.I32_MUL -> {
                    popConstBinary(stack, IrValType.I32, opc.name, contextDesc)
                    ops += when (opc) {
                        OpCode.I32_ADD -> IrConstOp.I32Add
                        OpCode.I32_SUB -> IrConstOp.I32Sub
                        else -> IrConstOp.I32Mul
                    }
                    stack += IrValType.I32
                }
                OpCode.I64_ADD, OpCode.I64_SUB, OpCode.I64_MUL -> {
                    popConstBinary(stack, IrValType.I64, opc.name, contextDesc)
                    ops += when (opc) {
                        OpCode.I64_ADD -> IrConstOp.I64Add
                        OpCode.I64_SUB -> IrConstOp.I64Sub
                        else -> IrConstOp.I64Mul
                    }
                    stack += IrValType.I64
                }
                else -> {
                    val feature = classifyUnsupportedOpcode(opc)
                    if (feature != null) {
                        throw UnsupportedWasmFeatureException(
                            feature = feature,
                            moduleIndex = moduleIndex,
                            instructionName = opc.name,
                            detail = "Unsupported instruction in constant expression ($contextDesc)",
                        )
                    }
                    throw IllegalArgumentException(
                        "Unsupported opcode ${opc.name} in constant expression ($contextDesc) of core module $moduleIndex",
                    )
                }
            }
        }
        if (stack != listOf(expectedType)) {
            throw IllegalArgumentException(
                "Constant expression ($contextDesc) type mismatch in core module $moduleIndex: expected [${expectedType.mnemonic}], got $stack",
            )
        }
        return IrConstExpr(ops, expectedType)
    }

    private fun popConstBinary(
        stack: MutableList<IrValType>,
        expected: IrValType,
        opName: String,
        contextDesc: String,
    ) {
        if (stack.size < 2) {
            throw IllegalArgumentException(
                "Constant expression stack underflow at $opName ($contextDesc) in core module $moduleIndex",
            )
        }
        val rhs = stack.removeAt(stack.lastIndex)
        val lhs = stack.removeAt(stack.lastIndex)
        if (lhs != expected || rhs != expected) {
            throw IllegalArgumentException(
                "Constant expression type mismatch at $opName ($contextDesc) in core module $moduleIndex",
            )
        }
    }

    private fun convertFunction(
        funcIndex: Int,
        typeIndex: Int,
        funcType: IrFuncType,
        body: FunctionBody,
        debugName: String?,
        declaredFuncs: Set<Int>,
        usesDataCountState: BooleanArray,
    ): IrFunction {
        val params = funcType.params.mapIndexed { idx, vt ->
            IrLocal(
                index = idx,
                valType = vt,
                isParam = true,
                debugName = safeName { names?.nameOfLocal(funcIndex, idx) },
            )
        }
        val bodyLocals = body.localTypes().mapIndexed { idx, vt ->
            val slot = params.size + idx
            IrLocal(
                index = slot,
                valType = mapValType(vt, funcIndex, "local[$slot]"),
                isParam = false,
                debugName = safeName { names?.nameOfLocal(funcIndex, slot) },
            )
        }
        val lowerer = FunctionBodyLowerer(
            funcIndex = funcIndex,
            funcType = funcType,
            allLocals = params + bodyLocals,
            declaredFuncs = declaredFuncs,
            usesDataCountState = usesDataCountState,
        )
        lowerer.lower(body.instructions())
        return IrFunction(
            index = funcIndex,
            typeIndex = typeIndex,
            type = funcType,
            params = params,
            locals = bodyLocals,
            blocks = lowerer.completedBlocks,
            instructions = lowerer.emitted,
            debugName = debugName,
        )
    }

    private fun mapMutability(mut: MutabilityType): IrMutability = when (mut) {
        MutabilityType.Const -> IrMutability.CONST
        MutabilityType.Var -> IrMutability.VAR
    }

    private fun mapElementType(valType: ValType, contextDesc: String): IrValType =
        if (valType == ValType.FuncRef || valType.typeIdx() == FUNCREF_TYPE_IDX) {
            // Chicory represents legacy elemkind=0x00 as non-null Ref[-16].
            IrValType.FUNCREF
        } else {
            mapValType(valType, null, contextDesc)
        }

    private fun mapValType(valType: ValType, funcIndex: Int?, contextDesc: String): IrValType =
        when {
            valType == ValType.I32 -> IrValType.I32
            valType == ValType.I64 -> IrValType.I64
            valType == ValType.F32 -> IrValType.F32
            valType == ValType.F64 -> IrValType.F64
            valType == ValType.FuncRef ||
                (valType.opcode() == ValType.ID.RefNull && valType.typeIdx() == FUNCREF_TYPE_IDX) ->
                IrValType.FUNCREF
            valType == ValType.ExternRef ||
                (valType.opcode() == ValType.ID.RefNull && valType.typeIdx() == EXTERNREF_TYPE_IDX) ->
                IrValType.EXTERNREF
            valType == ValType.V128 -> throw UnsupportedWasmFeatureException(
                feature = "simd128",
                moduleIndex = moduleIndex,
                functionIndex = funcIndex,
                detail = "v128 type used in $contextDesc",
            )
            else -> {
                val feature = if (valType.typeIdx() == EXNREF_TYPE_IDX) "exception-handling" else "wasm-gc"
                throw UnsupportedWasmFeatureException(
                    feature = feature,
                    moduleIndex = moduleIndex,
                    functionIndex = funcIndex,
                    detail = "Unsupported value type '$valType' in $contextDesc",
                )
            }
        }

    private fun mapValTypeId(id: Long, funcIndex: Int?, insnName: String): IrValType {
        val opcode = id.toInt()
        val typeIdx = (id shr 32).toInt()
        return when {
            id == ValType.I32.id() || opcode == ValType.ID.I32 -> IrValType.I32
            id == ValType.I64.id() || opcode == ValType.ID.I64 -> IrValType.I64
            id == ValType.F32.id() || opcode == ValType.ID.F32 -> IrValType.F32
            id == ValType.F64.id() || opcode == ValType.ID.F64 -> IrValType.F64
            id == ValType.FuncRef.id() ||
                id == ValType.ID.FuncRef.toLong() ||
                (opcode == ValType.ID.RefNull && typeIdx == FUNCREF_TYPE_IDX) ->
                IrValType.FUNCREF
            id == ValType.ExternRef.id() ||
                id == ValType.ID.ExternRef.toLong() ||
                (opcode == ValType.ID.RefNull && typeIdx == EXTERNREF_TYPE_IDX) ->
                IrValType.EXTERNREF
            id == ValType.V128.id() || opcode == ValType.ID.V128 -> throw UnsupportedWasmFeatureException(
                feature = "simd128",
                moduleIndex = moduleIndex,
                functionIndex = funcIndex,
                instructionName = insnName,
                detail = "v128 value type is not supported",
            )
            id == ValType.ExnRef.id() ||
                id == ValType.ID.ExnRef.toLong() ||
                (opcode == ValType.ID.RefNull && typeIdx == EXNREF_TYPE_IDX) ||
                (opcode == ValType.ID.Ref && typeIdx == EXNREF_TYPE_IDX) ->
                throw UnsupportedWasmFeatureException(
                    feature = "exception-handling",
                    moduleIndex = moduleIndex,
                    functionIndex = funcIndex,
                    instructionName = insnName,
                    detail = "exnref value type is not supported",
                )
            else -> throw UnsupportedWasmFeatureException(
                feature = "wasm-gc",
                moduleIndex = moduleIndex,
                functionIndex = funcIndex,
                instructionName = insnName,
                detail = "Unsupported reference/heap type id $id",
            )
        }
    }

    private fun mapRefNullOperand(typeIdx: Long, funcIndex: Int?, insnName: String): IrValType =
        when (typeIdx) {
            FUNCREF_TYPE_IDX.toLong() -> IrValType.FUNCREF
            EXTERNREF_TYPE_IDX.toLong() -> IrValType.EXTERNREF
            EXNREF_TYPE_IDX.toLong() -> throw UnsupportedWasmFeatureException(
                feature = "exception-handling",
                moduleIndex = moduleIndex,
                functionIndex = funcIndex,
                instructionName = insnName,
                detail = "ref.null exnref is not supported",
            )
            else -> throw UnsupportedWasmFeatureException(
                feature = "wasm-gc",
                moduleIndex = moduleIndex,
                functionIndex = funcIndex,
                instructionName = insnName,
                detail = "Unsupported ref.null heap type $typeIdx",
            )
        }

    private fun requireFuncType(typeIndex: Int, funcIndex: Int?): IrFuncType =
        irTypes.getOrNull(typeIndex)
            ?: throw IllegalArgumentException(
                "Type index $typeIndex out of bounds in core module $moduleIndex${funcIndex?.let { ", function $it" } ?: ""}",
            )

    private fun requireFunctionType(targetFuncIndex: Int, callerFuncIndex: Int?): IrFuncType =
        allFuncTypes.getOrNull(targetFuncIndex)
            ?: throw IllegalArgumentException(
                "Function index $targetFuncIndex out of bounds in core module $moduleIndex${callerFuncIndex?.let { ", function $it" } ?: ""}",
            )

    private fun requireTableType(tableIndex: Int, funcIndex: Int): IrTableType =
        allTableTypes.getOrNull(tableIndex)
            ?: throw IllegalArgumentException(
                "Table index $tableIndex out of bounds in core module $moduleIndex, function $funcIndex",
            )

    private fun requireMemoryType(memoryIndex: Int, funcIndex: Int): IrMemoryType =
        allMemoryTypes.getOrNull(memoryIndex)
            ?: throw IllegalArgumentException(
                "Memory index $memoryIndex out of bounds in core module $moduleIndex, function $funcIndex",
            )

    private fun requireGlobalType(globalIndex: Int, funcIndex: Int): IrGlobalType =
        allGlobalTypes.getOrNull(globalIndex)
            ?: throw IllegalArgumentException(
                "Global index $globalIndex out of bounds in core module $moduleIndex, function $funcIndex",
            )

    private fun safeNameOfFunction(funcIndex: Int): String? =
        safeName { names?.nameOfFunction(funcIndex) }

    private inline fun safeName(block: () -> String?): String? =
        try {
            block()?.takeIf { it.isNotEmpty() }
        } catch (_: RuntimeException) {
            null
        }

    private inner class FunctionBodyLowerer(
        private val funcIndex: Int,
        private val funcType: IrFuncType,
        private val allLocals: List<IrLocal>,
        private val declaredFuncs: Set<Int>,
        private val usesDataCountState: BooleanArray,
    ) {
        private val valueStack = mutableListOf<IrStackType>()
        private val controlStack = ArrayDeque<ControlFrame>()
        private var nextBlockId = 1

        val completedBlocks = mutableListOf<IrBlockInfo>()
        val emitted = mutableListOf<IrTypedInsn>()

        fun lower(instructions: List<AnnotatedInstruction>) {
            controlStack.addLast(
                ControlFrame(
                    blockId = 0,
                    kind = IrBlockKind.FUNCTION,
                    blockType = IrBlockType.Empty,
                    params = emptyList(),
                    results = funcType.results,
                    startInsnIndex = -1,
                    elseInsnIndex = null,
                    baseHeight = 0,
                    unreachable = false,
                ),
            )

            for (insn in instructions) {
                if (controlStack.isEmpty()) {
                    throw IllegalArgumentException(
                        "Unexpected instruction ${insn.opcode().name} after function end in core module $moduleIndex, function $funcIndex",
                    )
                }
                lowerOne(insn)
            }

            if (controlStack.isNotEmpty()) {
                throw IllegalArgumentException(
                    "Unclosed control block(s) at end of function $funcIndex in core module $moduleIndex",
                )
            }
            completedBlocks.sortBy { it.id }
        }

        private fun lowerOne(insn: AnnotatedInstruction) {
            val frameBefore = controlStack.last()
            val stackBefore = valueStack.toList()
            val polyBefore = frameBefore.unreachable
            val baseBefore = frameBefore.baseHeight
            val opcode = insn.opcode()

            val op = executeOp(insn, opcode)

            val activeFrameAfter = controlStack.lastOrNull()
            val polyAfter = activeFrameAfter?.unreachable ?: false
            val baseAfter = activeFrameAfter?.baseHeight ?: 0
            // For block-opening ops, the emitted instruction sits at the boundary of the new frame.
            val reportedBase = if (op is WasmOp.End) baseBefore else baseAfter

            emitted += IrTypedInsn(
                wasmOffset = insn.address(),
                op = op,
                stackBefore = stackBefore,
                stackAfter = valueStack.toList(),
                polymorphicBefore = polyBefore,
                polymorphicAfter = polyAfter,
                frameBaseHeight = reportedBase,
            )
        }

        private fun executeOp(insn: AnnotatedInstruction, opcode: OpCode): WasmOp {
            UNARY_OP_MAP[opcode]?.let { unary ->
                popExpected(unary.inputType, unary.mnemonic)
                pushVal(unary.resultType)
                return WasmOp.Unary(unary)
            }
            BINARY_OP_MAP[opcode]?.let { binary ->
                popExpected(binary.operandType, binary.mnemonic)
                popExpected(binary.operandType, binary.mnemonic)
                pushVal(binary.resultType)
                return WasmOp.Binary(binary)
            }
            LOAD_KIND_MAP[opcode]?.let { loadKind ->
                val memIdx = if (insn.operandCount() >= 3) insn.operand(2).toInt() else 0
                requireMemoryType(memIdx, funcIndex)
                val align = insn.operand(0).toInt()
                val offset = insn.operand(1) and 0xFFFFFFFFL
                popExpected(IrValType.I32, loadKind.mnemonic)
                pushVal(loadKind.resultType)
                return WasmOp.Load(loadKind, memIdx, align, offset)
            }
            STORE_KIND_MAP[opcode]?.let { storeKind ->
                val memIdx = if (insn.operandCount() >= 3) insn.operand(2).toInt() else 0
                requireMemoryType(memIdx, funcIndex)
                val align = insn.operand(0).toInt()
                val offset = insn.operand(1) and 0xFFFFFFFFL
                popExpected(storeKind.valueType, storeKind.mnemonic)
                popExpected(IrValType.I32, storeKind.mnemonic)
                return WasmOp.Store(storeKind, memIdx, align, offset)
            }

            return when (opcode) {
                OpCode.UNREACHABLE -> {
                    setUnreachable()
                    WasmOp.Unreachable
                }
                OpCode.NOP -> WasmOp.Nop
                OpCode.BLOCK -> openBlock(insn, IrBlockKind.BLOCK)
                OpCode.LOOP -> openBlock(insn, IrBlockKind.LOOP)
                OpCode.IF -> openBlock(insn, IrBlockKind.IF)
                OpCode.ELSE -> handleElse()
                OpCode.END -> handleEnd()
                OpCode.BR -> {
                    val target = resolveBranchTarget(insn.operand(0).toInt(), "br")
                    popTypes(target.labelTypes, "br")
                    setUnreachable()
                    WasmOp.Br(target)
                }
                OpCode.BR_IF -> {
                    val target = resolveBranchTarget(insn.operand(0).toInt(), "br_if")
                    popExpected(IrValType.I32, "br_if")
                    popTypes(target.labelTypes, "br_if")
                    pushVals(target.labelTypes)
                    WasmOp.BrIf(target)
                }
                OpCode.BR_TABLE -> handleBrTable(insn)
                OpCode.RETURN -> {
                    popTypes(funcType.results, "return")
                    setUnreachable()
                    WasmOp.Return(funcType.results)
                }
                OpCode.CALL -> {
                    val targetIdx = insn.operand(0).toInt()
                    val calleeType = requireFunctionType(targetIdx, funcIndex)
                    popTypes(calleeType.params, "call")
                    pushVals(calleeType.results)
                    WasmOp.Call(targetIdx, calleeType)
                }
                OpCode.CALL_INDIRECT -> {
                    val callTypeIdx = insn.operand(0).toInt()
                    val tableIdx = insn.operand(1).toInt()
                    val tableType = requireTableType(tableIdx, funcIndex)
                    if (tableType.elementType != IrValType.FUNCREF) {
                        throw IllegalArgumentException(
                            "call_indirect requires funcref table in core module $moduleIndex, function $funcIndex (table $tableIdx has ${tableType.elementType.mnemonic})",
                        )
                    }
                    val calleeType = requireFuncType(callTypeIdx, funcIndex)
                    popExpected(IrValType.I32, "call_indirect")
                    popTypes(calleeType.params, "call_indirect")
                    pushVals(calleeType.results)
                    WasmOp.CallIndirect(callTypeIdx, tableIdx, calleeType)
                }
                OpCode.DROP -> WasmOp.Drop(popAny())
                OpCode.SELECT -> handleUntypedSelect()
                OpCode.SELECT_T -> handleTypedSelect(insn)
                OpCode.LOCAL_GET -> {
                    val localIdx = insn.operand(0).toInt()
                    val vt = requireLocalType(localIdx)
                    pushVal(vt)
                    WasmOp.LocalGet(localIdx, vt)
                }
                OpCode.LOCAL_SET -> {
                    val localIdx = insn.operand(0).toInt()
                    val vt = requireLocalType(localIdx)
                    popExpected(vt, "local.set")
                    WasmOp.LocalSet(localIdx, vt)
                }
                OpCode.LOCAL_TEE -> {
                    val localIdx = insn.operand(0).toInt()
                    val vt = requireLocalType(localIdx)
                    popExpected(vt, "local.tee")
                    pushVal(vt)
                    WasmOp.LocalTee(localIdx, vt)
                }
                OpCode.GLOBAL_GET -> {
                    val globalIdx = insn.operand(0).toInt()
                    val vt = requireGlobalType(globalIdx, funcIndex).valueType
                    pushVal(vt)
                    WasmOp.GlobalGet(globalIdx, vt)
                }
                OpCode.GLOBAL_SET -> {
                    val globalIdx = insn.operand(0).toInt()
                    val gt = requireGlobalType(globalIdx, funcIndex)
                    if (gt.mutability != IrMutability.VAR) {
                        throw IllegalArgumentException(
                            "Cannot mutate const global $globalIdx in core module $moduleIndex, function $funcIndex",
                        )
                    }
                    popExpected(gt.valueType, "global.set")
                    WasmOp.GlobalSet(globalIdx, gt.valueType)
                }
                OpCode.REF_NULL -> {
                    val vt = mapRefNullOperand(insn.operand(0), funcIndex, "ref.null")
                    pushVal(vt)
                    WasmOp.RefNull(vt)
                }
                OpCode.REF_IS_NULL -> {
                    val popped = popAny()
                    if (popped is IrStackType.Concrete &&
                        popped.valType != IrValType.FUNCREF &&
                        popped.valType != IrValType.EXTERNREF
                    ) {
                        throw IllegalArgumentException(
                            "ref.is_null requires reference type in core module $moduleIndex, function $funcIndex (found ${popped.valType.mnemonic})",
                        )
                    }
                    pushVal(IrValType.I32)
                    WasmOp.RefIsNull(popped)
                }
                OpCode.REF_FUNC -> {
                    val targetFuncIdx = insn.operand(0).toInt()
                    requireFunctionType(targetFuncIdx, funcIndex)
                    if (targetFuncIdx !in declaredFuncs) {
                        throw IllegalArgumentException(
                            "Undeclared function reference $targetFuncIdx in core module $moduleIndex, function $funcIndex",
                        )
                    }
                    pushVal(IrValType.FUNCREF)
                    WasmOp.RefFunc(targetFuncIdx)
                }
                OpCode.TABLE_GET -> {
                    val tableIdx = insn.operand(0).toInt()
                    val vt = requireTableType(tableIdx, funcIndex).elementType
                    popExpected(IrValType.I32, "table.get")
                    pushVal(vt)
                    WasmOp.TableGet(tableIdx, vt)
                }
                OpCode.TABLE_SET -> {
                    val tableIdx = insn.operand(0).toInt()
                    val vt = requireTableType(tableIdx, funcIndex).elementType
                    popExpected(vt, "table.set")
                    popExpected(IrValType.I32, "table.set")
                    WasmOp.TableSet(tableIdx, vt)
                }
                OpCode.TABLE_INIT -> {
                    val elemIdx = insn.operand(0).toInt()
                    val tableIdx = insn.operand(1).toInt()
                    val elem = irElements.getOrNull(elemIdx)
                        ?: throw IllegalArgumentException(
                            "Element segment index $elemIdx out of bounds in core module $moduleIndex, function $funcIndex",
                        )
                    val tableType = requireTableType(tableIdx, funcIndex)
                    if (elem.elementType != tableType.elementType) {
                        throw IllegalArgumentException(
                            "table.init type mismatch in core module $moduleIndex, function $funcIndex: elem $elemIdx (${elem.elementType.mnemonic}) vs table $tableIdx (${tableType.elementType.mnemonic})",
                        )
                    }
                    popExpected(IrValType.I32, "table.init")
                    popExpected(IrValType.I32, "table.init")
                    popExpected(IrValType.I32, "table.init")
                    WasmOp.TableInit(elemIdx, tableIdx, tableType.elementType)
                }
                OpCode.ELEM_DROP -> {
                    val elemIdx = insn.operand(0).toInt()
                    if (elemIdx !in irElements.indices) {
                        throw IllegalArgumentException(
                            "Element segment index $elemIdx out of bounds in core module $moduleIndex, function $funcIndex",
                        )
                    }
                    WasmOp.ElemDrop(elemIdx)
                }
                OpCode.TABLE_COPY -> {
                    val dstTable = insn.operand(0).toInt()
                    val srcTable = insn.operand(1).toInt()
                    val dstType = requireTableType(dstTable, funcIndex)
                    val srcType = requireTableType(srcTable, funcIndex)
                    if (dstType.elementType != srcType.elementType) {
                        throw IllegalArgumentException(
                            "table.copy type mismatch in core module $moduleIndex, function $funcIndex: table $dstTable (${dstType.elementType.mnemonic}) vs table $srcTable (${srcType.elementType.mnemonic})",
                        )
                    }
                    popExpected(IrValType.I32, "table.copy")
                    popExpected(IrValType.I32, "table.copy")
                    popExpected(IrValType.I32, "table.copy")
                    WasmOp.TableCopy(dstTable, srcTable)
                }
                OpCode.TABLE_GROW -> {
                    val tableIdx = insn.operand(0).toInt()
                    val elemType = requireTableType(tableIdx, funcIndex).elementType
                    popExpected(IrValType.I32, "table.grow")
                    popExpected(elemType, "table.grow")
                    pushVal(IrValType.I32)
                    WasmOp.TableGrow(tableIdx, elemType)
                }
                OpCode.TABLE_SIZE -> {
                    val tableIdx = insn.operand(0).toInt()
                    requireTableType(tableIdx, funcIndex)
                    pushVal(IrValType.I32)
                    WasmOp.TableSize(tableIdx)
                }
                OpCode.TABLE_FILL -> {
                    val tableIdx = insn.operand(0).toInt()
                    val elemType = requireTableType(tableIdx, funcIndex).elementType
                    popExpected(IrValType.I32, "table.fill")
                    popExpected(elemType, "table.fill")
                    popExpected(IrValType.I32, "table.fill")
                    WasmOp.TableFill(tableIdx, elemType)
                }
                OpCode.MEMORY_SIZE -> {
                    val memIdx = insn.operand(0).toInt()
                    requireMemoryType(memIdx, funcIndex)
                    pushVal(IrValType.I32)
                    WasmOp.MemorySize(memIdx)
                }
                OpCode.MEMORY_GROW -> {
                    val memIdx = insn.operand(0).toInt()
                    requireMemoryType(memIdx, funcIndex)
                    popExpected(IrValType.I32, "memory.grow")
                    pushVal(IrValType.I32)
                    WasmOp.MemoryGrow(memIdx)
                }
                OpCode.MEMORY_INIT -> {
                    val dataIdx = insn.operand(0).toInt()
                    val memIdx = insn.operand(1).toInt()
                    usesDataCountState[0] = true
                    if (dataIdx !in irDataSegments.indices) {
                        throw IllegalArgumentException(
                            "Data segment index $dataIdx out of bounds in core module $moduleIndex, function $funcIndex",
                        )
                    }
                    requireMemoryType(memIdx, funcIndex)
                    popExpected(IrValType.I32, "memory.init")
                    popExpected(IrValType.I32, "memory.init")
                    popExpected(IrValType.I32, "memory.init")
                    WasmOp.MemoryInit(dataIdx, memIdx)
                }
                OpCode.DATA_DROP -> {
                    val dataIdx = insn.operand(0).toInt()
                    usesDataCountState[0] = true
                    if (dataIdx !in irDataSegments.indices) {
                        throw IllegalArgumentException(
                            "Data segment index $dataIdx out of bounds in core module $moduleIndex, function $funcIndex",
                        )
                    }
                    WasmOp.DataDrop(dataIdx)
                }
                OpCode.MEMORY_COPY -> {
                    val dstMem = insn.operand(0).toInt()
                    val srcMem = insn.operand(1).toInt()
                    requireMemoryType(dstMem, funcIndex)
                    requireMemoryType(srcMem, funcIndex)
                    popExpected(IrValType.I32, "memory.copy")
                    popExpected(IrValType.I32, "memory.copy")
                    popExpected(IrValType.I32, "memory.copy")
                    WasmOp.MemoryCopy(dstMem, srcMem)
                }
                OpCode.MEMORY_FILL -> {
                    val memIdx = insn.operand(0).toInt()
                    requireMemoryType(memIdx, funcIndex)
                    popExpected(IrValType.I32, "memory.fill")
                    popExpected(IrValType.I32, "memory.fill")
                    popExpected(IrValType.I32, "memory.fill")
                    WasmOp.MemoryFill(memIdx)
                }
                OpCode.I32_CONST -> {
                    pushVal(IrValType.I32)
                    WasmOp.I32Const(insn.operand(0).toInt())
                }
                OpCode.I64_CONST -> {
                    pushVal(IrValType.I64)
                    WasmOp.I64Const(insn.operand(0))
                }
                OpCode.F32_CONST -> {
                    pushVal(IrValType.F32)
                    WasmOp.F32Const(insn.operand(0).toInt())
                }
                OpCode.F64_CONST -> {
                    pushVal(IrValType.F64)
                    WasmOp.F64Const(insn.operand(0))
                }
                else -> {
                    val feature = classifyUnsupportedOpcode(opcode) ?: "unsupported-wasm-feature"
                    throw UnsupportedWasmFeatureException(
                        feature = feature,
                        moduleIndex = moduleIndex,
                        functionIndex = funcIndex,
                        instructionName = opcode.name,
                        detail = "Instruction is outside the supported Core Wasm subset",
                    )
                }
            }
        }

        private fun openBlock(insn: AnnotatedInstruction, kind: IrBlockKind): WasmOp {
            val (blockType, params, results) = decodeBlockType(insn, kind.mnemonic)
            if (kind == IrBlockKind.IF) {
                popExpected(IrValType.I32, "if")
            }
            popTypes(params, kind.mnemonic)
            val blockId = nextBlockId++
            val baseHeight = valueStack.size
            controlStack.addLast(
                ControlFrame(
                    blockId = blockId,
                    kind = kind,
                    blockType = blockType,
                    params = params,
                    results = results,
                    startInsnIndex = emitted.size,
                    baseHeight = baseHeight,
                    unreachable = false,
                ),
            )
            pushVals(params)
            return when (kind) {
                IrBlockKind.BLOCK -> WasmOp.Block(blockId, blockType, params, results)
                IrBlockKind.LOOP -> WasmOp.Loop(blockId, blockType, params, results)
                IrBlockKind.IF -> WasmOp.If(blockId, blockType, params, results)
                IrBlockKind.FUNCTION -> error("Unexpected function block")
            }
        }

        private fun handleElse(): WasmOp.Else {
            val frame = controlStack.last()
            if (frame.kind != IrBlockKind.IF || frame.elseInsnIndex != null) {
                throw IllegalArgumentException(
                    "Unexpected 'else' in core module $moduleIndex, function $funcIndex",
                )
            }
            popTypes(frame.results, "else")
            if (valueStack.size != frame.baseHeight) {
                throw IllegalArgumentException(
                    "Stack height mismatch before 'else' in core module $moduleIndex, function $funcIndex",
                )
            }
            frame.unreachable = false
            frame.elseInsnIndex = emitted.size
            pushVals(frame.params)
            return WasmOp.Else(frame.blockId)
        }

        private fun handleEnd(): WasmOp.End {
            val frame = controlStack.last()
            if (frame.kind == IrBlockKind.IF && frame.elseInsnIndex == null && frame.params != frame.results) {
                throw IllegalArgumentException(
                    "'if' without 'else' must preserve types in core module $moduleIndex, function $funcIndex",
                )
            }
            popTypes(frame.results, "end")
            if (valueStack.size != frame.baseHeight) {
                throw IllegalArgumentException(
                    "Stack height mismatch at 'end' in core module $moduleIndex, function $funcIndex",
                )
            }
            controlStack.removeLast()
            pushVals(frame.results)
            completedBlocks += IrBlockInfo(
                id = frame.blockId,
                kind = frame.kind,
                blockType = frame.blockType,
                params = frame.params,
                results = frame.results,
                branchLabelTypes = frame.labelTypes,
                startInsnIndex = frame.startInsnIndex,
                elseInsnIndex = frame.elseInsnIndex,
                endInsnIndex = emitted.size,
            )
            return WasmOp.End(frame.blockId, frame.kind)
        }

        private fun handleBrTable(insn: AnnotatedInstruction): WasmOp.BrTable {
            val ops = insn.operands()
            if (ops.isEmpty()) {
                throw IllegalArgumentException(
                    "br_table missing default target in core module $moduleIndex, function $funcIndex",
                )
            }
            val defaultTarget = resolveBranchTarget(ops.last().toInt(), "br_table")
            val targets = (0 until ops.size - 1).map { i ->
                resolveBranchTarget(ops[i].toInt(), "br_table")
            }
            popExpected(IrValType.I32, "br_table")
            val expectedArity = defaultTarget.labelTypes.size
            for (target in targets) {
                if (target.labelTypes.size != expectedArity) {
                    throw IllegalArgumentException(
                        "br_table target arity mismatch in core module $moduleIndex, function $funcIndex",
                    )
                }
                val popped = popTypes(target.labelTypes, "br_table")
                for (t in popped) pushStackType(t)
            }
            popTypes(defaultTarget.labelTypes, "br_table")
            setUnreachable()
            return WasmOp.BrTable(targets, defaultTarget)
        }

        private fun handleUntypedSelect(): WasmOp.Select {
            popExpected(IrValType.I32, "select")
            val v2 = popAny()
            val v1 = popAny()
            val resolved = when {
                v1 is IrStackType.Unknown -> v2
                v2 is IrStackType.Unknown -> v1
                v1 == v2 -> v1
                else -> throw IllegalArgumentException(
                    "select operand mismatch in core module $moduleIndex, function $funcIndex: $v1 vs $v2",
                )
            }
            if (resolved is IrStackType.Concrete &&
                (resolved.valType == IrValType.FUNCREF || resolved.valType == IrValType.EXTERNREF)
            ) {
                throw IllegalArgumentException(
                    "Untyped select cannot operate on reference type ${resolved.valType.mnemonic} in core module $moduleIndex, function $funcIndex",
                )
            }
            pushStackType(resolved)
            return WasmOp.Select(emptyList(), resolved)
        }

        private fun handleTypedSelect(insn: AnnotatedInstruction): WasmOp.Select {
            val types = insn.operands().map { mapValTypeId(it, funcIndex, "select") }
            if (types.size != 1) {
                throw IllegalArgumentException(
                    "Typed select expects arity 1 in core module $moduleIndex, function $funcIndex (got ${types.size})",
                )
            }
            val vt = types[0]
            popExpected(IrValType.I32, "select_t")
            popExpected(vt, "select_t")
            popExpected(vt, "select_t")
            pushVal(vt)
            return WasmOp.Select(types, IrStackType.Concrete(vt))
        }

        private fun decodeBlockType(
            insn: AnnotatedInstruction,
            opName: String,
        ): Triple<IrBlockType, List<IrValType>, List<IrValType>> {
            val addr = insn.address()
            if (addr < 0 || addr + 1 >= wasmBytes.size) {
                throw IllegalArgumentException(
                    "Missing instruction byte offset for '$opName' blocktype in core module $moduleIndex, function $funcIndex",
                )
            }
            val sleb = readSleb33(wasmBytes, addr + 1, funcIndex)
            return when {
                sleb == BLOCKTYPE_VOID -> Triple(IrBlockType.Empty, emptyList(), emptyList())
                sleb < 0 -> {
                    val vt = when (sleb.toInt()) {
                        -1 -> IrValType.I32
                        -2 -> IrValType.I64
                        -3 -> IrValType.F32
                        -4 -> IrValType.F64
                        -5 -> throw UnsupportedWasmFeatureException(
                            feature = "simd128",
                            moduleIndex = moduleIndex,
                            functionIndex = funcIndex,
                            instructionName = opName,
                            detail = "v128 block result type is not supported",
                        )
                        -16 -> IrValType.FUNCREF
                        -17 -> IrValType.EXTERNREF
                        -23 -> throw UnsupportedWasmFeatureException(
                            feature = "exception-handling",
                            moduleIndex = moduleIndex,
                            functionIndex = funcIndex,
                            instructionName = opName,
                            detail = "exnref block result type is not supported",
                        )
                        else -> throw UnsupportedWasmFeatureException(
                            feature = "wasm-gc",
                            moduleIndex = moduleIndex,
                            functionIndex = funcIndex,
                            instructionName = opName,
                            detail = "Unsupported negative blocktype SLEB128=$sleb",
                        )
                    }
                    Triple(IrBlockType.Value(vt), emptyList(), listOf(vt))
                }
                else -> {
                    val typeIndex = sleb.toInt()
                    val ft = requireFuncType(typeIndex, funcIndex)
                    Triple(IrBlockType.TypeRef(typeIndex), ft.params, ft.results)
                }
            }
        }

        private fun resolveBranchTarget(depth: Int, opName: String): IrBranchTarget {
            if (depth !in 0 until controlStack.size) {
                throw IllegalArgumentException(
                    "Branch depth $depth out of range in core module $moduleIndex, function $funcIndex at '$opName'",
                )
            }
            val targetFrame = controlStack[controlStack.size - 1 - depth]
            return IrBranchTarget(
                depth = depth,
                targetBlockId = targetFrame.blockId,
                targetKind = targetFrame.kind,
                labelTypes = targetFrame.labelTypes,
                unwindHeight = targetFrame.baseHeight,
            )
        }

        private fun requireLocalType(localIndex: Int): IrValType =
            allLocals.getOrNull(localIndex)?.valType
                ?: throw IllegalArgumentException(
                    "Local index $localIndex out of bounds in core module $moduleIndex, function $funcIndex",
                )

        private fun popAny(): IrStackType {
            val frame = controlStack.last()
            if (valueStack.size == frame.baseHeight) {
                if (frame.unreachable) return IrStackType.Unknown
                throw IllegalArgumentException(
                    "Operand stack underflow in core module $moduleIndex, function $funcIndex",
                )
            }
            return valueStack.removeAt(valueStack.lastIndex)
        }

        private fun popExpected(expected: IrValType, opName: String): IrStackType {
            val popped = popAny()
            if (popped is IrStackType.Concrete && popped.valType != expected) {
                throw IllegalArgumentException(
                    "Type mismatch in core module $moduleIndex, function $funcIndex at '$opName': expected ${expected.mnemonic}, found ${popped.valType.mnemonic}",
                )
            }
            return popped
        }

        private fun popTypes(expected: List<IrValType>, opName: String): List<IrStackType> {
            val poppedReversed = ArrayList<IrStackType>(expected.size)
            for (i in expected.lastIndex downTo 0) {
                poppedReversed += popExpected(expected[i], opName)
            }
            return poppedReversed.asReversed()
        }

        private fun pushVal(valType: IrValType) {
            valueStack.add(IrStackType.Concrete(valType))
        }

        private fun pushStackType(stackType: IrStackType) {
            valueStack.add(stackType)
        }

        private fun pushVals(types: List<IrValType>) {
            for (t in types) pushVal(t)
        }

        private fun setUnreachable() {
            val frame = controlStack.last()
            while (valueStack.size > frame.baseHeight) {
                valueStack.removeAt(valueStack.lastIndex)
            }
            frame.unreachable = true
        }
    }

    private fun readSleb33(bytes: ByteArray, startOffset: Int, funcIndex: Int): Long {
        var result = 0L
        var shift = 0
        var pos = startOffset
        while (pos < bytes.size) {
            val byte = bytes[pos++].toInt() and 0xFF
            result = result or ((byte and 0x7F).toLong() shl shift)
            shift += 7
            if ((byte and 0x80) == 0) {
                if (shift < 64 && (byte and 0x40) != 0) {
                    result = result or (-1L shl shift)
                }
                return result
            }
        }
        throw IllegalArgumentException(
            "Unexpected EOF reading blocktype LEB128 in core module $moduleIndex, function $funcIndex",
        )
    }

    private companion object {
        const val BLOCKTYPE_VOID = -64L
        const val FUNCREF_TYPE_IDX = -16
        const val EXTERNREF_TYPE_IDX = -17
        const val EXNREF_TYPE_IDX = -23

        val LOAD_KIND_MAP: Map<OpCode, LoadKind> = mapOf(
            OpCode.I32_LOAD to LoadKind.I32,
            OpCode.I64_LOAD to LoadKind.I64,
            OpCode.F32_LOAD to LoadKind.F32,
            OpCode.F64_LOAD to LoadKind.F64,
            OpCode.I32_LOAD8_S to LoadKind.I32_8S,
            OpCode.I32_LOAD8_U to LoadKind.I32_8U,
            OpCode.I32_LOAD16_S to LoadKind.I32_16S,
            OpCode.I32_LOAD16_U to LoadKind.I32_16U,
            OpCode.I64_LOAD8_S to LoadKind.I64_8S,
            OpCode.I64_LOAD8_U to LoadKind.I64_8U,
            OpCode.I64_LOAD16_S to LoadKind.I64_16S,
            OpCode.I64_LOAD16_U to LoadKind.I64_16U,
            OpCode.I64_LOAD32_S to LoadKind.I64_32S,
            OpCode.I64_LOAD32_U to LoadKind.I64_32U,
        )

        val STORE_KIND_MAP: Map<OpCode, StoreKind> = mapOf(
            OpCode.I32_STORE to StoreKind.I32,
            OpCode.I64_STORE to StoreKind.I64,
            OpCode.F32_STORE to StoreKind.F32,
            OpCode.F64_STORE to StoreKind.F64,
            OpCode.I32_STORE8 to StoreKind.I32_8,
            OpCode.I32_STORE16 to StoreKind.I32_16,
            OpCode.I64_STORE8 to StoreKind.I64_8,
            OpCode.I64_STORE16 to StoreKind.I64_16,
            OpCode.I64_STORE32 to StoreKind.I64_32,
        )

        val UNARY_OP_MAP: Map<OpCode, UnaryOp> = mapOf(
            OpCode.I32_EQZ to UnaryOp.I32_EQZ,
            OpCode.I64_EQZ to UnaryOp.I64_EQZ,
            OpCode.I32_CLZ to UnaryOp.I32_CLZ,
            OpCode.I32_CTZ to UnaryOp.I32_CTZ,
            OpCode.I32_POPCNT to UnaryOp.I32_POPCNT,
            OpCode.I64_CLZ to UnaryOp.I64_CLZ,
            OpCode.I64_CTZ to UnaryOp.I64_CTZ,
            OpCode.I64_POPCNT to UnaryOp.I64_POPCNT,
            OpCode.F32_ABS to UnaryOp.F32_ABS,
            OpCode.F32_NEG to UnaryOp.F32_NEG,
            OpCode.F32_CEIL to UnaryOp.F32_CEIL,
            OpCode.F32_FLOOR to UnaryOp.F32_FLOOR,
            OpCode.F32_TRUNC to UnaryOp.F32_TRUNC,
            OpCode.F32_NEAREST to UnaryOp.F32_NEAREST,
            OpCode.F32_SQRT to UnaryOp.F32_SQRT,
            OpCode.F64_ABS to UnaryOp.F64_ABS,
            OpCode.F64_NEG to UnaryOp.F64_NEG,
            OpCode.F64_CEIL to UnaryOp.F64_CEIL,
            OpCode.F64_FLOOR to UnaryOp.F64_FLOOR,
            OpCode.F64_TRUNC to UnaryOp.F64_TRUNC,
            OpCode.F64_NEAREST to UnaryOp.F64_NEAREST,
            OpCode.F64_SQRT to UnaryOp.F64_SQRT,
            OpCode.I32_WRAP_I64 to UnaryOp.I32_WRAP_I64,
            OpCode.I32_TRUNC_F32_S to UnaryOp.I32_TRUNC_F32_S,
            OpCode.I32_TRUNC_F32_U to UnaryOp.I32_TRUNC_F32_U,
            OpCode.I32_TRUNC_F64_S to UnaryOp.I32_TRUNC_F64_S,
            OpCode.I32_TRUNC_F64_U to UnaryOp.I32_TRUNC_F64_U,
            OpCode.I64_EXTEND_I32_S to UnaryOp.I64_EXTEND_I32_S,
            OpCode.I64_EXTEND_I32_U to UnaryOp.I64_EXTEND_I32_U,
            OpCode.I64_TRUNC_F32_S to UnaryOp.I64_TRUNC_F32_S,
            OpCode.I64_TRUNC_F32_U to UnaryOp.I64_TRUNC_F32_U,
            OpCode.I64_TRUNC_F64_S to UnaryOp.I64_TRUNC_F64_S,
            OpCode.I64_TRUNC_F64_U to UnaryOp.I64_TRUNC_F64_U,
            OpCode.F32_CONVERT_I32_S to UnaryOp.F32_CONVERT_I32_S,
            OpCode.F32_CONVERT_I32_U to UnaryOp.F32_CONVERT_I32_U,
            OpCode.F32_CONVERT_I64_S to UnaryOp.F32_CONVERT_I64_S,
            OpCode.F32_CONVERT_I64_U to UnaryOp.F32_CONVERT_I64_U,
            OpCode.F32_DEMOTE_F64 to UnaryOp.F32_DEMOTE_F64,
            OpCode.F64_CONVERT_I32_S to UnaryOp.F64_CONVERT_I32_S,
            OpCode.F64_CONVERT_I32_U to UnaryOp.F64_CONVERT_I32_U,
            OpCode.F64_CONVERT_I64_S to UnaryOp.F64_CONVERT_I64_S,
            OpCode.F64_CONVERT_I64_U to UnaryOp.F64_CONVERT_I64_U,
            OpCode.F64_PROMOTE_F32 to UnaryOp.F64_PROMOTE_F32,
            OpCode.I32_REINTERPRET_F32 to UnaryOp.I32_REINTERPRET_F32,
            OpCode.I64_REINTERPRET_F64 to UnaryOp.I64_REINTERPRET_F64,
            OpCode.F32_REINTERPRET_I32 to UnaryOp.F32_REINTERPRET_I32,
            OpCode.F64_REINTERPRET_I64 to UnaryOp.F64_REINTERPRET_I64,
            OpCode.I32_EXTEND_8_S to UnaryOp.I32_EXTEND_8_S,
            OpCode.I32_EXTEND_16_S to UnaryOp.I32_EXTEND_16_S,
            OpCode.I64_EXTEND_8_S to UnaryOp.I64_EXTEND_8_S,
            OpCode.I64_EXTEND_16_S to UnaryOp.I64_EXTEND_16_S,
            OpCode.I64_EXTEND_32_S to UnaryOp.I64_EXTEND_32_S,
            OpCode.I32_TRUNC_SAT_F32_S to UnaryOp.I32_TRUNC_SAT_F32_S,
            OpCode.I32_TRUNC_SAT_F32_U to UnaryOp.I32_TRUNC_SAT_F32_U,
            OpCode.I32_TRUNC_SAT_F64_S to UnaryOp.I32_TRUNC_SAT_F64_S,
            OpCode.I32_TRUNC_SAT_F64_U to UnaryOp.I32_TRUNC_SAT_F64_U,
            OpCode.I64_TRUNC_SAT_F32_S to UnaryOp.I64_TRUNC_SAT_F32_S,
            OpCode.I64_TRUNC_SAT_F32_U to UnaryOp.I64_TRUNC_SAT_F32_U,
            OpCode.I64_TRUNC_SAT_F64_S to UnaryOp.I64_TRUNC_SAT_F64_S,
            OpCode.I64_TRUNC_SAT_F64_U to UnaryOp.I64_TRUNC_SAT_F64_U,
        )

        val BINARY_OP_MAP: Map<OpCode, BinaryOp> = mapOf(
            OpCode.I32_EQ to BinaryOp.I32_EQ,
            OpCode.I32_NE to BinaryOp.I32_NE,
            OpCode.I32_LT_S to BinaryOp.I32_LT_S,
            OpCode.I32_LT_U to BinaryOp.I32_LT_U,
            OpCode.I32_GT_S to BinaryOp.I32_GT_S,
            OpCode.I32_GT_U to BinaryOp.I32_GT_U,
            OpCode.I32_LE_S to BinaryOp.I32_LE_S,
            OpCode.I32_LE_U to BinaryOp.I32_LE_U,
            OpCode.I32_GE_S to BinaryOp.I32_GE_S,
            OpCode.I32_GE_U to BinaryOp.I32_GE_U,
            OpCode.I64_EQ to BinaryOp.I64_EQ,
            OpCode.I64_NE to BinaryOp.I64_NE,
            OpCode.I64_LT_S to BinaryOp.I64_LT_S,
            OpCode.I64_LT_U to BinaryOp.I64_LT_U,
            OpCode.I64_GT_S to BinaryOp.I64_GT_S,
            OpCode.I64_GT_U to BinaryOp.I64_GT_U,
            OpCode.I64_LE_S to BinaryOp.I64_LE_S,
            OpCode.I64_LE_U to BinaryOp.I64_LE_U,
            OpCode.I64_GE_S to BinaryOp.I64_GE_S,
            OpCode.I64_GE_U to BinaryOp.I64_GE_U,
            OpCode.F32_EQ to BinaryOp.F32_EQ,
            OpCode.F32_NE to BinaryOp.F32_NE,
            OpCode.F32_LT to BinaryOp.F32_LT,
            OpCode.F32_GT to BinaryOp.F32_GT,
            OpCode.F32_LE to BinaryOp.F32_LE,
            OpCode.F32_GE to BinaryOp.F32_GE,
            OpCode.F64_EQ to BinaryOp.F64_EQ,
            OpCode.F64_NE to BinaryOp.F64_NE,
            OpCode.F64_LT to BinaryOp.F64_LT,
            OpCode.F64_GT to BinaryOp.F64_GT,
            OpCode.F64_LE to BinaryOp.F64_LE,
            OpCode.F64_GE to BinaryOp.F64_GE,
            OpCode.I32_ADD to BinaryOp.I32_ADD,
            OpCode.I32_SUB to BinaryOp.I32_SUB,
            OpCode.I32_MUL to BinaryOp.I32_MUL,
            OpCode.I32_DIV_S to BinaryOp.I32_DIV_S,
            OpCode.I32_DIV_U to BinaryOp.I32_DIV_U,
            OpCode.I32_REM_S to BinaryOp.I32_REM_S,
            OpCode.I32_REM_U to BinaryOp.I32_REM_U,
            OpCode.I32_AND to BinaryOp.I32_AND,
            OpCode.I32_OR to BinaryOp.I32_OR,
            OpCode.I32_XOR to BinaryOp.I32_XOR,
            OpCode.I32_SHL to BinaryOp.I32_SHL,
            OpCode.I32_SHR_S to BinaryOp.I32_SHR_S,
            OpCode.I32_SHR_U to BinaryOp.I32_SHR_U,
            OpCode.I32_ROTL to BinaryOp.I32_ROTL,
            OpCode.I32_ROTR to BinaryOp.I32_ROTR,
            OpCode.I64_ADD to BinaryOp.I64_ADD,
            OpCode.I64_SUB to BinaryOp.I64_SUB,
            OpCode.I64_MUL to BinaryOp.I64_MUL,
            OpCode.I64_DIV_S to BinaryOp.I64_DIV_S,
            OpCode.I64_DIV_U to BinaryOp.I64_DIV_U,
            OpCode.I64_REM_S to BinaryOp.I64_REM_S,
            OpCode.I64_REM_U to BinaryOp.I64_REM_U,
            OpCode.I64_AND to BinaryOp.I64_AND,
            OpCode.I64_OR to BinaryOp.I64_OR,
            OpCode.I64_XOR to BinaryOp.I64_XOR,
            OpCode.I64_SHL to BinaryOp.I64_SHL,
            OpCode.I64_SHR_S to BinaryOp.I64_SHR_S,
            OpCode.I64_SHR_U to BinaryOp.I64_SHR_U,
            OpCode.I64_ROTL to BinaryOp.I64_ROTL,
            OpCode.I64_ROTR to BinaryOp.I64_ROTR,
            OpCode.F32_ADD to BinaryOp.F32_ADD,
            OpCode.F32_SUB to BinaryOp.F32_SUB,
            OpCode.F32_MUL to BinaryOp.F32_MUL,
            OpCode.F32_DIV to BinaryOp.F32_DIV,
            OpCode.F32_MIN to BinaryOp.F32_MIN,
            OpCode.F32_MAX to BinaryOp.F32_MAX,
            OpCode.F32_COPYSIGN to BinaryOp.F32_COPYSIGN,
            OpCode.F64_ADD to BinaryOp.F64_ADD,
            OpCode.F64_SUB to BinaryOp.F64_SUB,
            OpCode.F64_MUL to BinaryOp.F64_MUL,
            OpCode.F64_DIV to BinaryOp.F64_DIV,
            OpCode.F64_MIN to BinaryOp.F64_MIN,
            OpCode.F64_MAX to BinaryOp.F64_MAX,
            OpCode.F64_COPYSIGN to BinaryOp.F64_COPYSIGN,
        )

        fun classifyUnsupportedOpcode(opcode: OpCode): String? {
            val upper = opcode.name.uppercase()
            return when {
                opcode == OpCode.THROW || opcode == OpCode.THROW_REF || opcode == OpCode.TRY_TABLE ->
                    "exception-handling"
                opcode == OpCode.RETURN_CALL ||
                    opcode == OpCode.RETURN_CALL_INDIRECT ||
                    opcode == OpCode.RETURN_CALL_REF ->
                    "tail-call"
                upper.startsWith("V128_") ||
                    upper.startsWith("I8X16_") ||
                    upper.startsWith("I16X8_") ||
                    upper.startsWith("I32X4_") ||
                    upper.startsWith("I64X2_") ||
                    upper.startsWith("F32X4_") ||
                    upper.startsWith("F64X2_") ->
                    "simd128"
                upper.startsWith("MEM_ATOMIC_") ||
                    opcode == OpCode.ATOMIC_FENCE ||
                    "_ATOMIC_" in upper ->
                    "threads"
                opcode == OpCode.CALL_REF ||
                    opcode == OpCode.REF_EQ ||
                    opcode == OpCode.REF_AS_NON_NULL ||
                    opcode == OpCode.BR_ON_NULL ||
                    opcode == OpCode.BR_ON_NON_NULL ||
                    opcode == OpCode.REF_I31 ||
                    opcode == OpCode.I31_GET_S ||
                    opcode == OpCode.I31_GET_U ||
                    upper.startsWith("STRUCT_") ||
                    upper.startsWith("ARRAY_") ||
                    upper.startsWith("REF_TEST") ||
                    upper.startsWith("REF_CAST") ||
                    upper.startsWith("BR_ON_CAST") ||
                    upper == "ANY_CONVERT_EXTERN" ||
                    upper == "EXTERN_CONVERT_ANY" ->
                    "wasm-gc"
                else -> null
            }
        }
    }
}

private object RawWasmInspector {
    private val WASM_HEADER = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
    private const val MAX_WASM32_MEMORY_PAGES = 65536L

    fun inspectMetadata(wasmBytes: ByteArray, moduleIndex: Int): RawModuleMetadata {
        val cursor = ByteCursor(wasmBytes)
        if (!hasValidHeader(wasmBytes)) {
            throw IllegalArgumentException("Malformed core module $moduleIndex: invalid Wasm magic/version")
        }
        cursor.pos = WASM_HEADER.size

        val importTables = mutableListOf<IrLimits>()
        val importMemories = mutableListOf<IrLimits>()
        val definedTables = mutableListOf<IrLimits>()
        val definedMemories = mutableListOf<IrLimits>()
        var importedFuncCount = 0

        while (cursor.hasRemaining()) {
            val sectionId = cursor.readByte()
            val sectionSize = cursor.readUleb32()
            val sectionEnd = cursor.pos + sectionSize
            if (sectionEnd > wasmBytes.size) {
                throw IllegalArgumentException("Malformed core module $moduleIndex: truncated section $sectionId")
            }
            when (sectionId) {
                2 -> {
                    val count = cursor.readUleb32()
                    repeat(count) {
                        cursor.skipString()
                        cursor.skipString()
                        when (val kind = cursor.readByte()) {
                            0x00 -> {
                                cursor.readUleb32()
                                importedFuncCount++
                            }
                            0x01 -> {
                                cursor.skipValType()
                                importTables += readLimits(cursor, moduleIndex, isMemory = false, "import table")
                            }
                            0x02 -> {
                                importMemories += readLimits(cursor, moduleIndex, isMemory = true, "import memory")
                            }
                            0x03 -> {
                                cursor.skipValType()
                                cursor.readByte()
                            }
                            0x04 -> throw UnsupportedWasmFeatureException(
                                feature = "exception-handling",
                                moduleIndex = moduleIndex,
                                detail = "Imported exception tag is not supported",
                            )
                            else -> throw IllegalArgumentException("Malformed core module $moduleIndex: invalid import kind 0x${kind.toString(16)}")
                        }
                    }
                }
                4 -> {
                    val count = cursor.readUleb32()
                    repeat(count) { idx ->
                        if (cursor.peekByte() == 0x40) {
                            throw UnsupportedWasmFeatureException(
                                feature = "wasm-gc",
                                moduleIndex = moduleIndex,
                                detail = "Table $idx typed initialization syntax is not supported",
                            )
                        }
                        cursor.skipValType()
                        definedTables += readLimits(cursor, moduleIndex, isMemory = false, "table[$idx]")
                    }
                }
                5 -> {
                    val count = cursor.readUleb32()
                    repeat(count) { idx ->
                        definedMemories += readLimits(cursor, moduleIndex, isMemory = true, "memory[$idx]")
                    }
                }
                13 -> {
                    val tagCount = cursor.readUleb32()
                    if (tagCount > 0) {
                        throw UnsupportedWasmFeatureException(
                            feature = "exception-handling",
                            moduleIndex = moduleIndex,
                            detail = "Module defines $tagCount exception tag(s)",
                        )
                    }
                }
            }
            cursor.pos = sectionEnd
        }

        return RawModuleMetadata(
            importTables = importTables,
            importMemories = importMemories,
            definedTables = definedTables,
            definedMemories = definedMemories,
            importedFuncCount = importedFuncCount,
        )
    }

    fun diagnoseFailure(wasmBytes: ByteArray, moduleIndex: Int, cause: ChicoryException): RuntimeException {
        if (!hasValidHeader(wasmBytes)) {
            return IllegalArgumentException("Malformed core module $moduleIndex: ${cause.message}", cause)
        }
        val sections = scanSectionSlices(wasmBytes)
        var importedFuncCount = 0

        for ((id, start, end) in sections) {
            try {
                when (id) {
                    1 -> inspectTypeSectionForGc(wasmBytes, start, end, moduleIndex, cause)
                    2 -> {
                        val c = ByteCursor(wasmBytes, start)
                        val count = c.readUleb32()
                        repeat(count) {
                            c.skipString()
                            c.skipString()
                            when (c.readByte()) {
                                0x00 -> {
                                    c.readUleb32()
                                    importedFuncCount++
                                }
                                0x01 -> {
                                    c.skipValType()
                                    readLimits(c, moduleIndex, isMemory = false, "import table")
                                }
                                0x02 -> readLimits(c, moduleIndex, isMemory = true, "import memory")
                                0x03 -> {
                                    c.skipValType()
                                    c.readByte()
                                }
                                0x04 -> return UnsupportedWasmFeatureException(
                                    feature = "exception-handling",
                                    moduleIndex = moduleIndex,
                                    detail = "Imported exception tag is not supported",
                                    cause = cause,
                                )
                            }
                        }
                    }
                    4 -> {
                        val c = ByteCursor(wasmBytes, start)
                        val count = c.readUleb32()
                        repeat(count) { idx ->
                            if (c.peekByte() == 0x40) {
                                return UnsupportedWasmFeatureException(
                                    feature = "wasm-gc",
                                    moduleIndex = moduleIndex,
                                    detail = "Table $idx typed initialization syntax is not supported",
                                    cause = cause,
                                )
                            }
                            c.skipValType()
                            readLimits(c, moduleIndex, isMemory = false, "table[$idx]")
                        }
                    }
                    5 -> {
                        val c = ByteCursor(wasmBytes, start)
                        val count = c.readUleb32()
                        repeat(count) { idx ->
                            readLimits(c, moduleIndex, isMemory = true, "memory[$idx]")
                        }
                    }
                    10 -> scanCodeSection(wasmBytes, start, end, moduleIndex, importedFuncCount, cause)
                    13 -> {
                        val c = ByteCursor(wasmBytes, start)
                        val count = c.readUleb32()
                        if (count > 0) {
                            return UnsupportedWasmFeatureException(
                                feature = "exception-handling",
                                moduleIndex = moduleIndex,
                                detail = "Module defines $count exception tag(s)",
                                cause = cause,
                            )
                        }
                    }
                }
            } catch (e: UnsupportedWasmFeatureException) {
                return e
            } catch (e: IllegalArgumentException) {
                return e
            } catch (_: RuntimeException) {
                // Malformed binary inside a section: fall through to Chicory malformed error.
            }
        }
        return IllegalArgumentException("Malformed core module $moduleIndex: ${cause.message}", cause)
    }

    private fun inspectTypeSectionForGc(
        wasmBytes: ByteArray,
        start: Int,
        end: Int,
        moduleIndex: Int,
        cause: ChicoryException,
    ) {
        val c = ByteCursor(wasmBytes, start)
        val count = c.readUleb32()
        if (count > 0 && c.pos < end) {
            when (c.peekByte()) {
                0x4E, 0x4F, 0x50, 0x5E, 0x5F -> throw UnsupportedWasmFeatureException(
                    feature = "wasm-gc",
                    moduleIndex = moduleIndex,
                    detail = "GC/subtyping type definition is not supported",
                    cause = cause,
                )
            }
        }
    }

    private fun scanCodeSection(
        wasmBytes: ByteArray,
        start: Int,
        end: Int,
        moduleIndex: Int,
        importedFuncCount: Int,
        cause: ChicoryException,
    ) {
        val c = ByteCursor(wasmBytes, start)
        val bodyCount = c.readUleb32()
        for (bodyIdx in 0 until bodyCount) {
            if (c.pos >= end) return
            val bodySize = c.readUleb32()
            val bodyEnd = minOf(c.pos + bodySize, end)
            val funcIdx = importedFuncCount + bodyIdx
            val localGroups = c.readUleb32()
            repeat(localGroups) {
                c.readUleb32()
                c.skipValType()
            }
            while (c.pos < bodyEnd) {
                val op = c.readByte()
                when (op) {
                    0x06 -> throw UnsupportedWasmFeatureException(
                        feature = "exception-handling",
                        moduleIndex = moduleIndex,
                        functionIndex = funcIdx,
                        instructionName = "TRY",
                        detail = "Legacy exception-handling instruction 'try' is not supported",
                        cause = cause,
                    )
                    0x07 -> throw UnsupportedWasmFeatureException(
                        feature = "exception-handling",
                        moduleIndex = moduleIndex,
                        functionIndex = funcIdx,
                        instructionName = "CATCH",
                        detail = "Legacy exception-handling instruction 'catch' is not supported",
                        cause = cause,
                    )
                    0x08 -> throw UnsupportedWasmFeatureException(
                        feature = "exception-handling",
                        moduleIndex = moduleIndex,
                        functionIndex = funcIdx,
                        instructionName = "THROW",
                        detail = "Exception-handling instruction 'throw' is not supported",
                        cause = cause,
                    )
                    0x09 -> throw UnsupportedWasmFeatureException(
                        feature = "exception-handling",
                        moduleIndex = moduleIndex,
                        functionIndex = funcIdx,
                        instructionName = "RETHROW",
                        detail = "Legacy exception-handling instruction 'rethrow' is not supported",
                        cause = cause,
                    )
                    0x0A -> throw UnsupportedWasmFeatureException(
                        feature = "exception-handling",
                        moduleIndex = moduleIndex,
                        functionIndex = funcIdx,
                        instructionName = "THROW_REF",
                        detail = "Exception-handling instruction 'throw_ref' is not supported",
                        cause = cause,
                    )
                    0x18 -> throw UnsupportedWasmFeatureException(
                        feature = "exception-handling",
                        moduleIndex = moduleIndex,
                        functionIndex = funcIdx,
                        instructionName = "DELEGATE",
                        detail = "Legacy exception-handling instruction 'delegate' is not supported",
                        cause = cause,
                    )
                    0x19 -> throw UnsupportedWasmFeatureException(
                        feature = "exception-handling",
                        moduleIndex = moduleIndex,
                        functionIndex = funcIdx,
                        instructionName = "CATCH_ALL",
                        detail = "Legacy exception-handling instruction 'catch_all' is not supported",
                        cause = cause,
                    )
                    0x1F -> throw UnsupportedWasmFeatureException(
                        feature = "exception-handling",
                        moduleIndex = moduleIndex,
                        functionIndex = funcIdx,
                        instructionName = "TRY_TABLE",
                        detail = "Exception-handling instruction 'try_table' is not supported",
                        cause = cause,
                    )
                    0x12, 0x13, 0x15 -> throw UnsupportedWasmFeatureException(
                        feature = "tail-call",
                        moduleIndex = moduleIndex,
                        functionIndex = funcIdx,
                        instructionName = when (op) {
                            0x12 -> "RETURN_CALL"
                            0x13 -> "RETURN_CALL_INDIRECT"
                            else -> "RETURN_CALL_REF"
                        },
                        detail = "Tail-call instruction is not supported",
                        cause = cause,
                    )
                    0x14, 0xFB, in 0xD3..0xD6 -> throw UnsupportedWasmFeatureException(
                        feature = "wasm-gc",
                        moduleIndex = moduleIndex,
                        functionIndex = funcIdx,
                        instructionName = when (op) {
                            0x14 -> "CALL_REF"
                            0xD3 -> "REF_EQ"
                            0xD4 -> "REF_AS_NON_NULL"
                            0xD5 -> "BR_ON_NULL"
                            0xD6 -> "BR_ON_NON_NULL"
                            else -> "GC_PREFIX_0XFB"
                        },
                        detail = "Wasm GC instruction is not supported",
                        cause = cause,
                    )
                    0xFD -> throw UnsupportedWasmFeatureException(
                        feature = "simd128",
                        moduleIndex = moduleIndex,
                        functionIndex = funcIdx,
                        detail = "SIMD128 instruction is not supported",
                        cause = cause,
                    )
                    0xFE -> throw UnsupportedWasmFeatureException(
                        feature = "threads",
                        moduleIndex = moduleIndex,
                        functionIndex = funcIdx,
                        detail = "Threads/atomic instruction is not supported",
                        cause = cause,
                    )
                    0x00, 0x01, 0x05, 0x0B, 0x0F, 0x1A, 0x1B, 0xD1 -> Unit
                    0x02, 0x03, 0x04 -> c.skipSleb()
                    0x0C, 0x0D, 0x10, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0xD2 -> c.readUleb32()
                    0x11 -> {
                        c.readUleb32()
                        c.readUleb32()
                    }
                    0x0E -> {
                        val n = c.readUleb32()
                        repeat(n + 1) { c.readUleb32() }
                    }
                    0x1C -> {
                        val n = c.readUleb32()
                        repeat(n) { c.skipValType() }
                    }
                    in 0x28..0x3E -> {
                        val align = c.readUleb32()
                        if ((align and 0x40) != 0) c.readUleb32()
                        c.readUleb32()
                    }
                    0x3F, 0x40 -> c.readUleb32()
                    0x41, 0x42, 0xD0 -> c.skipSleb()
                    0x43 -> c.skipBytes(4)
                    0x44 -> c.skipBytes(8)
                    in 0x45..0xC4 -> Unit
                    0xFC -> {
                        when (c.readUleb32()) {
                            in 0..7 -> Unit
                            8, 10, 12, 14 -> {
                                c.readUleb32()
                                c.readUleb32()
                            }
                            9, 11, 13, 15, 16, 17 -> c.readUleb32()
                            else -> Unit
                        }
                    }
                    else -> break
                }
            }
            c.pos = bodyEnd
        }
    }

    private fun readLimits(
        cursor: ByteCursor,
        moduleIndex: Int,
        isMemory: Boolean,
        contextDesc: String,
    ): IrLimits {
        val flags = cursor.readUleb32()
        if ((flags and 0xF8) != 0) {
            throw IllegalArgumentException(
                "Malformed core module $moduleIndex: invalid limits flags 0x${flags.toString(16)} in $contextDesc",
            )
        }
        if ((flags and 0x04) != 0) {
            throw UnsupportedWasmFeatureException(
                feature = "memory64",
                moduleIndex = moduleIndex,
                detail = "64-bit ${if (isMemory) "memory" else "table"} limits in $contextDesc are not supported",
            )
        }
        if ((flags and 0x02) != 0) {
            throw UnsupportedWasmFeatureException(
                feature = "threads",
                moduleIndex = moduleIndex,
                detail = "Shared ${if (isMemory) "memory" else "table"} limits in $contextDesc are not supported",
            )
        }
        val hasMax = (flags and 0x01) != 0
        val min = cursor.readUleb32AsLong()
        val max = if (hasMax) cursor.readUleb32AsLong() else null
        if (max != null && max < min) {
            throw IllegalArgumentException(
                "Malformed core module $moduleIndex: min ($min) exceeds max ($max) in $contextDesc",
            )
        }
        if (isMemory && (min > MAX_WASM32_MEMORY_PAGES || (max != null && max > MAX_WASM32_MEMORY_PAGES))) {
            throw IllegalArgumentException(
                "Malformed core module $moduleIndex: memory pages exceed 65536 in $contextDesc",
            )
        }
        return IrLimits(min, max)
    }

    private fun hasValidHeader(bytes: ByteArray): Boolean =
        bytes.size >= WASM_HEADER.size &&
            (0 until WASM_HEADER.size).all { bytes[it] == WASM_HEADER[it] }

    private fun scanSectionSlices(bytes: ByteArray): List<Triple<Int, Int, Int>> {
        val out = mutableListOf<Triple<Int, Int, Int>>()
        val c = ByteCursor(bytes, WASM_HEADER.size)
        return try {
            while (c.hasRemaining()) {
                val id = c.readByte()
                val size = c.readUleb32()
                val start = c.pos
                val end = minOf(start + size, bytes.size)
                out += Triple(id, start, end)
                c.pos = end
            }
            out
        } catch (_: RuntimeException) {
            out
        }
    }

    private class ByteCursor(
        private val bytes: ByteArray,
        var pos: Int = 0,
    ) {
        fun hasRemaining(): Boolean = pos < bytes.size

        fun peekByte(): Int {
            check(pos < bytes.size) { "Unexpected EOF" }
            return bytes[pos].toInt() and 0xFF
        }

        fun readByte(): Int {
            check(pos < bytes.size) { "Unexpected EOF" }
            return bytes[pos++].toInt() and 0xFF
        }

        fun skipBytes(count: Int) {
            check(pos + count <= bytes.size) { "Unexpected EOF" }
            pos += count
        }

        fun readUleb32(): Int {
            val v = readUleb32AsLong()
            check(v <= Int.MAX_VALUE.toLong()) { "ULEB32 overflow: $v" }
            return v.toInt()
        }

        fun readUleb32AsLong(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = readByte()
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
                check(shift < 35) { "ULEB32 too long" }
            }
        }

        fun skipSleb() {
            var shift = 0
            while (true) {
                val b = readByte()
                if ((b and 0x80) == 0) return
                shift += 7
                check(shift < 70) { "SLEB too long" }
            }
        }

        fun skipString() {
            val len = readUleb32()
            skipBytes(len)
        }

        fun skipValType() {
            val b = readByte()
            if (b == 0x63 || b == 0x64) {
                skipSleb()
            }
        }
    }
}
