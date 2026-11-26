@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.tooling.aot

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.TypeSpec
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
import wasm2kt.runtime.aot.AotExportDesc
import wasm2kt.runtime.aot.AotPostReturn
import wasm2kt.runtime.aot.AotRealloc
import wasm2kt.runtime.aot.AotResourceDtor
import wasm2kt.runtime.aot.I32Global
import wasm2kt.runtime.aot.LinearMemory
import wasm2kt.runtime.canonical.InternalWasm2KtApi
import wasm2kt.runtime.canonical.StringEncoding
import wasm2kt.runtime.canonical.Transcoder
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
            val dispatchKey = plan.imports.getValue(tr.import).dispatchKey
            val ftId = trampolineFuncTypeIds.getValue(trampolineIndex)
            val argsExpr = helper.coreArgsLongArrayExpr(paramExprs, paramTypes, rawArgsVar)
            val callCode = CodeBlock.builder()
                .add("rt.callHost(%S, FUNC_TYPES[%L], opt_%L, ", dispatchKey, ftId, tr.options)
                .add(argsExpr)
                .add(")")
                .build()
            helper.emitLongArrayToReturn(out, callCode, resultTypes, returnStyle)
        }

        fun buildFile(): FileSpec {
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

        private fun buildInstantiateFun(): FunSpec {
            val dispatcherType = WitHostImportDispatcher::class.asTypeName().copy(nullable = true)
            val body = CodeBlock.builder()

            body.addStatement("val rt = %T(imports, RESOURCE_TABLES)", AotComponentBackend::class)

            for (optIdx in plan.options.keys.sorted()) {
                val opt = plan.options.getValue(optIdx)
                body.addStatement(
                    "val opt_%L = %T(%T.%L, %L)",
                    optIdx,
                    AotCanonOptions::class,
                    StringEncoding::class,
                    opt.stringEncoding.name,
                    opt.instance,
                )
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
                body.addStatement("val taskMayBlock = %T(0)", I32Global::class)
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
                    for ((optIdx, opt) in plan.options.entries.sortedBy { it.key }) {
                        if (opt.postReturn == init.index) {
                            body.addStatement("opt_%L.postReturn = postReturn_%L", optIdx, init.index)
                        }
                    }
                }
                is BuildInitializer.ExtractTable -> {
                    helper.tabExport(init.export)
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
                    throw IllegalStateException("Async callback initializer is not supported by the AOT backend")
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
                body.unindent().add("}),\n")
            }
            body.unindent().add("))\n")
        }
    }
}
