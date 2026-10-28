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
import com.squareup.kotlinpoet.joinToCode
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
    private var currentWorldCollidingNames: Set<String> = emptySet()

    private val witJsonSerializableClass = ClassName("wasm2kt.runtime", "WitJsonSerializable")
    private val witHostImportDispatcherClass = ClassName("wasm2kt.runtime", "WitHostImportDispatcher")
    private val ffmCodecClass = ClassName("wasm2kt.runtime", "FfmJsonCodec")
    private val jsonElementClass = ClassName("kotlinx.serialization.json", "JsonElement")
    private val jsonArrayClass = ClassName("kotlinx.serialization.json", "JsonArray")
    private val jsonNullClass = ClassName("kotlinx.serialization.json", "JsonNull")

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

        // Detect colliding type names across interfaces in this world so they can be disambiguated cleanly
        val candidateRootTypeIndices = linkedSetOf<Int>()
        for (ifaceId in referencedInterfaceIds) {
            val iface = resolve.interfaces.getOrNull(ifaceId) ?: continue
            for ((_, typeIdx) in iface.types) {
                candidateRootTypeIndices += resolveRootTypeIndex(typeIdx)
            }
        }
        resolve.types.forEachIndexed { idx, typeDef ->
            if (typeDef.owner?.world == worldId) {
                candidateRootTypeIndices += resolveRootTypeIndex(idx)
            }
        }
        val byPascal = mutableMapOf<String, MutableSet<Int>>()
        for (rootIdx in candidateRootTypeIndices) {
            val name = resolve.types.getOrNull(rootIdx)?.name ?: continue
            byPascal.getOrPut(NamingConventions.toPascalCase(name)) { mutableSetOf() }.add(rootIdx)
        }
        currentWorldCollidingNames = byPascal.filterValues { it.size > 1 }.keys

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
                    resourceFunctions = resourceFunctions,
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
                    resourceFunctions = resourceFunctions,
                    emittedTypeIndices = emittedTypeIndices,
                )
            }
        }

        // 2. Build Imports interface with static reflection-free dispatchHostImport
        val importsBuilder = TypeSpec.interfaceBuilder("Imports")
            .addSuperinterface(witHostImportDispatcherClass)
        val dispatchBranches = mutableListOf<CodeBlock>()
        var hasImports = false
        for ((importKey, item) in world.imports) {
            when (item) {
                is WitWorldItem.FunctionItem -> {
                    val fn = item.function
                    if (fn.kind is WitFunctionKind.Freestanding) {
                        importsBuilder.addFunction(buildInterfaceFunctionSpec(fn, worldClassName))
                        dispatchBranches += buildHostImportBranchCode(
                            importSpecKey = fn.name,
                            receiverProperty = null,
                            fn = fn,
                            worldClassName = worldClassName,
                        )
                        hasImports = true
                    }
                }
                is WitWorldItem.InterfaceRef -> {
                    val iface = resolve.interfaces.getOrNull(item.id) ?: continue
                    val rawIfaceName = iface.name ?: importKey
                    val shortIfaceName = rawIfaceName.substringAfter('/').substringBefore('@')
                    if (iface.functions.values.any { it.kind is WitFunctionKind.Freestanding }) {
                        val subIfacePascal = NamingConventions.toPascalCase(shortIfaceName)
                        val subIfacePropName = NamingConventions.toCamelCase(shortIfaceName)
                        val subIfaceBuilder = TypeSpec.interfaceBuilder(subIfacePascal)
                        iface.docs?.contents?.let { subIfaceBuilder.addKdoc("%L\n", it.trim()) }
                        for (fn in iface.functions.values) {
                            if (fn.kind is WitFunctionKind.Freestanding) {
                                subIfaceBuilder.addFunction(buildInterfaceFunctionSpec(fn, worldClassName))
                                dispatchBranches += buildHostImportBranchCode(
                                    importSpecKey = "$shortIfaceName#${fn.name}",
                                    receiverProperty = subIfacePropName,
                                    fn = fn,
                                    worldClassName = worldClassName,
                                )
                            }
                        }
                        worldObjBuilder.addType(subIfaceBuilder.build())
                        importsBuilder.addProperty(
                            PropertySpec.builder(
                                subIfacePropName,
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
                    .addSuperinterface(witHostImportDispatcherClass)
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
            val dispatchCode = CodeBlock.builder()
                .beginControlFlow("return when (importSpec)")
            for (branch in dispatchBranches) {
                dispatchCode.add(branch)
            }
            dispatchCode.addStatement("else -> error(%P)", "Unknown host import: \$importSpec")
            dispatchCode.endControlFlow()

            importsBuilder.addFunction(
                FunSpec.builder("dispatchHostImport")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("importSpec", STRING)
                    .addParameter("args", jsonArrayClass)
                    .returns(jsonElementClass)
                    .addCode(dispatchCode.build())
                    .build(),
            )
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

        // 4. Add openSession & withSession on World object (Path and ByteArray overloads)
        val pathClass = ClassName("java.nio.file", "Path")
        val byteArrayClass = ClassName("kotlin", "ByteArray")
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

        val openSessionBytesBuilder = FunSpec.builder("openSession")
            .addParameter("wasmBytes", byteArrayClass)
            .addParameter(
                ParameterSpec.builder("imports", importsClassName)
                    .apply { if (!hasImports) defaultValue("Imports.EMPTY") }
                    .build(),
            )
            .returns(sessionClassName)
            .addStatement("return Session(%T.open(wasmBytes, imports))", runtimeSessionClass)
        worldObjBuilder.addFunction(openSessionBytesBuilder.build())

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

        val withSessionBytesBuilder = FunSpec.builder("withSession")
            .addModifiers(KModifier.INLINE)
            .addTypeVariable(typeVarR)
            .addParameter("wasmBytes", byteArrayClass)
            .addParameter(
                ParameterSpec.builder("imports", importsClassName)
                    .apply { if (!hasImports) defaultValue("Imports.EMPTY") }
                    .build(),
            )
            .addParameter("block", contextLambdaType)
            .returns(typeVarR)
            .addStatement("return openSession(wasmBytes, imports).use { session -> context(session) { block() } }")
        worldObjBuilder.addFunction(withSessionBytesBuilder.build())

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
                            resourceFunctions = resourceFunctions,
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

    private fun buildHostImportBranchCode(
        importSpecKey: String,
        receiverProperty: String?,
        fn: WitFunction,
        worldClassName: ClassName,
    ): CodeBlock {
        val fnCamelName = NamingConventions.toCamelCase(fn.name)
        val decodedArgs = fn.params.mapIndexed { idx, param ->
            buildDecodeExpression(param.type, CodeBlock.of("args[$idx]"), worldClassName)
        }
        val callExpr = if (receiverProperty != null) {
            CodeBlock.of("%N.%N(%L)", receiverProperty, fnCamelName, decodedArgs.joinToCode(", "))
        } else {
            CodeBlock.of("%N(%L)", fnCamelName, decodedArgs.joinToCode(", "))
        }

        return if (fn.results.isEmpty()) {
            CodeBlock.builder()
                .beginControlFlow("%S ->", importSpecKey)
                .addStatement("%L", callExpr)
                .addStatement("%T", jsonNullClass)
                .endControlFlow()
                .build()
        } else {
            CodeBlock.builder()
                .addStatement("%S -> %T.toJsonElement(%L)", importSpecKey, ffmCodecClass, callExpr)
                .build()
        }
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

    private fun resolveKotlinTypeSimpleName(typeIdx: Int): String {
        val rootIdx = resolveRootTypeIndex(typeIdx)
        val typeDef = resolve.types.getOrNull(rootIdx)
            ?: throw IllegalArgumentException("Invalid WIT type index: $typeIdx")
        val rawName = typeDef.name
            ?: throw IllegalStateException("Named type at index $rootIdx has null name")
        val basePascal = NamingConventions.toPascalCase(rawName)
        if (basePascal !in currentWorldCollidingNames) {
            return basePascal
        }
        val ifaceId = typeDef.owner?.`interface`
        if (ifaceId != null) {
            val ifaceName = resolve.interfaces.getOrNull(ifaceId)?.name
            if (!ifaceName.isNullOrBlank()) {
                val shortIface = ifaceName.substringAfter('/').substringBefore('@')
                return NamingConventions.toPascalCase(shortIface) + basePascal
            }
        }
        val worldId = typeDef.owner?.world
        if (worldId != null) {
            val worldName = resolve.worlds.getOrNull(worldId)?.name
            if (!worldName.isNullOrBlank()) {
                return NamingConventions.toPascalCase(worldName) + basePascal
            }
        }
        return "${basePascal}_$rootIdx"
    }

    private fun containsResource(typeRef: WitTypeRef, visited: MutableSet<Int> = mutableSetOf()): Boolean =
        when (typeRef) {
            is WitTypeRef.Primitive -> false
            is WitTypeRef.Id -> {
                if (!visited.add(typeRef.index)) {
                    false
                } else {
                    when (val kind = resolve.types.getOrNull(typeRef.index)?.kind) {
                        is WitTypeDefKind.Resource, is WitTypeDefKind.Handle -> true
                        is WitTypeDefKind.TypeAlias -> containsResource(kind.type, visited)
                        is WitTypeDefKind.OptionType -> containsResource(kind.type, visited)
                        is WitTypeDefKind.ResultType ->
                            (kind.ok?.let { containsResource(it, visited) } ?: false) ||
                                (kind.err?.let { containsResource(it, visited) } ?: false)
                        is WitTypeDefKind.ListType -> containsResource(kind.type, visited)
                        is WitTypeDefKind.Tuple -> kind.types.any { containsResource(it, visited) }
                        is WitTypeDefKind.Record -> kind.fields.any { containsResource(it.type, visited) }
                        is WitTypeDefKind.Variant -> kind.cases.any { it.type != null && containsResource(it.type, visited) }
                        is WitTypeDefKind.FutureType -> kind.type?.let { containsResource(it, visited) } ?: false
                        is WitTypeDefKind.StreamType -> kind.element?.let { containsResource(it, visited) } ?: false
                        else -> false
                    }
                }
            }
        }

    private fun emitTypeDefinitionIfNeeded(
        typeIdx: Int,
        ownerContainer: TypeSpec.Builder,
        fileBuilder: FileSpec.Builder,
        worldClassName: ClassName,
        sessionClassName: ClassName,
        resourceFunctions: Map<Int, List<WitFunction>>,
        emittedTypeIndices: MutableSet<Int>,
    ) {
        if (!emittedTypeIndices.add(typeIdx)) return
        val typeDef = resolve.types.getOrNull(typeIdx) ?: return
        val rawName = typeDef.name ?: return

        when (val kind = typeDef.kind) {
            is WitTypeDefKind.Record -> {
                val typePascalName = resolveKotlinTypeSimpleName(typeIdx)
                ownerContainer.addType(
                    buildRecordTypeSpec(typePascalName, kind.fields, typeDef.docs, worldClassName, sessionClassName),
                )
            }
            is WitTypeDefKind.Enum -> {
                val typePascalName = resolveKotlinTypeSimpleName(typeIdx)
                ownerContainer.addType(
                    buildEnumTypeSpec(typePascalName, kind.cases, typeDef.docs, worldClassName.nestedClass(typePascalName)),
                )
            }
            is WitTypeDefKind.Flags -> {
                val typePascalName = resolveKotlinTypeSimpleName(typeIdx)
                ownerContainer.addType(
                    buildFlagsTypeSpec(typePascalName, kind.flags, typeDef.docs, worldClassName.nestedClass(typePascalName)),
                )
            }
            is WitTypeDefKind.Variant -> {
                val typePascalName = resolveKotlinTypeSimpleName(typeIdx)
                ownerContainer.addType(
                    buildVariantTypeSpec(typePascalName, kind.cases, typeDef.docs, worldClassName, sessionClassName),
                )
            }
            is WitTypeDefKind.Resource -> {
                val typePascalName = resolveKotlinTypeSimpleName(typeIdx)
                ownerContainer.addType(
                    buildResourceTypeSpec(
                        resourceWitName = rawName,
                        resourcePascalName = typePascalName,
                        docs = typeDef.docs,
                        worldClassName = worldClassName,
                        sessionClassName = sessionClassName,
                        functions = resourceFunctions[typeIdx].orEmpty(),
                    ),
                )
            }
            is WitTypeDefKind.TypeAlias -> {
                if (kind.type is WitTypeRef.Id) {
                    val targetDef = resolve.types.getOrNull(kind.type.index)
                    if (targetDef?.name == rawName) {
                        emitTypeDefinitionIfNeeded(
                            typeIdx = kind.type.index,
                            ownerContainer = ownerContainer,
                            fileBuilder = fileBuilder,
                            worldClassName = worldClassName,
                            sessionClassName = sessionClassName,
                            resourceFunctions = resourceFunctions,
                            emittedTypeIndices = emittedTypeIndices,
                        )
                        return
                    }
                }
                val typePascalName = resolveKotlinTypeSimpleName(typeIdx)
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
        sessionClassName: ClassName,
    ): TypeSpec {
        val recordClassName = worldClassName.nestedClass(name)
        val builder = TypeSpec.classBuilder(name)
            .addSuperinterface(witJsonSerializableClass)
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

        val toWitJsonBuilder = FunSpec.builder("toWitJson")
            .addModifiers(KModifier.OVERRIDE)
            .returns(jsonElementClass)
        if (fields.isEmpty()) {
            toWitJsonBuilder.addStatement("return %T.encodeRecord()", ffmCodecClass)
        } else {
            val pairs = fields.map { field ->
                val propName = NamingConventions.toCamelCase(field.name)
                CodeBlock.of("%S to %N", field.name, propName)
            }
            toWitJsonBuilder.addStatement("return %T.encodeRecord(%L)", ffmCodecClass, pairs.joinToCode(", "))
        }
        builder.addFunction(toWitJsonBuilder.build())

        val hasResourceFields = fields.any { containsResource(it.type) }
        val sessionExpr = if (hasResourceFields) CodeBlock.of("session") else null
        val fromWitJsonBuilder = FunSpec.builder("fromWitJson")
            .addParameter("elem", jsonElementClass)
            .apply {
                if (hasResourceFields) {
                    addParameter("session", sessionClassName)
                }
            }
            .returns(recordClassName)
        if (fields.isEmpty()) {
            fromWitJsonBuilder.addStatement("return %T()", recordClassName)
        } else {
            fromWitJsonBuilder.addStatement("val obj = %T.asJsonObject(elem)", ffmCodecClass)
            val ctorArgs = fields.map { field ->
                val propName = NamingConventions.toCamelCase(field.name)
                val fieldElemExpr = CodeBlock.of("%T.field(obj, %S)", ffmCodecClass, field.name)
                val decodedExpr = buildDecodeExpression(field.type, fieldElemExpr, worldClassName, sessionExpr = sessionExpr)
                CodeBlock.of("%N = %L", propName, decodedExpr)
            }
            fromWitJsonBuilder.addStatement("return %T(%L)", recordClassName, ctorArgs.joinToCode(", "))
        }
        builder.addType(
            TypeSpec.companionObjectBuilder()
                .addFunction(fromWitJsonBuilder.build())
                .build(),
        )

        return builder.build()
    }

    private fun buildEnumTypeSpec(
        name: String,
        cases: List<WitEnumCase>,
        docs: WitDocs?,
        selfClassName: ClassName,
    ): TypeSpec {
        val builder = TypeSpec.enumBuilder(name)
            .addSuperinterface(witJsonSerializableClass)
        docs?.contents?.let { builder.addKdoc("%L\n", it.trim()) }
        for (case in cases) {
            val entryName = NamingConventions.toUpperSnakeCase(case.name)
            val entrySpec = TypeSpec.anonymousClassBuilder()
                .apply { case.docs?.contents?.let { addKdoc("%L\n", it.trim()) } }
                .build()
            builder.addEnumConstant(entryName, entrySpec)
        }

        val toJsonCode = CodeBlock.builder()
            .beginControlFlow("return when (this)")
        for (case in cases) {
            val entryName = NamingConventions.toUpperSnakeCase(case.name)
            toJsonCode.addStatement("%N -> %T.toJsonElement(%S)", entryName, ffmCodecClass, case.name)
        }
        toJsonCode.endControlFlow()
        builder.addFunction(
            FunSpec.builder("toWitJson")
                .addModifiers(KModifier.OVERRIDE)
                .returns(jsonElementClass)
                .addCode(toJsonCode.build())
                .build(),
        )

        val fromJsonCode = CodeBlock.builder()
            .beginControlFlow("return when (val tag = %T.decodeString(elem))", ffmCodecClass)
        for (case in cases) {
            val entryName = NamingConventions.toUpperSnakeCase(case.name)
            fromJsonCode.addStatement("%S -> %N", case.name, entryName)
        }
        fromJsonCode.addStatement("else -> error(%P)", "Unknown $name enum case: \$tag")
        fromJsonCode.endControlFlow()
        builder.addType(
            TypeSpec.companionObjectBuilder()
                .addFunction(
                    FunSpec.builder("fromWitJson")
                        .addParameter("elem", jsonElementClass)
                        .returns(selfClassName)
                        .addCode(fromJsonCode.build())
                        .build(),
                )
                .build(),
        )

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
            .addSuperinterface(witJsonSerializableClass)
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

        val decodeMethod = if (useULong) "decodeULong" else "decodeUInt"
        companionBuilder.addFunction(
            FunSpec.builder("fromWitJson")
                .addParameter("elem", jsonElementClass)
                .returns(selfClassName)
                .addStatement("return %T(%T.$decodeMethod(elem))", selfClassName, ffmCodecClass)
                .build(),
        )

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
        builder.addFunction(
            FunSpec.builder("toWitJson")
                .addModifiers(KModifier.OVERRIDE)
                .returns(jsonElementClass)
                .addStatement("return %T.toJsonElement(raw)", ffmCodecClass)
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
        sessionClassName: ClassName,
    ): TypeSpec {
        val variantClassName = worldClassName.nestedClass(name)
        val builder = TypeSpec.interfaceBuilder(name)
            .addModifiers(KModifier.SEALED)
            .addSuperinterface(witJsonSerializableClass)
        docs?.contents?.let { builder.addKdoc("%L\n", it.trim()) }

        for (case in cases) {
            val caseName = NamingConventions.toPascalCase(case.name)
            if (case.type == null) {
                val objBuilder = TypeSpec.objectBuilder(caseName)
                    .addModifiers(KModifier.DATA)
                    .addSuperinterface(variantClassName)
                    .addFunction(
                        FunSpec.builder("toWitJson")
                            .addModifiers(KModifier.OVERRIDE)
                            .returns(jsonElementClass)
                            .addStatement("return %T.encodeVariant(%S)", ffmCodecClass, case.name)
                            .build(),
                    )
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
                    .addFunction(
                        FunSpec.builder("toWitJson")
                            .addModifiers(KModifier.OVERRIDE)
                            .returns(jsonElementClass)
                            .addStatement("return %T.encodeVariant(%S, value)", ffmCodecClass, case.name)
                            .build(),
                    )
                case.docs?.contents?.let { classBuilder.addKdoc("%L\n", it.trim()) }
                builder.addType(classBuilder.build())
            }
        }

        val hasResourceCases = cases.any { it.type != null && containsResource(it.type) }
        val sessionExpr = if (hasResourceCases) CodeBlock.of("session") else null
        val fromJsonCode = CodeBlock.builder()
            .addStatement("val obj = %T.asJsonObject(elem)", ffmCodecClass)
            .beginControlFlow("return when (val caseTag = %T.variantCase(obj))", ffmCodecClass)
        for (case in cases) {
            val caseName = NamingConventions.toPascalCase(case.name)
            if (case.type == null) {
                fromJsonCode.addStatement("%S -> %N", case.name, caseName)
            } else {
                val payloadElem = CodeBlock.of("%T.variantPayload(obj)", ffmCodecClass)
                val decodedPayload = buildDecodeExpression(case.type, payloadElem, worldClassName, sessionExpr = sessionExpr)
                fromJsonCode.addStatement("%S -> %N(%L)", case.name, caseName, decodedPayload)
            }
        }
        fromJsonCode.addStatement("else -> error(%P)", "Unknown $name variant case: \$caseTag")
        fromJsonCode.endControlFlow()
        builder.addType(
            TypeSpec.companionObjectBuilder()
                .addFunction(
                    FunSpec.builder("fromWitJson")
                        .addParameter("elem", jsonElementClass)
                        .apply {
                            if (hasResourceCases) {
                                addParameter("session", sessionClassName)
                            }
                        }
                        .returns(variantClassName)
                        .addCode(fromJsonCode.build())
                        .build(),
                )
                .build(),
        )

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
            .addSuperinterface(witJsonSerializableClass)
            .addFunction(
                FunSpec.builder("consumeOwnership")
                    .addModifiers(KModifier.INTERNAL)
                    .returns(INT)
                    .addStatement("check(!isClosed) { %P }", "Resource $resourcePascalName(handle=\$handle) is already closed or moved")
                    .addStatement("isClosed = true")
                    .addStatement("return handle")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("toWitJson")
                    .addModifiers(KModifier.OVERRIDE)
                    .returns(jsonElementClass)
                    .addStatement("return %T.toJsonElement(handle)", ffmCodecClass)
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
                    val argFormatPlaceholders = mutableListOf<String>()
                    val formatArgs = mutableListOf<Any>(resourceWitName)
                    for (param in fn.params) {
                        val paramName = NamingConventions.toCamelCase(param.name)
                        invokeBuilder.addParameter(paramName, mapTypeRef(param.type, worldClassName))
                        val (fmt, argVal) = formatArgumentCode(paramName, param.type)
                        argFormatPlaceholders += fmt
                        formatArgs += argVal
                    }
                    val argsJoined = if (argFormatPlaceholders.isEmpty()) "" else ", " + argFormatPlaceholders.joinToString(", ")
                    invokeBuilder.addStatement(
                        "val handle = session.runtimeSession.newResource(%S$argsJoined)",
                        *formatArgs.toTypedArray(),
                    )
                    invokeBuilder.addStatement("return %T(session, handle)", resourceClassName)
                    companionBuilder.addFunction(invokeBuilder.build())
                }
                is WitFunctionKind.Method -> {
                    val cleanMethodName = NamingConventions.extractResourceMemberName(fn.name)
                    val methodBuilder = FunSpec.builder(cleanMethodName)
                    fn.docs?.contents?.let { methodBuilder.addKdoc("%L\n", it.trim()) }
                    val userParams = fn.params.dropWhile { it.name == "self" }
                    val argFormatPlaceholders = mutableListOf<String>()
                    val extraFormatArgs = mutableListOf<Any>()
                    for (param in userParams) {
                        val paramName = NamingConventions.toCamelCase(param.name)
                        methodBuilder.addParameter(paramName, mapTypeRef(param.type, worldClassName))
                        val (fmt, argVal) = formatArgumentCode(paramName, param.type)
                        argFormatPlaceholders += fmt
                        extraFormatArgs += argVal
                    }
                    methodBuilder.addStatement("check(!isClosed) { %P }", "Resource $resourcePascalName(handle=\$handle) is already closed or moved")
                    val returnType = mapFunctionReturnType(fn, worldClassName)
                    methodBuilder.returns(returnType)
                    val argsJoined = if (argFormatPlaceholders.isEmpty()) "" else ", " + argFormatPlaceholders.joinToString(", ")
                    if (returnType == UNIT) {
                        methodBuilder.addStatement(
                            "session.runtimeSession.invokeMethod(handle, %S, { %T.decodeUnit(it) }$argsJoined)",
                            *(listOf<Any>(fn.name, ffmCodecClass) + extraFormatArgs).toTypedArray(),
                        )
                    } else if (returnsOwnedResource(fn)) {
                        methodBuilder.addStatement(
                            "val resultHandle = session.runtimeSession.invokeMethod(handle, %S, { %T.decodeInt(it) }$argsJoined)",
                            *(listOf<Any>(fn.name, ffmCodecClass) + extraFormatArgs).toTypedArray(),
                        )
                        methodBuilder.addStatement("return %T(session, resultHandle)", returnType)
                    } else {
                        val decoderLambda = buildFunctionReturnDecoderLambda(fn, worldClassName)
                        methodBuilder.addStatement(
                            "return session.runtimeSession.invokeMethod(handle, %S, %L$argsJoined)",
                            *(listOf<Any>(fn.name, decoderLambda) + extraFormatArgs).toTypedArray(),
                        )
                    }
                    builder.addFunction(methodBuilder.build())
                }
                is WitFunctionKind.Static -> {
                    val staticName = NamingConventions.extractResourceMemberName(fn.name)
                    val staticBuilder = FunSpec.builder(staticName)
                        .contextParameter("session", sessionClassName)
                    fn.docs?.contents?.let { staticBuilder.addKdoc("%L\n", it.trim()) }
                    val argFormatPlaceholders = mutableListOf<String>()
                    val extraFormatArgs = mutableListOf<Any>()
                    for (param in fn.params) {
                        val paramName = NamingConventions.toCamelCase(param.name)
                        staticBuilder.addParameter(paramName, mapTypeRef(param.type, worldClassName))
                        val (fmt, argVal) = formatArgumentCode(paramName, param.type)
                        argFormatPlaceholders += fmt
                        extraFormatArgs += argVal
                    }
                    val returnType = mapFunctionReturnType(fn, worldClassName)
                    staticBuilder.returns(returnType)
                    val argsJoined = if (argFormatPlaceholders.isEmpty()) "" else ", " + argFormatPlaceholders.joinToString(", ")
                    if (returnType == UNIT) {
                        staticBuilder.addStatement(
                            "session.runtimeSession.invoke(%S, { %T.decodeUnit(it) }$argsJoined)",
                            *(listOf<Any>(fn.name, ffmCodecClass) + extraFormatArgs).toTypedArray(),
                        )
                    } else if (returnsOwnedResource(fn)) {
                        staticBuilder.addStatement(
                            "val resultHandle = session.runtimeSession.invoke(%S, { %T.decodeInt(it) }$argsJoined)",
                            *(listOf<Any>(fn.name, ffmCodecClass) + extraFormatArgs).toTypedArray(),
                        )
                        staticBuilder.addStatement("return %T(session, resultHandle)", returnType)
                    } else {
                        val decoderLambda = buildFunctionReturnDecoderLambda(fn, worldClassName)
                        staticBuilder.addStatement(
                            "return session.runtimeSession.invoke(%S, %L$argsJoined)",
                            *(listOf<Any>(fn.name, decoderLambda) + extraFormatArgs).toTypedArray(),
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

        val argFormatPlaceholders = mutableListOf<String>()
        val extraFormatArgs = mutableListOf<Any>()
        for (param in fn.params) {
            val paramName = NamingConventions.toCamelCase(param.name)
            builder.addParameter(paramName, mapTypeRef(param.type, worldClassName))
            val (fmt, argVal) = formatArgumentCode(paramName, param.type)
            argFormatPlaceholders += fmt
            extraFormatArgs += argVal
        }

        val returnType = mapFunctionReturnType(fn, worldClassName)
        builder.returns(returnType)

        val fullExportName = "$invokeTargetPrefix${fn.name}"
        val argsJoined = if (argFormatPlaceholders.isEmpty()) "" else ", " + argFormatPlaceholders.joinToString(", ")
        if (returnType == UNIT) {
            builder.addStatement(
                "session.runtimeSession.invoke(%S, { %T.decodeUnit(it) }$argsJoined)",
                *(listOf<Any>(fullExportName, ffmCodecClass) + extraFormatArgs).toTypedArray(),
            )
        } else if (returnsOwnedResource(fn)) {
            builder.addStatement(
                "val resultHandle = session.runtimeSession.invoke(%S, { %T.decodeInt(it) }$argsJoined)",
                *(listOf<Any>(fullExportName, ffmCodecClass) + extraFormatArgs).toTypedArray(),
            )
            builder.addStatement("return %T(session, resultHandle)", returnType)
        } else {
            val decoderLambda = buildFunctionReturnDecoderLambda(fn, worldClassName)
            builder.addStatement(
                "return session.runtimeSession.invoke(%S, %L$argsJoined)",
                *(listOf<Any>(fullExportName, decoderLambda) + extraFormatArgs).toTypedArray(),
            )
        }
        return builder.build()
    }

    private fun buildFunctionReturnDecoderLambda(
        fn: WitFunction,
        worldClassName: ClassName,
    ): CodeBlock {
        val sessionExpr = CodeBlock.of("session")
        return when (fn.results.size) {
            0 -> CodeBlock.of("{ %T.decodeUnit(it) }", ffmCodecClass)
            1 -> {
                val body = buildDecodeExpression(fn.results.first().type, CodeBlock.of("elem"), worldClassName, sessionExpr = sessionExpr)
                CodeBlock.of("{ elem -> %L }", body)
            }
            else -> {
                val arity = fn.results.size
                generatedTupleArities += arity
                val tupleClass = ClassName(targetPackage, "Tuple$arity")
                val elemDecoders = fn.results.mapIndexed { idx, res ->
                    buildDecodeExpression(res.type, CodeBlock.of("arr[$idx]"), worldClassName, sessionExpr = sessionExpr)
                }
                CodeBlock.of(
                    "{ elem -> %T.asJsonArray(elem).let { arr -> %T(%L) } }",
                    ffmCodecClass,
                    tupleClass,
                    elemDecoders.joinToCode(", "),
                )
            }
        }
    }

    private fun buildDecodeExpression(
        typeRef: WitTypeRef,
        elemExpr: CodeBlock,
        worldClassName: ClassName,
        depth: Int = 0,
        sessionExpr: CodeBlock? = null,
    ): CodeBlock {
        return when (typeRef) {
            is WitTypeRef.Primitive -> when (typeRef.name) {
                "bool" -> CodeBlock.of("%T.decodeBoolean(%L)", ffmCodecClass, elemExpr)
                "u8" -> CodeBlock.of("%T.decodeUByte(%L)", ffmCodecClass, elemExpr)
                "s8" -> CodeBlock.of("%T.decodeByte(%L)", ffmCodecClass, elemExpr)
                "u16" -> CodeBlock.of("%T.decodeUShort(%L)", ffmCodecClass, elemExpr)
                "s16" -> CodeBlock.of("%T.decodeShort(%L)", ffmCodecClass, elemExpr)
                "u32" -> CodeBlock.of("%T.decodeUInt(%L)", ffmCodecClass, elemExpr)
                "s32" -> CodeBlock.of("%T.decodeInt(%L)", ffmCodecClass, elemExpr)
                "u64" -> CodeBlock.of("%T.decodeULong(%L)", ffmCodecClass, elemExpr)
                "s64" -> CodeBlock.of("%T.decodeLong(%L)", ffmCodecClass, elemExpr)
                "f32", "float32" -> CodeBlock.of("%T.decodeFloat(%L)", ffmCodecClass, elemExpr)
                "f64", "float64" -> CodeBlock.of("%T.decodeDouble(%L)", ffmCodecClass, elemExpr)
                "char" -> CodeBlock.of("%T.decodeChar(%L)", ffmCodecClass, elemExpr)
                "string" -> CodeBlock.of("%T.decodeString(%L)", ffmCodecClass, elemExpr)
                "_" -> CodeBlock.of("%T.decodeUnit(%L)", ffmCodecClass, elemExpr)
                else -> throw IllegalArgumentException("Unknown WIT primitive type: ${typeRef.name}")
            }
            is WitTypeRef.Id -> {
                val typeDef = resolve.types.getOrNull(typeRef.index)
                    ?: throw IllegalArgumentException("Invalid WIT type index: ${typeRef.index}")
                when (val kind = typeDef.kind) {
                    is WitTypeDefKind.Record,
                    is WitTypeDefKind.Enum,
                    is WitTypeDefKind.Flags,
                    is WitTypeDefKind.Variant,
                    -> {
                        val pascal = resolveKotlinTypeSimpleName(typeRef.index)
                        val typeClass = worldClassName.nestedClass(pascal)
                        if (sessionExpr != null && containsResource(typeRef)) {
                            CodeBlock.of("%T.fromWitJson(%L, %L)", typeClass, elemExpr, sessionExpr)
                        } else {
                            CodeBlock.of("%T.fromWitJson(%L)", typeClass, elemExpr)
                        }
                    }
                    is WitTypeDefKind.TypeAlias -> {
                        buildDecodeExpression(kind.type, elemExpr, worldClassName, depth, sessionExpr)
                    }
                    is WitTypeDefKind.Resource -> {
                        if (sessionExpr != null) {
                            val pascal = resolveKotlinTypeSimpleName(typeRef.index)
                            val resourceClass = worldClassName.nestedClass(pascal)
                            CodeBlock.of("%T(%L, %T.decodeInt(%L))", resourceClass, sessionExpr, ffmCodecClass, elemExpr)
                        } else {
                            CodeBlock.of("%T.decodeInt(%L)", ffmCodecClass, elemExpr)
                        }
                    }
                    is WitTypeDefKind.Handle -> {
                        if (sessionExpr != null) {
                            val pascal = resolveKotlinTypeSimpleName(kind.resourceId)
                            val resourceClass = worldClassName.nestedClass(pascal)
                            CodeBlock.of("%T(%L, %T.decodeInt(%L))", resourceClass, sessionExpr, ffmCodecClass, elemExpr)
                        } else {
                            CodeBlock.of("%T.decodeInt(%L)", ffmCodecClass, elemExpr)
                        }
                    }
                    is WitTypeDefKind.OptionType -> {
                        val witOptionClass = ClassName(targetPackage, "WitOption")
                        val varName = "optElem$depth"
                        val innerDecode = buildDecodeExpression(kind.type, CodeBlock.of(varName), worldClassName, depth + 1, sessionExpr)
                        CodeBlock.of("%T.fromWitJson(%L) { %L -> %L }", witOptionClass, elemExpr, varName, innerDecode)
                    }
                    is WitTypeDefKind.ResultType -> {
                        val witResultClass = ClassName(targetPackage, "WitResult")
                        val okVar = "okElem$depth"
                        val errVar = "errElem$depth"
                        val okDecode = kind.ok?.let { buildDecodeExpression(it, CodeBlock.of(okVar), worldClassName, depth + 1, sessionExpr) }
                            ?: CodeBlock.of("Unit")
                        val errDecode = kind.err?.let { buildDecodeExpression(it, CodeBlock.of(errVar), worldClassName, depth + 1, sessionExpr) }
                            ?: CodeBlock.of("Unit")
                        CodeBlock.of(
                            "%T.fromWitJson(%L, { %L -> %L }, { %L -> %L })",
                            witResultClass,
                            elemExpr,
                            okVar,
                            okDecode,
                            errVar,
                            errDecode,
                        )
                    }
                    is WitTypeDefKind.ListType -> {
                        val itemVar = "itemElem$depth"
                        val itemDecode = buildDecodeExpression(kind.type, CodeBlock.of(itemVar), worldClassName, depth + 1, sessionExpr)
                        CodeBlock.of("%T.decodeList(%L) { %L -> %L }", ffmCodecClass, elemExpr, itemVar, itemDecode)
                    }
                    is WitTypeDefKind.Tuple -> {
                        val arity = kind.types.size
                        generatedTupleArities += arity
                        val arrVar = "tupArr$depth"
                        val tupleClass = ClassName(targetPackage, "Tuple$arity")
                        val elemDecoders = kind.types.mapIndexed { idx, t ->
                            buildDecodeExpression(t, CodeBlock.of("$arrVar[$idx]"), worldClassName, depth + 1, sessionExpr)
                        }
                        CodeBlock.of(
                            "%T.asJsonArray(%L).let { %L -> %T(%L) }",
                            ffmCodecClass,
                            elemExpr,
                            arrVar,
                            tupleClass,
                            elemDecoders.joinToCode(", "),
                        )
                    }
                    is WitTypeDefKind.FutureType -> {
                        val futDecode = kind.type?.let { buildDecodeExpression(it, elemExpr, worldClassName, depth + 1, sessionExpr) }
                            ?: CodeBlock.of("Unit")
                        CodeBlock.of("%T.completedFuture(%L)", ClassName("java.util.concurrent", "CompletableFuture"), futDecode)
                    }
                    is WitTypeDefKind.StreamType -> {
                        val itemVar = "streamElem$depth"
                        val itemDecode = kind.element?.let { buildDecodeExpression(it, CodeBlock.of(itemVar), worldClassName, depth + 1, sessionExpr) }
                            ?: CodeBlock.of("Unit")
                        CodeBlock.of("%T.decodeList(%L) { %L -> %L }", ffmCodecClass, elemExpr, itemVar, itemDecode)
                    }
                }
            }
        }
    }

    private fun formatArgumentCode(paramName: String, typeRef: WitTypeRef): Pair<String, String> {
        if (typeRef is WitTypeRef.Id) {
            val def = resolve.types.getOrNull(typeRef.index)
            val kind = def?.kind
            if (kind is WitTypeDefKind.Handle) {
                return when (kind.kind) {
                    wasm2kt.ast.HandleKind.OWN -> "%N.consumeOwnership()" to paramName
                    wasm2kt.ast.HandleKind.BORROW -> "%N.handle" to paramName
                }
            }
        }
        return "%N" to paramName
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
                val pascal = resolveKotlinTypeSimpleName(index)
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
                    ClassName(targetPackage, resolveKotlinTypeSimpleName(index))
                } else {
                    mapTypeRef(kind.type, worldClassName)
                }
            }
            is WitTypeDefKind.Handle -> {
                mapTypeDefByIndex(kind.resourceId, worldClassName)
            }
            is WitTypeDefKind.OptionType -> {
                val innerType = mapTypeRef(kind.type, worldClassName)
                ClassName(targetPackage, "WitOption").parameterizedBy(innerType)
            }
            is WitTypeDefKind.ResultType -> {
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

        // 4. WasmtimeSession runtime bridge interface, FfmWasmtimeSession, JniWasmtimeSession & InMemoryWasmtimeSession
        fileBuilder.addType(buildWasmtimeSessionTypeSpec())
        fileBuilder.addType(buildFfmWasmtimeSessionTypeSpec())
        fileBuilder.addType(buildJniWasmtimeSessionTypeSpec())
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
            .addFunction(
                FunSpec.builder("toWitJson")
                    .addModifiers(KModifier.OVERRIDE)
                    .returns(jsonElementClass)
                    .addStatement("return %T.encodeOptionSome(value)", ffmCodecClass)
                    .build(),
            )
            .build()

        val noneObject = TypeSpec.objectBuilder("None")
            .addModifiers(KModifier.DATA)
            .addSuperinterface(witOptionClass.parameterizedBy(NOTHING))
            .addFunction(
                FunSpec.builder("toWitJson")
                    .addModifiers(KModifier.OVERRIDE)
                    .returns(jsonElementClass)
                    .addStatement("return %T.encodeOptionNone()", ffmCodecClass)
                    .build(),
            )
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

        val fromWitJsonFn = FunSpec.builder("fromWitJson")
            .addModifiers(KModifier.INLINE)
            .addTypeVariable(typeTInvariant)
            .addParameter("elem", jsonElementClass)
            .addParameter(
                "decodeValue",
                LambdaTypeName.get(
                    parameters = listOf(ParameterSpec.unnamed(jsonElementClass)),
                    returnType = typeTInvariant,
                ),
            )
            .returns(witOptionClass.parameterizedBy(typeTInvariant))
            .addCode(
                CodeBlock.builder()
                    .beginControlFlow("return if (%T.isOptionNone(elem))", ffmCodecClass)
                    .addStatement("None")
                    .nextControlFlow("else")
                    .addStatement("Some(decodeValue(%T.optionValue(elem)))", ffmCodecClass)
                    .endControlFlow()
                    .build(),
            )
            .build()

        return TypeSpec.interfaceBuilder("WitOption")
            .addModifiers(KModifier.SEALED)
            .addTypeVariable(typeT)
            .addSuperinterface(witJsonSerializableClass)
            .addType(someClass)
            .addType(noneObject)
            .addFunction(getOrNullFn)
            .addType(
                TypeSpec.companionObjectBuilder()
                    .addFunction(fromWitJsonFn)
                    .build(),
            )
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
            .addFunction(
                FunSpec.builder("toWitJson")
                    .addModifiers(KModifier.OVERRIDE)
                    .returns(jsonElementClass)
                    .addStatement("return %T.encodeResultOk(value)", ffmCodecClass)
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
            .addFunction(
                FunSpec.builder("toWitJson")
                    .addModifiers(KModifier.OVERRIDE)
                    .returns(jsonElementClass)
                    .addStatement("return %T.encodeResultErr(error)", ffmCodecClass)
                    .build(),
            )
            .build()

        val fromWitJsonFn = FunSpec.builder("fromWitJson")
            .addModifiers(KModifier.INLINE)
            .addTypeVariable(typeTInvariant)
            .addTypeVariable(typeEInvariant)
            .addParameter("elem", jsonElementClass)
            .addParameter(
                "decodeOk",
                LambdaTypeName.get(
                    parameters = listOf(ParameterSpec.unnamed(jsonElementClass)),
                    returnType = typeTInvariant,
                ),
            )
            .addParameter(
                "decodeErr",
                LambdaTypeName.get(
                    parameters = listOf(ParameterSpec.unnamed(jsonElementClass)),
                    returnType = typeEInvariant,
                ),
            )
            .returns(witResultClass.parameterizedBy(typeTInvariant, typeEInvariant))
            .addCode(
                CodeBlock.builder()
                    .beginControlFlow("return if (%T.isResultOk(elem))", ffmCodecClass)
                    .addStatement("Ok(decodeOk(%T.resultOkValue(elem)))", ffmCodecClass)
                    .nextControlFlow("else")
                    .addStatement("Err(decodeErr(%T.resultErrValue(elem)))", ffmCodecClass)
                    .endControlFlow()
                    .build(),
            )
            .build()

        return TypeSpec.interfaceBuilder("WitResult")
            .addModifiers(KModifier.SEALED)
            .addTypeVariable(typeT)
            .addTypeVariable(typeE)
            .addSuperinterface(witJsonSerializableClass)
            .addType(okClass)
            .addType(errClass)
            .addType(
                TypeSpec.companionObjectBuilder()
                    .addFunction(fromWitJsonFn)
                    .build(),
            )
            .build()
    }

    private fun buildTupleTypeSpec(arity: Int): TypeSpec {
        val builder = TypeSpec.classBuilder("Tuple$arity")
            .addSuperinterface(witJsonSerializableClass)
        if (arity > 0) {
            builder.addModifiers(KModifier.DATA)
        }
        val ctorBuilder = FunSpec.constructorBuilder()
        val fieldNames = mutableListOf<String>()
        for (i in 0 until arity) {
            val typeVarOut = TypeVariableName("T$i", KModifier.OUT)
            val typeVar = TypeVariableName("T$i")
            builder.addTypeVariable(typeVarOut)
            val fieldName = "f$i"
            fieldNames += fieldName
            ctorBuilder.addParameter(fieldName, typeVar)
            builder.addProperty(
                PropertySpec.builder(fieldName, typeVar)
                    .initializer(fieldName)
                    .build(),
            )
        }
        builder.primaryConstructor(ctorBuilder.build())
        val tupleArgs = fieldNames.joinToString(", ")
        builder.addFunction(
            FunSpec.builder("toWitJson")
                .addModifiers(KModifier.OVERRIDE)
                .returns(jsonElementClass)
                .addStatement("return %T.encodeTuple($tupleArgs)", ffmCodecClass)
                .build(),
        )
        return builder.build()
    }

    private fun buildWasmtimeSessionTypeSpec(): TypeSpec {
        val autoCloseableClass = ClassName("java.lang", "AutoCloseable")
        val pathClass = ClassName("java.nio.file", "Path")
        val byteArrayClass = ClassName("kotlin", "ByteArray")
        val runtimeSessionClass = ClassName(targetPackage, "WasmtimeSession")
        val ffmSessionClass = ClassName(targetPackage, "FfmWasmtimeSession")
        val jniSessionClass = ClassName(targetPackage, "JniWasmtimeSession")
        val typeR = TypeVariableName("R")
        val anyNullable = ClassName("kotlin", "Any").copy(nullable = true)
        val decoderType = LambdaTypeName.get(
            parameters = listOf(ParameterSpec.unnamed(jsonElementClass)),
            returnType = typeR,
        )

        return TypeSpec.interfaceBuilder("WasmtimeSession")
            .addSuperinterface(autoCloseableClass)
            .addFunction(
                FunSpec.builder("invoke")
                    .addModifiers(KModifier.ABSTRACT)
                    .addTypeVariable(typeR)
                    .addParameter("functionName", STRING)
                    .addParameter("decoder", decoderType)
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
                    .addParameter("decoder", decoderType)
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
                        PropertySpec.builder("isFfmAvailable", BOOLEAN, KModifier.PRIVATE)
                            .initializer(
                                "try { Class.forName(%S); true } catch (_: Throwable) { false }",
                                "java.lang.foreign.Linker",
                            )
                            .build(),
                    )
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
                                "{ wasmPath, imports -> if (isFfmAvailable) %T.open(wasmPath, imports) else %T.open(wasmPath, imports) }",
                                ffmSessionClass,
                                jniSessionClass,
                            )
                            .build(),
                    )
                    .addProperty(
                        PropertySpec.builder(
                            "bytesFactory",
                            LambdaTypeName.get(
                                parameters = listOf(
                                    ParameterSpec.builder("wasmBytes", byteArrayClass).build(),
                                    ParameterSpec.builder("imports", anyNullable).build(),
                                ),
                                returnType = runtimeSessionClass,
                            ),
                        )
                            .mutable(true)
                            .initializer(
                                "{ wasmBytes, imports -> if (isFfmAvailable) %T.open(wasmBytes, imports) else %T.open(wasmBytes, imports) }",
                                ffmSessionClass,
                                jniSessionClass,
                            )
                            .build(),
                    )
                    .addFunction(
                        FunSpec.builder("useJniBackend")
                            .addStatement("factory = { wasmPath, imports -> %T.open(wasmPath, imports) }", jniSessionClass)
                            .addStatement("bytesFactory = { wasmBytes, imports -> %T.open(wasmBytes, imports) }", jniSessionClass)
                            .build(),
                    )
                    .addFunction(
                        FunSpec.builder("useFfmBackend")
                            .addStatement("factory = { wasmPath, imports -> %T.open(wasmPath, imports) }", ffmSessionClass)
                            .addStatement("bytesFactory = { wasmBytes, imports -> %T.open(wasmBytes, imports) }", ffmSessionClass)
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
                    .addFunction(
                        FunSpec.builder("open")
                            .addParameter("wasmBytes", byteArrayClass)
                            .addParameter("imports", anyNullable)
                            .returns(runtimeSessionClass)
                            .addStatement("return bytesFactory(wasmBytes, imports)")
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
        val arenaClass = ClassName("java.lang.foreign", "Arena")
        val memorySegmentClass = ClassName("java.lang.foreign", "MemorySegment")
        val pathClass = ClassName("java.nio.file", "Path")
        val byteArrayClass = ClassName("kotlin", "ByteArray")
        val jsonClass = ClassName("kotlinx.serialization.json", "Json")
        val jsonObjectMember = com.squareup.kotlinpoet.MemberName("kotlinx.serialization.json", "jsonObject")
        val jsonPrimitiveMember = com.squareup.kotlinpoet.MemberName("kotlinx.serialization.json", "jsonPrimitive")
        val booleanMember = com.squareup.kotlinpoet.MemberName("kotlinx.serialization.json", "boolean")
        val anyNullable = ClassName("kotlin", "Any").copy(nullable = true)
        val typeR = TypeVariableName("R")
        val decoderType = LambdaTypeName.get(
            parameters = listOf(ParameterSpec.unnamed(jsonElementClass)),
            returnType = typeR,
        )

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
                    .addTypeVariable(typeR)
                    .addParameter("functionName", STRING)
                    .addParameter("decoder", decoderType)
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
                    .addStatement("return decoder(valElem)")
                    .endControlFlow()
                    .build(),
            )
            .addFunction(
                FunSpec.builder("newResource")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("resourceName", STRING)
                    .addParameter("args", anyNullable, KModifier.VARARG)
                    .returns(INT)
                    .addStatement("return invoke(%P, { %T.decodeInt(it) }, *args)", "[constructor]\$resourceName", ffmCodecClass)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("invokeMethod")
                    .addModifiers(KModifier.OVERRIDE)
                    .addTypeVariable(typeR)
                    .addParameter("handle", INT)
                    .addParameter("methodName", STRING)
                    .addParameter("decoder", decoderType)
                    .addParameter("args", anyNullable, KModifier.VARARG)
                    .returns(typeR)
                    .addStatement("val allArgs = arrayOf<Any?>(handle, *args)")
                    .addStatement("return invoke(methodName, decoder, *allArgs)")
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
                    .addFunction(
                        FunSpec.builder("open")
                            .addParameter("wasmBytes", byteArrayClass)
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
                                "val wasmBytesPtr = %T.allocateBytes(sessionArena, wasmBytes)",
                                ffmBridgeClass,
                            )
                            .addStatement("val importsJsonPtr = %T.allocateUtf8CString(sessionArena, %S)", ffmBridgeClass, "[]")
                            .addStatement("val callbackStub = bridge.createHostCallbackStub(sessionArena, imports)")
                            .addStatement("val errCap = 4096L")
                            .addStatement("val errBuf = sessionArena.allocate(errCap)")
                            .addStatement(
                                "val ptr = bridge.sessionOpenBytesHandle.invokeExact(wasmBytesPtr, wasmBytes.size.toLong(), importsJsonPtr, callbackStub, errBuf, errCap) as %T",
                                memorySegmentClass,
                            )
                            .beginControlFlow("if (ptr == %T.NULL)", memorySegmentClass)
                            .addStatement("val errMsg = %T.readNullTerminatedUtf8(errBuf)", ffmBridgeClass)
                            .addStatement("throw IllegalStateException(%P)", "Failed to open Wasmtime session from bytes: \$errMsg")
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

    private fun buildJniWasmtimeSessionTypeSpec(): TypeSpec {
        val runtimeSessionClass = ClassName(targetPackage, "WasmtimeSession")
        val jniSessionClass = ClassName(targetPackage, "JniWasmtimeSession")
        val jniBridgeClass = ClassName("wasm2kt.runtime", "JniWasmtimeBridge")
        val pathClass = ClassName("java.nio.file", "Path")
        val byteArrayClass = ClassName("kotlin", "ByteArray")
        val jsonClass = ClassName("kotlinx.serialization.json", "Json")
        val jsonObjectMember = com.squareup.kotlinpoet.MemberName("kotlinx.serialization.json", "jsonObject")
        val jsonPrimitiveMember = com.squareup.kotlinpoet.MemberName("kotlinx.serialization.json", "jsonPrimitive")
        val booleanMember = com.squareup.kotlinpoet.MemberName("kotlinx.serialization.json", "boolean")
        val anyNullable = ClassName("kotlin", "Any").copy(nullable = true)
        val typeR = TypeVariableName("R")
        val decoderType = LambdaTypeName.get(
            parameters = listOf(ParameterSpec.unnamed(jsonElementClass)),
            returnType = typeR,
        )

        return TypeSpec.classBuilder("JniWasmtimeSession")
            .addSuperinterface(runtimeSessionClass)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addModifiers(KModifier.PRIVATE)
                    .addParameter("sessionPtr", LONG)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("sessionPtr", LONG, KModifier.PRIVATE)
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
                    .addTypeVariable(typeR)
                    .addParameter("functionName", STRING)
                    .addParameter("decoder", decoderType)
                    .addParameter("args", anyNullable, KModifier.VARARG)
                    .returns(typeR)
                    .addStatement("check(!isClosed) { %S }", "JniWasmtimeSession is already closed")
                    .addStatement(
                        "val argsJson = %T.encodeToString(%T.serializer(), %T(args.map { %T.toJsonElement(it) }))",
                        jsonClass,
                        jsonElementClass,
                        jsonArrayClass,
                        ffmCodecClass,
                    )
                    .addStatement("val respStr = %T.nativeSessionInvoke(sessionPtr, functionName, argsJson)", jniBridgeClass)
                    .addStatement("val respObj = %T.parseToJsonElement(respStr).%M", jsonClass, jsonObjectMember)
                    .beginControlFlow("if (!respObj.getValue(%S).%M.%M)", "ok", jsonPrimitiveMember, booleanMember)
                    .addStatement("val errMsg = respObj[%S]?.%M?.content ?: %S", "error", jsonPrimitiveMember, "Unknown Wasmtime error")
                    .addStatement("throw IllegalStateException(errMsg)")
                    .endControlFlow()
                    .addStatement("val valElem = respObj[%S] ?: %T", "value", jsonNullClass)
                    .addStatement("return decoder(valElem)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("newResource")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("resourceName", STRING)
                    .addParameter("args", anyNullable, KModifier.VARARG)
                    .returns(INT)
                    .addStatement("return invoke(%P, { %T.decodeInt(it) }, *args)", "[constructor]\$resourceName", ffmCodecClass)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("invokeMethod")
                    .addModifiers(KModifier.OVERRIDE)
                    .addTypeVariable(typeR)
                    .addParameter("handle", INT)
                    .addParameter("methodName", STRING)
                    .addParameter("decoder", decoderType)
                    .addParameter("args", anyNullable, KModifier.VARARG)
                    .returns(typeR)
                    .addStatement("val allArgs = arrayOf<Any?>(handle, *args)")
                    .addStatement("return invoke(methodName, decoder, *allArgs)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("dropResource")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("handle", INT)
                    .addStatement("check(!isClosed) { %S }", "JniWasmtimeSession is already closed")
                    .addStatement("val respStr = %T.nativeSessionDropResource(sessionPtr, handle)", jniBridgeClass)
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
                    .addStatement("%T.nativeSessionClose(sessionPtr)", jniBridgeClass)
                    .endControlFlow()
                    .build(),
            )
            .addType(
                TypeSpec.companionObjectBuilder()
                    .addFunction(
                        FunSpec.builder("open")
                            .addParameter("wasmPath", pathClass)
                            .addParameter("imports", anyNullable)
                            .returns(jniSessionClass)
                            .addStatement("return %T(%T.openSession(wasmPath, imports))", jniSessionClass, jniBridgeClass)
                            .build(),
                    )
                    .addFunction(
                        FunSpec.builder("open")
                            .addParameter("wasmBytes", byteArrayClass)
                            .addParameter("imports", anyNullable)
                            .returns(jniSessionClass)
                            .addStatement("return %T(%T.openSessionBytes(wasmBytes, imports))", jniSessionClass, jniBridgeClass)
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    private fun buildInMemoryWasmtimeSessionTypeSpec(): TypeSpec {
        val runtimeSessionClass = ClassName(targetPackage, "WasmtimeSession")
        val anyNullable = ClassName("kotlin", "Any").copy(nullable = true)
        val argsListType = LIST.parameterizedBy(anyNullable)
        val typeR = TypeVariableName("R")
        val decoderType = LambdaTypeName.get(
            parameters = listOf(ParameterSpec.unnamed(jsonElementClass)),
            returnType = typeR,
        )
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
                    .addParameter("decoder", decoderType)
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
                    .addParameter("decoder", decoderType)
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
