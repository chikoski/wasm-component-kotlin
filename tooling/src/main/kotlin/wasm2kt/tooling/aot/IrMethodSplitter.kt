package wasm2kt.tooling.aot

import wasm2kt.tooling.ir.BinaryOp
import wasm2kt.tooling.ir.IrBlockInfo
import wasm2kt.tooling.ir.IrBlockKind
import wasm2kt.tooling.ir.IrFuncType
import wasm2kt.tooling.ir.IrFunction
import wasm2kt.tooling.ir.IrTypedInsn
import wasm2kt.tooling.ir.IrValType
import wasm2kt.tooling.ir.LoadKind
import wasm2kt.tooling.ir.StoreKind
import wasm2kt.tooling.ir.UnaryOp
import wasm2kt.tooling.ir.WasmOp
import java.util.BitSet

internal data class SplitSpillSlot(
    val valType: IrValType,
    val typeIndex: Int,
)

internal sealed interface ReturnShape {
    data object Void : ReturnShape
    data class DirectStackValue(val valType: IrValType) : ReturnShape
    data class DirectLiveOut(val localIndex: Int, val valType: IrValType) : ReturnShape
    data class SpillAndExitCode(
        val returnsExitCode: Boolean,
        val liveOutSlots: List<Pair<Int, SplitSpillSlot>>,
        val normalStackSlots: List<SplitSpillSlot>,
        val externalExits: List<ExternalExitSpec>,
    ) : ReturnShape
}

internal data class ExternalExitSpec(
    val exitCode: Int,
    val target: wasm2kt.tooling.ir.IrBranchTarget?, // null => Function return
    val blockId: Int,
    val labelTypes: List<IrValType>,
    val spillSlots: List<SplitSpillSlot>,
)

internal data class HelperMethodSpec(
    val name: String,
    val directParams: List<Pair<Int, IrValType>>,
    val spillInLocals: List<Pair<Int, SplitSpillSlot>>,
    val liveInSet: BitSet,
    val referencedLocals: BitSet,
    val body: List<IrNode>,
    val normalExitReachable: Boolean,
    val normalExitTypes: List<IrValType>,
    val returnShape: ReturnShape,
    val descriptor: String,
    val estimatedBytes: Int,
    val maxSpillUsage: SpillCounts,
)

internal data class SpillCounts(
    val i32: Int = 0,
    val i64: Int = 0,
    val f32: Int = 0,
    val f64: Int = 0,
    val ref: Int = 0,
) {
    fun maxWith(other: SpillCounts): SpillCounts = SpillCounts(
        i32 = maxOf(i32, other.i32),
        i64 = maxOf(i64, other.i64),
        f32 = maxOf(f32, other.f32),
        f64 = maxOf(f64, other.f64),
        ref = maxOf(ref, other.ref),
    )

    companion object {
        val ZERO = SpillCounts()
    }
}

internal class LocalFrameLayout(
    val counts: IntArray,
    val indexInTypeArray: IntArray,
    val arrayParams: List<Pair<Int, String>>,
) {
    companion object {
        fun kindOf(vt: IrValType): Int = when (vt) {
            IrValType.I32 -> 0
            IrValType.I64 -> 1
            IrValType.F32 -> 2
            IrValType.F64 -> 3
            IrValType.FUNCREF, IrValType.EXTERNREF -> 4
        }

        fun descOf(kind: Int): String = when (kind) {
            0 -> "[I"
            1 -> "[J"
            2 -> "[F"
            3 -> "[D"
            else -> "[Ljava/lang/Object;"
        }

        fun build(fn: IrFunction): LocalFrameLayout {
            val counts = IntArray(5)
            val idxArr = IntArray(fn.allLocals.size)
            for (i in fn.allLocals.indices) {
                val k = kindOf(fn.allLocals[i].valType)
                idxArr[i] = counts[k]++
            }
            val params = buildList {
                for (k in 0..4) {
                    if (counts[k] > 0) add(k to descOf(k))
                }
            }
            return LocalFrameLayout(counts, idxArr, params)
        }
    }
}

internal class FunctionSplitPlan(
    val fn: IrFunction,
    val rootBody: List<IrNode>,
    val rootExitReachable: Boolean,
    val rootLiveLocalsUsed: BitSet,
    val helpers: List<HelperMethodSpec>,
    val maxSpillUsage: SpillCounts,
    val overflowParamCount: Int,
    val frameLayout: LocalFrameLayout? = null,
)

internal sealed class IrNode {
    var entryJvmReachable: Boolean = false
    var exitJvmReachable: Boolean = false
    var estBytes: Int = 0
    val reads: BitSet = BitSet()
    val writes: BitSet = BitSet()
    val definedBlocks: HashSet<Int> = HashSet()
    val externalBranches: MutableList<BranchRef> = mutableListOf()
    lateinit var liveBefore: BitSet
    lateinit var liveAfter: BitSet

    abstract val entryStackHeight: Int
    abstract val exitStackHeight: Int
    abstract var minTouchHeight: Int
    abstract val firstInsnOffset: Int

    class InsnNode(val insn: IrTypedInsn) : IrNode() {
        override val entryStackHeight: Int get() = insn.stackBefore.size
        override val exitStackHeight: Int get() = insn.stackAfter.size
        override var minTouchHeight: Int = insn.stackBefore.size
        override val firstInsnOffset: Int get() = insn.wasmOffset
    }

    class BlockNode(
        val openInsn: IrTypedInsn,
        val endInsn: IrTypedInsn,
        val blockInfo: IrBlockInfo,
        val body: MutableList<IrNode>,
    ) : IrNode() {
        override val entryStackHeight: Int get() = openInsn.stackBefore.size
        override val exitStackHeight: Int get() = endInsn.stackAfter.size
        override var minTouchHeight: Int = openInsn.frameBaseHeight
        override val firstInsnOffset: Int get() = openInsn.wasmOffset
    }

    class LoopNode(
        val openInsn: IrTypedInsn,
        val endInsn: IrTypedInsn,
        val blockInfo: IrBlockInfo,
        val body: MutableList<IrNode>,
    ) : IrNode() {
        override val entryStackHeight: Int get() = openInsn.stackBefore.size
        override val exitStackHeight: Int get() = endInsn.stackAfter.size
        override var minTouchHeight: Int = openInsn.frameBaseHeight
        override val firstInsnOffset: Int get() = openInsn.wasmOffset
    }

    class IfNode(
        val openInsn: IrTypedInsn,
        val elseInsn: IrTypedInsn?,
        val endInsn: IrTypedInsn,
        val blockInfo: IrBlockInfo,
        val thenBody: MutableList<IrNode>,
        val elseBody: MutableList<IrNode>,
    ) : IrNode() {
        var elseEntryJvmReachable: Boolean = false
        override val entryStackHeight: Int get() = openInsn.stackBefore.size
        override val exitStackHeight: Int get() = endInsn.stackAfter.size
        override var minTouchHeight: Int = openInsn.frameBaseHeight
        override val firstInsnOffset: Int get() = openInsn.wasmOffset
    }

    class OutlinedCallNode(
        val helper: HelperMethodSpec,
        val entryHeight: Int,
        val exitHeight: Int,
        val startStackBefore: List<wasm2kt.tooling.ir.IrStackType>,
        val endStackAfter: List<wasm2kt.tooling.ir.IrStackType>,
        override val firstInsnOffset: Int,
        val callerOverheadBytes: Int,
    ) : IrNode() {
        override val entryStackHeight: Int get() = entryHeight
        override val exitStackHeight: Int get() = exitHeight
        override var minTouchHeight: Int = entryHeight
    }
}

internal data class BranchRef(
    val targetBlockId: Int,
    val target: wasm2kt.tooling.ir.IrBranchTarget?, // null => WasmOp.Return
    val popArity: Int,
    val stackBeforeSize: Int,
)

/**
 * Analyzes structured Wasm IR functions, tracks exact JVM reachability and backward local
 * liveness, and splits functions exceeding [targetMethodBytes] at IR/CFG boundaries into
 * stack-transparent static helper methods before bytecode emission.
 */
internal object IrMethodSplitter {
    private const val MIN_REGION_BYTES = 160
    private const val MIN_GAIN_BYTES = 32
    private const val MAX_LIVE_IN_DIRECT_SLOTS = 235

    fun planFunction(
        fn: IrFunction,
        instanceInternalName: String,
        targetMethodBytes: Int,
        maxHelperBodyBytes: Int = (targetMethodBytes * 0.76).toInt().coerceIn(2_000, 5_700),
        maxChildBodyBytes: Int = (maxHelperBodyBytes * 0.58).toInt().coerceIn(800, 3_400),
        funcTypes: (Int) -> IrFuncType,
    ): FunctionSplitPlan {
        val rawTree = buildTree(fn)
        computeReachability(rawTree, fn)
        val liveTargets = computeLiveness(rawTree, fn, funcTypes)
        computeMinTouchAndSummaries(rawTree, funcTypes)

        val directParams = AotNames.countDirectParams(fn.type.params)
        val overflowParams = fn.type.params.size - directParams
        val referencedLocals = collectReferencedLocals(rawTree)
        val refCount = referencedLocals.cardinality()
        val prologueCost = minOf(refCount, 240) * 2 + maxOf(0, refCount - 240) * 5 + overflowParams * 10

        val rootEst = rawTree.sumOf { it.estBytes } + prologueCost + 20
        val helpers = mutableListOf<HelperMethodSpec>()
        var nextHelperId = 0
        var activeFrameLayout: LocalFrameLayout? = null

        if (rootEst > targetMethodBytes) {
            val frameLayout = if (fn.allLocals.size > 64) LocalFrameLayout.build(fn) else null
            val effectivePrologueCost = if (frameLayout != null) {
                recomputeEstBytesForFrame(rawTree, funcTypes)
                frameLayout.arrayParams.size * 8 + fn.params.size * 8 + overflowParams * 10
            } else {
                prologueCost
            }
            val minRoot = minOf(250, (targetMethodBytes / 2).coerceAtLeast(160))
            val ctx = SplitContext(
                fn = fn,
                instanceInternalName = instanceInternalName,
                liveTargets = liveTargets,
                maxHelperTotalBytes = maxHelperBodyBytes,
                maxChildBodyBytes = maxChildBodyBytes.coerceAtMost(maxHelperBodyBytes),
                targetRootBodyBytes = (targetMethodBytes - effectivePrologueCost - 80).coerceAtLeast(minRoot),
                helpers = helpers,
                allocateHelperName = { "${AotNames.funcMethodName(fn.index)}\$s${nextHelperId++}" },
                frameLayout = frameLayout,
            )
            splitNodeList(rawTree, ctx, isRoot = true)
            if (helpers.isNotEmpty() && frameLayout != null) {
                activeFrameLayout = frameLayout
            }
        }

        val rootExitReachable = rawTree.lastOrNull()?.exitJvmReachable ?: true
        val rootReferenced = collectReferencedLocals(rawTree)
        for (idx in 0 until fn.params.size) rootReferenced.set(idx)
        // Include any spill-in locals required at top level? Root already initializes params/referenced.

        var maxSpill = computeFunctionSignatureSpill(fn, directParams)
        for (h in helpers) {
            maxSpill = maxSpill.maxWith(h.maxSpillUsage)
        }

        return FunctionSplitPlan(
            fn = fn,
            rootBody = rawTree,
            rootExitReachable = rootExitReachable,
            rootLiveLocalsUsed = rootReferenced,
            helpers = helpers,
            maxSpillUsage = maxSpill,
            overflowParamCount = overflowParams,
            frameLayout = activeFrameLayout,
        )
    }

    private fun computeFunctionSignatureSpill(fn: IrFunction, directParams: Int): SpillCounts {
        if (directParams >= fn.type.params.size) return SpillCounts.ZERO
        var i32 = 0; var i64 = 0; var f32 = 0; var f64 = 0; var ref = 0
        for (i in directParams until fn.type.params.size) {
            when (fn.type.params[i]) {
                IrValType.I32 -> i32++
                IrValType.I64 -> i64++
                IrValType.F32 -> f32++
                IrValType.F64 -> f64++
                IrValType.FUNCREF, IrValType.EXTERNREF -> ref++
            }
        }
        return SpillCounts(i32, i64, f32, f64, ref)
    }

    private class SplitContext(
        val fn: IrFunction,
        val instanceInternalName: String,
        val liveTargets: Map<Int, BitSet>,
        val maxHelperTotalBytes: Int,
        val maxChildBodyBytes: Int,
        val targetRootBodyBytes: Int,
        val helpers: MutableList<HelperMethodSpec>,
        val allocateHelperName: () -> String,
        val frameLayout: LocalFrameLayout? = null,
    )

    private fun reachableEstBytes(nodes: List<IrNode>): Int {
        var sum = 0
        for (node in nodes) {
            if (node.entryJvmReachable) sum += node.estBytes
        }
        return sum
    }

    private fun splitNodeList(
        nodes: MutableList<IrNode>,
        ctx: SplitContext,
        isRoot: Boolean,
    ) {
        // 1. Strict single-pass post-order: shrink children exceeding child budget once.
        for (node in nodes) {
            if (!node.entryJvmReachable || node.estBytes <= ctx.maxChildBodyBytes) continue
            when (node) {
                is IrNode.BlockNode -> {
                    splitNodeList(node.body, ctx, isRoot = false)
                    recomputeNodeSummary(node)
                }
                is IrNode.LoopNode -> {
                    splitNodeList(node.body, ctx, isRoot = false)
                    recomputeNodeSummary(node)
                }
                is IrNode.IfNode -> {
                    splitNodeList(node.thenBody, ctx, isRoot = false)
                    splitNodeList(node.elseBody, ctx, isRoot = false)
                    recomputeNodeSummary(node)
                }
                is IrNode.InsnNode, is IrNode.OutlinedCallNode -> {}
            }
        }

        // 2. Repeatedly outline contiguous slices in `nodes` until total fits target budget.
        val budget = if (isRoot) ctx.targetRootBodyBytes else ctx.maxChildBodyBytes
        var guard = 0
        while (reachableEstBytes(nodes) > budget && guard++ < 256) {
            val candidate = findBestSlice(nodes, ctx) ?: break
            applyOutline(nodes, candidate, ctx)
        }
    }

    private class SliceCandidate(
        val start: Int,
        val end: Int,
        val normalExitReachable: Boolean,
        val normalExitTypes: List<IrValType>,
        val externalExits: List<ExternalExitSpec>,
        val liveIn: BitSet,
        val liveOut: BitSet,
        val referencedLocals: BitSet,
        val helperBodyEst: Int,
        val callerCostEst: Int,
        val netSavings: Int,
        val sliceEstBytes: Int,
    )

    private fun findBestSlice(nodes: List<IrNode>, ctx: SplitContext): SliceCandidate? {
        val n = nodes.size
        if (n == 0) return null

        var best: SliceCandidate? = null
        var bestScore = Int.MIN_VALUE
        val maxSliceBody = (ctx.maxHelperTotalBytes - 100).coerceAtLeast(MIN_REGION_BYTES)

        for (i in 0 until n) {
            val startNode = nodes[i]
            if (!startNode.entryJvmReachable) continue
            val entryH = startNode.entryStackHeight

            val accReads = BitSet()
            val accWrites = BitSet()
            val externalTargetsByBlock = LinkedHashMap<Int, BranchRef>()
            var accBytes = 0

            var j = i
            while (j < n) {
                val cur = nodes[j]
                if (!cur.entryJvmReachable) {
                    j++
                    continue
                }
                if (accBytes + cur.estBytes > maxSliceBody && j > i) {
                    break
                }
                if (cur.minTouchHeight < entryH) {
                    break
                }
                var branchUnsafe = false
                for (b in cur.externalBranches) {
                    if (b.stackBeforeSize - b.popArity < entryH) {
                        branchUnsafe = true
                        break
                    }
                    externalTargetsByBlock.putIfAbsent(b.targetBlockId, b)
                }
                if (branchUnsafe || externalTargetsByBlock.size > 128) {
                    break
                }

                accBytes += cur.estBytes
                accReads.or(cur.reads)
                accWrites.or(cur.writes)

                val validEndpoint = !cur.exitJvmReachable || cur.exitStackHeight >= entryH
                if (validEndpoint && accBytes >= MIN_REGION_BYTES) {
                    val isSingleTinyCall = (i == j && cur is IrNode.OutlinedCallNode)
                    if (!isSingleTinyCall) {
                        val cand = evaluateSlice(
                            nodes = nodes,
                            start = i,
                            end = j,
                            entryH = entryH,
                            accReads = accReads,
                            accWrites = accWrites,
                            externalTargetsByBlock = externalTargetsByBlock,
                            sliceBytes = accBytes,
                            ctx = ctx,
                        )
                        if (cand != null && cand.netSavings >= MIN_GAIN_BYTES) {
                            val score = cand.netSavings * 2 + cand.sliceEstBytes - cand.externalExits.size * 80
                            if (score > bestScore) {
                                bestScore = score
                                best = cand
                            }
                        }
                    }
                }
                j++
            }
            if (n > 120 &&
                best != null &&
                best.sliceEstBytes >= ctx.maxChildBodyBytes &&
                best.netSavings >= ctx.maxChildBodyBytes / 2 &&
                best.externalExits.size <= 2
            ) {
                break
            }
        }
        return best
    }

    private fun evaluateSlice(
        nodes: List<IrNode>,
        start: Int,
        end: Int,
        entryH: Int,
        accReads: BitSet,
        accWrites: BitSet,
        externalTargetsByBlock: Map<Int, BranchRef>,
        sliceBytes: Int,
        ctx: SplitContext,
    ): SliceCandidate? {
        val endNode = nodes[end]
        val normalExitReachable = endNode.exitJvmReachable
        val normalExitTypes = if (normalExitReachable) {
            extractNormalExitTypes(endNode, entryH) ?: return null
        } else {
            emptyList()
        }

        val fl = ctx.frameLayout
        if (fl != null) {
            val extCount = externalTargetsByBlock.size
            val numArrays = fl.arrayParams.size
            val usesSpillReturn = !(extCount == 0 && normalExitTypes.size <= 1)
            val normalOutBytes = if (usesSpillReturn) normalExitTypes.size * 11 else 0

            val callerCost = 8 +
                numArrays * 2 +
                normalOutBytes +
                if (extCount > 0) 20 + extCount * 16 else 0

            val epilogueCost = if (!usesSpillReturn) {
                6
            } else {
                14 + normalOutBytes + extCount * 12
            }
            val helperCost = sliceBytes + epilogueCost
            if (helperCost > ctx.maxHelperTotalBytes) return null

            val netSavings = sliceBytes - callerCost
            if (netSavings < MIN_GAIN_BYTES) return null

            val externalExits = externalTargetsByBlock.values.mapIndexed { idx, b ->
                val lTypes = if (b.target == null) ctx.fn.type.results else b.target.labelTypes
                ExternalExitSpec(
                    exitCode = idx + 1,
                    target = b.target,
                    blockId = b.targetBlockId,
                    labelTypes = lTypes,
                    spillSlots = emptyList(),
                )
            }

            return SliceCandidate(
                start = start,
                end = end,
                normalExitReachable = normalExitReachable,
                normalExitTypes = normalExitTypes,
                externalExits = externalExits,
                liveIn = BitSet(),
                liveOut = BitSet(),
                referencedLocals = BitSet(),
                helperBodyEst = helperCost,
                callerCostEst = callerCost,
                netSavings = netSavings,
                sliceEstBytes = sliceBytes,
            )
        }

        val liveOut = BitSet()
        if (normalExitReachable) {
            val atNormal = BitSet().apply {
                or(endNode.liveAfter)
                and(accWrites)
            }
            liveOut.or(atNormal)
        }
        for ((blockId, _) in externalTargetsByBlock) {
            val targetLive = ctx.liveTargets[blockId]
            if (targetLive != null) {
                val atExit = BitSet().apply {
                    or(targetLive)
                    and(accWrites)
                }
                liveOut.or(atExit)
            }
        }

        val liveIn = BitSet().apply {
            or(accReads)
            or(liveOut)
            and(nodes[start].liveBefore)
        }

        val referenced = BitSet().apply {
            or(accReads)
            or(accWrites)
            or(liveOut)
            or(liveIn)
        }

        val liveInCount = liveIn.cardinality()
        val liveOutCount = liveOut.cardinality()
        val extCount = externalTargetsByBlock.size

        // Partition live-in using actual slot ceiling (235 JVM slots) for accurate cost estimation.
        var directSlotsUsed = 0
        var directInCount = 0
        var spillInCount = 0
        var v = liveIn.nextSetBit(0)
        while (v >= 0) {
            val w = AotNames.slotSize(ctx.fn.allLocals[v].valType)
            if (directSlotsUsed + w <= MAX_LIVE_IN_DIRECT_SLOTS) {
                directSlotsUsed += w
                directInCount++
            } else {
                spillInCount++
            }
            v = liveIn.nextSetBit(v + 1)
        }
        val prologueInitCount = (referenced.cardinality() - liveInCount).coerceAtLeast(0)
        val wideLocals = ctx.fn.allLocals.size > 200
        val directPerLocal = if (wideLocals) 3 else 2
        val spillPerLocal = if (wideLocals) 9 else 7
        val usesSpillReturn = !(liveOutCount == 0 && extCount == 0 && normalExitTypes.size <= 1) &&
            !(liveOutCount == 1 && extCount == 0 && normalExitTypes.isEmpty())
        val spillInBytes = if (spillInCount == 0) 0 else 11 + spillInCount * spillPerLocal
        val spillOutBatchBytes = if (usesSpillReturn) {
            (if (liveOutCount >= 2) 11 else 0) + liveOutCount * spillPerLocal + normalExitTypes.size * 11
        } else {
            if (liveOutCount > 0) 2 else 0
        }

        val callerCost = 10 +
            directInCount * directPerLocal +
            spillInBytes +
            spillOutBatchBytes +
            if (extCount > 0) 20 + extCount * 16 else 0

        val epilogueCost = if (!usesSpillReturn) {
            6
        } else {
            14 + spillOutBatchBytes + extCount * 12
        }
        val helperCost = sliceBytes + spillInBytes + prologueInitCount * directPerLocal + epilogueCost
        if (helperCost > ctx.maxHelperTotalBytes) return null

        val netSavings = sliceBytes - callerCost
        if (netSavings < MIN_GAIN_BYTES) return null

        val externalExits = externalTargetsByBlock.values.mapIndexed { idx, b ->
            val lTypes = if (b.target == null) ctx.fn.type.results else b.target.labelTypes
            ExternalExitSpec(
                exitCode = idx + 1,
                target = b.target,
                blockId = b.targetBlockId,
                labelTypes = lTypes,
                spillSlots = emptyList(),
            )
        }

        return SliceCandidate(
            start = start,
            end = end,
            normalExitReachable = normalExitReachable,
            normalExitTypes = normalExitTypes,
            externalExits = externalExits,
            liveIn = liveIn,
            liveOut = liveOut,
            referencedLocals = referenced,
            helperBodyEst = helperCost,
            callerCostEst = callerCost,
            netSavings = netSavings,
            sliceEstBytes = sliceBytes,
        )
    }

    private fun extractNormalExitTypes(endNode: IrNode, entryH: Int): List<IrValType>? {
        val stackAfter: List<wasm2kt.tooling.ir.IrStackType> = when (endNode) {
            is IrNode.InsnNode -> endNode.insn.stackAfter
            is IrNode.BlockNode -> endNode.endInsn.stackAfter
            is IrNode.LoopNode -> endNode.endInsn.stackAfter
            is IrNode.IfNode -> endNode.endInsn.stackAfter
            is IrNode.OutlinedCallNode -> endNode.endStackAfter
        }
        if (stackAfter.size < entryH) return null
        val out = ArrayList<IrValType>(stackAfter.size - entryH)
        for (idx in entryH until stackAfter.size) {
            val st = stackAfter[idx]
            if (st !is wasm2kt.tooling.ir.IrStackType.Concrete) return null
            out += st.valType
        }
        return out
    }

    private fun applyOutline(nodes: MutableList<IrNode>, cand: SliceCandidate, ctx: SplitContext) {
        val helperName = ctx.allocateHelperName()
        val locals = ctx.fn.allLocals

        // Partition liveIn into direct JVM params (up to MAX_LIVE_IN_DIRECT_SLOTS) + spill overflow.
        val directParams = mutableListOf<Pair<Int, IrValType>>()
        val spillIn = mutableListOf<Pair<Int, SplitSpillSlot>>()
        val inSpillAllocator = SpillSlotAllocator()
        var directSlots = 0
        var v = cand.liveIn.nextSetBit(0)
        while (v >= 0) {
            val vt = locals[v].valType
            val w = AotNames.slotSize(vt)
            if (directSlots + w <= MAX_LIVE_IN_DIRECT_SLOTS) {
                directParams += v to vt
                directSlots += w
            } else {
                spillIn += v to inSpillAllocator.alloc(vt)
            }
            v = cand.liveIn.nextSetBit(v + 1)
        }

        // Determine ReturnShape and allocate spill out slots cleanly.
        val outAlloc = SpillSlotAllocator()
        val liveOutList = buildList {
            var lo = cand.liveOut.nextSetBit(0)
            while (lo >= 0) {
                add(lo to locals[lo].valType)
                lo = cand.liveOut.nextSetBit(lo + 1)
            }
        }

        val returnShape: ReturnShape = when {
            liveOutList.isEmpty() && cand.externalExits.isEmpty() && cand.normalExitTypes.isEmpty() ->
                ReturnShape.Void
            liveOutList.isEmpty() && cand.externalExits.isEmpty() && cand.normalExitTypes.size == 1 ->
                ReturnShape.DirectStackValue(cand.normalExitTypes[0])
            liveOutList.size == 1 && cand.externalExits.isEmpty() && cand.normalExitTypes.isEmpty() ->
                ReturnShape.DirectLiveOut(liveOutList[0].first, liveOutList[0].second)
            else -> {
                val liveOutSlots = liveOutList.map { (idx, vt) -> idx to outAlloc.alloc(vt) }
                // Normal exit and every external exit are mutually exclusive -> reuse exit stack offsets after liveOutSlots.
                val baseAfterLocals = outAlloc.snapshot()
                val normalStackSlots = if (cand.normalExitReachable) {
                    val exitAlloc = SpillSlotAllocator(baseAfterLocals)
                    cand.normalExitTypes.map { exitAlloc.alloc(it) }.also { outAlloc.maxWith(exitAlloc) }
                } else {
                    emptyList()
                }
                val exitsWithSlots = cand.externalExits.map { ext ->
                    val exitAlloc = SpillSlotAllocator(baseAfterLocals)
                    val slots = ext.labelTypes.map { exitAlloc.alloc(it) }
                    outAlloc.maxWith(exitAlloc)
                    ext.copy(spillSlots = slots)
                }
                ReturnShape.SpillAndExitCode(
                    returnsExitCode = cand.externalExits.isNotEmpty(),
                    liveOutSlots = liveOutSlots,
                    normalStackSlots = normalStackSlots,
                    externalExits = exitsWithSlots,
                )
            }
        }

        val fl = ctx.frameLayout
        val desc = buildString {
            append('(')
            append('L').append(ctx.instanceInternalName).append(';')
            if (fl != null) {
                for ((_, arrDesc) in fl.arrayParams) append(arrDesc)
            } else {
                for ((_, vt) in directParams) append(AotNames.jvmDesc(vt))
            }
            append(')')
            when (returnShape) {
                is ReturnShape.Void -> append('V')
                is ReturnShape.DirectStackValue -> append(AotNames.jvmDesc(returnShape.valType))
                is ReturnShape.DirectLiveOut -> append(AotNames.jvmDesc(returnShape.valType))
                is ReturnShape.SpillAndExitCode -> append(if (returnShape.returnsExitCode) "I" else "V")
            }
        }

        val subList = ArrayList<IrNode>(cand.end - cand.start + 1)
        for (idx in cand.start..cand.end) {
            subList += nodes[idx]
        }

        val helperSpillMax = inSpillAllocator.toCounts().maxWith(outAlloc.toCounts())
        val helperSpec = HelperMethodSpec(
            name = helperName,
            directParams = directParams,
            spillInLocals = spillIn,
            liveInSet = cand.liveIn,
            referencedLocals = cand.referencedLocals,
            body = subList,
            normalExitReachable = cand.normalExitReachable,
            normalExitTypes = cand.normalExitTypes,
            returnShape = returnShape,
            descriptor = desc,
            estimatedBytes = cand.helperBodyEst,
            maxSpillUsage = helperSpillMax,
        )
        ctx.helpers += helperSpec

        val startNode = nodes[cand.start]
        val startStack = when (startNode) {
            is IrNode.InsnNode -> startNode.insn.stackBefore
            is IrNode.BlockNode -> startNode.openInsn.stackBefore
            is IrNode.LoopNode -> startNode.openInsn.stackBefore
            is IrNode.IfNode -> startNode.openInsn.stackBefore
            is IrNode.OutlinedCallNode -> startNode.startStackBefore
        }
        val endNode = nodes[cand.end]
        val endStack = when (endNode) {
            is IrNode.InsnNode -> endNode.insn.stackAfter
            is IrNode.BlockNode -> endNode.endInsn.stackAfter
            is IrNode.LoopNode -> endNode.endInsn.stackAfter
            is IrNode.IfNode -> endNode.endInsn.stackAfter
            is IrNode.OutlinedCallNode -> endNode.endStackAfter
        }
        val entryH = startNode.entryStackHeight
        val exitH = entryH + cand.normalExitTypes.size
        val callNode = IrNode.OutlinedCallNode(
            helper = helperSpec,
            entryHeight = entryH,
            exitHeight = exitH,
            startStackBefore = startStack,
            endStackAfter = endStack,
            firstInsnOffset = startNode.firstInsnOffset,
            callerOverheadBytes = cand.callerCostEst,
        ).apply {
            entryJvmReachable = true
            exitJvmReachable = cand.normalExitReachable
            estBytes = cand.callerCostEst
            reads.or(cand.liveIn)
            writes.or(cand.liveOut)
            liveBefore = nodes[cand.start].liveBefore
            liveAfter = nodes[cand.end].liveAfter
            for (ext in cand.externalExits) {
                externalBranches += BranchRef(
                    targetBlockId = ext.blockId,
                    target = ext.target,
                    popArity = ext.labelTypes.size,
                    stackBeforeSize = entryH + ext.labelTypes.size,
                )
            }
        }

        // Replace nodes[start..end] with single callNode.
        for (k in cand.end downTo cand.start) {
            nodes.removeAt(k)
        }
        nodes.add(cand.start, callNode)
    }

    private fun buildTree(fn: IrFunction): MutableList<IrNode> {
        val blockById = fn.blocks.associateBy { it.id }
        val root = mutableListOf<IrNode>()

        class Frame(
            val kind: IrBlockKind,
            val openInsn: IrTypedInsn,
            val blockInfo: IrBlockInfo,
            val thenBody: MutableList<IrNode> = mutableListOf(),
            val elseBody: MutableList<IrNode> = mutableListOf(),
            var elseInsn: IrTypedInsn? = null,
            var inElse: Boolean = false,
        ) {
            fun activeList(): MutableList<IrNode> = if (inElse) elseBody else thenBody
        }

        val stack = ArrayDeque<Frame>()
        for (insn in fn.instructions) {
            when (val op = insn.op) {
                is WasmOp.Block -> {
                    val info = blockById.getValue(op.blockId)
                    stack.addLast(Frame(IrBlockKind.BLOCK, insn, info))
                }
                is WasmOp.Loop -> {
                    val info = blockById.getValue(op.blockId)
                    stack.addLast(Frame(IrBlockKind.LOOP, insn, info))
                }
                is WasmOp.If -> {
                    val info = blockById.getValue(op.blockId)
                    stack.addLast(Frame(IrBlockKind.IF, insn, info))
                }
                is WasmOp.Else -> {
                    val top = stack.last()
                    top.elseInsn = insn
                    top.inElse = true
                }
                is WasmOp.End -> {
                    if (op.blockId == 0 && stack.isEmpty()) {
                        // Final function End reached.
                        break
                    }
                    val top = stack.removeLast()
                    val node: IrNode = when (top.kind) {
                        IrBlockKind.BLOCK -> IrNode.BlockNode(top.openInsn, insn, top.blockInfo, top.thenBody)
                        IrBlockKind.LOOP -> IrNode.LoopNode(top.openInsn, insn, top.blockInfo, top.thenBody)
                        IrBlockKind.IF -> IrNode.IfNode(top.openInsn, top.elseInsn, insn, top.blockInfo, top.thenBody, top.elseBody)
                        IrBlockKind.FUNCTION -> error("Unexpected nested function frame")
                    }
                    val target = stack.lastOrNull()?.activeList() ?: root
                    target.add(node)
                }
                else -> {
                    val target = stack.lastOrNull()?.activeList() ?: root
                    target.add(IrNode.InsnNode(insn))
                }
            }
        }
        return root
    }

    private fun computeReachability(root: List<IrNode>, fn: IrFunction) {
        val targetedBlocks = HashSet<Int>()

        fun markAllDead(node: IrNode) {
            node.entryJvmReachable = false
            node.exitJvmReachable = false
            when (node) {
                is IrNode.BlockNode -> node.body.forEach(::markAllDead)
                is IrNode.LoopNode -> node.body.forEach(::markAllDead)
                is IrNode.IfNode -> {
                    node.elseEntryJvmReachable = false
                    node.thenBody.forEach(::markAllDead)
                    node.elseBody.forEach(::markAllDead)
                }
                is IrNode.InsnNode, is IrNode.OutlinedCallNode -> {}
            }
        }

        fun walkSeq(nodes: List<IrNode>, initialReachable: Boolean): Boolean {
            var reachable = initialReachable
            for (node in nodes) {
                node.entryJvmReachable = reachable
                if (!reachable) {
                    markAllDead(node)
                    continue
                }
                when (node) {
                    is IrNode.InsnNode -> {
                        when (val op = node.insn.op) {
                            is WasmOp.Unreachable, is WasmOp.Return -> {
                                reachable = false
                            }
                            is WasmOp.Br -> {
                                if (op.target.targetKind != IrBlockKind.LOOP) {
                                    targetedBlocks.add(op.target.targetBlockId)
                                }
                                reachable = false
                            }
                            is WasmOp.BrIf -> {
                                if (op.target.targetKind != IrBlockKind.LOOP) {
                                    targetedBlocks.add(op.target.targetBlockId)
                                }
                            }
                            is WasmOp.BrTable -> {
                                for (t in op.targets) {
                                    if (t.targetKind != IrBlockKind.LOOP) {
                                        targetedBlocks.add(t.targetBlockId)
                                    }
                                }
                                if (op.defaultTarget.targetKind != IrBlockKind.LOOP) {
                                    targetedBlocks.add(op.defaultTarget.targetBlockId)
                                }
                                reachable = false
                            }
                            else -> {}
                        }
                        node.exitJvmReachable = reachable
                    }
                    is IrNode.BlockNode -> {
                        val endBody = walkSeq(node.body, true)
                        reachable = endBody || (node.blockInfo.id in targetedBlocks)
                        node.exitJvmReachable = reachable
                    }
                    is IrNode.LoopNode -> {
                        val endBody = walkSeq(node.body, true)
                        reachable = endBody
                        node.exitJvmReachable = reachable
                    }
                    is IrNode.IfNode -> {
                        val endThen = walkSeq(node.thenBody, true)
                        val endElse = if (node.elseInsn != null) {
                            node.elseEntryJvmReachable = true
                            walkSeq(node.elseBody, true)
                        } else {
                            node.elseEntryJvmReachable = false
                            true // false branch falls through to end
                        }
                        reachable = endThen || endElse || (node.blockInfo.id in targetedBlocks)
                        node.exitJvmReachable = reachable
                    }
                    is IrNode.OutlinedCallNode -> {
                        reachable = node.exitJvmReachable
                    }
                }
            }
            return reachable
        }

        walkSeq(root, true)
    }

    private fun computeLiveness(
        root: List<IrNode>,
        fn: IrFunction,
        @Suppress("UNUSED_PARAMETER") funcTypes: (Int) -> IrFuncType,
    ): Map<Int, BitSet> {
        val targetLive = HashMap<Int, BitSet>()
        // Return target (block 0) needs zero caller locals.
        targetLive[0] = BitSet()

        fun analyzeSeq(nodes: List<IrNode>, liveAfterSeq: BitSet): BitSet {
            var curLive = BitSet().apply { or(liveAfterSeq) }
            for (i in nodes.lastIndex downTo 0) {
                val node = nodes[i]
                node.liveAfter = BitSet().apply { or(curLive) }
                if (!node.entryJvmReachable) {
                    node.liveBefore = BitSet()
                    // Keep curLive? No, previous instruction couldn't reach this unreachable node normally
                    // UNLESS previous instruction was the unconditional jump/return that preceded it, whose own
                    // branch target sets curLive on the preceding iteration. Wait: if `node` is unreachable,
                    // any instruction right BEFORE it that CAN exit normally doesn't exist (prev would also be
                    // unreachable or ended with br/return/unreachable), EXCEPT in dead region after br.
                    // Setting curLive empty when node is dead is exact!
                    curLive = BitSet()
                    continue
                }
                curLive = when (node) {
                    is IrNode.InsnNode -> {
                        val before = when (val op = node.insn.op) {
                            is WasmOp.Unreachable, is WasmOp.Return -> BitSet()
                            is WasmOp.Br -> BitSet().apply {
                                targetLive[op.target.targetBlockId]?.let { or(it) }
                            }
                            is WasmOp.BrTable -> BitSet().apply {
                                for (t in op.targets) targetLive[t.targetBlockId]?.let { or(it) }
                                targetLive[op.defaultTarget.targetBlockId]?.let { or(it) }
                            }
                            is WasmOp.BrIf -> BitSet().apply {
                                or(curLive)
                                targetLive[op.target.targetBlockId]?.let { or(it) }
                            }
                            is WasmOp.LocalGet -> BitSet().apply {
                                or(curLive)
                                set(op.localIndex)
                            }
                            is WasmOp.LocalSet, is WasmOp.LocalTee -> {
                                val idx = if (op is WasmOp.LocalSet) op.localIndex else (op as WasmOp.LocalTee).localIndex
                                BitSet().apply {
                                    or(curLive)
                                    clear(idx)
                                }
                            }
                            else -> BitSet().apply { or(curLive) }
                        }
                        before
                    }
                    is IrNode.BlockNode -> {
                        targetLive[node.blockInfo.id] = BitSet().apply { or(curLive) }
                        analyzeSeq(node.body, curLive)
                    }
                    is IrNode.IfNode -> {
                        targetLive[node.blockInfo.id] = BitSet().apply { or(curLive) }
                        val thenBefore = analyzeSeq(node.thenBody, curLive)
                        val elseBefore = if (node.elseInsn != null) {
                            analyzeSeq(node.elseBody, curLive)
                        } else {
                            curLive
                        }
                        BitSet().apply {
                            or(thenBefore)
                            or(elseBefore)
                        }
                    }
                    is IrNode.LoopNode -> {
                        val id = node.blockInfo.id
                        var loopEntry = BitSet()
                        var guard = 0
                        while (guard++ < fn.allLocals.size + 4) {
                            targetLive[id] = BitSet().apply { or(loopEntry) }
                            val next = analyzeSeq(node.body, curLive)
                            if (next == loopEntry) break
                            loopEntry = next
                        }
                        loopEntry
                    }
                    is IrNode.OutlinedCallNode -> BitSet().apply { or(curLive) }
                }
                node.liveBefore = curLive
            }
            return curLive
        }

        analyzeSeq(root, BitSet())
        return targetLive
    }

    private fun computeMinTouchAndSummaries(root: List<IrNode>, funcTypes: (Int) -> IrFuncType) {
        for (node in root) {
            summarizeNode(node, funcTypes)
        }
    }

    private fun recomputeEstBytesForFrame(root: List<IrNode>, funcTypes: (Int) -> IrFuncType) {
        fun updateNode(node: IrNode): Int {
            if (!node.entryJvmReachable) {
                node.estBytes = 0
                return 0
            }
            val updated = when (node) {
                is IrNode.InsnNode -> estimateInsnBytes(node.insn, funcTypes, frameBacked = true)
                is IrNode.BlockNode -> 4 + node.body.sumOf(::updateNode)
                is IrNode.LoopNode -> 4 + node.body.sumOf(::updateNode)
                is IrNode.IfNode -> 10 + node.thenBody.sumOf(::updateNode) + node.elseBody.sumOf(::updateNode)
                is IrNode.OutlinedCallNode -> node.estBytes
            }
            node.estBytes = updated
            return updated
        }
        for (node in root) {
            updateNode(node)
        }
    }

    private fun summarizeNode(node: IrNode, funcTypes: (Int) -> IrFuncType) {
        if (!node.entryJvmReachable) {
            node.estBytes = 0
            node.minTouchHeight = Int.MAX_VALUE
            return
        }
        when (node) {
            is IrNode.InsnNode -> {
                val insn = node.insn
                node.estBytes = estimateInsnBytes(insn, funcTypes)
                val pop = popCount(insn.op)
                node.minTouchHeight = (insn.stackBefore.size - pop).coerceAtLeast(0)
                when (val op = insn.op) {
                    is WasmOp.LocalGet -> node.reads.set(op.localIndex)
                    is WasmOp.LocalSet -> node.writes.set(op.localIndex)
                    is WasmOp.LocalTee -> node.writes.set(op.localIndex)
                    is WasmOp.Return -> {
                        node.externalBranches += BranchRef(0, null, op.resultTypes.size, insn.stackBefore.size)
                    }
                    is WasmOp.Br -> {
                        node.externalBranches += BranchRef(
                            op.target.targetBlockId,
                            op.target,
                            op.target.labelTypes.size,
                            insn.stackBefore.size,
                        )
                    }
                    is WasmOp.BrIf -> {
                        node.externalBranches += BranchRef(
                            op.target.targetBlockId,
                            op.target,
                            1 + op.target.labelTypes.size,
                            insn.stackBefore.size,
                        )
                    }
                    is WasmOp.BrTable -> {
                        val s = insn.stackBefore.size
                        for (t in op.targets) {
                            node.externalBranches += BranchRef(t.targetBlockId, t, 1 + t.labelTypes.size, s)
                        }
                        node.externalBranches += BranchRef(
                            op.defaultTarget.targetBlockId,
                            op.defaultTarget,
                            1 + op.defaultTarget.labelTypes.size,
                            s,
                        )
                    }
                    else -> {}
                }
            }
            is IrNode.BlockNode -> {
                node.definedBlocks.clear()
                node.definedBlocks.add(node.blockInfo.id)
                var sum = 4
                var minT = node.openInsn.frameBaseHeight
                for (child in node.body) {
                    summarizeNode(child, funcTypes)
                    sum += child.estBytes
                    node.reads.or(child.reads)
                    node.writes.or(child.writes)
                    node.definedBlocks.addAll(child.definedBlocks)
                    for (b in child.externalBranches) {
                        if (b.targetBlockId !in node.definedBlocks) {
                            node.externalBranches += b
                        } else if (b.target != null) {
                            minT = minOf(minT, b.target.unwindHeight)
                        }
                    }
                    minT = minOf(minT, child.minTouchHeight)
                }
                node.minTouchHeight = minT
                node.estBytes = sum
            }
            is IrNode.LoopNode -> {
                node.definedBlocks.clear()
                node.definedBlocks.add(node.blockInfo.id)
                var sum = 4
                var minT = node.openInsn.frameBaseHeight
                for (child in node.body) {
                    summarizeNode(child, funcTypes)
                    sum += child.estBytes
                    node.reads.or(child.reads)
                    node.writes.or(child.writes)
                    node.definedBlocks.addAll(child.definedBlocks)
                    for (b in child.externalBranches) {
                        if (b.targetBlockId !in node.definedBlocks) {
                            node.externalBranches += b
                        } else if (b.target != null) {
                            minT = minOf(minT, b.target.unwindHeight)
                        }
                    }
                    minT = minOf(minT, child.minTouchHeight)
                }
                node.minTouchHeight = minT
                node.estBytes = sum
            }
            is IrNode.IfNode -> {
                node.definedBlocks.clear()
                node.definedBlocks.add(node.blockInfo.id)
                var sum = 10
                var minT = node.openInsn.frameBaseHeight
                for (child in node.thenBody) {
                    summarizeNode(child, funcTypes)
                    sum += child.estBytes
                    node.reads.or(child.reads)
                    node.writes.or(child.writes)
                    node.definedBlocks.addAll(child.definedBlocks)
                    for (b in child.externalBranches) {
                        if (b.targetBlockId !in node.definedBlocks) {
                            node.externalBranches += b
                        }
                    }
                    minT = minOf(minT, child.minTouchHeight)
                }
                for (child in node.elseBody) {
                    summarizeNode(child, funcTypes)
                    sum += child.estBytes
                    node.reads.or(child.reads)
                    node.writes.or(child.writes)
                    node.definedBlocks.addAll(child.definedBlocks)
                    for (b in child.externalBranches) {
                        if (b.targetBlockId !in node.definedBlocks) {
                            node.externalBranches += b
                        }
                    }
                    minT = minOf(minT, child.minTouchHeight)
                }
                node.minTouchHeight = minT
                node.estBytes = sum
            }
            is IrNode.OutlinedCallNode -> {
                node.estBytes = node.callerOverheadBytes
            }
        }
    }

    private fun recomputeNodeSummary(node: IrNode) {
        if (!node.entryJvmReachable) {
            node.estBytes = 0
            node.minTouchHeight = Int.MAX_VALUE
            node.reads.clear()
            node.writes.clear()
            node.externalBranches.clear()
            return
        }
        when (node) {
            is IrNode.BlockNode -> {
                node.reads.clear()
                node.writes.clear()
                node.definedBlocks.clear()
                node.definedBlocks.add(node.blockInfo.id)
                node.externalBranches.clear()
                var sum = 4
                var minT = node.openInsn.frameBaseHeight
                for (child in node.body) {
                    if (!child.entryJvmReachable) continue
                    sum += child.estBytes
                    node.reads.or(child.reads)
                    node.writes.or(child.writes)
                    node.definedBlocks.addAll(child.definedBlocks)
                    for (b in child.externalBranches) {
                        if (b.targetBlockId !in node.definedBlocks) {
                            node.externalBranches += b
                        } else if (b.target != null) {
                            minT = minOf(minT, b.target.unwindHeight)
                        }
                    }
                    minT = minOf(minT, child.minTouchHeight)
                }
                node.minTouchHeight = minT
                node.estBytes = sum
            }
            is IrNode.LoopNode -> {
                node.reads.clear()
                node.writes.clear()
                node.definedBlocks.clear()
                node.definedBlocks.add(node.blockInfo.id)
                node.externalBranches.clear()
                var sum = 4
                var minT = node.openInsn.frameBaseHeight
                for (child in node.body) {
                    if (!child.entryJvmReachable) continue
                    sum += child.estBytes
                    node.reads.or(child.reads)
                    node.writes.or(child.writes)
                    node.definedBlocks.addAll(child.definedBlocks)
                    for (b in child.externalBranches) {
                        if (b.targetBlockId !in node.definedBlocks) {
                            node.externalBranches += b
                        } else if (b.target != null) {
                            minT = minOf(minT, b.target.unwindHeight)
                        }
                    }
                    minT = minOf(minT, child.minTouchHeight)
                }
                node.minTouchHeight = minT
                node.estBytes = sum
            }
            is IrNode.IfNode -> {
                node.reads.clear()
                node.writes.clear()
                node.definedBlocks.clear()
                node.definedBlocks.add(node.blockInfo.id)
                node.externalBranches.clear()
                var sum = 10
                var minT = node.openInsn.frameBaseHeight
                for (child in node.thenBody) {
                    if (!child.entryJvmReachable) continue
                    sum += child.estBytes
                    node.reads.or(child.reads)
                    node.writes.or(child.writes)
                    node.definedBlocks.addAll(child.definedBlocks)
                    for (b in child.externalBranches) {
                        if (b.targetBlockId !in node.definedBlocks) {
                            node.externalBranches += b
                        }
                    }
                    minT = minOf(minT, child.minTouchHeight)
                }
                for (child in node.elseBody) {
                    if (!child.entryJvmReachable) continue
                    sum += child.estBytes
                    node.reads.or(child.reads)
                    node.writes.or(child.writes)
                    node.definedBlocks.addAll(child.definedBlocks)
                    for (b in child.externalBranches) {
                        if (b.targetBlockId !in node.definedBlocks) {
                            node.externalBranches += b
                        }
                    }
                    minT = minOf(minT, child.minTouchHeight)
                }
                node.minTouchHeight = minT
                node.estBytes = sum
            }
            is IrNode.InsnNode, is IrNode.OutlinedCallNode -> {}
        }
    }

    private fun collectReferencedLocals(nodes: List<IrNode>): BitSet {
        val out = BitSet()
        for (n in nodes) {
            out.or(n.reads)
            out.or(n.writes)
        }
        return out
    }

    private fun popCount(op: WasmOp): Int = when (op) {
        is WasmOp.Unreachable, is WasmOp.Nop, is WasmOp.Block, is WasmOp.Loop, is WasmOp.Else, is WasmOp.End -> 0
        is WasmOp.If -> 1 + op.params.size
        is WasmOp.Br -> op.target.labelTypes.size
        is WasmOp.BrIf -> 1 + op.target.labelTypes.size
        is WasmOp.BrTable -> 1 + op.defaultTarget.labelTypes.size
        is WasmOp.Return -> op.resultTypes.size
        is WasmOp.Call -> op.funcType.params.size
        is WasmOp.CallIndirect -> op.funcType.params.size + 1
        is WasmOp.Drop -> 1
        is WasmOp.Select -> 3
        is WasmOp.LocalGet, is WasmOp.GlobalGet, is WasmOp.RefNull, is WasmOp.RefFunc -> 0
        is WasmOp.LocalSet, is WasmOp.LocalTee, is WasmOp.GlobalSet, is WasmOp.RefIsNull -> 1
        is WasmOp.TableGet -> 1
        is WasmOp.TableSet -> 2
        is WasmOp.TableInit -> 3
        is WasmOp.ElemDrop -> 0
        is WasmOp.TableCopy -> 3
        is WasmOp.TableGrow -> 2
        is WasmOp.TableSize -> 0
        is WasmOp.TableFill -> 3
        is WasmOp.Load -> 1
        is WasmOp.Store -> 2
        is WasmOp.MemorySize -> 0
        is WasmOp.MemoryGrow -> 1
        is WasmOp.MemoryInit, is WasmOp.MemoryCopy, is WasmOp.MemoryFill -> 3
        is WasmOp.DataDrop -> 0
        is WasmOp.I32Const, is WasmOp.I64Const, is WasmOp.F32Const, is WasmOp.F64Const -> 0
        is WasmOp.Unary -> 1
        is WasmOp.Binary -> 2
    }

    /** Conservative upper-ish JVM bytecode length estimate per IR instruction. */
    private fun estimateInsnBytes(
        insn: IrTypedInsn,
        funcTypes: (Int) -> IrFuncType,
        frameBacked: Boolean = false,
    ): Int = when (val op = insn.op) {
        is WasmOp.Nop -> 0
        is WasmOp.Unreachable -> 4
        is WasmOp.Br -> 6 + (insn.stackBefore.size - op.target.unwindHeight).coerceAtLeast(0) * 3
        is WasmOp.BrIf -> 10 + (insn.stackBefore.size - 1 - op.target.unwindHeight).coerceAtLeast(0) * 3
        is WasmOp.BrTable -> 24 + op.targets.size * 10
        is WasmOp.Return -> 10 + op.resultTypes.size * 3
        is WasmOp.Call -> {
            val ft = funcTypes(op.funcIndex)
            8 + ft.results.size * 4
        }
        is WasmOp.CallIndirect -> 18 + op.funcType.params.size * 2 + op.funcType.results.size * 4
        is WasmOp.Drop -> 3
        is WasmOp.LocalGet -> if (frameBacked) 6 else 3
        is WasmOp.LocalSet -> if (frameBacked) 9 else 3
        is WasmOp.LocalTee -> if (frameBacked) 11 else 3
        is WasmOp.Select -> 12
        is WasmOp.GlobalGet, is WasmOp.GlobalSet -> 6
        is WasmOp.RefNull, is WasmOp.RefFunc -> 10
        is WasmOp.RefIsNull -> 10
        is WasmOp.TableGet, is WasmOp.TableSet, is WasmOp.TableGrow, is WasmOp.TableSize -> 8
        is WasmOp.TableInit, is WasmOp.TableCopy, is WasmOp.TableFill, is WasmOp.ElemDrop -> 12
        is WasmOp.Load -> 12
        is WasmOp.Store -> 12
        is WasmOp.MemorySize, is WasmOp.MemoryGrow -> 8
        is WasmOp.MemoryInit, is WasmOp.MemoryCopy, is WasmOp.MemoryFill, is WasmOp.DataDrop -> 12
        is WasmOp.I32Const -> if (op.value in -128..127) 2 else if (op.value in -32768..32767) 3 else 4
        is WasmOp.I64Const -> if (op.value == 0L || op.value == 1L) 1 else 4
        is WasmOp.F32Const -> 8
        is WasmOp.F64Const -> 8
        is WasmOp.Unary -> when (op.op) {
            UnaryOp.I32_EQZ, UnaryOp.I64_EQZ -> 10
            UnaryOp.I32_WRAP_I64, UnaryOp.I64_EXTEND_I32_S,
            UnaryOp.F32_CONVERT_I32_S, UnaryOp.F32_CONVERT_I64_S,
            UnaryOp.F32_DEMOTE_F64, UnaryOp.F64_CONVERT_I32_S,
            UnaryOp.F64_CONVERT_I64_S, UnaryOp.F64_PROMOTE_F32,
            UnaryOp.I32_EXTEND_8_S, UnaryOp.I32_EXTEND_16_S -> 2
            else -> 5
        }
        is WasmOp.Binary -> when (op.op) {
            BinaryOp.I32_ADD, BinaryOp.I32_SUB, BinaryOp.I32_MUL, BinaryOp.I32_AND,
            BinaryOp.I32_OR, BinaryOp.I32_XOR, BinaryOp.I32_SHL, BinaryOp.I32_SHR_S, BinaryOp.I32_SHR_U,
            BinaryOp.I64_ADD, BinaryOp.I64_SUB, BinaryOp.I64_MUL, BinaryOp.I64_AND, BinaryOp.I64_OR, BinaryOp.I64_XOR,
            BinaryOp.F32_ADD, BinaryOp.F32_SUB, BinaryOp.F32_MUL, BinaryOp.F32_DIV,
            BinaryOp.F64_ADD, BinaryOp.F64_SUB, BinaryOp.F64_MUL, BinaryOp.F64_DIV -> 2
            BinaryOp.I32_EQ, BinaryOp.I32_NE, BinaryOp.I32_LT_S, BinaryOp.I32_LT_U,
            BinaryOp.I32_GT_S, BinaryOp.I32_GT_U, BinaryOp.I32_LE_S, BinaryOp.I32_LE_U,
            BinaryOp.I32_GE_S, BinaryOp.I32_GE_U,
            BinaryOp.I64_EQ, BinaryOp.I64_NE, BinaryOp.I64_LT_S, BinaryOp.I64_LT_U,
            BinaryOp.I64_GT_S, BinaryOp.I64_GT_U, BinaryOp.I64_LE_S, BinaryOp.I64_LE_U,
            BinaryOp.I64_GE_S, BinaryOp.I64_GE_U,
            BinaryOp.F32_EQ, BinaryOp.F32_NE, BinaryOp.F32_LT, BinaryOp.F32_GT, BinaryOp.F32_LE, BinaryOp.F32_GE,
            BinaryOp.F64_EQ, BinaryOp.F64_NE, BinaryOp.F64_LT, BinaryOp.F64_GT, BinaryOp.F64_LE, BinaryOp.F64_GE -> 12
            else -> 4
        }
        else -> 4
    }

    private class SpillSlotAllocator(
        initial: IntArray = IntArray(5),
    ) {
        private var counts = initial.copyOf()

        fun snapshot(): IntArray = counts.copyOf()

        fun alloc(vt: IrValType): SplitSpillSlot {
            val k = kindIndex(vt)
            val idx = counts[k]++
            return SplitSpillSlot(vt, idx)
        }

        fun maxWith(other: SpillSlotAllocator) {
            for (k in 0..4) {
                if (other.counts[k] > counts[k]) counts[k] = other.counts[k]
            }
        }

        fun toCounts(): SpillCounts = SpillCounts(counts[0], counts[1], counts[2], counts[3], counts[4])

        private fun kindIndex(vt: IrValType): Int = when (vt) {
            IrValType.I32 -> 0
            IrValType.I64 -> 1
            IrValType.F32 -> 2
            IrValType.F64 -> 3
            IrValType.FUNCREF, IrValType.EXTERNREF -> 4
        }
    }
}
