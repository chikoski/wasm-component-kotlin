@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.tooling.aot

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.BYTE
import com.squareup.kotlinpoet.BYTE_ARRAY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.FLOAT
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.SHORT
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.U_BYTE
import com.squareup.kotlinpoet.U_INT
import com.squareup.kotlinpoet.U_LONG
import com.squareup.kotlinpoet.U_SHORT
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.asClassName
import wasm2kt.ast.WitFunctionKind
import wasm2kt.ast.WitTypeRef
import wasm2kt.generator.AotExportBinding
import wasm2kt.generator.AotImportBinding
import wasm2kt.generator.AotTypeInfo
import wasm2kt.generator.AotWorldModel
import wasm2kt.runtime.aot.AbiDirection
import wasm2kt.runtime.aot.AotCanonOptions
import wasm2kt.runtime.aot.AotComponentFactory
import wasm2kt.runtime.aot.AotComponentRuntime
import wasm2kt.runtime.aot.AotMappedImportBridge
import wasm2kt.runtime.aot.AotPostReturn
import wasm2kt.runtime.aot.AotRealloc
import wasm2kt.runtime.aot.AotResourceDtor
import wasm2kt.runtime.aot.I32Global
import wasm2kt.runtime.aot.LinearMemory
import wasm2kt.runtime.canonical.CanonicalAbi
import wasm2kt.runtime.canonical.DirectCanonicalAbi
import wasm2kt.runtime.canonical.FuncType
import wasm2kt.runtime.canonical.InternalWasm2KtApi
import wasm2kt.runtime.canonical.StringEncoding
import wasm2kt.runtime.canonical.Transcoder
import wasm2kt.runtime.canonical.WitType
import wasm2kt.tooling.ComponentCodegen
import wasm2kt.tooling.ir.IrValType

/**
 * Generates Phase 2 static Kotlin component wiring (`<Name>Component : AotComponentFactory<AotImports, Session>`)
 * that directly connects compiled Core Wasm functions and `:runtime-canonical` [DirectCanonicalAbi]
 * helpers to the generated World DSL `Session` and local host imports (ADR-0023 Phase 2).
 *
 * Components without mapped ADR-0014 library imports emit ZERO references to `WitWriter`,
 * `WitReader`, `Lowerer`, `Lifter`, `ComponentBackend`, `Linker`, `WitType`, `FuncType`, or `CanonicalAbi`.
 */
internal class DirectAotComponentEmitter(
    private val componentName: String,
    private val packageName: String,
    private val compiled: CompiledAotComponent,
    private val model: AotWorldModel,
) {
    private val plan: BuildPlan = compiled.plan
    private val objectName: String = ComponentCodegen.componentObjectName(componentName)
    private val codePointClass: ClassName = ClassName(packageName, "CodePoint")

    private val postReturnDefs: Map<Int, BuildCoreDef> = plan.initializers
        .filterIsInstance<BuildInitializer.ExtractPostReturn>()
        .associate { it.index to it.def }

    private val mappedBridgeFuncTypeIds: Map<Int, Int>
    private val mappedFuncTypePool: FuncTypePool?

    init {
        if (model.hasMappedImports) {
            val pool = FuncTypePool()
            val ids = LinkedHashMap<Int, Int>()
            for ((idx, tr) in plan.trampolines) {
                if (tr is BuildTrampoline.LowerImport) {
                    val key = plan.imports.getValue(tr.import).dispatchKey
                    if (model.imports[key]?.isMapped == true) {
                        ids[idx] = pool.intern(tr.type)
                    }
                }
            }
            mappedBridgeFuncTypeIds = ids
            mappedFuncTypePool = pool
        } else {
            mappedBridgeFuncTypeIds = emptyMap()
            mappedFuncTypePool = null
        }
    }

    private val planHelper: AotPlanEmissionHelper = AotPlanEmissionHelper(plan, compiled) {
            out, trampolineIndex, tr, paramExprs, paramTypes, resultTypes, rawArgsVar, returnStyle ->
        emitLowerImportCall(out, trampolineIndex, tr, paramExprs, paramTypes, resultTypes, rawArgsVar, returnStyle)
    }

    private var varCounter = 0
    private fun fresh(prefix: String): String = "__${prefix}_${varCounter++}"

    fun buildFile(): FileSpec {
        val factorySuper = AotComponentFactory::class.asClassName()
            .parameterizedBy(model.aotImportsClassName, model.sessionClassName)
        val compObj = TypeSpec.objectBuilder(objectName)
            .addKdoc(
                "The `%L` WebAssembly component, statically compiled and directly wired to [%T] (ADR-0023 Phase 2).\n",
                componentName,
                model.sessionClassName,
            )
            .addSuperinterface(factorySuper)

        mappedFuncTypePool?.emitInto(compObj)
        planHelper.emitResourceTablesProperty(compObj)
        planHelper.emitTrapMessageArrays(compObj)
        if (model.hasMappedImports) {
            emitMappedDropKeysProperty(compObj)
        }
        compObj.addFunction(
            FunSpec.builder("instantiate")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("imports", model.aotImportsClassName)
                .returns(model.sessionClassName)
                .addStatement("return Instance(imports)")
                .build(),
        )
        compObj.addType(buildInstanceClass())

        return FileSpec.builder(packageName, objectName)
            .addAnnotation(
                AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
                    .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                    .addMember("%T::class", InternalWasm2KtApi::class)
                    .build(),
            )
            .addAnnotation(
                AnnotationSpec.builder(Suppress::class)
                    .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                    .addMember(
                        "%S, %S, %S, %S, %S, %S, %S, %S, %S, %S, %S, %S",
                        "UNUSED_PARAMETER",
                        "UNUSED_VARIABLE",
                        "UNCHECKED_CAST",
                        "LocalVariableName",
                        "PropertyName",
                        "FunctionName",
                        "ClassName",
                        "RedundantVisibilityModifier",
                        "USELESS_CAST",
                        "ObjectPropertyName",
                        "LargeClass",
                        "LongMethod",
                    )
                    .build(),
            )
            .addType(compObj.build())
            .build()
    }

    private fun emitMappedDropKeysProperty(type: TypeSpec.Builder) {
        val setType = ClassName("kotlin.collections", "Set").parameterizedBy(STRING)
        val init = if (model.mappedResourceDropKeys.isEmpty()) {
            CodeBlock.of("emptySet()")
        } else {
            val parts = model.mappedResourceDropKeys.sorted().map { CodeBlock.of("%S", it) }
            CodeBlock.builder().add("setOf(").apply {
                parts.forEachIndexed { i, p ->
                    if (i > 0) add(", ")
                    add(p)
                }
            }.add(")").build()
        }
        type.addProperty(
            PropertySpec.builder("MAPPED_DROP_KEYS", setType, KModifier.PRIVATE)
                .initializer(init)
                .build(),
        )
    }

    private fun buildInstanceClass(): TypeSpec {
        val inst = TypeSpec.classBuilder("Instance")
            .addModifiers(KModifier.PRIVATE)
            .superclass(model.sessionClassName)
            .primaryConstructor(FunSpec.constructorBuilder().addParameter("imports", model.aotImportsClassName).build())

        inst.addProperty(
            PropertySpec.builder("rt", AotComponentRuntime::class, KModifier.PRIVATE)
                .initializer("%T(RESOURCE_TABLES, hasHostResources = %L)", AotComponentRuntime::class, model.hasHostResources)
                .build(),
        )

        for (provider in model.importProviders) {
            inst.addProperty(
                PropertySpec.builder("import_${provider.propName}", provider.contractClass, KModifier.PRIVATE)
                    .initializer("imports.%N()", provider.propName)
                    .build(),
            )
        }

        if (model.hasMappedImports) {
            inst.addProperty(
                PropertySpec.builder("mappedBridge", AotMappedImportBridge::class, KModifier.PRIVATE)
                    .initializer("%T(rt, imports.newMappedDispatcher(), MAPPED_DROP_KEYS)", AotMappedImportBridge::class)
                    .build(),
            )
            for (optIdx in plan.options.keys.sorted()) {
                val opt = plan.options.getValue(optIdx)
                inst.addProperty(
                    PropertySpec.builder("opt_$optIdx", AotCanonOptions::class, KModifier.PRIVATE)
                        .initializer("%T(%T.%L, %L)", AotCanonOptions::class, StringEncoding::class, opt.stringEncoding.name, opt.instance)
                        .build(),
                )
            }
        }

        // InstanceFlags & TaskMayBlock shared mutable cells.
        val flagsIndices = sortedSetOf<Int>()
        var needsTaskMayBlock = false
        for (init in plan.initializers) {
            if (init !is BuildInitializer.Instantiate) continue
            for (arg in init.args) {
                when (val d = arg.def) {
                    is BuildCoreDef.InstanceFlags -> flagsIndices.add(d.index)
                    is BuildCoreDef.TaskMayBlock -> needsTaskMayBlock = true
                    else -> {}
                }
            }
        }
        for (fIdx in flagsIndices) {
            inst.addProperty(
                PropertySpec.builder("flags_$fIdx", I32Global::class, KModifier.PRIVATE)
                    .initializer("%T(3)", I32Global::class)
                    .build(),
            )
        }
        if (needsTaskMayBlock) {
            inst.addProperty(
                PropertySpec.builder("taskMayBlock", I32Global::class, KModifier.PRIVATE)
                    .initializer("%T(0)", I32Global::class)
                    .build(),
            )
        }

        for (em in plan.initializers.filterIsInstance<BuildInitializer.ExtractMemory>().distinctBy { it.index }) {
            inst.addProperty(PropertySpec.builder("mem_${em.index}", LinearMemory::class, KModifier.PRIVATE).mutable(true).addModifiers(KModifier.LATEINIT).build())
        }
        for (er in plan.initializers.filterIsInstance<BuildInitializer.ExtractRealloc>().distinctBy { it.index }) {
            inst.addProperty(PropertySpec.builder("realloc_${er.index}", AotRealloc::class, KModifier.PRIVATE).mutable(true).addModifiers(KModifier.LATEINIT).build())
        }
        if (model.hasMappedImports) {
            for (ep in plan.initializers.filterIsInstance<BuildInitializer.ExtractPostReturn>().distinctBy { it.index }) {
                inst.addProperty(PropertySpec.builder("postReturn_${ep.index}", AotPostReturn::class, KModifier.PRIVATE).mutable(true).addModifiers(KModifier.LATEINIT).build())
            }
        }
        for ((tIdx, tr) in plan.trampolines.entries.sortedBy { it.key }) {
            if (tr is BuildTrampoline.Transcode) {
                inst.addProperty(
                    PropertySpec.builder("transcoder_$tIdx", Transcoder::class.asClassName().copy(nullable = true), KModifier.PRIVATE)
                        .mutable(true)
                        .initializer("null")
                        .build(),
                )
            }
        }
        for (ii in plan.initializers.filterIsInstance<BuildInitializer.Instantiate>().sortedBy { it.instance }) {
            val instClass = planHelper.classNameFromInternal(compiled.modules.getValue(ii.module).instanceInternalName)
            inst.addProperty(PropertySpec.builder("inst_${ii.instance}", instClass, KModifier.PRIVATE).mutable(true).addModifiers(KModifier.LATEINIT).build())
        }

        // Initializer block
        val initCode = CodeBlock.builder()
        if (model.hasMappedImports) {
            initCode.addStatement("rt.setMappedDropHandler(mappedBridge)")
        }
        initCode.beginControlFlow("rt.initComponent")
        for (item in plan.initializers) {
            emitInitializer(initCode, item)
        }
        initCode.endControlFlow()
        inst.addInitializerBlock(initCode.build())

        // Direct unmapped host import bridge functions (one per LowerImport trampoline for unmapped imports).
        for ((tIdx, tr) in plan.trampolines.entries.sortedBy { it.key }) {
            if (tr !is BuildTrampoline.LowerImport) continue
            val dispatchKey = plan.imports.getValue(tr.import).dispatchKey
            val importBinding = model.imports[dispatchKey]
            if (importBinding != null && !importBinding.isMapped) {
                inst.addFunction(buildDirectImportBridgeFun(tIdx, tr, importBinding))
            }
        }

        // Export implementations overriding Session's internal abstract __export_* methods.
        for (exp in plan.exports) {
            val key = exp.instance?.let { "$it#${exp.name}" } ?: exp.name
            val binding = model.exports[key]
                ?: error("Plan export '$key' not found in AotWorldModel.exports=${model.exports.keys}")
            inst.addFunction(buildDirectExportFun(exp, binding))
        }

        if (model.hasGuestResources) {
            val dropBuilder = FunSpec.builder("__dropResource")
                .addModifiers(KModifier.OVERRIDE)
                .addAnnotation(ClassName("kotlin.jvm", "Synchronized"))
                .addParameter("handle", INT)
            if (model.hasHostResources) {
                dropBuilder.addStatement("rt.withHostResources { rt.dropResource(handle) }")
            } else {
                dropBuilder.addStatement("rt.dropResource(handle)")
            }
            inst.addFunction(dropBuilder.build())
        }

        inst.addFunction(
            FunSpec.builder("close")
                .addModifiers(KModifier.OVERRIDE)
                .addAnnotation(ClassName("kotlin.jvm", "Synchronized"))
                .addStatement("rt.close()")
                .build(),
        )

        return inst.build()
    }

    private fun emitInitializer(body: CodeBlock.Builder, init: BuildInitializer) {
        when (init) {
            is BuildInitializer.Instantiate -> planHelper.emitInstantiateCore(body, init)
            is BuildInitializer.ExtractMemory -> {
                val exp = planHelper.memExport(init.export)
                body.addStatement("mem_%L = inst_%L.%L", init.index, init.export.instance, exp.fieldName)
                if (model.hasMappedImports) {
                    for ((optIdx, opt) in plan.options.entries.sortedBy { it.key }) {
                        if (opt.memory == init.index) {
                            body.addStatement("opt_%L.memory = mem_%L", optIdx, init.index)
                        }
                    }
                }
            }
            is BuildInitializer.ExtractRealloc -> {
                body.beginControlFlow("realloc_%L = %T { oldPtr, oldSize, align, newSize ->", init.index, AotRealloc::class)
                val params = listOf(CodeBlock.of("oldPtr"), CodeBlock.of("oldSize"), CodeBlock.of("align"), CodeBlock.of("newSize"))
                planHelper.emitDefInvocation(
                    out = body,
                    def = init.def,
                    paramExprs = params,
                    paramTypes = AotPlanEmissionHelper.REALLOC_IR_TYPE.params,
                    resultTypes = AotPlanEmissionHelper.REALLOC_IR_TYPE.results,
                    rawArgsVar = null,
                    returnStyle = AotPlanEmissionHelper.ReturnStyle.SCALAR_OR_UNIT,
                )
                body.endControlFlow()
                if (model.hasMappedImports) {
                    for ((optIdx, opt) in plan.options.entries.sortedBy { it.key }) {
                        if (opt.realloc == init.index) {
                            body.addStatement("opt_%L.realloc = realloc_%L", optIdx, init.index)
                        }
                    }
                }
            }
            is BuildInitializer.ExtractPostReturn -> {
                if (model.hasMappedImports) {
                    val postReturnParamTypes = planHelper.defCoreParamTypes(init.def)
                    val paramName = if (postReturnParamTypes.isEmpty()) "_" else "r"
                    body.beginControlFlow("postReturn_%L = %T { %L ->", init.index, AotPostReturn::class, paramName)
                    val params = planHelper.unpackLongArrayArgs("r", postReturnParamTypes)
                    planHelper.emitDefInvocation(
                        out = body,
                        def = init.def,
                        paramExprs = params,
                        paramTypes = postReturnParamTypes,
                        resultTypes = emptyList(),
                        rawArgsVar = if (init.def is BuildCoreDef.Trampoline && postReturnParamTypes.isNotEmpty()) "r" else null,
                        returnStyle = AotPlanEmissionHelper.ReturnStyle.UNIT_STMT,
                    )
                    body.endControlFlow()
                    for ((optIdx, opt) in plan.options.entries.sortedBy { it.key }) {
                        if (opt.postReturn == init.index) {
                            body.addStatement("opt_%L.postReturn = postReturn_%L", optIdx, init.index)
                        }
                    }
                }
            }
            is BuildInitializer.ExtractTable -> {
                planHelper.tabExport(init.export)
            }
            is BuildInitializer.Resource -> {
                val dtor = init.dtor
                if (dtor != null) {
                    body.beginControlFlow("rt.registerResourceDtor(%L, %T { rep ->", init.resource, AotResourceDtor::class)
                    planHelper.emitDefInvocation(
                        out = body,
                        def = dtor,
                        paramExprs = listOf(CodeBlock.of("rep")),
                        paramTypes = AotPlanEmissionHelper.DTOR_IR_TYPE.params,
                        resultTypes = emptyList(),
                        rawArgsVar = null,
                        returnStyle = AotPlanEmissionHelper.ReturnStyle.UNIT_STMT,
                    )
                    body.unindent().addStatement("})")
                }
            }
            is BuildInitializer.ExtractCallback -> {
                throw IllegalStateException("Async callback initializer is not supported by the AOT backend")
            }
        }
    }

    private fun emitLowerImportCall(
        out: CodeBlock.Builder,
        trampolineIndex: Int,
        tr: BuildTrampoline.LowerImport,
        paramExprs: List<CodeBlock>,
        paramTypes: List<IrValType>,
        resultTypes: List<IrValType>,
        rawArgsVar: String?,
        returnStyle: AotPlanEmissionHelper.ReturnStyle,
    ) {
        val dispatchKey = plan.imports.getValue(tr.import).dispatchKey
        val importBinding = model.imports[dispatchKey]
        if (importBinding != null && !importBinding.isMapped) {
            val call = CodeBlock.builder().add("__import_%L(", trampolineIndex)
            paramExprs.forEachIndexed { idx, expr ->
                if (idx > 0) call.add(", ")
                call.add(expr)
            }
            call.add(")")
            val callCode = call.build()
            when (returnStyle.kind) {
                AotPlanEmissionHelper.ReturnKind.DIRECT -> {
                    if (resultTypes.isEmpty()) out.add(callCode).add("\n")
                    else out.add("return ").add(callCode).add("\n")
                }
                AotPlanEmissionHelper.ReturnKind.SCALAR_OR_UNIT,
                AotPlanEmissionHelper.ReturnKind.UNIT_STMT,
                -> out.add(callCode).add("\n")
                AotPlanEmissionHelper.ReturnKind.LONG_ARRAY -> {
                    when (resultTypes.size) {
                        0 -> {
                            out.add(callCode).add("\n")
                            out.addStatement("null")
                        }
                        1 -> {
                            out.add("longArrayOf(").add(planHelper.packToLong(callCode, resultTypes[0])).add(")\n")
                        }
                        else -> error("Multi-value host import trampoline return unexpected: $resultTypes")
                    }
                }
            }
            return
        }

        // Mapped ADR-0014 library import -> delegate through AotMappedImportBridge.
        val ftId = mappedBridgeFuncTypeIds.getValue(trampolineIndex)
        val argsExpr = planHelper.coreArgsLongArrayExpr(paramExprs, paramTypes, rawArgsVar)
        val callCode = CodeBlock.builder()
            .add("mappedBridge.callMappedHost(%S, FUNC_TYPES[%L], opt_%L, ", dispatchKey, ftId, tr.options)
            .add(argsExpr)
            .add(")")
            .build()
        planHelper.emitLongArrayToReturn(out, callCode, resultTypes, returnStyle)
    }

    // ------------------------------------------------------------------ direct export function

    private fun buildDirectExportFun(exp: BuildPlanExport, binding: AotExportBinding): FunSpec {
        val fn = binding.witFunction
        val builder = FunSpec.builder(binding.methodName)
            .addModifiers(KModifier.OVERRIDE)
            .addAnnotation(ClassName("kotlin.jvm", "Synchronized"))
            .returns(binding.returnKotlinType)

        val usedNames = mutableSetOf<String>()
        val paramVars = mutableListOf<String>()
        fn.params.forEachIndexed { idx, p ->
            var pName = if (fn.kind is WitFunctionKind.Method && idx == 0) "self" else wasm2kt.generator.NamingConventions.toCamelCase(p.name)
            while (!usedNames.add(pName)) pName = "${pName}_$idx"
            builder.addParameter(pName, binding.params[idx].kotlinType)
            paramVars += pName
        }

        val body = CodeBlock.builder()
        val unsupported = findUnsupportedDetail(exp.type)
        if (unsupported != null) {
            body.addStatement("throw %T(%S)", UnsupportedOperationException::class, "WIT type is not supported by the AOT backend yet: $unsupported")
            return builder.addCode(body.build()).build()
        }

        val wrapHostRes = model.hasHostResources
        if (wrapHostRes) {
            body.beginControlFlow("return rt.withHostResources")
        }

        val touchesRes = hasResources(exp.type.paramTypes) || hasResources(exp.type.results)

        body.addStatement("val syncDepth = rt.enterDirect()")
        val cxVar = if (touchesRes) "cx" else null
        if (cxVar != null) {
            body.addStatement("val %L = rt.newResourceContext(%T.HOST_TO_GUEST)", cxVar, AbiDirection::class)
        }

        body.beginControlFlow("try")

        val opt = plan.options.getValue(exp.options)
        // Collect guest own classes needed BEFORE emitting statements so moved_N declarations sit at top of try.
        val guestOwnClasses = LinkedHashSet<ClassName>()
        exp.type.paramTypes.forEachIndexed { i, wt ->
            collectGuestOwnClasses(wt, binding.params[i].type, exportCtx = true, guestOwnClasses)
        }
        val movedDecls = LinkedHashMap<ClassName, String>()
        guestOwnClasses.forEachIndexed { i, cls ->
            val v = "moved_$i"
            movedDecls[cls] = v
            body.addStatement("val %L = ArrayList<%T>(2)", v, cls)
        }
        val lowerCtx = AbiEmitContext(
            memExpr = opt.memory?.let { "mem_$it" },
            reallocExpr = opt.realloc?.let { "realloc_$it" },
            encoding = opt.stringEncoding,
            exportCtx = true,
            cxVar = cxVar,
            movedGuestCollector = { resCls, expr ->
                val v = movedDecls.getValue(resCls)
                body.addStatement("%L.add(%L)", v, expr)
            },
        )
        val liftCtx = lowerCtx.copy(movedGuestCollector = { _, _ -> })

        val coreParamTypes = planHelper.defCoreParamTypes(exp.func)
        val coreArgExprs: List<CodeBlock>
        if (touchesRes) {
            val hoisted = coreParamTypes.mapIndexed { idx, vt ->
                val cv = fresh("ca$idx")
                body.addStatement("val %L: %T", cv, planHelper.kotlinTypeForVal(vt))
                cv
            }
            body.beginControlFlow("try")
            val lowered = emitLowerExportParams(body, exp, binding, paramVars, lowerCtx)
            lowered.forEachIndexed { idx, expr ->
                body.add("%L = ", hoisted[idx]).add(expr).add("\n")
            }
            body.nextControlFlow("catch (__t: Throwable)")
            body.addStatement("%L.rollbackLowering()", cxVar)
            body.addStatement("throw __t")
            body.endControlFlow()
            body.addStatement("%L.commitMoves()", cxVar)
            coreArgExprs = hoisted.map { CodeBlock.of("%L", it) }
        } else {
            coreArgExprs = emitLowerExportParams(body, exp, binding, paramVars, lowerCtx)
        }

        val coreResultTypes = planHelper.defCoreResultTypes(exp.func)
        val coreRetVar = if (coreResultTypes.isEmpty()) null else fresh("coreRet")
        if (coreRetVar != null) {
            body.add("val %L = ", coreRetVar)
        }
        body.beginControlFlow("rt.guardGuest")
        planHelper.emitDefInvocation(
            out = body,
            def = exp.func,
            paramExprs = coreArgExprs,
            paramTypes = coreParamTypes,
            resultTypes = coreResultTypes,
            rawArgsVar = null,
            returnStyle = if (coreRetVar == null) AotPlanEmissionHelper.ReturnStyle.UNIT_STMT else AotPlanEmissionHelper.ReturnStyle.SCALAR_OR_UNIT,
        )
        body.endControlFlow()

        if (cxVar != null) {
            body.addStatement("%L.checkBorrowsDropped()", cxVar)
        }
        for (listVar in movedDecls.values) {
            body.beginControlFlow("for (__m in %L)", listVar)
            body.addStatement("__m.markMoved()")
            body.endControlFlow()
        }

        val resultVar = if (exp.type.results.isNotEmpty()) {
            val rv = fresh("result")
            body.beginControlFlow("val %L = rt.guardGuest", rv)
            val liftedExpr = emitLiftExportResults(body, exp, binding, coreRetVar, liftCtx)
            body.add("  ").add(liftedExpr).add("\n")
            body.endControlFlow()
            rv
        } else {
            null
        }

        opt.postReturn?.let { prIdx ->
            val prDef = postReturnDefs.getValue(prIdx)
            val prParamTypes = planHelper.defCoreParamTypes(prDef)
            val prArgs = when {
                prParamTypes.isEmpty() -> emptyList()
                coreRetVar != null -> listOf(CodeBlock.of("%L", coreRetVar))
                else -> error("post_return expects args but export had no core return")
            }
            body.beginControlFlow("rt.runPostReturn")
            planHelper.emitDefInvocation(
                out = body,
                def = prDef,
                paramExprs = prArgs,
                paramTypes = prParamTypes,
                resultTypes = emptyList(),
                rawArgsVar = null,
                returnStyle = AotPlanEmissionHelper.ReturnStyle.UNIT_STMT,
            )
            body.endControlFlow()
        }

        if (wrapHostRes) {
            if (resultVar != null) body.addStatement("%L", resultVar) else body.addStatement("Unit")
        } else {
            if (resultVar != null) body.addStatement("return %L", resultVar) else body.addStatement("return")
        }

        body.nextControlFlow("finally")
        if (cxVar != null) {
            body.addStatement("%L.releaseLends()", cxVar)
        }
        body.addStatement("rt.exitDirect(syncDepth)")
        body.endControlFlow()

        if (wrapHostRes) {
            body.endControlFlow()
        }
        return builder.addCode(body.build()).build()
    }

    private fun emitLowerExportParams(
        out: CodeBlock.Builder,
        exp: BuildPlanExport,
        binding: AotExportBinding,
        paramVars: List<String>,
        ctx: AbiEmitContext,
    ): List<CodeBlock> {
        val paramTypes = exp.type.paramTypes
        if (exp.type.flatParamCount > CanonicalAbi.MAX_FLAT_PARAMS) {
            val tuple = exp.type.paramTuple
            val mem = ctx.requireMemory()
            val realloc = ctx.requireRealloc()
            val ptrVar = fresh("paramPtr")
            out.addStatement("val %L = rt.alloc(%L, %L, %L, %L)", ptrVar, mem, realloc, tuple.align, tuple.size)
            var off = 0
            for (i in paramTypes.indices) {
                val pt = paramTypes[i]
                off = CanonicalAbi.alignTo(off, pt.align)
                emitStore(out, pt, binding.params[i].type, CodeBlock.of("%L", paramVars[i]), ptrVar, off, ctx)
                off += pt.size
            }
            return listOf(CodeBlock.of("%L", ptrVar))
        }

        val slots = ArrayList<CodeBlock>()
        for (i in paramTypes.indices) {
            slots += emitLowerFlat(out, paramTypes[i], binding.params[i].type, CodeBlock.of("%L", paramVars[i]), ctx)
        }
        return slots
    }

    private fun emitLiftExportResults(
        out: CodeBlock.Builder,
        exp: BuildPlanExport,
        binding: AotExportBinding,
        coreRetVar: String?,
        ctx: AbiEmitContext,
    ): CodeBlock {
        val results = exp.type.results
        if (exp.type.flatResultCount > CanonicalAbi.MAX_FLAT_RESULTS) {
            val ptrVar = checkNotNull(coreRetVar)
            val mem = ctx.requireMemory()
            val tuple = exp.type.resultTuple
            out.addStatement(
                "%T.checkMemoryRange(%L, %L, %LL, %L, %S)",
                DirectCanonicalAbi::class,
                mem,
                ptrVar,
                tuple.size,
                tuple.align,
                "tuple pointer",
            )
            return if (binding.resultTypes.size == 1) {
                emitLoad(out, results[0], binding.resultTypes[0], ptrVar, 0, ctx)
            } else {
                var off = 0
                val vals = results.indices.map { idx ->
                    val rt = results[idx]
                    off = CanonicalAbi.alignTo(off, rt.align)
                    val expr = emitLoad(out, rt, binding.resultTypes[idx], ptrVar, off, ctx)
                    val lv = fresh("resField")
                    out.add("val %L = ", lv).add(expr).add("\n")
                    off += rt.size
                    CodeBlock.of("%L", lv)
                }
                val tupleClass = ClassName(packageName, "Tuple${binding.resultTypes.size}")
                val cb = CodeBlock.builder().add("%T(", tupleClass)
                vals.forEachIndexed { idx, v ->
                    if (idx > 0) cb.add(", ")
                    cb.add(v)
                }
                cb.add(")").build()
            }
        }
        // flatResultCount <= 1
        val flatSlots = if (exp.type.flatResultCount == 0) emptyList() else listOf(CodeBlock.of("%L", checkNotNull(coreRetVar)))
        return emitLiftFlat(out, results[0], binding.resultTypes[0], flatSlots, ctx)
    }

    // ------------------------------------------------------------------ direct local host import bridge

    private fun buildDirectImportBridgeFun(
        trampolineIndex: Int,
        tr: BuildTrampoline.LowerImport,
        binding: AotImportBinding,
    ): FunSpec {
        val paramTypes = planHelper.trampolineParamTypes(trampolineIndex)
        // LowerImport always returns at most 1 flat scalar (or Unit when flatResultCount == 0 or > 1).
        val actualCoreReturnType = if (tr.type.results.isNotEmpty() && tr.type.flatResultCount == 1) {
            val irType = planHelper.coreTypeToIrValType(tr.type.results[0].flat[0])
            planHelper.kotlinTypeForVal(irType)
        } else {
            UNIT
        }

        val builder = FunSpec.builder("__import_$trampolineIndex")
            .addModifiers(KModifier.PRIVATE)
            .returns(actualCoreReturnType)

        paramTypes.forEachIndexed { idx, vt ->
            builder.addParameter("p$idx", planHelper.kotlinTypeForVal(vt))
        }

        val body = CodeBlock.builder()
        val unsupported = findUnsupportedDetail(tr.type)
        if (unsupported != null) {
            body.addStatement("throw %T(%S)", UnsupportedOperationException::class, "WIT type is not supported by the AOT backend yet: $unsupported")
            return builder.addCode(body.build()).build()
        }

        body.addStatement("rt.checkMayLeave(%S)", "host import '${binding.key}'")
        val touchesRes = hasResources(tr.type.paramTypes) || hasResources(tr.type.results)
        val cxVar = if (touchesRes) "cx" else null
        if (cxVar != null) {
            body.addStatement("val %L = rt.newResourceContext(%T.GUEST_TO_HOST)", cxVar, AbiDirection::class)
            body.beginControlFlow("try")
        }

        val opt = plan.options.getValue(tr.options)
        val ctx = AbiEmitContext(
            memExpr = opt.memory?.let { "mem_$it" },
            reallocExpr = opt.realloc?.let { "realloc_$it" },
            encoding = opt.stringEncoding,
            exportCtx = false,
            cxVar = cxVar,
            movedGuestCollector = { _, _ -> throw UnsupportedOperationException("Guest resources cannot be moved to host imports yet") },
        )

        // 1. Lift guest arguments
        val argVars = ArrayList<String>(binding.params.size)
        if (tr.type.flatParamCount > CanonicalAbi.MAX_FLAT_PARAMS) {
            val ptrVar = "p0"
            val tuple = tr.type.paramTuple
            val mem = ctx.requireMemory()
            body.addStatement(
                "%T.checkMemoryRange(%L, %L, %LL, %L, %S)",
                DirectCanonicalAbi::class,
                mem,
                ptrVar,
                tuple.size,
                tuple.align,
                "tuple pointer",
            )
            var off = 0
            for (i in tr.type.paramTypes.indices) {
                val pt = tr.type.paramTypes[i]
                off = CanonicalAbi.alignTo(off, pt.align)
                val av = fresh("arg")
                val expr = emitLoad(body, pt, binding.params[i].type, ptrVar, off, ctx)
                body.add("val %L = ", av).add(expr).add("\n")
                argVars += av
                off += pt.size
            }
        } else {
            var slotPos = 0
            for (i in tr.type.paramTypes.indices) {
                val pt = tr.type.paramTypes[i]
                val count = pt.flat.size
                val slots = (0 until count).map { CodeBlock.of("p%L", slotPos + it) }
                slotPos += count
                val av = fresh("arg")
                val expr = emitLiftFlat(body, pt, binding.params[i].type, slots, ctx)
                body.add("val %L = ", av).add(expr).add("\n")
                argVars += av
            }
        }

        // 2. Call host Kotlin implementation AND lower return value inside rt.guardHostImport { ... }
        val hasCoreReturn = actualCoreReturnType != UNIT
        if (hasCoreReturn) {
            body.add("return rt.guardHostImport {\n").indent()
        } else {
            body.beginControlFlow("rt.guardHostImport")
        }

        val callExpr = when (binding.witFunction.kind) {
            is WitFunctionKind.Method -> {
                val callArgs = CodeBlock.builder()
                argVars.drop(1).forEachIndexed { idx, av ->
                    if (idx > 0) callArgs.add(", ")
                    callArgs.add("%L", av)
                }
                CodeBlock.of("%L.%N(%L)", argVars[0], binding.kotlinMethodName, callArgs.build())
            }
            else -> {
                val propName = checkNotNull(binding.providerPropName)
                val callArgs = CodeBlock.builder()
                argVars.forEachIndexed { idx, av ->
                    if (idx > 0) callArgs.add(", ")
                    callArgs.add("%L", av)
                }
                CodeBlock.of("import_%L.%N(%L)", propName, binding.kotlinMethodName, callArgs.build())
            }
        }

        if (tr.type.results.isEmpty()) {
            body.add(callExpr).add("\n")
        } else {
            val hostRetVar = fresh("hostRet")
            body.add("val %L = ", hostRetVar).add(callExpr).add("\n")
            if (cxVar != null) {
                body.beginControlFlow("try")
            }
            if (tr.type.flatResultCount > CanonicalAbi.MAX_FLAT_RESULTS) {
                val retPtrVar = "p${paramTypes.size - 1}"
                val mem = ctx.requireMemory()
                val tuple = tr.type.resultTuple
                body.addStatement(
                    "%T.checkMemoryRange(%L, %L, %LL, %L, %S)",
                    DirectCanonicalAbi::class,
                    mem,
                    retPtrVar,
                    tuple.size,
                    tuple.align,
                    "return pointer",
                )
                if (binding.resultTypes.size == 1) {
                    emitStore(body, tr.type.results[0], binding.resultTypes[0], CodeBlock.of("%L", hostRetVar), retPtrVar, 0, ctx)
                } else {
                    var off = 0
                    for (rIdx in tr.type.results.indices) {
                        val rt = tr.type.results[rIdx]
                        off = CanonicalAbi.alignTo(off, rt.align)
                        emitStore(body, rt, binding.resultTypes[rIdx], CodeBlock.of("%L.f%L", hostRetVar, rIdx), retPtrVar, off, ctx)
                        off += rt.size
                    }
                }
                if (cxVar != null) body.addStatement("%L.commitMoves()", cxVar)
            } else {
                val slots = emitLowerFlat(body, tr.type.results[0], binding.resultTypes[0], CodeBlock.of("%L", hostRetVar), ctx)
                if (cxVar != null) body.addStatement("%L.commitMoves()", cxVar)
                if (hasCoreReturn) {
                    body.add(slots.single()).add("\n")
                }
            }
            if (cxVar != null) {
                body.nextControlFlow("catch (__t: Throwable)")
                body.addStatement("%L.rollbackLowering()", cxVar)
                body.addStatement("throw __t")
                body.endControlFlow()
            }
        }

        if (hasCoreReturn) {
            body.unindent().add("}\n")
        } else {
            body.endControlFlow()
        }

        if (cxVar != null) {
            body.nextControlFlow("finally")
            body.addStatement("%L.releaseLends()", cxVar)
            body.endControlFlow()
        }

        return builder.addCode(body.build()).build()
    }

    // ------------------------------------------------------------------ Flat lower / lift

    private fun unwrapAst(astRef: WitTypeRef, exportCtx: Boolean): WitTypeRef {
        var cur = astRef
        val seen = mutableSetOf<Int>()
        while (cur is WitTypeRef.Id && seen.add(cur.index)) {
            val info = model.typeCatalog.get(cur.index, exportCtx)
            if (info is AotTypeInfo.TypeAliasInfo) {
                cur = info.target
            } else {
                break
            }
        }
        return cur
    }

    private fun emitLowerFlat(
        out: CodeBlock.Builder,
        planType: WitType,
        astRef: WitTypeRef,
        expr: CodeBlock,
        ctx: AbiEmitContext,
    ): List<CodeBlock> {
        val unwrapped = unwrapAst(astRef, ctx.exportCtx)
        return when (planType) {
            WitType.Bool -> listOf(CodeBlock.builder().add("%T.lowerBool(", DirectCanonicalAbi::class).add(expr).add(")").build())
            WitType.S8 -> listOf(CodeBlock.builder().add("(").add(expr).add(").toInt()").build())
            WitType.U8 -> listOf(CodeBlock.builder().add("((").add(expr).add(").toInt() and 0xFF)").build())
            WitType.S16 -> listOf(CodeBlock.builder().add("(").add(expr).add(").toInt()").build())
            WitType.U16 -> listOf(CodeBlock.builder().add("((").add(expr).add(").toInt() and 0xFFFF)").build())
            WitType.S32 -> listOf(expr)
            WitType.U32 -> listOf(CodeBlock.builder().add("(").add(expr).add(").toInt()").build())
            WitType.S64 -> listOf(expr)
            WitType.U64 -> listOf(CodeBlock.builder().add("(").add(expr).add(").toLong()").build())
            WitType.F32 -> listOf(CodeBlock.builder().add("%T.lowerF32(", DirectCanonicalAbi::class).add(expr).add(")").build())
            WitType.F64 -> listOf(CodeBlock.builder().add("%T.lowerF64(", DirectCanonicalAbi::class).add(expr).add(")").build())
            WitType.Char -> listOf(CodeBlock.builder().add("%T.lowerChar((", DirectCanonicalAbi::class).add(expr).add(").value)").build())
            WitType.Str -> {
                val mem = ctx.requireMemory()
                val realloc = ctx.requireRealloc()
                val packed = fresh("str")
                out.add("val %L = rt.lowerString(%L, %L, %T.%L, ", packed, mem, realloc, StringEncoding::class, ctx.encoding.name)
                    .add(expr).add(")\n")
                listOf(
                    CodeBlock.of("%L.toInt()", packed),
                    CodeBlock.of("(%L ushr 32).toInt()", packed),
                )
            }
            is WitType.ListOf -> {
                val listInfo = model.typeCatalog.get((unwrapped as WitTypeRef.Id).index, ctx.exportCtx) as AotTypeInfo.ListInfo
                val mem = ctx.requireMemory()
                val realloc = ctx.requireRealloc()
                if (listInfo.isByteArray) {
                    val packed = fresh("bytes")
                    out.add("val %L = rt.lowerByteArray(%L, %L, ", packed, mem, realloc)
                        .add(expr).add(")\n")
                    listOf(
                        CodeBlock.of("%L.toInt()", packed),
                        CodeBlock.of("(%L ushr 32).toInt()", packed),
                    )
                } else {
                    val listVar = fresh("list")
                    out.add("val %L = ", listVar).add(expr).add("\n")
                    val nVar = fresh("len")
                    val elemSize = planType.element.size
                    out.addStatement("val %L = %L.size", nVar, listVar)
                    val byteLenVar = fresh("byteLen")
                    out.addStatement("val %L = %T.checkLowerListLength(%L, %L)", byteLenVar, DirectCanonicalAbi::class, nVar, elemSize)
                    val ptrVar = fresh("listPtr")
                    out.addStatement("val %L = rt.alloc(%L, %L, %L, %L)", ptrVar, mem, realloc, planType.element.align, byteLenVar)
                    val idxVar = fresh("i")
                    out.beginControlFlow("for (%L in 0 until %L)", idxVar, nVar)
                    val elemExpr = CodeBlock.of("%L[%L]", listVar, idxVar)
                    emitStoreDynamicPtr(out, planType.element, listInfo.element, elemExpr, CodeBlock.of("%L + %L * %L", ptrVar, idxVar, elemSize), 0, ctx)
                    out.endControlFlow()
                    listOf(CodeBlock.of("%L", ptrVar), CodeBlock.of("%L", nVar))
                }
            }
            is WitType.Record -> {
                val recInfo = model.typeCatalog.get((unwrapped as WitTypeRef.Id).index, ctx.exportCtx) as AotTypeInfo.RecordInfo
                if (recInfo.fields.isEmpty()) {
                    emptyList()
                } else {
                    val v = fresh("rec")
                    out.add("val %L = ", v).add(expr).add("\n")
                    planType.fields.indices.flatMap { i ->
                        val f = recInfo.fields[i]
                        emitLowerFlat(out, planType.fields[i].second, f.type, CodeBlock.of("%L.%N", v, f.kotlinName), ctx)
                    }
                }
            }
            is WitType.Tuple -> {
                val tupleInfo = model.typeCatalog.get((unwrapped as WitTypeRef.Id).index, ctx.exportCtx) as AotTypeInfo.TupleInfo
                if (tupleInfo.types.isEmpty()) {
                    emptyList()
                } else {
                    val v = fresh("tup")
                    out.add("val %L = ", v).add(expr).add("\n")
                    planType.types.indices.flatMap { i ->
                        emitLowerFlat(out, planType.types[i], tupleInfo.types[i], CodeBlock.of("%L.f%L", v, i), ctx)
                    }
                }
            }
            is WitType.Flags -> {
                val flagsInfo = model.typeCatalog.get((unwrapped as WitTypeRef.Id).index, ctx.exportCtx) as AotTypeInfo.FlagsInfo
                when {
                    flagsInfo.flagCount == 0 -> emptyList()
                    !flagsInfo.useULong -> {
                        val rawVar = fresh("flags")
                        out.add("val %L = %T.checkLowerFlags32((", rawVar, DirectCanonicalAbi::class)
                            .add(expr).add(").raw.toInt(), %L)\n", flagsInfo.flagCount)
                        listOf(CodeBlock.of("%L", rawVar))
                    }
                    flagsInfo.flagCount <= 64 -> {
                        val rawVar = fresh("flags64")
                        out.add("val %L = %T.checkLowerFlags64((", rawVar, DirectCanonicalAbi::class)
                            .add(expr).add(").raw.toLong(), %L)\n", flagsInfo.flagCount)
                        listOf(
                            CodeBlock.of("%L.toInt()", rawVar),
                            CodeBlock.of("(%L ushr 32).toInt()", rawVar),
                        )
                    }
                    else -> throw UnsupportedOperationException("Flags with >64 bits are not supported")
                }
            }
            is WitType.Variant -> emitLowerVariantFlat(out, planType, unwrapped, expr, ctx)
            is WitType.Own -> {
                val handleVar = lowerResourceHandle(out, planType.table, unwrapped, own = true, expr, ctx)
                listOf(CodeBlock.of("%L", handleVar))
            }
            is WitType.Borrow -> {
                val handleVar = lowerResourceHandle(out, planType.table, unwrapped, own = false, expr, ctx)
                listOf(CodeBlock.of("%L", handleVar))
            }
            is WitType.Stream, is WitType.Future, WitType.ErrorContext, is WitType.Unsupported ->
                throw UnsupportedOperationException("WIT type is not supported by the AOT backend yet: $planType")
        }
    }

    private fun emitLowerVariantFlat(
        out: CodeBlock.Builder,
        variant: WitType.Variant,
        unwrappedAst: WitTypeRef,
        expr: CodeBlock,
        ctx: AbiEmitContext,
    ): List<CodeBlock> {
        val info = model.typeCatalog.get((unwrappedAst as WitTypeRef.Id).index, ctx.exportCtx)
        val joined = variant.payloadFlat
        if (variant.kind == WitType.VariantKind.ENUM) {
            info as AotTypeInfo.EnumInfo
            return listOf(CodeBlock.builder().add("(").add(expr).add(").ordinal").build())
        }

        val vVar = fresh("var")
        out.add("val %L = ", vVar).add(expr).add("\n")
        val discVar = fresh("disc")
        out.addStatement("val %L: Int", discVar)
        val slotVars = joined.mapIndexed { k, ct ->
            val sv = fresh("p$k")
            val (kotlinType, initLit) = flatSlotKotlinInit(ct)
            out.addStatement("var %L: %T = %L", sv, kotlinType, initLit)
            sv
        }

        fun assignJoinedSlots(caseIdx: Int, caseType: WitType?, caseAst: WitTypeRef?, payloadExpr: CodeBlock) {
            out.addStatement("%L = %L", discVar, caseIdx)
            if (caseType != null && caseAst != null) {
                val caseFlat = caseType.flat
                val caseVals = emitLowerFlat(out, caseType, caseAst, payloadExpr, ctx)
                for (k in caseFlat.indices) {
                    val coerced = coerceFlatExpr(caseVals[k], caseFlat[k], joined[k])
                    out.add("%L = ", slotVars[k]).add(coerced).add("\n")
                }
            }
        }

        when (info) {
            is AotTypeInfo.OptionInfo -> {
                if (info.isWitOption) {
                    out.beginControlFlow("if (%L is %T.Some)", vVar, info.witOptionClass)
                    assignJoinedSlots(1, variant.cases[1].second, info.inner, CodeBlock.of("%L.value", vVar))
                    out.nextControlFlow("else")
                    out.addStatement("%L = 0", discVar)
                    out.endControlFlow()
                } else {
                    out.beginControlFlow("if (%L != null)", vVar)
                    assignJoinedSlots(1, variant.cases[1].second, info.inner, CodeBlock.of("%L", vVar))
                    out.nextControlFlow("else")
                    out.addStatement("%L = 0", discVar)
                    out.endControlFlow()
                }
            }
            is AotTypeInfo.ResultInfo -> {
                out.beginControlFlow("when (%L)", vVar)
                out.beginControlFlow("is %T.Ok ->", info.witResultClass)
                assignJoinedSlots(0, variant.cases[0].second, info.ok, CodeBlock.of("%L.value", vVar))
                out.endControlFlow()
                out.beginControlFlow("is %T.Err ->", info.witResultClass)
                assignJoinedSlots(1, variant.cases[1].second, info.err, CodeBlock.of("%L.error", vVar))
                out.endControlFlow()
                out.endControlFlow()
            }
            is AotTypeInfo.VariantInfo -> {
                out.beginControlFlow("when (%L)", vVar)
                variant.cases.forEachIndexed { idx, (_, cType) ->
                    val cInfo = info.cases[idx]
                    out.beginControlFlow("is %T ->", cInfo.className)
                    assignJoinedSlots(idx, cType, cInfo.payloadType, CodeBlock.of("%L.value", vVar))
                    out.endControlFlow()
                }
                out.endControlFlow()
            }
            else -> error("Unexpected AST info $info for Variant")
        }

        return listOf(CodeBlock.of("%L", discVar)) + slotVars.map { CodeBlock.of("%L", it) }
    }

    private fun emitLiftFlat(
        out: CodeBlock.Builder,
        planType: WitType,
        astRef: WitTypeRef,
        slots: List<CodeBlock>,
        ctx: AbiEmitContext,
    ): CodeBlock {
        val unwrapped = unwrapAst(astRef, ctx.exportCtx)
        return when (planType) {
            WitType.Bool -> CodeBlock.builder().add("%T.liftBool(", DirectCanonicalAbi::class).add(slots[0]).add(")").build()
            WitType.S8 -> CodeBlock.builder().add("(").add(slots[0]).add(").toByte()").build()
            WitType.U8 -> CodeBlock.builder().add("(").add(slots[0]).add(").toUByte()").build()
            WitType.S16 -> CodeBlock.builder().add("(").add(slots[0]).add(").toShort()").build()
            WitType.U16 -> CodeBlock.builder().add("(").add(slots[0]).add(").toUShort()").build()
            WitType.S32 -> slots[0]
            WitType.U32 -> CodeBlock.builder().add("(").add(slots[0]).add(").toUInt()").build()
            WitType.S64 -> slots[0]
            WitType.U64 -> CodeBlock.builder().add("(").add(slots[0]).add(").toULong()").build()
            WitType.F32 -> CodeBlock.builder().add("%T.liftF32(", DirectCanonicalAbi::class).add(slots[0]).add(")").build()
            WitType.F64 -> CodeBlock.builder().add("%T.liftF64(", DirectCanonicalAbi::class).add(slots[0]).add(")").build()
            WitType.Char -> CodeBlock.builder().add("%T(%T.liftChar(", codePointClass, DirectCanonicalAbi::class).add(slots[0]).add("))").build()
            WitType.Str -> {
                val mem = ctx.requireMemory()
                CodeBlock.builder()
                    .add("%T.liftString(%L, ", DirectCanonicalAbi::class, mem)
                    .add(slots[0]).add(", ").add(slots[1])
                    .add(", %T.%L)", StringEncoding::class, ctx.encoding.name)
                    .build()
            }
            is WitType.ListOf -> {
                val listInfo = model.typeCatalog.get((unwrapped as WitTypeRef.Id).index, ctx.exportCtx) as AotTypeInfo.ListInfo
                val mem = ctx.requireMemory()
                if (listInfo.isByteArray) {
                    CodeBlock.builder()
                        .add("%T.liftByteArray(%L, ", DirectCanonicalAbi::class, mem)
                        .add(slots[0]).add(", ").add(slots[1]).add(")")
                        .build()
                } else {
                    val ptrVar = fresh("listPtr")
                    val nVar = fresh("listLen")
                    val outVar = fresh("list")
                    val elemSize = planType.element.size
                    out.add("val %L = ", ptrVar).add(slots[0]).add("\n")
                    out.addStatement(
                        "val %L = %T.checkLiftList(%L, %L, %L, %L, %L)",
                        nVar, DirectCanonicalAbi::class, mem, ptrVar, slots[1], elemSize, planType.element.align,
                    )
                    out.addStatement("val %L = ArrayList<%T>(%L)", outVar, listInfo.elementKotlinType, nVar)
                    val idxVar = fresh("i")
                    out.beginControlFlow("for (%L in 0 until %L)", idxVar, nVar)
                    val elemExpr = emitLoadDynamicPtr(out, planType.element, listInfo.element, CodeBlock.of("%L + %L * %L", ptrVar, idxVar, elemSize), 0, ctx)
                    out.add("%L.add(", outVar).add(elemExpr).add(")\n")
                    out.endControlFlow()
                    CodeBlock.of("%L", outVar)
                }
            }
            is WitType.Record -> {
                val recInfo = model.typeCatalog.get((unwrapped as WitTypeRef.Id).index, ctx.exportCtx) as AotTypeInfo.RecordInfo
                if (recInfo.fields.isEmpty()) {
                    CodeBlock.of("%T()", recInfo.className)
                } else {
                    var pos = 0
                    val args = planType.fields.indices.map { i ->
                        val ft = planType.fields[i].second
                        val fCount = ft.flat.size
                        val fSlots = slots.subList(pos, pos + fCount)
                        pos += fCount
                        val fv = fresh("fld")
                        val expr = emitLiftFlat(out, ft, recInfo.fields[i].type, fSlots, ctx)
                        out.add("val %L = ", fv).add(expr).add("\n")
                        CodeBlock.of("%N = %L", recInfo.fields[i].kotlinName, fv)
                    }
                    CodeBlock.builder().add("%T(", recInfo.className).apply {
                        args.forEachIndexed { idx, a ->
                            if (idx > 0) add(", ")
                            add(a)
                        }
                    }.add(")").build()
                }
            }
            is WitType.Tuple -> {
                val tupleInfo = model.typeCatalog.get((unwrapped as WitTypeRef.Id).index, ctx.exportCtx) as AotTypeInfo.TupleInfo
                if (tupleInfo.types.isEmpty()) {
                    CodeBlock.of("%T()", tupleInfo.tupleClass)
                } else {
                    var pos = 0
                    val args = planType.types.indices.map { i ->
                        val et = planType.types[i]
                        val eCount = et.flat.size
                        val eSlots = slots.subList(pos, pos + eCount)
                        pos += eCount
                        val ev = fresh("tupEl")
                        val expr = emitLiftFlat(out, et, tupleInfo.types[i], eSlots, ctx)
                        out.add("val %L = ", ev).add(expr).add("\n")
                        CodeBlock.of("%L", ev)
                    }
                    CodeBlock.builder().add("%T(", tupleInfo.tupleClass).apply {
                        args.forEachIndexed { idx, a ->
                            if (idx > 0) add(", ")
                            add(a)
                        }
                    }.add(")").build()
                }
            }
            is WitType.Flags -> {
                val flagsInfo = model.typeCatalog.get((unwrapped as WitTypeRef.Id).index, ctx.exportCtx) as AotTypeInfo.FlagsInfo
                when {
                    flagsInfo.flagCount == 0 -> CodeBlock.of("%T(0u)", flagsInfo.className)
                    !flagsInfo.useULong -> CodeBlock.builder()
                        .add("%T(%T.maskLiftFlags32(", flagsInfo.className, DirectCanonicalAbi::class)
                        .add(slots[0]).add(", %L).toUInt())", flagsInfo.flagCount)
                        .build()
                    flagsInfo.flagCount <= 64 -> {
                        val combined = fresh("flags64")
                        out.add("val %L = ((", combined)
                            .add(slots[0]).add(".toLong() and 0xFFFF_FFFFL) or (").add(slots[1]).add(".toLong() shl 32))\n")
                        CodeBlock.of("%T(%T.maskLiftFlags64(%L, %L).toULong())", flagsInfo.className, DirectCanonicalAbi::class, combined, flagsInfo.flagCount)
                    }
                    else -> throw UnsupportedOperationException("Flags with >64 bits are not supported")
                }
            }
            is WitType.Variant -> emitLiftVariantFlat(out, planType, unwrapped, slots, ctx)
            is WitType.Own -> liftResourceHandle(planType.table, unwrapped, own = true, slots[0], ctx)
            is WitType.Borrow -> liftResourceHandle(planType.table, unwrapped, own = false, slots[0], ctx)
            is WitType.Stream, is WitType.Future, WitType.ErrorContext, is WitType.Unsupported ->
                throw UnsupportedOperationException("WIT type is not supported by the AOT backend yet: $planType")
        }
    }

    private fun emitLiftVariantFlat(
        out: CodeBlock.Builder,
        variant: WitType.Variant,
        unwrappedAst: WitTypeRef,
        slots: List<CodeBlock>,
        ctx: AbiEmitContext,
    ): CodeBlock {
        val info = model.typeCatalog.get((unwrappedAst as WitTypeRef.Id).index, ctx.exportCtx)
        val joined = variant.payloadFlat
        val discCheck = CodeBlock.builder()
            .add("%T.checkLiftDiscriminant(", DirectCanonicalAbi::class)
            .add(slots[0])
            .add(", %L)", variant.cases.size)
            .build()

        if (info is AotTypeInfo.EnumInfo) {
            return CodeBlock.builder().add("%T.entries[", info.className).add(discCheck).add("]").build()
        }

        val slotVars = joined.indices.map { k ->
            val sv = fresh("p$k")
            out.add("val %L = ", sv).add(slots[1 + k]).add("\n")
            CodeBlock.of("%L", sv)
        }
        val resVar = fresh("var")
        val kotlinRetType = when (info) {
            is AotTypeInfo.OptionInfo -> info.kotlinType
            is AotTypeInfo.ResultInfo -> info.kotlinType
            is AotTypeInfo.VariantInfo -> info.className
            else -> error("Unexpected AST info $info")
        }
        out.add("val %L: %T = when (", resVar, kotlinRetType).add(discCheck).add(") {\n").indent()

        fun uncoerceCaseSlots(caseType: WitType): List<CodeBlock> =
            caseType.flat.mapIndexed { k, dstType -> uncoerceFlatExpr(slotVars[k], joined[k], dstType) }

        when (info) {
            is AotTypeInfo.OptionInfo -> {
                if (info.isWitOption) {
                    out.addStatement("0 -> %T.None", info.witOptionClass)
                    out.beginControlFlow("1 ->")
                    val innerExpr = emitLiftFlat(out, variant.cases[1].second!!, info.inner, uncoerceCaseSlots(variant.cases[1].second!!), ctx)
                    out.add("%T.Some(", info.witOptionClass).add(innerExpr).add(")\n")
                    out.endControlFlow()
                } else {
                    out.addStatement("0 -> null")
                    out.beginControlFlow("1 ->")
                    val innerExpr = emitLiftFlat(out, variant.cases[1].second!!, info.inner, uncoerceCaseSlots(variant.cases[1].second!!), ctx)
                    out.add(innerExpr).add("\n")
                    out.endControlFlow()
                }
            }
            is AotTypeInfo.ResultInfo -> {
                out.beginControlFlow("0 ->")
                val okType = variant.cases[0].second
                val okRef = info.ok
                if (okType != null && okRef != null) {
                    val expr = emitLiftFlat(out, okType, okRef, uncoerceCaseSlots(okType), ctx)
                    out.add("%T.Ok(", info.witResultClass).add(expr).add(")\n")
                } else {
                    out.addStatement("%T.Ok(Unit)", info.witResultClass)
                }
                out.endControlFlow()
                out.beginControlFlow("1 ->")
                val errType = variant.cases[1].second
                val errRef = info.err
                if (errType != null && errRef != null) {
                    val expr = emitLiftFlat(out, errType, errRef, uncoerceCaseSlots(errType), ctx)
                    out.add("%T.Err(", info.witResultClass).add(expr).add(")\n")
                } else {
                    out.addStatement("%T.Err(Unit)", info.witResultClass)
                }
                out.endControlFlow()
            }
            is AotTypeInfo.VariantInfo -> {
                variant.cases.forEachIndexed { idx, (_, cType) ->
                    val cInfo = info.cases[idx]
                    val pType = cInfo.payloadType
                    if (cInfo.isObject || cType == null || pType == null) {
                        out.addStatement("%L -> %T", idx, cInfo.className)
                    } else {
                        out.beginControlFlow("%L ->", idx)
                        val expr = emitLiftFlat(out, cType, pType, uncoerceCaseSlots(cType), ctx)
                        out.add("%T(", cInfo.className).add(expr).add(")\n")
                        out.endControlFlow()
                    }
                }
            }
            else -> error("Unexpected AST info $info")
        }
        out.addStatement("else -> error(%S)", "unreachable")
        out.unindent().add("}\n")
        return CodeBlock.of("%L", resVar)
    }

    // ------------------------------------------------------------------ Memory store / load

    private fun emitStore(
        out: CodeBlock.Builder,
        planType: WitType,
        astRef: WitTypeRef,
        expr: CodeBlock,
        basePtrVar: String,
        offset: Int,
        ctx: AbiEmitContext,
    ) = emitStoreDynamicPtr(out, planType, astRef, expr, CodeBlock.of("%L", basePtrVar), offset, ctx)

    private fun emitStoreDynamicPtr(
        out: CodeBlock.Builder,
        planType: WitType,
        astRef: WitTypeRef,
        expr: CodeBlock,
        ptrExpr: CodeBlock,
        offset: Int,
        ctx: AbiEmitContext,
    ) {
        val unwrapped = unwrapAst(astRef, ctx.exportCtx)
        val mem = ctx.requireMemory()
        when (planType) {
            WitType.Bool -> out.add("%L.storeI32_8(", mem).add(ptrExpr).add(", %L, %T.lowerBool(", offset, DirectCanonicalAbi::class).add(expr).add("))\n")
            WitType.S8, WitType.U8 -> out.add("%L.storeI32_8(", mem).add(ptrExpr).add(", %L, (", offset).add(expr).add(").toInt())\n")
            WitType.S16, WitType.U16 -> out.add("%L.storeI32_16(", mem).add(ptrExpr).add(", %L, (", offset).add(expr).add(").toInt())\n")
            WitType.S32, WitType.U32 -> out.add("%L.storeI32(", mem).add(ptrExpr).add(", %L, (", offset).add(expr).add(").toInt())\n")
            WitType.S64, WitType.U64 -> out.add("%L.storeI64(", mem).add(ptrExpr).add(", %L, (", offset).add(expr).add(").toLong())\n")
            WitType.F32 -> out.add("%L.storeI32(", mem).add(ptrExpr).add(", %L, %T.lowerF32Bits(", offset, DirectCanonicalAbi::class).add(expr).add("))\n")
            WitType.F64 -> out.add("%L.storeI64(", mem).add(ptrExpr).add(", %L, %T.lowerF64Bits(", offset, DirectCanonicalAbi::class).add(expr).add("))\n")
            WitType.Char -> out.add("%L.storeI32(", mem).add(ptrExpr).add(", %L, %T.lowerChar((", offset, DirectCanonicalAbi::class).add(expr).add(").value))\n")
            WitType.Str, is WitType.ListOf -> {
                val baseVar = ensurePtrVar(out, ptrExpr)
                val flatSlots = emitLowerFlat(out, planType, unwrapped, expr, ctx)
                out.add("%L.storeI32(%L, %L, ", mem, baseVar, offset).add(flatSlots[0]).add(")\n")
                out.add("%L.storeI32(%L, %L, ", mem, baseVar, offset + 4).add(flatSlots[1]).add(")\n")
            }
            is WitType.Record -> {
                val recInfo = model.typeCatalog.get((unwrapped as WitTypeRef.Id).index, ctx.exportCtx) as AotTypeInfo.RecordInfo
                if (recInfo.fields.isEmpty()) return
                val baseVar = ensurePtrVar(out, ptrExpr)
                val v = fresh("rec")
                out.add("val %L = ", v).add(expr).add("\n")
                var off = offset
                val baseOff = offset
                var rel = 0
                for (i in planType.fields.indices) {
                    val ft = planType.fields[i].second
                    rel = CanonicalAbi.alignTo(rel, ft.align)
                    off = baseOff + rel
                    val f = recInfo.fields[i]
                    emitStore(out, ft, f.type, CodeBlock.of("%L.%N", v, f.kotlinName), baseVar, off, ctx)
                    rel += ft.size
                }
            }
            is WitType.Tuple -> {
                val tupleInfo = model.typeCatalog.get((unwrapped as WitTypeRef.Id).index, ctx.exportCtx) as AotTypeInfo.TupleInfo
                if (tupleInfo.types.isEmpty()) return
                val baseVar = ensurePtrVar(out, ptrExpr)
                val v = fresh("tup")
                out.add("val %L = ", v).add(expr).add("\n")
                var rel = 0
                for (i in planType.types.indices) {
                    val et = planType.types[i]
                    rel = CanonicalAbi.alignTo(rel, et.align)
                    emitStore(out, et, tupleInfo.types[i], CodeBlock.of("%L.f%L", v, i), baseVar, offset + rel, ctx)
                    rel += et.size
                }
            }
            is WitType.Flags -> {
                val flagsInfo = model.typeCatalog.get((unwrapped as WitTypeRef.Id).index, ctx.exportCtx) as AotTypeInfo.FlagsInfo
                if (flagsInfo.flagCount == 0) return
                val baseVar = ensurePtrVar(out, ptrExpr)
                if (!flagsInfo.useULong) {
                    val rawVar = fresh("flags")
                    out.add("val %L = %T.checkLowerFlags32((", rawVar, DirectCanonicalAbi::class)
                        .add(expr).add(").raw.toInt(), %L)\n", flagsInfo.flagCount)
                    when (planType.size) {
                        1 -> out.addStatement("%L.storeI32_8(%L, %L, %L)", mem, baseVar, offset, rawVar)
                        2 -> out.addStatement("%L.storeI32_16(%L, %L, %L)", mem, baseVar, offset, rawVar)
                        else -> out.addStatement("%L.storeI32(%L, %L, %L)", mem, baseVar, offset, rawVar)
                    }
                } else if (flagsInfo.flagCount <= 64) {
                    val rawVar = fresh("flags64")
                    out.add("val %L = %T.checkLowerFlags64((", rawVar, DirectCanonicalAbi::class)
                        .add(expr).add(").raw.toLong(), %L)\n", flagsInfo.flagCount)
                    out.addStatement("%L.storeI32(%L, %L, %L.toInt())", mem, baseVar, offset, rawVar)
                    out.addStatement("%L.storeI32(%L, %L, (%L ushr 32).toInt())", mem, baseVar, offset + 4, rawVar)
                } else {
                    throw UnsupportedOperationException("Flags with >64 bits are not supported")
                }
            }
            is WitType.Variant -> {
                val baseVar = ensurePtrVar(out, ptrExpr)
                emitStoreVariant(out, planType, unwrapped, expr, baseVar, offset, ctx)
            }
            is WitType.Own -> {
                val handleVar = lowerResourceHandle(out, planType.table, unwrapped, own = true, expr, ctx)
                out.add("%L.storeI32(", mem).add(ptrExpr).add(", %L, %L)\n", offset, handleVar)
            }
            is WitType.Borrow -> {
                val handleVar = lowerResourceHandle(out, planType.table, unwrapped, own = false, expr, ctx)
                out.add("%L.storeI32(", mem).add(ptrExpr).add(", %L, %L)\n", offset, handleVar)
            }
            is WitType.Stream, is WitType.Future, WitType.ErrorContext, is WitType.Unsupported ->
                throw UnsupportedOperationException("WIT type is not supported by the AOT backend yet: $planType")
        }
    }

    private fun emitStoreVariant(
        out: CodeBlock.Builder,
        variant: WitType.Variant,
        unwrappedAst: WitTypeRef,
        expr: CodeBlock,
        basePtrVar: String,
        offset: Int,
        ctx: AbiEmitContext,
    ) {
        val mem = ctx.requireMemory()
        val info = model.typeCatalog.get((unwrappedAst as WitTypeRef.Id).index, ctx.exportCtx)
        fun writeDisc(discExpr: CodeBlock) {
            when (variant.discSize) {
                1 -> out.add("%L.storeI32_8(%L, %L, ", mem, basePtrVar, offset).add(discExpr).add(")\n")
                2 -> out.add("%L.storeI32_16(%L, %L, ", mem, basePtrVar, offset).add(discExpr).add(")\n")
                else -> out.add("%L.storeI32(%L, %L, ", mem, basePtrVar, offset).add(discExpr).add(")\n")
            }
        }
        if (info is AotTypeInfo.EnumInfo) {
            writeDisc(CodeBlock.builder().add("(").add(expr).add(").ordinal").build())
            return
        }
        val payloadOff = offset + variant.payloadOffset
        val vVar = fresh("var")
        out.add("val %L = ", vVar).add(expr).add("\n")
        when (info) {
            is AotTypeInfo.OptionInfo -> {
                if (info.isWitOption) {
                    out.beginControlFlow("if (%L is %T.Some)", vVar, info.witOptionClass)
                    writeDisc(CodeBlock.of("1"))
                    emitStore(out, variant.cases[1].second!!, info.inner, CodeBlock.of("%L.value", vVar), basePtrVar, payloadOff, ctx)
                    out.nextControlFlow("else")
                    writeDisc(CodeBlock.of("0"))
                    out.endControlFlow()
                } else {
                    out.beginControlFlow("if (%L != null)", vVar)
                    writeDisc(CodeBlock.of("1"))
                    emitStore(out, variant.cases[1].second!!, info.inner, CodeBlock.of("%L", vVar), basePtrVar, payloadOff, ctx)
                    out.nextControlFlow("else")
                    writeDisc(CodeBlock.of("0"))
                    out.endControlFlow()
                }
            }
            is AotTypeInfo.ResultInfo -> {
                out.beginControlFlow("when (%L)", vVar)
                out.beginControlFlow("is %T.Ok ->", info.witResultClass)
                writeDisc(CodeBlock.of("0"))
                variant.cases[0].second?.let { ct ->
                    info.ok?.let { okAst -> emitStore(out, ct, okAst, CodeBlock.of("%L.value", vVar), basePtrVar, payloadOff, ctx) }
                }
                out.endControlFlow()
                out.beginControlFlow("is %T.Err ->", info.witResultClass)
                writeDisc(CodeBlock.of("1"))
                variant.cases[1].second?.let { ct ->
                    info.err?.let { errAst -> emitStore(out, ct, errAst, CodeBlock.of("%L.error", vVar), basePtrVar, payloadOff, ctx) }
                }
                out.endControlFlow()
                out.endControlFlow()
            }
            is AotTypeInfo.VariantInfo -> {
                out.beginControlFlow("when (%L)", vVar)
                variant.cases.forEachIndexed { idx, (_, cType) ->
                    val cInfo = info.cases[idx]
                    val pType = cInfo.payloadType
                    out.beginControlFlow("is %T ->", cInfo.className)
                    writeDisc(CodeBlock.of("%L", idx))
                    if (cType != null && pType != null) {
                        emitStore(out, cType, pType, CodeBlock.of("%L.value", vVar), basePtrVar, payloadOff, ctx)
                    }
                    out.endControlFlow()
                }
                out.endControlFlow()
            }
            else -> error("Unexpected AST info $info")
        }
    }

    private fun emitLoad(
        out: CodeBlock.Builder,
        planType: WitType,
        astRef: WitTypeRef,
        basePtrVar: String,
        offset: Int,
        ctx: AbiEmitContext,
    ): CodeBlock = emitLoadDynamicPtr(out, planType, astRef, CodeBlock.of("%L", basePtrVar), offset, ctx)

    private fun emitLoadDynamicPtr(
        out: CodeBlock.Builder,
        planType: WitType,
        astRef: WitTypeRef,
        ptrExpr: CodeBlock,
        offset: Int,
        ctx: AbiEmitContext,
    ): CodeBlock {
        val unwrapped = unwrapAst(astRef, ctx.exportCtx)
        val mem = ctx.requireMemory()
        return when (planType) {
            WitType.Bool -> CodeBlock.builder().add("%T.liftBool(%L.loadI32_8u(", DirectCanonicalAbi::class, mem).add(ptrExpr).add(", %L))", offset).build()
            WitType.S8 -> CodeBlock.builder().add("%L.loadI32_8s(", mem).add(ptrExpr).add(", %L).toByte()", offset).build()
            WitType.U8 -> CodeBlock.builder().add("%L.loadI32_8u(", mem).add(ptrExpr).add(", %L).toUByte()", offset).build()
            WitType.S16 -> CodeBlock.builder().add("%L.loadI32_16s(", mem).add(ptrExpr).add(", %L).toShort()", offset).build()
            WitType.U16 -> CodeBlock.builder().add("%L.loadI32_16u(", mem).add(ptrExpr).add(", %L).toUShort()", offset).build()
            WitType.S32 -> CodeBlock.builder().add("%L.loadI32(", mem).add(ptrExpr).add(", %L)", offset).build()
            WitType.U32 -> CodeBlock.builder().add("%L.loadI32(", mem).add(ptrExpr).add(", %L).toUInt()", offset).build()
            WitType.S64 -> CodeBlock.builder().add("%L.loadI64(", mem).add(ptrExpr).add(", %L)", offset).build()
            WitType.U64 -> CodeBlock.builder().add("%L.loadI64(", mem).add(ptrExpr).add(", %L).toULong()", offset).build()
            WitType.F32 -> CodeBlock.builder().add("%T.liftF32Bits(%L.loadI32(", DirectCanonicalAbi::class, mem).add(ptrExpr).add(", %L))", offset).build()
            WitType.F64 -> CodeBlock.builder().add("%T.liftF64Bits(%L.loadI64(", DirectCanonicalAbi::class, mem).add(ptrExpr).add(", %L))", offset).build()
            WitType.Char -> CodeBlock.builder().add("%T(%T.liftChar(%L.loadI32(", codePointClass, DirectCanonicalAbi::class, mem).add(ptrExpr).add(", %L)))", offset).build()
            WitType.Str, is WitType.ListOf -> {
                val baseVar = ensurePtrVar(out, ptrExpr)
                val ptrSlot = CodeBlock.of("%L.loadI32(%L, %L)", mem, baseVar, offset)
                val lenSlot = CodeBlock.of("%L.loadI32(%L, %L)", mem, baseVar, offset + 4)
                emitLiftFlat(out, planType, unwrapped, listOf(ptrSlot, lenSlot), ctx)
            }
            is WitType.Record -> {
                val recInfo = model.typeCatalog.get((unwrapped as WitTypeRef.Id).index, ctx.exportCtx) as AotTypeInfo.RecordInfo
                if (recInfo.fields.isEmpty()) return CodeBlock.of("%T()", recInfo.className)
                val baseVar = ensurePtrVar(out, ptrExpr)
                var rel = 0
                val args = planType.fields.indices.map { i ->
                    val ft = planType.fields[i].second
                    rel = CanonicalAbi.alignTo(rel, ft.align)
                    val fv = fresh("fld")
                    val expr = emitLoad(out, ft, recInfo.fields[i].type, baseVar, offset + rel, ctx)
                    out.add("val %L = ", fv).add(expr).add("\n")
                    rel += ft.size
                    CodeBlock.of("%N = %L", recInfo.fields[i].kotlinName, fv)
                }
                CodeBlock.builder().add("%T(", recInfo.className).apply {
                    args.forEachIndexed { idx, a ->
                        if (idx > 0) add(", ")
                        add(a)
                    }
                }.add(")").build()
            }
            is WitType.Tuple -> {
                val tupleInfo = model.typeCatalog.get((unwrapped as WitTypeRef.Id).index, ctx.exportCtx) as AotTypeInfo.TupleInfo
                if (tupleInfo.types.isEmpty()) return CodeBlock.of("%T()", tupleInfo.tupleClass)
                val baseVar = ensurePtrVar(out, ptrExpr)
                var rel = 0
                val args = planType.types.indices.map { i ->
                    val et = planType.types[i]
                    rel = CanonicalAbi.alignTo(rel, et.align)
                    val ev = fresh("tupEl")
                    val expr = emitLoad(out, et, tupleInfo.types[i], baseVar, offset + rel, ctx)
                    out.add("val %L = ", ev).add(expr).add("\n")
                    rel += et.size
                    CodeBlock.of("%L", ev)
                }
                CodeBlock.builder().add("%T(", tupleInfo.tupleClass).apply {
                    args.forEachIndexed { idx, a ->
                        if (idx > 0) add(", ")
                        add(a)
                    }
                }.add(")").build()
            }
            is WitType.Flags -> {
                val flagsInfo = model.typeCatalog.get((unwrapped as WitTypeRef.Id).index, ctx.exportCtx) as AotTypeInfo.FlagsInfo
                if (flagsInfo.flagCount == 0) return CodeBlock.of("%T(0u)", flagsInfo.className)
                val baseVar = ensurePtrVar(out, ptrExpr)
                if (!flagsInfo.useULong) {
                    val rawRead = when (planType.size) {
                        1 -> CodeBlock.of("%L.loadI32_8u(%L, %L)", mem, baseVar, offset)
                        2 -> CodeBlock.of("%L.loadI32_16u(%L, %L)", mem, baseVar, offset)
                        else -> CodeBlock.of("%L.loadI32(%L, %L)", mem, baseVar, offset)
                    }
                    CodeBlock.builder()
                        .add("%T(%T.maskLiftFlags32(", flagsInfo.className, DirectCanonicalAbi::class)
                        .add(rawRead).add(", %L).toUInt())", flagsInfo.flagCount)
                        .build()
                } else if (flagsInfo.flagCount <= 64) {
                    val rawVar = fresh("flags64")
                    out.addStatement(
                        "val %L = ((%L.loadI32(%L, %L).toLong() and 0xFFFF_FFFFL) or (%L.loadI32(%L, %L).toLong() shl 32))",
                        rawVar, mem, baseVar, offset, mem, baseVar, offset + 4,
                    )
                    CodeBlock.of("%T(%T.maskLiftFlags64(%L, %L).toULong())", flagsInfo.className, DirectCanonicalAbi::class, rawVar, flagsInfo.flagCount)
                } else {
                    throw UnsupportedOperationException("Flags with >64 bits are not supported")
                }
            }
            is WitType.Variant -> {
                val baseVar = ensurePtrVar(out, ptrExpr)
                emitLoadVariant(out, planType, unwrapped, baseVar, offset, ctx)
            }
            is WitType.Own -> {
                val baseVar = ensurePtrVar(out, ptrExpr)
                liftResourceHandle(planType.table, unwrapped, own = true, CodeBlock.of("%L.loadI32(%L, %L)", mem, baseVar, offset), ctx)
            }
            is WitType.Borrow -> {
                val baseVar = ensurePtrVar(out, ptrExpr)
                liftResourceHandle(planType.table, unwrapped, own = false, CodeBlock.of("%L.loadI32(%L, %L)", mem, baseVar, offset), ctx)
            }
            is WitType.Stream, is WitType.Future, WitType.ErrorContext, is WitType.Unsupported ->
                throw UnsupportedOperationException("WIT type is not supported by the AOT backend yet: $planType")
        }
    }

    private fun emitLoadVariant(
        out: CodeBlock.Builder,
        variant: WitType.Variant,
        unwrappedAst: WitTypeRef,
        basePtrVar: String,
        offset: Int,
        ctx: AbiEmitContext,
    ): CodeBlock {
        val mem = ctx.requireMemory()
        val info = model.typeCatalog.get((unwrappedAst as WitTypeRef.Id).index, ctx.exportCtx)
        val readDisc = when (variant.discSize) {
            1 -> CodeBlock.of("%L.loadI32_8u(%L, %L)", mem, basePtrVar, offset)
            2 -> CodeBlock.of("%L.loadI32_16u(%L, %L)", mem, basePtrVar, offset)
            else -> CodeBlock.of("%L.loadI32(%L, %L)", mem, basePtrVar, offset)
        }
        val discCheck = CodeBlock.builder()
            .add("%T.checkLiftDiscriminant(", DirectCanonicalAbi::class)
            .add(readDisc)
            .add(", %L)", variant.cases.size)
            .build()
        if (info is AotTypeInfo.EnumInfo) {
            return CodeBlock.builder().add("%T.entries[", info.className).add(discCheck).add("]").build()
        }
        val payloadOff = offset + variant.payloadOffset
        val resVar = fresh("var")
        val kotlinRetType = when (info) {
            is AotTypeInfo.OptionInfo -> info.kotlinType
            is AotTypeInfo.ResultInfo -> info.kotlinType
            is AotTypeInfo.VariantInfo -> info.className
            else -> error("Unexpected AST info $info")
        }
        out.add("val %L: %T = when (", resVar, kotlinRetType).add(discCheck).add(") {\n").indent()
        when (info) {
            is AotTypeInfo.OptionInfo -> {
                if (info.isWitOption) {
                    out.addStatement("0 -> %T.None", info.witOptionClass)
                    out.beginControlFlow("1 ->")
                    val innerExpr = emitLoad(out, variant.cases[1].second!!, info.inner, basePtrVar, payloadOff, ctx)
                    out.add("%T.Some(", info.witOptionClass).add(innerExpr).add(")\n")
                    out.endControlFlow()
                } else {
                    out.addStatement("0 -> null")
                    out.beginControlFlow("1 ->")
                    val innerExpr = emitLoad(out, variant.cases[1].second!!, info.inner, basePtrVar, payloadOff, ctx)
                    out.add(innerExpr).add("\n")
                    out.endControlFlow()
                }
            }
            is AotTypeInfo.ResultInfo -> {
                out.beginControlFlow("0 ->")
                val okType = variant.cases[0].second
                val okRef = info.ok
                if (okType != null && okRef != null) {
                    val expr = emitLoad(out, okType, okRef, basePtrVar, payloadOff, ctx)
                    out.add("%T.Ok(", info.witResultClass).add(expr).add(")\n")
                } else {
                    out.addStatement("%T.Ok(Unit)", info.witResultClass)
                }
                out.endControlFlow()
                out.beginControlFlow("1 ->")
                val errType = variant.cases[1].second
                val errRef = info.err
                if (errType != null && errRef != null) {
                    val expr = emitLoad(out, errType, errRef, basePtrVar, payloadOff, ctx)
                    out.add("%T.Err(", info.witResultClass).add(expr).add(")\n")
                } else {
                    out.addStatement("%T.Err(Unit)", info.witResultClass)
                }
                out.endControlFlow()
            }
            is AotTypeInfo.VariantInfo -> {
                variant.cases.forEachIndexed { idx, (_, cType) ->
                    val cInfo = info.cases[idx]
                    val pType = cInfo.payloadType
                    if (cInfo.isObject || cType == null || pType == null) {
                        out.addStatement("%L -> %T", idx, cInfo.className)
                    } else {
                        out.beginControlFlow("%L ->", idx)
                        val expr = emitLoad(out, cType, pType, basePtrVar, payloadOff, ctx)
                        out.add("%T(", cInfo.className).add(expr).add(")\n")
                        out.endControlFlow()
                    }
                }
            }
            else -> error("Unexpected AST info $info")
        }
        out.addStatement("else -> error(%S)", "unreachable")
        out.unindent().add("}\n")
        return CodeBlock.of("%L", resVar)
    }

    // ------------------------------------------------------------------ Resources

    private fun resolveHandleInfo(astRef: WitTypeRef, exportCtx: Boolean): Pair<Boolean, ClassName> {
        val unwrapped = unwrapAst(astRef, exportCtx) as WitTypeRef.Id
        return when (val info = model.typeCatalog.get(unwrapped.index, exportCtx)) {
            is AotTypeInfo.HandleInfo -> info.isHost to info.resourceClass
            is AotTypeInfo.ResourceInfo -> info.isHost to info.className
            else -> error("Expected resource/handle AST info for index ${unwrapped.index}, got $info")
        }
    }

    private fun lowerResourceHandle(
        out: CodeBlock.Builder,
        table: Int,
        unwrappedAst: WitTypeRef,
        own: Boolean,
        expr: CodeBlock,
        ctx: AbiEmitContext,
    ): String {
        val (isHost, resClass) = resolveHandleInfo(unwrappedAst, ctx.exportCtx)
        val cx = checkNotNull(ctx.cxVar)
        val v = fresh("resObj")
        out.add("val %L = ", v).add(expr).add("\n")
        val hVar = fresh("handle")
        if (isHost) {
            if (own) {
                out.addStatement("val %L = %L.lowerHostOwn(%L, %L)", hVar, cx, table, v)
            } else {
                out.addStatement("val %L = %L.lowerHostBorrow(%L, %L)", hVar, cx, table, v)
            }
        } else {
            val rawHandleVar = fresh("guestId")
            out.addStatement("val %L = %L.handleFor(this@Instance)", rawHandleVar, v)
            if (own) {
                out.addStatement("val %L = %L.lowerOwn(%L, %L)", hVar, cx, table, rawHandleVar)
                ctx.movedGuestCollector(resClass, v)
            } else {
                out.addStatement("val %L = %L.lowerBorrow(%L, %L)", hVar, cx, table, rawHandleVar)
            }
        }
        return hVar
    }

    private fun liftResourceHandle(
        table: Int,
        unwrappedAst: WitTypeRef,
        own: Boolean,
        handleExpr: CodeBlock,
        ctx: AbiEmitContext,
    ): CodeBlock {
        val (isHost, resClass) = resolveHandleInfo(unwrappedAst, ctx.exportCtx)
        val cx = checkNotNull(ctx.cxVar)
        return if (isHost) {
            if (own) {
                CodeBlock.builder()
                    .add("%L.liftHostOwn(%L, ", cx, table)
                    .add(handleExpr)
                    .add(", %T::class.java)", resClass)
                    .build()
            } else {
                CodeBlock.builder()
                    .add("%L.liftHostBorrow(%L, ", cx, table)
                    .add(handleExpr)
                    .add(", %T::class.java)", resClass)
                    .build()
            }
        } else {
            if (own) {
                CodeBlock.builder()
                    .add("%T(this@Instance, %L.liftOwn(%L, ", resClass, cx, table)
                    .add(handleExpr)
                    .add("))")
                    .build()
            } else {
                CodeBlock.builder()
                    .add("%T(null, %L.liftBorrow(%L, ", resClass, cx, table)
                    .add(handleExpr)
                    .add("))")
                    .build()
            }
        }
    }

    // ------------------------------------------------------------------ Small utilities

    private data class AbiEmitContext(
        val memExpr: String?,
        val reallocExpr: String?,
        val encoding: StringEncoding,
        val exportCtx: Boolean,
        val cxVar: String?,
        val movedGuestCollector: (ClassName, String) -> Unit,
    ) {
        fun requireMemory(): String = memExpr ?: error("Function requires linear memory but canonical options specify none")
        fun requireRealloc(): String = reallocExpr ?: error("Function requires cabi_realloc but canonical options specify none")
    }

    private fun ensurePtrVar(out: CodeBlock.Builder, ptrExpr: CodeBlock): String {
        val raw = ptrExpr.toString()
        if (raw.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return raw
        val pv = fresh("ptr")
        out.add("val %L = ", pv).add(ptrExpr).add("\n")
        return pv
    }

    private fun flatSlotKotlinInit(ct: CanonicalAbi.CoreType): Pair<TypeName, String> = when (ct) {
        CanonicalAbi.CoreType.I32 -> INT to "0"
        CanonicalAbi.CoreType.I64 -> LONG to "0L"
        CanonicalAbi.CoreType.F32 -> FLOAT to "0f"
        CanonicalAbi.CoreType.F64 -> DOUBLE to "0.0"
    }

    private fun coerceFlatExpr(expr: CodeBlock, from: CanonicalAbi.CoreType, to: CanonicalAbi.CoreType): CodeBlock = when {
        from == to -> expr
        from == CanonicalAbi.CoreType.I32 && to == CanonicalAbi.CoreType.I64 ->
            CodeBlock.builder().add("%T.i32ToI64(", DirectCanonicalAbi::class).add(expr).add(")").build()
        from == CanonicalAbi.CoreType.F32 && to == CanonicalAbi.CoreType.I32 ->
            CodeBlock.builder().add("%T.f32ToI32(", DirectCanonicalAbi::class).add(expr).add(")").build()
        from == CanonicalAbi.CoreType.F32 && to == CanonicalAbi.CoreType.I64 ->
            CodeBlock.builder().add("%T.f32ToI64(", DirectCanonicalAbi::class).add(expr).add(")").build()
        from == CanonicalAbi.CoreType.F64 && to == CanonicalAbi.CoreType.I64 ->
            CodeBlock.builder().add("%T.f64ToI64(", DirectCanonicalAbi::class).add(expr).add(")").build()
        else -> error("Unsupported Canonical ABI flat coercion: $from -> $to")
    }

    private fun uncoerceFlatExpr(expr: CodeBlock, from: CanonicalAbi.CoreType, to: CanonicalAbi.CoreType): CodeBlock = when {
        from == to -> expr
        from == CanonicalAbi.CoreType.I64 && to == CanonicalAbi.CoreType.I32 ->
            CodeBlock.builder().add("%T.i64ToI32(", DirectCanonicalAbi::class).add(expr).add(")").build()
        from == CanonicalAbi.CoreType.I32 && to == CanonicalAbi.CoreType.F32 ->
            CodeBlock.builder().add("%T.i32ToF32(", DirectCanonicalAbi::class).add(expr).add(")").build()
        from == CanonicalAbi.CoreType.I64 && to == CanonicalAbi.CoreType.F32 ->
            CodeBlock.builder().add("%T.i64ToF32(", DirectCanonicalAbi::class).add(expr).add(")").build()
        from == CanonicalAbi.CoreType.I64 && to == CanonicalAbi.CoreType.F64 ->
            CodeBlock.builder().add("%T.i64ToF64(", DirectCanonicalAbi::class).add(expr).add(")").build()
        else -> error("Unsupported Canonical ABI flat uncoercion: $from -> $to")
    }

    private fun hasResources(types: List<WitType>): Boolean = types.any(::hasResources)

    private fun hasResources(t: WitType): Boolean = when (t) {
        is WitType.Own, is WitType.Borrow -> true
        is WitType.ListOf -> hasResources(t.element)
        is WitType.Record -> t.fields.any { hasResources(it.second) }
        is WitType.Tuple -> t.types.any(::hasResources)
        is WitType.Variant -> t.cases.any { c -> c.second?.let(::hasResources) ?: false }
        else -> false
    }

    private fun hasGuestOwn(t: WitType, exportCtx: Boolean): Boolean = when (t) {
        is WitType.Own -> {
            val table = plan.resourceTables[t.table]
            table != null && !table.imported
        }
        is WitType.ListOf -> hasGuestOwn(t.element, exportCtx)
        is WitType.Record -> t.fields.any { hasGuestOwn(it.second, exportCtx) }
        is WitType.Tuple -> t.types.any { hasGuestOwn(it, exportCtx) }
        is WitType.Variant -> t.cases.any { c -> c.second?.let { hasGuestOwn(it, exportCtx) } ?: false }
        else -> false
    }

    private fun collectGuestOwnClasses(
        t: WitType,
        astRef: WitTypeRef,
        exportCtx: Boolean,
        out: MutableSet<ClassName>,
    ) {
        val unwrapped = unwrapAst(astRef, exportCtx)
        when (t) {
            is WitType.Own -> {
                val (isHost, resCls) = resolveHandleInfo(unwrapped, exportCtx)
                if (!isHost) out.add(resCls)
            }
            is WitType.ListOf -> {
                val listInfo = model.typeCatalog.get((unwrapped as WitTypeRef.Id).index, exportCtx) as AotTypeInfo.ListInfo
                collectGuestOwnClasses(t.element, listInfo.element, exportCtx, out)
            }
            is WitType.Record -> {
                val recInfo = model.typeCatalog.get((unwrapped as WitTypeRef.Id).index, exportCtx) as AotTypeInfo.RecordInfo
                t.fields.indices.forEach { i -> collectGuestOwnClasses(t.fields[i].second, recInfo.fields[i].type, exportCtx, out) }
            }
            is WitType.Tuple -> {
                val tupInfo = model.typeCatalog.get((unwrapped as WitTypeRef.Id).index, exportCtx) as AotTypeInfo.TupleInfo
                t.types.indices.forEach { i -> collectGuestOwnClasses(t.types[i], tupInfo.types[i], exportCtx, out) }
            }
            is WitType.Variant -> {
                when (val info = model.typeCatalog.get((unwrapped as WitTypeRef.Id).index, exportCtx)) {
                    is AotTypeInfo.OptionInfo -> t.cases[1].second?.let { collectGuestOwnClasses(it, info.inner, exportCtx, out) }
                    is AotTypeInfo.ResultInfo -> {
                        t.cases[0].second?.let { ct -> info.ok?.let { collectGuestOwnClasses(ct, it, exportCtx, out) } }
                        t.cases[1].second?.let { ct -> info.err?.let { collectGuestOwnClasses(ct, it, exportCtx, out) } }
                    }
                    is AotTypeInfo.VariantInfo -> t.cases.forEachIndexed { idx, (_, ct) ->
                        val pt = info.cases[idx].payloadType
                        if (ct != null && pt != null) collectGuestOwnClasses(ct, pt, exportCtx, out)
                    }
                    else -> {}
                }
            }
            else -> {}
        }
    }

    private fun findUnsupportedDetail(ft: FuncType): String? {
        for ((_, t) in ft.params) findUnsupportedInType(t)?.let { return it }
        for (t in ft.results) findUnsupportedInType(t)?.let { return it }
        return null
    }

    private fun findUnsupportedInType(t: WitType): String? = when (t) {
        is WitType.Unsupported -> t.detail
        is WitType.Stream -> "stream"
        is WitType.Future -> "future"
        WitType.ErrorContext -> "error-context"
        is WitType.ListOf -> findUnsupportedInType(t.element)
        is WitType.Record -> t.fields.firstNotNullOfOrNull { findUnsupportedInType(it.second) }
        is WitType.Tuple -> t.types.firstNotNullOfOrNull(::findUnsupportedInType)
        is WitType.Variant -> t.cases.firstNotNullOfOrNull { c -> c.second?.let(::findUnsupportedInType) }
        else -> null
    }
}
