@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.tooling.aot

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.FLOAT
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.UNIT
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
import wasm2kt.runtime.aot.AotExportDesc
import wasm2kt.runtime.aot.AotPostReturn
import wasm2kt.runtime.aot.AotRealloc
import wasm2kt.runtime.aot.AotResourceDtor
import wasm2kt.runtime.aot.AotResourceTableDesc
import wasm2kt.runtime.aot.AotSpillFrame
import wasm2kt.runtime.aot.FuncRef
import wasm2kt.runtime.aot.HostTrampoline
import wasm2kt.runtime.aot.I32Global
import wasm2kt.runtime.aot.LinearMemory
import wasm2kt.runtime.canonical.CanonicalAbi
import wasm2kt.runtime.canonical.FuncType
import wasm2kt.runtime.canonical.InternalWasm2KtApi
import wasm2kt.runtime.canonical.StringEncoding
import wasm2kt.runtime.canonical.TranscodeOp
import wasm2kt.runtime.canonical.Transcoder
import wasm2kt.runtime.canonical.WitType
import wasm2kt.tooling.ComponentBuild
import wasm2kt.tooling.ComponentCodegen
import wasm2kt.tooling.Decomposer
import wasm2kt.tooling.ir.IrFuncType
import wasm2kt.tooling.ir.IrMutability
import wasm2kt.tooling.ir.IrValType
import java.nio.file.Path
import java.nio.file.Files

/**
 * Generates static Kotlin wiring (`<Name>Component`) for a component compiled with
 * [AotComponentCompiler], eliminating runtime `plan.json`, `.meta`, Chicory, and
 * `kotlinx.serialization` dependencies (ADR-0023 Phase 1).
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
        WitToKotlinPoetConverter(
            resolve = resolve,
            targetPackage = packageName,
            targetWorldName = worldName,
            emitWasmtimeSessions = false,
            interfaceMappings = interfaceMappings,
            libraryManifests = InterfaceManifest.readAll(libraryClasspath),
        ).generate().forEach { it.writeTo(outputDir) }

        generateComponentOnly(
            outputDir = outputDir,
            componentName = componentName,
            packageName = packageName,
            compiled = compiled,
            internalPackage = internalPackage,
        )
    }

    /** Generates only `<Name>Component.kt` without WIT world bindings (used by raw backend tests). */
    fun generateComponentOnly(
        outputDir: Path,
        componentName: String,
        packageName: String,
        compiled: CompiledAotComponent,
        @Suppress("UNUSED_PARAMETER") internalPackage: String = ComponentBuild.internalPackage(packageName, componentName),
    ) {
        ComponentEmitter(
            componentName = componentName,
            packageName = packageName,
            compiled = compiled,
        ).buildFile().writeTo(outputDir)
    }

    private class ComponentEmitter(
        private val componentName: String,
        private val packageName: String,
        private val compiled: CompiledAotComponent,
    ) {
        private val plan: BuildPlan = compiled.plan
        private val objectName: String = ComponentCodegen.componentObjectName(componentName)
        private val instanceToModule: Map<Int, Int> = plan.initializers
            .filterIsInstance<BuildInitializer.Instantiate>()
            .associate { it.instance to it.module }

        private val moduleIndicesByModule: Map<Int, ModuleExportIndex> = compiled.modules.mapValues { (_, mod) ->
            ModuleExportIndex(mod)
        }

        private val funcTypePool = FuncTypePool()
        private val exportFuncTypeIds: List<Int> = plan.exports.map { funcTypePool.intern(it.type) }
        private val trampolineFuncTypeIds: Map<Int, Int> = buildMap {
            for ((idx, tr) in plan.trampolines) {
                if (tr is BuildTrampoline.LowerImport) {
                    put(idx, funcTypePool.intern(tr.type))
                }
            }
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
            emitResourceTablesProperty(type)
            emitTrapMessageArrays(type)
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

        private fun emitResourceTablesProperty(type: TypeSpec.Builder) {
            val descType = AotResourceTableDesc::class.asClassName()
            val arrayType = ClassName("kotlin", "Array").parameterizedBy(descType)
            val initCode = CodeBlock.builder()
            if (plan.resourceTables.isEmpty()) {
                initCode.add("emptyArray()")
            } else {
                val definedInByResource = LinkedHashMap<Int, Int>()
                for (init in plan.initializers) {
                    if (init is BuildInitializer.Resource) {
                        definedInByResource.putIfAbsent(init.resource, init.instance)
                    }
                }
                initCode.add("arrayOf(\n").indent()
                for (rt in plan.resourceTables.values.sortedBy { it.index }) {
                    val resourceId = rt.resource ?: -1
                    val definedIn = rt.resource?.let { definedInByResource[it] } ?: -1
                    val dropKey = if (rt.imported) rt.import?.let { plan.imports[it]?.resourceDropKey } else null
                    initCode.add(
                        "%T(index = %L, resource = %L, imported = %L, instance = %L, definedInInstance = %L, dropKey = %L),\n",
                        descType,
                        rt.index,
                        resourceId,
                        rt.imported,
                        rt.instance,
                        definedIn,
                        dropKey?.let { CodeBlock.of("%S", it) } ?: CodeBlock.of("null"),
                    )
                }
                initCode.unindent().add(")")
            }
            type.addProperty(
                PropertySpec.builder("RESOURCE_TABLES", arrayType, KModifier.PRIVATE)
                    .initializer(initCode.build())
                    .build(),
            )
        }

        private fun emitTrapMessageArrays(type: TypeSpec.Builder) {
            val nullableStringArray = ClassName("kotlin", "Array").parameterizedBy(String::class.asTypeName().copy(nullable = true))
            for ((idx, tr) in plan.trampolines.entries.sortedBy { it.key }) {
                if (tr !is BuildTrampoline.Trap) continue
                val code = CodeBlock.builder()
                if (tr.messages.isEmpty()) {
                    code.add("emptyArray()")
                } else {
                    code.add("arrayOf(")
                    tr.messages.forEachIndexed { i, msg ->
                        if (i > 0) code.add(", ")
                        if (msg == null) code.add("null") else code.add("%S", msg)
                    }
                    code.add(")")
                }
                type.addProperty(
                    PropertySpec.builder("TRAP_MESSAGES_$idx", nullableStringArray, KModifier.PRIVATE)
                        .initializer(code.build())
                        .build(),
                )
            }
        }

        private fun buildInstantiateFun(): FunSpec {
            val dispatcherType = WitHostImportDispatcher::class.asTypeName().copy(nullable = true)
            val body = CodeBlock.builder()

            body.addStatement("val rt = %T(imports, RESOURCE_TABLES)", AotComponentBackend::class)

            // Canonical options.
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
                body.addStatement("val flags_%L = %T(3)", fIdx, I32Global::class)
            }
            if (needsTaskMayBlock) {
                body.addStatement("val taskMayBlock = %T(0)", I32Global::class)
            }

            // Extracted memories, reallocs, post-returns, and lazy transcoders.
            val extractedMemories = plan.initializers.filterIsInstance<BuildInitializer.ExtractMemory>().distinctBy { it.index }
            for (em in extractedMemories) {
                body.addStatement("lateinit var mem_%L: %T", em.index, LinearMemory::class)
            }
            val extractedReallocs = plan.initializers.filterIsInstance<BuildInitializer.ExtractRealloc>().distinctBy { it.index }
            for (er in extractedReallocs) {
                body.addStatement("lateinit var realloc_%L: %T", er.index, AotRealloc::class)
            }
            val extractedPostReturns = plan.initializers.filterIsInstance<BuildInitializer.ExtractPostReturn>().distinctBy { it.index }
            for (ep in extractedPostReturns) {
                body.addStatement("lateinit var postReturn_%L: %T", ep.index, AotPostReturn::class)
            }
            for ((tIdx, tr) in plan.trampolines.entries.sortedBy { it.key }) {
                if (tr is BuildTrampoline.Transcode) {
                    body.addStatement("var transcoder_%L: %T? = null", tIdx, Transcoder::class)
                }
            }

            // Core module instances declared upfront so forward-referenced dtors compile cleanly.
            val instInits = plan.initializers.filterIsInstance<BuildInitializer.Instantiate>().sortedBy { it.instance }
            for (ii in instInits) {
                val instClass = classNameFromInternal(compiled.modules.getValue(ii.module).instanceInternalName)
                body.addStatement("lateinit var inst_%L: %T", ii.instance, instClass)
            }

            // Initialize in exact plan.initializers order inside ComponentGuard.guest.
            body.beginControlFlow("rt.initComponent")
            for (init in plan.initializers) {
                emitInitializer(body, init)
            }
            body.endControlFlow()

            // Register exported component functions.
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
                is BuildInitializer.Instantiate -> emitInstantiateCore(body, init)
                is BuildInitializer.ExtractMemory -> {
                    val exp = memExport(init.export)
                    body.addStatement("mem_%L = inst_%L.%L", init.index, init.export.instance, exp.fieldName)
                    for ((optIdx, opt) in plan.options.entries.sortedBy { it.key }) {
                        if (opt.memory == init.index) {
                            body.addStatement("opt_%L.memory = mem_%L", optIdx, init.index)
                        }
                    }
                }
                is BuildInitializer.ExtractRealloc -> {
                    body.beginControlFlow("realloc_%L = %T { oldPtr, oldSize, align, newSize ->", init.index, AotRealloc::class)
                    val params = listOf(
                        CodeBlock.of("oldPtr"),
                        CodeBlock.of("oldSize"),
                        CodeBlock.of("align"),
                        CodeBlock.of("newSize"),
                    )
                    emitDefInvocation(
                        out = body,
                        def = init.def,
                        paramExprs = params,
                        paramTypes = REALLOC_IR_TYPE.params,
                        resultTypes = REALLOC_IR_TYPE.results,
                        rawArgsVar = null,
                        returnStyle = ReturnStyle.SCALAR_OR_UNIT,
                    )
                    body.endControlFlow()
                    for ((optIdx, opt) in plan.options.entries.sortedBy { it.key }) {
                        if (opt.realloc == init.index) {
                            body.addStatement("opt_%L.realloc = realloc_%L", optIdx, init.index)
                        }
                    }
                }
                is BuildInitializer.ExtractPostReturn -> {
                    val postReturnParamTypes = postReturnIrType(init.def)
                    val paramName = if (postReturnParamTypes.isEmpty()) "_" else "r"
                    body.beginControlFlow("postReturn_%L = %T { %L ->", init.index, AotPostReturn::class, paramName)
                    val params = unpackLongArrayArgs("r", postReturnParamTypes)
                    emitDefInvocation(
                        out = body,
                        def = init.def,
                        paramExprs = params,
                        paramTypes = postReturnParamTypes,
                        resultTypes = emptyList(),
                        rawArgsVar = if (init.def is BuildCoreDef.Trampoline && postReturnParamTypes.isNotEmpty()) "r" else null,
                        returnStyle = ReturnStyle.UNIT_STMT,
                    )
                    body.endControlFlow()
                    for ((optIdx, opt) in plan.options.entries.sortedBy { it.key }) {
                        if (opt.postReturn == init.index) {
                            body.addStatement("opt_%L.postReturn = postReturn_%L", optIdx, init.index)
                        }
                    }
                }
                is BuildInitializer.ExtractTable -> {
                    tabExport(init.export)
                }
                is BuildInitializer.Resource -> {
                    val dtor = init.dtor
                    if (dtor != null) {
                        body.beginControlFlow("rt.registerResourceDtor(%L, %T { rep ->", init.resource, AotResourceDtor::class)
                        emitDefInvocation(
                            out = body,
                            def = dtor,
                            paramExprs = listOf(CodeBlock.of("rep")),
                            paramTypes = DTOR_IR_TYPE.params,
                            resultTypes = emptyList(),
                            rawArgsVar = null,
                            returnStyle = ReturnStyle.UNIT_STMT,
                        )
                        body.unindent().addStatement("})")
                    }
                }
                is BuildInitializer.ExtractCallback -> {
                    throw IllegalStateException("Async callback initializer is not supported by the AOT backend")
                }
            }
        }

        private fun emitInstantiateCore(body: CodeBlock.Builder, init: BuildInitializer.Instantiate) {
            val ir = compiled.irModules.getValue(init.module)
            val compiledMod = compiled.modules.getValue(init.module)
            val planMod = plan.modules.first { it.index == init.module }
            val instClass = classNameFromInternal(compiledMod.instanceInternalName)

            val argQueues = buildInstantiateArgQueues(planMod, init)
            val ctorArgs = ArrayList<CodeBlock>()

            // 1. Imported memories.
            for (m in ir.importedMemories) {
                val arg = popInstantiateArg(argQueues, "memory", m.module, m.name, init)
                val exportRef = (arg.def as? BuildCoreDef.Export)?.ref
                    ?: error("Core module ${init.module} memory import ${m.module}::${m.name} expects Export, got ${arg.def}")
                val exp = memExport(exportRef)
                ctorArgs += CodeBlock.of("inst_%L.%L", exportRef.instance, exp.fieldName)
            }

            // 2. Imported tables.
            for (t in ir.importedTables) {
                val arg = popInstantiateArg(argQueues, "table", t.module, t.name, init)
                val exportRef = (arg.def as? BuildCoreDef.Export)?.ref
                    ?: error("Core module ${init.module} table import ${t.module}::${t.name} expects Export, got ${arg.def}")
                val exp = tabExport(exportRef)
                ctorArgs += CodeBlock.of("inst_%L.%L", exportRef.instance, exp.fieldName)
            }

            // 3. Imported globals.
            for (g in ir.importedGlobals) {
                val arg = popInstantiateArg(argQueues, "global", g.module, g.name, init)
                val expectsVar = g.globalType.mutability == IrMutability.VAR
                ctorArgs += when (val d = arg.def) {
                    is BuildCoreDef.InstanceFlags -> if (expectsVar) {
                        CodeBlock.of("flags_%L", d.index)
                    } else {
                        CodeBlock.of("flags_%L.value", d.index)
                    }
                    is BuildCoreDef.TaskMayBlock -> if (expectsVar) {
                        CodeBlock.of("taskMayBlock")
                    } else {
                        CodeBlock.of("taskMayBlock.value")
                    }
                    is BuildCoreDef.Export -> {
                        val exp = globExport(d.ref)
                        if (!expectsVar && exp.isBoxedCell) {
                            CodeBlock.of("inst_%L.%L.value", d.ref.instance, exp.fieldName)
                        } else {
                            CodeBlock.of("inst_%L.%L", d.ref.instance, exp.fieldName)
                        }
                    }
                    else -> error("Core module ${init.module} global import ${g.module}::${g.name} has unsupported def $d")
                }
            }

            // 4 & 5. Imports interface + importedFuncRefs array when the module imports functions.
            if (ir.importedFunctions.isNotEmpty()) {
                val funcArgs = ir.importedFunctions.map { f ->
                    f to popInstantiateArg(argQueues, "func", f.module, f.name, init)
                }
                val importsIfaceClass = classNameFromInternal(compiledMod.importsInterfaceInternalName!!)

                // 4. object : CoreMInstance.Imports { ... }
                val importsObj = CodeBlock.builder()
                importsObj.add("object : %T {\n", importsIfaceClass).indent()
                for ((imp, arg) in funcArgs) {
                    val ft = ir.types[imp.typeIndex]
                    val directCount = AotNames.countDirectParams(ft.params)
                    val returnType = kotlinResultType(compiledMod.instanceInternalName, ft.results)
                    importsObj.add("override fun %L(", AotNames.importBridgeName(imp.funcIndex))
                    var sigIdx = 0
                    for (p in 0 until directCount) {
                        if (sigIdx > 0) importsObj.add(", ")
                        importsObj.add("p%L: %T", p, kotlinTypeForVal(ft.params[p]))
                        sigIdx++
                    }
                    if (directCount < ft.params.size) {
                        if (sigIdx > 0) importsObj.add(", ")
                        importsObj.add("spill: %T", AotSpillFrame::class)
                    }
                    importsObj.add(")")
                    if (returnType != UNIT) {
                        importsObj.add(": %T", returnType)
                    }
                    importsObj.add(" {\n").indent()

                    // Materialize all overflow arguments from spill before calling any target.
                    val paramExprs = ArrayList<CodeBlock>(ft.params.size)
                    for (p in 0 until directCount) {
                        paramExprs += CodeBlock.of("p%L", p)
                    }
                    if (directCount < ft.params.size) {
                        val spillCounter = SpillSlotCounter()
                        for (p in directCount until ft.params.size) {
                            val vt = ft.params[p]
                            val sIdx = spillCounter.next(vt)
                            val opVar = "op_$p"
                            val field = spillArrayField(vt)
                            when (vt) {
                                IrValType.FUNCREF -> importsObj.addStatement("val %L = spill.%L[%L] as %T?", opVar, field, sIdx, FuncRef::class)
                                IrValType.EXTERNREF -> importsObj.addStatement("val %L = spill.%L[%L]", opVar, field, sIdx)
                                else -> importsObj.addStatement("val %L = spill.%L[%L]", opVar, field, sIdx)
                            }
                            paramExprs += CodeBlock.of("%L", opVar)
                        }
                    }

                    val dstMvClass = if (ft.results.size >= 2) {
                        classNameFromInternal(AotNames.multiValueInternalName(compiledMod.instanceInternalName, ft.results))
                    } else {
                        null
                    }
                    emitDefInvocation(
                        out = importsObj,
                        def = arg.def,
                        paramExprs = paramExprs,
                        paramTypes = ft.params,
                        resultTypes = ft.results,
                        rawArgsVar = null,
                        returnStyle = ReturnStyle.DIRECT(dstMvClass),
                    )
                    importsObj.unindent().add("}\n")
                }
                importsObj.unindent().add("}")
                ctorArgs += importsObj.build()

                // 5. Trailing Array<FuncRef> for cross-module funcref / call_indirect.
                val funcRefArray = CodeBlock.builder()
                funcRefArray.add("arrayOf<%T>(\n", FuncRef::class).indent()
                for ((imp, arg) in funcArgs) {
                    val ft = ir.types[imp.typeIndex]
                    val sig = AotNames.canonicalSignature(ft)
                    when (val d = arg.def) {
                        is BuildCoreDef.Export -> {
                            val target = funcExport(d.ref)
                            funcRefArray.add(
                                "%T.core(inst_%L, %L, %S),\n",
                                FuncRef::class,
                                d.ref.instance,
                                target.index,
                                sig,
                            )
                        }
                        else -> {
                            val lambdaArg = if (ft.params.isEmpty()) "_" else "a"
                            funcRefArray.add("%T.host(%S, %T { %L ->\n", FuncRef::class, sig, HostTrampoline::class, lambdaArg).indent()
                            emitDefInvocation(
                                out = funcRefArray,
                                def = d,
                                paramExprs = unpackLongArrayArgs("a", ft.params),
                                paramTypes = ft.params,
                                resultTypes = ft.results,
                                rawArgsVar = if (ft.params.isNotEmpty()) "a" else null,
                                returnStyle = ReturnStyle.LONG_ARRAY,
                            )
                            funcRefArray.unindent().add("}),\n")
                        }
                    }
                }
                funcRefArray.unindent().add(")")
                ctorArgs += funcRefArray.build()
            }

            if (ctorArgs.isEmpty()) {
                body.addStatement("inst_%L = %T()", init.instance, instClass)
            } else {
                body.add("inst_%L = %T(\n", init.instance, instClass).indent()
                ctorArgs.forEach { argCode ->
                    body.add(argCode).add(",\n")
                }
                body.unindent().add(")\n")
            }
        }

        private fun buildInstantiateArgQueues(
            planMod: BuildPlanModule,
            init: BuildInitializer.Instantiate,
        ): HashMap<Triple<String, String, String>, ArrayDeque<BuildInstantiateArg>> {
            val queues = HashMap<Triple<String, String, String>, ArrayDeque<BuildInstantiateArg>>()
            val alignedWithPlanImports = planMod.imports.size == init.args.size &&
                planMod.imports.indices.all {
                    planMod.imports[it].module == init.args[it].module && planMod.imports[it].name == init.args[it].name
                }
            for (i in init.args.indices) {
                val arg = init.args[i]
                val kind = if (alignedWithPlanImports) {
                    planMod.imports[i].kind
                } else {
                    planMod.imports.first { it.module == arg.module && it.name == arg.name }.kind
                }
                queues.getOrPut(Triple(kind, arg.module, arg.name)) { ArrayDeque() }.addLast(arg)
            }
            return queues
        }

        private fun popInstantiateArg(
            queues: HashMap<Triple<String, String, String>, ArrayDeque<BuildInstantiateArg>>,
            kind: String,
            module: String,
            name: String,
            init: BuildInitializer.Instantiate,
        ): BuildInstantiateArg =
            queues[Triple(kind, module, name)]?.removeFirstOrNull()
                ?: error("Missing instantiate arg ($kind $module::$name) for core instance ${init.instance} (module ${init.module})")

        private fun emitSetExports(body: CodeBlock.Builder) {
            if (plan.exports.isEmpty()) {
                body.addStatement("rt.setExports(emptyArray())")
                return
            }
            body.add("rt.setExports(arrayOf(\n").indent()
            for (i in plan.exports.indices) {
                val e = plan.exports[i]
                val ftId = exportFuncTypeIds[i]
                val paramTypes = exportCoreParamTypes(e.func)
                val resultTypes = exportCoreResultTypes(e.func)
                val needsArgs = e.func is BuildCoreDef.Trampoline || paramTypes.isNotEmpty()
                val lambdaArg = if (needsArgs) "a" else "_"
                body.add("%T(", AotExportDesc::class)
                if (e.instance == null) body.add("null") else body.add("%S", e.instance)
                body.add(", %S, FUNC_TYPES[%L], opt_%L, %T { %L ->\n", e.name, ftId, e.options, AotCoreFunction::class, lambdaArg).indent()
                emitDefInvocation(
                    out = body,
                    def = e.func,
                    paramExprs = unpackLongArrayArgs("a", paramTypes),
                    paramTypes = paramTypes,
                    resultTypes = resultTypes,
                    rawArgsVar = if (needsArgs) "a" else null,
                    returnStyle = ReturnStyle.LONG_ARRAY,
                )
                body.unindent().add("}),\n")
            }
            body.unindent().add("))\n")
        }

        private enum class ReturnKind { DIRECT, LONG_ARRAY, UNIT_STMT, SCALAR_OR_UNIT }

        private class ReturnStyle private constructor(
            val kind: ReturnKind,
            val dstMultiValueClass: ClassName? = null,
        ) {
            companion object {
                val LONG_ARRAY = ReturnStyle(ReturnKind.LONG_ARRAY)
                val UNIT_STMT = ReturnStyle(ReturnKind.UNIT_STMT)
                val SCALAR_OR_UNIT = ReturnStyle(ReturnKind.SCALAR_OR_UNIT)
                fun DIRECT(dstMultiValueClass: ClassName?) = ReturnStyle(ReturnKind.DIRECT, dstMultiValueClass)
            }
        }

        private fun emitDefInvocation(
            out: CodeBlock.Builder,
            def: BuildCoreDef,
            paramExprs: List<CodeBlock>,
            paramTypes: List<IrValType>,
            resultTypes: List<IrValType>,
            rawArgsVar: String?,
            returnStyle: ReturnStyle,
        ) {
            when (def) {
                is BuildCoreDef.Export -> emitExportDefCall(out, def, paramExprs, returnStyle)
                is BuildCoreDef.Trampoline -> emitTrampolineDefCall(out, def.index, paramExprs, paramTypes, resultTypes, rawArgsVar, returnStyle)
                is BuildCoreDef.Intrinsic -> {
                    out.addStatement("rt.unsupportedTrampoline(%S)", "intrinsic ${def.name}")
                }
                is BuildCoreDef.InstanceFlags,
                is BuildCoreDef.TaskMayBlock,
                -> {
                    error("Non-callable core definition $def used as function")
                }
            }
        }

        private fun emitExportDefCall(
            out: CodeBlock.Builder,
            def: BuildCoreDef.Export,
            paramExprs: List<CodeBlock>,
            returnStyle: ReturnStyle,
        ) {
            val exp = funcExport(def.ref)
            val target = exp.target
            val ft = target.funcType
            val targetInstVar = "inst_${def.ref.instance}"
            val directCount = AotNames.countDirectParams(ft.params)

            if (target.overflowParamCount > 0) {
                val spillCounter = SpillSlotCounter()
                for (p in directCount until ft.params.size) {
                    val vt = ft.params[p]
                    val sIdx = spillCounter.next(vt)
                    out.add("%L.spill.%L[%L] = ", targetInstVar, spillArrayField(vt), sIdx)
                        .add(paramExprs[p])
                        .add("\n")
                }
            }

            val ownerClass = classNameFromInternal(target.ownerInternalName)
            val call = CodeBlock.builder()
            call.add("%T.%L(", ownerClass, target.methodName)
            for (p in 0 until directCount) {
                call.add(paramExprs[p]).add(", ")
            }
            call.add("%L)", targetInstVar)
            val callCode = call.build()

            when (returnStyle.kind) {
                ReturnKind.UNIT_STMT, ReturnKind.SCALAR_OR_UNIT -> {
                    out.add(callCode).add("\n")
                }
                ReturnKind.LONG_ARRAY -> {
                    when (ft.results.size) {
                        0 -> {
                            out.add(callCode).add("\n")
                            out.addStatement("null")
                        }
                        1 -> {
                            out.add("longArrayOf(").add(packToLong(callCode, ft.results[0])).add(")\n")
                        }
                        else -> {
                            out.add("val mv = ").add(callCode).add("\n")
                            out.add("longArrayOf(")
                            ft.results.forEachIndexed { rIdx, vt ->
                                if (rIdx > 0) out.add(", ")
                                out.add(packToLong(CodeBlock.of("mv.v%L", rIdx), vt))
                            }
                            out.add(")\n")
                        }
                    }
                }
                ReturnKind.DIRECT -> {
                    when (ft.results.size) {
                        0 -> out.add(callCode).add("\n")
                        1 -> out.add("return ").add(callCode).add("\n")
                        else -> {
                            val dstMvClass = returnStyle.dstMultiValueClass!!
                            val srcMvClass = classNameFromInternal(target.multiValueClassInternalName!!)
                            if (srcMvClass == dstMvClass) {
                                out.add("return ").add(callCode).add("\n")
                            } else {
                                out.add("val mv = ").add(callCode).add("\n")
                                out.addStatement("val res = %T()", dstMvClass)
                                for (rIdx in ft.results.indices) {
                                    out.addStatement("res.v%L = mv.v%L", rIdx, rIdx)
                                }
                                out.addStatement("return res")
                            }
                        }
                    }
                }
            }
        }

        private fun emitTrampolineDefCall(
            out: CodeBlock.Builder,
            trampolineIndex: Int,
            paramExprs: List<CodeBlock>,
            paramTypes: List<IrValType>,
            resultTypes: List<IrValType>,
            rawArgsVar: String?,
            returnStyle: ReturnStyle,
        ) {
            val tr = plan.trampolines[trampolineIndex] ?: error("Unknown trampoline $trampolineIndex")
            when (tr) {
                is BuildTrampoline.LowerImport -> {
                    val dispatchKey = plan.imports.getValue(tr.import).dispatchKey
                    val ftId = trampolineFuncTypeIds.getValue(trampolineIndex)
                    val argsExpr = coreArgsLongArrayExpr(paramExprs, paramTypes, rawArgsVar)
                    val callCode = CodeBlock.builder()
                        .add("rt.callHost(%S, FUNC_TYPES[%L], opt_%L, ", dispatchKey, ftId, tr.options)
                        .add(argsExpr)
                        .add(")")
                        .build()
                    emitLongArrayToReturn(out, callCode, resultTypes, returnStyle)
                }
                is BuildTrampoline.ResourceNew -> {
                    val callCode = CodeBlock.builder()
                        .add("rt.resourceNew(%L, ", tr.table)
                        .add(paramExprs[0])
                        .add(")")
                        .build()
                    emitIntToReturn(out, callCode, returnStyle)
                }
                is BuildTrampoline.ResourceRep -> {
                    val callCode = CodeBlock.builder()
                        .add("rt.resourceRep(%L, ", tr.table)
                        .add(paramExprs[0])
                        .add(")")
                        .build()
                    emitIntToReturn(out, callCode, returnStyle)
                }
                is BuildTrampoline.ResourceDrop -> {
                    out.add("rt.resourceDrop(%L, ", tr.table).add(paramExprs[0]).add(")\n")
                    if (returnStyle.kind == ReturnKind.LONG_ARRAY) out.addStatement("null")
                }
                is BuildTrampoline.Trap -> {
                    out.add("rt.adapterTrap(").add(paramExprs[0]).add(", TRAP_MESSAGES_%L)\n", trampolineIndex)
                }
                is BuildTrampoline.Transcode -> {
                    val tcVar = "tc_$trampolineIndex"
                    out.addStatement(
                        "val %L = transcoder_%L ?: %T(%T.%L, mem_%L, mem_%L).also { transcoder_%L = it }",
                        tcVar,
                        trampolineIndex,
                        Transcoder::class,
                        TranscodeOp::class,
                        tr.op.name,
                        tr.from,
                        tr.to,
                        trampolineIndex,
                    )
                    val argsExpr = coreArgsLongArrayExpr(paramExprs, paramTypes, rawArgsVar)
                    val callCode = CodeBlock.builder().add("%L.apply(", tcVar).add(argsExpr).add(")").build()
                    emitLongArrayToReturn(out, callCode, resultTypes, returnStyle)
                }
                is BuildTrampoline.Unsupported -> {
                    out.addStatement("rt.unsupportedTrampoline(%S)", tr.detail)
                }
                is BuildTrampoline.Async -> {
                    when (tr.kind) {
                        "enter-sync-call" -> {
                            // Phase 1 sync-only ignores (calleeAsync, calleeInstance, ...); Phase 3 (#188) will pass them.
                            out.addStatement("rt.enterSyncCall()")
                            if (returnStyle.kind == ReturnKind.LONG_ARRAY) out.addStatement("null")
                        }
                        "exit-sync-call" -> {
                            out.addStatement("rt.exitSyncCall()")
                            if (returnStyle.kind == ReturnKind.LONG_ARRAY) out.addStatement("null")
                        }
                        "resource-transfer-own" -> {
                            val callCode = CodeBlock.builder()
                                .add("rt.resourceTransferOwn(")
                                .add(paramExprs[0]).add(", ")
                                .add(paramExprs[1]).add(", ")
                                .add(paramExprs[2]).add(")")
                                .build()
                            emitIntToReturn(out, callCode, returnStyle)
                        }
                        "resource-transfer-borrow" -> {
                            val callCode = CodeBlock.builder()
                                .add("rt.resourceTransferBorrow(")
                                .add(paramExprs[0]).add(", ")
                                .add(paramExprs[1]).add(", ")
                                .add(paramExprs[2]).add(")")
                                .build()
                            emitIntToReturn(out, callCode, returnStyle)
                        }
                        else -> out.addStatement("rt.unsupportedTrampoline(%S)", tr.kind)
                    }
                }
            }
        }

        private fun coreArgsLongArrayExpr(
            paramExprs: List<CodeBlock>,
            paramTypes: List<IrValType>,
            rawArgsVar: String?,
        ): CodeBlock {
            if (paramTypes.isEmpty()) return CodeBlock.of("%T.EMPTY_LONG_ARRAY", CanonicalAbi::class)
            if (rawArgsVar != null) return CodeBlock.of("%L", rawArgsVar)
            val b = CodeBlock.builder()
            b.add("longArrayOf(")
            paramTypes.forEachIndexed { i, vt ->
                if (i > 0) b.add(", ")
                b.add(packToLong(paramExprs[i], vt))
            }
            b.add(")")
            return b.build()
        }

        private fun emitIntToReturn(out: CodeBlock.Builder, intCall: CodeBlock, returnStyle: ReturnStyle) {
            when (returnStyle.kind) {
                ReturnKind.DIRECT -> out.add("return ").add(intCall).add("\n")
                ReturnKind.SCALAR_OR_UNIT -> out.add(intCall).add("\n")
                ReturnKind.UNIT_STMT -> out.add(intCall).add("\n")
                ReturnKind.LONG_ARRAY -> out.add("longArrayOf((").add(intCall).add(").toLong())\n")
            }
        }

        private fun emitLongArrayToReturn(
            out: CodeBlock.Builder,
            callCode: CodeBlock,
            resultTypes: List<IrValType>,
            returnStyle: ReturnStyle,
        ) {
            when (returnStyle.kind) {
                ReturnKind.LONG_ARRAY -> {
                    out.add(callCode).add("\n")
                }
                ReturnKind.UNIT_STMT -> {
                    out.add(callCode).add("\n")
                }
                ReturnKind.SCALAR_OR_UNIT -> {
                    when (resultTypes.size) {
                        0 -> out.add(callCode).add("\n")
                        1 -> {
                            out.add("val raw = ").add(callCode).add("!!\n")
                            out.add(unpackFromLong("raw[0]", resultTypes[0])).add("\n")
                        }
                        else -> error("Scalar return expected, got ${resultTypes.size} results")
                    }
                }
                ReturnKind.DIRECT -> {
                    when (resultTypes.size) {
                        0 -> out.add(callCode).add("\n")
                        1 -> {
                            out.add("val raw = ").add(callCode).add("!!\n")
                            out.add("return ").add(unpackFromLong("raw[0]", resultTypes[0])).add("\n")
                        }
                        else -> {
                            val dstMvClass = returnStyle.dstMultiValueClass!!
                            out.add("val raw = ").add(callCode).add("!!\n")
                            out.addStatement("val res = %T()", dstMvClass)
                            for (rIdx in resultTypes.indices) {
                                out.add("res.v%L = ", rIdx)
                                    .add(unpackFromLong("raw[$rIdx]", resultTypes[rIdx]))
                                    .add("\n")
                            }
                            out.addStatement("return res")
                        }
                    }
                }
            }
        }

        private fun exportCoreParamTypes(def: BuildCoreDef): List<IrValType> = when (def) {
            is BuildCoreDef.Export -> funcExport(def.ref).target.funcType.params
            is BuildCoreDef.Trampoline -> trampolineParamTypes(def.index)
            else -> emptyList()
        }

        private fun exportCoreResultTypes(def: BuildCoreDef): List<IrValType> = when (def) {
            is BuildCoreDef.Export -> funcExport(def.ref).target.funcType.results
            is BuildCoreDef.Trampoline -> trampolineResultTypes(def.index)
            else -> emptyList()
        }

        private fun postReturnIrType(def: BuildCoreDef): List<IrValType> = when (def) {
            is BuildCoreDef.Export -> funcExport(def.ref).target.funcType.params
            is BuildCoreDef.Trampoline -> trampolineParamTypes(def.index)
            else -> emptyList()
        }

        private fun trampolineParamTypes(trampolineIndex: Int): List<IrValType> =
            when (val tr = plan.trampolines.getValue(trampolineIndex)) {
                is BuildTrampoline.LowerImport -> {
                    val flatParams = if (tr.type.flatParamCount > CanonicalAbi.MAX_FLAT_PARAMS) {
                        listOf(IrValType.I32)
                    } else {
                        tr.type.paramTypes.flatMap { it.flat }.map(::coreTypeToIrValType)
                    }
                    if (tr.type.flatResultCount > CanonicalAbi.MAX_FLAT_RESULTS) {
                        flatParams + IrValType.I32
                    } else {
                        flatParams
                    }
                }
                is BuildTrampoline.ResourceNew -> listOf(IrValType.I32)
                is BuildTrampoline.ResourceRep -> listOf(IrValType.I32)
                is BuildTrampoline.ResourceDrop -> listOf(IrValType.I32)
                is BuildTrampoline.Trap -> listOf(IrValType.I32)
                is BuildTrampoline.Async -> when (tr.kind) {
                    "enter-sync-call" -> listOf(IrValType.I32, IrValType.I32, IrValType.I32)
                    "exit-sync-call" -> emptyList()
                    "resource-transfer-own", "resource-transfer-borrow" -> listOf(IrValType.I32, IrValType.I32, IrValType.I32)
                    else -> emptyList()
                }
                is BuildTrampoline.Transcode,
                is BuildTrampoline.Unsupported,
                -> emptyList()
            }

        private fun coreTypeToIrValType(ct: CanonicalAbi.CoreType): IrValType = when (ct) {
            CanonicalAbi.CoreType.I32 -> IrValType.I32
            CanonicalAbi.CoreType.I64 -> IrValType.I64
            CanonicalAbi.CoreType.F32 -> IrValType.F32
            CanonicalAbi.CoreType.F64 -> IrValType.F64
        }

        private fun trampolineResultTypes(trampolineIndex: Int): List<IrValType> =
            when (val tr = plan.trampolines.getValue(trampolineIndex)) {
                is BuildTrampoline.ResourceNew -> listOf(IrValType.I32)
                is BuildTrampoline.ResourceRep -> listOf(IrValType.I32)
                is BuildTrampoline.Async -> when (tr.kind) {
                    "resource-transfer-own", "resource-transfer-borrow" -> listOf(IrValType.I32)
                    else -> emptyList()
                }
                else -> emptyList()
            }

        private fun unpackLongArrayArgs(argsVar: String, types: List<IrValType>): List<CodeBlock> =
            types.mapIndexed { idx, vt -> unpackFromLong("$argsVar[$idx]", vt) }

        private fun unpackFromLong(expr: String, vt: IrValType): CodeBlock = when (vt) {
            IrValType.I32 -> CodeBlock.of("%L.toInt()", expr)
            IrValType.I64 -> CodeBlock.of("%L", expr)
            IrValType.F32 -> CodeBlock.of("java.lang.Float.intBitsToFloat(%L.toInt())", expr)
            IrValType.F64 -> CodeBlock.of("java.lang.Double.longBitsToDouble(%L)", expr)
            IrValType.FUNCREF -> CodeBlock.of(
                "if (%L == 0L) null else throw %T(%S)",
                expr,
                UnsupportedOperationException::class,
                "Cannot marshal non-null funcref across LongArray boundary",
            )
            IrValType.EXTERNREF -> CodeBlock.of(
                "if (%L == 0L) null else throw %T(%S)",
                expr,
                UnsupportedOperationException::class,
                "Cannot marshal non-null externref across LongArray boundary",
            )
        }

        private fun packToLong(expr: CodeBlock, vt: IrValType): CodeBlock = when (vt) {
            IrValType.I32 -> CodeBlock.builder().add("(").add(expr).add(").toLong()").build()
            IrValType.I64 -> expr
            IrValType.F32 -> CodeBlock.builder().add("java.lang.Float.floatToRawIntBits(").add(expr).add(").toLong()").build()
            IrValType.F64 -> CodeBlock.builder().add("java.lang.Double.doubleToRawLongBits(").add(expr).add(")").build()
            IrValType.FUNCREF -> CodeBlock.builder()
                .add("if (").add(expr).add(" == null) 0L else throw %T(%S)", UnsupportedOperationException::class, "Cannot marshal non-null funcref across LongArray boundary")
                .build()
            IrValType.EXTERNREF -> CodeBlock.builder()
                .add("if (").add(expr).add(" == null) 0L else throw %T(%S)", UnsupportedOperationException::class, "Cannot marshal non-null externref across LongArray boundary")
                .build()
        }

        private fun funcExport(ref: BuildCoreExportRef): CompiledExport.Function =
            exportIndex(ref.instance).functions[ref.name]
                ?: error("Core instance ${ref.instance} has no exported function '${ref.name}'")

        private fun memExport(ref: BuildCoreExportRef): CompiledExport.Memory =
            exportIndex(ref.instance).memories[ref.name]
                ?: error("Core instance ${ref.instance} has no exported memory '${ref.name}'")

        private fun tabExport(ref: BuildCoreExportRef): CompiledExport.Table =
            exportIndex(ref.instance).tables[ref.name]
                ?: error("Core instance ${ref.instance} has no exported table '${ref.name}'")

        private fun globExport(ref: BuildCoreExportRef): CompiledExport.Global =
            exportIndex(ref.instance).globals[ref.name]
                ?: error("Core instance ${ref.instance} has no exported global '${ref.name}'")

        private fun exportIndex(instance: Int): ModuleExportIndex {
            val moduleIndex = instanceToModule[instance]
                ?: error("Core instance $instance is not instantiated by plan initializers")
            return moduleIndicesByModule.getValue(moduleIndex)
        }

        private fun kotlinTypeForVal(vt: IrValType): TypeName = when (vt) {
            IrValType.I32 -> INT
            IrValType.I64 -> LONG
            IrValType.F32 -> FLOAT
            IrValType.F64 -> DOUBLE
            IrValType.FUNCREF -> FuncRef::class.asTypeName().copy(nullable = true)
            IrValType.EXTERNREF -> Any::class.asTypeName().copy(nullable = true)
        }

        private fun kotlinResultType(instanceInternalName: String, results: List<IrValType>): TypeName = when (results.size) {
            0 -> UNIT
            1 -> kotlinTypeForVal(results[0])
            else -> classNameFromInternal(AotNames.multiValueInternalName(instanceInternalName, results))
        }

        private fun spillArrayField(vt: IrValType): String = when (vt) {
            IrValType.I32 -> "i32"
            IrValType.I64 -> "i64"
            IrValType.F32 -> "f32"
            IrValType.F64 -> "f64"
            IrValType.FUNCREF, IrValType.EXTERNREF -> "refs"
        }

        private fun classNameFromInternal(internalName: String): ClassName {
            val lastSlash = internalName.lastIndexOf('/')
            val pkg = if (lastSlash >= 0) internalName.substring(0, lastSlash).replace('/', '.') else ""
            val simple = if (lastSlash >= 0) internalName.substring(lastSlash + 1) else internalName
            val parts = simple.split('$')
            return ClassName(pkg, parts.first(), *parts.drop(1).toTypedArray())
        }
    }

    private class ModuleExportIndex(mod: CompiledAotModule) {
        val functions = LinkedHashMap<String, CompiledExport.Function>()
        val memories = LinkedHashMap<String, CompiledExport.Memory>()
        val tables = LinkedHashMap<String, CompiledExport.Table>()
        val globals = LinkedHashMap<String, CompiledExport.Global>()

        init {
            for (exp in mod.exports) {
                when (exp) {
                    is CompiledExport.Function -> functions[exp.name] = exp
                    is CompiledExport.Memory -> memories[exp.name] = exp
                    is CompiledExport.Table -> tables[exp.name] = exp
                    is CompiledExport.Global -> globals[exp.name] = exp
                }
            }
        }
    }

    private class SpillSlotCounter {
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

    /**
     * Deduplicates [FuncType] and reused compound [WitType] subtrees and chunks `FUNC_TYPES`
     * initialization into small helper methods so `<clinit>` stays well below the 64 KB JVM ceiling.
     */
    private class FuncTypePool {
        private val indexByKey = LinkedHashMap<String, Int>()
        private val entries = ArrayList<FuncType>()

        fun intern(type: FuncType): Int {
            val key = funcTypeKey(type)
            return indexByKey.getOrPut(key) {
                val id = entries.size
                entries.add(type)
                id
            }
        }

        fun emitInto(type: TypeSpec.Builder) {
            val funcTypeClass = FuncType::class.asClassName()
            val arrayType = ClassName("kotlin", "Array").parameterizedBy(funcTypeClass)
            if (entries.isEmpty()) {
                type.addProperty(
                    PropertySpec.builder("FUNC_TYPES", arrayType, KModifier.PRIVATE)
                        .initializer("emptyArray()")
                        .build(),
                )
                return
            }

            // Count compound WitType occurrences bottom-up and extract helpers for shared subtrees.
            val counts = LinkedHashMap<String, Int>()
            val repByKey = LinkedHashMap<String, WitType>()
            val order = ArrayList<String>()
            fun countType(t: WitType) {
                if (!isCompound(t)) return
                val k = witTypeKey(t)
                val prev = counts[k] ?: 0
                counts[k] = prev + 1
                if (prev == 0) {
                    when (t) {
                        is WitType.Record -> t.fields.forEach { countType(it.second) }
                        is WitType.Tuple -> t.types.forEach { countType(it) }
                        is WitType.ListOf -> countType(t.element)
                        is WitType.Variant -> t.cases.forEach { it.second?.let(::countType) }
                        else -> {}
                    }
                    repByKey[k] = t
                    order.add(k)
                }
            }
            for (ft in entries) {
                ft.params.forEach { countType(it.second) }
                ft.results.forEach { countType(it) }
            }

            val helperNameByKey = LinkedHashMap<String, String>()
            var nextHelper = 0
            for (k in order) {
                if ((counts[k] ?: 0) >= 2) {
                    helperNameByKey[k] = "wt_${nextHelper++}"
                }
            }

            // Emit shared WitType builders in bottom-up topological order.
            for (k in order) {
                val hName = helperNameByKey[k] ?: continue
                val wt = repByKey.getValue(k)
                val expr = emitWitTypeExpr(wt, helperNameByKey, allowHelperForRoot = false)
                type.addFunction(
                    FunSpec.builder(hName)
                        .addModifiers(KModifier.PRIVATE)
                        .returns(WitType::class)
                        .addCode(CodeBlock.builder().add("return ").add(expr).add("\n").build())
                        .build(),
                )
            }

            val chunkSize = 32
            val chunks = entries.chunked(chunkSize)
            val nullableArrayType = ClassName("kotlin", "Array").parameterizedBy(funcTypeClass.copy(nullable = true))

            chunks.forEachIndexed { chunkIdx, chunk ->
                val baseIdx = chunkIdx * chunkSize
                val fnBody = CodeBlock.builder()
                chunk.forEachIndexed { offset, ft ->
                    fnBody.add("out[%L] = ", baseIdx + offset)
                        .add(emitFuncTypeExpr(ft, helperNameByKey))
                        .add("\n")
                }
                type.addFunction(
                    FunSpec.builder("initFuncTypes_$chunkIdx")
                        .addModifiers(KModifier.PRIVATE)
                        .addParameter("out", nullableArrayType)
                        .addCode(fnBody.build())
                        .build(),
                )
            }

            val buildBody = CodeBlock.builder()
            buildBody.addStatement("val out = arrayOfNulls<%T>(%L)", funcTypeClass, entries.size)
            for (chunkIdx in chunks.indices) {
                buildBody.addStatement("initFuncTypes_%L(out)", chunkIdx)
            }
            buildBody.addStatement("return out as %T", arrayType)
            type.addFunction(
                FunSpec.builder("buildFuncTypes")
                    .addModifiers(KModifier.PRIVATE)
                    .returns(arrayType)
                    .addCode(buildBody.build())
                    .build(),
            )

            type.addProperty(
                PropertySpec.builder("FUNC_TYPES", arrayType, KModifier.PRIVATE)
                    .initializer("buildFuncTypes()")
                    .build(),
            )
        }

        private fun emitFuncTypeExpr(ft: FuncType, helpers: Map<String, String>): CodeBlock {
            val b = CodeBlock.builder()
            b.add("%T(", FuncType::class)
            if (ft.params.isEmpty()) {
                b.add("emptyList(), ")
            } else {
                b.add("listOf(")
                ft.params.forEachIndexed { idx, (name, wt) ->
                    if (idx > 0) b.add(", ")
                    b.add("%S to ", name).add(emitWitTypeExpr(wt, helpers, allowHelperForRoot = true))
                }
                b.add("), ")
            }
            if (ft.results.isEmpty()) {
                b.add("emptyList()")
            } else {
                b.add("listOf(")
                ft.results.forEachIndexed { idx, wt ->
                    if (idx > 0) b.add(", ")
                    b.add(emitWitTypeExpr(wt, helpers, allowHelperForRoot = true))
                }
                b.add(")")
            }
            if (ft.async) b.add(", async = true")
            b.add(")")
            return b.build()
        }

        private fun emitWitTypeExpr(t: WitType, helpers: Map<String, String>, allowHelperForRoot: Boolean): CodeBlock {
            if (allowHelperForRoot && isCompound(t)) {
                helpers[witTypeKey(t)]?.let { h -> return CodeBlock.of("%L()", h) }
            }
            return when (t) {
                WitType.Bool -> CodeBlock.of("%T", WitType.Bool::class)
                WitType.S8 -> CodeBlock.of("%T", WitType.S8::class)
                WitType.U8 -> CodeBlock.of("%T", WitType.U8::class)
                WitType.S16 -> CodeBlock.of("%T", WitType.S16::class)
                WitType.U16 -> CodeBlock.of("%T", WitType.U16::class)
                WitType.S32 -> CodeBlock.of("%T", WitType.S32::class)
                WitType.U32 -> CodeBlock.of("%T", WitType.U32::class)
                WitType.S64 -> CodeBlock.of("%T", WitType.S64::class)
                WitType.U64 -> CodeBlock.of("%T", WitType.U64::class)
                WitType.F32 -> CodeBlock.of("%T", WitType.F32::class)
                WitType.F64 -> CodeBlock.of("%T", WitType.F64::class)
                WitType.Char -> CodeBlock.of("%T", WitType.Char::class)
                WitType.Str -> CodeBlock.of("%T", WitType.Str::class)
                WitType.ErrorContext -> CodeBlock.of("%T", WitType.ErrorContext::class)
                is WitType.Own -> CodeBlock.of("%T(%L)", WitType.Own::class, t.table)
                is WitType.Borrow -> CodeBlock.of("%T(%L)", WitType.Borrow::class, t.table)
                is WitType.Stream -> CodeBlock.of("%T(%L)", WitType.Stream::class, t.table)
                is WitType.Future -> CodeBlock.of("%T(%L)", WitType.Future::class, t.table)
                is WitType.Unsupported -> CodeBlock.of("%T(%S)", WitType.Unsupported::class, t.detail)
                is WitType.ListOf -> CodeBlock.builder()
                    .add("%T(", WitType.ListOf::class)
                    .add(emitWitTypeExpr(t.element, helpers, allowHelperForRoot = true))
                    .add(")")
                    .build()
                is WitType.Tuple -> {
                    val b = CodeBlock.builder().add("%T(", WitType.Tuple::class)
                    if (t.types.isEmpty()) {
                        b.add("emptyList()")
                    } else {
                        b.add("listOf(")
                        t.types.forEachIndexed { idx, et ->
                            if (idx > 0) b.add(", ")
                            b.add(emitWitTypeExpr(et, helpers, allowHelperForRoot = true))
                        }
                        b.add(")")
                    }
                    b.add(")").build()
                }
                is WitType.Flags -> {
                    val b = CodeBlock.builder().add("%T(", WitType.Flags::class)
                    if (t.names.isEmpty()) {
                        b.add("emptyList()")
                    } else {
                        b.add("listOf(")
                        t.names.forEachIndexed { idx, n ->
                            if (idx > 0) b.add(", ")
                            b.add("%S", n)
                        }
                        b.add(")")
                    }
                    b.add(")").build()
                }
                is WitType.Record -> {
                    val b = CodeBlock.builder().add("%T(", WitType.Record::class)
                    if (t.fields.isEmpty()) {
                        b.add("emptyList()")
                    } else {
                        b.add("listOf(")
                        t.fields.forEachIndexed { idx, (fName, fType) ->
                            if (idx > 0) b.add(", ")
                            b.add("%S to ", fName).add(emitWitTypeExpr(fType, helpers, allowHelperForRoot = true))
                        }
                        b.add(")")
                    }
                    b.add(")").build()
                }
                is WitType.Variant -> {
                    val b = CodeBlock.builder()
                        .add("%T(%T.%L, ", WitType.Variant::class, WitType.VariantKind::class, t.kind.name)
                    if (t.cases.isEmpty()) {
                        b.add("emptyList()")
                    } else {
                        b.add("listOf(")
                        t.cases.forEachIndexed { idx, (cName, cType) ->
                            if (idx > 0) b.add(", ")
                            if (cType == null) {
                                b.add("%S to null", cName)
                            } else {
                                b.add("%S to ", cName).add(emitWitTypeExpr(cType, helpers, allowHelperForRoot = true))
                            }
                        }
                        b.add(")")
                    }
                    b.add(")").build()
                }
            }
        }

        private fun isCompound(t: WitType): Boolean = when (t) {
            is WitType.Record,
            is WitType.Tuple,
            is WitType.ListOf,
            is WitType.Flags,
            is WitType.Variant,
            -> true
            else -> false
        }

        private fun funcTypeKey(ft: FuncType): String = buildString {
            append('(')
            ft.params.forEachIndexed { i, (n, wt) ->
                if (i > 0) append(',')
                append(n.length).append(':').append(n).append('=').append(witTypeKey(wt))
            }
            append(")->(")
            ft.results.forEachIndexed { i, wt ->
                if (i > 0) append(',')
                append(witTypeKey(wt))
            }
            append(')')
            if (ft.async) append(":async")
        }

        private fun witTypeKey(t: WitType): String = when (t) {
            WitType.Bool -> "bool"
            WitType.S8 -> "s8"
            WitType.U8 -> "u8"
            WitType.S16 -> "s16"
            WitType.U16 -> "u16"
            WitType.S32 -> "s32"
            WitType.U32 -> "u32"
            WitType.S64 -> "s64"
            WitType.U64 -> "u64"
            WitType.F32 -> "f32"
            WitType.F64 -> "f64"
            WitType.Char -> "char"
            WitType.Str -> "str"
            WitType.ErrorContext -> "ec"
            is WitType.Own -> "own(${t.table})"
            is WitType.Borrow -> "borrow(${t.table})"
            is WitType.Stream -> "stream(${t.table})"
            is WitType.Future -> "future(${t.table})"
            is WitType.Unsupported -> "unsup(${t.detail.length}:${t.detail})"
            is WitType.ListOf -> "list(${witTypeKey(t.element)})"
            is WitType.Tuple -> t.types.joinToString(",", prefix = "tuple(", postfix = ")", transform = ::witTypeKey)
            is WitType.Flags -> t.names.joinToString(",", prefix = "flags(", postfix = ")") { "${it.length}:$it" }
            is WitType.Record -> t.fields.joinToString(",", prefix = "rec(", postfix = ")") { (n, ft) ->
                "${n.length}:$n:${witTypeKey(ft)}"
            }
            is WitType.Variant -> t.cases.joinToString(",", prefix = "var(${t.kind.name},", postfix = ")") { (n, ct) ->
                "${n.length}:$n:${ct?.let(::witTypeKey) ?: "_"}"
            }
        }
    }

    private val REALLOC_IR_TYPE = IrFuncType(
        params = listOf(IrValType.I32, IrValType.I32, IrValType.I32, IrValType.I32),
        results = listOf(IrValType.I32),
    )
    private val DTOR_IR_TYPE = IrFuncType(
        params = listOf(IrValType.I32),
        results = emptyList(),
    )
}
