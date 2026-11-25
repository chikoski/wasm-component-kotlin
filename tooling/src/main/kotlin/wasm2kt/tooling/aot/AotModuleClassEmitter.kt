package wasm2kt.tooling.aot

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import wasm2kt.tooling.ir.IrConstExpr
import wasm2kt.tooling.ir.IrConstOp
import wasm2kt.tooling.ir.IrDataSegment
import wasm2kt.tooling.ir.IrElementSegment
import wasm2kt.tooling.ir.IrExportKind
import wasm2kt.tooling.ir.IrFuncType
import wasm2kt.tooling.ir.IrImport
import wasm2kt.tooling.ir.IrModule
import wasm2kt.tooling.ir.IrMutability
import wasm2kt.tooling.ir.IrValType
import wasm2kt.tooling.ir.WasmOp
import java.util.TreeSet

internal data class ModuleEmissionResult(
    val instanceInternalName: String,
    val importsInterfaceInternalName: String?,
    val dataResourcePath: String?,
    val dataResourceBytes: ByteArray?,
    val classes: LinkedHashMap<String, ByteArray>,
    val exports: List<CompiledExport>,
    val functionTargets: Map<Int, CompiledFunctionTarget>,
    val methodCodeBytes: Map<String, Int>,
    val constantPoolCounts: Map<String, Int>,
)

/**
 * Emits all JVM classes for a compiled [IrModule]: `CoreNInstance`, optional `$Imports` interface,
 * `$Result_*` multi-value tuple holders, `FuncGroup_*` static method containers, and `Dispatch_*`
 * `call_indirect` routers with two-level `tableswitch` / chunked splitting.
 */
internal object AotModuleClassEmitter {
    fun emit(
        ir: IrModule,
        packageName: String,
        baseSimpleName: String,
        dataResourcePathOverride: String?,
        plans: List<FunctionSplitPlan>,
        options: AotCompileOptions,
    ): ModuleEmissionResult {
        val pkgPrefix = if (packageName.isEmpty()) "" else packageName.replace('.', '/') + "/"
        val instanceInternalName = "${pkgPrefix}${baseSimpleName}Instance"
        val importsIfaceInternal = if (ir.importedFunctions.isNotEmpty()) "${instanceInternalName}\$Imports" else null

        val dataBytes: ByteArray?
        val resolvedDataPath: String?
        if (ir.dataSegments.isNotEmpty()) {
            dataBytes = DataSegmentPacker.pack(ir.dataSegments.map { it.bytes })
            resolvedDataPath = (dataResourcePathOverride ?: "wasm2kt/aot/${pkgPrefix}${baseSimpleName.lowercase()}.data")
                .removePrefix("/")
        } else {
            dataBytes = null
            resolvedDataPath = null
        }

        val indirectlyCallableFuncs = collectIndirectlyCallableFuncs(ir)
        val hasElemSegments = ir.elements.isNotEmpty() || anyFunctionUsesTableSegmentOps(ir)
        val hasDataSegments = ir.dataSegments.isNotEmpty()
        val hasFuncRefs = indirectlyCallableFuncs.isNotEmpty() || anyFunctionUsesRefFunc(ir) || ir.importedFunctions.isNotEmpty()

        // Identify shared (imported or exported mutable) globals.
        val sharedGlobals = computeSharedGlobals(ir)

        // Partition defined functions into FuncGroup_* classes staying well below 65,535 CP limit.
        val planByFuncIndex = HashMap<Int, FunctionSplitPlan>(plans.size)
        for (p in plans) planByFuncIndex[p.fn.index] = p

        val funcGroups = partitionFuncGroups(ir, plans, pkgPrefix, baseSimpleName, options)
        val funcTargets = LinkedHashMap<Int, CompiledFunctionTarget>(ir.totalFunctionCount)

        // Register imported function targets -> static bridges on CoreNInstance.
        for (imp in ir.importedFunctions) {
            val fType = ir.types[imp.typeIndex]
            val directCount = AotNames.countDirectParams(fType.params)
            val overflow = fType.params.size - directCount
            val mvClass = if (fType.results.size >= 2) AotNames.multiValueInternalName(instanceInternalName, fType.results) else null
            funcTargets[imp.funcIndex] = CompiledFunctionTarget(
                funcIndex = imp.funcIndex,
                ownerInternalName = instanceInternalName,
                methodName = AotNames.importBridgeName(imp.funcIndex),
                methodDescriptor = AotNames.staticFuncDescriptor(instanceInternalName, fType, directCount),
                funcType = fType,
                overflowParamCount = overflow,
                multiValueClassInternalName = mvClass,
            )
        }
        // Register defined function targets -> static methods on FuncGroup_k.
        for (group in funcGroups) {
            for (plan in group.plans) {
                val fn = plan.fn
                val directCount = AotNames.countDirectParams(fn.type.params)
                val mvClass = if (fn.type.results.size >= 2) AotNames.multiValueInternalName(instanceInternalName, fn.type.results) else null
                funcTargets[fn.index] = CompiledFunctionTarget(
                    funcIndex = fn.index,
                    ownerInternalName = group.internalName,
                    methodName = AotNames.funcMethodName(fn.index),
                    methodDescriptor = AotNames.staticFuncDescriptor(instanceInternalName, fn.type, directCount),
                    funcType = fn.type,
                    overflowParamCount = plan.overflowParamCount,
                    multiValueClassInternalName = mvClass,
                )
            }
        }

        // Collect all call_indirect signatures and allocate Dispatch class slots.
        val indirectSignatures = collectCallIndirectSignatures(ir)
        val dispatchAllocation = planDispatchers(
            ir = ir,
            pkgPrefix = pkgPrefix,
            baseSimpleName = baseSimpleName,
            instanceInternalName = instanceInternalName,
            indirectSignatures = indirectSignatures,
            indirectlyCallableFuncs = indirectlyCallableFuncs,
            options = options,
        )

        // Track required multi-value tuple classes.
        val multiValueTupleTypes = LinkedHashMap<String, List<IrValType>>()
        fun registerMultiValue(results: List<IrValType>): String {
            val key = AotNames.multiValueKey(results)
            multiValueTupleTypes.putIfAbsent(key, results)
            return AotNames.multiValueInternalName(instanceInternalName, results)
        }
        // Pre-register any multi-value return type appearing in exports/imports/signatures.
        for (t in ir.types) {
            if (t.results.size >= 2) registerMultiValue(t.results)
        }
        for (fn in ir.definedFunctions) {
            if (fn.type.results.size >= 2) registerMultiValue(fn.type.results)
        }

        // Compute max spill capacities required across all functions and indirect dispatchers.
        var maxSpill = SpillCounts.ZERO
        for (p in plans) maxSpill = maxSpill.maxWith(p.maxSpillUsage)
        for (imp in ir.importedFunctions) {
            val ft = ir.types[imp.typeIndex]
            val direct = AotNames.countDirectParams(ft.params)
            if (direct < ft.params.size) {
                maxSpill = maxSpill.maxWith(spillCountsForSlice(ft.params, direct))
            }
        }
        for (sig in indirectSignatures) {
            val direct = AotNames.countDirectParams(sig.params)
            if (direct < sig.params.size) {
                maxSpill = maxSpill.maxWith(spillCountsForSlice(sig.params, direct))
            }
        }
        val needsSpillField = maxSpill != SpillCounts.ZERO

        val emitCtx = object : ModuleEmitContext {
            override val ir: IrModule = ir
            override val instanceInternalName: String = instanceInternalName
            override val hasElemSegments: Boolean = hasElemSegments
            override val hasDataSegments: Boolean = hasDataSegments
            override val hasFuncRefs: Boolean = hasFuncRefs
            override val emitLineNumbers: Boolean = options.emitLineNumbers
            override fun funcTarget(funcIndex: Int): CompiledFunctionTarget = funcTargets.getValue(funcIndex)
            override fun callIndirectDispatcher(funcType: IrFuncType): DispatchCallSite =
                dispatchAllocation.callSite(funcType)
            override fun isSharedGlobal(globalIndex: Int): Boolean = sharedGlobals[globalIndex]
            override fun multiValueReturnClass(results: List<IrValType>): String = registerMultiValue(results)
        }

        val emittedNodes = ArrayList<ClassNode>()

        // 1. Imports interface
        if (importsIfaceInternal != null) {
            emittedNodes += emitImportsInterface(ir, importsIfaceInternal, instanceInternalName)
        }

        // 2. FuncGroup_* classes
        val funcGroupNodes = ArrayList<ClassNode>(funcGroups.size)
        for (group in funcGroups) {
            val cn = ClassNode().apply {
                version = Opcodes.V17
                access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER
                name = group.internalName
                superName = "java/lang/Object"
                sourceFile = "core${ir.moduleIndex}.wasm"
            }
            for (plan in group.plans) {
                cn.methods.addAll(WasmBytecodeEmitter.emitFunctionMethods(plan, emitCtx))
            }
            funcGroupNodes += cn
            emittedNodes += cn
        }

        // 3. Dispatch_* classes (must come before/after multi-value registration, tuple types already pre-registered)
        emittedNodes.addAll(
            emitDispatchClasses(
                ir = ir,
                dispatchAllocation = dispatchAllocation,
                instanceInternalName = instanceInternalName,
                funcTargets = funcTargets,
                options = options,
            ),
        )

        // Attach JVMS §4.7.6 InnerClasses metadata on FuncGroup_* classes referencing Result_* / Imports.
        for (fg in funcGroupNodes) {
            if (importsIfaceInternal != null) {
                fg.visitInnerClass(importsIfaceInternal, instanceInternalName, "Imports", Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE)
            }
            for ((_, results) in multiValueTupleTypes) {
                val tupleInternal = AotNames.multiValueInternalName(instanceInternalName, results)
                fg.visitInnerClass(tupleInternal, instanceInternalName, tupleInternal.substringAfterLast('$'), Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL)
            }
        }

        // 4. Multi-value Result_* classes
        for ((_, results) in multiValueTupleTypes) {
            emittedNodes += emitMultiValueTupleClass(instanceInternalName, results)
        }

        // 5. CoreNInstance class (must be emitted after multiValueTupleTypes populated)
        emittedNodes += emitInstanceClass(
            ir = ir,
            instanceInternalName = instanceInternalName,
            importsIfaceInternal = importsIfaceInternal,
            resolvedDataPath = resolvedDataPath,
            sharedGlobals = sharedGlobals,
            indirectlyCallableFuncs = indirectlyCallableFuncs,
            hasElemSegments = hasElemSegments,
            hasDataSegments = hasDataSegments,
            hasFuncRefs = hasFuncRefs,
            needsSpillField = needsSpillField,
            maxSpill = maxSpill,
            multiValueTupleTypes = multiValueTupleTypes,
            funcTargets = funcTargets,
            options = options,
        )

        // Write ClassNodes to raw bytecode using AotClassWriter(COMPUTE_FRAMES).
        val classes = LinkedHashMap<String, ByteArray>(emittedNodes.size)
        val methodCodeBytes = LinkedHashMap<String, Int>()
        val constantPoolCounts = LinkedHashMap<String, Int>()
        val prefixSlash = if (packageName.isEmpty()) "${baseSimpleName}" else pkgPrefix + baseSimpleName

        for (cn in emittedNodes) {
            val cw = AotClassWriter(prefixSlash)
            cn.accept(cw)
            val bytes = cw.toByteArray()
            classes[cn.name] = bytes
            constantPoolCounts[cn.name] = readConstantPoolCount(bytes)
            for ((key, codeLen) in readMethodCodeLengths(bytes)) {
                methodCodeBytes["${cn.name}.${key.name}${key.desc}"] = codeLen
            }
        }

        val exports = ir.exports.map { exp ->
            when (exp.kind) {
                IrExportKind.FUNCTION -> CompiledExport.Function(exp.name, exp.index, funcTargets.getValue(exp.index))
                IrExportKind.TABLE -> CompiledExport.Table(
                    exp.name,
                    exp.index,
                    AotNames.tableFieldName(exp.index),
                    ir.tableTypeOrNull(exp.index)!!,
                )
                IrExportKind.MEMORY -> CompiledExport.Memory(
                    exp.name,
                    exp.index,
                    AotNames.memoryFieldName(exp.index),
                    ir.memoryTypeOrNull(exp.index)!!,
                )
                IrExportKind.GLOBAL -> CompiledExport.Global(
                    exp.name,
                    exp.index,
                    AotNames.globalFieldName(exp.index),
                    ir.globalTypeOrNull(exp.index)!!,
                    sharedGlobals[exp.index],
                )
            }
        }

        return ModuleEmissionResult(
            instanceInternalName = instanceInternalName,
            importsInterfaceInternalName = importsIfaceInternal,
            dataResourcePath = resolvedDataPath,
            dataResourceBytes = dataBytes,
            classes = classes,
            exports = exports,
            functionTargets = funcTargets,
            methodCodeBytes = methodCodeBytes,
            constantPoolCounts = constantPoolCounts,
        )
    }

    private fun spillCountsForSlice(params: List<IrValType>, start: Int): SpillCounts {
        var i32 = 0; var i64 = 0; var f32 = 0; var f64 = 0; var ref = 0
        for (i in start until params.size) {
            when (params[i]) {
                IrValType.I32 -> i32++
                IrValType.I64 -> i64++
                IrValType.F32 -> f32++
                IrValType.F64 -> f64++
                IrValType.FUNCREF, IrValType.EXTERNREF -> ref++
            }
        }
        return SpillCounts(i32, i64, f32, f64, ref)
    }

    private data class FuncGroupPartition(
        val internalName: String,
        val plans: List<FunctionSplitPlan>,
    )

    private fun partitionFuncGroups(
        ir: IrModule,
        plans: List<FunctionSplitPlan>,
        pkgPrefix: String,
        baseSimpleName: String,
        options: AotCompileOptions,
    ): List<FuncGroupPartition> {
        if (plans.isEmpty()) return emptyList()
        val groups = mutableListOf<FuncGroupPartition>()
        var cur = mutableListOf<FunctionSplitPlan>()
        var cpEstimate = 256
        for (p in plans) {
            val fnCpEstimate = estimatePlanCpCost(p)
            if (cur.isNotEmpty() && (cur.size >= options.maxFuncsPerGroup || cpEstimate + fnCpEstimate > options.maxConstPoolBudget)) {
                val gIdx = groups.size
                groups += FuncGroupPartition("${pkgPrefix}${baseSimpleName}FuncGroup_$gIdx", cur)
                cur = mutableListOf()
                cpEstimate = 256
            }
            cur += p
            cpEstimate += fnCpEstimate
        }
        if (cur.isNotEmpty()) {
            groups += FuncGroupPartition("${pkgPrefix}${baseSimpleName}FuncGroup_${groups.size}", cur)
        }
        return groups
    }

    private fun estimatePlanCpCost(plan: FunctionSplitPlan): Int {
        // Conservative estimate: distinct callees/fields/constants in instructions + method headers.
        return 32 + plan.helpers.size * 12 + (plan.fn.instructions.size / 2).coerceAtLeast(8)
    }

    private fun collectIndirectlyCallableFuncs(ir: IrModule): TreeSet<Int> {
        val funcs = TreeSet<Int>()
        for (elem in ir.elements) {
            for (item in elem.items) {
                for (op in item.ops) {
                    if (op is IrConstOp.RefFunc) funcs.add(op.funcIndex)
                }
            }
        }
        for (g in ir.definedGlobals) {
            for (op in g.initExpr.ops) {
                if (op is IrConstOp.RefFunc) funcs.add(op.funcIndex)
            }
        }
        for (fn in ir.definedFunctions) {
            for (insn in fn.instructions) {
                val op = insn.op
                if (op is WasmOp.RefFunc) funcs.add(op.funcIndex)
            }
        }
        for (exp in ir.exports) {
            if (exp.kind == IrExportKind.FUNCTION) funcs.add(exp.index)
        }
        return funcs
    }

    private fun collectCallIndirectSignatures(ir: IrModule): List<IrFuncType> {
        val seen = LinkedHashMap<String, IrFuncType>()
        for (fn in ir.definedFunctions) {
            for (insn in fn.instructions) {
                val op = insn.op
                if (op is WasmOp.CallIndirect) {
                    seen.putIfAbsent(op.funcType.compactSignature, op.funcType)
                }
            }
        }
        return seen.values.toList()
    }

    internal fun computeSharedGlobals(ir: IrModule): BooleanArray {
        val exportedGlobalIndices = ir.exports
            .filter { it.kind == IrExportKind.GLOBAL }
            .mapTo(HashSet()) { it.index }
        val sharedGlobals = BooleanArray(ir.totalGlobalCount)
        for (i in 0 until ir.totalGlobalCount) {
            val gt = ir.globalTypeOrNull(i)!!
            val imported = i < ir.importedGlobals.size
            if (gt.mutability == IrMutability.VAR && (imported || i in exportedGlobalIndices)) {
                sharedGlobals[i] = true
            }
        }
        return sharedGlobals
    }

    private fun anyFunctionUsesTableSegmentOps(ir: IrModule): Boolean =
        ir.definedFunctions.any { fn ->
            fn.instructions.any { it.op is WasmOp.TableInit || it.op is WasmOp.ElemDrop }
        }

    private fun anyFunctionUsesRefFunc(ir: IrModule): Boolean =
        ir.definedFunctions.any { fn -> fn.instructions.any { it.op is WasmOp.RefFunc } } ||
            ir.definedGlobals.any { g -> g.initExpr.ops.any { it is IrConstOp.RefFunc } } ||
            ir.elements.any { el -> el.items.any { it.ops.any { op -> op is IrConstOp.RefFunc } } }

    // ---------------------------------------------------------------- Dispatch planning & emission

    private data class SigDispatchPlan(
        val sigKey: String,
        val funcType: IrFuncType,
        val methodName: String,
        val ownerInternalName: String,
        val candidates: List<Int>,
    )

    private class DispatchAllocation(
        val groups: List<Pair<String, List<SigDispatchPlan>>>,
        private val bySig: Map<String, SigDispatchPlan>,
        private val instanceInternalName: String,
    ) {
        fun callSite(ft: IrFuncType): DispatchCallSite {
            val plan = bySig.getValue(ft.compactSignature)
            val directCount = AotNames.countDirectParams(ft.params)
            val desc = buildString {
                append('(')
                for (i in 0 until directCount) append(AotNames.jvmDesc(ft.params[i]))
                append('I') // table element index
                append(AotNames.WASM_TABLE_DESC)
                append('L').append(instanceInternalName).append(';')
                append(')')
                append(AotNames.returnTypeDesc(instanceInternalName, ft.results))
            }
            return DispatchCallSite(plan.ownerInternalName, plan.methodName, desc)
        }
    }

    private fun planDispatchers(
        ir: IrModule,
        pkgPrefix: String,
        baseSimpleName: String,
        instanceInternalName: String,
        indirectSignatures: List<IrFuncType>,
        indirectlyCallableFuncs: Set<Int>,
        options: AotCompileOptions,
    ): DispatchAllocation {
        val bySigCandidates = HashMap<String, MutableList<Int>>()
        for (fnIdx in indirectlyCallableFuncs) {
            val ft = ir.functionTypeOrNull(fnIdx) ?: continue
            bySigCandidates.getOrPut(ft.compactSignature) { mutableListOf() }.add(fnIdx)
        }

        val groups = mutableListOf<Pair<String, MutableList<SigDispatchPlan>>>()
        var curList = mutableListOf<SigDispatchPlan>()
        var curCp = 256
        val bySig = HashMap<String, SigDispatchPlan>()

        for ((sigIdx, ft) in indirectSignatures.withIndex()) {
            val sigKey = ft.compactSignature
            val cands = bySigCandidates[sigKey]?.sorted().orEmpty()
            val cost = 40 + cands.size * 6
            if (curList.isNotEmpty() && (curList.size >= options.maxFuncsPerGroup || curCp + cost > options.maxConstPoolBudget)) {
                groups += "${pkgPrefix}${baseSimpleName}Dispatch_${groups.size}" to curList
                curList = mutableListOf()
                curCp = 256
            }
            val owner = "${pkgPrefix}${baseSimpleName}Dispatch_${groups.size}"
            val plan = SigDispatchPlan(
                sigKey = sigKey,
                funcType = ft,
                methodName = "callIndirect_$sigIdx",
                ownerInternalName = owner,
                candidates = cands,
            )
            curList += plan
            curCp += cost
            bySig[sigKey] = plan
        }
        if (curList.isNotEmpty()) {
            groups += "${pkgPrefix}${baseSimpleName}Dispatch_${groups.size}" to curList
        }
        return DispatchAllocation(groups, bySig, instanceInternalName)
    }

    private fun emitDispatchClasses(
        ir: IrModule,
        dispatchAllocation: DispatchAllocation,
        instanceInternalName: String,
        funcTargets: Map<Int, CompiledFunctionTarget>,
        options: AotCompileOptions,
    ): List<ClassNode> {
        val out = ArrayList<ClassNode>(dispatchAllocation.groups.size)
        for ((ownerInternal, sigPlans) in dispatchAllocation.groups) {
            val cn = ClassNode().apply {
                version = Opcodes.V17
                access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER
                name = ownerInternal
                superName = "java/lang/Object"
                sourceFile = "core${ir.moduleIndex}.wasm"
            }
            for (plan in sigPlans) {
                emitSigDispatcher(
                    cn = cn,
                    plan = plan,
                    instanceInternalName = instanceInternalName,
                    funcTargets = funcTargets,
                    maxBucketSize = options.maxDispatchBucketSize,
                )
            }
            out += cn
        }
        return out
    }

    private fun emitSigDispatcher(
        cn: ClassNode,
        plan: SigDispatchPlan,
        instanceInternalName: String,
        funcTargets: Map<Int, CompiledFunctionTarget>,
        maxBucketSize: Int,
    ) {
        val ft = plan.funcType
        val directCount = AotNames.countDirectParams(ft.params)
        val retDesc = AotNames.returnTypeDesc(instanceInternalName, ft.results)
        val entryDesc = buildString {
            append('(')
            for (i in 0 until directCount) append(AotNames.jvmDesc(ft.params[i]))
            append('I') // elemIdx
            append(AotNames.WASM_TABLE_DESC)
            append('L').append(instanceInternalName).append(';')
            append(')')
            append(retDesc)
        }

        val paramSlots = IntArray(directCount)
        var nextSlot = 0
        for (i in 0 until directCount) {
            paramSlots[i] = nextSlot
            nextSlot += AotNames.slotSize(ft.params[i])
        }
        val elemIdxSlot = nextSlot++
        val tableSlot = nextSlot++
        val instSlot = nextSlot++
        val refSlot = nextSlot++
        val funcIdxSlot = nextSlot++
        val scratchStart = nextSlot

        val mn = MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, plan.methodName, entryDesc, null, null)
        val insns = mn.instructions

        // val ref = AotOps.callIndirectLookup(elemIdx, canonicalSig, table)
        insns.add(VarInsnNode(Opcodes.ILOAD, elemIdxSlot))
        insns.add(LdcInsnNode(plan.sigKey))
        insns.add(VarInsnNode(Opcodes.ALOAD, tableSlot))
        insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, AotNames.OPS_INTERNAL, "callIndirectLookup", "(ILjava/lang/String;${AotNames.WASM_TABLE_DESC})${AotNames.FUNC_REF_DESC}", false))
        insns.add(VarInsnNode(Opcodes.ASTORE, refSlot))

        // if (ref is FuncRef.Core && ref.owner === inst) -> localFastPath else externalPath
        val externalPathLbl = LabelNode()
        val localFastLbl = LabelNode()
        insns.add(VarInsnNode(Opcodes.ALOAD, refSlot))
        insns.add(TypeInsnNode(Opcodes.INSTANCEOF, AotNames.FUNC_REF_CORE_INTERNAL))
        insns.add(JumpInsnNode(Opcodes.IFEQ, externalPathLbl))
        insns.add(VarInsnNode(Opcodes.ALOAD, refSlot))
        insns.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, AotNames.FUNC_REF_INTERNAL, "getOwner", "()Ljava/lang/Object;", false))
        insns.add(VarInsnNode(Opcodes.ALOAD, instSlot))
        insns.add(JumpInsnNode(Opcodes.IF_ACMPEQ, localFastLbl))
        insns.add(externalPathLbl)

        // External fallback path: invoke AotDispatchSupport.invokeExternal(ref, args)
        emitExternalDispatchFallback(insns, ft, directCount, paramSlots, instSlot, refSlot, scratchStart, instanceInternalName)

        // Local fast path: dispatch on ref.funcIndex
        insns.add(localFastLbl)
        if (plan.candidates.isEmpty()) {
            emitTrapInstruction(insns, "indirect call type/table mismatch in signature ${plan.sigKey}")
        } else {
            insns.add(VarInsnNode(Opcodes.ALOAD, refSlot))
            insns.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, AotNames.FUNC_REF_INTERNAL, "getFuncIndex", "()I", false))
            insns.add(VarInsnNode(Opcodes.ISTORE, funcIdxSlot))

            val subMethodDesc = buildString {
                append('(')
                for (i in 0 until directCount) append(AotNames.jvmDesc(ft.params[i]))
                append('I') // funcIndex
                append('L').append(instanceInternalName).append(';')
                append(')')
                append(retDesc)
            }

            if (plan.candidates.size <= maxBucketSize) {
                emitLocalCandidateSwitch(insns, plan.candidates, ft, directCount, paramSlots, instSlot, funcIdxSlot, funcTargets)
            } else {
                val helperCounter = intArrayOf(0)
                emitChunkedCandidateRouter(
                    cn = cn,
                    baseMethodName = plan.methodName,
                    subMethodDesc = subMethodDesc,
                    candidates = plan.candidates,
                    ft = ft,
                    directCount = directCount,
                    callerInsns = insns,
                    callerParamSlots = paramSlots,
                    callerFuncIdxSlot = funcIdxSlot,
                    callerInstSlot = instSlot,
                    funcTargets = funcTargets,
                    maxBucketSize = maxBucketSize,
                    helperCounter = helperCounter,
                )
            }
        }
        mn.maxStack = 256
        mn.maxLocals = scratchStart + 8
        cn.methods.add(mn)
    }

    private fun emitExternalDispatchFallback(
        insns: InsnList,
        ft: IrFuncType,
        directCount: Int,
        paramSlots: IntArray,
        instSlot: Int,
        refSlot: Int,
        scratchStart: Int,
        instanceInternalName: String,
    ) {
        val argsArrSlot = scratchStart
        WasmBytecodeEmitter.pushInt(insns, ft.params.size)
        insns.add(TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"))
        insns.add(VarInsnNode(Opcodes.ASTORE, argsArrSlot))

        for (i in 0 until directCount) {
            insns.add(VarInsnNode(Opcodes.ALOAD, argsArrSlot))
            WasmBytecodeEmitter.pushInt(insns, i)
            insns.add(VarInsnNode(AotNames.loadOpcode(ft.params[i]), paramSlots[i]))
            emitBoxPrimitiveIfNeeded(insns, ft.params[i])
            insns.add(InsnNode(Opcodes.AASTORE))
        }
        if (directCount < ft.params.size) {
            val spillCounter = WasmBytecodeEmitter.TypeSlotCounters()
            for (i in directCount until ft.params.size) {
                val vt = ft.params[i]
                val sIdx = spillCounter.next(vt)
                insns.add(VarInsnNode(Opcodes.ALOAD, argsArrSlot))
                WasmBytecodeEmitter.pushInt(insns, i)
                emitLoadSpillForBoxing(insns, instanceInternalName, instSlot, vt, sIdx)
                emitBoxPrimitiveIfNeeded(insns, vt)
                insns.add(InsnNode(Opcodes.AASTORE))
            }
        }
        insns.add(VarInsnNode(Opcodes.ALOAD, refSlot))
        insns.add(VarInsnNode(Opcodes.ALOAD, argsArrSlot))
        insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, AotNames.DISPATCH_SUPPORT_INTERNAL, "invokeExternal", "(${AotNames.FUNC_REF_DESC}[Ljava/lang/Object;)[Ljava/lang/Object;", false))
        val resArrSlot = scratchStart + 1
        when (ft.results.size) {
            0 -> {
                insns.add(InsnNode(Opcodes.POP))
                insns.add(InsnNode(Opcodes.RETURN))
            }
            1 -> {
                insns.add(InsnNode(Opcodes.ICONST_0))
                insns.add(InsnNode(Opcodes.AALOAD))
                emitUnboxFromObject(insns, ft.results[0])
                insns.add(InsnNode(AotNames.returnOpcode(ft.results[0])))
            }
            else -> {
                insns.add(VarInsnNode(Opcodes.ASTORE, resArrSlot))
                val tupleClass = AotNames.multiValueInternalName(instanceInternalName, ft.results)
                val tupleSlot = scratchStart + 2
                val hasRefs = ft.results.any { AotNames.isRef(it) }
                if (hasRefs) {
                    insns.add(TypeInsnNode(Opcodes.NEW, tupleClass))
                    insns.add(InsnNode(Opcodes.DUP))
                    insns.add(MethodInsnNode(Opcodes.INVOKESPECIAL, tupleClass, "<init>", "()V", false))
                } else {
                    insns.add(VarInsnNode(Opcodes.ALOAD, instSlot))
                    insns.add(FieldInsnNode(Opcodes.GETFIELD, instanceInternalName, "mv_${AotNames.multiValueKey(ft.results)}", "L$tupleClass;"))
                }
                insns.add(VarInsnNode(Opcodes.ASTORE, tupleSlot))
                for (k in ft.results.indices) {
                    insns.add(VarInsnNode(Opcodes.ALOAD, tupleSlot))
                    insns.add(VarInsnNode(Opcodes.ALOAD, resArrSlot))
                    WasmBytecodeEmitter.pushInt(insns, k)
                    insns.add(InsnNode(Opcodes.AALOAD))
                    emitUnboxFromObject(insns, ft.results[k])
                    insns.add(FieldInsnNode(Opcodes.PUTFIELD, tupleClass, "v$k", AotNames.jvmDesc(ft.results[k])))
                }
                insns.add(VarInsnNode(Opcodes.ALOAD, tupleSlot))
                insns.add(InsnNode(Opcodes.ARETURN))
            }
        }
    }

    private fun emitChunkedCandidateRouter(
        cn: ClassNode,
        baseMethodName: String,
        subMethodDesc: String,
        candidates: List<Int>,
        ft: IrFuncType,
        directCount: Int,
        callerInsns: InsnList,
        callerParamSlots: IntArray,
        callerFuncIdxSlot: Int,
        callerInstSlot: Int,
        funcTargets: Map<Int, CompiledFunctionTarget>,
        maxBucketSize: Int,
        helperCounter: IntArray,
    ) {
        val chunks = candidates.chunked(maxBucketSize)
        val firstMin = candidates.first()
        val lastMax = candidates.last()
        val span = lastMax.toLong() - firstMin.toLong() + 1L
        val bucketRange = maxBucketSize
        val totalBuckets = ((span + bucketRange - 1) / bucketRange).toInt()

        if (span <= candidates.size.toLong() * 4L + 128L && totalBuckets in 2..maxBucketSize) {
            // Dense two-level TABLESWITCH!
            val minBuckets = Array<MutableList<Int>>(totalBuckets) { mutableListOf() }
            for (idx in candidates) {
                val b = (idx - firstMin) / bucketRange
                minBuckets[b].add(idx)
            }
            val bucketTargets = arrayOfNulls<Pair<String, LabelNode>>(totalBuckets)
            val dfltLbl = LabelNode()
            val switchLabels = Array(totalBuckets) { b ->
                if (minBuckets[b].isEmpty()) {
                    dfltLbl
                } else {
                    val hName = "${baseMethodName}\$b${helperCounter[0]++}"
                    val hNode = emitSubLeafMethod(hName, subMethodDesc, minBuckets[b], ft, directCount, funcTargets)
                    cn.methods.add(hNode)
                    val lbl = LabelNode()
                    bucketTargets[b] = hName to lbl
                    lbl
                }
            }
            // bucketIdx = (funcIndex - firstMin) / bucketRange (bounds-guarded against negative)
            callerInsns.add(VarInsnNode(Opcodes.ILOAD, callerFuncIdxSlot))
            WasmBytecodeEmitter.pushInt(callerInsns, firstMin)
            callerInsns.add(JumpInsnNode(Opcodes.IF_ICMPLT, dfltLbl))
            callerInsns.add(VarInsnNode(Opcodes.ILOAD, callerFuncIdxSlot))
            WasmBytecodeEmitter.pushInt(callerInsns, firstMin)
            callerInsns.add(InsnNode(Opcodes.ISUB))
            WasmBytecodeEmitter.pushInt(callerInsns, bucketRange)
            callerInsns.add(InsnNode(Opcodes.IDIV))
            callerInsns.add(TableSwitchInsnNode(0, totalBuckets - 1, dfltLbl, *switchLabels))
            for (b in 0 until totalBuckets) {
                val pair = bucketTargets[b] ?: continue
                callerInsns.add(pair.second)
                for (i in 0 until directCount) callerInsns.add(VarInsnNode(AotNames.loadOpcode(ft.params[i]), callerParamSlots[i]))
                callerInsns.add(VarInsnNode(Opcodes.ILOAD, callerFuncIdxSlot))
                callerInsns.add(VarInsnNode(Opcodes.ALOAD, callerInstSlot))
                callerInsns.add(MethodInsnNode(Opcodes.INVOKESTATIC, cn.name, pair.first, subMethodDesc, false))
                emitReturnOpcodeFor(callerInsns, ft.results)
            }
            callerInsns.add(dfltLbl)
            emitTrapInstruction(callerInsns, "indirect call function index out of range")
            return
        }

        // Sparse or very large (> maxBucketSize^2): chunk sorted list cleanly; if <= maxBucketSize chunks
        // emit balanced binary search directly over chunk ranges, else split chunks recursively into sub-routers!
        val chunkHelpers = chunks.map { chunk ->
            val hName = "${baseMethodName}\$b${helperCounter[0]++}"
            cn.methods.add(emitSubLeafMethod(hName, subMethodDesc, chunk, ft, directCount, funcTargets))
            ChunkTarget(chunk.first(), chunk.last(), cn.name, hName)
        }
        emitChunkBinarySearch(
            cn = cn,
            baseMethodName = baseMethodName,
            subMethodDesc = subMethodDesc,
            chunks = chunkHelpers,
            ft = ft,
            directCount = directCount,
            insns = callerInsns,
            paramSlots = callerParamSlots,
            funcIdxSlot = callerFuncIdxSlot,
            instSlot = callerInstSlot,
            maxFanout = maxBucketSize,
            helperCounter = helperCounter,
        )
    }

    private data class ChunkTarget(
        val minFunc: Int,
        val maxFunc: Int,
        val ownerInternalName: String,
        val methodName: String,
    )

    private fun emitChunkBinarySearch(
        cn: ClassNode,
        baseMethodName: String,
        subMethodDesc: String,
        chunks: List<ChunkTarget>,
        ft: IrFuncType,
        directCount: Int,
        insns: InsnList,
        paramSlots: IntArray,
        funcIdxSlot: Int,
        instSlot: Int,
        maxFanout: Int,
        helperCounter: IntArray,
    ) {
        // Keep each router method small by grouping if chunks.size > maxFanout.
        if (chunks.size > maxFanout) {
            val grouped = chunks.chunked(maxFanout).map { sub ->
                val hName = "${baseMethodName}\$r${helperCounter[0]++}"
                val subMn = MethodNode(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, hName, subMethodDesc, null, null)
                var nextSlot = 0
                val pSlots = IntArray(directCount)
                for (i in 0 until directCount) {
                    pSlots[i] = nextSlot
                    nextSlot += AotNames.slotSize(ft.params[i])
                }
                val fIdxSlot = nextSlot++
                val iSlot = nextSlot++
                emitChunkBinarySearch(
                    cn, baseMethodName, subMethodDesc, sub, ft, directCount,
                    subMn.instructions, pSlots, fIdxSlot, iSlot, maxFanout, helperCounter,
                )
                subMn.maxStack = 64
                subMn.maxLocals = nextSlot + 4
                cn.methods.add(subMn)
                ChunkTarget(sub.first().minFunc, sub.last().maxFunc, cn.name, hName)
            }
            emitChunkBinarySearch(
                cn, baseMethodName, subMethodDesc, grouped, ft, directCount,
                insns, paramSlots, funcIdxSlot, instSlot, maxFanout, helperCounter,
            )
            return
        }

        val trapLbl = LabelNode()
        fun emitTree(lo: Int, hi: Int) {
            if (lo == hi) {
                val c = chunks[lo]
                for (i in 0 until directCount) insns.add(VarInsnNode(AotNames.loadOpcode(ft.params[i]), paramSlots[i]))
                insns.add(VarInsnNode(Opcodes.ILOAD, funcIdxSlot))
                insns.add(VarInsnNode(Opcodes.ALOAD, instSlot))
                insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, c.ownerInternalName, c.methodName, subMethodDesc, false))
                emitReturnOpcodeFor(insns, ft.results)
                return
            }
            val mid = (lo + hi) ushr 1
            val rightLbl = LabelNode()
            insns.add(VarInsnNode(Opcodes.ILOAD, funcIdxSlot))
            WasmBytecodeEmitter.pushInt(insns, chunks[mid].maxFunc)
            insns.add(JumpInsnNode(Opcodes.IF_ICMPGT, rightLbl))
            emitTree(lo, mid)
            insns.add(rightLbl)
            emitTree(mid + 1, hi)
        }

        if (chunks.isEmpty()) {
            emitTrapInstruction(insns, "indirect call failed: no functions")
        } else {
            emitTree(0, chunks.lastIndex)
        }
    }

    private fun emitSubLeafMethod(
        name: String,
        desc: String,
        candidates: List<Int>,
        ft: IrFuncType,
        directCount: Int,
        funcTargets: Map<Int, CompiledFunctionTarget>,
    ): MethodNode {
        val mn = MethodNode(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, name, desc, null, null)
        var nextSlot = 0
        val pSlots = IntArray(directCount)
        for (i in 0 until directCount) {
            pSlots[i] = nextSlot
            nextSlot += AotNames.slotSize(ft.params[i])
        }
        val funcIdxSlot = nextSlot++
        val instSlot = nextSlot++
        emitLocalCandidateSwitch(mn.instructions, candidates, ft, directCount, pSlots, instSlot, funcIdxSlot, funcTargets)
        mn.maxStack = 64
        mn.maxLocals = nextSlot + 4
        return mn
    }

    private fun emitLocalCandidateSwitch(
        insns: InsnList,
        candidates: List<Int>,
        ft: IrFuncType,
        directCount: Int,
        paramSlots: IntArray,
        instSlot: Int,
        funcIdxSlot: Int,
        funcTargets: Map<Int, CompiledFunctionTarget>,
    ) {
        val dfltLbl = LabelNode()
        val sorted = candidates.distinct().sorted()
        val caseLabels = Array(sorted.size) { LabelNode() }
        val min = sorted.first()
        val max = sorted.last()
        val span = max.toLong() - min.toLong() + 1L

        insns.add(VarInsnNode(Opcodes.ILOAD, funcIdxSlot))
        if (span <= sorted.size.toLong() * 2L + 16L && span <= 256L) {
            val map = sorted.withIndex().associate { (i, fnIdx) -> fnIdx to caseLabels[i] }
            val tableLabels = Array(span.toInt()) { offset -> map[min + offset] ?: dfltLbl }
            insns.add(TableSwitchInsnNode(min, max, dfltLbl, *tableLabels))
        } else {
            insns.add(LookupSwitchInsnNode(dfltLbl, sorted.toIntArray(), caseLabels))
        }

        for (i in sorted.indices) {
            val fnIdx = sorted[i]
            val target = funcTargets.getValue(fnIdx)
            insns.add(caseLabels[i])
            for (p in 0 until directCount) {
                insns.add(VarInsnNode(AotNames.loadOpcode(ft.params[p]), paramSlots[p]))
            }
            insns.add(VarInsnNode(Opcodes.ALOAD, instSlot))
            insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, target.ownerInternalName, target.methodName, target.methodDescriptor, false))
            emitReturnOpcodeFor(insns, ft.results)
        }
        insns.add(dfltLbl)
        emitTrapInstruction(insns, "indirect call type mismatch")
    }

    private fun emitReturnOpcodeFor(insns: InsnList, results: List<IrValType>) {
        when (results.size) {
            0 -> insns.add(InsnNode(Opcodes.RETURN))
            1 -> insns.add(InsnNode(AotNames.returnOpcode(results[0])))
            else -> insns.add(InsnNode(Opcodes.ARETURN))
        }
    }

    // ---------------------------------------------------------------- Multi-value tuple & Imports interface

    private fun emitMultiValueTupleClass(instanceInternalName: String, results: List<IrValType>): ClassNode {
        val name = AotNames.multiValueInternalName(instanceInternalName, results)
        val simpleName = name.substringAfterLast('$')
        val cn = ClassNode().apply {
            version = Opcodes.V17
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER
            this.name = name
            superName = "java/lang/Object"
        }
        cn.visitInnerClass(name, instanceInternalName, simpleName, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL)
        for (i in results.indices) {
            cn.visitField(Opcodes.ACC_PUBLIC, "v$i", AotNames.jvmDesc(results[i]), null, null)
        }
        val ctor = MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
            instructions.add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false))
            instructions.add(InsnNode(Opcodes.RETURN))
            maxStack = 1
            maxLocals = 1
        }
        cn.methods.add(ctor)
        return cn
    }

    private fun emitImportsInterface(
        ir: IrModule,
        importsIfaceInternal: String,
        instanceInternalName: String,
    ): ClassNode {
        val cn = ClassNode().apply {
            version = Opcodes.V17
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE
            name = importsIfaceInternal
            superName = "java/lang/Object"
        }
        cn.visitInnerClass(
            importsIfaceInternal,
            instanceInternalName,
            "Imports",
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE,
        )
        val visitedResults = HashSet<String>()
        for (imp in ir.importedFunctions) {
            val ft = ir.types[imp.typeIndex]
            val desc = AotNames.importMethodDesc(ft.params, ft.results, instanceInternalName)
            cn.methods.add(MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, AotNames.importBridgeName(imp.funcIndex), desc, null, null))
            if (ft.results.size >= 2) {
                val tupleInternal = AotNames.multiValueInternalName(instanceInternalName, ft.results)
                if (visitedResults.add(tupleInternal)) {
                    cn.visitInnerClass(
                        tupleInternal,
                        instanceInternalName,
                        tupleInternal.substringAfterLast('$'),
                        Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
                    )
                }
            }
        }
        return cn
    }

    // ---------------------------------------------------------------- CoreNInstance emission

    private fun emitInstanceClass(
        ir: IrModule,
        instanceInternalName: String,
        importsIfaceInternal: String?,
        resolvedDataPath: String?,
        sharedGlobals: BooleanArray,
        indirectlyCallableFuncs: Set<Int>,
        hasElemSegments: Boolean,
        hasDataSegments: Boolean,
        hasFuncRefs: Boolean,
        needsSpillField: Boolean,
        maxSpill: SpillCounts,
        multiValueTupleTypes: Map<String, List<IrValType>>,
        funcTargets: Map<Int, CompiledFunctionTarget>,
        options: AotCompileOptions,
    ): ClassNode {
        val cn = ClassNode().apply {
            version = Opcodes.V17
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER
            name = instanceInternalName
            superName = "java/lang/Object"
            interfaces = mutableListOf(AotNames.CORE_INSTANCE_IFACE_INTERNAL)
            sourceFile = "core${ir.moduleIndex}.wasm"
        }
        if (importsIfaceInternal != null) {
            cn.visitInnerClass(
                importsIfaceInternal,
                instanceInternalName,
                "Imports",
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE,
            )
        }
        for ((_, results) in multiValueTupleTypes) {
            val tupleInternal = AotNames.multiValueInternalName(instanceInternalName, results)
            cn.visitInnerClass(
                tupleInternal,
                instanceInternalName,
                tupleInternal.substringAfterLast('$'),
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            )
        }

        // Instance fields.
        for (m in 0 until ir.totalMemoryCount) {
            cn.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, AotNames.memoryFieldName(m), AotNames.LINEAR_MEMORY_DESC, null, null)
        }
        for (t in 0 until ir.totalTableCount) {
            cn.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, AotNames.tableFieldName(t), AotNames.WASM_TABLE_DESC, null, null)
        }
        for (g in 0 until ir.totalGlobalCount) {
            val gt = ir.globalTypeOrNull(g)!!
            val fName = AotNames.globalFieldName(g)
            val acc = if (g < ir.importedGlobals.size && sharedGlobals[g]) {
                Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL
            } else {
                Opcodes.ACC_PUBLIC
            }
            if (sharedGlobals[g]) {
                cn.visitField(acc, fName, AotNames.boxedGlobalDesc(gt.valueType), null, null)
            } else {
                cn.visitField(Opcodes.ACC_PUBLIC, fName, AotNames.jvmDesc(gt.valueType), null, null)
            }
        }
        if (importsIfaceInternal != null) {
            cn.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "imports", "L$importsIfaceInternal;", null, null)
        }
        if (ir.importedFunctions.isNotEmpty()) {
            cn.visitField(Opcodes.ACC_PUBLIC, "importedFuncRefs", "[${AotNames.FUNC_REF_DESC}", null, null)
        }
        if (hasFuncRefs) {
            cn.visitField(Opcodes.ACC_PUBLIC, "funcRefs", "[${AotNames.FUNC_REF_DESC}", null, null)
        }
        if (hasElemSegments) {
            cn.visitField(Opcodes.ACC_PUBLIC, "elemSegments", AotNames.ELEM_STORE_DESC, null, null)
        }
        if (hasDataSegments) {
            cn.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "dataSegments", AotNames.DATA_STORE_DESC, null, null)
        }
        if (needsSpillField) {
            cn.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "spill", AotNames.SPILL_FRAME_DESC, null, null)
        }
        for ((key, results) in multiValueTupleTypes) {
            if (results.none { AotNames.isRef(it) }) {
                val tClass = AotNames.multiValueInternalName(instanceInternalName, results)
                cn.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "mv_$key", "L$tClass;", null, null)
            }
        }

        // Static import bridges on CoreNInstance.
        if (importsIfaceInternal != null) {
            emitImportBridgeMethods(cn, ir, instanceInternalName, importsIfaceInternal)
        }

        // Constructors.
        emitConstructors(
            cn = cn,
            ir = ir,
            instanceInternalName = instanceInternalName,
            importsIfaceInternal = importsIfaceInternal,
            resolvedDataPath = resolvedDataPath,
            sharedGlobals = sharedGlobals,
            indirectlyCallableFuncs = indirectlyCallableFuncs,
            hasElemSegments = hasElemSegments,
            hasDataSegments = hasDataSegments,
            hasFuncRefs = hasFuncRefs,
            needsSpillField = needsSpillField,
            maxSpill = maxSpill,
            multiValueTupleTypes = multiValueTupleTypes,
            funcTargets = funcTargets,
        )

        // CoreModuleInstance.invokeIndirect(int funcIndex, Object[] args): Object[]
        emitInvokeIndirect(
            cn = cn,
            ir = ir,
            instanceInternalName = instanceInternalName,
            indirectlyCallableFuncs = indirectlyCallableFuncs,
            funcTargets = funcTargets,
            maxBucketSize = minOf(64, options.maxDispatchBucketSize),
        )

        return cn
    }

    private fun emitImportBridgeMethods(
        cn: ClassNode,
        ir: IrModule,
        instanceInternalName: String,
        importsIfaceInternal: String,
    ) {
        for (imp in ir.importedFunctions) {
            val ft = ir.types[imp.typeIndex]
            val directCount = AotNames.countDirectParams(ft.params)
            val bridgeDesc = AotNames.staticFuncDescriptor(instanceInternalName, ft, directCount)
            val mn = MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, AotNames.importBridgeName(imp.funcIndex), bridgeDesc, null, null)
            val insns = mn.instructions
            var nextSlot = 0
            val pSlots = IntArray(directCount)
            for (i in 0 until directCount) {
                pSlots[i] = nextSlot
                nextSlot += AotNames.slotSize(ft.params[i])
            }
            val instSlot = nextSlot++

            insns.add(VarInsnNode(Opcodes.ALOAD, instSlot))
            insns.add(FieldInsnNode(Opcodes.GETFIELD, instanceInternalName, "imports", "L$importsIfaceInternal;"))
            for (i in 0 until directCount) {
                insns.add(VarInsnNode(AotNames.loadOpcode(ft.params[i]), pSlots[i]))
            }
            if (directCount < ft.params.size) {
                insns.add(VarInsnNode(Opcodes.ALOAD, instSlot))
                insns.add(FieldInsnNode(Opcodes.GETFIELD, instanceInternalName, "spill", AotNames.SPILL_FRAME_DESC))
            }
            val ifaceMethodDesc = AotNames.importMethodDesc(ft.params, ft.results, instanceInternalName)
            insns.add(MethodInsnNode(Opcodes.INVOKEINTERFACE, importsIfaceInternal, AotNames.importBridgeName(imp.funcIndex), ifaceMethodDesc, true))
            emitReturnOpcodeFor(insns, ft.results)
            mn.maxStack = 64
            mn.maxLocals = nextSlot
            cn.methods.add(mn)
        }
    }

    private fun emitConstructors(
        cn: ClassNode,
        ir: IrModule,
        instanceInternalName: String,
        importsIfaceInternal: String?,
        resolvedDataPath: String?,
        sharedGlobals: BooleanArray,
        indirectlyCallableFuncs: Set<Int>,
        hasElemSegments: Boolean,
        hasDataSegments: Boolean,
        hasFuncRefs: Boolean,
        needsSpillField: Boolean,
        maxSpill: SpillCounts,
        multiValueTupleTypes: Map<String, List<IrValType>>,
        funcTargets: Map<Int, CompiledFunctionTarget>,
    ) {
        // Compute base constructor parameter descriptors (memories, tables, globals, imports).
        val ctorParams = mutableListOf<String>()
        for (m in ir.importedMemories) ctorParams += AotNames.LINEAR_MEMORY_DESC
        for (t in ir.importedTables) ctorParams += AotNames.WASM_TABLE_DESC
        for (g in ir.importedGlobals) {
            if (g.globalType.mutability == IrMutability.VAR) {
                ctorParams += AotNames.boxedGlobalDesc(g.globalType.valueType)
            } else {
                ctorParams += AotNames.jvmDesc(g.globalType.valueType)
            }
        }
        if (importsIfaceInternal != null) ctorParams += "L$importsIfaceInternal;"

        val primaryDesc = "(${ctorParams.joinToString("")})V"
        if (ir.importedFunctions.isNotEmpty()) {
            // Primary convenience ctor delegates to full ctor with null FuncRef[].
            val fullDesc = "(${ctorParams.joinToString("")}[${AotNames.FUNC_REF_DESC})V"
            val convenience = MethodNode(Opcodes.ACC_PUBLIC, "<init>", primaryDesc, null, null)
            val ci = convenience.instructions
            ci.add(VarInsnNode(Opcodes.ALOAD, 0))
            var slot = 1
            for (pDesc in ctorParams) {
                val loadOp = when (pDesc) {
                    "I" -> Opcodes.ILOAD
                    "J" -> Opcodes.LLOAD
                    "F" -> Opcodes.FLOAD
                    "D" -> Opcodes.DLOAD
                    else -> Opcodes.ALOAD
                }
                val step = if (pDesc == "J" || pDesc == "D") 2 else 1
                ci.add(VarInsnNode(loadOp, slot))
                slot += step
            }
            ci.add(InsnNode(Opcodes.ACONST_NULL))
            ci.add(MethodInsnNode(Opcodes.INVOKESPECIAL, instanceInternalName, "<init>", fullDesc, false))
            ci.add(InsnNode(Opcodes.RETURN))
            convenience.maxStack = slot + 4
            convenience.maxLocals = slot + 1
            cn.methods.add(convenience)

            emitMainConstructor(
                cn, ir, instanceInternalName, importsIfaceInternal, resolvedDataPath,
                sharedGlobals, indirectlyCallableFuncs, hasElemSegments, hasDataSegments,
                hasFuncRefs, needsSpillField, maxSpill, multiValueTupleTypes, funcTargets,
                ctorParams, fullDesc, hasImportedFuncRefsParam = true,
            )
        } else {
            emitMainConstructor(
                cn, ir, instanceInternalName, importsIfaceInternal, resolvedDataPath,
                sharedGlobals, indirectlyCallableFuncs, hasElemSegments, hasDataSegments,
                hasFuncRefs, needsSpillField, maxSpill, multiValueTupleTypes, funcTargets,
                ctorParams, primaryDesc, hasImportedFuncRefsParam = false,
            )
        }
    }

    private fun emitMainConstructor(
        cn: ClassNode,
        ir: IrModule,
        instanceInternalName: String,
        importsIfaceInternal: String?,
        resolvedDataPath: String?,
        sharedGlobals: BooleanArray,
        indirectlyCallableFuncs: Set<Int>,
        hasElemSegments: Boolean,
        hasDataSegments: Boolean,
        hasFuncRefs: Boolean,
        needsSpillField: Boolean,
        maxSpill: SpillCounts,
        multiValueTupleTypes: Map<String, List<IrValType>>,
        funcTargets: Map<Int, CompiledFunctionTarget>,
        baseCtorParams: List<String>,
        ctorDesc: String,
        hasImportedFuncRefsParam: Boolean,
    ) {
        val init = MethodNode(Opcodes.ACC_PUBLIC, "<init>", ctorDesc, null, null)
        val insns = init.instructions

        insns.add(VarInsnNode(Opcodes.ALOAD, 0))
        insns.add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false))

        var slot = 1
        // 1. Imported memories
        for (m in ir.importedMemories) {
            insns.add(VarInsnNode(Opcodes.ALOAD, 0))
            insns.add(VarInsnNode(Opcodes.ALOAD, slot++))
            insns.add(FieldInsnNode(Opcodes.PUTFIELD, instanceInternalName, AotNames.memoryFieldName(m.memoryIndex), AotNames.LINEAR_MEMORY_DESC))
        }
        // Defined memories
        for (m in ir.definedMemories) {
            insns.add(VarInsnNode(Opcodes.ALOAD, 0))
            insns.add(TypeInsnNode(Opcodes.NEW, AotNames.LINEAR_MEMORY_INTERNAL))
            insns.add(InsnNode(Opcodes.DUP))
            WasmBytecodeEmitter.pushInt(insns, m.type.initialPages)
            val maxP = m.type.maxPages
            if (maxP != null) {
                WasmBytecodeEmitter.pushInt(insns, maxP)
                insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false))
            } else {
                insns.add(InsnNode(Opcodes.ACONST_NULL))
            }
            insns.add(InsnNode(Opcodes.ICONST_0))
            insns.add(TypeInsnNode(Opcodes.ANEWARRAY, "[B"))
            insns.add(MethodInsnNode(Opcodes.INVOKESPECIAL, AotNames.LINEAR_MEMORY_INTERNAL, "<init>", "(ILjava/lang/Integer;[[B)V", false))
            insns.add(FieldInsnNode(Opcodes.PUTFIELD, instanceInternalName, AotNames.memoryFieldName(m.index), AotNames.LINEAR_MEMORY_DESC))
        }

        // 2. Imported tables
        for (t in ir.importedTables) {
            insns.add(VarInsnNode(Opcodes.ALOAD, 0))
            insns.add(VarInsnNode(Opcodes.ALOAD, slot++))
            insns.add(FieldInsnNode(Opcodes.PUTFIELD, instanceInternalName, AotNames.tableFieldName(t.tableIndex), AotNames.WASM_TABLE_DESC))
        }
        // Defined tables
        for (t in ir.definedTables) {
            insns.add(VarInsnNode(Opcodes.ALOAD, 0))
            insns.add(TypeInsnNode(Opcodes.NEW, AotNames.WASM_TABLE_INTERNAL))
            insns.add(InsnNode(Opcodes.DUP))
            WasmBytecodeEmitter.pushInt(insns, t.type.limits.min.toInt())
            // Clamp Wasm u32 table max limits (up to 0xFFFF_FFFF) to WasmTable.MAX_TABLE_SIZE on JVM.
            val maxT = t.type.limits.max?.let { minOf(it, (Int.MAX_VALUE - 8).toLong()).toInt() }
            if (maxT != null) {
                WasmBytecodeEmitter.pushInt(insns, maxT)
                insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false))
            } else {
                insns.add(InsnNode(Opcodes.ACONST_NULL))
            }
            insns.add(InsnNode(Opcodes.ACONST_NULL))
            insns.add(MethodInsnNode(Opcodes.INVOKESPECIAL, AotNames.WASM_TABLE_INTERNAL, "<init>", "(ILjava/lang/Integer;Ljava/lang/Object;)V", false))
            insns.add(FieldInsnNode(Opcodes.PUTFIELD, instanceInternalName, AotNames.tableFieldName(t.index), AotNames.WASM_TABLE_DESC))
        }

        // 3. Imported globals
        for (g in ir.importedGlobals) {
            val isVar = g.globalType.mutability == IrMutability.VAR
            val vt = g.globalType.valueType
            val desc = if (isVar) AotNames.boxedGlobalDesc(vt) else AotNames.jvmDesc(vt)
            val loadOp = if (isVar) Opcodes.ALOAD else AotNames.loadOpcode(vt)
            val step = if (!isVar && (vt == IrValType.I64 || vt == IrValType.F64)) 2 else 1
            insns.add(VarInsnNode(Opcodes.ALOAD, 0))
            insns.add(VarInsnNode(loadOp, slot))
            insns.add(FieldInsnNode(Opcodes.PUTFIELD, instanceInternalName, AotNames.globalFieldName(g.globalIndex), desc))
            slot += step
        }

        // 4. Imports & importedFuncRefs
        if (importsIfaceInternal != null) {
            insns.add(VarInsnNode(Opcodes.ALOAD, 0))
            insns.add(VarInsnNode(Opcodes.ALOAD, slot++))
            insns.add(FieldInsnNode(Opcodes.PUTFIELD, instanceInternalName, "imports", "L$importsIfaceInternal;"))
        }
        val impFuncRefsArgSlot = if (hasImportedFuncRefsParam) slot else -1

        // 5. Spill & reusable primitive MV tuple holders
        if (needsSpillField) {
            insns.add(VarInsnNode(Opcodes.ALOAD, 0))
            insns.add(TypeInsnNode(Opcodes.NEW, AotNames.SPILL_FRAME_INTERNAL))
            insns.add(InsnNode(Opcodes.DUP))
            WasmBytecodeEmitter.pushInt(insns, maxSpill.i32)
            WasmBytecodeEmitter.pushInt(insns, maxSpill.i64)
            WasmBytecodeEmitter.pushInt(insns, maxSpill.f32)
            WasmBytecodeEmitter.pushInt(insns, maxSpill.f64)
            WasmBytecodeEmitter.pushInt(insns, maxSpill.ref)
            insns.add(MethodInsnNode(Opcodes.INVOKESPECIAL, AotNames.SPILL_FRAME_INTERNAL, "<init>", "(IIIII)V", false))
            insns.add(FieldInsnNode(Opcodes.PUTFIELD, instanceInternalName, "spill", AotNames.SPILL_FRAME_DESC))
        }
        for ((key, results) in multiValueTupleTypes) {
            if (results.none { AotNames.isRef(it) }) {
                val tClass = AotNames.multiValueInternalName(instanceInternalName, results)
                insns.add(VarInsnNode(Opcodes.ALOAD, 0))
                insns.add(TypeInsnNode(Opcodes.NEW, tClass))
                insns.add(InsnNode(Opcodes.DUP))
                insns.add(MethodInsnNode(Opcodes.INVOKESPECIAL, tClass, "<init>", "()V", false))
                insns.add(FieldInsnNode(Opcodes.PUTFIELD, instanceInternalName, "mv_$key", "L$tClass;"))
            }
        }

        // 6. Initialize importedFuncRefs & funcRefs (chunked)
        if (ir.importedFunctions.isNotEmpty()) {
            val helper = emitInitImportedFuncRefs(cn, ir, instanceInternalName)
            insns.add(VarInsnNode(Opcodes.ALOAD, 0))
            insns.add(VarInsnNode(Opcodes.ALOAD, impFuncRefsArgSlot))
            insns.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, instanceInternalName, helper, "([${AotNames.FUNC_REF_DESC})V", false))
        }
        if (hasFuncRefs) {
            val refFuncsToBuild = indirectlyCallableFuncs.toList()
            val helperCount = emitInitFuncRefsHelpers(cn, ir, instanceInternalName, refFuncsToBuild)
            insns.add(VarInsnNode(Opcodes.ALOAD, 0))
            WasmBytecodeEmitter.pushInt(insns, ir.totalFunctionCount)
            insns.add(TypeInsnNode(Opcodes.ANEWARRAY, AotNames.FUNC_REF_INTERNAL))
            insns.add(FieldInsnNode(Opcodes.PUTFIELD, instanceInternalName, "funcRefs", "[${AotNames.FUNC_REF_DESC}"))
            for (hIdx in 0 until helperCount) {
                insns.add(VarInsnNode(Opcodes.ALOAD, 0))
                insns.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, instanceInternalName, "initFuncRefs_$hIdx", "()V", false))
            }
        }

        // 7. Defined globals (chunked every 80 globals)
        if (ir.definedGlobals.isNotEmpty()) {
            val chunks = ir.definedGlobals.chunked(80)
            for ((cIdx, chunk) in chunks.withIndex()) {
                val mName = "initGlobals_$cIdx"
                val gm = MethodNode(Opcodes.ACC_PRIVATE, mName, "()V", null, null)
                val gi = gm.instructions
                for (g in chunk) {
                    val fName = AotNames.globalFieldName(g.index)
                    val vt = g.type.valueType
                    if (sharedGlobals[g.index]) {
                        val boxInternal = AotNames.boxedGlobalInternalName(vt)
                        gi.add(VarInsnNode(Opcodes.ALOAD, 0))
                        gi.add(TypeInsnNode(Opcodes.NEW, boxInternal))
                        gi.add(InsnNode(Opcodes.DUP))
                        emitConstExpr(gi, g.initExpr, ir, instanceInternalName, sharedGlobals)
                        gi.add(MethodInsnNode(Opcodes.INVOKESPECIAL, boxInternal, "<init>", "(${AotNames.jvmDesc(vt)})V", false))
                        gi.add(FieldInsnNode(Opcodes.PUTFIELD, instanceInternalName, fName, "L$boxInternal;"))
                    } else {
                        gi.add(VarInsnNode(Opcodes.ALOAD, 0))
                        emitConstExpr(gi, g.initExpr, ir, instanceInternalName, sharedGlobals)
                        gi.add(FieldInsnNode(Opcodes.PUTFIELD, instanceInternalName, fName, AotNames.jvmDesc(vt)))
                    }
                }
                gi.add(InsnNode(Opcodes.RETURN))
                gm.maxStack = 32
                gm.maxLocals = 1
                cn.methods.add(gm)

                insns.add(VarInsnNode(Opcodes.ALOAD, 0))
                insns.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, instanceInternalName, mName, "()V", false))
            }
        }

        // 8. Elem segments & active elem patching (chunked)
        if (hasElemSegments) {
            emitElemInitialization(cn, ir, instanceInternalName, sharedGlobals, insns)
        }

        // 9. Data segments resource load & active data initialization (chunked)
        if (hasDataSegments) {
            emitDataInitialization(cn, ir, instanceInternalName, resolvedDataPath, sharedGlobals, insns)
        }

        // 10. Start function
        val startFn = ir.startFunctionIndex
        if (startFn != null) {
            val target = funcTargets.getValue(startFn)
            insns.add(VarInsnNode(Opcodes.ALOAD, 0))
            insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, target.ownerInternalName, target.methodName, target.methodDescriptor, false))
        }

        insns.add(InsnNode(Opcodes.RETURN))
        init.maxStack = 64
        init.maxLocals = slot + 4
        cn.methods.add(init)
    }

    private fun emitInitImportedFuncRefs(
        cn: ClassNode,
        ir: IrModule,
        instanceInternalName: String,
    ): String {
        val mName = "initImportedFuncRefs"
        val mn = MethodNode(Opcodes.ACC_PRIVATE, mName, "([${AotNames.FUNC_REF_DESC})V", null, null)
        val insns = mn.instructions
        val arrSlot = 2
        WasmBytecodeEmitter.pushInt(insns, ir.importedFunctions.size)
        insns.add(TypeInsnNode(Opcodes.ANEWARRAY, AotNames.FUNC_REF_INTERNAL))
        insns.add(VarInsnNode(Opcodes.ASTORE, arrSlot))

        for ((idx, imp) in ir.importedFunctions.withIndex()) {
            val sig = AotNames.canonicalSignature(ir.types[imp.typeIndex])
            val skipCopy = LabelNode()
            val setFallback = LabelNode()
            val after = LabelNode()

            insns.add(VarInsnNode(Opcodes.ALOAD, 1))
            insns.add(JumpInsnNode(Opcodes.IFNULL, setFallback))
            insns.add(VarInsnNode(Opcodes.ALOAD, 1))
            WasmBytecodeEmitter.pushInt(insns, idx)
            insns.add(InsnNode(Opcodes.AALOAD))
            insns.add(JumpInsnNode(Opcodes.IFNULL, setFallback))

            insns.add(VarInsnNode(Opcodes.ALOAD, arrSlot))
            WasmBytecodeEmitter.pushInt(insns, idx)
            insns.add(VarInsnNode(Opcodes.ALOAD, 1))
            WasmBytecodeEmitter.pushInt(insns, idx)
            insns.add(InsnNode(Opcodes.AALOAD))
            insns.add(InsnNode(Opcodes.AASTORE))
            insns.add(JumpInsnNode(Opcodes.GOTO, after))

            insns.add(setFallback)
            insns.add(VarInsnNode(Opcodes.ALOAD, arrSlot))
            WasmBytecodeEmitter.pushInt(insns, idx)
            insns.add(VarInsnNode(Opcodes.ALOAD, 0))
            WasmBytecodeEmitter.pushInt(insns, imp.funcIndex)
            insns.add(LdcInsnNode(sig))
            insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, AotNames.FUNC_REF_INTERNAL, "core", "(Ljava/lang/Object;ILjava/lang/String;)L${AotNames.FUNC_REF_INTERNAL}\$Core;", false))
            insns.add(InsnNode(Opcodes.AASTORE))
            insns.add(after)
        }
        insns.add(VarInsnNode(Opcodes.ALOAD, 0))
        insns.add(VarInsnNode(Opcodes.ALOAD, arrSlot))
        insns.add(FieldInsnNode(Opcodes.PUTFIELD, instanceInternalName, "importedFuncRefs", "[${AotNames.FUNC_REF_DESC}"))
        insns.add(InsnNode(Opcodes.RETURN))
        mn.maxStack = 16
        mn.maxLocals = 4
        cn.methods.add(mn)
        return mName
    }

    private fun emitInitFuncRefsHelpers(
        cn: ClassNode,
        ir: IrModule,
        instanceInternalName: String,
        funcIndices: List<Int>,
    ): Int {
        if (funcIndices.isEmpty()) return 0
        val chunks = funcIndices.chunked(100)
        for ((cIdx, chunk) in chunks.withIndex()) {
            val mn = MethodNode(Opcodes.ACC_PRIVATE, "initFuncRefs_$cIdx", "()V", null, null)
            val insns = mn.instructions
            insns.add(VarInsnNode(Opcodes.ALOAD, 0))
            insns.add(FieldInsnNode(Opcodes.GETFIELD, instanceInternalName, "funcRefs", "[${AotNames.FUNC_REF_DESC}"))
            insns.add(VarInsnNode(Opcodes.ASTORE, 1))
            for (fnIdx in chunk) {
                insns.add(VarInsnNode(Opcodes.ALOAD, 1))
                WasmBytecodeEmitter.pushInt(insns, fnIdx)
                if (fnIdx < ir.importedFunctions.size) {
                    insns.add(VarInsnNode(Opcodes.ALOAD, 0))
                    insns.add(FieldInsnNode(Opcodes.GETFIELD, instanceInternalName, "importedFuncRefs", "[${AotNames.FUNC_REF_DESC}"))
                    WasmBytecodeEmitter.pushInt(insns, fnIdx)
                    insns.add(InsnNode(Opcodes.AALOAD))
                } else {
                    val sig = AotNames.canonicalSignature(ir.functionTypeOrNull(fnIdx)!!)
                    insns.add(VarInsnNode(Opcodes.ALOAD, 0))
                    WasmBytecodeEmitter.pushInt(insns, fnIdx)
                    insns.add(LdcInsnNode(sig))
                    insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, AotNames.FUNC_REF_INTERNAL, "core", "(Ljava/lang/Object;ILjava/lang/String;)L${AotNames.FUNC_REF_INTERNAL}\$Core;", false))
                }
                insns.add(InsnNode(Opcodes.AASTORE))
            }
            insns.add(InsnNode(Opcodes.RETURN))
            mn.maxStack = 12
            mn.maxLocals = 2
            cn.methods.add(mn)
        }
        return chunks.size
    }

    private fun emitElemInitialization(
        cn: ClassNode,
        ir: IrModule,
        instanceInternalName: String,
        sharedGlobals: BooleanArray,
        initInsns: InsnList,
    ) {
        val segHelperNames = mutableListOf<String>()
        for (seg in ir.elements) {
            if (seg is IrElementSegment.Declarative || seg.items.isEmpty()) {
                continue
            }
            val itemChunks = seg.items.chunked(100)
            for ((chunkIdx, chunk) in itemChunks.withIndex()) {
                val baseItemIdx = chunkIdx * 100
                val mName = "fillElem_${seg.index}_$chunkIdx"
                segHelperNames += mName
                val mn = MethodNode(Opcodes.ACC_PRIVATE, mName, "([Ljava/lang/Object;)V", null, null)
                val insns = mn.instructions
                for ((localOffset, itemExpr) in chunk.withIndex()) {
                    insns.add(VarInsnNode(Opcodes.ALOAD, 1))
                    WasmBytecodeEmitter.pushInt(insns, baseItemIdx + localOffset)
                    emitConstExpr(insns, itemExpr, ir, instanceInternalName, sharedGlobals)
                    insns.add(InsnNode(Opcodes.AASTORE))
                }
                insns.add(InsnNode(Opcodes.RETURN))
                mn.maxStack = 16
                mn.maxLocals = 2
                cn.methods.add(mn)
            }
        }

        // Helper that creates ElemSegmentStore and applies active segments in chunks.
        val setupName = "initElemSegments"
        val setup = MethodNode(Opcodes.ACC_PRIVATE, setupName, "()V", null, null)
        val si = setup.instructions
        WasmBytecodeEmitter.pushInt(si, ir.elements.size)
        si.add(TypeInsnNode(Opcodes.ANEWARRAY, "[Ljava/lang/Object;"))
        si.add(VarInsnNode(Opcodes.ASTORE, 1))

        var helperCursor = 0
        for (seg in ir.elements) {
            if (seg is IrElementSegment.Declarative || seg.items.isEmpty()) {
                si.add(VarInsnNode(Opcodes.ALOAD, 1))
                WasmBytecodeEmitter.pushInt(si, seg.index)
                si.add(InsnNode(Opcodes.ICONST_0))
                si.add(TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"))
                si.add(InsnNode(Opcodes.AASTORE))
                continue
            }
            val chunkCount = (seg.items.size + 99) / 100
            WasmBytecodeEmitter.pushInt(si, seg.items.size)
            si.add(TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"))
            si.add(VarInsnNode(Opcodes.ASTORE, 2))
            repeat(chunkCount) {
                val mName = segHelperNames[helperCursor++]
                si.add(VarInsnNode(Opcodes.ALOAD, 0))
                si.add(VarInsnNode(Opcodes.ALOAD, 2))
                si.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, instanceInternalName, mName, "([Ljava/lang/Object;)V", false))
            }
            si.add(VarInsnNode(Opcodes.ALOAD, 1))
            WasmBytecodeEmitter.pushInt(si, seg.index)
            si.add(VarInsnNode(Opcodes.ALOAD, 2))
            si.add(InsnNode(Opcodes.AASTORE))
        }

        si.add(VarInsnNode(Opcodes.ALOAD, 0))
        si.add(TypeInsnNode(Opcodes.NEW, AotNames.ELEM_STORE_INTERNAL))
        si.add(InsnNode(Opcodes.DUP))
        si.add(VarInsnNode(Opcodes.ALOAD, 1))
        si.add(MethodInsnNode(Opcodes.INVOKESPECIAL, AotNames.ELEM_STORE_INTERNAL, "<init>", "([[Ljava/lang/Object;)V", false))
        si.add(FieldInsnNode(Opcodes.PUTFIELD, instanceInternalName, "elemSegments", AotNames.ELEM_STORE_DESC))

        val activeElems = ir.elements.filterIsInstance<IrElementSegment.Active>()
        for (activeChunk in activeElems.chunked(60)) {
            val actName = "applyActiveElems_${cn.methods.size}"
            val am = MethodNode(Opcodes.ACC_PRIVATE, actName, "()V", null, null)
            val ai = am.instructions
            for (seg in activeChunk) {
                ai.add(VarInsnNode(Opcodes.ALOAD, 0))
                ai.add(FieldInsnNode(Opcodes.GETFIELD, instanceInternalName, "elemSegments", AotNames.ELEM_STORE_DESC))
                ai.add(VarInsnNode(Opcodes.ALOAD, 0))
                ai.add(FieldInsnNode(Opcodes.GETFIELD, instanceInternalName, AotNames.tableFieldName(seg.tableIndex), AotNames.WASM_TABLE_DESC))
                WasmBytecodeEmitter.pushInt(ai, seg.index)
                emitConstExpr(ai, seg.offset, ir, instanceInternalName, sharedGlobals)
                ai.add(InsnNode(Opcodes.ICONST_0))
                WasmBytecodeEmitter.pushInt(ai, seg.items.size)
                ai.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, AotNames.ELEM_STORE_INTERNAL, "init", "(${AotNames.WASM_TABLE_DESC}IIII)V", false))

                ai.add(VarInsnNode(Opcodes.ALOAD, 0))
                ai.add(FieldInsnNode(Opcodes.GETFIELD, instanceInternalName, "elemSegments", AotNames.ELEM_STORE_DESC))
                WasmBytecodeEmitter.pushInt(ai, seg.index)
                ai.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, AotNames.ELEM_STORE_INTERNAL, "drop", "(I)V", false))
            }
            ai.add(InsnNode(Opcodes.RETURN))
            am.maxStack = 16
            am.maxLocals = 1
            cn.methods.add(am)

            si.add(VarInsnNode(Opcodes.ALOAD, 0))
            si.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, instanceInternalName, actName, "()V", false))
        }

        si.add(InsnNode(Opcodes.RETURN))
        setup.maxStack = 16
        setup.maxLocals = 3
        cn.methods.add(setup)

        initInsns.add(VarInsnNode(Opcodes.ALOAD, 0))
        initInsns.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, instanceInternalName, setupName, "()V", false))
    }

    private fun emitDataInitialization(
        cn: ClassNode,
        ir: IrModule,
        instanceInternalName: String,
        resolvedDataPath: String?,
        sharedGlobals: BooleanArray,
        initInsns: InsnList,
    ) {
        val resourcePath = requireNotNull(resolvedDataPath) { "Missing dataResourcePath for module ${ir.moduleIndex}" }
        initInsns.add(VarInsnNode(Opcodes.ALOAD, 0))
        initInsns.add(LdcInsnNode(org.objectweb.asm.Type.getObjectType(instanceInternalName)))
        initInsns.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false))
        initInsns.add(LdcInsnNode(resourcePath))
        initInsns.add(MethodInsnNode(Opcodes.INVOKESTATIC, AotNames.DATA_STORE_INTERNAL, "load", "(Ljava/lang/ClassLoader;Ljava/lang/String;)${AotNames.DATA_STORE_DESC}", false))
        initInsns.add(FieldInsnNode(Opcodes.PUTFIELD, instanceInternalName, "dataSegments", AotNames.DATA_STORE_DESC))

        val activeData = ir.dataSegments.filterIsInstance<IrDataSegment.Active>()
        for ((idx, chunk) in activeData.chunked(80).withIndex()) {
            val mName = "applyActiveData_$idx"
            val mn = MethodNode(Opcodes.ACC_PRIVATE, mName, "()V", null, null)
            val di = mn.instructions
            for (seg in chunk) {
                di.add(VarInsnNode(Opcodes.ALOAD, 0))
                di.add(FieldInsnNode(Opcodes.GETFIELD, instanceInternalName, "dataSegments", AotNames.DATA_STORE_DESC))
                di.add(VarInsnNode(Opcodes.ALOAD, 0))
                di.add(FieldInsnNode(Opcodes.GETFIELD, instanceInternalName, AotNames.memoryFieldName(seg.memoryIndex), AotNames.LINEAR_MEMORY_DESC))
                WasmBytecodeEmitter.pushInt(di, seg.index)
                emitConstExpr(di, seg.offset, ir, instanceInternalName, sharedGlobals)
                di.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, AotNames.DATA_STORE_INTERNAL, "initActive", "(${AotNames.LINEAR_MEMORY_DESC}II)V", false))
            }
            di.add(InsnNode(Opcodes.RETURN))
            mn.maxStack = 16
            mn.maxLocals = 1
            cn.methods.add(mn)

            initInsns.add(VarInsnNode(Opcodes.ALOAD, 0))
            initInsns.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, instanceInternalName, mName, "()V", false))
        }
    }

    private fun emitConstExpr(
        insns: InsnList,
        expr: IrConstExpr,
        ir: IrModule,
        instanceInternalName: String,
        sharedGlobals: BooleanArray,
    ) {
        for (op in expr.ops) {
            when (op) {
                is IrConstOp.I32Const -> WasmBytecodeEmitter.pushInt(insns, op.value)
                is IrConstOp.I64Const -> WasmBytecodeEmitter.pushLong(insns, op.value)
                is IrConstOp.F32Const -> WasmBytecodeEmitter.pushF32Bits(insns, op.rawBits)
                is IrConstOp.F64Const -> WasmBytecodeEmitter.pushF64Bits(insns, op.rawBits)
                is IrConstOp.RefNull -> insns.add(InsnNode(Opcodes.ACONST_NULL))
                is IrConstOp.RefFunc -> {
                    insns.add(VarInsnNode(Opcodes.ALOAD, 0))
                    insns.add(FieldInsnNode(Opcodes.GETFIELD, instanceInternalName, "funcRefs", "[${AotNames.FUNC_REF_DESC}"))
                    WasmBytecodeEmitter.pushInt(insns, op.funcIndex)
                    insns.add(InsnNode(Opcodes.AALOAD))
                }
                is IrConstOp.GlobalGet -> {
                    val fName = AotNames.globalFieldName(op.globalIndex)
                    insns.add(VarInsnNode(Opcodes.ALOAD, 0))
                    if (sharedGlobals[op.globalIndex]) {
                        val boxInternal = AotNames.boxedGlobalInternalName(op.valType)
                        insns.add(FieldInsnNode(Opcodes.GETFIELD, instanceInternalName, fName, "L$boxInternal;"))
                        insns.add(FieldInsnNode(Opcodes.GETFIELD, boxInternal, "value", AotNames.jvmDesc(op.valType)))
                    } else {
                        insns.add(FieldInsnNode(Opcodes.GETFIELD, instanceInternalName, fName, AotNames.jvmDesc(op.valType)))
                    }
                }
                IrConstOp.I32Add -> insns.add(InsnNode(Opcodes.IADD))
                IrConstOp.I32Sub -> insns.add(InsnNode(Opcodes.ISUB))
                IrConstOp.I32Mul -> insns.add(InsnNode(Opcodes.IMUL))
                IrConstOp.I64Add -> insns.add(InsnNode(Opcodes.LADD))
                IrConstOp.I64Sub -> insns.add(InsnNode(Opcodes.LSUB))
                IrConstOp.I64Mul -> insns.add(InsnNode(Opcodes.LMUL))
            }
        }
    }

    // ---------------------------------------------------------------- CoreModuleInstance.invokeIndirect

    private fun emitInvokeIndirect(
        cn: ClassNode,
        ir: IrModule,
        instanceInternalName: String,
        indirectlyCallableFuncs: Set<Int>,
        funcTargets: Map<Int, CompiledFunctionTarget>,
        maxBucketSize: Int,
    ) {
        val desc = "(I[Ljava/lang/Object;)[Ljava/lang/Object;"
        val mn = MethodNode(Opcodes.ACC_PUBLIC, "invokeIndirect", desc, null, null)
        val insns = mn.instructions
        val sorted = indirectlyCallableFuncs.sorted()
        if (sorted.isEmpty()) {
            emitTrapInstruction(insns, "module has no indirectly callable functions")
            mn.maxStack = 4
            mn.maxLocals = 3
            cn.methods.add(mn)
            return
        }

        if (sorted.size <= maxBucketSize) {
            emitInvokeIndirectLeafSwitch(insns, ir, instanceInternalName, sorted, funcTargets)
        } else {
            val helperCounter = intArrayOf(0)
            val chunks = sorted.chunked(maxBucketSize).map { chunk ->
                val hName = "invokeIndirect\$b${helperCounter[0]++}"
                val hm = MethodNode(Opcodes.ACC_PRIVATE, hName, desc, null, null)
                emitInvokeIndirectLeafSwitch(hm.instructions, ir, instanceInternalName, chunk, funcTargets)
                hm.maxStack = 64
                hm.maxLocals = 6
                cn.methods.add(hm)
                ChunkTarget(chunk.first(), chunk.last(), instanceInternalName, hName)
            }
            emitInvokeIndirectRouter(cn, instanceInternalName, desc, chunks, insns, maxFanout = maxBucketSize, helperCounter = helperCounter)
        }
        mn.maxStack = 64
        mn.maxLocals = 6
        cn.methods.add(mn)
    }

    private fun emitInvokeIndirectRouter(
        cn: ClassNode,
        instanceInternalName: String,
        desc: String,
        chunks: List<ChunkTarget>,
        insns: InsnList,
        maxFanout: Int,
        helperCounter: IntArray,
    ) {
        if (chunks.size > maxFanout) {
            val grouped = chunks.chunked(maxFanout).map { sub ->
                val hName = "invokeIndirect\$r${helperCounter[0]++}"
                val hm = MethodNode(Opcodes.ACC_PRIVATE, hName, desc, null, null)
                emitInvokeIndirectRouter(cn, instanceInternalName, desc, sub, hm.instructions, maxFanout, helperCounter)
                hm.maxStack = 16
                hm.maxLocals = 3
                cn.methods.add(hm)
                ChunkTarget(sub.first().minFunc, sub.last().maxFunc, instanceInternalName, hName)
            }
            emitInvokeIndirectRouter(cn, instanceInternalName, desc, grouped, insns, maxFanout, helperCounter)
            return
        }
        fun emitTree(lo: Int, hi: Int) {
            if (lo == hi) {
                val c = chunks[lo]
                insns.add(VarInsnNode(Opcodes.ALOAD, 0))
                insns.add(VarInsnNode(Opcodes.ILOAD, 1))
                insns.add(VarInsnNode(Opcodes.ALOAD, 2))
                insns.add(MethodInsnNode(Opcodes.INVOKESPECIAL, instanceInternalName, c.methodName, desc, false))
                insns.add(InsnNode(Opcodes.ARETURN))
                return
            }
            val mid = (lo + hi) ushr 1
            val rightLbl = LabelNode()
            insns.add(VarInsnNode(Opcodes.ILOAD, 1))
            WasmBytecodeEmitter.pushInt(insns, chunks[mid].maxFunc)
            insns.add(JumpInsnNode(Opcodes.IF_ICMPGT, rightLbl))
            emitTree(lo, mid)
            insns.add(rightLbl)
            emitTree(mid + 1, hi)
        }
        emitTree(0, chunks.lastIndex)
    }

    private fun emitInvokeIndirectLeafSwitch(
        insns: InsnList,
        ir: IrModule,
        instanceInternalName: String,
        candidates: List<Int>,
        funcTargets: Map<Int, CompiledFunctionTarget>,
    ) {
        val dfltLbl = LabelNode()
        val caseLabels = Array(candidates.size) { LabelNode() }
        val min = candidates.first()
        val max = candidates.last()
        val span = max.toLong() - min.toLong() + 1L

        insns.add(VarInsnNode(Opcodes.ILOAD, 1))
        if (span <= candidates.size.toLong() * 2L + 16L && span <= 256L) {
            val map = candidates.withIndex().associate { (i, fnIdx) -> fnIdx to caseLabels[i] }
            val tableLabels = Array(span.toInt()) { offset -> map[min + offset] ?: dfltLbl }
            insns.add(TableSwitchInsnNode(min, max, dfltLbl, *tableLabels))
        } else {
            insns.add(LookupSwitchInsnNode(dfltLbl, candidates.toIntArray(), caseLabels))
        }

        for (i in candidates.indices) {
            val fnIdx = candidates[i]
            val target = funcTargets.getValue(fnIdx)
            val ft = target.funcType
            val directCount = AotNames.countDirectParams(ft.params)
            insns.add(caseLabels[i])

            if (directCount < ft.params.size) {
                val spillCounter = WasmBytecodeEmitter.TypeSlotCounters()
                for (p in directCount until ft.params.size) {
                    val vt = ft.params[p]
                    val sIdx = spillCounter.next(vt)
                    insns.add(VarInsnNode(Opcodes.ALOAD, 2))
                    WasmBytecodeEmitter.pushInt(insns, p)
                    insns.add(InsnNode(Opcodes.AALOAD))
                    emitUnboxFromObject(insns, vt)
                    emitStoreTopIntoSpill(insns, instanceInternalName, 0, vt, sIdx, 3)
                }
            }
            for (p in 0 until directCount) {
                insns.add(VarInsnNode(Opcodes.ALOAD, 2))
                WasmBytecodeEmitter.pushInt(insns, p)
                insns.add(InsnNode(Opcodes.AALOAD))
                emitUnboxFromObject(insns, ft.params[p])
            }
            insns.add(VarInsnNode(Opcodes.ALOAD, 0))
            insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, target.ownerInternalName, target.methodName, target.methodDescriptor, false))

            when (ft.results.size) {
                0 -> {
                    insns.add(InsnNode(Opcodes.ACONST_NULL))
                    insns.add(InsnNode(Opcodes.ARETURN))
                }
                1 -> {
                    emitBoxPrimitiveIfNeeded(insns, ft.results[0])
                    insns.add(VarInsnNode(Opcodes.ASTORE, 3))
                    insns.add(InsnNode(Opcodes.ICONST_1))
                    insns.add(TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"))
                    insns.add(InsnNode(Opcodes.DUP))
                    insns.add(InsnNode(Opcodes.ICONST_0))
                    insns.add(VarInsnNode(Opcodes.ALOAD, 3))
                    insns.add(InsnNode(Opcodes.AASTORE))
                    insns.add(InsnNode(Opcodes.ARETURN))
                }
                else -> {
                    val tupleClass = target.multiValueClassInternalName!!
                    insns.add(VarInsnNode(Opcodes.ASTORE, 3))
                    WasmBytecodeEmitter.pushInt(insns, ft.results.size)
                    insns.add(TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"))
                    insns.add(VarInsnNode(Opcodes.ASTORE, 4))
                    for (r in ft.results.indices) {
                        insns.add(VarInsnNode(Opcodes.ALOAD, 4))
                        WasmBytecodeEmitter.pushInt(insns, r)
                        insns.add(VarInsnNode(Opcodes.ALOAD, 3))
                        insns.add(FieldInsnNode(Opcodes.GETFIELD, tupleClass, "v$r", AotNames.jvmDesc(ft.results[r])))
                        emitBoxPrimitiveIfNeeded(insns, ft.results[r])
                        insns.add(InsnNode(Opcodes.AASTORE))
                    }
                    insns.add(VarInsnNode(Opcodes.ALOAD, 4))
                    insns.add(InsnNode(Opcodes.ARETURN))
                }
            }
        }
        insns.add(dfltLbl)
        emitTrapInstruction(insns, "indirect call target function not callable")
    }

    // ---------------------------------------------------------------- Boxing, Trap, Bytecode Metrics

    private fun emitBoxPrimitiveIfNeeded(insns: InsnList, vt: IrValType) {
        when (vt) {
            IrValType.I32 -> insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false))
            IrValType.I64 -> insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false))
            IrValType.F32 -> insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false))
            IrValType.F64 -> insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false))
            IrValType.FUNCREF, IrValType.EXTERNREF -> {}
        }
    }

    private fun emitUnboxFromObject(insns: InsnList, vt: IrValType) {
        when (vt) {
            IrValType.I32 -> {
                insns.add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Number"))
                insns.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false))
            }
            IrValType.I64 -> {
                insns.add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Number"))
                insns.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "longValue", "()J", false))
            }
            IrValType.F32 -> {
                insns.add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Number"))
                insns.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "floatValue", "()F", false))
            }
            IrValType.F64 -> {
                insns.add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Number"))
                insns.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false))
            }
            IrValType.FUNCREF -> insns.add(TypeInsnNode(Opcodes.CHECKCAST, AotNames.FUNC_REF_INTERNAL))
            IrValType.EXTERNREF -> {}
        }
    }

    private fun emitLoadSpillForBoxing(
        insns: InsnList,
        instanceInternalName: String,
        instSlot: Int,
        vt: IrValType,
        spillIdx: Int,
    ) {
        val (fName, fDesc, loadOp) = spillFieldTriplet(vt)
        insns.add(VarInsnNode(Opcodes.ALOAD, instSlot))
        insns.add(FieldInsnNode(Opcodes.GETFIELD, instanceInternalName, "spill", AotNames.SPILL_FRAME_DESC))
        insns.add(FieldInsnNode(Opcodes.GETFIELD, AotNames.SPILL_FRAME_INTERNAL, fName, fDesc))
        WasmBytecodeEmitter.pushInt(insns, spillIdx)
        insns.add(InsnNode(loadOp))
        if (vt == IrValType.FUNCREF) {
            insns.add(TypeInsnNode(Opcodes.CHECKCAST, AotNames.FUNC_REF_INTERNAL))
        }
    }

    private fun emitStoreTopIntoSpill(
        insns: InsnList,
        instanceInternalName: String,
        instSlot: Int,
        vt: IrValType,
        spillIdx: Int,
        scratchSlot: Int,
    ) {
        val (fName, fDesc, _) = spillFieldTriplet(vt)
        val storeOp = when (vt) {
            IrValType.I32 -> Opcodes.IASTORE
            IrValType.I64 -> Opcodes.LASTORE
            IrValType.F32 -> Opcodes.FASTORE
            IrValType.F64 -> Opcodes.DASTORE
            IrValType.FUNCREF, IrValType.EXTERNREF -> Opcodes.AASTORE
        }
        insns.add(VarInsnNode(AotNames.storeOpcode(vt), scratchSlot))
        insns.add(VarInsnNode(Opcodes.ALOAD, instSlot))
        insns.add(FieldInsnNode(Opcodes.GETFIELD, instanceInternalName, "spill", AotNames.SPILL_FRAME_DESC))
        insns.add(FieldInsnNode(Opcodes.GETFIELD, AotNames.SPILL_FRAME_INTERNAL, fName, fDesc))
        WasmBytecodeEmitter.pushInt(insns, spillIdx)
        insns.add(VarInsnNode(AotNames.loadOpcode(vt), scratchSlot))
        insns.add(InsnNode(storeOp))
    }

    private fun spillFieldTriplet(vt: IrValType): Triple<String, String, Int> = when (vt) {
        IrValType.I32 -> Triple("i32", "[I", Opcodes.IALOAD)
        IrValType.I64 -> Triple("i64", "[J", Opcodes.LALOAD)
        IrValType.F32 -> Triple("f32", "[F", Opcodes.FALOAD)
        IrValType.F64 -> Triple("f64", "[D", Opcodes.DALOAD)
        IrValType.FUNCREF, IrValType.EXTERNREF -> Triple("refs", "[Ljava/lang/Object;", Opcodes.AALOAD)
    }

    private fun emitTrapInstruction(insns: InsnList, message: String) {
        insns.add(LdcInsnNode(message))
        insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, AotNames.TRAP_SUPPORT_INTERNAL, "trap", "(Ljava/lang/String;)Ljava/lang/Void;", false))
        insns.add(InsnNode(Opcodes.POP))
        insns.add(InsnNode(Opcodes.ACONST_NULL))
        insns.add(InsnNode(Opcodes.ATHROW))
    }

    internal class AotClassWriter(
        private val generatedPrefix: String,
    ) : ClassWriter(COMPUTE_FRAMES) {
        override fun getCommonSuperClass(type1: String, type2: String): String {
            if (type1 == type2) return type1
            val frefPrefix = AotNames.FUNC_REF_INTERNAL
            if ((type1 == frefPrefix || type1.startsWith("$frefPrefix\$")) &&
                (type2 == frefPrefix || type2.startsWith("$frefPrefix\$"))
            ) {
                return frefPrefix
            }
            if (type1.startsWith(generatedPrefix) || type2.startsWith(generatedPrefix)) {
                return "java/lang/Object"
            }
            return try {
                super.getCommonSuperClass(type1, type2)
            } catch (_: RuntimeException) {
                "java/lang/Object"
            }
        }
    }

    internal data class MethodKey(val name: String, val desc: String)

    internal fun readConstantPoolCount(classBytes: ByteArray): Int {
        if (classBytes.size < 10) return 0
        return ((classBytes[8].toInt() and 0xFF) shl 8) or (classBytes[9].toInt() and 0xFF)
    }

    internal fun readMethodCodeLengths(classBytes: ByteArray): Map<MethodKey, Int> {
        val buf = java.nio.ByteBuffer.wrap(classBytes).order(java.nio.ByteOrder.BIG_ENDIAN)
        if (buf.remaining() < 10 || buf.int != 0xCAFEBABE.toInt()) return emptyMap()
        buf.short
        buf.short
        val cpCount = buf.short.toInt() and 0xFFFF
        val utf8 = arrayOfNulls<String>(cpCount)
        var i = 1
        while (i < cpCount) {
            val tag = buf.get().toInt() and 0xFF
            when (tag) {
                1 -> {
                    val len = buf.short.toInt() and 0xFFFF
                    val bytes = ByteArray(len)
                    buf.get(bytes)
                    utf8[i] = String(bytes, Charsets.UTF_8)
                }
                3, 4, 9, 10, 11, 12, 17, 18 -> buf.position(buf.position() + 4)
                5, 6 -> {
                    buf.position(buf.position() + 8)
                    i++
                }
                7, 8, 16, 19, 20 -> buf.position(buf.position() + 2)
                15 -> buf.position(buf.position() + 3)
                else -> return emptyMap()
            }
            i++
        }
        buf.position(buf.position() + 6)
        val ifaceCount = buf.short.toInt() and 0xFFFF
        buf.position(buf.position() + ifaceCount * 2)

        fun skipAttributes() {
            val count = buf.short.toInt() and 0xFFFF
            repeat(count) {
                buf.short
                val len = buf.int
                buf.position(buf.position() + len)
            }
        }

        val fieldsCount = buf.short.toInt() and 0xFFFF
        repeat(fieldsCount) {
            buf.position(buf.position() + 6)
            skipAttributes()
        }

        val methodsCount = buf.short.toInt() and 0xFFFF
        val result = LinkedHashMap<MethodKey, Int>(methodsCount)
        repeat(methodsCount) {
            buf.short
            val nameIdx = buf.short.toInt() and 0xFFFF
            val descIdx = buf.short.toInt() and 0xFFFF
            val attrCount = buf.short.toInt() and 0xFFFF
            var codeLen = 0
            repeat(attrCount) {
                val attrNameIdx = buf.short.toInt() and 0xFFFF
                val attrLen = buf.int
                val attrEnd = buf.position() + attrLen
                if (utf8.getOrNull(attrNameIdx) == "Code" && attrLen >= 8) {
                    buf.short
                    buf.short
                    codeLen = buf.int
                }
                buf.position(attrEnd)
            }
            val name = utf8.getOrNull(nameIdx)
            val desc = utf8.getOrNull(descIdx)
            if (name != null && desc != null && codeLen > 0) {
                result[MethodKey(name, desc)] = codeLen
            }
        }
        return result
    }
}
