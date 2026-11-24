package wasm2kt.tooling.aot

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import wasm2kt.tooling.ir.BinaryOp
import wasm2kt.tooling.ir.IrBlockKind
import wasm2kt.tooling.ir.IrFuncType
import wasm2kt.tooling.ir.IrFunction
import wasm2kt.tooling.ir.IrModule
import wasm2kt.tooling.ir.IrStackType
import wasm2kt.tooling.ir.IrTypedInsn
import wasm2kt.tooling.ir.IrValType
import wasm2kt.tooling.ir.LoadKind
import wasm2kt.tooling.ir.StoreKind
import wasm2kt.tooling.ir.UnaryOp
import wasm2kt.tooling.ir.WasmOp

internal data class DispatchCallSite(
    val ownerInternalName: String,
    val methodName: String,
    val methodDescriptor: String,
)

internal interface ModuleEmitContext {
    val ir: IrModule
    val instanceInternalName: String
    val hasElemSegments: Boolean
    val hasDataSegments: Boolean
    val hasFuncRefs: Boolean
    val emitLineNumbers: Boolean
    fun funcTarget(funcIndex: Int): CompiledFunctionTarget
    fun callIndirectDispatcher(funcType: IrFuncType): DispatchCallSite
    fun isSharedGlobal(globalIndex: Int): Boolean
    fun multiValueReturnClass(results: List<IrValType>): String
}

/**
 * Directly translates a [FunctionSplitPlan] (root body plus pre-split helpers) into verified-clean
 * ASM [MethodNode]s without `invokedynamic` or `condy`.
 */
internal object WasmBytecodeEmitter {
    fun emitFunctionMethods(
        plan: FunctionSplitPlan,
        ctx: ModuleEmitContext,
    ): List<MethodNode> {
        val methods = ArrayList<MethodNode>(1 + plan.helpers.size)
        for (helper in plan.helpers) {
            methods += emitHelperMethod(plan, helper, ctx)
        }
        methods += emitRootMethod(plan, ctx)
        return methods
    }

    private fun emitRootMethod(
        plan: FunctionSplitPlan,
        ctx: ModuleEmitContext,
    ): MethodNode {
        val fn = plan.fn
        val directCount = AotNames.countDirectParams(fn.type.params)
        val desc = AotNames.staticFuncDescriptor(ctx.instanceInternalName, fn.type, directCount)
        val mn = MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, AotNames.funcMethodName(fn.index), desc, null, null)

        val fl = plan.frameLayout
        if (fl != null) {
            val paramSlots = IntArray(directCount) { -1 }
            var nextSlot = 0
            for (i in 0 until directCount) {
                paramSlots[i] = nextSlot
                nextSlot += AotNames.slotSize(fn.allLocals[i].valType)
            }
            val instSlot = nextSlot++
            val frameArraySlots = IntArray(5) { -1 }
            for ((kind, _) in fl.arrayParams) {
                frameArraySlots[kind] = nextSlot++
            }
            val builder = BodyEmitter(
                fn = fn,
                ctx = ctx,
                instSlot = instSlot,
                localSlots = paramSlots,
                firstScratchSlot = nextSlot,
                mn = mn,
                helperSpec = null,
                baseWasmOffset = fn.instructions.firstOrNull()?.wasmOffset ?: 0,
                frameLayout = fl,
                frameArraySlots = frameArraySlots,
            )
            builder.emitFrameAllocations()
            for (i in 0 until directCount) {
                val vt = fn.params[i].valType
                val kind = LocalFrameLayout.kindOf(vt)
                builder.emitFrameArrayStoreFromLocal(kind, fl.indexInTypeArray[i], vt, paramSlots[i])
            }
            if (directCount < fn.params.size) {
                val spillAlloc = TypeSlotCounters()
                for (i in directCount until fn.params.size) {
                    val vt = fn.params[i].valType
                    val kind = LocalFrameLayout.kindOf(vt)
                    val spillIdx = spillAlloc.next(vt)
                    builder.emitFrameArrayStoreFromSpill(kind, fl.indexInTypeArray[i], vt, spillIdx)
                }
            }
            val rootFrame = ControlBlockState(
                blockId = 0,
                kind = IrBlockKind.FUNCTION,
                unwindHeight = 0,
                labelTypes = fn.type.results,
                entryLabel = LabelNode(),
                elseLabel = null,
                endLabel = LabelNode(),
                endReachable = false,
                elseReachable = false,
            )
            builder.pushBlock(rootFrame)
            builder.emitNodeList(plan.rootBody)
            builder.finishRoot(rootFrame)
            mn.maxStack = 256
            mn.maxLocals = builder.maxLocalUsed.coerceAtLeast(nextSlot)
            return mn
        }

        val localSlots = IntArray(fn.allLocals.size) { -1 }
        var nextSlot = 0
        for (i in 0 until directCount) {
            localSlots[i] = nextSlot
            nextSlot += AotNames.slotSize(fn.allLocals[i].valType)
        }
        val instSlot = nextSlot++

        for (i in directCount until fn.params.size) {
            localSlots[i] = nextSlot
            nextSlot += AotNames.slotSize(fn.allLocals[i].valType)
        }
        for (i in fn.params.size until fn.allLocals.size) {
            if (plan.rootLiveLocalsUsed.get(i)) {
                localSlots[i] = nextSlot
                nextSlot += AotNames.slotSize(fn.allLocals[i].valType)
            }
        }

        val builder = BodyEmitter(
            fn = fn,
            ctx = ctx,
            instSlot = instSlot,
            localSlots = localSlots,
            firstScratchSlot = nextSlot,
            mn = mn,
            helperSpec = null,
            baseWasmOffset = fn.instructions.firstOrNull()?.wasmOffset ?: 0,
            frameLayout = null,
            frameArraySlots = IntArray(0),
        )

        if (directCount < fn.params.size) {
            val spillAlloc = TypeSlotCounters()
            val overflowEntries = (directCount until fn.params.size).map { i ->
                val vt = fn.params[i].valType
                i to SplitSpillSlot(vt, spillAlloc.next(vt))
            }
            builder.emitBatchSpillLoadsToLocals(overflowEntries) { localSlots[it] }
        }
        for (i in fn.params.size until fn.allLocals.size) {
            val slot = localSlots[i]
            if (slot >= 0) {
                builder.emitZeroInitLocal(fn.allLocals[i].valType, slot)
            }
        }

        val rootFrame = ControlBlockState(
            blockId = 0,
            kind = IrBlockKind.FUNCTION,
            unwindHeight = 0,
            labelTypes = fn.type.results,
            entryLabel = LabelNode(),
            elseLabel = null,
            endLabel = LabelNode(),
            endReachable = false,
            elseReachable = false,
        )
        builder.pushBlock(rootFrame)
        builder.emitNodeList(plan.rootBody)
        builder.finishRoot(rootFrame)
        mn.maxStack = 256
        mn.maxLocals = builder.maxLocalUsed.coerceAtLeast(nextSlot)
        return mn
    }

    private fun emitHelperMethod(
        plan: FunctionSplitPlan,
        helper: HelperMethodSpec,
        ctx: ModuleEmitContext,
    ): MethodNode {
        val fn = plan.fn
        val mn = MethodNode(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, helper.name, helper.descriptor, null, null)

        val fl = plan.frameLayout
        if (fl != null) {
            val instSlot = 0
            var nextSlot = 1
            val frameArraySlots = IntArray(5) { -1 }
            for ((kind, _) in fl.arrayParams) {
                frameArraySlots[kind] = nextSlot++
            }
            val exitCodeSlot = if (helper.returnShape is ReturnShape.SpillAndExitCode && helper.returnShape.returnsExitCode) {
                nextSlot++
            } else {
                -1
            }
            val builder = BodyEmitter(
                fn = fn,
                ctx = ctx,
                instSlot = instSlot,
                localSlots = IntArray(0),
                firstScratchSlot = nextSlot,
                mn = mn,
                helperSpec = helper,
                baseWasmOffset = fn.instructions.firstOrNull()?.wasmOffset ?: 0,
                frameLayout = fl,
                frameArraySlots = frameArraySlots,
            )
            builder.emitHelperBodyAndExit(helper, exitCodeSlot)
            mn.maxStack = 256
            mn.maxLocals = builder.maxLocalUsed.coerceAtLeast(nextSlot)
            return mn
        }

        val localSlots = IntArray(fn.allLocals.size) { -1 }
        val instSlot = 0
        var nextSlot = 1

        val directSet = HashSet<Int>(helper.directParams.size)
        for ((localIdx, vt) in helper.directParams) {
            localSlots[localIdx] = nextSlot
            nextSlot += AotNames.slotSize(vt)
            directSet += localIdx
        }

        val spillSet = HashSet<Int>(helper.spillInLocals.size)
        for ((localIdx, _) in helper.spillInLocals) {
            spillSet += localIdx
        }

        var l = helper.referencedLocals.nextSetBit(0)
        while (l >= 0) {
            if (l !in directSet) {
                localSlots[l] = nextSlot
                nextSlot += AotNames.slotSize(fn.allLocals[l].valType)
            }
            l = helper.referencedLocals.nextSetBit(l + 1)
        }

        val exitCodeSlot = if (helper.returnShape is ReturnShape.SpillAndExitCode && helper.returnShape.returnsExitCode) {
            nextSlot++
        } else {
            -1
        }

        val builder = BodyEmitter(
            fn = fn,
            ctx = ctx,
            instSlot = instSlot,
            localSlots = localSlots,
            firstScratchSlot = nextSlot,
            mn = mn,
            helperSpec = helper,
            baseWasmOffset = fn.instructions.firstOrNull()?.wasmOffset ?: 0,
            frameLayout = null,
            frameArraySlots = IntArray(0),
        )

        builder.emitBatchSpillLoadsToLocals(helper.spillInLocals) { localSlots[it] }
        var refLocal = helper.referencedLocals.nextSetBit(0)
        while (refLocal >= 0) {
            if (!helper.liveInSet.get(refLocal) && refLocal !in directSet && refLocal !in spillSet) {
                builder.emitZeroInitLocal(fn.allLocals[refLocal].valType, localSlots[refLocal])
            }
            refLocal = helper.referencedLocals.nextSetBit(refLocal + 1)
        }

        builder.emitHelperBodyAndExit(helper, exitCodeSlot)
        mn.maxStack = 256
        mn.maxLocals = builder.maxLocalUsed.coerceAtLeast(nextSlot)
        return mn
    }

    internal class TypeSlotCounters {
        private val counts = IntArray(5)
        fun next(vt: IrValType): Int {
            val k = when (vt) {
                IrValType.I32 -> 0
                IrValType.I64 -> 1
                IrValType.F32 -> 2
                IrValType.F64 -> 3
                IrValType.FUNCREF, IrValType.EXTERNREF -> 4
            }
            return counts[k]++
        }
    }

    private data class ControlBlockState(
        val blockId: Int,
        val kind: IrBlockKind,
        val unwindHeight: Int,
        val labelTypes: List<IrValType>,
        val entryLabel: LabelNode,
        val elseLabel: LabelNode?,
        val endLabel: LabelNode,
        var endReachable: Boolean,
        var elseReachable: Boolean,
    )

    private class BodyEmitter(
        private val fn: IrFunction,
        private val ctx: ModuleEmitContext,
        private val instSlot: Int,
        private val localSlots: IntArray,
        private val firstScratchSlot: Int,
        mn: MethodNode,
        private val helperSpec: HelperMethodSpec?,
        private val baseWasmOffset: Int,
        private val frameLayout: LocalFrameLayout?,
        private val frameArraySlots: IntArray,
    ) {
        private val insns: InsnList = mn.instructions
        private val blockStack = ArrayDeque<ControlBlockState>()
        private val blockMap = HashMap<Int, ControlBlockState>()
        private val externalExitLabels = HashMap<Int, LabelNode>()
        private var helperEntryHeight: Int = 0
        private var jvmReachable: Boolean = true
        private var lastEmittedLine = -1
        private var scratchTop: Int = firstScratchSlot

        var maxLocalUsed: Int = firstScratchSlot
            private set

        init {
            val shape = helperSpec?.returnShape
            if (shape is ReturnShape.SpillAndExitCode) {
                for (ext in shape.externalExits) {
                    externalExitLabels[ext.blockId] = LabelNode()
                }
            }
        }

        fun emitFrameAllocations() {
            val fl = frameLayout ?: return
            for ((kind, _) in fl.arrayParams) {
                pushInt(insns, fl.counts[kind])
                when (kind) {
                    0 -> insns.add(IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT))
                    1 -> insns.add(IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_LONG))
                    2 -> insns.add(IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_FLOAT))
                    3 -> insns.add(IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_DOUBLE))
                    else -> insns.add(TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"))
                }
                insns.add(VarInsnNode(Opcodes.ASTORE, frameArraySlots[kind]))
            }
        }

        fun emitFrameArrayStoreFromLocal(kind: Int, arrIdx: Int, vt: IrValType, srcSlot: Int) {
            insns.add(VarInsnNode(Opcodes.ALOAD, frameArraySlots[kind]))
            pushInt(insns, arrIdx)
            insns.add(VarInsnNode(AotNames.loadOpcode(vt), srcSlot))
            insns.add(InsnNode(arrayStoreOpcode(vt)))
        }

        fun emitFrameArrayStoreFromSpill(kind: Int, arrIdx: Int, vt: IrValType, spillIdx: Int) {
            insns.add(VarInsnNode(Opcodes.ALOAD, frameArraySlots[kind]))
            pushInt(insns, arrIdx)
            emitSpillLoadToStack(vt, spillIdx)
            insns.add(InsnNode(arrayStoreOpcode(vt)))
        }

        fun pushBlock(state: ControlBlockState) {
            blockStack.addLast(state)
            blockMap[state.blockId] = state
        }

        fun popBlock(state: ControlBlockState) {
            blockStack.removeLast()
            blockMap.remove(state.blockId)
        }

        private inline fun <T> withScratchScope(block: () -> T): T {
            val saved = scratchTop
            return try {
                block()
            } finally {
                scratchTop = saved
            }
        }

        private fun allocScratch(types: List<IrValType>): IntArray {
            val slots = IntArray(types.size)
            for (i in types.indices) {
                slots[i] = allocSingleScratch(types[i])
            }
            return slots
        }

        private fun allocSingleScratch(vt: IrValType): Int {
            val slot = scratchTop
            scratchTop += AotNames.slotSize(vt)
            if (scratchTop > maxLocalUsed) maxLocalUsed = scratchTop
            return slot
        }

        private fun recordLine(wasmOffset: Int) {
            if (!ctx.emitLineNumbers) return
            val line = (wasmOffset - baseWasmOffset + 1).coerceIn(1, 65535)
            if (line != lastEmittedLine) {
                lastEmittedLine = line
                val lbl = LabelNode()
                insns.add(lbl)
                insns.add(LineNumberNode(line, lbl))
            }
        }

        fun emitZeroInitLocal(vt: IrValType, slot: Int) {
            when (vt) {
                IrValType.I32 -> {
                    insns.add(InsnNode(Opcodes.ICONST_0))
                    insns.add(VarInsnNode(Opcodes.ISTORE, slot))
                }
                IrValType.I64 -> {
                    insns.add(InsnNode(Opcodes.LCONST_0))
                    insns.add(VarInsnNode(Opcodes.LSTORE, slot))
                }
                IrValType.F32 -> {
                    insns.add(InsnNode(Opcodes.FCONST_0))
                    insns.add(VarInsnNode(Opcodes.FSTORE, slot))
                }
                IrValType.F64 -> {
                    insns.add(InsnNode(Opcodes.DCONST_0))
                    insns.add(VarInsnNode(Opcodes.DSTORE, slot))
                }
                IrValType.FUNCREF, IrValType.EXTERNREF -> {
                    insns.add(InsnNode(Opcodes.ACONST_NULL))
                    insns.add(VarInsnNode(Opcodes.ASTORE, slot))
                }
            }
        }

        private fun pushSpillFrame() {
            insns.add(VarInsnNode(Opcodes.ALOAD, instSlot))
            insns.add(FieldInsnNode(Opcodes.GETFIELD, ctx.instanceInternalName, "spill", AotNames.SPILL_FRAME_DESC))
        }

        private fun spillArrayField(vt: IrValType): Pair<String, String> = when (vt) {
            IrValType.I32 -> "i32" to "[I"
            IrValType.I64 -> "i64" to "[J"
            IrValType.F32 -> "f32" to "[F"
            IrValType.F64 -> "f64" to "[D"
            IrValType.FUNCREF, IrValType.EXTERNREF -> "refs" to "[Ljava/lang/Object;"
        }

        private fun arrayLoadOpcode(vt: IrValType): Int = when (vt) {
            IrValType.I32 -> Opcodes.IALOAD
            IrValType.I64 -> Opcodes.LALOAD
            IrValType.F32 -> Opcodes.FALOAD
            IrValType.F64 -> Opcodes.DALOAD
            IrValType.FUNCREF, IrValType.EXTERNREF -> Opcodes.AALOAD
        }

        private fun arrayStoreOpcode(vt: IrValType): Int = when (vt) {
            IrValType.I32 -> Opcodes.IASTORE
            IrValType.I64 -> Opcodes.LASTORE
            IrValType.F32 -> Opcodes.FASTORE
            IrValType.F64 -> Opcodes.DASTORE
            IrValType.FUNCREF, IrValType.EXTERNREF -> Opcodes.AASTORE
        }

        fun emitSpillLoadToStack(vt: IrValType, spillIndex: Int) {
            val (fieldName, fieldDesc) = spillArrayField(vt)
            pushSpillFrame()
            insns.add(FieldInsnNode(Opcodes.GETFIELD, AotNames.SPILL_FRAME_INTERNAL, fieldName, fieldDesc))
            pushInt(insns, spillIndex)
            insns.add(InsnNode(arrayLoadOpcode(vt)))
            if (vt == IrValType.FUNCREF) {
                insns.add(TypeInsnNode(Opcodes.CHECKCAST, AotNames.FUNC_REF_INTERNAL))
                clearSpillRef(spillIndex)
            } else if (vt == IrValType.EXTERNREF) {
                clearSpillRef(spillIndex)
            }
        }

        private fun clearSpillRef(spillIndex: Int) {
            pushSpillFrame()
            insns.add(FieldInsnNode(Opcodes.GETFIELD, AotNames.SPILL_FRAME_INTERNAL, "refs", "[Ljava/lang/Object;"))
            pushInt(insns, spillIndex)
            insns.add(InsnNode(Opcodes.ACONST_NULL))
            insns.add(InsnNode(Opcodes.AASTORE))
        }

        fun emitSpillLoadToLocal(vt: IrValType, spillIndex: Int, dstLocalSlot: Int) {
            check(dstLocalSlot >= 0) { "Unallocated local slot for spill load ($vt index $spillIndex)" }
            emitSpillLoadToStack(vt, spillIndex)
            insns.add(VarInsnNode(AotNames.storeOpcode(vt), dstLocalSlot))
        }

        private fun emitSpillStoreFromStackTop(vt: IrValType, spillIndex: Int) = withScratchScope {
            val tmp = allocSingleScratch(vt)
            insns.add(VarInsnNode(AotNames.storeOpcode(vt), tmp))
            val (fieldName, fieldDesc) = spillArrayField(vt)
            pushSpillFrame()
            insns.add(FieldInsnNode(Opcodes.GETFIELD, AotNames.SPILL_FRAME_INTERNAL, fieldName, fieldDesc))
            pushInt(insns, spillIndex)
            insns.add(VarInsnNode(AotNames.loadOpcode(vt), tmp))
            insns.add(InsnNode(arrayStoreOpcode(vt)))
        }

        private fun emitSpillStoreFromLocal(vt: IrValType, spillIndex: Int, localIdx: Int) {
            val srcSlot = localSlots[localIdx]
            check(srcSlot >= 0) { "Unallocated local slot for Wasm local $localIdx in spill store" }
            val (fieldName, fieldDesc) = spillArrayField(vt)
            pushSpillFrame()
            insns.add(FieldInsnNode(Opcodes.GETFIELD, AotNames.SPILL_FRAME_INTERNAL, fieldName, fieldDesc))
            pushInt(insns, spillIndex)
            insns.add(VarInsnNode(AotNames.loadOpcode(vt), srcSlot))
            insns.add(InsnNode(arrayStoreOpcode(vt)))
        }

        fun emitBatchSpillLoadsToLocals(
            entries: List<Pair<Int, SplitSpillSlot>>,
            slotLookup: (Int) -> Int,
        ) {
            if (entries.isEmpty()) return
            if (entries.size == 1) {
                val (localIdx, spillSlot) = entries[0]
                emitSpillLoadToLocal(spillSlot.valType, spillSlot.typeIndex, slotLookup(localIdx))
                return
            }
            val groups = LinkedHashMap<Pair<String, String>, MutableList<Pair<Int, SplitSpillSlot>>>()
            for (entry in entries) {
                groups.getOrPut(spillArrayField(entry.second.valType)) { mutableListOf() }.add(entry)
            }
            for ((fieldPair, group) in groups) {
                if (group.size == 1) {
                    val (localIdx, spillSlot) = group[0]
                    emitSpillLoadToLocal(spillSlot.valType, spillSlot.typeIndex, slotLookup(localIdx))
                    continue
                }
                withScratchScope {
                    val arrSlot = allocSingleScratch(IrValType.EXTERNREF)
                    pushSpillFrame()
                    insns.add(FieldInsnNode(Opcodes.GETFIELD, AotNames.SPILL_FRAME_INTERNAL, fieldPair.first, fieldPair.second))
                    insns.add(VarInsnNode(Opcodes.ASTORE, arrSlot))
                    for ((localIdx, spillSlot) in group) {
                        val vt = spillSlot.valType
                        val dstSlot = slotLookup(localIdx)
                        check(dstSlot >= 0) { "Unallocated local slot for Wasm local $localIdx in batch spill load" }
                        insns.add(VarInsnNode(Opcodes.ALOAD, arrSlot))
                        pushInt(insns, spillSlot.typeIndex)
                        insns.add(InsnNode(arrayLoadOpcode(vt)))
                        if (vt == IrValType.FUNCREF) {
                            insns.add(TypeInsnNode(Opcodes.CHECKCAST, AotNames.FUNC_REF_INTERNAL))
                        }
                        insns.add(VarInsnNode(AotNames.storeOpcode(vt), dstSlot))
                        if (vt == IrValType.FUNCREF || vt == IrValType.EXTERNREF) {
                            insns.add(VarInsnNode(Opcodes.ALOAD, arrSlot))
                            pushInt(insns, spillSlot.typeIndex)
                            insns.add(InsnNode(Opcodes.ACONST_NULL))
                            insns.add(InsnNode(Opcodes.AASTORE))
                        }
                    }
                }
            }
        }

        fun emitBatchSpillStoresFromLocals(entries: List<Pair<Int, SplitSpillSlot>>) {
            if (entries.isEmpty()) return
            if (entries.size == 1) {
                val (localIdx, spillSlot) = entries[0]
                emitSpillStoreFromLocal(spillSlot.valType, spillSlot.typeIndex, localIdx)
                return
            }
            val groups = LinkedHashMap<Pair<String, String>, MutableList<Pair<Int, SplitSpillSlot>>>()
            for (entry in entries) {
                groups.getOrPut(spillArrayField(entry.second.valType)) { mutableListOf() }.add(entry)
            }
            for ((fieldPair, group) in groups) {
                if (group.size == 1) {
                    val (localIdx, spillSlot) = group[0]
                    emitSpillStoreFromLocal(spillSlot.valType, spillSlot.typeIndex, localIdx)
                    continue
                }
                withScratchScope {
                    val arrSlot = allocSingleScratch(IrValType.EXTERNREF)
                    pushSpillFrame()
                    insns.add(FieldInsnNode(Opcodes.GETFIELD, AotNames.SPILL_FRAME_INTERNAL, fieldPair.first, fieldPair.second))
                    insns.add(VarInsnNode(Opcodes.ASTORE, arrSlot))
                    for ((localIdx, spillSlot) in group) {
                        val vt = spillSlot.valType
                        val srcSlot = localSlots[localIdx]
                        check(srcSlot >= 0) { "Unallocated local slot for Wasm local $localIdx in batch spill store" }
                        insns.add(VarInsnNode(Opcodes.ALOAD, arrSlot))
                        pushInt(insns, spillSlot.typeIndex)
                        insns.add(VarInsnNode(AotNames.loadOpcode(vt), srcSlot))
                        insns.add(InsnNode(arrayStoreOpcode(vt)))
                    }
                }
            }
        }

        fun emitNodeList(nodes: List<IrNode>) {
            for (node in nodes) {
                emitNode(node)
            }
        }

        fun finishRoot(rootFrame: ControlBlockState) {
            if (jvmReachable) {
                emitMethodReturnFromTop(fn.type.results)
            }
            if (rootFrame.endReachable) {
                insns.add(rootFrame.endLabel)
                emitMethodReturnFromTop(fn.type.results)
            }
            popBlock(rootFrame)
        }

        fun emitHelperBodyAndExit(helper: HelperMethodSpec, exitCodeSlot: Int) {
            helperEntryHeight = helper.body.firstOrNull()?.entryStackHeight ?: 0
            emitNodeList(helper.body)

            val commonEpilogueLabel = LabelNode()
            val commonReached = jvmReachable ||
                (helper.returnShape is ReturnShape.SpillAndExitCode && externalExitLabels.isNotEmpty())

            when (val shape = helper.returnShape) {
                is ReturnShape.Void -> {
                    if (jvmReachable) {
                        insns.add(InsnNode(Opcodes.RETURN))
                    }
                }
                is ReturnShape.DirectStackValue -> {
                    if (jvmReachable) {
                        insns.add(InsnNode(AotNames.returnOpcode(shape.valType)))
                    }
                }
                is ReturnShape.DirectLiveOut -> {
                    if (jvmReachable) {
                        val slot = localSlots[shape.localIndex]
                        check(slot >= 0) { "Unallocated local slot for Wasm local ${shape.localIndex} in ${helper.name} DirectLiveOut" }
                        insns.add(VarInsnNode(AotNames.loadOpcode(shape.valType), slot))
                        insns.add(InsnNode(AotNames.returnOpcode(shape.valType)))
                    }
                }
                is ReturnShape.SpillAndExitCode -> {
                    if (jvmReachable) {
                        for (idx in shape.normalStackSlots.lastIndex downTo 0) {
                            val slotSpec = shape.normalStackSlots[idx]
                            emitSpillStoreFromStackTop(slotSpec.valType, slotSpec.typeIndex)
                        }
                        if (shape.returnsExitCode) {
                            insns.add(InsnNode(Opcodes.ICONST_0))
                            insns.add(VarInsnNode(Opcodes.ISTORE, exitCodeSlot))
                        }
                        if (shape.externalExits.isNotEmpty()) {
                            insns.add(JumpInsnNode(Opcodes.GOTO, commonEpilogueLabel))
                        }
                    }

                    for (ext in shape.externalExits) {
                        val stubLabel = externalExitLabels.getValue(ext.blockId)
                        insns.add(stubLabel)
                        for (idx in ext.spillSlots.lastIndex downTo 0) {
                            val slotSpec = ext.spillSlots[idx]
                            emitSpillStoreFromStackTop(slotSpec.valType, slotSpec.typeIndex)
                        }
                        pushInt(insns, ext.exitCode)
                        insns.add(VarInsnNode(Opcodes.ISTORE, exitCodeSlot))
                        insns.add(JumpInsnNode(Opcodes.GOTO, commonEpilogueLabel))
                    }

                    if (commonReached) {
                        if (shape.externalExits.isNotEmpty()) {
                            insns.add(commonEpilogueLabel)
                        }
                        emitBatchSpillStoresFromLocals(shape.liveOutSlots)
                        if (shape.returnsExitCode) {
                            insns.add(VarInsnNode(Opcodes.ILOAD, exitCodeSlot))
                            insns.add(InsnNode(Opcodes.IRETURN))
                        } else {
                            insns.add(InsnNode(Opcodes.RETURN))
                        }
                    }
                }
            }
        }

        private fun emitNode(node: IrNode) {
            if (!node.entryJvmReachable) {
                jvmReachable = false
                return
            }
            when (node) {
                is IrNode.InsnNode -> emitInsn(node.insn)
                is IrNode.BlockNode -> emitBlockNode(node)
                is IrNode.LoopNode -> emitLoopNode(node)
                is IrNode.IfNode -> emitIfNode(node)
                is IrNode.OutlinedCallNode -> emitOutlinedCallNode(node)
            }
        }

        private fun emitBlockNode(node: IrNode.BlockNode) {
            val endLbl = LabelNode()
            val state = ControlBlockState(
                blockId = node.blockInfo.id,
                kind = IrBlockKind.BLOCK,
                unwindHeight = node.openInsn.frameBaseHeight,
                labelTypes = node.blockInfo.branchLabelTypes,
                entryLabel = LabelNode(),
                elseLabel = null,
                endLabel = endLbl,
                endReachable = false,
                elseReachable = false,
            )
            pushBlock(state)
            emitNodeList(node.body)
            if (jvmReachable) state.endReachable = true
            if (state.endReachable) {
                insns.add(endLbl)
            }
            jvmReachable = state.endReachable
            popBlock(state)
        }

        private fun emitLoopNode(node: IrNode.LoopNode) {
            val loopStart = LabelNode()
            val endLbl = LabelNode()
            val state = ControlBlockState(
                blockId = node.blockInfo.id,
                kind = IrBlockKind.LOOP,
                unwindHeight = node.openInsn.frameBaseHeight,
                labelTypes = node.blockInfo.branchLabelTypes,
                entryLabel = loopStart,
                elseLabel = null,
                endLabel = endLbl,
                endReachable = false,
                elseReachable = false,
            )
            pushBlock(state)
            insns.add(loopStart)
            emitNodeList(node.body)
            popBlock(state)
        }

        private fun emitIfNode(node: IrNode.IfNode) {
            val hasElse = node.elseInsn != null
            val elseLbl = if (hasElse) LabelNode() else null
            val endLbl = LabelNode()
            val state = ControlBlockState(
                blockId = node.blockInfo.id,
                kind = IrBlockKind.IF,
                unwindHeight = node.openInsn.frameBaseHeight,
                labelTypes = node.blockInfo.branchLabelTypes,
                entryLabel = LabelNode(),
                elseLabel = elseLbl,
                endLabel = endLbl,
                endReachable = false,
                elseReachable = hasElse,
            )
            recordLine(node.openInsn.wasmOffset)
            pushBlock(state)
            if (hasElse) {
                insns.add(JumpInsnNode(Opcodes.IFEQ, elseLbl))
            } else {
                state.endReachable = true
                insns.add(JumpInsnNode(Opcodes.IFEQ, endLbl))
            }
            emitNodeList(node.thenBody)
            if (jvmReachable) {
                state.endReachable = true
                if (hasElse) {
                    insns.add(JumpInsnNode(Opcodes.GOTO, endLbl))
                }
            }
            if (hasElse) {
                insns.add(elseLbl!!)
                jvmReachable = true
                emitNodeList(node.elseBody)
                if (jvmReachable) {
                    state.endReachable = true
                }
            }
            if (state.endReachable) {
                insns.add(endLbl)
            }
            jvmReachable = state.endReachable
            popBlock(state)
        }

        private fun emitOutlinedCallNode(node: IrNode.OutlinedCallNode) {
            val helper = node.helper
            recordLine(node.firstInsnOffset)

            val fl = frameLayout
            if (fl != null) {
                insns.add(VarInsnNode(Opcodes.ALOAD, instSlot))
                for ((kind, _) in fl.arrayParams) {
                    insns.add(VarInsnNode(Opcodes.ALOAD, frameArraySlots[kind]))
                }
            } else {
                emitBatchSpillStoresFromLocals(helper.spillInLocals)

                insns.add(VarInsnNode(Opcodes.ALOAD, instSlot))
                for ((localIdx, vt) in helper.directParams) {
                    val srcSlot = localSlots[localIdx]
                    check(srcSlot >= 0) { "Unallocated local slot for Wasm local $localIdx in ${helper.name} directParams" }
                    insns.add(VarInsnNode(AotNames.loadOpcode(vt), srcSlot))
                }
            }
            insns.add(
                MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    ctx.funcTarget(fn.index).ownerInternalName,
                    helper.name,
                    helper.descriptor,
                    false,
                ),
            )

            when (val shape = helper.returnShape) {
                is ReturnShape.Void -> {
                    if (!helper.normalExitReachable) {
                        insns.add(InsnNode(Opcodes.ACONST_NULL))
                        insns.add(InsnNode(Opcodes.ATHROW))
                        jvmReachable = false
                    }
                }
                is ReturnShape.DirectStackValue -> {}
                is ReturnShape.DirectLiveOut -> {
                    val dstSlot = localSlots[shape.localIndex]
                    check(dstSlot >= 0) { "Unallocated local slot for Wasm local ${shape.localIndex} in ${helper.name} DirectLiveOut" }
                    insns.add(VarInsnNode(AotNames.storeOpcode(shape.valType), dstSlot))
                }
                is ReturnShape.SpillAndExitCode -> withScratchScope {
                    var exitCodeScratch = -1
                    if (shape.returnsExitCode) {
                        exitCodeScratch = allocSingleScratch(IrValType.I32)
                        insns.add(VarInsnNode(Opcodes.ISTORE, exitCodeScratch))
                    }
                    emitBatchSpillLoadsToLocals(shape.liveOutSlots) { localIdx ->
                        localSlots[localIdx].also { slot ->
                            check(slot >= 0) { "Unallocated local slot for Wasm local $localIdx in ${helper.name} liveOut" }
                        }
                    }
                    if (!shape.returnsExitCode) {
                        for (slotSpec in shape.normalStackSlots) {
                            emitSpillLoadToStack(slotSpec.valType, slotSpec.typeIndex)
                        }
                        if (!helper.normalExitReachable) {
                            insns.add(InsnNode(Opcodes.ACONST_NULL))
                            insns.add(InsnNode(Opcodes.ATHROW))
                            jvmReachable = false
                        }
                    } else {
                        val normalLabel = LabelNode()
                        val exitLabels = shape.externalExits.map { LabelNode() }
                        val dflt = if (helper.normalExitReachable) normalLabel else exitLabels.last()
                        insns.add(VarInsnNode(Opcodes.ILOAD, exitCodeScratch))
                        insns.add(TableSwitchInsnNode(1, shape.externalExits.size, dflt, *exitLabels.toTypedArray()))

                        val prefixTypes = node.startStackBefore.subList(0, node.entryHeight)
                        for (idx in shape.externalExits.indices) {
                            val ext = shape.externalExits[idx]
                            val extLabel = exitLabels[idx]
                            insns.add(extLabel)
                            val fullStack = ArrayList<IrStackType>(node.entryHeight + ext.labelTypes.size).apply {
                                addAll(prefixTypes)
                                for (t in ext.labelTypes) add(IrStackType.Concrete(t))
                            }
                            for (slotSpec in ext.spillSlots) {
                                emitSpillLoadToStack(slotSpec.valType, slotSpec.typeIndex)
                            }
                            emitBranchFromStack(
                                targetBlockId = ext.blockId,
                                labelTypes = ext.labelTypes,
                                wasmUnwindHeight = ext.target?.unwindHeight ?: 0,
                                stackAtBranch = fullStack,
                            )
                        }
                        if (helper.normalExitReachable) {
                            insns.add(normalLabel)
                            for (slotSpec in shape.normalStackSlots) {
                                emitSpillLoadToStack(slotSpec.valType, slotSpec.typeIndex)
                            }
                            jvmReachable = true
                        } else {
                            jvmReachable = false
                        }
                    }
                }
            }
        }

        private fun emitInsn(insn: IrTypedInsn) {
            if (!jvmReachable || insn.polymorphicBefore) return
            val op = insn.op
            if (op is WasmOp.Nop) return
            recordLine(insn.wasmOffset)

            when (op) {
                is WasmOp.Nop, is WasmOp.Block, is WasmOp.Loop, is WasmOp.If, is WasmOp.Else, is WasmOp.End -> {}
                is WasmOp.Unreachable -> {
                    insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, AotNames.TRAP_SUPPORT_INTERNAL, "unreachable", "()Ljava/lang/Void;", false))
                    insns.add(InsnNode(Opcodes.POP))
                    insns.add(InsnNode(Opcodes.ACONST_NULL))
                    insns.add(InsnNode(Opcodes.ATHROW))
                    jvmReachable = false
                }
                is WasmOp.Return -> {
                    emitReturnFromStack(insn.stackBefore)
                    jvmReachable = false
                }
                is WasmOp.Br -> {
                    emitBranchFromStack(op.target.targetBlockId, op.target.labelTypes, op.target.unwindHeight, insn.stackBefore)
                    jvmReachable = false
                }
                is WasmOp.BrIf -> {
                    val stackAfterCond = insn.stackAfter
                    val target = op.target
                    val internalState = blockMap[target.targetBlockId]
                    val unwindH = if (internalState != null) target.unwindHeight else helperEntryHeight
                    val excess = stackAfterCond.size - target.labelTypes.size - unwindH
                    if (internalState != null && internalState.blockId != 0 && excess == 0) {
                        val dst = if (internalState.kind == IrBlockKind.LOOP) {
                            internalState.entryLabel
                        } else {
                            internalState.endReachable = true
                            internalState.endLabel
                        }
                        insns.add(JumpInsnNode(Opcodes.IFNE, dst))
                    } else {
                        val skipLabel = LabelNode()
                        insns.add(JumpInsnNode(Opcodes.IFEQ, skipLabel))
                        emitBranchFromStack(target.targetBlockId, target.labelTypes, target.unwindHeight, stackAfterCond)
                        insns.add(skipLabel)
                    }
                }
                is WasmOp.BrTable -> {
                    val stackAfterSelector = insn.stackBefore.dropLast(1)
                    if (op.targets.isEmpty()) {
                        insns.add(InsnNode(Opcodes.POP))
                        val dt = op.defaultTarget
                        emitBranchFromStack(dt.targetBlockId, dt.labelTypes, dt.unwindHeight, stackAfterSelector)
                        jvmReachable = false
                        return
                    }
                    val targetOrder = LinkedHashMap<Int, wasm2kt.tooling.ir.IrBranchTarget>()
                    for (t in op.targets) targetOrder.putIfAbsent(t.targetBlockId, t)
                    targetOrder.putIfAbsent(op.defaultTarget.targetBlockId, op.defaultTarget)

                    val directLabels = HashMap<Int, LabelNode>()
                    val trampolines = LinkedHashMap<Int, Pair<LabelNode, wasm2kt.tooling.ir.IrBranchTarget>>()
                    for ((bid, tgt) in targetOrder) {
                        val internalState = blockMap[bid]
                        val unwindH = if (internalState != null) tgt.unwindHeight else helperEntryHeight
                        val excess = stackAfterSelector.size - tgt.labelTypes.size - unwindH
                        if (internalState != null && internalState.blockId != 0 && excess == 0) {
                            val lbl = if (internalState.kind == IrBlockKind.LOOP) {
                                internalState.entryLabel
                            } else {
                                internalState.endReachable = true
                                internalState.endLabel
                            }
                            directLabels[bid] = lbl
                        } else {
                            trampolines[bid] = LabelNode() to tgt
                        }
                    }

                    fun labelFor(bid: Int): LabelNode =
                        directLabels[bid] ?: trampolines.getValue(bid).first

                    val caseLabels = Array(op.targets.size) { i -> labelFor(op.targets[i].targetBlockId) }
                    val dfltLabel = labelFor(op.defaultTarget.targetBlockId)
                    insns.add(TableSwitchInsnNode(0, op.targets.size - 1, dfltLabel, *caseLabels))
                    for ((_, pair) in trampolines) {
                        val (lbl, tgt) = pair
                        insns.add(lbl)
                        emitBranchFromStack(tgt.targetBlockId, tgt.labelTypes, tgt.unwindHeight, stackAfterSelector)
                    }
                    jvmReachable = false
                }
                is WasmOp.Call -> emitDirectCall(op)
                is WasmOp.CallIndirect -> emitIndirectCall(op)
                is WasmOp.Drop -> {
                    val isWide = (op.droppedType as? IrStackType.Concrete)?.valType?.let {
                        it == IrValType.I64 || it == IrValType.F64
                    } == true
                    insns.add(InsnNode(if (isWide) Opcodes.POP2 else Opcodes.POP))
                }
                is WasmOp.Select -> {
                    val vt = (op.resolvedType as IrStackType.Concrete).valType
                    val takeV2 = LabelNode()
                    val endLbl = LabelNode()
                    if (AotNames.slotSize(vt) == 1) {
                        insns.add(JumpInsnNode(Opcodes.IFEQ, takeV2))
                        insns.add(InsnNode(Opcodes.POP))
                        insns.add(JumpInsnNode(Opcodes.GOTO, endLbl))
                        insns.add(takeV2)
                        insns.add(InsnNode(Opcodes.SWAP))
                        insns.add(InsnNode(Opcodes.POP))
                        insns.add(endLbl)
                    } else withScratchScope {
                        val tmp = allocSingleScratch(vt)
                        insns.add(JumpInsnNode(Opcodes.IFEQ, takeV2))
                        insns.add(InsnNode(Opcodes.POP2))
                        insns.add(JumpInsnNode(Opcodes.GOTO, endLbl))
                        insns.add(takeV2)
                        insns.add(VarInsnNode(AotNames.storeOpcode(vt), tmp))
                        insns.add(InsnNode(Opcodes.POP2))
                        insns.add(VarInsnNode(AotNames.loadOpcode(vt), tmp))
                        insns.add(endLbl)
                    }
                }
                is WasmOp.LocalGet -> {
                    val fl = frameLayout
                    if (fl != null) {
                        val kind = LocalFrameLayout.kindOf(op.valType)
                        insns.add(VarInsnNode(Opcodes.ALOAD, frameArraySlots[kind]))
                        pushInt(insns, fl.indexInTypeArray[op.localIndex])
                        insns.add(InsnNode(arrayLoadOpcode(op.valType)))
                        if (op.valType == IrValType.FUNCREF) {
                            insns.add(TypeInsnNode(Opcodes.CHECKCAST, AotNames.FUNC_REF_INTERNAL))
                        }
                    } else {
                        insns.add(VarInsnNode(AotNames.loadOpcode(op.valType), localSlots[op.localIndex]))
                    }
                }
                is WasmOp.LocalSet -> {
                    val fl = frameLayout
                    if (fl != null) {
                        withScratchScope {
                            val tmp = allocSingleScratch(op.valType)
                            val kind = LocalFrameLayout.kindOf(op.valType)
                            insns.add(VarInsnNode(AotNames.storeOpcode(op.valType), tmp))
                            insns.add(VarInsnNode(Opcodes.ALOAD, frameArraySlots[kind]))
                            pushInt(insns, fl.indexInTypeArray[op.localIndex])
                            insns.add(VarInsnNode(AotNames.loadOpcode(op.valType), tmp))
                            insns.add(InsnNode(arrayStoreOpcode(op.valType)))
                        }
                    } else {
                        insns.add(VarInsnNode(AotNames.storeOpcode(op.valType), localSlots[op.localIndex]))
                    }
                }
                is WasmOp.LocalTee -> {
                    val fl = frameLayout
                    if (fl != null) {
                        withScratchScope {
                            val tmp = allocSingleScratch(op.valType)
                            val kind = LocalFrameLayout.kindOf(op.valType)
                            insns.add(VarInsnNode(AotNames.storeOpcode(op.valType), tmp))
                            insns.add(VarInsnNode(Opcodes.ALOAD, frameArraySlots[kind]))
                            pushInt(insns, fl.indexInTypeArray[op.localIndex])
                            insns.add(VarInsnNode(AotNames.loadOpcode(op.valType), tmp))
                            insns.add(InsnNode(arrayStoreOpcode(op.valType)))
                            insns.add(VarInsnNode(AotNames.loadOpcode(op.valType), tmp))
                        }
                    } else {
                        val dupOp = if (AotNames.slotSize(op.valType) == 2) Opcodes.DUP2 else Opcodes.DUP
                        insns.add(InsnNode(dupOp))
                        insns.add(VarInsnNode(AotNames.storeOpcode(op.valType), localSlots[op.localIndex]))
                    }
                }
                is WasmOp.GlobalGet -> {
                    val fieldName = AotNames.globalFieldName(op.globalIndex)
                    insns.add(VarInsnNode(Opcodes.ALOAD, instSlot))
                    if (ctx.isSharedGlobal(op.globalIndex)) {
                        val boxInternal = AotNames.boxedGlobalInternalName(op.valType)
                        insns.add(FieldInsnNode(Opcodes.GETFIELD, ctx.instanceInternalName, fieldName, "L$boxInternal;"))
                        insns.add(FieldInsnNode(Opcodes.GETFIELD, boxInternal, "value", AotNames.jvmDesc(op.valType)))
                    } else {
                        insns.add(FieldInsnNode(Opcodes.GETFIELD, ctx.instanceInternalName, fieldName, AotNames.jvmDesc(op.valType)))
                    }
                }
                is WasmOp.GlobalSet -> {
                    val fieldName = AotNames.globalFieldName(op.globalIndex)
                    if (ctx.isSharedGlobal(op.globalIndex)) {
                        val boxDesc = AotNames.boxedGlobalDesc(op.valType)
                        insns.add(VarInsnNode(Opcodes.ALOAD, instSlot))
                        insns.add(FieldInsnNode(Opcodes.GETFIELD, ctx.instanceInternalName, fieldName, boxDesc))
                        insns.add(
                            MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                AotNames.OPS_INTERNAL,
                                AotNames.globalSetHelperName(op.valType),
                                "(${AotNames.jvmDesc(op.valType)}$boxDesc)V",
                                false,
                            ),
                        )
                    } else if (AotNames.slotSize(op.valType) == 1) {
                        insns.add(VarInsnNode(Opcodes.ALOAD, instSlot))
                        insns.add(InsnNode(Opcodes.SWAP))
                        insns.add(FieldInsnNode(Opcodes.PUTFIELD, ctx.instanceInternalName, fieldName, AotNames.jvmDesc(op.valType)))
                    } else withScratchScope {
                        val tmp = allocSingleScratch(op.valType)
                        insns.add(VarInsnNode(AotNames.storeOpcode(op.valType), tmp))
                        insns.add(VarInsnNode(Opcodes.ALOAD, instSlot))
                        insns.add(VarInsnNode(AotNames.loadOpcode(op.valType), tmp))
                        insns.add(FieldInsnNode(Opcodes.PUTFIELD, ctx.instanceInternalName, fieldName, AotNames.jvmDesc(op.valType)))
                    }
                }
                is WasmOp.RefNull -> insns.add(InsnNode(Opcodes.ACONST_NULL))
                is WasmOp.RefIsNull -> {
                    val nullLbl = LabelNode()
                    val endLbl = LabelNode()
                    insns.add(JumpInsnNode(Opcodes.IFNULL, nullLbl))
                    insns.add(InsnNode(Opcodes.ICONST_0))
                    insns.add(JumpInsnNode(Opcodes.GOTO, endLbl))
                    insns.add(nullLbl)
                    insns.add(InsnNode(Opcodes.ICONST_1))
                    insns.add(endLbl)
                }
                is WasmOp.RefFunc -> {
                    insns.add(VarInsnNode(Opcodes.ALOAD, instSlot))
                    insns.add(FieldInsnNode(Opcodes.GETFIELD, ctx.instanceInternalName, "funcRefs", "[${AotNames.FUNC_REF_DESC}"))
                    pushInt(insns, op.funcIndex)
                    insns.add(InsnNode(Opcodes.AALOAD))
                }
                is WasmOp.TableGet -> {
                    pushTable(op.tableIndex)
                    val (helperName, retDesc) = when (op.elemType) {
                        IrValType.FUNCREF -> "tableGetFuncRef" to AotNames.FUNC_REF_DESC
                        else -> "tableGetExternRef" to "Ljava/lang/Object;"
                    }
                    insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, AotNames.OPS_INTERNAL, helperName, "(I${AotNames.WASM_TABLE_DESC})$retDesc", false))
                }
                is WasmOp.TableSet -> {
                    pushTable(op.tableIndex)
                    insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, AotNames.OPS_INTERNAL, "tableSet", "(ILjava/lang/Object;${AotNames.WASM_TABLE_DESC})V", false))
                }
                is WasmOp.TableGrow -> {
                    pushTable(op.tableIndex)
                    insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, AotNames.OPS_INTERNAL, "tableGrow", "(Ljava/lang/Object;I${AotNames.WASM_TABLE_DESC})I", false))
                }
                is WasmOp.TableSize -> {
                    pushTable(op.tableIndex)
                    insns.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, AotNames.WASM_TABLE_INTERNAL, "size", "()I", false))
                }
                is WasmOp.TableFill -> {
                    pushTable(op.tableIndex)
                    insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, AotNames.OPS_INTERNAL, "tableFill", "(ILjava/lang/Object;I${AotNames.WASM_TABLE_DESC})V", false))
                }
                is WasmOp.TableCopy -> {
                    pushTable(op.dstTableIndex)
                    pushTable(op.srcTableIndex)
                    insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, AotNames.OPS_INTERNAL, "tableCopy", "(III${AotNames.WASM_TABLE_DESC}${AotNames.WASM_TABLE_DESC})V", false))
                }
                is WasmOp.TableInit -> {
                    insns.add(VarInsnNode(Opcodes.ALOAD, instSlot))
                    insns.add(FieldInsnNode(Opcodes.GETFIELD, ctx.instanceInternalName, "elemSegments", AotNames.ELEM_STORE_DESC))
                    pushInt(insns, op.elemIndex)
                    pushTable(op.tableIndex)
                    insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, AotNames.OPS_INTERNAL, "tableInit", "(III${AotNames.ELEM_STORE_DESC}I${AotNames.WASM_TABLE_DESC})V", false))
                }
                is WasmOp.ElemDrop -> {
                    insns.add(VarInsnNode(Opcodes.ALOAD, instSlot))
                    insns.add(FieldInsnNode(Opcodes.GETFIELD, ctx.instanceInternalName, "elemSegments", AotNames.ELEM_STORE_DESC))
                    pushInt(insns, op.elemIndex)
                    insns.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, AotNames.ELEM_STORE_INTERNAL, "drop", "(I)V", false))
                }
                is WasmOp.Load -> emitLoad(op)
                is WasmOp.Store -> emitStore(op)
                is WasmOp.MemorySize -> {
                    pushMemory(op.memoryIndex)
                    insns.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, AotNames.LINEAR_MEMORY_INTERNAL, "pages", "()I", false))
                }
                is WasmOp.MemoryGrow -> {
                    pushMemory(op.memoryIndex)
                    insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, AotNames.OPS_INTERNAL, "memoryGrow", "(I${AotNames.LINEAR_MEMORY_DESC})I", false))
                }
                is WasmOp.MemoryInit -> {
                    insns.add(VarInsnNode(Opcodes.ALOAD, instSlot))
                    insns.add(FieldInsnNode(Opcodes.GETFIELD, ctx.instanceInternalName, "dataSegments", AotNames.DATA_STORE_DESC))
                    pushInt(insns, op.dataIndex)
                    pushMemory(op.memoryIndex)
                    insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, AotNames.OPS_INTERNAL, "memoryInit", "(III${AotNames.DATA_STORE_DESC}I${AotNames.LINEAR_MEMORY_DESC})V", false))
                }
                is WasmOp.DataDrop -> {
                    insns.add(VarInsnNode(Opcodes.ALOAD, instSlot))
                    insns.add(FieldInsnNode(Opcodes.GETFIELD, ctx.instanceInternalName, "dataSegments", AotNames.DATA_STORE_DESC))
                    pushInt(insns, op.dataIndex)
                    insns.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, AotNames.DATA_STORE_INTERNAL, "drop", "(I)V", false))
                }
                is WasmOp.MemoryCopy -> {
                    pushMemory(op.dstMemoryIndex)
                    pushMemory(op.srcMemoryIndex)
                    insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, AotNames.OPS_INTERNAL, "memoryCopy", "(III${AotNames.LINEAR_MEMORY_DESC}${AotNames.LINEAR_MEMORY_DESC})V", false))
                }
                is WasmOp.MemoryFill -> {
                    pushMemory(op.memoryIndex)
                    insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, AotNames.OPS_INTERNAL, "memoryFill", "(III${AotNames.LINEAR_MEMORY_DESC})V", false))
                }
                is WasmOp.I32Const -> pushInt(insns, op.value)
                is WasmOp.I64Const -> pushLong(insns, op.value)
                is WasmOp.F32Const -> pushF32Bits(insns, op.rawBits)
                is WasmOp.F64Const -> pushF64Bits(insns, op.rawBits)
                is WasmOp.Unary -> emitUnary(op.op)
                is WasmOp.Binary -> emitBinary(op.op)
            }
        }

        private fun pushMemory(memoryIndex: Int) {
            insns.add(VarInsnNode(Opcodes.ALOAD, instSlot))
            insns.add(FieldInsnNode(Opcodes.GETFIELD, ctx.instanceInternalName, AotNames.memoryFieldName(memoryIndex), AotNames.LINEAR_MEMORY_DESC))
        }

        private fun pushTable(tableIndex: Int) {
            insns.add(VarInsnNode(Opcodes.ALOAD, instSlot))
            insns.add(FieldInsnNode(Opcodes.GETFIELD, ctx.instanceInternalName, AotNames.tableFieldName(tableIndex), AotNames.WASM_TABLE_DESC))
        }

        private fun emitReturnFromStack(stackBefore: List<IrStackType>) {
            if (helperSpec != null) {
                emitBranchFromStack(0, fn.type.results, helperEntryHeight, stackBefore)
            } else {
                emitMethodReturnFromTop(fn.type.results)
            }
        }

        private fun emitBranchFromStack(
            targetBlockId: Int,
            labelTypes: List<IrValType>,
            wasmUnwindHeight: Int,
            stackAtBranch: List<IrStackType>,
        ) {
            val internalState = blockMap[targetBlockId]
            val targetUnwind = if (internalState != null) wasmUnwindHeight else helperEntryHeight
            val excess = stackAtBranch.size - labelTypes.size - targetUnwind
            if (excess > 0) {
                if (labelTypes.isEmpty()) {
                    for (idx in (stackAtBranch.size - 1) downTo targetUnwind) {
                        popStackItem(stackAtBranch[idx])
                    }
                } else withScratchScope {
                    val tmpSlots = allocScratch(labelTypes)
                    for (k in labelTypes.lastIndex downTo 0) {
                        insns.add(VarInsnNode(AotNames.storeOpcode(labelTypes[k]), tmpSlots[k]))
                    }
                    for (idx in (stackAtBranch.size - 1 - labelTypes.size) downTo targetUnwind) {
                        popStackItem(stackAtBranch[idx])
                    }
                    for (k in labelTypes.indices) {
                        insns.add(VarInsnNode(AotNames.loadOpcode(labelTypes[k]), tmpSlots[k]))
                    }
                }
            }

            if (internalState != null) {
                if (internalState.blockId == 0) {
                    emitMethodReturnFromTop(fn.type.results)
                } else {
                    val dst = if (internalState.kind == IrBlockKind.LOOP) {
                        internalState.entryLabel
                    } else {
                        internalState.endReachable = true
                        internalState.endLabel
                    }
                    insns.add(JumpInsnNode(Opcodes.GOTO, dst))
                }
            } else {
                val extLbl = externalExitLabels[targetBlockId]
                    ?: error("Missing external exit label for block $targetBlockId in helper ${helperSpec?.name}")
                insns.add(JumpInsnNode(Opcodes.GOTO, extLbl))
            }
        }

        private fun popStackItem(st: IrStackType) {
            val isWide = (st as? IrStackType.Concrete)?.valType?.let {
                it == IrValType.I64 || it == IrValType.F64
            } == true
            insns.add(InsnNode(if (isWide) Opcodes.POP2 else Opcodes.POP))
        }

        private fun emitMethodReturnFromTop(results: List<IrValType>) {
            when (results.size) {
                0 -> insns.add(InsnNode(Opcodes.RETURN))
                1 -> insns.add(InsnNode(AotNames.returnOpcode(results[0])))
                else -> withScratchScope {
                    val tupleClass = ctx.multiValueReturnClass(results)
                    val tmpSlots = allocScratch(results)
                    for (k in results.lastIndex downTo 0) {
                        insns.add(VarInsnNode(AotNames.storeOpcode(results[k]), tmpSlots[k]))
                    }
                    val hasRefs = results.any { AotNames.isRef(it) }
                    val tupleScratch = allocSingleScratch(IrValType.EXTERNREF)
                    if (hasRefs) {
                        insns.add(TypeInsnNode(Opcodes.NEW, tupleClass))
                        insns.add(InsnNode(Opcodes.DUP))
                        insns.add(MethodInsnNode(Opcodes.INVOKESPECIAL, tupleClass, "<init>", "()V", false))
                        insns.add(VarInsnNode(Opcodes.ASTORE, tupleScratch))
                    } else {
                        val fieldName = "mv_${AotNames.multiValueKey(results)}"
                        insns.add(VarInsnNode(Opcodes.ALOAD, instSlot))
                        insns.add(FieldInsnNode(Opcodes.GETFIELD, ctx.instanceInternalName, fieldName, "L$tupleClass;"))
                        insns.add(VarInsnNode(Opcodes.ASTORE, tupleScratch))
                    }
                    for (k in results.indices) {
                        insns.add(VarInsnNode(Opcodes.ALOAD, tupleScratch))
                        insns.add(VarInsnNode(AotNames.loadOpcode(results[k]), tmpSlots[k]))
                        insns.add(FieldInsnNode(Opcodes.PUTFIELD, tupleClass, "v$k", AotNames.jvmDesc(results[k])))
                    }
                    insns.add(VarInsnNode(Opcodes.ALOAD, tupleScratch))
                    insns.add(InsnNode(Opcodes.ARETURN))
                }
            }
        }

        private fun emitDirectCall(op: WasmOp.Call) {
            val target = ctx.funcTarget(op.funcIndex)
            val params = op.funcType.params
            if (target.overflowParamCount > 0) {
                val directCount = params.size - target.overflowParamCount
                val spillAlloc = TypeSlotCounters()
                val indices = IntArray(target.overflowParamCount)
                for (i in 0 until target.overflowParamCount) {
                    indices[i] = spillAlloc.next(params[directCount + i])
                }
                for (i in (target.overflowParamCount - 1) downTo 0) {
                    emitSpillStoreFromStackTop(params[directCount + i], indices[i])
                }
            }
            insns.add(VarInsnNode(Opcodes.ALOAD, instSlot))
            insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, target.ownerInternalName, target.methodName, target.methodDescriptor, false))
            unpackMultiValueIfNeeded(op.funcType.results)
        }

        private fun emitIndirectCall(op: WasmOp.CallIndirect) {
            val params = op.funcType.params
            val directCount = AotNames.countDirectParams(params)
            if (directCount < params.size) withScratchScope {
                val elemTmp = allocSingleScratch(IrValType.I32)
                insns.add(VarInsnNode(Opcodes.ISTORE, elemTmp))
                val spillAlloc = TypeSlotCounters()
                val indices = IntArray(params.size - directCount)
                for (i in directCount until params.size) {
                    indices[i - directCount] = spillAlloc.next(params[i])
                }
                for (i in (params.size - 1) downTo directCount) {
                    emitSpillStoreFromStackTop(params[i], indices[i - directCount])
                }
                insns.add(VarInsnNode(Opcodes.ILOAD, elemTmp))
            }
            pushTable(op.tableIndex)
            insns.add(VarInsnNode(Opcodes.ALOAD, instSlot))
            val dispatch = ctx.callIndirectDispatcher(op.funcType)
            insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, dispatch.ownerInternalName, dispatch.methodName, dispatch.methodDescriptor, false))
            unpackMultiValueIfNeeded(op.funcType.results)
        }

        private fun unpackMultiValueIfNeeded(results: List<IrValType>) {
            if (results.size <= 1) return
            withScratchScope {
                val tupleClass = ctx.multiValueReturnClass(results)
                val tupleSlot = allocSingleScratch(IrValType.EXTERNREF)
                insns.add(VarInsnNode(Opcodes.ASTORE, tupleSlot))
                for (i in results.indices) {
                    insns.add(VarInsnNode(Opcodes.ALOAD, tupleSlot))
                    insns.add(FieldInsnNode(Opcodes.GETFIELD, tupleClass, "v$i", AotNames.jvmDesc(results[i])))
                }
            }
        }

        private fun emitLoad(op: WasmOp.Load) {
            pushMemory(op.memoryIndex)
            val methodName = when (op.kind) {
                LoadKind.I32 -> "loadI32"
                LoadKind.I64 -> "loadI64"
                LoadKind.F32 -> "loadF32"
                LoadKind.F64 -> "loadF64"
                LoadKind.I32_8S -> "loadI32_8s"
                LoadKind.I32_8U -> "loadI32_8u"
                LoadKind.I32_16S -> "loadI32_16s"
                LoadKind.I32_16U -> "loadI32_16u"
                LoadKind.I64_8S -> "loadI64_8s"
                LoadKind.I64_8U -> "loadI64_8u"
                LoadKind.I64_16S -> "loadI64_16s"
                LoadKind.I64_16U -> "loadI64_16u"
                LoadKind.I64_32S -> "loadI64_32s"
                LoadKind.I64_32U -> "loadI64_32u"
            }
            val retDesc = AotNames.jvmDesc(op.kind.resultType)
            if (op.offset == 0L) {
                insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, AotNames.OPS_INTERNAL, methodName, "(I${AotNames.LINEAR_MEMORY_DESC})$retDesc", false))
            } else {
                pushInt(insns, op.offset.toInt())
                insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, AotNames.OPS_INTERNAL, methodName, "(I${AotNames.LINEAR_MEMORY_DESC}I)$retDesc", false))
            }
        }

        private fun emitStore(op: WasmOp.Store) {
            pushMemory(op.memoryIndex)
            val methodName = when (op.kind) {
                StoreKind.I32 -> "storeI32"
                StoreKind.I64 -> "storeI64"
                StoreKind.F32 -> "storeF32"
                StoreKind.F64 -> "storeF64"
                StoreKind.I32_8 -> "storeI32_8"
                StoreKind.I32_16 -> "storeI32_16"
                StoreKind.I64_8 -> "storeI64_8"
                StoreKind.I64_16 -> "storeI64_16"
                StoreKind.I64_32 -> "storeI64_32"
            }
            val valDesc = AotNames.jvmDesc(op.kind.valueType)
            if (op.offset == 0L) {
                insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, AotNames.OPS_INTERNAL, methodName, "(I$valDesc${AotNames.LINEAR_MEMORY_DESC})V", false))
            } else {
                pushInt(insns, op.offset.toInt())
                insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, AotNames.OPS_INTERNAL, methodName, "(I$valDesc${AotNames.LINEAR_MEMORY_DESC}I)V", false))
            }
        }

        private fun emitUnary(op: UnaryOp) {
            when (op) {
                UnaryOp.I32_EQZ -> emitZeroCmp(Opcodes.IFEQ)
                UnaryOp.I64_EQZ -> {
                    insns.add(InsnNode(Opcodes.LCONST_0))
                    insns.add(InsnNode(Opcodes.LCMP))
                    emitZeroCmp(Opcodes.IFEQ)
                }
                UnaryOp.I32_CLZ -> insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "numberOfLeadingZeros", "(I)I", false))
                UnaryOp.I32_CTZ -> insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "numberOfTrailingZeros", "(I)I", false))
                UnaryOp.I32_POPCNT -> insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "bitCount", "(I)I", false))
                UnaryOp.I64_CLZ -> {
                    insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "numberOfLeadingZeros", "(J)I", false))
                    insns.add(InsnNode(Opcodes.I2L))
                }
                UnaryOp.I64_CTZ -> {
                    insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "numberOfTrailingZeros", "(J)I", false))
                    insns.add(InsnNode(Opcodes.I2L))
                }
                UnaryOp.I64_POPCNT -> {
                    insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "bitCount", "(J)I", false))
                    insns.add(InsnNode(Opcodes.I2L))
                }
                UnaryOp.F32_ABS -> numericsCall("f32Abs", "(F)F")
                UnaryOp.F32_NEG -> numericsCall("f32Neg", "(F)F")
                UnaryOp.F32_CEIL -> numericsCall("f32Ceil", "(F)F")
                UnaryOp.F32_FLOOR -> numericsCall("f32Floor", "(F)F")
                UnaryOp.F32_TRUNC -> numericsCall("f32Trunc", "(F)F")
                UnaryOp.F32_NEAREST -> numericsCall("f32Nearest", "(F)F")
                UnaryOp.F32_SQRT -> numericsCall("f32Sqrt", "(F)F")
                UnaryOp.F64_ABS -> numericsCall("f64Abs", "(D)D")
                UnaryOp.F64_NEG -> numericsCall("f64Neg", "(D)D")
                UnaryOp.F64_CEIL -> numericsCall("f64Ceil", "(D)D")
                UnaryOp.F64_FLOOR -> numericsCall("f64Floor", "(D)D")
                UnaryOp.F64_TRUNC -> numericsCall("f64Trunc", "(D)D")
                UnaryOp.F64_NEAREST -> numericsCall("f64Nearest", "(D)D")
                UnaryOp.F64_SQRT -> numericsCall("f64Sqrt", "(D)D")
                UnaryOp.I32_WRAP_I64 -> insns.add(InsnNode(Opcodes.L2I))
                UnaryOp.I32_TRUNC_F32_S -> numericsCall("i32TruncF32S", "(F)I")
                UnaryOp.I32_TRUNC_F32_U -> numericsCall("i32TruncF32U", "(F)I")
                UnaryOp.I32_TRUNC_F64_S -> numericsCall("i32TruncF64S", "(D)I")
                UnaryOp.I32_TRUNC_F64_U -> numericsCall("i32TruncF64U", "(D)I")
                UnaryOp.I64_EXTEND_I32_S -> insns.add(InsnNode(Opcodes.I2L))
                UnaryOp.I64_EXTEND_I32_U -> {
                    insns.add(InsnNode(Opcodes.I2L))
                    insns.add(LdcInsnNode(0xFFFF_FFFFL))
                    insns.add(InsnNode(Opcodes.LAND))
                }
                UnaryOp.I64_TRUNC_F32_S -> numericsCall("i64TruncF32S", "(F)J")
                UnaryOp.I64_TRUNC_F32_U -> numericsCall("i64TruncF32U", "(F)J")
                UnaryOp.I64_TRUNC_F64_S -> numericsCall("i64TruncF64S", "(D)J")
                UnaryOp.I64_TRUNC_F64_U -> numericsCall("i64TruncF64U", "(D)J")
                UnaryOp.F32_CONVERT_I32_S -> insns.add(InsnNode(Opcodes.I2F))
                UnaryOp.F32_CONVERT_I32_U -> {
                    insns.add(InsnNode(Opcodes.I2L))
                    insns.add(LdcInsnNode(0xFFFF_FFFFL))
                    insns.add(InsnNode(Opcodes.LAND))
                    insns.add(InsnNode(Opcodes.L2F))
                }
                UnaryOp.F32_CONVERT_I64_S -> insns.add(InsnNode(Opcodes.L2F))
                UnaryOp.F32_CONVERT_I64_U -> numericsCall("f32ConvertI64U", "(J)F")
                UnaryOp.F32_DEMOTE_F64 -> insns.add(InsnNode(Opcodes.D2F))
                UnaryOp.F64_CONVERT_I32_S -> insns.add(InsnNode(Opcodes.I2D))
                UnaryOp.F64_CONVERT_I32_U -> {
                    insns.add(InsnNode(Opcodes.I2L))
                    insns.add(LdcInsnNode(0xFFFF_FFFFL))
                    insns.add(InsnNode(Opcodes.LAND))
                    insns.add(InsnNode(Opcodes.L2D))
                }
                UnaryOp.F64_CONVERT_I64_S -> insns.add(InsnNode(Opcodes.L2D))
                UnaryOp.F64_CONVERT_I64_U -> numericsCall("f64ConvertI64U", "(J)D")
                UnaryOp.F64_PROMOTE_F32 -> insns.add(InsnNode(Opcodes.F2D))
                UnaryOp.I32_REINTERPRET_F32 -> insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Float", "floatToRawIntBits", "(F)I", false))
                UnaryOp.I64_REINTERPRET_F64 -> insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "doubleToRawLongBits", "(D)J", false))
                UnaryOp.F32_REINTERPRET_I32 -> insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Float", "intBitsToFloat", "(I)F", false))
                UnaryOp.F64_REINTERPRET_I64 -> insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false))
                UnaryOp.I32_EXTEND_8_S -> insns.add(InsnNode(Opcodes.I2B))
                UnaryOp.I32_EXTEND_16_S -> insns.add(InsnNode(Opcodes.I2S))
                UnaryOp.I64_EXTEND_8_S -> {
                    insns.add(InsnNode(Opcodes.L2I))
                    insns.add(InsnNode(Opcodes.I2B))
                    insns.add(InsnNode(Opcodes.I2L))
                }
                UnaryOp.I64_EXTEND_16_S -> {
                    insns.add(InsnNode(Opcodes.L2I))
                    insns.add(InsnNode(Opcodes.I2S))
                    insns.add(InsnNode(Opcodes.I2L))
                }
                UnaryOp.I64_EXTEND_32_S -> {
                    insns.add(InsnNode(Opcodes.L2I))
                    insns.add(InsnNode(Opcodes.I2L))
                }
                UnaryOp.I32_TRUNC_SAT_F32_S -> numericsCall("i32TruncSatF32S", "(F)I")
                UnaryOp.I32_TRUNC_SAT_F32_U -> numericsCall("i32TruncSatF32U", "(F)I")
                UnaryOp.I32_TRUNC_SAT_F64_S -> numericsCall("i32TruncSatF64S", "(D)I")
                UnaryOp.I32_TRUNC_SAT_F64_U -> numericsCall("i32TruncSatF64U", "(D)I")
                UnaryOp.I64_TRUNC_SAT_F32_S -> numericsCall("i64TruncSatF32S", "(F)J")
                UnaryOp.I64_TRUNC_SAT_F32_U -> numericsCall("i64TruncSatF32U", "(F)J")
                UnaryOp.I64_TRUNC_SAT_F64_S -> numericsCall("i64TruncSatF64S", "(D)J")
                UnaryOp.I64_TRUNC_SAT_F64_U -> numericsCall("i64TruncSatF64U", "(D)J")
            }
        }

        private fun emitBinary(op: BinaryOp) {
            when (op) {
                BinaryOp.I32_EQ -> emitZeroCmp(Opcodes.IF_ICMPEQ)
                BinaryOp.I32_NE -> emitZeroCmp(Opcodes.IF_ICMPNE)
                BinaryOp.I32_LT_S -> emitZeroCmp(Opcodes.IF_ICMPLT)
                BinaryOp.I32_GT_S -> emitZeroCmp(Opcodes.IF_ICMPGT)
                BinaryOp.I32_LE_S -> emitZeroCmp(Opcodes.IF_ICMPLE)
                BinaryOp.I32_GE_S -> emitZeroCmp(Opcodes.IF_ICMPGE)
                BinaryOp.I32_LT_U -> {
                    insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "compareUnsigned", "(II)I", false))
                    emitZeroCmp(Opcodes.IFLT)
                }
                BinaryOp.I32_GT_U -> {
                    insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "compareUnsigned", "(II)I", false))
                    emitZeroCmp(Opcodes.IFGT)
                }
                BinaryOp.I32_LE_U -> {
                    insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "compareUnsigned", "(II)I", false))
                    emitZeroCmp(Opcodes.IFLE)
                }
                BinaryOp.I32_GE_U -> {
                    insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "compareUnsigned", "(II)I", false))
                    emitZeroCmp(Opcodes.IFGE)
                }

                BinaryOp.I64_EQ -> { insns.add(InsnNode(Opcodes.LCMP)); emitZeroCmp(Opcodes.IFEQ) }
                BinaryOp.I64_NE -> { insns.add(InsnNode(Opcodes.LCMP)); emitZeroCmp(Opcodes.IFNE) }
                BinaryOp.I64_LT_S -> { insns.add(InsnNode(Opcodes.LCMP)); emitZeroCmp(Opcodes.IFLT) }
                BinaryOp.I64_GT_S -> { insns.add(InsnNode(Opcodes.LCMP)); emitZeroCmp(Opcodes.IFGT) }
                BinaryOp.I64_LE_S -> { insns.add(InsnNode(Opcodes.LCMP)); emitZeroCmp(Opcodes.IFLE) }
                BinaryOp.I64_GE_S -> { insns.add(InsnNode(Opcodes.LCMP)); emitZeroCmp(Opcodes.IFGE) }
                BinaryOp.I64_LT_U -> {
                    insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "compareUnsigned", "(JJ)I", false))
                    emitZeroCmp(Opcodes.IFLT)
                }
                BinaryOp.I64_GT_U -> {
                    insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "compareUnsigned", "(JJ)I", false))
                    emitZeroCmp(Opcodes.IFGT)
                }
                BinaryOp.I64_LE_U -> {
                    insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "compareUnsigned", "(JJ)I", false))
                    emitZeroCmp(Opcodes.IFLE)
                }
                BinaryOp.I64_GE_U -> {
                    insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "compareUnsigned", "(JJ)I", false))
                    emitZeroCmp(Opcodes.IFGE)
                }

                BinaryOp.F32_EQ -> { insns.add(InsnNode(Opcodes.FCMPL)); emitZeroCmp(Opcodes.IFEQ) }
                BinaryOp.F32_NE -> { insns.add(InsnNode(Opcodes.FCMPL)); emitZeroCmp(Opcodes.IFNE) }
                BinaryOp.F32_LT -> { insns.add(InsnNode(Opcodes.FCMPG)); emitZeroCmp(Opcodes.IFLT) }
                BinaryOp.F32_LE -> { insns.add(InsnNode(Opcodes.FCMPG)); emitZeroCmp(Opcodes.IFLE) }
                BinaryOp.F32_GT -> { insns.add(InsnNode(Opcodes.FCMPL)); emitZeroCmp(Opcodes.IFGT) }
                BinaryOp.F32_GE -> { insns.add(InsnNode(Opcodes.FCMPL)); emitZeroCmp(Opcodes.IFGE) }

                BinaryOp.F64_EQ -> { insns.add(InsnNode(Opcodes.DCMPL)); emitZeroCmp(Opcodes.IFEQ) }
                BinaryOp.F64_NE -> { insns.add(InsnNode(Opcodes.DCMPL)); emitZeroCmp(Opcodes.IFNE) }
                BinaryOp.F64_LT -> { insns.add(InsnNode(Opcodes.DCMPG)); emitZeroCmp(Opcodes.IFLT) }
                BinaryOp.F64_LE -> { insns.add(InsnNode(Opcodes.DCMPG)); emitZeroCmp(Opcodes.IFLE) }
                BinaryOp.F64_GT -> { insns.add(InsnNode(Opcodes.DCMPL)); emitZeroCmp(Opcodes.IFGT) }
                BinaryOp.F64_GE -> { insns.add(InsnNode(Opcodes.DCMPL)); emitZeroCmp(Opcodes.IFGE) }

                BinaryOp.I32_ADD -> insns.add(InsnNode(Opcodes.IADD))
                BinaryOp.I32_SUB -> insns.add(InsnNode(Opcodes.ISUB))
                BinaryOp.I32_MUL -> insns.add(InsnNode(Opcodes.IMUL))
                BinaryOp.I32_DIV_S -> numericsCall("i32DivS", "(II)I")
                BinaryOp.I32_DIV_U -> numericsCall("i32DivU", "(II)I")
                BinaryOp.I32_REM_S -> numericsCall("i32RemS", "(II)I")
                BinaryOp.I32_REM_U -> numericsCall("i32RemU", "(II)I")
                BinaryOp.I32_AND -> insns.add(InsnNode(Opcodes.IAND))
                BinaryOp.I32_OR -> insns.add(InsnNode(Opcodes.IOR))
                BinaryOp.I32_XOR -> insns.add(InsnNode(Opcodes.IXOR))
                BinaryOp.I32_SHL -> insns.add(InsnNode(Opcodes.ISHL))
                BinaryOp.I32_SHR_S -> insns.add(InsnNode(Opcodes.ISHR))
                BinaryOp.I32_SHR_U -> insns.add(InsnNode(Opcodes.IUSHR))
                BinaryOp.I32_ROTL -> insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "rotateLeft", "(II)I", false))
                BinaryOp.I32_ROTR -> insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "rotateRight", "(II)I", false))

                BinaryOp.I64_ADD -> insns.add(InsnNode(Opcodes.LADD))
                BinaryOp.I64_SUB -> insns.add(InsnNode(Opcodes.LSUB))
                BinaryOp.I64_MUL -> insns.add(InsnNode(Opcodes.LMUL))
                BinaryOp.I64_DIV_S -> numericsCall("i64DivS", "(JJ)J")
                BinaryOp.I64_DIV_U -> numericsCall("i64DivU", "(JJ)J")
                BinaryOp.I64_REM_S -> numericsCall("i64RemS", "(JJ)J")
                BinaryOp.I64_REM_U -> numericsCall("i64RemU", "(JJ)J")
                BinaryOp.I64_AND -> insns.add(InsnNode(Opcodes.LAND))
                BinaryOp.I64_OR -> insns.add(InsnNode(Opcodes.LOR))
                BinaryOp.I64_XOR -> insns.add(InsnNode(Opcodes.LXOR))
                BinaryOp.I64_SHL -> { insns.add(InsnNode(Opcodes.L2I)); insns.add(InsnNode(Opcodes.LSHL)) }
                BinaryOp.I64_SHR_S -> { insns.add(InsnNode(Opcodes.L2I)); insns.add(InsnNode(Opcodes.LSHR)) }
                BinaryOp.I64_SHR_U -> { insns.add(InsnNode(Opcodes.L2I)); insns.add(InsnNode(Opcodes.LUSHR)) }
                BinaryOp.I64_ROTL -> {
                    insns.add(InsnNode(Opcodes.L2I))
                    insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "rotateLeft", "(JI)J", false))
                }
                BinaryOp.I64_ROTR -> {
                    insns.add(InsnNode(Opcodes.L2I))
                    insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "rotateRight", "(JI)J", false))
                }

                BinaryOp.F32_ADD -> insns.add(InsnNode(Opcodes.FADD))
                BinaryOp.F32_SUB -> insns.add(InsnNode(Opcodes.FSUB))
                BinaryOp.F32_MUL -> insns.add(InsnNode(Opcodes.FMUL))
                BinaryOp.F32_DIV -> insns.add(InsnNode(Opcodes.FDIV))
                BinaryOp.F32_MIN -> numericsCall("f32Min", "(FF)F")
                BinaryOp.F32_MAX -> numericsCall("f32Max", "(FF)F")
                BinaryOp.F32_COPYSIGN -> numericsCall("f32Copysign", "(FF)F")

                BinaryOp.F64_ADD -> insns.add(InsnNode(Opcodes.DADD))
                BinaryOp.F64_SUB -> insns.add(InsnNode(Opcodes.DSUB))
                BinaryOp.F64_MUL -> insns.add(InsnNode(Opcodes.DMUL))
                BinaryOp.F64_DIV -> insns.add(InsnNode(Opcodes.DDIV))
                BinaryOp.F64_MIN -> numericsCall("f64Min", "(DD)D")
                BinaryOp.F64_MAX -> numericsCall("f64Max", "(DD)D")
                BinaryOp.F64_COPYSIGN -> numericsCall("f64Copysign", "(DD)D")
            }
        }

        private fun emitZeroCmp(jumpOpcode: Int) {
            val trueLbl = LabelNode()
            val endLbl = LabelNode()
            insns.add(JumpInsnNode(jumpOpcode, trueLbl))
            insns.add(InsnNode(Opcodes.ICONST_0))
            insns.add(JumpInsnNode(Opcodes.GOTO, endLbl))
            insns.add(trueLbl)
            insns.add(InsnNode(Opcodes.ICONST_1))
            insns.add(endLbl)
        }

        private fun numericsCall(name: String, desc: String) {
            insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, AotNames.NUMERICS_INTERNAL, name, desc, false))
        }
    }

    internal fun pushInt(insns: InsnList, value: Int) {
        when (value) {
            -1 -> insns.add(InsnNode(Opcodes.ICONST_M1))
            0 -> insns.add(InsnNode(Opcodes.ICONST_0))
            1 -> insns.add(InsnNode(Opcodes.ICONST_1))
            2 -> insns.add(InsnNode(Opcodes.ICONST_2))
            3 -> insns.add(InsnNode(Opcodes.ICONST_3))
            4 -> insns.add(InsnNode(Opcodes.ICONST_4))
            5 -> insns.add(InsnNode(Opcodes.ICONST_5))
            in Byte.MIN_VALUE..Byte.MAX_VALUE -> insns.add(IntInsnNode(Opcodes.BIPUSH, value))
            in Short.MIN_VALUE..Short.MAX_VALUE -> insns.add(IntInsnNode(Opcodes.SIPUSH, value))
            else -> insns.add(LdcInsnNode(value))
        }
    }

    internal fun pushLong(insns: InsnList, value: Long) {
        when (value) {
            0L -> insns.add(InsnNode(Opcodes.LCONST_0))
            1L -> insns.add(InsnNode(Opcodes.LCONST_1))
            else -> insns.add(LdcInsnNode(value))
        }
    }

    internal fun pushF32Bits(insns: InsnList, rawBits: Int) {
        when (rawBits) {
            0 -> insns.add(InsnNode(Opcodes.FCONST_0))
            0x3F80_0000 -> insns.add(InsnNode(Opcodes.FCONST_1))
            0x4000_0000 -> insns.add(InsnNode(Opcodes.FCONST_2))
            else -> {
                if ((rawBits and 0x7F80_0000) == 0x7F80_0000 && (rawBits and 0x007F_FFFF) != 0) {
                    pushInt(insns, rawBits)
                    insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Float", "intBitsToFloat", "(I)F", false))
                } else {
                    insns.add(LdcInsnNode(java.lang.Float.intBitsToFloat(rawBits)))
                }
            }
        }
    }

    internal fun pushF64Bits(insns: InsnList, rawBits: Long) {
        when (rawBits) {
            0L -> insns.add(InsnNode(Opcodes.DCONST_0))
            0x3FF0_0000_0000_0000L -> insns.add(InsnNode(Opcodes.DCONST_1))
            else -> {
                if ((rawBits and 0x7FF0_0000_0000_0000L) == 0x7FF0_0000_0000_0000L &&
                    (rawBits and 0x000F_FFFF_FFFF_FFFFL) != 0L
                ) {
                    pushLong(insns, rawBits)
                    insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false))
                } else {
                    insns.add(LdcInsnNode(java.lang.Double.longBitsToDouble(rawBits)))
                }
            }
        }
    }
}
