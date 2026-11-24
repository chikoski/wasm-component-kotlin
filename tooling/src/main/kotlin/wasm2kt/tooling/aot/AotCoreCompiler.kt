package wasm2kt.tooling.aot

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.SimpleVerifier
import org.objectweb.asm.util.CheckClassAdapter
import org.objectweb.asm.util.Textifier
import org.objectweb.asm.util.TraceMethodVisitor
import wasm2kt.tooling.JvmMethodSplitter
import wasm2kt.tooling.ir.IrFuncType
import wasm2kt.tooling.ir.IrModule
import wasm2kt.tooling.ir.IrValType
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Directly compiles Core WebAssembly typed IR ([IrModule]) into Java 17 JVM `.class` bytecode
 * using ASM without runtime Chicory dependencies, performing IR/CFG-level method splitting before
 * emission so functions exceeding JVM 64 KB or ART 16,383 DEX-unit limits never fall back to an
 * interpreter.
 */
object AotCoreCompiler {
    fun compile(
        ir: IrModule,
        packageName: String,
        baseSimpleName: String = "Core${ir.moduleIndex}",
        dataResourcePath: String? = null,
        options: AotCompileOptions = AotCompileOptions(),
    ): CompiledAotModule {
        val pkgPrefix = if (packageName.isEmpty()) "" else packageName.replace('.', '/') + "/"
        val instanceInternalName = "${pkgPrefix}${baseSimpleName}Instance"
        val funcTypes: (Int) -> IrFuncType = { idx ->
            ir.functionTypeOrNull(idx)
                ?: error("Function index $idx out of bounds in core module ${ir.moduleIndex}")
        }

        val sharedGlobals = AotModuleClassEmitter.computeSharedGlobals(ir)
        val plans = ir.definedFunctions.map { fn ->
            planWithAdaptiveCheck(
                ir = ir,
                fn = fn,
                pkgPrefix = pkgPrefix,
                baseSimpleName = baseSimpleName,
                instanceInternalName = instanceInternalName,
                sharedGlobals = sharedGlobals,
                options = options,
                funcTypes = funcTypes,
            )
        }

        val emitted = AotModuleClassEmitter.emit(
            ir = ir,
            packageName = packageName,
            baseSimpleName = baseSimpleName,
            dataResourcePathOverride = dataResourcePath,
            plans = plans,
            options = options,
        )

        var splitFunctions = 0
        var totalHelpers = 0
        for (p in plans) {
            if (p.helpers.isNotEmpty()) {
                splitFunctions++
                totalHelpers += p.helpers.size
            }
        }

        var maxMethodBytes = 0
        var maxMethodName: String? = null
        var hotspotOversized = 0
        var artOversized = 0
        for ((mName, codeLen) in emitted.methodCodeBytes) {
            if (codeLen > maxMethodBytes) {
                maxMethodBytes = codeLen
                maxMethodName = mName
            }
            if (codeLen > JvmMethodSplitter.HOTSPOT_HUGE_METHOD_LIMIT_BYTES) hotspotOversized++
            if (codeLen > JvmMethodSplitter.ART_OVERSIZED_BYTECODE_THRESHOLD_BYTES) artOversized++
        }
        val maxCp = emitted.constantPoolCounts.values.maxOrNull() ?: 0

        val stats = AotCompileStats(
            moduleIndex = ir.moduleIndex,
            definedFunctions = ir.definedFunctions.size,
            generatedClasses = emitted.classes.size,
            splitFunctions = splitFunctions,
            generatedSplitHelpers = totalHelpers,
            interpretedFunctions = 0,
            hotspotOversizedMethods = hotspotOversized,
            artOversizedMethods = artOversized,
            maxMethodCodeBytes = maxMethodBytes,
            maxMethodName = maxMethodName,
            maxConstantPoolCount = maxCp,
            dataResourceSizeBytes = emitted.dataResourceBytes?.size ?: 0,
        )

        val compiled = CompiledAotModule(
            moduleIndex = ir.moduleIndex,
            packageName = packageName,
            baseSimpleName = baseSimpleName,
            instanceInternalName = emitted.instanceInternalName,
            importsInterfaceInternalName = emitted.importsInterfaceInternalName,
            dataResourcePath = emitted.dataResourcePath,
            dataResourceBytes = emitted.dataResourceBytes,
            classes = emitted.classes,
            exports = emitted.exports,
            functionTargets = emitted.functionTargets,
            methodCodeBytes = emitted.methodCodeBytes,
            constantPoolCounts = emitted.constantPoolCounts,
            stats = stats,
        )
        if (options.verifyClasses) {
            verifyAll(compiled)
        }
        return compiled
    }

    fun compileToDirectory(
        ir: IrModule,
        classesDir: Path,
        resourcesDir: Path,
        packageName: String,
        baseSimpleName: String = "Core${ir.moduleIndex}",
        dataResourcePath: String? = null,
        options: AotCompileOptions = AotCompileOptions(),
    ): CompiledAotModule {
        val compiled = compile(ir, packageName, baseSimpleName, dataResourcePath, options)
        for ((internalName, bytes) in compiled.classes) {
            val out = classesDir.resolve("$internalName.class")
            Files.createDirectories(out.parent)
            Files.write(out, bytes)
        }
        val resPath = compiled.dataResourcePath
        val resBytes = compiled.dataResourceBytes
        if (resPath != null && resBytes != null) {
            val out = resourcesDir.resolve(resPath)
            Files.createDirectories(out.parent)
            Files.write(out, resBytes)
        }
        return compiled
    }

    /**
     * Verifies all emitted classfiles with ASM [CheckClassAdapter.verify] and enforces Java 17
     * bytecode constraints (major version 61, zero `invokedynamic`, zero `condy`, constant pool
     * count < 65,535).
     */
    fun verifyAll(
        compiled: CompiledAotModule,
        parentLoader: ClassLoader = AotCoreCompiler::class.java.classLoader,
    ): VerificationReport {
        val loader = InMemoryModuleClassLoader(compiled.classes, parentLoader)
        var maxBytes = 0
        var maxMethod: String? = null
        var maxCp = 0
        var hotspotOver = 0
        var artOver = 0
        var totalMethods = 0

        for ((internalName, bytes) in compiled.classes) {
            val major = readClassMajorVersion(bytes)
            check(major == Opcodes.V17) {
                "Expected Java 17 (major ${Opcodes.V17}) for $internalName, got $major"
            }
            val cpCount = AotModuleClassEmitter.readConstantPoolCount(bytes)
            check(cpCount < 65_535) {
                "Constant pool overflow risk in $internalName: $cpCount entries"
            }
            if (cpCount > maxCp) maxCp = cpCount
            assertNoDynamicCpTags(internalName, bytes)

            val cn = ClassNode()
            ClassReader(bytes).accept(cn, 0)
            for (mn in cn.methods) {
                var insn = mn.instructions.first
                while (insn != null) {
                    check(insn !is InvokeDynamicInsnNode) {
                        "Forbidden invokedynamic in $internalName.${mn.name}${mn.desc}"
                    }
                    if (insn is LdcInsnNode) {
                        val cst = insn.cst
                        check(cst !is ConstantDynamic && cst !is Handle && (cst !is Type || cst.sort != Type.METHOD)) {
                            "Forbidden condy/MethodHandle/MethodType LDC in $internalName.${mn.name}${mn.desc}"
                        }
                    }
                    insn = insn.next
                }
            }

            val sw = StringWriter()
            CheckClassAdapter.verify(ClassReader(bytes), loader, false, PrintWriter(sw))
            val errors = sw.toString()
            if (errors.isNotBlank()) {
                val diag = buildString {
                    val verifier = SimpleVerifier()
                    verifier.setClassLoader(loader)
                    val analyzer = Analyzer(verifier)
                    for (mn in cn.methods) {
                        try {
                            analyzer.analyze(cn.name, mn)
                        } catch (ex: org.objectweb.asm.tree.analysis.AnalyzerException) {
                            appendLine("--- Failing method: ${mn.name}${mn.desc} (msg=${ex.message}) ---")
                            val badIdx = if (ex.node != null) mn.instructions.indexOf(ex.node) else -1
                            val frames = analyzer.frames
                            val textifier = Textifier()
                            val tmv = TraceMethodVisitor(textifier)
                            val from = maxOf(0, badIdx - 25)
                            val to = minOf(mn.instructions.size() - 1, badIdx + 10)
                            for (i in 0 until mn.instructions.size()) {
                                textifier.text.clear()
                                mn.instructions[i].accept(tmv)
                                if (i in from..to || badIdx < 0) {
                                    val line = textifier.text.joinToString("").trim()
                                    val frame = frames?.getOrNull(i)?.toString() ?: "<null>"
                                    val mark = if (i == badIdx) " >>> " else "     "
                                    appendLine("$mark${i.toString().padStart(5, '0')}: $line   | $frame")
                                }
                            }
                        }
                    }
                    if (isEmpty()) {
                        appendLine(errors.take(4000))
                    }
                }
                error("CheckClassAdapter.verify failed for $internalName:\n${diag.take(8000)}")
            }

            val lengths = AotModuleClassEmitter.readMethodCodeLengths(bytes)
            totalMethods += cn.methods.size
            for ((key, len) in lengths) {
                if (len > maxBytes) {
                    maxBytes = len
                    maxMethod = "$internalName.${key.name}${key.desc}"
                }
                if (len > JvmMethodSplitter.HOTSPOT_HUGE_METHOD_LIMIT_BYTES) hotspotOver++
                if (len > JvmMethodSplitter.ART_OVERSIZED_BYTECODE_THRESHOLD_BYTES) artOver++
            }
        }

        return VerificationReport(
            verifiedClasses = compiled.classes.size,
            totalMethods = totalMethods,
            maxMethodCodeBytes = maxBytes,
            maxMethodName = maxMethod,
            maxConstantPoolCount = maxCp,
            hotspotOversizedMethods = hotspotOver,
            artOversizedMethods = artOver,
        )
    }

    private fun planWithAdaptiveCheck(
        ir: IrModule,
        fn: wasm2kt.tooling.ir.IrFunction,
        pkgPrefix: String,
        baseSimpleName: String,
        instanceInternalName: String,
        sharedGlobals: BooleanArray,
        options: AotCompileOptions,
        funcTypes: (Int) -> IrFuncType,
    ): FunctionSplitPlan {
        var currentTarget = options.targetMethodBytes
        var helperBudget = (options.targetMethodBytes * 0.76).toInt().coerceIn(2_000, 5_700)
        var childBudget = (helperBudget * 0.58).toInt().coerceIn(800, 3_400)
        var bestPlan = IrMethodSplitter.planFunction(
            fn = fn,
            instanceInternalName = instanceInternalName,
            targetMethodBytes = currentTarget,
            maxHelperBodyBytes = helperBudget,
            maxChildBodyBytes = childBudget,
            funcTypes = funcTypes,
        )

        // Probe exact emitted bytecode lengths whenever function is non-trivial or was split.
        val rootBodyEst = bestPlan.rootBody.sumOf { if (it.entryJvmReachable) it.estBytes else 0 }
        if (fn.instructions.size > 400 || bestPlan.helpers.isNotEmpty() || rootBodyEst > (options.targetMethodBytes * 0.65).toInt()) {
            val rootMethodName = AotNames.funcMethodName(fn.index)
            var (rootBytes, maxHelperBytes) = measureEmittedBytes(ir, bestPlan, pkgPrefix, baseSimpleName, instanceInternalName, sharedGlobals, rootMethodName)
            var bestMax = maxOf(rootBytes, maxHelperBytes)
            if (bestMax <= options.targetMethodBytes) return bestPlan

            repeat(3) {
                val desiredTarget = (options.targetMethodBytes * 0.76).toInt()
                if (rootBytes > options.targetMethodBytes) {
                    val ratio = desiredTarget.toDouble() / rootBytes.toDouble()
                    currentTarget = (currentTarget * ratio).toInt().coerceIn(400, maxOf(400, currentTarget - 200))
                    childBudget = (childBudget * ratio).toInt().coerceIn(300, maxOf(300, childBudget - 250))
                }
                if (maxHelperBytes > options.targetMethodBytes) {
                    val hRatio = desiredTarget.toDouble() / maxHelperBytes.toDouble()
                    helperBudget = (helperBudget * hRatio).toInt().coerceIn(1_800, maxOf(1_800, helperBudget - 200))
                    childBudget = minOf(childBudget, (helperBudget * 0.58).toInt().coerceAtLeast(300))
                }

                val candidatePlan = IrMethodSplitter.planFunction(
                    fn = fn,
                    instanceInternalName = instanceInternalName,
                    targetMethodBytes = currentTarget,
                    maxHelperBodyBytes = helperBudget,
                    maxChildBodyBytes = childBudget,
                    funcTypes = funcTypes,
                )
                val (cRoot, cHelper) = measureEmittedBytes(ir, candidatePlan, pkgPrefix, baseSimpleName, instanceInternalName, sharedGlobals, rootMethodName)
                val cMax = maxOf(cRoot, cHelper)
                if (cMax < bestMax) {
                    bestPlan = candidatePlan
                    bestMax = cMax
                }
                rootBytes = cRoot
                maxHelperBytes = cHelper
                if (bestMax <= options.targetMethodBytes) return bestPlan
            }
        }
        return bestPlan
    }

    private fun measureEmittedBytes(
        ir: IrModule,
        plan: FunctionSplitPlan,
        pkgPrefix: String,
        baseSimpleName: String,
        instanceInternalName: String,
        sharedGlobals: BooleanArray,
        rootMethodName: String,
    ): Pair<Int, Int> {
        val probeGroupName = "${pkgPrefix}${baseSimpleName}Probe_${plan.fn.index}"
        val probeCtx = object : ModuleEmitContext {
            override val ir: IrModule = ir
            override val instanceInternalName: String = instanceInternalName
            override val hasElemSegments: Boolean = true
            override val hasDataSegments: Boolean = true
            override val hasFuncRefs: Boolean = true
            override val emitLineNumbers: Boolean = false
            override fun funcTarget(funcIndex: Int): CompiledFunctionTarget {
                val ft = ir.functionTypeOrNull(funcIndex)!!
                val direct = AotNames.countDirectParams(ft.params)
                val mv = if (ft.results.size >= 2) AotNames.multiValueInternalName(instanceInternalName, ft.results) else null
                return CompiledFunctionTarget(
                    funcIndex = funcIndex,
                    ownerInternalName = probeGroupName,
                    methodName = AotNames.funcMethodName(funcIndex),
                    methodDescriptor = AotNames.staticFuncDescriptor(instanceInternalName, ft, direct),
                    funcType = ft,
                    overflowParamCount = ft.params.size - direct,
                    multiValueClassInternalName = mv,
                )
            }
            override fun callIndirectDispatcher(funcType: IrFuncType): DispatchCallSite {
                val direct = AotNames.countDirectParams(funcType.params)
                val desc = buildString {
                    append('(')
                    for (i in 0 until direct) append(AotNames.jvmDesc(funcType.params[i]))
                    append('I').append(AotNames.WASM_TABLE_DESC).append('L').append(instanceInternalName).append(';').append(')')
                    append(AotNames.returnTypeDesc(instanceInternalName, funcType.results))
                }
                return DispatchCallSite("${pkgPrefix}${baseSimpleName}Dispatch_0", "callIndirect_0", desc)
            }
            override fun isSharedGlobal(globalIndex: Int): Boolean = sharedGlobals.getOrElse(globalIndex) { false }
            override fun multiValueReturnClass(results: List<IrValType>): String =
                AotNames.multiValueInternalName(instanceInternalName, results)
        }
        val cn = ClassNode().apply {
            version = Opcodes.V17
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER
            name = probeGroupName
            superName = "java/lang/Object"
        }
        cn.methods.addAll(WasmBytecodeEmitter.emitFunctionMethods(plan, probeCtx))
        val prefixSlash = if (pkgPrefix.isEmpty()) baseSimpleName else pkgPrefix + baseSimpleName
        val cw = AotModuleClassEmitter.AotClassWriter(prefixSlash)
        cn.accept(cw)
        val lengths = AotModuleClassEmitter.readMethodCodeLengths(cw.toByteArray())
        var rootBytes = 0
        var maxHelperBytes = 0
        for ((key, len) in lengths) {
            if (key.name == rootMethodName) {
                rootBytes = maxOf(rootBytes, len)
            } else {
                maxHelperBytes = maxOf(maxHelperBytes, len)
            }
        }
        return rootBytes to maxHelperBytes
    }

    private fun readClassMajorVersion(classBytes: ByteArray): Int {
        check(classBytes.size >= 8) { "Classfile too small: ${classBytes.size} bytes" }
        return ((classBytes[6].toInt() and 0xFF) shl 8) or (classBytes[7].toInt() and 0xFF)
    }

    private fun assertNoDynamicCpTags(internalName: String, classBytes: ByteArray) {
        val buf = ByteBuffer.wrap(classBytes).order(ByteOrder.BIG_ENDIAN)
        buf.position(8)
        val cpCount = buf.short.toInt() and 0xFFFF
        var i = 1
        while (i < cpCount) {
            val tag = buf.get().toInt() and 0xFF
            when (tag) {
                1 -> {
                    val len = buf.short.toInt() and 0xFFFF
                    buf.position(buf.position() + len)
                }
                3, 4, 9, 10, 11, 12 -> buf.position(buf.position() + 4)
                5, 6 -> {
                    buf.position(buf.position() + 8)
                    i++
                }
                7, 8, 19, 20 -> buf.position(buf.position() + 2)
                15 -> error("Forbidden CONSTANT_MethodHandle constant pool entry in $internalName")
                16 -> error("Forbidden CONSTANT_MethodType constant pool entry in $internalName")
                17 -> error("Forbidden CONSTANT_Dynamic (condy) constant pool entry in $internalName")
                18 -> error("Forbidden CONSTANT_InvokeDynamic constant pool entry in $internalName")
                else -> error("Unknown constant pool tag $tag in $internalName")
            }
            i++
        }
    }

    private class InMemoryModuleClassLoader(
        private val classes: Map<String, ByteArray>,
        parent: ClassLoader,
    ) : ClassLoader(parent) {
        override fun findClass(name: String): Class<*> {
            val internal = name.replace('.', '/')
            val bytes = classes[internal] ?: throw ClassNotFoundException(name)
            return defineClass(name, bytes, 0, bytes.size)
        }
    }
}
