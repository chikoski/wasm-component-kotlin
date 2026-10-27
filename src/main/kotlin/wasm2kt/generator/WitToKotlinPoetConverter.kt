package wasm2kt.generator

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.BYTE
import com.squareup.kotlinpoet.CHAR
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.ExperimentalKotlinPoetApi
import com.squareup.kotlinpoet.FLOAT
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.NOTHING
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.SHORT
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeAliasSpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.U_BYTE
import com.squareup.kotlinpoet.U_INT
import com.squareup.kotlinpoet.U_LONG
import com.squareup.kotlinpoet.U_SHORT
import com.squareup.kotlinpoet.UNIT
import wasm2kt.ast.WitDocs
import wasm2kt.ast.WitEnumCase
import wasm2kt.ast.WitField
import wasm2kt.ast.WitFlag
import wasm2kt.ast.WitFunction
import wasm2kt.ast.WitFunctionKind
import wasm2kt.ast.WitResolve
import wasm2kt.ast.WitTypeDefKind
import wasm2kt.ast.WitTypeRef
import wasm2kt.ast.WitVariantCase
import wasm2kt.ast.WitWorld
import wasm2kt.ast.WitWorldItem
import java.nio.file.Files
import java.nio.file.Path

/**
 * Generated Kotlin source file model with redundant `public` modifiers stripped for idiomatic Kotlin style.
 */
data class GeneratedKotlinFile(
    val packageName: String,
    val name: String,
    val content: String,
) {
    fun writeTo(outputDir: Path): Path {
        val pkgDir = if (packageName.isEmpty()) {
            outputDir
        } else {
            outputDir.resolve(packageName.replace('.', '/'))
        }
        Files.createDirectories(pkgDir)
        val filePath = pkgDir.resolve("$name.kt")
        Files.writeString(filePath, content)
        return filePath
    }

    override fun toString(): String = content
}

/**
 * Converts a [WitResolve] AST into [GeneratedKotlinFile]s according to the wasm2kt specification.
 */
@OptIn(ExperimentalKotlinPoetApi::class)
class WitToKotlinPoetConverter(
    private val resolve: WitResolve,
    private val targetPackage: String,
    private val targetWorldName: String? = null,
) {
    private val generatedTupleArities = mutableSetOf<Int>()
    private var needsWitOption = false
    private var needsWitResult = false

    fun generate(): List<GeneratedKotlinFile> {
        val fileSpecs = mutableListOf<FileSpec>()

        val worldsToGenerate = if (targetWorldName != null) {
            resolve.worlds.filter { it.name == targetWorldName }
        } else {
            resolve.worlds
        }

        // If the WIT file only defines interfaces and no worlds, synthesize a default world
        if (worldsToGenerate.isEmpty() && resolve.interfaces.isNotEmpty() && targetWorldName == null) {
            fileSpecs += generateStandaloneInterfacesFile()
        } else {
            for (world in worldsToGenerate) {
                fileSpecs += generateWorldFile(world)
            }
        }

        fileSpecs.add(0, generateCommonSupportFile())
        return fileSpecs.map { spec ->
            GeneratedKotlinFile(
                packageName = spec.packageName,
                name = spec.name,
                content = formatIdiomaticKotlin(spec),
            )
        }
    }

    private fun generateWorldFile(world: WitWorld): FileSpec {
        val worldObjectName = NamingConventions.toPascalCase(world.name) + "World"
        val worldClassName = ClassName(targetPackage, worldObjectName)
        val sessionClassName = worldClassName.nestedClass("Session")
        val importsClassName = worldClassName.nestedClass("Imports")

        val fileBuilder = FileSpec.builder(targetPackage, worldObjectName)
        val worldObjBuilder = TypeSpec.objectBuilder(worldClassName)
        world.docs?.contents?.let { worldObjBuilder.addKdoc("%L\n", it.trim()) }

        // Track types already emitted inside the World object to avoid duplicates
        val emittedTypeIndices = mutableSetOf<Int>()

        // 1. Collect all types reachable from interfaces or world imports/exports
        val worldId = resolve.worlds.indexOf(world)

        val referencedInterfaceIds = linkedSetOf<Int>()
        world.imports.values.forEach { item ->
            if (item is WitWorldItem.InterfaceRef) referencedInterfaceIds += item.id
            if (item is WitWorldItem.TypeRef) {
                val targetId = resolveRootTypeIndex(item.id)
                resolve.types.getOrNull(targetId)?.owner?.`interface`?.let { referencedInterfaceIds += it }
            }
        }
        world.exports.values.forEach { item ->
            if (item is WitWorldItem.InterfaceRef) referencedInterfaceIds += item.id
        }

        // Group resource functions by resource type index
        val resourceFunctions = collectResourceFunctions(referencedInterfaceIds, world)

        // Emit types from referenced interfaces first
        for (ifaceId in referencedInterfaceIds) {
            val iface = resolve.interfaces.getOrNull(ifaceId) ?: continue
            for ((_, typeIdx) in iface.types) {
                emitTypeDefinitionIfNeeded(
                    typeIdx = typeIdx,
                    ownerContainer = worldObjBuilder,
                    fileBuilder = fileBuilder,
                    worldClassName = worldClassName,
                    sessionClassName = sessionClassName,
                    resourceFunctions = resourceFunctions[typeIdx].orEmpty(),
                    emittedTypeIndices = emittedTypeIndices,
                )
            }
        }

        // Emit world-owned types (excluding simple `use` re-exports that point to already-emitted interface types)
        resolve.types.forEachIndexed { idx, typeDef ->
            if (typeDef.owner?.world == worldId) {
                val kind = typeDef.kind
                if (kind is WitTypeDefKind.TypeAlias && kind.type is WitTypeRef.Id && kind.type.index in emittedTypeIndices) {
                    val targetDef = resolve.types[kind.type.index]
                    if (targetDef.name == typeDef.name) {
                        emittedTypeIndices += idx
                        return@forEachIndexed
                    }
                }
                emitTypeDefinitionIfNeeded(
                    typeIdx = idx,
                    ownerContainer = worldObjBuilder,
                    fileBuilder = fileBuilder,
                    worldClassName = worldClassName,
                    sessionClassName = sessionClassName,
                    resourceFunctions = resourceFunctions[idx].orEmpty(),
                    emittedTypeIndices = emittedTypeIndices,
                )
            }
        }

        // 2. Build Imports interface
        val importsBuilder = TypeSpec.interfaceBuilder("Imports")
        var hasImports = false
        for ((importKey, item) in world.imports) {
            when (item) {
                is WitWorldItem.FunctionItem -> {
                    if (item.function.kind is WitFunctionKind.Freestanding) {
                        importsBuilder.addFunction(buildInterfaceFunctionSpec(item.function, worldClassName))
                        hasImports = true
                    }
                }
                is WitWorldItem.InterfaceRef -> {
                    val iface = resolve.interfaces.getOrNull(item.id) ?: continue
                    val ifaceName = iface.name ?: importKey
                    if (iface.functions.values.any { it.kind is WitFunctionKind.Freestanding }) {
                        val subIfacePascal = NamingConventions.toPascalCase(ifaceName)
                        val subIfaceBuilder = TypeSpec.interfaceBuilder(subIfacePascal)
                        iface.docs?.contents?.let { subIfaceBuilder.addKdoc("%L\n", it.trim()) }
                        for (fn in iface.functions.values) {
                            if (fn.kind is WitFunctionKind.Freestanding) {
                                subIfaceBuilder.addFunction(buildInterfaceFunctionSpec(fn, worldClassName))
                            }
                        }
                        worldObjBuilder.addType(subIfaceBuilder.build())
                        importsBuilder.addProperty(
                            PropertySpec.builder(
                                NamingConventions.toCamelCase(ifaceName),
                                worldClassName.nestedClass(subIfacePascal),
                            ).build(),
                        )
                        hasImports = true
                    }
                }
                is WitWorldItem.TypeRef -> Unit
            }
        }
        if (!hasImports) {
            worldObjBuilder.addType(
                TypeSpec.interfaceBuilder("Imports")
                    .addType(
                        TypeSpec.companionObjectBuilder().addProperty(
                            PropertySpec.builder("EMPTY", importsClassName)
                                .initializer("object : Imports {}")
                                .build(),
                        ).build(),
                    )
                    .build(),
            )
        } else {
            worldObjBuilder.addType(importsBuilder.build())
        }

        // 3. Build Session class inside World object
        val runtimeSessionClass = ClassName(targetPackage, "WasmtimeSession")
        val autoCloseableClass = ClassName("java.lang", "AutoCloseable")
        val sessionTypeSpec = TypeSpec.classBuilder("Session")
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addModifiers(KModifier.INTERNAL)
                    .addParameter("runtimeSession", runtimeSessionClass)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("runtimeSession", runtimeSessionClass, KModifier.INTERNAL)
                    .initializer("runtimeSession")
                    .build(),
            )
            .addSuperinterface(autoCloseableClass)
            .addFunction(
                FunSpec.builder("close")
                    .addModifiers(KModifier.OVERRIDE)
                    .addStatement("runtimeSession.close()")
                    .build(),
            )
            .build()
        worldObjBuilder.addType(sessionTypeSpec)

        // 4. Add openSession & withSession on World object
        val pathClass = ClassName("java.nio.file", "Path")
        val openSessionBuilder = FunSpec.builder("openSession")
            .addParameter("wasmPath", pathClass)
            .addParameter(
                ParameterSpec.builder("imports", importsClassName)
                    .apply { if (!hasImports) defaultValue("Imports.EMPTY") }
                    .build(),
            )
            .returns(sessionClassName)
            .addStatement("return Session(%T.open(wasmPath, imports))", runtimeSessionClass)
        worldObjBuilder.addFunction(openSessionBuilder.build())

        val typeVarR = TypeVariableName("R")
        val contextLambdaType = LambdaTypeName.get(
            receiver = null,
            parameters = emptyList(),
            returnType = typeVarR,
            contextParameters = listOf(sessionClassName),
        )
        val withSessionBuilder = FunSpec.builder("withSession")
            .addModifiers(KModifier.INLINE)
            .addTypeVariable(typeVarR)
            .addParameter("wasmPath", pathClass)
            .addParameter(
                ParameterSpec.builder("imports", importsClassName)
                    .apply { if (!hasImports) defaultValue("Imports.EMPTY") }
                    .build(),
            )
            .addParameter("block", contextLambdaType)
            .returns(typeVarR)
            .addStatement("return openSession(wasmPath, imports).use { session -> context(session) { block() } }")
        worldObjBuilder.addFunction(withSessionBuilder.build())

        // 5. Emit Exported Interfaces & Top-level Exported Functions
        for ((exportKey, item) in world.exports) {
            when (item) {
                is WitWorldItem.FunctionItem -> {
                    if (item.function.kind is WitFunctionKind.Freestanding) {
                        fileBuilder.addFunction(
                            buildExportedExtensionFunctionSpec(
                                fn = item.function,
                                receiverClassName = worldClassName,
                                worldClassName = worldClassName,
                                sessionClassName = sessionClassName,
                                invokeTargetPrefix = "",
                            ),
                        )
                    }
                }
                is WitWorldItem.InterfaceRef -> {
                    val iface = resolve.interfaces.getOrNull(item.id) ?: continue
                    val ifaceName = iface.name ?: exportKey
                    val ifaceObjectName = NamingConventions.toPascalCase(ifaceName)
                    val ifaceClassName = worldClassName.nestedClass(ifaceObjectName)
                    val ifaceObjBuilder = TypeSpec.objectBuilder(ifaceObjectName)
                    iface.docs?.contents?.let { ifaceObjBuilder.addKdoc("%L\n", it.trim()) }

                    // Also ensure any types in this exported interface are emitted
                    for ((_, typeIdx) in iface.types) {
                        emitTypeDefinitionIfNeeded(
                            typeIdx = typeIdx,
                            ownerContainer = worldObjBuilder,
                            fileBuilder = fileBuilder,
                            worldClassName = worldClassName,
                            sessionClassName = sessionClassName,
                            resourceFunctions = resourceFunctions[typeIdx].orEmpty(),
                            emittedTypeIndices = emittedTypeIndices,
                        )
                    }

                    worldObjBuilder.addType(ifaceObjBuilder.build())

                    for (fn in iface.functions.values) {
                        if (fn.kind is WitFunctionKind.Freestanding) {
                            fileBuilder.addFunction(
                                buildExportedExtensionFunctionSpec(
                                    fn = fn,
                                    receiverClassName = ifaceClassName,
                                    worldClassName = worldClassName,
                                    sessionClassName = sessionClassName,
                                    invokeTargetPrefix = "$ifaceName#",
                                ),
                            )
                        }
                    }
                }
                is WitWorldItem.TypeRef -> Unit
            }
        }

        fileBuilder.addType(worldObjBuilder.build())
        return fileBuilder.build()
    }

    private fun generateStandaloneInterfacesFile(): FileSpec {
        val syntheticWorld = WitWorld(
            name = "default",
            exports = resolve.interfaces.indices.associate { idx ->
                val name = resolve.interfaces[idx].name ?: "interface-$idx"
                name to WitWorldItem.InterfaceRef(idx)
            },
        )
        return generateWorldFile(syntheticWorld)
    }

    private fun collectResourceFunctions(
        referencedInterfaceIds: Set<Int>,
        world: WitWorld,
    ): Map<Int, List<WitFunction>> {
        val result = mutableMapOf<Int, MutableList<WitFunction>>()
        fun addFn(fn: WitFunction) {
            val resId = when (val k = fn.kind) {
                is WitFunctionKind.Constructor -> k.resourceId
                is WitFunctionKind.Method -> k.resourceId
                is WitFunctionKind.Static -> k.resourceId
                WitFunctionKind.Freestanding -> null
            }
            if (resId != null) {
                result.getOrPut(resId) { mutableListOf() }.add(fn)
            }
        }
        for (ifaceId in referencedInterfaceIds) {
            val iface = resolve.interfaces.getOrNull(ifaceId) ?: continue
            iface.functions.values.forEach(::addFn)
        }
        world.exports.values.forEach { item ->
            if (item is WitWorldItem.FunctionItem) addFn(item.function)
            if (item is WitWorldItem.InterfaceRef && item.id !in referencedInterfaceIds) {
                resolve.interfaces.getOrNull(item.id)?.functions?.values?.forEach(::addFn)
            }
        }
        world.imports.values.forEach { item ->
            if (item is WitWorldItem.FunctionItem) addFn(item.function)
        }
        return result
    }

    private fun resolveRootTypeIndex(typeIdx: Int): Int {
        var current = typeIdx
        val visited = mutableSetOf<Int>()
        while (current in resolve.types.indices && visited.add(current)) {
            val def = resolve.types[current]
            val kind = def.kind
            if (kind is WitTypeDefKind.TypeAlias && kind.type is WitTypeRef.Id) {
                val targetDef = resolve.types[kind.type.index]
                if (targetDef.name == def.name) {
                    current = kind.type.index
                    continue
                }
            }
            break
        }
        return current
    }

    private fun emitTypeDefinitionIfNeeded(
        typeIdx: Int,
        ownerContainer: TypeSpec.Builder,
        fileBuilder: FileSpec.Builder,
        worldClassName: ClassName,
        sessionClassName: ClassName,
        resourceFunctions: List<WitFunction>,
        emittedTypeIndices: MutableSet<Int>,
    ) {
        if (!emittedTypeIndices.add(typeIdx)) return
        val typeDef = resolve.types.getOrNull(typeIdx) ?: return
        val rawName = typeDef.name ?: return
        val typePascalName = NamingConventions.toPascalCase(rawName)

        when (val kind = typeDef.kind) {
            is WitTypeDefKind.Record -> {
                ownerContainer.addType(buildRecordTypeSpec(typePascalName, kind.fields, typeDef.docs, worldClassName))
            }
            is WitTypeDefKind.Enum -> {
                ownerContainer.addType(buildEnumTypeSpec(typePascalName, kind.cases, typeDef.docs))
            }
            is WitTypeDefKind.Flags -> {
                ownerContainer.addType(buildFlagsTypeSpec(typePascalName, kind.flags, typeDef.docs, worldClassName.nestedClass(typePascalName)))
            }
            is WitTypeDefKind.Variant -> {
                ownerContainer.addType(buildVariantTypeSpec(typePascalName, kind.cases, typeDef.docs, worldClassName))
            }
            is WitTypeDefKind.Resource -> {
                ownerContainer.addType(
                    buildResourceTypeSpec(
                        resourceWitName = rawName,
                        resourcePascalName = typePascalName,
                        docs = typeDef.docs,
                        worldClassName = worldClassName,
                        sessionClassName = sessionClassName,
                        functions = resourceFunctions,
                    ),
                )
            }
            is WitTypeDefKind.TypeAlias -> {
                if (kind.type is WitTypeRef.Id) {
                    val targetDef = resolve.types.getOrNull(kind.type.index)
                    if (targetDef?.name == rawName) {
                        return
                    }
                }
                val aliasedType = mapTypeRef(kind.type, worldClassName)
                val aliasSpec = TypeAliasSpec.builder(typePascalName, aliasedType)
                    .apply { typeDef.docs?.contents?.let { addKdoc("%L\n", it.trim()) } }
                    .build()
                fileBuilder.addTypeAlias(aliasSpec)
            }
            else -> Unit
        }
    }

    private fun buildRecordTypeSpec(
        name: String,
        fields: List<WitField>,
        docs: WitDocs?,
        worldClassName: ClassName,
    ): TypeSpec {
        val builder = TypeSpec.classBuilder(name)
        if (fields.isNotEmpty()) {
            builder.addModifiers(KModifier.DATA)
        }
        docs?.contents?.let { builder.addKdoc("%L\n", it.trim()) }

        val ctorBuilder = FunSpec.constructorBuilder()
        for (field in fields) {
            val fieldName = NamingConventions.toCamelCase(field.name)
            val fieldType = mapTypeRef(field.type, worldClassName)
            ctorBuilder.addParameter(fieldName, fieldType)
            val propBuilder = PropertySpec.builder(fieldName, fieldType)
                .initializer(fieldName)
            field.docs?.contents?.let { propBuilder.addKdoc("%L\n", it.trim()) }
            builder.addProperty(propBuilder.build())
        }
        builder.primaryConstructor(ctorBuilder.build())
        return builder.build()
    }

    private fun buildEnumTypeSpec(
        name: String,
        cases: List<WitEnumCase>,
        docs: WitDocs?,
    ): TypeSpec {
        val builder = TypeSpec.enumBuilder(name)
        docs?.contents?.let { builder.addKdoc("%L\n", it.trim()) }
        for (case in cases) {
            val entryName = NamingConventions.toUpperSnakeCase(case.name)
            val entrySpec = TypeSpec.anonymousClassBuilder()
                .apply { case.docs?.contents?.let { addKdoc("%L\n", it.trim()) } }
                .build()
            builder.addEnumConstant(entryName, entrySpec)
        }
        return builder.build()
    }

    private fun buildFlagsTypeSpec(
        name: String,
        flags: List<WitFlag>,
        docs: WitDocs?,
        selfClassName: ClassName,
    ): TypeSpec {
        val useULong = flags.size > 32
        val backingType = if (useULong) U_LONG else U_INT
        val zeroLiteral = if (useULong) "0uL" else "0u"
        val oneLiteral = if (useULong) "1uL" else "1u"

        val builder = TypeSpec.classBuilder(name)
            .addModifiers(KModifier.VALUE)
            .addAnnotation(JvmInline::class)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter(
                        ParameterSpec.builder("raw", backingType)
                            .defaultValue(zeroLiteral)
                            .build(),
                    )
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("raw", backingType)
                    .initializer("raw")
                    .build(),
            )

        docs?.contents?.let { builder.addKdoc("%L\n", it.trim()) }

        val companionBuilder = TypeSpec.companionObjectBuilder()
        companionBuilder.addProperty(
            PropertySpec.builder("NONE", selfClassName)
                .initializer("%T($zeroLiteral)", selfClassName)
                .build(),
        )

        flags.forEachIndexed { index, flag ->
            val propName = NamingConventions.toCamelCase(flag.name)
            val constName = NamingConventions.toUpperSnakeCase(flag.name)
            val maskExpr = "($oneLiteral shl $index)"

            val propBuilder = PropertySpec.builder(propName, BOOLEAN)
                .getter(
                    FunSpec.getterBuilder()
                        .addStatement("return (raw and $maskExpr) != $zeroLiteral")
                        .build(),
                )
            flag.docs?.contents?.let { propBuilder.addKdoc("%L\n", it.trim()) }
            builder.addProperty(propBuilder.build())

            companionBuilder.addProperty(
                PropertySpec.builder(constName, selfClassName)
                    .initializer("%T($maskExpr)", selfClassName)
                    .build(),
            )
        }

        builder.addFunction(
            FunSpec.builder("or")
                .addModifiers(KModifier.INFIX)
                .addParameter("other", selfClassName)
                .returns(selfClassName)
                .addStatement("return %T(raw or other.raw)", selfClassName)
                .build(),
        )
        builder.addFunction(
            FunSpec.builder("and")
                .addModifiers(KModifier.INFIX)
                .addParameter("other", selfClassName)
                .returns(selfClassName)
                .addStatement("return %T(raw and other.raw)", selfClassName)
                .build(),
        )
        builder.addType(companionBuilder.build())
        return builder.build()
    }

    private fun buildVariantTypeSpec(
        name: String,
        cases: List<WitVariantCase>,
        docs: WitDocs?,
        worldClassName: ClassName,
    ): TypeSpec {
        val variantClassName = worldClassName.nestedClass(name)
        val builder = TypeSpec.interfaceBuilder(name)
            .addModifiers(KModifier.SEALED)
        docs?.contents?.let { builder.addKdoc("%L\n", it.trim()) }

        for (case in cases) {
            val caseName = NamingConventions.toPascalCase(case.name)
            if (case.type == null) {
                val objBuilder = TypeSpec.objectBuilder(caseName)
                    .addModifiers(KModifier.DATA)
                    .addSuperinterface(variantClassName)
                case.docs?.contents?.let { objBuilder.addKdoc("%L\n", it.trim()) }
                builder.addType(objBuilder.build())
            } else {
                val payloadType = mapTypeRef(case.type, worldClassName)
                val classBuilder = TypeSpec.classBuilder(caseName)
                    .addModifiers(KModifier.DATA)
                    .addSuperinterface(variantClassName)
                    .primaryConstructor(
                        FunSpec.constructorBuilder()
                            .addParameter("value", payloadType)
                            .build(),
                    )
                    .addProperty(
                        PropertySpec.builder("value", payloadType)
                            .initializer("value")
                            .build(),
                    )
                case.docs?.contents?.let { classBuilder.addKdoc("%L\n", it.trim()) }
                builder.addType(classBuilder.build())
            }
        }
        return builder.build()
    }

    private fun buildResourceTypeSpec(
        resourceWitName: String,
        resourcePascalName: String,
        docs: WitDocs?,
        worldClassName: ClassName,
        sessionClassName: ClassName,
        functions: List<WitFunction>,
    ): TypeSpec {
        val resourceClassName = worldClassName.nestedClass(resourcePascalName)
        val autoCloseableClass = ClassName("java.lang", "AutoCloseable")

        val builder = TypeSpec.classBuilder(resourcePascalName)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addModifiers(KModifier.INTERNAL)
                    .addParameter("session", sessionClassName)
                    .addParameter("handle", INT)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("session", sessionClassName, KModifier.PRIVATE)
                    .initializer("session")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("handle", INT)
                    .initializer("handle")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("isClosed", BOOLEAN)
                    .mutable(true)
                    .setter(FunSpec.setterBuilder().addModifiers(KModifier.INTERNAL).build())
                    .initializer("false")
                    .build(),
            )
            .addSuperinterface(autoCloseableClass)
            .addFunction(
                FunSpec.builder("consumeOwnership")
                    .addModifiers(KModifier.INTERNAL)
                    .returns(INT)
                    .addStatement("check(!isClosed) { %P }", "Resource $resourcePascalName(handle=\$handle) is already closed or moved")
                    .addStatement("isClosed = true")
                    .addStatement("return handle")
                    .build(),
            )

        docs?.contents?.let { builder.addKdoc("%L\n", it.trim()) }

        val companionBuilder = TypeSpec.companionObjectBuilder()

        for (fn in functions) {
            when (fn.kind) {
                is WitFunctionKind.Constructor -> {
                    val invokeBuilder = FunSpec.builder("invoke")
                        .addModifiers(KModifier.OPERATOR)
                        .contextParameter("session", sessionClassName)
                        .returns(resourceClassName)
                    fn.docs?.contents?.let { invokeBuilder.addKdoc("%L\n", it.trim()) }
                    val argNames = mutableListOf<String>()
                    for (param in fn.params) {
                        val paramName = NamingConventions.toCamelCase(param.name)
                        invokeBuilder.addParameter(paramName, mapTypeRef(param.type, worldClassName))
                        argNames += paramName
                    }
                    val argsJoined = if (argNames.isEmpty()) "" else ", " + argNames.joinToString(", ")
                    invokeBuilder.addStatement(
                        "val handle = session.runtimeSession.newResource(%S$argsJoined)",
                        resourceWitName,
                    )
                    invokeBuilder.addStatement("return %T(session, handle)", resourceClassName)
                    companionBuilder.addFunction(invokeBuilder.build())
                }
                is WitFunctionKind.Method -> {
                    val methodName = NamingConventions.extractResourceMemberName(fn.name)
                    val methodBuilder = FunSpec.builder(methodName)
                    fn.docs?.contents?.let { methodBuilder.addKdoc("%L\n", it.trim()) }
                    val userParams = fn.params.dropWhile { it.name == "self" }
                    val argExprs = mutableListOf<String>()
                    for (param in userParams) {
                        val paramName = NamingConventions.toCamelCase(param.name)
                        methodBuilder.addParameter(paramName, mapTypeRef(param.type, worldClassName))
                        argExprs += formatArgumentExpression(paramName, param.type)
                    }
                    methodBuilder.addStatement("check(!isClosed) { %P }", "Resource $resourcePascalName(handle=\$handle) is already closed or moved")
                    val returnType = mapFunctionReturnType(fn, worldClassName)
                    methodBuilder.returns(returnType)
                    val argsJoined = if (argExprs.isEmpty()) "" else ", " + argExprs.joinToString(", ")
                    val typeOfMember = com.squareup.kotlinpoet.MemberName("kotlin.reflect", "typeOf")
                    if (returnType == UNIT) {
                        methodBuilder.addStatement(
                            "session.runtimeSession.invokeMethod<Unit>(handle, %S, %M<%T>()$argsJoined)",
                            fn.name,
                            typeOfMember,
                            UNIT,
                        )
                    } else if (returnsOwnedResource(fn)) {
                        methodBuilder.addStatement(
                            "val resultHandle = session.runtimeSession.invokeMethod<Int>(handle, %S, %M<%T>()$argsJoined)",
                            fn.name,
                            typeOfMember,
                            INT,
                        )
                        methodBuilder.addStatement("return %T(session, resultHandle)", returnType)
                    } else {
                        methodBuilder.addStatement(
                            "return session.runtimeSession.invokeMethod(handle, %S, %M<%T>()$argsJoined)",
                            fn.name,
                            typeOfMember,
                            returnType,
                        )
                    }
                    builder.addFunction(methodBuilder.build())
                }
                is WitFunctionKind.Static -> {
                    val staticName = NamingConventions.extractResourceMemberName(fn.name)
                    val staticBuilder = FunSpec.builder(staticName)
                        .contextParameter("session", sessionClassName)
                    fn.docs?.contents?.let { staticBuilder.addKdoc("%L\n", it.trim()) }
                    val argExprs = mutableListOf<String>()
                    for (param in fn.params) {
                        val paramName = NamingConventions.toCamelCase(param.name)
                        staticBuilder.addParameter(paramName, mapTypeRef(param.type, worldClassName))
                        argExprs += formatArgumentExpression(paramName, param.type)
                    }
                    val returnType = mapFunctionReturnType(fn, worldClassName)
                    staticBuilder.returns(returnType)
                    val argsJoined = if (argExprs.isEmpty()) "" else ", " + argExprs.joinToString(", ")
                    if (returnType == UNIT) {
                        staticBuilder.addStatement(
                            "session.runtimeSession.invoke<Unit>(%S, %M<%T>()$argsJoined)",
                            fn.name,
                            com.squareup.kotlinpoet.MemberName("kotlin.reflect", "typeOf"),
                            UNIT,
                        )
                    } else if (returnsOwnedResource(fn)) {
                        staticBuilder.addStatement(
                            "val resultHandle = session.runtimeSession.invoke<Int>(%S, %M<%T>()$argsJoined)",
                            fn.name,
                            com.squareup.kotlinpoet.MemberName("kotlin.reflect", "typeOf"),
                            INT,
                        )
                        staticBuilder.addStatement("return %T(session, resultHandle)", returnType)
                    } else {
                        staticBuilder.addStatement(
                            "return session.runtimeSession.invoke(%S, %M<%T>()$argsJoined)",
                            fn.name,
                            com.squareup.kotlinpoet.MemberName("kotlin.reflect", "typeOf"),
                            returnType,
                        )
                    }
                    companionBuilder.addFunction(staticBuilder.build())
                }
                WitFunctionKind.Freestanding -> Unit
            }
        }

        builder.addFunction(
            FunSpec.builder("close")
                .addModifiers(KModifier.OVERRIDE)
                .beginControlFlow("if (!isClosed)")
                .addStatement("isClosed = true")
                .addStatement("session.runtimeSession.dropResource(handle)")
                .endControlFlow()
                .build(),
        )
        builder.addType(companionBuilder.build())
        return builder.build()
    }

    private fun buildInterfaceFunctionSpec(
        fn: WitFunction,
        worldClassName: ClassName,
    ): FunSpec {
        val fnName = NamingConventions.toCamelCase(fn.name)
        val builder = FunSpec.builder(fnName)
            .addModifiers(KModifier.ABSTRACT)
        fn.docs?.contents?.let { builder.addKdoc("%L\n", it.trim()) }
        for (param in fn.params) {
            builder.addParameter(
                NamingConventions.toCamelCase(param.name),
                mapTypeRef(param.type, worldClassName),
            )
        }
        builder.returns(mapFunctionReturnType(fn, worldClassName))
        return builder.build()
    }

    private fun buildExportedExtensionFunctionSpec(
        fn: WitFunction,
        receiverClassName: ClassName,
        worldClassName: ClassName,
        sessionClassName: ClassName,
        invokeTargetPrefix: String,
    ): FunSpec {
        val fnName = NamingConventions.toCamelCase(fn.name)
        val builder = FunSpec.builder(fnName)
            .receiver(receiverClassName)
            .contextParameter("session", sessionClassName)

        fn.docs?.contents?.let { builder.addKdoc("%L\n", it.trim()) }

        val argExprs = mutableListOf<String>()
        for (param in fn.params) {
            val paramName = NamingConventions.toCamelCase(param.name)
            builder.addParameter(paramName, mapTypeRef(param.type, worldClassName))
            argExprs += formatArgumentExpression(paramName, param.type)
        }

        val returnType = mapFunctionReturnType(fn, worldClassName)
        builder.returns(returnType)

        val fullExportName = "$invokeTargetPrefix${fn.name}"
        val argsJoined = if (argExprs.isEmpty()) "" else ", " + argExprs.joinToString(", ")
        val typeOfMember = com.squareup.kotlinpoet.MemberName("kotlin.reflect", "typeOf")
        if (returnType == UNIT) {
            builder.addStatement("session.runtimeSession.invoke<Unit>(%S, %M<%T>()$argsJoined)", fullExportName, typeOfMember, UNIT)
        } else if (returnsOwnedResource(fn)) {
            builder.addStatement("val resultHandle = session.runtimeSession.invoke<Int>(%S, %M<%T>()$argsJoined)", fullExportName, typeOfMember, INT)
            builder.addStatement("return %T(session, resultHandle)", returnType)
        } else {
            builder.addStatement("return session.runtimeSession.invoke(%S, %M<%T>()$argsJoined)", fullExportName, typeOfMember, returnType)
        }
        return builder.build()
    }

    private fun formatArgumentExpression(paramName: String, typeRef: WitTypeRef): String {
        if (typeRef is WitTypeRef.Id) {
            val def = resolve.types.getOrNull(typeRef.index)
            val kind = def?.kind
            if (kind is WitTypeDefKind.Handle) {
                return when (kind.kind) {
                    wasm2kt.ast.HandleKind.OWN -> "$paramName.consumeOwnership()"
                    wasm2kt.ast.HandleKind.BORROW -> "$paramName.handle"
                }
            }
        }
        return paramName
    }

    private fun returnsOwnedResource(fn: WitFunction): Boolean {
        if (fn.results.size != 1) return false
        val ref = fn.results.first().type
        if (ref is WitTypeRef.Id) {
            val def = resolve.types.getOrNull(ref.index)
            if (def?.kind is WitTypeDefKind.Handle || def?.kind is WitTypeDefKind.Resource) {
                return true
            }
        }
        return false
    }

    private fun mapFunctionReturnType(fn: WitFunction, worldClassName: ClassName): TypeName {
        return when (fn.results.size) {
            0 -> UNIT
            1 -> mapTypeRef(fn.results.first().type, worldClassName)
            else -> {
                val arity = fn.results.size
                generatedTupleArities += arity
                val tupleClass = ClassName(targetPackage, "Tuple$arity")
                tupleClass.parameterizedBy(fn.results.map { mapTypeRef(it.type, worldClassName) })
            }
        }
    }

    fun mapTypeRef(typeRef: WitTypeRef, worldClassName: ClassName): TypeName {
        return when (typeRef) {
            is WitTypeRef.Primitive -> mapPrimitiveType(typeRef.name)
            is WitTypeRef.Id -> mapTypeDefByIndex(typeRef.index, worldClassName)
        }
    }

    private fun mapPrimitiveType(name: String): TypeName = when (name) {
        "bool" -> BOOLEAN
        "u8" -> U_BYTE
        "s8" -> BYTE
        "u16" -> U_SHORT
        "s16" -> SHORT
        "u32" -> U_INT
        "s32" -> INT
        "u64" -> U_LONG
        "s64" -> LONG
        "f32", "float32" -> FLOAT
        "f64", "float64" -> DOUBLE
        "char" -> CHAR
        "string" -> STRING
        "_" -> UNIT
        else -> throw IllegalArgumentException("Unknown WIT primitive type: $name")
    }

    private fun mapTypeDefByIndex(index: Int, worldClassName: ClassName): TypeName {
        val typeDef = resolve.types.getOrNull(index)
            ?: throw IllegalArgumentException("Invalid WIT type index: $index")

        val name = typeDef.name
        return when (val kind = typeDef.kind) {
            is WitTypeDefKind.Record,
            is WitTypeDefKind.Enum,
            is WitTypeDefKind.Flags,
            is WitTypeDefKind.Variant,
            is WitTypeDefKind.Resource,
            -> {
                val pascal = NamingConventions.toPascalCase(
                    name ?: throw IllegalStateException("Named type at index $index has null name"),
                )
                worldClassName.nestedClass(pascal)
            }
            is WitTypeDefKind.TypeAlias -> {
                if (name != null) {
                    if (kind.type is WitTypeRef.Id) {
                        val targetDef = resolve.types.getOrNull(kind.type.index)
                        if (targetDef?.name == name) {
                            return mapTypeDefByIndex(kind.type.index, worldClassName)
                        }
                    }
                    ClassName(targetPackage, NamingConventions.toPascalCase(name))
                } else {
                    mapTypeRef(kind.type, worldClassName)
                }
            }
            is WitTypeDefKind.Handle -> {
                mapTypeDefByIndex(kind.resourceId, worldClassName)
            }
            is WitTypeDefKind.OptionType -> {
                needsWitOption = true
                val innerType = mapTypeRef(kind.type, worldClassName)
                ClassName(targetPackage, "WitOption").parameterizedBy(innerType)
            }
            is WitTypeDefKind.ResultType -> {
                needsWitResult = true
                val okType = kind.ok?.let { mapTypeRef(it, worldClassName) } ?: UNIT
                val errType = kind.err?.let { mapTypeRef(it, worldClassName) } ?: UNIT
                ClassName(targetPackage, "WitResult").parameterizedBy(okType, errType)
            }
            is WitTypeDefKind.ListType -> {
                val innerType = mapTypeRef(kind.type, worldClassName)
                LIST.parameterizedBy(innerType)
            }
            is WitTypeDefKind.Tuple -> {
                val arity = kind.types.size
                generatedTupleArities += arity
                val tupleClass = ClassName(targetPackage, "Tuple$arity")
                tupleClass.parameterizedBy(kind.types.map { mapTypeRef(it, worldClassName) })
            }
            is WitTypeDefKind.FutureType -> {
                val innerType = kind.type?.let { mapTypeRef(it, worldClassName) } ?: UNIT
                ClassName("java.util.concurrent", "CompletableFuture").parameterizedBy(innerType)
            }
            is WitTypeDefKind.StreamType -> {
                val innerType = kind.element?.let { mapTypeRef(it, worldClassName) } ?: UNIT
                LIST.parameterizedBy(innerType)
            }
        }
    }

    private fun generateCommonSupportFile(): FileSpec {
        val fileBuilder = FileSpec.builder(targetPackage, "WitSupport")

        // 1. WitOption<out T>
        fileBuilder.addType(buildWitOptionTypeSpec())

        // 2. WitResult<out T, out E>
        fileBuilder.addType(buildWitResultTypeSpec())

        // 3. Any TupleN types used in the WIT definitions
        for (arity in generatedTupleArities.sorted()) {
            fileBuilder.addType(buildTupleTypeSpec(arity))
        }

        // 4. WasmtimeSession runtime bridge interface, FfmWasmtimeSession & InMemoryWasmtimeSession
        fileBuilder.addType(buildWasmtimeSessionTypeSpec())
        fileBuilder.addType(buildFfmWasmtimeSessionTypeSpec())
        fileBuilder.addType(buildInMemoryWasmtimeSessionTypeSpec())

        return fileBuilder.build()
    }

    private fun buildWitOptionTypeSpec(): TypeSpec {
        val witOptionClass = ClassName(targetPackage, "WitOption")
        val typeT = TypeVariableName("T", KModifier.OUT)
        val typeTInvariant = TypeVariableName("T")

        val someClass = TypeSpec.classBuilder("Some")
            .addModifiers(KModifier.DATA)
            .addTypeVariable(typeT)
            .addSuperinterface(witOptionClass.parameterizedBy(typeTInvariant))
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("value", typeTInvariant)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("value", typeTInvariant)
                    .initializer("value")
                    .build(),
            )
            .build()

        val noneObject = TypeSpec.objectBuilder("None")
            .addModifiers(KModifier.DATA)
            .addSuperinterface(witOptionClass.parameterizedBy(NOTHING))
            .build()

        val getOrNullFn = FunSpec.builder("getOrNull")
            .returns(typeTInvariant.copy(nullable = true))
            .addCode(
                CodeBlock.builder()
                    .beginControlFlow("return when (this)")
                    .addStatement("is Some -> value")
                    .addStatement("is None -> null")
                    .endControlFlow()
                    .build(),
            )
            .build()

        return TypeSpec.interfaceBuilder("WitOption")
            .addModifiers(KModifier.SEALED)
            .addTypeVariable(typeT)
            .addType(someClass)
            .addType(noneObject)
            .addFunction(getOrNullFn)
            .build()
    }

    private fun buildWitResultTypeSpec(): TypeSpec {
        val witResultClass = ClassName(targetPackage, "WitResult")
        val typeT = TypeVariableName("T", KModifier.OUT)
        val typeE = TypeVariableName("E", KModifier.OUT)
        val typeTInvariant = TypeVariableName("T")
        val typeEInvariant = TypeVariableName("E")

        val okClass = TypeSpec.classBuilder("Ok")
            .addModifiers(KModifier.DATA)
            .addTypeVariable(typeT)
            .addSuperinterface(witResultClass.parameterizedBy(typeTInvariant, NOTHING))
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("value", typeTInvariant)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("value", typeTInvariant)
                    .initializer("value")
                    .build(),
            )
            .build()

        val errClass = TypeSpec.classBuilder("Err")
            .addModifiers(KModifier.DATA)
            .addTypeVariable(typeE)
            .addSuperinterface(witResultClass.parameterizedBy(NOTHING, typeEInvariant))
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("error", typeEInvariant)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("error", typeEInvariant)
                    .initializer("error")
                    .build(),
            )
            .build()

        return TypeSpec.interfaceBuilder("WitResult")
            .addModifiers(KModifier.SEALED)
            .addTypeVariable(typeT)
            .addTypeVariable(typeE)
            .addType(okClass)
            .addType(errClass)
            .build()
    }

    private fun buildTupleTypeSpec(arity: Int): TypeSpec {
        val builder = TypeSpec.classBuilder("Tuple$arity")
        if (arity > 0) {
            builder.addModifiers(KModifier.DATA)
        }
        val ctorBuilder = FunSpec.constructorBuilder()
        for (i in 0 until arity) {
            val typeVarOut = TypeVariableName("T$i", KModifier.OUT)
            val typeVar = TypeVariableName("T$i")
            builder.addTypeVariable(typeVarOut)
            val fieldName = "f$i"
            ctorBuilder.addParameter(fieldName, typeVar)
            builder.addProperty(
                PropertySpec.builder(fieldName, typeVar)
                    .initializer(fieldName)
                    .build(),
            )
        }
        builder.primaryConstructor(ctorBuilder.build())
        return builder.build()
    }

    private fun buildWasmtimeSessionTypeSpec(): TypeSpec {
        val autoCloseableClass = ClassName("java.lang", "AutoCloseable")
        val pathClass = ClassName("java.nio.file", "Path")
        val kTypeClass = ClassName("kotlin.reflect", "KType")
        val runtimeSessionClass = ClassName(targetPackage, "WasmtimeSession")
        val ffmSessionClass = ClassName(targetPackage, "FfmWasmtimeSession")
        val typeR = TypeVariableName("R")
        val anyNullable = ClassName("kotlin", "Any").copy(nullable = true)

        return TypeSpec.interfaceBuilder("WasmtimeSession")
            .addSuperinterface(autoCloseableClass)
            .addFunction(
                FunSpec.builder("invoke")
                    .addModifiers(KModifier.ABSTRACT)
                    .addTypeVariable(typeR)
                    .addParameter("functionName", STRING)
                    .addParameter("returnType", kTypeClass)
                    .addParameter("args", anyNullable, KModifier.VARARG)
                    .returns(typeR)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("newResource")
                    .addModifiers(KModifier.ABSTRACT)
                    .addParameter("resourceName", STRING)
                    .addParameter("args", anyNullable, KModifier.VARARG)
                    .returns(INT)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("invokeMethod")
                    .addModifiers(KModifier.ABSTRACT)
                    .addTypeVariable(typeR)
                    .addParameter("handle", INT)
                    .addParameter("methodName", STRING)
                    .addParameter("returnType", kTypeClass)
                    .addParameter("args", anyNullable, KModifier.VARARG)
                    .returns(typeR)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("dropResource")
                    .addModifiers(KModifier.ABSTRACT)
                    .addParameter("handle", INT)
                    .build(),
            )
            .addType(
                TypeSpec.companionObjectBuilder()
                    .addProperty(
                        PropertySpec.builder(
                            "factory",
                            LambdaTypeName.get(
                                parameters = listOf(
                                    ParameterSpec.builder("wasmPath", pathClass).build(),
                                    ParameterSpec.builder("imports", anyNullable).build(),
                                ),
                                returnType = runtimeSessionClass,
                            ),
                        )
                            .mutable(true)
                            .initializer(
                                "{ wasmPath, imports -> %T.open(wasmPath, imports) }",
                                ffmSessionClass,
                            )
                            .build(),
                    )
                    .addFunction(
                        FunSpec.builder("open")
                            .addParameter("wasmPath", pathClass)
                            .addParameter("imports", anyNullable)
                            .returns(runtimeSessionClass)
                            .addStatement("return factory(wasmPath, imports)")
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    private fun buildFfmWasmtimeSessionTypeSpec(): TypeSpec {
        val runtimeSessionClass = ClassName(targetPackage, "WasmtimeSession")
        val ffmSessionClass = ClassName(targetPackage, "FfmWasmtimeSession")
        val ffmBridgeClass = ClassName("wasm2kt.runtime", "FfmWasmtimeBridge")
        val ffmCodecClass = ClassName("wasm2kt.runtime", "FfmJsonCodec")
        val arenaClass = ClassName("java.lang.foreign", "Arena")
        val memorySegmentClass = ClassName("java.lang.foreign", "MemorySegment")
        val valueLayoutClass = ClassName("java.lang.foreign", "ValueLayout")
        val standardCharsetsClass = ClassName("java.nio.charset", "StandardCharsets")
        val pathClass = ClassName("java.nio.file", "Path")
        val kTypeClass = ClassName("kotlin.reflect", "KType")
        val jsonClass = ClassName("kotlinx.serialization.json", "Json")
        val jsonArrayClass = ClassName("kotlinx.serialization.json", "JsonArray")
        val jsonElementClass = ClassName("kotlinx.serialization.json", "JsonElement")
        val jsonNullClass = ClassName("kotlinx.serialization.json", "JsonNull")
        val jsonObjectMember = com.squareup.kotlinpoet.MemberName("kotlinx.serialization.json", "jsonObject")
        val jsonPrimitiveMember = com.squareup.kotlinpoet.MemberName("kotlinx.serialization.json", "jsonPrimitive")
        val booleanMember = com.squareup.kotlinpoet.MemberName("kotlinx.serialization.json", "boolean")
        val typeOfMember = com.squareup.kotlinpoet.MemberName("kotlin.reflect", "typeOf")
        val anyNullable = ClassName("kotlin", "Any").copy(nullable = true)
        val typeR = TypeVariableName("R")

        return TypeSpec.classBuilder("FfmWasmtimeSession")
            .addSuperinterface(runtimeSessionClass)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addModifiers(KModifier.PRIVATE)
                    .addParameter("bridge", ffmBridgeClass)
                    .addParameter("sessionArena", arenaClass)
                    .addParameter("sessionPtr", memorySegmentClass)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("bridge", ffmBridgeClass, KModifier.PRIVATE)
                    .initializer("bridge")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("sessionArena", arenaClass, KModifier.PRIVATE)
                    .initializer("sessionArena")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("sessionPtr", memorySegmentClass, KModifier.PRIVATE)
                    .initializer("sessionPtr")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("isClosed", BOOLEAN)
                    .mutable(true)
                    .setter(FunSpec.setterBuilder().addModifiers(KModifier.PRIVATE).build())
                    .initializer("false")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("invoke")
                    .addModifiers(KModifier.OVERRIDE)
                    .addAnnotation(
                        com.squareup.kotlinpoet.AnnotationSpec.builder(Suppress::class)
                            .addMember("%S", "UNCHECKED_CAST")
                            .build(),
                    )
                    .addTypeVariable(typeR)
                    .addParameter("functionName", STRING)
                    .addParameter("returnType", kTypeClass)
                    .addParameter("args", anyNullable, KModifier.VARARG)
                    .returns(typeR)
                    .addStatement("check(!isClosed) { %S }", "FfmWasmtimeSession is already closed")
                    .beginControlFlow("%T.ofConfined().use { callArena ->", arenaClass)
                    .addStatement(
                        "val argsJson = %T.encodeToString(%T.serializer(), %T(args.map { %T.toJsonElement(it) }))",
                        jsonClass,
                        jsonElementClass,
                        jsonArrayClass,
                        ffmCodecClass,
                    )
                    .addStatement("val fnPtr = %T.allocateUtf8CString(callArena, functionName)", ffmBridgeClass)
                    .addStatement("val argsPtr = %T.allocateUtf8CString(callArena, argsJson)", ffmBridgeClass)
                    .addStatement(
                        "val respPtr = bridge.sessionInvokeHandle.invokeExact(sessionPtr, fnPtr, argsPtr) as %T",
                        memorySegmentClass,
                    )
                    .addStatement("val respStr = bridge.readAndFreeCString(respPtr)")
                    .addStatement("val respObj = %T.parseToJsonElement(respStr).%M", jsonClass, jsonObjectMember)
                    .beginControlFlow("if (!respObj.getValue(%S).%M.%M)", "ok", jsonPrimitiveMember, booleanMember)
                    .addStatement("val errMsg = respObj[%S]?.%M?.content ?: %S", "error", jsonPrimitiveMember, "Unknown Wasmtime error")
                    .addStatement("throw IllegalStateException(errMsg)")
                    .endControlFlow()
                    .addStatement("val valElem = respObj[%S] ?: %T", "value", jsonNullClass)
                    .addStatement("return %T.fromJsonElement(valElem, returnType) as R", ffmCodecClass)
                    .endControlFlow()
                    .build(),
            )
            .addFunction(
                FunSpec.builder("newResource")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("resourceName", STRING)
                    .addParameter("args", anyNullable, KModifier.VARARG)
                    .returns(INT)
                    .addStatement("return invoke(%P, %M<Int>(), *args)", "[constructor]\$resourceName", typeOfMember)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("invokeMethod")
                    .addModifiers(KModifier.OVERRIDE)
                    .addTypeVariable(typeR)
                    .addParameter("handle", INT)
                    .addParameter("methodName", STRING)
                    .addParameter("returnType", kTypeClass)
                    .addParameter("args", anyNullable, KModifier.VARARG)
                    .returns(typeR)
                    .addStatement("val allArgs = arrayOf<Any?>(handle, *args)")
                    .addStatement("return invoke(methodName, returnType, *allArgs)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("dropResource")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("handle", INT)
                    .addStatement("check(!isClosed) { %S }", "FfmWasmtimeSession is already closed")
                    .addStatement(
                        "val respPtr = bridge.sessionDropResourceHandle.invokeExact(sessionPtr, handle) as %T",
                        memorySegmentClass,
                    )
                    .addStatement("val respStr = bridge.readAndFreeCString(respPtr)")
                    .addStatement("val respObj = %T.parseToJsonElement(respStr).%M", jsonClass, jsonObjectMember)
                    .beginControlFlow("if (!respObj.getValue(%S).%M.%M)", "ok", jsonPrimitiveMember, booleanMember)
                    .addStatement("val errMsg = respObj[%S]?.%M?.content ?: %S", "error", jsonPrimitiveMember, "Failed to drop resource")
                    .addStatement("throw IllegalStateException(errMsg)")
                    .endControlFlow()
                    .build(),
            )
            .addFunction(
                FunSpec.builder("close")
                    .addModifiers(KModifier.OVERRIDE)
                    .beginControlFlow("if (!isClosed)")
                    .addStatement("isClosed = true")
                    .addStatement("bridge.sessionCloseHandle.invokeExact(sessionPtr)")
                    .addStatement("sessionArena.close()")
                    .endControlFlow()
                    .build(),
            )
            .addType(
                TypeSpec.companionObjectBuilder()
                    .addFunction(
                        FunSpec.builder("open")
                            .addParameter("wasmPath", pathClass)
                            .addParameter("imports", anyNullable)
                            .addParameter(
                                ParameterSpec.builder("bridge", ffmBridgeClass)
                                    .defaultValue("%T.getDefault()", ffmBridgeClass)
                                    .build(),
                            )
                            .returns(ffmSessionClass)
                            .addStatement("val sessionArena = %T.ofShared()", arenaClass)
                            .beginControlFlow("try")
                            .addStatement(
                                "val wasmPathPtr = %T.allocateUtf8CString(sessionArena, wasmPath.toAbsolutePath().toString())",
                                ffmBridgeClass,
                            )
                            .addStatement("val importsJsonPtr = %T.allocateUtf8CString(sessionArena, %S)", ffmBridgeClass, "[]")
                            .addStatement("val callbackStub = bridge.createHostCallbackStub(sessionArena, imports)")
                            .addStatement("val errCap = 4096L")
                            .addStatement("val errBuf = sessionArena.allocate(errCap)")
                            .addStatement(
                                "val ptr = bridge.sessionOpenHandle.invokeExact(wasmPathPtr, importsJsonPtr, callbackStub, errBuf, errCap) as %T",
                                memorySegmentClass,
                            )
                            .beginControlFlow("if (ptr == %T.NULL)", memorySegmentClass)
                            .addStatement("val errMsg = %T.readNullTerminatedUtf8(errBuf)", ffmBridgeClass)
                            .addStatement("throw IllegalStateException(%P)", "Failed to open Wasmtime session: \$errMsg")
                            .endControlFlow()
                            .addStatement("return %T(bridge, sessionArena, ptr)", ffmSessionClass)
                            .nextControlFlow("catch (t: Throwable)")
                            .addStatement("sessionArena.close()")
                            .addStatement("throw t")
                            .endControlFlow()
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    private fun buildInMemoryWasmtimeSessionTypeSpec(): TypeSpec {
        val runtimeSessionClass = ClassName(targetPackage, "WasmtimeSession")
        val kTypeClass = ClassName("kotlin.reflect", "KType")
        val anyNullable = ClassName("kotlin", "Any").copy(nullable = true)
        val argsListType = LIST.parameterizedBy(anyNullable)
        val typeR = TypeVariableName("R")
        val fnHandlerType = LambdaTypeName.get(
            parameters = listOf(ParameterSpec.builder("args", argsListType).build()),
            returnType = anyNullable,
        )
        val methodHandlerType = LambdaTypeName.get(
            parameters = listOf(
                ParameterSpec.builder("handle", INT).build(),
                ParameterSpec.builder("args", argsListType).build(),
            ),
            returnType = anyNullable,
        )

        return TypeSpec.classBuilder("InMemoryWasmtimeSession")
            .addSuperinterface(runtimeSessionClass)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter(
                        ParameterSpec.builder("imports", anyNullable)
                            .defaultValue("null")
                            .build(),
                    )
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("imports", anyNullable)
                    .initializer("imports")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder(
                    "functionHandlers",
                    ClassName("kotlin.collections", "MutableMap").parameterizedBy(STRING, fnHandlerType),
                    KModifier.PRIVATE,
                )
                    .initializer("mutableMapOf()")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder(
                    "resourceConstructors",
                    ClassName("kotlin.collections", "MutableMap").parameterizedBy(STRING, fnHandlerType),
                    KModifier.PRIVATE,
                )
                    .initializer("mutableMapOf()")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder(
                    "methodHandlers",
                    ClassName("kotlin.collections", "MutableMap").parameterizedBy(STRING, methodHandlerType),
                    KModifier.PRIVATE,
                )
                    .initializer("mutableMapOf()")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder(
                    "activeResources",
                    ClassName("kotlin.collections", "MutableMap").parameterizedBy(INT, anyNullable),
                    KModifier.PRIVATE,
                )
                    .initializer("mutableMapOf()")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder(
                    "droppedHandles",
                    ClassName("kotlin.collections", "MutableList").parameterizedBy(INT),
                )
                    .initializer("mutableListOf()")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("nextHandle", INT, KModifier.PRIVATE)
                    .mutable(true)
                    .initializer("1")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("isClosed", BOOLEAN)
                    .mutable(true)
                    .setter(FunSpec.setterBuilder().addModifiers(KModifier.PRIVATE).build())
                    .initializer("false")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("registerFunction")
                    .addParameter("name", STRING)
                    .addParameter("handler", fnHandlerType)
                    .addStatement("functionHandlers[name] = handler")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("registerResourceConstructor")
                    .addParameter("resourceName", STRING)
                    .addParameter("constructor", fnHandlerType)
                    .addStatement("resourceConstructors[resourceName] = constructor")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("registerMethod")
                    .addParameter("methodName", STRING)
                    .addParameter("handler", methodHandlerType)
                    .addStatement("methodHandlers[methodName] = handler")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("getResourceState")
                    .addParameter("handle", INT)
                    .returns(anyNullable)
                    .addStatement("check(handle in activeResources) { %P }", "Invalid or already dropped resource handle: \$handle")
                    .addStatement("return activeResources[handle]")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("setResourceState")
                    .addParameter("handle", INT)
                    .addParameter("state", anyNullable)
                    .addStatement("check(handle in activeResources) { %P }", "Invalid or already dropped resource handle: \$handle")
                    .addStatement("activeResources[handle] = state")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("invoke")
                    .addModifiers(KModifier.OVERRIDE)
                    .addAnnotation(
                        com.squareup.kotlinpoet.AnnotationSpec.builder(Suppress::class)
                            .addMember("%S", "UNCHECKED_CAST")
                            .build(),
                    )
                    .addTypeVariable(typeR)
                    .addParameter("functionName", STRING)
                    .addParameter("returnType", kTypeClass)
                    .addParameter("args", anyNullable, KModifier.VARARG)
                    .returns(typeR)
                    .addStatement("check(!isClosed) { %S }", "WasmtimeSession is already closed")
                    .addStatement(
                        "val handler = functionHandlers[functionName] ?: error(%P)",
                        "No handler registered for function: \$functionName",
                    )
                    .addStatement("return handler(args.toList()) as R")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("newResource")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("resourceName", STRING)
                    .addParameter("args", anyNullable, KModifier.VARARG)
                    .returns(INT)
                    .addStatement("check(!isClosed) { %S }", "WasmtimeSession is already closed")
                    .addStatement("val handle = nextHandle++")
                    .addStatement("val argList = args.toList()")
                    .addStatement("val state = resourceConstructors[resourceName]?.invoke(argList) ?: argList")
                    .addStatement("activeResources[handle] = state")
                    .addStatement("return handle")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("invokeMethod")
                    .addModifiers(KModifier.OVERRIDE)
                    .addAnnotation(
                        com.squareup.kotlinpoet.AnnotationSpec.builder(Suppress::class)
                            .addMember("%S", "UNCHECKED_CAST")
                            .build(),
                    )
                    .addTypeVariable(typeR)
                    .addParameter("handle", INT)
                    .addParameter("methodName", STRING)
                    .addParameter("returnType", kTypeClass)
                    .addParameter("args", anyNullable, KModifier.VARARG)
                    .returns(typeR)
                    .addStatement("check(!isClosed) { %S }", "WasmtimeSession is already closed")
                    .addStatement("check(handle in activeResources) { %P }", "Resource handle \$handle is not active")
                    .addStatement(
                        "val handler = methodHandlers[methodName] ?: error(%P)",
                        "No handler registered for resource method: \$methodName",
                    )
                    .addStatement("return handler(handle, args.toList()) as R")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("dropResource")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("handle", INT)
                    .addStatement("check(!isClosed) { %S }", "WasmtimeSession is already closed")
                    .addStatement("check(activeResources.remove(handle) != null) { %P }", "Resource handle \$handle was already dropped or invalid")
                    .addStatement("droppedHandles.add(handle)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("close")
                    .addModifiers(KModifier.OVERRIDE)
                    .addStatement("isClosed = true")
                    .addStatement("activeResources.clear()")
                    .build(),
            )
            .build()
    }

    companion object {
        private val REDUNDANT_PUBLIC_REGEX = Regex("""(?m)^(\s*)public\s+""")

        /**
         * Renders a [FileSpec] and strips redundant `public` visibility modifiers emitted by KotlinPoet.
         */
        fun formatIdiomaticKotlin(fileSpec: FileSpec): String {
            val sb = StringBuilder()
            fileSpec.writeTo(sb)
            return sb.toString().replace(REDUNDANT_PUBLIC_REGEX, "$1")
        }
    }
}
