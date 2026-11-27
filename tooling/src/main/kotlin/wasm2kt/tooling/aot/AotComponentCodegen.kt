@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.tooling.aot

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName
import wasm2kt.ast.WitResolve
import wasm2kt.generator.InterfaceManifest
import wasm2kt.generator.WitToKotlinPoetConverter
import wasm2kt.runtime.ComponentBackend
import wasm2kt.runtime.ComponentBackendFactory
import wasm2kt.runtime.WitHostImportDispatcher
import wasm2kt.runtime.aot.AotCanonOptions
import wasm2kt.runtime.aot.AotComponentBackend
import wasm2kt.runtime.aot.AotCoreFunction
import wasm2kt.runtime.aot.AotErrorContextTableDesc
import wasm2kt.runtime.aot.AotExportDesc
import wasm2kt.runtime.aot.AotImportDesc
import wasm2kt.runtime.aot.AotPostReturn
import wasm2kt.runtime.aot.AotRealloc
import wasm2kt.runtime.aot.AotResourceDtor
import wasm2kt.runtime.aot.AotTransmitTableDesc
import wasm2kt.runtime.aot.I32Global
import wasm2kt.runtime.aot.LinearMemory
import wasm2kt.runtime.canonical.AsyncTrampolineSpec
import wasm2kt.runtime.canonical.InternalWasm2KtApi
import wasm2kt.runtime.canonical.StringEncoding
import wasm2kt.runtime.canonical.Transcoder
import wasm2kt.runtime.canonical.WitType
import wasm2kt.tooling.ComponentBuild
import wasm2kt.tooling.ComponentCodegen
import wasm2kt.tooling.Decomposer
import wasm2kt.tooling.ir.IrValType
import java.nio.file.Files
import java.nio.file.Path

/**
 * Generates static Kotlin wiring (`<Name>Component`) for a component compiled with
 * [AotComponentCompiler], eliminating runtime `plan.json`, `.meta`, Chicory, and
 * `kotlinx.serialization` dependencies (ADR-0023).
 *
 * [generate] emits Phase 2 direct-wired World DSL bindings (`AotComponentFactory<AotImports, Session>`).
 * [generateComponentOnly] emits the Phase 1 [ComponentBackendFactory] compatibility layer for
 * backend differential and conformance suites.
 */
object AotComponentCodegen {
    fun generate(
        decomposedDir: Path,
        outputDir: Path,
        componentName: String,
        packageName: String,
        compiled: CompiledAotComponent,
        internalPackage: String = ComponentBuild.internalPackage(packageName, componentName),
        worldName: String? = null,
        interfaceMappings: Map<String, String> = emptyMap(),
        libraryClasspath: List<Path> = emptyList(),
    ) {
        val resolve = WitResolve.parse(Files.readString(Decomposer.witJson(decomposedDir)))
        if (compiled.plan.usesAsync) {
            WitToKotlinPoetConverter(
                resolve = resolve,
                targetPackage = packageName,
                targetWorldName = worldName,
                emitWasmtimeSessions = false,
                interfaceMappings = interfaceMappings,
                libraryManifests = InterfaceManifest.readAll(libraryClasspath),
                aotDirectMode = false,
            ).generate().forEach { it.writeTo(outputDir) }
            Phase1ComponentEmitter(
                componentName = componentName,
                packageName = packageName,
                compiled = compiled,
            ).buildFile().writeTo(outputDir)
            return
        }
        val converter = WitToKotlinPoetConverter(
            resolve = resolve,
            targetPackage = packageName,
            targetWorldName = worldName,
            emitWasmtimeSessions = false,
            interfaceMappings = interfaceMappings,
            libraryManifests = InterfaceManifest.readAll(libraryClasspath),
            aotDirectMode = true,
        )
        converter.generate().forEach { it.writeTo(outputDir) }
        val model = requireNotNull(converter.aotModel) { "Expected aotModel when aotDirectMode = true" }
        DirectAotComponentEmitter(
            componentName = componentName,
            packageName = packageName,
            compiled = compiled,
            model = model,
        ).buildFile().writeTo(outputDir)
    }

    /** Generates only `<Name>Component.kt` using the Phase 1 [ComponentBackendFactory] wire layer. */
    fun generateComponentOnly(
        outputDir: Path,
        componentName: String,
        packageName: String,
        compiled: CompiledAotComponent,
        @Suppress("UNUSED_PARAMETER") internalPackage: String = ComponentBuild.internalPackage(packageName, componentName),
    ) {
        Phase1ComponentEmitter(
            componentName = componentName,
            packageName = packageName,
            compiled = compiled,
        ).buildFile().writeTo(outputDir)
    }

    private class Phase1ComponentEmitter(
        private val componentName: String,
        private val packageName: String,
        compiled: CompiledAotComponent,
    ) {
        private val plan: BuildPlan = compiled.plan
        private val objectName: String = ComponentCodegen.componentObjectName(componentName)
        private val funcTypePool = FuncTypePool()
        private val exportFuncTypeIds: List<Int> = plan.exports.map { funcTypePool.intern(it.type) }
        private val trampolineFuncTypeIds: Map<Int, Int> = buildMap {
            for ((idx, tr) in plan.trampolines) {
                if (tr is BuildTrampoline.LowerImport) {
                    put(idx, funcTypePool.intern(tr.type))
                }
            }
        }
        private val helper: AotPlanEmissionHelper = AotPlanEmissionHelper(plan, compiled) {
                out, trampolineIndex, tr, paramExprs, paramTypes, resultTypes, rawArgsVar, returnStyle ->
            val argsExpr = helper.coreArgsLongArrayExpr(paramExprs, paramTypes, rawArgsVar)
            val callCode = if (plan.usesAsync) {
                val ftId = trampolineFuncTypeIds.getValue(trampolineIndex)
                CodeBlock.builder()
                    .add("rt.callHostAsyncAware(%L, FUNC_TYPES[%L], %L, ", tr.import, ftId, tr.options)
                    .add(argsExpr)
                    .add(")")
                    .build()
            } else {
                val dispatchKey = plan.imports.getValue(tr.import).dispatchKey
                val ftId = trampolineFuncTypeIds.getValue(trampolineIndex)
                CodeBlock.builder()
                    .add("rt.callHost(%S, FUNC_TYPES[%L], opt_%L, ", dispatchKey, ftId, tr.options)
                    .add(argsExpr)
                    .add(")")
                    .build()
            }
            helper.emitLongArrayToReturn(out, callCode, resultTypes, returnStyle)
        }

        fun buildFile(): FileSpec {
            if (plan.usesAsync) {
                for (t in plan.streamTables.values) t.element?.let(funcTypePool::registerType)
                for (t in plan.futureTables.values) t.element?.let(funcTypePool::registerType)
                for (tr in plan.trampolines.values) {
                    if (tr is BuildTrampoline.Async) {
                        tr.results?.forEach(funcTypePool::registerType)
                    }
                }
            }

            val type = TypeSpec.objectBuilder(objectName)
                .addKdoc(
                    "The `%L` WebAssembly component, generated by wasm2kt. Its core modules and component " +
                        "wiring were compiled statically to JVM bytecode.\n\nPass it to a world's `withSession`/`openSession`; " +
                        "every call to [instantiate] creates an independent component instance.\n",
                    componentName,
                )
                .addSuperinterface(ComponentBackendFactory::class)

            funcTypePool.emitInto(type)
            helper.emitResourceTablesProperty(type)
            helper.emitTrapMessageArrays(type)
            if (plan.usesAsync) {
                emitAsyncMetadataProperties(type)
            }
            type.addFunction(buildInstantiateFun())

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
                .addType(type.build())
                .build()
        }

        private fun emitAsyncMetadataProperties(type: TypeSpec.Builder) {
            emitTransmitTablesProperty(type, "STREAM_TABLES", plan.streamTables)
            emitTransmitTablesProperty(type, "FUTURE_TABLES", plan.futureTables)
            emitErrorContextTablesProperty(type)
            emitImportsProperty(type)
            emitRegisterAsyncTrampolinesFun(type)
        }

        private fun emitTransmitTablesProperty(
            type: TypeSpec.Builder,
            name: String,
            tables: Map<Int, BuildTransmitTableInfo>,
        ) {
            val descType = AotTransmitTableDesc::class.asClassName()
            val arrayType = ClassName("kotlin", "Array").parameterizedBy(descType)
            val initCode = CodeBlock.builder()
            if (tables.isEmpty()) {
                initCode.add("emptyArray()")
            } else {
                initCode.add("arrayOf(\n").indent()
                for (t in tables.values.sortedBy { it.index }) {
                    val elemCode = t.element?.let { funcTypePool.witTypeCode(it) } ?: CodeBlock.of("null")
                    initCode.add("%T(%L, %L, ", descType, t.index, t.instance).add(elemCode).add("),\n")
                }
                initCode.unindent().add(")")
            }
            type.addProperty(
                PropertySpec.builder(name, arrayType, KModifier.PRIVATE)
                    .initializer(initCode.build())
                    .build(),
            )
        }

        private fun emitErrorContextTablesProperty(type: TypeSpec.Builder) {
            val descType = AotErrorContextTableDesc::class.asClassName()
            val arrayType = ClassName("kotlin", "Array").parameterizedBy(descType)
            val initCode = CodeBlock.builder()
            if (plan.errorContextTables.isEmpty()) {
                initCode.add("emptyArray()")
            } else {
                initCode.add("arrayOf(\n").indent()
                for ((idx, instIdx) in plan.errorContextTables.entries.sortedBy { it.key }) {
                    initCode.add("%T(%L, %L),\n", descType, idx, instIdx)
                }
                initCode.unindent().add(")")
            }
            type.addProperty(
                PropertySpec.builder("ERROR_CONTEXT_TABLES", arrayType, KModifier.PRIVATE)
                    .initializer(initCode.build())
                    .build(),
            )
        }

        private fun emitImportsProperty(type: TypeSpec.Builder) {
            val descType = AotImportDesc::class.asClassName()
            val arrayType = ClassName("kotlin", "Array").parameterizedBy(descType)
            val initCode = CodeBlock.builder()
            if (plan.imports.isEmpty()) {
                initCode.add("emptyArray()")
            } else {
                initCode.add("arrayOf(\n").indent()
                for ((idx, imp) in plan.imports.entries.sortedBy { it.key }) {
                    initCode.add("%T(%L, %S),\n", descType, idx, imp.dispatchKey)
                }
                initCode.unindent().add(")")
            }
            type.addProperty(
                PropertySpec.builder("IMPORTS", arrayType, KModifier.PRIVATE)
                    .initializer(initCode.build())
                    .build(),
            )
        }

        private fun emitRegisterAsyncTrampolinesFun(type: TypeSpec.Builder) {
            val entries = plan.trampolines.entries
                .filter { it.value is BuildTrampoline.Async }
                .sortedBy { it.key }
            val chunkSize = 32
            val chunks = entries.chunked(chunkSize)
            chunks.forEachIndexed { chunkIdx, chunk ->
                val b = CodeBlock.builder()
                for ((idx, rawTr) in chunk) {
                    val tr = rawTr as BuildTrampoline.Async
                    b.add("rt.registerAsyncTrampoline(%L, %T(", idx, AsyncTrampolineSpec::class)
                    b.add("kind = %S", tr.kind)
                    if (tr.instance != null) b.add(", instance = %L", tr.instance)
                    if (tr.table != null) b.add(", table = %L", tr.table)
                    if (tr.options != null) b.add(", options = %L", tr.options)
                    if (tr.async) b.add(", async = true")
                    if (tr.cancellable) b.add(", cancellable = true")
                    if (tr.results != null) {
                        if (tr.results.isEmpty()) {
                            b.add(", results = emptyList()")
                        } else {
                            b.add(", results = listOf(")
                            tr.results.forEachIndexed { rIdx, wt ->
                                if (rIdx > 0) b.add(", ")
                                b.add(funcTypePool.witTypeCode(wt))
                            }
                            b.add(")")
                        }
                    }
                    if (tr.resultsIndex != null) b.add(", resultsIndex = %L", tr.resultsIndex)
                    if (tr.memory != null) b.add(", memory = %L", tr.memory)
                    if (tr.callback != null) b.add(", callback = %L", tr.callback)
                    if (tr.postReturn != null) b.add(", postReturn = %L", tr.postReturn)
                    b.add("))\n")
                }
                type.addFunction(
                    FunSpec.builder("registerAsyncTrampolines_$chunkIdx")
                        .addModifiers(KModifier.PRIVATE)
                        .addParameter("rt", AotComponentBackend::class)
                        .addCode(b.build())
                        .build(),
                )
            }
            val top = CodeBlock.builder()
            for (chunkIdx in chunks.indices) {
                top.addStatement("registerAsyncTrampolines_%L(rt)", chunkIdx)
            }
            type.addFunction(
                FunSpec.builder("registerAsyncTrampolines")
                    .addModifiers(KModifier.PRIVATE)
                    .addParameter("rt", AotComponentBackend::class)
                    .addCode(top.build())
                    .build(),
            )
        }

        private fun buildInstantiateFun(): FunSpec {
            val dispatcherType = WitHostImportDispatcher::class.asTypeName().copy(nullable = true)
            val body = CodeBlock.builder()

            if (plan.usesAsync) {
                body.addStatement(
                    "val rt = %T(imports, RESOURCE_TABLES, usesAsync = true, streamTables = STREAM_TABLES, " +
                        "futureTables = FUTURE_TABLES, errorContextTables = ERROR_CONTEXT_TABLES, importDescs = IMPORTS)",
                    AotComponentBackend::class,
                )
                body.addStatement("registerAsyncTrampolines(rt)")
            } else {
                body.addStatement("val rt = %T(imports, RESOURCE_TABLES)", AotComponentBackend::class)
            }

            for (optIdx in plan.options.keys.sorted()) {
                val opt = plan.options.getValue(optIdx)
                if (plan.usesAsync) {
                    body.addStatement(
                        "val opt_%L = %T(%T.%L, %L, memoryIndex = %L, async = %L)",
                        optIdx,
                        AotCanonOptions::class,
                        StringEncoding::class,
                        opt.stringEncoding.name,
                        opt.instance,
                        opt.memory,
                        opt.async,
                    )
                    body.addStatement("rt.registerOptions(%L, opt_%L)", optIdx, optIdx)
                } else {
                    body.addStatement(
                        "val opt_%L = %T(%T.%L, %L)",
                        optIdx,
                        AotCanonOptions::class,
                        StringEncoding::class,
                        opt.stringEncoding.name,
                        opt.instance,
                    )
                }
            }

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
                body.addStatement("val flags_%L = %T(3)", fIdx, I32Global::class)
            }
            if (needsTaskMayBlock) {
                body.addStatement("val taskMayBlock = %T(%L)", I32Global::class, if (plan.usesAsync) 1 else 0)
            }

            for (em in plan.initializers.filterIsInstance<BuildInitializer.ExtractMemory>().distinctBy { it.index }) {
                body.addStatement("lateinit var mem_%L: %T", em.index, LinearMemory::class)
            }
            for (er in plan.initializers.filterIsInstance<BuildInitializer.ExtractRealloc>().distinctBy { it.index }) {
                body.addStatement("lateinit var realloc_%L: %T", er.index, AotRealloc::class)
            }
            for (ep in plan.initializers.filterIsInstance<BuildInitializer.ExtractPostReturn>().distinctBy { it.index }) {
                body.addStatement("lateinit var postReturn_%L: %T", ep.index, AotPostReturn::class)
            }
            if (plan.usesAsync) {
                for (ec in plan.initializers.filterIsInstance<BuildInitializer.ExtractCallback>().distinctBy { it.index }) {
                    body.addStatement("lateinit var callback_%L: %T", ec.index, AotCoreFunction::class)
                }
            }
            for ((tIdx, tr) in plan.trampolines.entries.sortedBy { it.key }) {
                if (tr is BuildTrampoline.Transcode) {
                    body.addStatement("var transcoder_%L: %T? = null", tIdx, Transcoder::class)
                }
            }

            val instInits = plan.initializers.filterIsInstance<BuildInitializer.Instantiate>().sortedBy { it.instance }
            for (ii in instInits) {
                val instClass = helper.classNameFromInternal(helper.compiled.modules.getValue(ii.module).instanceInternalName)
                body.addStatement("lateinit var inst_%L: %T", ii.instance, instClass)
            }

            body.beginControlFlow("rt.initComponent")
            for (init in plan.initializers) {
                emitInitializer(body, init)
            }
            body.endControlFlow()

            emitSetExports(body)
            body.addStatement("return rt")

            return FunSpec.builder("instantiate")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter(ParameterSpec.builder("imports", dispatcherType).build())
                .returns(ComponentBackend::class)
                .addCode(body.build())
                .build()
        }

        private fun emitInitializer(body: CodeBlock.Builder, init: BuildInitializer) {
            when (init) {
                is BuildInitializer.Instantiate -> helper.emitInstantiateCore(body, init)
                is BuildInitializer.ExtractMemory -> {
                    val exp = helper.memExport(init.export)
                    body.addStatement("mem_%L = inst_%L.%L", init.index, init.export.instance, exp.fieldName)
                    if (plan.usesAsync) {
                        body.addStatement("rt.registerMemory(%L, mem_%L)", init.index, init.index)
                    }
                    for ((optIdx, opt) in plan.options.entries.sortedBy { it.key }) {
                        if (opt.memory == init.index) {
                            body.addStatement("opt_%L.memory = mem_%L", optIdx, init.index)
                        }
                    }
                }
                is BuildInitializer.ExtractRealloc -> {
                    body.beginControlFlow("realloc_%L = %T { oldPtr, oldSize, align, newSize ->", init.index, AotRealloc::class)
                    val params = listOf(CodeBlock.of("oldPtr"), CodeBlock.of("oldSize"), CodeBlock.of("align"), CodeBlock.of("newSize"))
                    helper.emitDefInvocation(
                        out = body,
                        def = init.def,
                        paramExprs = params,
                        paramTypes = AotPlanEmissionHelper.REALLOC_IR_TYPE.params,
                        resultTypes = AotPlanEmissionHelper.REALLOC_IR_TYPE.results,
                        rawArgsVar = null,
                        returnStyle = AotPlanEmissionHelper.ReturnStyle.SCALAR_OR_UNIT,
                    )
                    body.endControlFlow()
                    for ((optIdx, opt) in plan.options.entries.sortedBy { it.key }) {
                        if (opt.realloc == init.index) {
                            body.addStatement("opt_%L.realloc = realloc_%L", optIdx, init.index)
                        }
                    }
                }
                is BuildInitializer.ExtractPostReturn -> {
                    val postReturnParamTypes = helper.defCoreParamTypes(init.def)
                    val paramName = if (postReturnParamTypes.isEmpty()) "_" else "r"
                    body.beginControlFlow("postReturn_%L = %T { %L ->", init.index, AotPostReturn::class, paramName)
                    val params = helper.unpackLongArrayArgs("r", postReturnParamTypes)
                    helper.emitDefInvocation(
                        out = body,
                        def = init.def,
                        paramExprs = params,
                        paramTypes = postReturnParamTypes,
                        resultTypes = emptyList(),
                        rawArgsVar = if (init.def is BuildCoreDef.Trampoline && postReturnParamTypes.isNotEmpty()) "r" else null,
                        returnStyle = AotPlanEmissionHelper.ReturnStyle.UNIT_STMT,
                    )
                    body.endControlFlow()
                    if (plan.usesAsync) {
                        body.addStatement("rt.registerPostReturn(%L, postReturn_%L)", init.index, init.index)
                    }
                    for ((optIdx, opt) in plan.options.entries.sortedBy { it.key }) {
                        if (opt.postReturn == init.index) {
                            body.addStatement("opt_%L.postReturn = postReturn_%L", optIdx, init.index)
                        }
                    }
                }
                is BuildInitializer.ExtractTable -> {
                    val exp = helper.tabExport(init.export)
                    if (plan.usesAsync) {
                        body.addStatement("rt.registerTable(%L, inst_%L.%L)", init.index, init.export.instance, exp.fieldName)
                    }
                }
                is BuildInitializer.Resource -> {
                    val dtor = init.dtor
                    if (dtor != null) {
                        body.beginControlFlow("rt.registerResourceDtor(%L, %T { rep ->", init.resource, AotResourceDtor::class)
                        helper.emitDefInvocation(
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
                    val cbParamTypes = helper.defCoreParamTypes(init.def)
                    val cbResultTypes = helper.defCoreResultTypes(init.def)
                    val paramName = if (cbParamTypes.isEmpty()) "_" else "r"
                    body.beginControlFlow("callback_%L = %T { %L ->", init.index, AotCoreFunction::class, paramName)
                    val params = helper.unpackLongArrayArgs("r", cbParamTypes)
                    helper.emitDefInvocation(
                        out = body,
                        def = init.def,
                        paramExprs = params,
                        paramTypes = cbParamTypes,
                        resultTypes = cbResultTypes,
                        rawArgsVar = if (init.def is BuildCoreDef.Trampoline && cbParamTypes.isNotEmpty()) "r" else null,
                        returnStyle = AotPlanEmissionHelper.ReturnStyle.LONG_ARRAY,
                    )
                    body.endControlFlow()
                    body.addStatement("rt.registerCallback(%L, callback_%L)", init.index, init.index)
                    for ((optIdx, opt) in plan.options.entries.sortedBy { it.key }) {
                        if (opt.callback == init.index) {
                            body.addStatement("opt_%L.callback = callback_%L", optIdx, init.index)
                        }
                    }
                }
            }
        }

        private fun emitSetExports(body: CodeBlock.Builder) {
            if (plan.exports.isEmpty()) {
                body.addStatement("rt.setExports(emptyArray())")
                return
            }
            body.add("rt.setExports(arrayOf(\n").indent()
            for (i in plan.exports.indices) {
                val e = plan.exports[i]
                val ftId = exportFuncTypeIds[i]
                val paramTypes = helper.defCoreParamTypes(e.func)
                val resultTypes = helper.defCoreResultTypes(e.func)
                val needsArgs = e.func is BuildCoreDef.Trampoline || paramTypes.isNotEmpty()
                val lambdaArg = if (needsArgs) "a" else "_"
                body.add("%T(", AotExportDesc::class)
                if (e.instance == null) body.add("null") else body.add("%S", e.instance)
                body.add(", %S, FUNC_TYPES[%L], opt_%L, %T { %L ->\n", e.name, ftId, e.options, AotCoreFunction::class, lambdaArg).indent()
                helper.emitDefInvocation(
                    out = body,
                    def = e.func,
                    paramExprs = helper.unpackLongArrayArgs("a", paramTypes),
                    paramTypes = paramTypes,
                    resultTypes = resultTypes,
                    rawArgsVar = if (needsArgs) "a" else null,
                    returnStyle = AotPlanEmissionHelper.ReturnStyle.LONG_ARRAY,
                )
                body.unindent().add("}, optionsIndex = %L),\n", e.options)
            }
            body.unindent().add("))\n")
        }
    }
}
