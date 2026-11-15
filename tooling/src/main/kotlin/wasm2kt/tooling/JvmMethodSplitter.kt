package wasm2kt.tooling

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.IincInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.MultiANewArrayInsnNode
import org.objectweb.asm.tree.TableSwitchInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.BasicInterpreter
import org.objectweb.asm.tree.analysis.BasicValue
import org.objectweb.asm.tree.analysis.Frame
import org.objectweb.asm.util.CheckClassAdapter
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URLClassLoader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.BitSet
import java.util.IdentityHashMap
import kotlin.io.path.extension

/**
 * Splits oversized Chicory-compiled `func_<N>` JVM methods into smaller private static helper
 * methods so ART (`dex2oat`, 16,383 DEX code unit limit) and HotSpot C2 (8,000-byte
 * `DontCompileHugeMethods` limit) can compile them instead of falling back to the interpreter.
 *
 * Chicory's Wasm compiler stores only primitive types in Wasm local slots after parameter
 * setup and initializes all Wasm locals in the prologue, making Single-Entry Multi-Exit
 * zero-stack region outlining safe and sound without modifying upstream Chicory.
 */
object JvmMethodSplitter {
    /** HotSpot C2 default HugeMethodLimit (methods strictly larger than this skip JIT by default). */
    const val HOTSPOT_HUGE_METHOD_LIMIT_BYTES: Int = 8_000

    /**
     * Conservative JVM bytecode threshold corresponding to ART's 16,383 DEX 16-bit code unit limit
     * (`Method exceeds compiler instruction limit` in `dex2oat`).
     *
     * Observed on `png_glitch` (`Core0MachineFuncGroup_0`): 24,557 JVM bytes produced 16,847 DEX
     * code units (exceeded ART limit), while 20,245 JVM bytes produced 13,569 DEX code units.
     */
    const val ART_OVERSIZED_BYTECODE_THRESHOLD_BYTES: Int = 21_000

    /** Default target JVM bytecode ceiling per method after splitting. */
    const val DEFAULT_TARGET_METHOD_BYTES: Int = 7_500

    private const val MIN_REGION_BYTES: Int = 320
    private const val MIN_REGION_BYTES_RELAXED: Int = 180
    private const val MAX_REGION_BYTES: Int = 3_800
    private const val MAX_EXTERNAL_TARGETS: Int = 12
    private const val MAX_EXTERNAL_TARGETS_RELAXED: Int = 20
    private const val MAX_HELPER_PARAM_SLOTS: Int = 240
    private const val MAX_HELPERS_PER_METHOD: Int = 128

    /** Summary of method sizes (and splitting performed) for a core module's generated classes. */
    data class MethodSizeStats(
        val splitMethods: Int,
        val generatedHelpers: Int,
        val hotspotOversizedMethods: Int,
        val artOversizedMethods: Int,
        val maxMethodCodeBytes: Int,
    )

    /**
     * Splits oversized `func_*` methods in classes matching [classRelativePathPrefix] under
     * [classesDir], and returns the resulting method-size statistics across all matching classes.
     */
    fun processClasses(
        classesDir: Path,
        classRelativePathPrefix: String,
        targetMethodBytes: Int = DEFAULT_TARGET_METHOD_BYTES,
    ): MethodSizeStats {
        if (!Files.isDirectory(classesDir)) {
            return MethodSizeStats(0, 0, 0, 0, 0)
        }
        val classFiles = Files.walk(classesDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.extension == "class" }
                .filter {
                    val rel = classesDir.relativize(it).toString().replace('\\', '/')
                    matchesClassPrefix(rel, classRelativePathPrefix)
                }
                .sorted()
                .toList()
        }
        if (classFiles.isEmpty()) {
            return MethodSizeStats(0, 0, 0, 0, 0)
        }

        var splitMethods = 0
        var generatedHelpers = 0

        URLClassLoader(arrayOf(classesDir.toUri().toURL()), JvmMethodSplitter::class.java.classLoader).use { loader ->
            for (classFile in classFiles) {
                val originalBytes = Files.readAllBytes(classFile)
                val initialSizes = readMethodCodeLengths(originalBytes)
                val needsSplit = initialSizes.any { (key, len) ->
                    isSplittableChicoryFunc(key.name) && len > targetMethodBytes
                }
                if (!needsSplit) continue

                val outcome = splitClassBytes(originalBytes, targetMethodBytes, loader)
                if (outcome.splitMethods > 0) {
                    Files.write(classFile, outcome.bytes)
                    splitMethods += outcome.splitMethods
                    generatedHelpers += outcome.generatedHelpers
                }
            }
        }

        val stats = measureClassFiles(classFiles)
        return MethodSizeStats(
            splitMethods = splitMethods,
            generatedHelpers = generatedHelpers,
            hotspotOversizedMethods = stats.hotspotOversizedMethods,
            artOversizedMethods = stats.artOversizedMethods,
            maxMethodCodeBytes = stats.maxMethodCodeBytes,
        )
    }

    /** Measures method `Code` attribute sizes across all `.class` files in [classesDir]. */
    fun measureClassesDir(classesDir: Path): MethodSizeStats {
        if (!Files.isDirectory(classesDir)) return MethodSizeStats(0, 0, 0, 0, 0)
        val classFiles = Files.walk(classesDir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.extension == "class" }.sorted().toList()
        }
        return measureClassFiles(classFiles)
    }

    private fun measureClassFiles(classFiles: List<Path>): MethodSizeStats {
        var hotspotOversized = 0
        var artOversized = 0
        var maxCodeBytes = 0
        for (classFile in classFiles) {
            val bytes = Files.readAllBytes(classFile)
            for ((_, codeLen) in readMethodCodeLengths(bytes)) {
                if (codeLen > maxCodeBytes) maxCodeBytes = codeLen
                if (codeLen > HOTSPOT_HUGE_METHOD_LIMIT_BYTES) hotspotOversized++
                if (codeLen >= ART_OVERSIZED_BYTECODE_THRESHOLD_BYTES) artOversized++
            }
        }
        return MethodSizeStats(
            splitMethods = 0,
            generatedHelpers = 0,
            hotspotOversizedMethods = hotspotOversized,
            artOversizedMethods = artOversized,
            maxMethodCodeBytes = maxCodeBytes,
        )
    }

    private fun matchesClassPrefix(relativePath: String, prefix: String): Boolean {
        if (!relativePath.startsWith(prefix)) return false
        if (relativePath == "$prefix.class") return true
        if (relativePath.length <= prefix.length) return false
        return !relativePath[prefix.length].isDigit()
    }

    private fun isSplittableChicoryFunc(name: String): Boolean {
        if (!name.startsWith("func_") || '$' in name) return false
        return name.substringAfter("func_").toIntOrNull() != null
    }

    private data class SplitClassOutcome(
        val bytes: ByteArray,
        val splitMethods: Int,
        val generatedHelpers: Int,
    )

    private fun splitClassBytes(
        originalBytes: ByteArray,
        targetMethodBytes: Int,
        loader: ClassLoader,
    ): SplitClassOutcome = try {
        val cn = ClassNode()
        ClassReader(originalBytes).accept(cn, ClassReader.SKIP_FRAMES)

        val initialSizes = readMethodCodeLengths(originalBytes)
        val existingMethodNames = cn.methods.mapTo(HashSet()) { it.name }
        val addedHelpers = mutableListOf<MethodNode>()
        var splitMethodCount = 0

        for (mn in cn.methods) {
            if (!isSplittableChicoryFunc(mn.name)) continue
            val initialSize = initialSizes[MethodKey(mn.name, mn.desc)] ?: estimateMethodBytes(mn.instructions)
            if (initialSize <= targetMethodBytes) continue

            val helpers = try {
                trySplitMethod(cn.name, mn, targetMethodBytes, existingMethodNames)
            } catch (_: Exception) {
                emptyList()
            }
            if (helpers.isNotEmpty()) {
                addedHelpers.addAll(helpers)
                splitMethodCount++
            }
        }

        if (splitMethodCount == 0) {
            SplitClassOutcome(originalBytes, 0, 0)
        } else {
            cn.methods.addAll(addedHelpers)

            val cw = object : ClassWriter(COMPUTE_MAXS or COMPUTE_FRAMES) {
                override fun getCommonSuperClass(type1: String, type2: String): String {
                    return try {
                        var c = Class.forName(type1.replace('/', '.'), false, loader)
                        val d = Class.forName(type2.replace('/', '.'), false, loader)
                        if (c.isAssignableFrom(d)) return type1
                        if (d.isAssignableFrom(c)) return type2
                        if (c.isInterface || d.isInterface) return "java/lang/Object"
                        do {
                            c = c.superclass ?: return "java/lang/Object"
                        } while (!c.isAssignableFrom(d))
                        c.name.replace('.', '/')
                    } catch (_: Exception) {
                        "java/lang/Object"
                    } catch (_: LinkageError) {
                        "java/lang/Object"
                    }
                }
            }
            cn.accept(cw)
            val candidateBytes = cw.toByteArray()

            val sw = StringWriter()
            CheckClassAdapter.verify(ClassReader(candidateBytes), loader, false, PrintWriter(sw))
            val verifyErrors = sw.toString()
            if (verifyErrors.isNotBlank()) {
                // Fall back cleanly if any unexpected bytecode pattern fails strict verification.
                SplitClassOutcome(originalBytes, 0, 0)
            } else {
                SplitClassOutcome(candidateBytes, splitMethodCount, addedHelpers.size)
            }
        }
    } catch (_: Exception) {
        SplitClassOutcome(originalBytes, 0, 0)
    }

    private fun trySplitMethod(
        ownerInternalName: String,
        mn: MethodNode,
        targetMethodBytes: Int,
        existingMethodNames: MutableSet<String>,
    ): List<MethodNode> {
        if (mn.tryCatchBlocks.isNotEmpty()) return emptyList()
        if ((mn.access and Opcodes.ACC_ABSTRACT) != 0 || (mn.access and Opcodes.ACC_NATIVE) != 0) return emptyList()
        if (hasUnsupportedInstructions(mn.instructions)) return emptyList()

        // Work on a cloned MethodNode so unsplit or failed methods retain original frames and line numbers.
        val work = MethodNode(Opcodes.ASM9, mn.access, mn.name, mn.desc, mn.signature, mn.exceptions?.toTypedArray())
        mn.accept(work)
        stripFrameAndLineNodes(work.instructions)

        val localTypes = inferLocalTypes(work) ?: return emptyList()
        val frameSlot = work.maxLocals
        val grownTypes = localTypes.copyOf(frameSlot + 1)
        grownTypes[frameSlot] = Type.getType("[J")
        work.maxLocals = frameSlot + 1

        var prologueEnd = findPrologueEnd(work.instructions)
        // Insert placeholder `ICONST_1; NEWARRAY T_LONG; ASTORE frameSlot` at prologueEnd so
        // Analyzer types `frameSlot` consistently as `[J` across all splitting iterations.
        val arraySizeInsn = InsnNode(Opcodes.ICONST_1)
        val newArrayInsn = IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_LONG)
        val storeFrameInsn = VarInsnNode(Opcodes.ASTORE, frameSlot)
        if (prologueEnd == 0) {
            work.instructions.insert(storeFrameInsn)
            work.instructions.insert(newArrayInsn)
            work.instructions.insert(arraySizeInsn)
        } else {
            val anchor = work.instructions.get(prologueEnd - 1)
            work.instructions.insert(anchor, storeFrameInsn)
            work.instructions.insert(anchor, newArrayInsn)
            work.instructions.insert(anchor, arraySizeInsn)
        }
        prologueEnd += 3

        val helpers = mutableListOf<MethodNode>()
        var maxOutCount = 0
        var nextHelperIdx = 0

        // Phase 1: conservative thresholds; Phase 2 (relaxed) only runs if method is still oversized.
        for (relaxed in listOf(false, true)) {
            val minRegionBytes = if (relaxed) MIN_REGION_BYTES_RELAXED else MIN_REGION_BYTES
            val maxExternalTargets = if (relaxed) MAX_EXTERNAL_TARGETS_RELAXED else MAX_EXTERNAL_TARGETS
            while (helpers.size < MAX_HELPERS_PER_METHOD &&
                estimateMethodBytes(work.instructions) > targetMethodBytes
            ) {
                val analysis = analyzeMethod(ownerInternalName, work, grownTypes, frameSlot) ?: break
                val region = findBestRegion(
                    mn = work,
                    analysis = analysis,
                    prologueEnd = prologueEnd,
                    minRegionBytes = minRegionBytes,
                    maxRegionBytes = MAX_REGION_BYTES,
                    maxExternalTargets = maxExternalTargets,
                    frameSlot = frameSlot,
                    localTypes = grownTypes,
                ) ?: break

                var helperName: String
                do {
                    helperName = "${work.name}\$s${nextHelperIdx++}"
                } while (!existingMethodNames.add(helperName))

                val helper = outlineRegion(
                    ownerInternalName = ownerInternalName,
                    caller = work,
                    helperName = helperName,
                    region = region,
                    localTypes = grownTypes,
                    frameSlot = frameSlot,
                )
                helpers.add(helper)
                if (region.outLocals.size > maxOutCount) {
                    maxOutCount = region.outLocals.size
                }
            }
        }

        if (helpers.isEmpty()) {
            return emptyList()
        }

        if (maxOutCount == 0) {
            work.instructions.remove(arraySizeInsn)
            work.instructions.remove(newArrayInsn)
            work.instructions.remove(storeFrameInsn)
            work.maxLocals = frameSlot
        } else {
            work.instructions.set(arraySizeInsn, pushIntInsn(maxOutCount))
        }
        mn.instructions = work.instructions
        mn.maxLocals = work.maxLocals
        mn.maxStack = work.maxStack
        mn.localVariables?.clear()
        return helpers
    }

    private fun hasUnsupportedInstructions(insns: InsnList): Boolean {
        var cur = insns.first
        while (cur != null) {
            when (cur.opcode) {
                Opcodes.ASTORE, Opcodes.JSR, Opcodes.RET -> return true
            }
            cur = cur.next
        }
        return false
    }

    private fun stripFrameAndLineNodes(insns: InsnList) {
        var cur = insns.first
        while (cur != null) {
            val next = cur.next
            if (cur is FrameNode || cur is LineNumberNode) {
                insns.remove(cur)
            }
            cur = next
        }
    }

    private fun inferLocalTypes(mn: MethodNode): Array<Type?>? {
        val types = arrayOfNulls<Type>(mn.maxLocals.coerceAtLeast(1))
        var slot = if ((mn.access and Opcodes.ACC_STATIC) == 0) 1 else 0
        if (slot == 1) {
            types[0] = Type.getObjectType("java/lang/Object")
        }
        for (arg in Type.getArgumentTypes(mn.desc)) {
            if (slot >= types.size) return null
            types[slot] = arg
            slot += arg.size
        }

        fun record(varIndex: Int, type: Type): Boolean {
            if (varIndex < 0 || varIndex >= types.size) return false
            val existing = types[varIndex]
            if (existing == null) {
                types[varIndex] = type
                return true
            }
            return samePrimitiveOrRef(existing, type)
        }

        var cur = mn.instructions.first
        while (cur != null) {
            when (cur) {
                is VarInsnNode -> {
                    val t = when (cur.opcode) {
                        Opcodes.ILOAD, Opcodes.ISTORE -> Type.INT_TYPE
                        Opcodes.LLOAD, Opcodes.LSTORE -> Type.LONG_TYPE
                        Opcodes.FLOAD, Opcodes.FSTORE -> Type.FLOAT_TYPE
                        Opcodes.DLOAD, Opcodes.DSTORE -> Type.DOUBLE_TYPE
                        Opcodes.ALOAD -> types.getOrNull(cur.`var`) ?: Type.getObjectType("java/lang/Object")
                        else -> return null
                    }
                    if (!record(cur.`var`, t)) return null
                }
                is IincInsnNode -> if (!record(cur.`var`, Type.INT_TYPE)) return null
            }
            cur = cur.next
        }
        return types
    }

    private fun samePrimitiveOrRef(a: Type, b: Type): Boolean {
        if (a.sort == b.sort) return true
        val aIsRef = a.sort == Type.OBJECT || a.sort == Type.ARRAY
        val bIsRef = b.sort == Type.OBJECT || b.sort == Type.ARRAY
        return aIsRef && bIsRef
    }

    private fun findPrologueEnd(insns: InsnList): Int {
        // Collect all branch targets so we never advance prologueEnd past a target label.
        val branchTargets = HashSet<LabelNode>()
        run {
            var cur = insns.first
            while (cur != null) {
                when (cur) {
                    is JumpInsnNode -> branchTargets.add(cur.label)
                    is TableSwitchInsnNode -> {
                        branchTargets.add(cur.dflt)
                        branchTargets.addAll(cur.labels)
                    }
                    is LookupSwitchInsnNode -> {
                        branchTargets.add(cur.dflt)
                        branchTargets.addAll(cur.labels)
                    }
                }
                cur = cur.next
            }
        }

        var i = 0
        val n = insns.size()
        while (i < n) {
            val insn = insns.get(i)
            if (insn is LabelNode) {
                if (insn in branchTargets) break
                i++
                continue
            }
            if (isConstantPush(insn) && i + 1 < n) {
                val next = insns.get(i + 1)
                if (next is VarInsnNode && next.opcode in Opcodes.ISTORE..Opcodes.DSTORE) {
                    i += 2
                    continue
                }
            }
            break
        }
        return i
    }

    private fun isConstantPush(insn: AbstractInsnNode): Boolean = when (insn.opcode) {
        Opcodes.ACONST_NULL,
        Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
        Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5,
        Opcodes.LCONST_0, Opcodes.LCONST_1,
        Opcodes.FCONST_0, Opcodes.FCONST_1, Opcodes.FCONST_2,
        Opcodes.DCONST_0, Opcodes.DCONST_1,
        Opcodes.BIPUSH, Opcodes.SIPUSH, Opcodes.LDC -> true
        else -> false
    }

    private class MethodAnalysis(
        val frames: Array<Frame<BasicValue>?>,
        val labelIndices: IdentityHashMap<LabelNode, Int>,
        val incomingFrom: Array<List<Int>>,
        val outgoingTargets: Array<List<LabelNode>>,
        val canFallThrough: BooleanArray,
        val liveIn: Array<BitSet>,
        val prefixBytes: IntArray,
        val callsHelper: BooleanArray,
    )

    private fun analyzeMethod(
        ownerInternalName: String,
        mn: MethodNode,
        localTypes: Array<Type?>,
        frameSlot: Int,
    ): MethodAnalysis? {
        val n = mn.instructions.size()
        mn.maxStack = maxOf(mn.maxStack, 256)
        val frames: Array<Frame<BasicValue>?> = try {
            @Suppress("UNCHECKED_CAST")
            Analyzer(BasicInterpreter()).analyze(ownerInternalName, mn)
        } catch (_: Exception) {
            return null
        }

        val labelIndices = IdentityHashMap<LabelNode, Int>()
        for (i in 0 until n) {
            val insn = mn.instructions.get(i)
            if (insn is LabelNode) labelIndices[insn] = i
        }

        val incoming = Array(n) { mutableListOf<Int>() }
        val outgoing = Array(n) { emptyList<LabelNode>() }
        val succs = Array(n) { mutableListOf<Int>() }
        val canFallThrough = BooleanArray(n)
        val use = Array(n) { BitSet(mn.maxLocals) }
        val def = Array(n) { BitSet(mn.maxLocals) }
        val prefixBytes = IntArray(n + 1)
        val callsHelper = BooleanArray(n)

        for (i in 0 until n) {
            val insn = mn.instructions.get(i)
            prefixBytes[i + 1] = prefixBytes[i] + estimateInsnBytes(insn)

            if (insn is MethodInsnNode &&
                insn.owner == ownerInternalName &&
                insn.name.startsWith("${mn.name}\$")
            ) {
                callsHelper[i] = true
            }

            when (insn) {
                is VarInsnNode -> when (insn.opcode) {
                    Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD ->
                        if (insn.`var` != frameSlot) use[i].set(insn.`var`)
                    Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE ->
                        if (insn.`var` != frameSlot) def[i].set(insn.`var`)
                }
                is IincInsnNode -> {
                    use[i].set(insn.`var`)
                    def[i].set(insn.`var`)
                }
            }

            val targets = when (insn) {
                is JumpInsnNode -> listOf(insn.label)
                is TableSwitchInsnNode -> listOf(insn.dflt) + insn.labels
                is LookupSwitchInsnNode -> listOf(insn.dflt) + insn.labels
                else -> emptyList()
            }
            if (targets.isNotEmpty()) {
                val distinct = ArrayList<LabelNode>(targets.size)
                val seen = IdentityHashMap<LabelNode, Boolean>()
                for (lbl in targets) {
                    if (seen.put(lbl, true) == null) {
                        distinct.add(lbl)
                        val tIdx = labelIndices[lbl] ?: return null
                        incoming[tIdx].add(i)
                        succs[i].add(tIdx)
                    }
                }
                outgoing[i] = distinct
            }

            val falls = when (insn.opcode) {
                Opcodes.GOTO, Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN,
                Opcodes.DRETURN, Opcodes.ARETURN, Opcodes.RETURN,
                Opcodes.ATHROW, Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH -> false
                else -> true
            }
            canFallThrough[i] = falls
            if (falls && i + 1 < n) {
                succs[i].add(i + 1)
            }
        }

        val liveIn = Array(n) { BitSet(mn.maxLocals) }
        val liveOut = Array(n) { BitSet(mn.maxLocals) }
        val tmp = BitSet(mn.maxLocals)
        var changed = true
        while (changed) {
            changed = false
            for (i in n - 1 downTo 0) {
                liveOut[i].clear()
                for (s in succs[i]) {
                    liveOut[i].or(liveIn[s])
                }
                tmp.clear()
                tmp.or(liveOut[i])
                tmp.andNot(def[i])
                tmp.or(use[i])
                if (tmp != liveIn[i]) {
                    liveIn[i].clear()
                    liveIn[i].or(tmp)
                    changed = true
                }
            }
        }

        // Verify localTypes covers every variable slot that is read.
        for (i in 0 until n) {
            var v = use[i].nextSetBit(0)
            while (v >= 0) {
                if (localTypes[v] == null) return null
                v = use[i].nextSetBit(v + 1)
            }
        }

        return MethodAnalysis(
            frames = frames,
            labelIndices = labelIndices,
            incomingFrom = Array(n) { incoming[it] },
            outgoingTargets = outgoing,
            canFallThrough = canFallThrough,
            liveIn = liveIn,
            prefixBytes = prefixBytes,
            callsHelper = callsHelper,
        )
    }

    private class CandidateRegion(
        val start: Int,
        val leadingLabelsEnd: Int,
        val end: Int,
        val trailingLabelsEnd: Int,
        val fallthroughOut: Boolean,
        val externalTargets: List<LabelNode>,
        val paramLocals: List<Int>,
        val outLocals: List<Int>,
        val netSavingsBytes: Int,
    )

    private fun findBestRegion(
        mn: MethodNode,
        analysis: MethodAnalysis,
        prologueEnd: Int,
        minRegionBytes: Int,
        maxRegionBytes: Int,
        maxExternalTargets: Int,
        frameSlot: Int,
        localTypes: Array<Type?>,
    ): CandidateRegion? {
        val n = mn.instructions.size()
        var best: CandidateRegion? = null

        var s = prologueEnd
        while (s < n) {
            // Keep consecutive labels together at region entry so none are left straddling.
            if (s > prologueEnd && mn.instructions.get(s - 1) is LabelNode) {
                s++
                continue
            }
            val startFrame = analysis.frames[s]
            if (startFrame == null || startFrame.stackSize != 0) {
                s++
                continue
            }

            var leadingLabelsEnd = s
            while (leadingLabelsEnd < n && mn.instructions.get(leadingLabelsEnd) is LabelNode) {
                leadingLabelsEnd++
            }
            if (leadingLabelsEnd >= n) break

            // Do not wrap an already-outlined call site or frameSlot access.
            val firstInsn = mn.instructions.get(leadingLabelsEnd)
            val touchesFrameAtStart = firstInsn is VarInsnNode && firstInsn.`var` == frameSlot
            if (analysis.callsHelper[leadingLabelsEnd] || touchesFrameAtStart) {
                s++
                continue
            }

            val paramLocals = collectParamLocals(
                liveInAtStart = analysis.liveIn[s],
                startFrame = startFrame,
                localTypes = localTypes,
                frameSlot = frameSlot,
            )
            if (paramLocals == null) {
                s++
                continue
            }

            var containsLeftExternalIncoming = false
            val modified = BitSet(mn.maxLocals)

            var e = leadingLabelsEnd + 1
            while (e <= n) {
                val prevIdx = e - 1
                val prevInsn = mn.instructions.get(prevIdx)

                // Never include returns, jsr/ret, or previously outlined call/frame sequences.
                val touchesFrameSlot = prevInsn is VarInsnNode && prevInsn.`var` == frameSlot
                if (prevInsn.opcode in Opcodes.IRETURN..Opcodes.RETURN ||
                    prevInsn.opcode == Opcodes.JSR ||
                    prevInsn.opcode == Opcodes.RET ||
                    analysis.callsHelper[prevIdx] ||
                    touchesFrameSlot
                ) {
                    break
                }

                if (prevIdx >= leadingLabelsEnd) {
                    for (src in analysis.incomingFrom[prevIdx]) {
                        if (src < s) {
                            // Edge from before s into strictly inside (s, e); no growth of e can fix this.
                            containsLeftExternalIncoming = true
                            break
                        }
                    }
                    if (containsLeftExternalIncoming) break
                }

                when (prevInsn) {
                    is VarInsnNode -> if (prevInsn.opcode in Opcodes.ISTORE..Opcodes.DSTORE && prevInsn.`var` != frameSlot) {
                        modified.set(prevInsn.`var`)
                    }
                    is IincInsnNode -> if (prevInsn.`var` != frameSlot) {
                        modified.set(prevInsn.`var`)
                    }
                }

                val regionBytes = analysis.prefixBytes[e] - analysis.prefixBytes[s]
                if (regionBytes > maxRegionBytes) break

                if (regionBytes >= minRegionBytes) {
                    val endFrame = if (e < n) analysis.frames[e] else null
                    val validEnd = endFrame != null && endFrame.stackSize == 0
                    if (validEnd) {
                        var trailingLabelsEnd = e
                        while (trailingLabelsEnd < n && mn.instructions.get(trailingLabelsEnd) is LabelNode) {
                            trailingLabelsEnd++
                        }

                        // Ensure no edge from >= e targets strictly inside [leadingLabelsEnd, e).
                        var hasRightExternalIncoming = false
                        for (k in leadingLabelsEnd until e) {
                            for (src in analysis.incomingFrom[k]) {
                                if (src >= e) {
                                    hasRightExternalIncoming = true
                                    break
                                }
                            }
                            if (hasRightExternalIncoming) break
                        }

                        if (!hasRightExternalIncoming) {
                            val candidate = buildCandidateIfValid(
                                mn = mn,
                                analysis = analysis,
                                start = s,
                                leadingLabelsEnd = leadingLabelsEnd,
                                end = e,
                                trailingLabelsEnd = trailingLabelsEnd,
                                regionBytes = regionBytes,
                                maxExternalTargets = maxExternalTargets,
                                paramLocals = paramLocals,
                                modified = modified,
                                frameSlot = frameSlot,
                                localTypes = localTypes,
                            )
                            if (candidate != null && (best == null || candidate.netSavingsBytes > best.netSavingsBytes)) {
                                best = candidate
                            }
                        }
                    }
                }
                e++
            }
            s++
        }

        return best
    }

    private fun collectParamLocals(
        liveInAtStart: BitSet,
        startFrame: Frame<BasicValue>,
        localTypes: Array<Type?>,
        frameSlot: Int,
    ): List<Int>? {
        val params = mutableListOf<Int>()
        var slots = 0
        var v = liveInAtStart.nextSetBit(0)
        while (v >= 0) {
            if (v != frameSlot) {
                val t = localTypes[v] ?: return null
                // If startFrame sees the local as uninitialized, loading it in caller would fail verification.
                if (startFrame.getLocal(v) == BasicValue.UNINITIALIZED_VALUE) return null
                params.add(v)
                slots += t.size
                if (slots > MAX_HELPER_PARAM_SLOTS) return null
            }
            v = liveInAtStart.nextSetBit(v + 1)
        }
        return params
    }

    private fun buildCandidateIfValid(
        mn: MethodNode,
        analysis: MethodAnalysis,
        start: Int,
        leadingLabelsEnd: Int,
        end: Int,
        trailingLabelsEnd: Int,
        regionBytes: Int,
        maxExternalTargets: Int,
        paramLocals: List<Int>,
        modified: BitSet,
        frameSlot: Int,
        localTypes: Array<Type?>,
    ): CandidateRegion? {
        val extTargets = mutableListOf<LabelNode>()
        val extSeen = IdentityHashMap<LabelNode, Boolean>()
        var branchesToFallthrough = false

        for (k in start until end) {
            for (lbl in analysis.outgoingTargets[k]) {
                val tIdx = analysis.labelIndices[lbl] ?: return null
                if (tIdx in start until end) continue // internal branch
                if (tIdx in end until trailingLabelsEnd) {
                    branchesToFallthrough = true
                    continue
                }
                val tFrame = analysis.frames[tIdx] ?: return null
                if (tFrame.stackSize != 0) return null
                if (extSeen.put(lbl, true) == null) {
                    extTargets.add(lbl)
                    if (extTargets.size > maxExternalTargets) return null
                }
            }
        }

        val lastInstructionFalls = analysis.canFallThrough[end - 1]
        val fallthroughOut = lastInstructionFalls || branchesToFallthrough
        if (!fallthroughOut && extTargets.isEmpty()) return null

        // Collect all locals live at any exit point of [start, end).
        val liveAfterExit = BitSet(mn.maxLocals)
        if (fallthroughOut) {
            liveAfterExit.or(analysis.liveIn[end])
        }
        for (lbl in extTargets) {
            val tIdx = analysis.labelIndices[lbl] ?: return null
            liveAfterExit.or(analysis.liveIn[tIdx])
        }

        val outSet = (modified.clone() as BitSet).apply { and(liveAfterExit) }
        val outLocals = mutableListOf<Int>()
        var v = outSet.nextSetBit(0)
        while (v >= 0) {
            if (v != frameSlot) {
                val t = localTypes[v] ?: return null
                // Frame serialization stores primitives cleanly; non-primitives are not expected here.
                if (t.sort !in Type.BOOLEAN..Type.DOUBLE) return null
                // Ensure each outLocal is initialized along every reachable exit frame.
                if (fallthroughOut && analysis.frames[end]?.getLocal(v) == BasicValue.UNINITIALIZED_VALUE) {
                    return null
                }
                for (lbl in extTargets) {
                    val tIdx = analysis.labelIndices[lbl] ?: return null
                    if (analysis.frames[tIdx]?.getLocal(v) == BasicValue.UNINITIALIZED_VALUE) {
                        return null
                    }
                }
                outLocals.add(v)
            }
            v = outSet.nextSetBit(v + 1)
        }

        // Estimate call-site + switch + unpack overhead vs savings in caller.
        val callOverhead = paramLocals.size * 2 +
            (if (outLocals.isNotEmpty()) 2 else 0) +
            3 +
            outLocals.size * 6 +
            extTargets.size * 5
        val netSavings = regionBytes - callOverhead - extTargets.size * 64
        if (netSavings <= 0) return null

        return CandidateRegion(
            start = start,
            leadingLabelsEnd = leadingLabelsEnd,
            end = end,
            trailingLabelsEnd = trailingLabelsEnd,
            fallthroughOut = fallthroughOut,
            externalTargets = extTargets,
            paramLocals = paramLocals,
            outLocals = outLocals,
            netSavingsBytes = netSavings,
        )
    }

    private fun outlineRegion(
        ownerInternalName: String,
        caller: MethodNode,
        helperName: String,
        region: CandidateRegion,
        localTypes: Array<Type?>,
        frameSlot: Int,
    ): MethodNode {
        val usesFrame = region.outLocals.isNotEmpty()
        val hasExtTargets = region.externalTargets.isNotEmpty()

        val paramDesc = buildString {
            append('(')
            for (v in region.paramLocals) {
                append(localTypes[v]!!.descriptor)
            }
            if (usesFrame) {
                append("[J")
            }
            append(')')
            append(if (hasExtTargets) 'I' else 'V')
        }

        val helper = MethodNode(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC, helperName, paramDesc, null, null)

        // Remap caller local slots to dense helper local slots.
        val helperLocalMap = mutableMapOf<Int, Int>()
        var nextHelperSlot = 0
        for (v in region.paramLocals) {
            helperLocalMap[v] = nextHelperSlot
            nextHelperSlot += localTypes[v]!!.size
        }
        val helperFrameSlot = if (usesFrame) nextHelperSlot++ else -1

        // Allocate helper slots for any locals defined or modified inside [start, end) not in paramLocals.
        val regionInsns = mutableListOf<AbstractInsnNode>()
        for (i in region.start until region.end) {
            regionInsns.add(caller.instructions.get(i))
        }
        for (insn in regionInsns) {
            val v = when (insn) {
                is VarInsnNode -> insn.`var`
                is IincInsnNode -> insn.`var`
                else -> -1
            }
            if (v >= 0 && v != frameSlot && v !in helperLocalMap) {
                val t = localTypes[v]!!
                helperLocalMap[v] = nextHelperSlot
                nextHelperSlot += t.size
            }
        }

        // Zero-initialize any local inside helperLocalMap that wasn't passed as a parameter so
        // verifier never sees an uninitialized register in the shared epilogue.
        for ((origVar, hVar) in helperLocalMap) {
            if (origVar !in region.paramLocals) {
                emitZeroLocal(helper.instructions, localTypes[origVar]!!, hVar)
            }
        }

        val internalLabelMap = IdentityHashMap<LabelNode, LabelNode>()
        val helperEntryLabel = LabelNode()
        helper.instructions.add(helperEntryLabel)

        for (i in region.start until region.leadingLabelsEnd) {
            val lbl = caller.instructions.get(i) as LabelNode
            internalLabelMap[lbl] = helperEntryLabel
        }
        for (i in region.leadingLabelsEnd until region.end) {
            val insn = caller.instructions.get(i)
            if (insn is LabelNode) {
                internalLabelMap[insn] = LabelNode()
            }
        }

        val fallthroughTargetLabel = LabelNode()
        for (i in region.end until region.trailingLabelsEnd) {
            val lbl = caller.instructions.get(i) as LabelNode
            internalLabelMap[lbl] = fallthroughTargetLabel
        }

        val extStubLabels = IdentityHashMap<LabelNode, LabelNode>()
        for (ext in region.externalTargets) {
            val stub = LabelNode()
            extStubLabels[ext] = stub
            internalLabelMap[ext] = stub
        }

        val cloningMap = IdentityHashMap<LabelNode, LabelNode>()
        cloningMap.putAll(internalLabelMap)

        for (i in region.leadingLabelsEnd until region.end) {
            val insn = caller.instructions.get(i)
            val cloned = when (insn) {
                is LabelNode -> internalLabelMap.getValue(insn)
                is VarInsnNode -> VarInsnNode(insn.opcode, helperLocalMap.getValue(insn.`var`))
                is IincInsnNode -> IincInsnNode(helperLocalMap.getValue(insn.`var`), insn.incr)
                else -> insn.clone(cloningMap)
            }
            helper.instructions.add(cloned)
        }

        if (region.fallthroughOut) {
            helper.instructions.add(fallthroughTargetLabel)
            emitStoreOutLocalsAndReturn(helper.instructions, region.outLocals, localTypes, helperLocalMap, helperFrameSlot, if (hasExtTargets) 0 else null)
        }

        region.externalTargets.forEachIndexed { idx, ext ->
            helper.instructions.add(extStubLabels.getValue(ext))
            emitStoreOutLocalsAndReturn(helper.instructions, region.outLocals, localTypes, helperLocalMap, helperFrameSlot, idx + 1)
        }

        helper.maxLocals = nextHelperSlot
        helper.maxStack = caller.maxStack.coerceAtLeast(32)

        // Replace [leadingLabelsEnd, end) in caller with the helper call and exit dispatch.
        val callReplacement = InsnList()
        for (v in region.paramLocals) {
            callReplacement.add(VarInsnNode(loadOpcode(localTypes[v]!!), v))
        }
        if (usesFrame) {
            callReplacement.add(VarInsnNode(Opcodes.ALOAD, frameSlot))
        }
        callReplacement.add(MethodInsnNode(Opcodes.INVOKESTATIC, ownerInternalName, helperName, paramDesc, false))

        if (usesFrame) {
            // Loading from `frameSlot` and storing into primitive locals leaves any int exit code on
            // the bottom of the operand stack untouched until the branch/switch dispatch below.
            for (i in region.outLocals.indices) {
                emitLoadOutLocalFromFrame(callReplacement, region.outLocals[i], localTypes[region.outLocals[i]]!!, frameSlot, i)
            }
        }

        if (hasExtTargets) {
            val afterLabel = LabelNode()
            val caseLabels = Array(region.externalTargets.size + 1) { i ->
                if (i == 0) afterLabel else region.externalTargets[i - 1]
            }
            if (region.externalTargets.size == 1) {
                callReplacement.add(JumpInsnNode(Opcodes.IFNE, region.externalTargets[0]))
            } else {
                callReplacement.add(TableSwitchInsnNode(0, region.externalTargets.size, afterLabel, *caseLabels))
            }
            callReplacement.add(afterLabel)
        }

        val insertionPoint = caller.instructions.get(region.leadingLabelsEnd - 1)
        val toRemove = (region.leadingLabelsEnd until region.end).map { caller.instructions.get(it) }
        for (node in toRemove) {
            caller.instructions.remove(node)
        }
        caller.instructions.insert(insertionPoint, callReplacement)

        return helper
    }

    private fun emitZeroLocal(insns: InsnList, type: Type, slot: Int) {
        when (type.sort) {
            Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> {
                insns.add(InsnNode(Opcodes.ICONST_0))
                insns.add(VarInsnNode(Opcodes.ISTORE, slot))
            }
            Type.LONG -> {
                insns.add(InsnNode(Opcodes.LCONST_0))
                insns.add(VarInsnNode(Opcodes.LSTORE, slot))
            }
            Type.FLOAT -> {
                insns.add(InsnNode(Opcodes.FCONST_0))
                insns.add(VarInsnNode(Opcodes.FSTORE, slot))
            }
            Type.DOUBLE -> {
                insns.add(InsnNode(Opcodes.DCONST_0))
                insns.add(VarInsnNode(Opcodes.DSTORE, slot))
            }
            else -> {
                insns.add(InsnNode(Opcodes.ACONST_NULL))
                insns.add(VarInsnNode(Opcodes.ASTORE, slot))
            }
        }
    }

    private fun emitStoreOutLocalsAndReturn(
        insns: InsnList,
        outLocals: List<Int>,
        localTypes: Array<Type?>,
        helperLocalMap: Map<Int, Int>,
        helperFrameSlot: Int,
        returnCode: Int?,
    ) {
        outLocals.forEachIndexed { idx, origVar ->
            val t = localTypes[origVar]!!
            val hVar = helperLocalMap.getValue(origVar)
            insns.add(VarInsnNode(Opcodes.ALOAD, helperFrameSlot))
            insns.add(pushIntInsn(idx))
            when (t.sort) {
                Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> {
                    insns.add(VarInsnNode(Opcodes.ILOAD, hVar))
                    insns.add(InsnNode(Opcodes.I2L))
                }
                Type.LONG -> {
                    insns.add(VarInsnNode(Opcodes.LLOAD, hVar))
                }
                Type.FLOAT -> {
                    insns.add(VarInsnNode(Opcodes.FLOAD, hVar))
                    insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Float", "floatToRawIntBits", "(F)I", false))
                    insns.add(InsnNode(Opcodes.I2L))
                }
                Type.DOUBLE -> {
                    insns.add(VarInsnNode(Opcodes.DLOAD, hVar))
                    insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "doubleToRawLongBits", "(D)J", false))
                }
                else -> error("Unexpected non-primitive out local type: $t")
            }
            insns.add(InsnNode(Opcodes.LASTORE))
        }
        if (returnCode != null) {
            insns.add(pushIntInsn(returnCode))
            insns.add(InsnNode(Opcodes.IRETURN))
        } else {
            insns.add(InsnNode(Opcodes.RETURN))
        }
    }

    private fun emitLoadOutLocalFromFrame(
        insns: InsnList,
        origVar: Int,
        t: Type,
        frameSlot: Int,
        frameIdx: Int,
    ) {
        insns.add(VarInsnNode(Opcodes.ALOAD, frameSlot))
        insns.add(pushIntInsn(frameIdx))
        insns.add(InsnNode(Opcodes.LALOAD))
        when (t.sort) {
            Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> {
                insns.add(InsnNode(Opcodes.L2I))
                insns.add(VarInsnNode(Opcodes.ISTORE, origVar))
            }
            Type.LONG -> {
                insns.add(VarInsnNode(Opcodes.LSTORE, origVar))
            }
            Type.FLOAT -> {
                insns.add(InsnNode(Opcodes.L2I))
                insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Float", "intBitsToFloat", "(I)F", false))
                insns.add(VarInsnNode(Opcodes.FSTORE, origVar))
            }
            Type.DOUBLE -> {
                insns.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false))
                insns.add(VarInsnNode(Opcodes.DSTORE, origVar))
            }
            else -> error("Unexpected non-primitive out local type: $t")
        }
    }

    private fun loadOpcode(t: Type): Int = when (t.sort) {
        Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.ILOAD
        Type.LONG -> Opcodes.LLOAD
        Type.FLOAT -> Opcodes.FLOAD
        Type.DOUBLE -> Opcodes.DLOAD
        else -> Opcodes.ALOAD
    }

    private fun pushIntInsn(value: Int): AbstractInsnNode = when (value) {
        -1 -> InsnNode(Opcodes.ICONST_M1)
        0 -> InsnNode(Opcodes.ICONST_0)
        1 -> InsnNode(Opcodes.ICONST_1)
        2 -> InsnNode(Opcodes.ICONST_2)
        3 -> InsnNode(Opcodes.ICONST_3)
        4 -> InsnNode(Opcodes.ICONST_4)
        5 -> InsnNode(Opcodes.ICONST_5)
        in Byte.MIN_VALUE..Byte.MAX_VALUE -> IntInsnNode(Opcodes.BIPUSH, value)
        else -> IntInsnNode(Opcodes.SIPUSH, value)
    }

    private fun estimateMethodBytes(insns: InsnList): Int {
        var total = 0
        var cur = insns.first
        while (cur != null) {
            total += estimateInsnBytes(cur)
            cur = cur.next
        }
        return total
    }

    private fun estimateInsnBytes(insn: AbstractInsnNode): Int = when (insn) {
        is LabelNode, is LineNumberNode, is FrameNode -> 0
        is InsnNode -> 1
        is IntInsnNode -> if (insn.opcode == Opcodes.SIPUSH) 3 else 2
        is VarInsnNode -> if (insn.`var` <= 3) 1 else if (insn.`var` <= 255) 2 else 4
        is IincInsnNode -> if (insn.`var` <= 255 && insn.incr in Byte.MIN_VALUE..Byte.MAX_VALUE) 3 else 6
        is LdcInsnNode -> if (insn.cst is Long || insn.cst is Double) 3 else 3
        is JumpInsnNode -> 3
        is TypeInsnNode, is FieldInsnNode, is MethodInsnNode -> if (insn is MethodInsnNode && insn.opcode == Opcodes.INVOKEINTERFACE) 5 else 3
        is InvokeDynamicInsnNode -> 5
        is MultiANewArrayInsnNode -> 4
        is TableSwitchInsnNode -> 16 + insn.labels.size * 4
        is LookupSwitchInsnNode -> 12 + insn.labels.size * 8
        else -> 3
    }

    private data class MethodKey(val name: String, val desc: String)

    /** Reads exact JVM `Code` attribute `code_length` for each method from raw `.class` bytes. */
    private fun readMethodCodeLengths(classBytes: ByteArray): Map<MethodKey, Int> {
        val buf = ByteBuffer.wrap(classBytes).order(ByteOrder.BIG_ENDIAN)
        if (buf.remaining() < 10 || buf.int != 0xCAFEBABE.toInt()) return emptyMap()
        buf.short // minor
        buf.short // major
        val cpCount = buf.short.toInt() and 0xFFFF
        val utf8 = arrayOfNulls<String>(cpCount)
        var i = 1
        while (i < cpCount) {
            val tag = buf.get().toInt() and 0xFF
            when (tag) {
                1 -> { // CONSTANT_Utf8
                    val len = buf.short.toInt() and 0xFFFF
                    val bytes = ByteArray(len)
                    buf.get(bytes)
                    utf8[i] = String(bytes, Charsets.UTF_8)
                }
                3, 4, 9, 10, 11, 12, 17, 18 -> buf.position(buf.position() + 4)
                5, 6 -> {
                    buf.position(buf.position() + 8)
                    i++ // takes 2 constant pool slots
                }
                7, 8, 16, 19, 20 -> buf.position(buf.position() + 2)
                15 -> buf.position(buf.position() + 3)
                else -> return emptyMap()
            }
            i++
        }

        buf.position(buf.position() + 6) // access_flags, this_class, super_class
        val ifaceCount = buf.short.toInt() and 0xFFFF
        buf.position(buf.position() + ifaceCount * 2)

        fun skipAttributes() {
            val count = buf.short.toInt() and 0xFFFF
            repeat(count) {
                buf.short // attr_name_index
                val len = buf.int
                buf.position(buf.position() + len)
            }
        }

        val fieldsCount = buf.short.toInt() and 0xFFFF
        repeat(fieldsCount) {
            buf.position(buf.position() + 6) // access, name, desc
            skipAttributes()
        }

        val methodsCount = buf.short.toInt() and 0xFFFF
        val result = LinkedHashMap<MethodKey, Int>(methodsCount)
        repeat(methodsCount) {
            buf.short // access
            val nameIdx = buf.short.toInt() and 0xFFFF
            val descIdx = buf.short.toInt() and 0xFFFF
            val attrCount = buf.short.toInt() and 0xFFFF
            var codeLen = 0
            repeat(attrCount) {
                val attrNameIdx = buf.short.toInt() and 0xFFFF
                val attrLen = buf.int
                val attrEnd = buf.position() + attrLen
                if (utf8.getOrNull(attrNameIdx) == "Code" && attrLen >= 8) {
                    buf.short // max_stack
                    buf.short // max_locals
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
