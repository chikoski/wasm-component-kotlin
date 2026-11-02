package wasm2kt.generator

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.CHAR
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.BYTE_ARRAY
import com.squareup.kotlinpoet.BYTE
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
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.NOTHING
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.SHORT
import com.squareup.kotlinpoet.SET
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

private const val NO_PATH_BACKEND_MESSAGE =
    "These bindings were generated without the Wasmtime backend; open a session with a ComponentBackendFactory " +
        "(e.g. the generated <Name>Component) or assign WasmtimeSession.factory / bytesFactory."

/** KDoc for every generated entry point that loads a precompiled `.cwasm` (#33). */
private const val PRECOMPILED_TRUST_KDOC =
    "Opens a precompiled `.cwasm` made by `precompileComponent` with the same wasm2kt bridge build.\n\n" +
        "**Only pass files from a trusted source** (e.g. produced by your own build): a `.cwasm` contains native " +
        "code that Wasmtime runs without validating it, so a crafted file escapes the WebAssembly sandbox. " +
        "`openSession` / `withSession` accept `.wasm` components only and reject `.cwasm`.\n"

/**
 * Converts a [WitResolve] AST into [GeneratedKotlinFile]s according to the wasm2kt specification.
 */
@OptIn(ExperimentalKotlinPoetApi::class)
class WitToKotlinPoetConverter(
    private val resolve: WitResolve,
    private val targetPackage: String,
    private val targetWorldName: String? = null,
    /**
     * Emit the Wasmtime-backed (FFM / JNI) session implementations and make them the default for
     * `openSession(Path | ByteArray)`. Disable for builds that only use a [wasm2kt.runtime.ComponentBackend]
     * such as the Chicory backend (e.g. Android), so the bindings don't reference the Wasmtime bridge.
     */
    private val emitWasmtimeSessions: Boolean = true,
    /**
     * Imported interfaces not to generate (ADR-0014): fully qualified interface name with version
     * (`wasi:filesystem/types@0.3.0`) to the Kotlin package its bindings were generated into.
     * Every entry must match an interface the world imports.
     */
    private val interfaceMappings: Map<String, String> = emptyMap(),
    /** Manifests of libraries on the class path: their interfaces are used when the world imports them. */
    private val libraryManifests: List<InterfaceManifest> = emptyList(),
    /**
     * Generate only the interfaces the world imports, value-type support and an [InterfaceManifest]
     * (see [manifest]), for a library other bindings map their imports to (ADR-0014).
     */
    private val interfacesOnly: Boolean = false,
) {
    private val generatedTupleArities = mutableSetOf<Int>()

    /** Imported interfaces mapped to existing packages, by interface id (ADR-0014). */
    private var mappedPackages: Map<Int, String> = emptyMap()

    /** The manifest of the generated library; set by [generate] when [interfacesOnly] is on. */
    var manifest: InterfaceManifest? = null
        private set

    // State of the world being generated (ADR-0011).
    private var currentWorldClassName: ClassName = ClassName(targetPackage, "World")
    private var currentWorldId: Int = -1
    private var currentImportedIfaces: Set<Int> = emptySet()
    private var currentExportedIfaces: Set<Int> = emptySet()
    private var currentInlineNames: Map<Int, String> = emptyMap()

    /** True while generating an exported interface: references to exported interfaces mean the export copy. */
    private var exportContext: Boolean = false

    // Shared by all worlds of one generation: each interface is generated once per direction.
    private val interfaceFiles = linkedMapOf<String, InterfaceFile>()
    private val emittedTypeKeys = mutableSetOf<String>()
    private val hostContracts = mutableMapOf<Int, HostContract?>()
    private val exportedInterfaceObjects = mutableSetOf<Int>()

    private val witSessionClass = ClassName(targetPackage, "WitSession")
    private val witExportsClass = ClassName(targetPackage, "WitExports")
    private val linkerClass = ClassName("wasm2kt.runtime", "Linker")
    private val importsFactoryClass = ClassName("wasm2kt.runtime", "ImportsFactory")
    private val hostResourceTableClass = ClassName("wasm2kt.runtime", "HostResourceTable")
    private val witHostResourceClass = ClassName("wasm2kt.runtime", "WitHostResource")
    private val witBorrowedClass = ClassName("wasm2kt.runtime", "WitBorrowed")
    private val hostResourceBoundSessionClass = ClassName(targetPackage, "HostResourceBoundSession")

    private val witJsonSerializableClass = ClassName("wasm2kt.runtime", "WitJsonSerializable")
    private val ffmCodecClass = ClassName("wasm2kt.runtime", "FfmJsonCodec")
    private val codePointClass = ClassName(targetPackage, "CodePoint")
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

        if (interfacesOnly) {
            require(targetWorldName == null || worldsToGenerate.isNotEmpty()) {
                "World '$targetWorldName' not found; available worlds: ${resolve.worlds.joinToString { it.name }}"
            }
            require(worldsToGenerate.size <= 1) {
                "The WIT defines several worlds (${worldsToGenerate.joinToString { it.name }}); choose the one whose imports to generate with --world"
            }
            // Without a world, every interface of the WIT is part of the library.
            val world = worldsToGenerate.singleOrNull() ?: WitWorld(
                name = "interfaces",
                imports = resolve.interfaces.indices.associate { idx -> (resolve.interfaces[idx].name ?: "interface-$idx") to WitWorldItem.InterfaceRef(idx) },
            )
            checkPackageVersions(listOf(world))
            generateInterfaces(world)
            fileSpecs += interfaceFiles.values.map { it.builder.build() }
            fileSpecs.add(0, generateCommonSupportFile())
            return fileSpecs.map { GeneratedKotlinFile(it.packageName, it.name, formatIdiomaticKotlin(it)) }
        }

        // If the WIT file only defines interfaces and no worlds, synthesize a default world
        if (worldsToGenerate.isEmpty() && resolve.interfaces.isNotEmpty() && targetWorldName == null) {
            val syntheticWorld = syntheticStandaloneWorld()
            checkPackageVersions(listOf(syntheticWorld))
            fileSpecs += generateWorldFile(syntheticWorld)
        } else {
            // The world's API is generated at the top level of the target package (ADR-0012), so one
            // generation holds exactly one world.
            require(targetWorldName == null || worldsToGenerate.isNotEmpty()) {
                "World '$targetWorldName' not found; available worlds: ${resolve.worlds.joinToString { it.name }}"
            }
            require(worldsToGenerate.size == 1) {
                "The WIT defines several worlds (${worldsToGenerate.joinToString { it.name }}); choose one with --world " +
                    "(each world is generated into its own package, ADR-0012)"
            }
            checkPackageVersions(worldsToGenerate)
            fileSpecs += generateWorldFile(worldsToGenerate.single())
        }
        fileSpecs += interfaceFiles.values.map { it.builder.build() }

        fileSpecs.add(0, generateCommonSupportFile())
        return fileSpecs.map { spec ->
            GeneratedKotlinFile(
                packageName = spec.packageName,
                name = spec.name,
                content = formatIdiomaticKotlin(spec),
            )
        }
    }

    /** `calculator` -> `CalculatorWorld`; names already ending in "World" are kept (`counter-world` -> `CounterWorld`). */
    private fun worldObjectName(witName: String): String {
        val pascal = NamingConventions.toPascalCase(witName)
        return if (pascal.endsWith("World")) pascal else pascal + "World"
    }

    // ------------------------------------------------------------------ interface identity (ADR-0011)

    /** Whether an interface is generated as the world's import or as its export (ADR-0011). */
    private enum class Direction { IMPORT, EXPORT }

    /** `wasi:http@0.2.0` split into its parts. */
    private data class PackageName(val namespace: String, val name: String, val version: String?)

    private fun packageNameOf(ifaceId: Int): PackageName? {
        val pkgIdx = resolve.interfaces.getOrNull(ifaceId)?.`package` ?: return null
        val raw = resolve.packages.getOrNull(pkgIdx)?.name ?: return null
        val namespace = raw.substringBefore(':', "")
        val rest = raw.substringAfter(':')
        return PackageName(namespace, rest.substringBefore('@'), rest.substringAfter('@', "").ifEmpty { null })
    }

    /** True for an interface declared inline in a world (`import foo: interface { ... }`). */
    private fun isInlineInterface(ifaceId: Int): Boolean =
        resolve.interfaces.getOrNull(ifaceId)?.name == null || packageNameOf(ifaceId) == null

    private fun inlineInterfaceName(ifaceId: Int): String =
        currentInlineNames[ifaceId] ?: resolve.interfaces.getOrNull(ifaceId)?.name ?: "interface-$ifaceId"

    /**
     * The key prefix used by the backends and the [wasm2kt.runtime.Linker]: the fully qualified name
     * (`wasi:http/types@0.2.0`) for package interfaces, the name in the world for inline ones.
     */
    private fun interfaceKey(ifaceId: Int): String {
        if (isInlineInterface(ifaceId)) return inlineInterfaceName(ifaceId)
        val pkg = checkNotNull(packageNameOf(ifaceId))
        val name = checkNotNull(resolve.interfaces[ifaceId].name)
        return "${pkg.namespace}:${pkg.name}/$name" + (pkg.version?.let { "@$it" } ?: "")
    }

    /** The WIT name segments an interface maps to: `[ns, pkg, iface]`, or `[name]` when inline. */
    private fun interfaceNameSegments(ifaceId: Int): List<String> {
        if (isInlineInterface(ifaceId)) return listOf(inlineInterfaceName(ifaceId))
        val pkg = checkNotNull(packageNameOf(ifaceId))
        return listOf(pkg.namespace, pkg.name, checkNotNull(resolve.interfaces[ifaceId].name))
    }

    /** `<base>.<ns>.<pkg>.<iface>`, under `<base>.exports` for exports (ADR-0011). */
    private fun interfacePackage(ifaceId: Int, direction: Direction): String {
        if (direction == Direction.IMPORT) mappedPackages[ifaceId]?.let { return it }
        val segments = buildList {
            if (direction == Direction.EXPORT) add("exports")
            interfaceNameSegments(ifaceId).forEach { add(NamingConventions.toPackageSegment(it)) }
        }
        return if (targetPackage.isEmpty()) segments.joinToString(".") else "$targetPackage.${segments.joinToString(".")}"
    }

    /** Parameter name in `imports(...)`: `wasiHttpTypes`, or the inline interface's own name. */
    private fun importParameterName(ifaceId: Int): String =
        NamingConventions.toCamelCase(interfaceNameSegments(ifaceId).joinToString("-"))

    /**
     * Name of the Kotlin interface the host implements (imports) or the object holding the calls
     * (exports): the interface name in PascalCase, with `Interface` appended when a type of the same
     * interface already has that name (`wasi:io/error` has `resource error`).
     */
    private fun interfaceTypeName(ifaceId: Int): String {
        val pascal = NamingConventions.toPascalCase(interfaceNameSegments(ifaceId).last())
        val typeNames = resolve.interfaces[ifaceId].types.keys.map { NamingConventions.toPascalCase(it) }.toSet()
        return if (pascal in typeNames) "${pascal}Interface" else pascal
    }

    /** Which copy of an interface a reference from the current context points to. */
    private fun directionOf(ifaceId: Int): Direction =
        if (ifaceId in currentExportedIfaces && (exportContext || ifaceId !in currentImportedIfaces)) Direction.EXPORT else Direction.IMPORT

    private class InterfaceFile(val ifaceId: Int, val direction: Direction, val builder: FileSpec.Builder)

    private fun interfaceFile(ifaceId: Int, direction: Direction): InterfaceFile {
        check(direction == Direction.EXPORT || ifaceId !in mappedPackages) { "Mapped interface '${interfaceKey(ifaceId)}' must not be generated" }
        val pkg = interfacePackage(ifaceId, direction)
        val existing = interfaceFiles[pkg]
        if (existing != null) {
            require(existing.ifaceId == ifaceId && existing.direction == direction) {
                "Interfaces '${interfaceKey(existing.ifaceId)}' and '${interfaceKey(ifaceId)}' both map to the Kotlin package '$pkg'"
            }
            return existing
        }
        val name = NamingConventions.toPascalCase(interfaceNameSegments(ifaceId).last())
        return InterfaceFile(ifaceId, direction, FileSpec.builder(pkg, name)).also { interfaceFiles[pkg] = it }
    }

    /**
     * A world can use only one version of a package (ADR-0011): the Kotlin package has no version, so
     * `wasi:http@0.2.0` and `wasi:http@0.3.0` would map to the same classes.
     */
    private fun checkPackageVersions(worlds: List<WitWorld>) {
        val versions = linkedMapOf<String, MutableSet<String?>>()
        for (world in worlds) {
            for (item in world.imports.values + world.exports.values) {
                if (item !is WitWorldItem.InterfaceRef) continue
                val pkg = packageNameOf(item.id) ?: continue
                versions.getOrPut("${pkg.namespace}:${pkg.name}") { linkedSetOf() } += pkg.version
            }
        }
        val conflicts = versions.filterValues { it.size > 1 }
        require(conflicts.isEmpty()) {
            conflicts.entries.joinToString("; ") { (pkg, vs) ->
                "Package '$pkg' is used at several versions (${vs.joinToString { it ?: "unversioned" }})"
            } + ". Bindings can use only one version of a package (ADR-0011)."
        }
    }

    private fun syntheticStandaloneWorld(): WitWorld = WitWorld(
        name = "default",
        exports = resolve.interfaces.indices.associate { idx ->
            val name = resolve.interfaces[idx].name ?: "interface-$idx"
            name to WitWorldItem.InterfaceRef(idx)
        },
    )

    // ------------------------------------------------------------------ world file

    private fun generateWorldFile(world: WitWorld): FileSpec {
        // The world's API lives at the top level of the target package (ADR-0012); the file keeps the
        // `<Name>World` name.
        val worldObjectName = worldObjectName(world.name)
        val worldClassName = ClassName(targetPackage, worldObjectName)
        val sessionClassName = ClassName(targetPackage, "Session")
        val worldImportsClassName = ClassName(targetPackage, "Imports")
        currentWorldClassName = worldClassName

        val fileBuilder = FileSpec.builder(targetPackage, worldObjectName)
        val worldObjBuilder = fileBuilder

        enterWorld(world)
        val worldId = currentWorldId
        val importedIds = currentImportedIfaces
        val exportedIds = currentExportedIfaces

        val resourceFunctions = collectResourceFunctions(importedIds + exportedIds, world)

        // 1. Types: interface types go to the interface's own file and package, world types into the world object.
        for (ifaceId in importedIds) if (ifaceId !in mappedPackages) emitInterfaceTypes(ifaceId, Direction.IMPORT, resourceFunctions)
        for (ifaceId in exportedIds) emitInterfaceTypes(ifaceId, Direction.EXPORT, resourceFunctions)
        val worldSink = TypeSink(
            id = "world:$targetPackage",
            addType = { worldObjBuilder.addType(it) },
            addAlias = { fileBuilder.addTypeAlias(it) },
        )
        resolve.types.forEachIndexed { idx, typeDef ->
            if (worldId >= 0 && typeDef.owner?.world == worldId) {
                emitTypeDefinitionIfNeeded(idx, worldSink, resourceFunctions)
            }
        }

        // 2. Host imports: a Kotlin interface + `Linker.add` per imported interface, the same for
        // world-level functions, and a typed `imports(...)` that builds a complete linker.
        val importParams = mutableListOf<ImportParam>()
        val requiredKeys = mutableListOf<String>()
        for (item in world.imports.values) {
            if (item !is WitWorldItem.InterfaceRef) continue
            val contract = hostContract(item.id) ?: continue
            importParams += ImportParam(importParameterName(item.id), contract.className, contract.addFunction)
            requiredKeys += contract.keys
        }
        val worldContract = worldHostContract(world, worldImportsClassName, fileBuilder)
        if (worldContract != null) {
            importParams += ImportParam("world", worldContract.className, worldContract.addFunction)
            requiredKeys += worldContract.keys
        }
        val hasImports = importParams.isNotEmpty()
        val duplicateParams = importParams.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicateParams.isEmpty()) {
            "World '${world.name}' has imports that map to the same imports(...) parameter: ${duplicateParams.joinToString()}"
        }
        if (hasImports) worldObjBuilder.addFunction(buildImportsFunction(importParams, fileBuilder))

        val linkFunctionName = "link${worldObjectName}Imports"
        val requiredKeysName = "${worldObjectName.replaceFirstChar { it.lowercaseChar() }}ImportKeys"
        fileBuilder.addProperty(
            PropertySpec.builder(requiredKeysName, SET.parameterizedBy(STRING), KModifier.PRIVATE)
                .initializer(
                    if (requiredKeys.isEmpty()) CodeBlock.of("emptySet()")
                    else CodeBlock.of("setOf(\n⇥%L⇤)", requiredKeys.map { CodeBlock.of("%S", it) }.joinToCode(",\n", suffix = ",\n")),
                )
                .build(),
        )
        fileBuilder.addFunction(
            FunSpec.builder(linkFunctionName)
                .addModifiers(KModifier.PRIVATE)
                .addParameter("imports", importsFactoryClass)
                .returns(linkerClass)
                .addStatement("return imports.newLinker().also { it.requireDefined(%N) }", requiredKeysName)
                .build(),
        )

        // 3. Session
        val runtimeSessionClass = ClassName(targetPackage, "WasmtimeSession")
        val exportMarkers = world.exports.values.filterIsInstance<WitWorldItem.InterfaceRef>().map { exportMarkerClassName(it.id) }
        worldObjBuilder.addType(
            TypeSpec.classBuilder("Session")
                .addKdoc("A session of the `%L` world. Close it to release the component instance.\n", world.name)
                .apply { world.docs?.contents?.let { addKdoc("\n%L\n", it.trim()) } }
                .superclass(witSessionClass)
                .addSuperclassConstructorParameter("runtimeSession")
                .addSuperinterfaces(exportMarkers)
                .primaryConstructor(
                    FunSpec.constructorBuilder()
                        .addModifiers(KModifier.INTERNAL)
                        .addParameter("runtimeSession", runtimeSessionClass)
                        .build(),
                )
                .build(),
        )

        // 4. openSession & withSession (Path, ByteArray and ComponentBackendFactory)
        fun importsParam(): ParameterSpec = ParameterSpec.builder("imports", importsFactoryClass)
            .apply { if (!hasImports) defaultValue("%T.EMPTY", importsFactoryClass) }
            .build()

        val pathClass = ClassName("java.nio.file", "Path")
        val byteArrayClass = ClassName("kotlin", "ByteArray")
        for ((paramName, paramType) in listOf("wasmPath" to pathClass, "wasmBytes" to byteArrayClass)) {
            worldObjBuilder.addFunction(
                FunSpec.builder("openSession")
                    .addParameter(paramName, paramType)
                    .addParameter(importsParam())
                    .returns(sessionClassName)
                    .addStatement("val linker = %N(imports)", linkFunctionName)
                    .addStatement("val resources = %T()", hostResourceTableClass)
                    .addStatement("return Session(%T(resources.bind { %T.open($paramName, linker) }, resources))", hostResourceBoundSessionClass, runtimeSessionClass)
                    .build(),
            )
        }

        // Backend-agnostic overload: any runtime implementing ComponentBackendFactory (e.g. the Chicory
        // backend running core modules compiled at build time) can host this world.
        val backendFactoryClass = ClassName("wasm2kt.runtime", "ComponentBackendFactory")
        val backendSessionClass = ClassName(targetPackage, "BackendWasmtimeSession")
        worldObjBuilder.addFunction(
            FunSpec.builder("openSession")
                .addParameter("component", backendFactoryClass)
                .addParameter(importsParam())
                .returns(sessionClassName)
                .addStatement("val linker = %N(imports)", linkFunctionName)
                .addStatement("val resources = %T()", hostResourceTableClass)
                .addStatement(
                    "return Session(%T(%T(resources.bind { component.instantiate(linker) }), resources))",
                    hostResourceBoundSessionClass,
                    backendSessionClass,
                )
                .build(),
        )

        val typeVarR = TypeVariableName("R")
        val contextLambdaType = LambdaTypeName.get(
            receiver = null,
            parameters = emptyList(),
            returnType = typeVarR,
            contextParameters = listOf(sessionClassName),
        )
        for ((paramName, paramType) in listOf("wasmPath" to pathClass, "component" to backendFactoryClass, "wasmBytes" to byteArrayClass)) {
            worldObjBuilder.addFunction(
                FunSpec.builder("withSession")
                    .addModifiers(KModifier.INLINE)
                    .addTypeVariable(typeVarR)
                    .addParameter(paramName, paramType)
                    .addParameter(importsParam())
                    .addParameter("block", contextLambdaType)
                    .returns(typeVarR)
                    .addStatement("return openSession($paramName, imports).use { session -> context(session) { block() } }")
                    .build(),
            )
        }

        // Precompiled .cwasm images run native code without validation, so they get their own,
        // explicitly named entry points instead of being accepted by openSession (#33).
        if (emitWasmtimeSessions) {
            for ((paramName, paramType) in listOf("cwasmPath" to pathClass, "cwasmBytes" to byteArrayClass)) {
                worldObjBuilder.addFunction(
                    FunSpec.builder("openPrecompiledSession")
                        .addKdoc(PRECOMPILED_TRUST_KDOC)
                        .addParameter(paramName, paramType)
                        .addParameter(importsParam())
                        .returns(sessionClassName)
                        .addStatement("val linker = %N(imports)", linkFunctionName)
                        .addStatement("val resources = %T()", hostResourceTableClass)
                        .addStatement(
                            "return Session(%T(resources.bind { %T.openPrecompiled($paramName, linker) }, resources))",
                            hostResourceBoundSessionClass,
                            runtimeSessionClass,
                        )
                        .build(),
                )
                worldObjBuilder.addFunction(
                    FunSpec.builder("withPrecompiledSession")
                        .addKdoc(PRECOMPILED_TRUST_KDOC)
                        .addModifiers(KModifier.INLINE)
                        .addTypeVariable(typeVarR)
                        .addParameter(paramName, paramType)
                        .addParameter(importsParam())
                        .addParameter("block", contextLambdaType)
                        .returns(typeVarR)
                        .addStatement("return openPrecompiledSession($paramName, imports).use { session -> context(session) { block() } }")
                        .build(),
                )
            }
        }

        // 5. Exports: world-level functions on the world object, interfaces in their own package.
        for ((_, item) in world.exports) {
            when (item) {
                is WitWorldItem.FunctionItem -> {
                    if (item.function.kind is WitFunctionKind.Freestanding) {
                        fileBuilder.addFunction(
                            buildExportedExtensionFunctionSpec(
                                fn = item.function,
                                receiverClassName = null,
                                worldClassName = worldClassName,
                                sessionClassName = sessionClassName,
                                invokeTargetPrefix = "",
                            ),
                        )
                    }
                }
                is WitWorldItem.InterfaceRef -> emitExportedInterface(item.id)
                is WitWorldItem.TypeRef -> Unit
            }
        }

        checkReservedWorldNames(world)
        return fileBuilder.build()
    }

    /**
     * Sets up the state of the world being generated: the interfaces it imports (including the
     * owners of types it `use`s) and exports, inline interface names, and the mappings (ADR-0014).
     */
    private fun enterWorld(world: WitWorld) {
        currentWorldId = resolve.worlds.indexOf(world)
        val importedIds = linkedSetOf<Int>()
        world.imports.values.forEach { item ->
            if (item is WitWorldItem.InterfaceRef) importedIds += item.id
            if (item is WitWorldItem.TypeRef) {
                val targetId = resolveRootTypeIndex(item.id)
                resolve.types.getOrNull(targetId)?.owner?.`interface`?.let { importedIds += it }
            }
        }
        val exportedIds = linkedSetOf<Int>()
        world.exports.values.forEach { item -> if (item is WitWorldItem.InterfaceRef) exportedIds += item.id }
        currentImportedIfaces = importedIds
        currentExportedIfaces = exportedIds
        currentInlineNames = (world.imports + world.exports).entries
            .mapNotNull { (key, item) -> (item as? WitWorldItem.InterfaceRef)?.takeIf { resolve.interfaces.getOrNull(it.id)?.name == null }?.let { it.id to key } }
            .toMap()
        exportContext = false
        mappedPackages = resolveMappings(world)
    }

    // ------------------------------------------------------------------ interface mappings (ADR-0014)

    /** `ns:pkg/iface` without the version, to find the same interface at another version. */
    private fun unversioned(key: String): String = key.substringBefore('@')

    /**
     * Which imported interfaces use existing packages: the explicit [interfaceMappings] first, then
     * the [libraryManifests]. Reports keys the world does not import, version mismatches, exported
     * interfaces and mapped interfaces whose dependencies are not mapped.
     */
    private fun resolveMappings(world: WitWorld): Map<Int, String> {
        val imported = currentImportedIfaces.filter { !isInlineInterface(it) }.associateBy { interfaceKey(it) }
        val exported = currentExportedIfaces.filter { !isInlineInterface(it) }.associateBy { interfaceKey(it) }
        val libraryByPackage = libraryManifests.flatMap { m -> m.interfaces.map { (key, pkg) -> pkg to (key to m) } }.groupBy({ it.first }, { it.second })
        val result = linkedMapOf<Int, String>()

        for ((key, pkg) in interfaceMappings) {
            val id = imported[key]
            if (id == null) {
                val sameInterface = imported.keys.filter { unversioned(it) == unversioned(key) }
                throw IllegalArgumentException(
                    when {
                        sameInterface.isNotEmpty() ->
                            "Interface mapping '$key' does not match the version the world '${world.name}' imports: ${sameInterface.joinToString()}"
                        key in exported ->
                            "Interface mapping '$key': the world '${world.name}' only exports it, and exported interfaces cannot be mapped (ADR-0014)"
                        else -> "Interface mapping '$key': the world '${world.name}' does not import this interface"
                    },
                )
            }
            for ((libKey, m) in libraryByPackage[pkg].orEmpty()) {
                require(libKey == key) {
                    "Interface mapping '$key' points to '$pkg', which library ${m.source} generated from '$libKey' (ADR-0014)"
                }
            }
            result[id] = pkg
        }

        for ((key, id) in imported) {
            if (id in result) continue
            val provided = libraryManifests.mapNotNull { m -> m.interfaces[key]?.let { it to m } }
            if (provided.isNotEmpty()) {
                val packages = provided.map { it.first }.distinct()
                require(packages.size == 1) {
                    "Libraries provide '$key' in several packages (${provided.joinToString { "${it.first} in ${it.second.source}" }}); choose one with an explicit mapping"
                }
                result[id] = packages.single()
                continue
            }
            val otherVersions = libraryManifests.flatMap { m -> m.interfaces.keys.filter { unversioned(it) == unversioned(key) }.map { it to m } }
            require(otherVersions.isEmpty()) {
                "The world '${world.name}' imports '$key', but ${otherVersions.joinToString { "${it.second.source} provides '${it.first}'" }}. " +
                    "Use a library generated from the same version, or turn off library mappings (ADR-0014)"
            }
        }

        // A mapped library refers to the interfaces its types use with its own classes, so those must
        // be mapped too.
        for ((id, pkg) in result) {
            val missing = interfaceDependencies(id).filter { it !in result }
            require(missing.isEmpty()) {
                "Interface '${interfaceKey(id)}' is mapped to '$pkg', but the interfaces its types use are not: " +
                    missing.joinToString { "'${interfaceKey(it)}'" } + " (ADR-0014)"
            }
        }
        return result
    }

    /** Interfaces (other than [ifaceId]) whose named types the types and functions of [ifaceId] refer to. */
    private fun interfaceDependencies(ifaceId: Int): Set<Int> {
        val iface = resolve.interfaces[ifaceId]
        val found = linkedSetOf<Int>()
        val visited = mutableSetOf<Int>()
        fun visit(ref: WitTypeRef?) {
            if (ref !is WitTypeRef.Id || !visited.add(ref.index)) return
            val def = resolve.types.getOrNull(ref.index) ?: return
            val owner = def.owner?.`interface`
            if (def.name != null && owner != null && owner != ifaceId) {
                found += owner
                return
            }
            when (val kind = def.kind) {
                is WitTypeDefKind.TypeAlias -> visit(kind.type)
                is WitTypeDefKind.OptionType -> visit(kind.type)
                is WitTypeDefKind.ResultType -> { visit(kind.ok); visit(kind.err) }
                is WitTypeDefKind.ListType -> visit(kind.type)
                is WitTypeDefKind.Tuple -> kind.types.forEach(::visit)
                is WitTypeDefKind.Record -> kind.fields.forEach { visit(it.type) }
                is WitTypeDefKind.Variant -> kind.cases.forEach { visit(it.type) }
                is WitTypeDefKind.Handle -> visit(WitTypeRef.Id(kind.resourceId))
                is WitTypeDefKind.FutureType -> visit(kind.type)
                is WitTypeDefKind.StreamType -> visit(kind.element)
                else -> Unit
            }
        }
        iface.types.values.forEach { visit(WitTypeRef.Id(it)) }
        iface.functions.values.forEach { fn -> fn.params.forEach { visit(it.type) }; fn.results.forEach { visit(it.type) } }
        return found
    }

    /** True for a named type alias owned by an interface whose import copy is mapped: it is expanded. */
    private fun isMappedAlias(typeIdx: Int): Boolean {
        val owner = resolve.types.getOrNull(typeIdx)?.owner?.`interface` ?: return false
        return owner in mappedPackages && directionOf(owner) == Direction.IMPORT
    }

    // ------------------------------------------------------------------ interfaces only (ADR-0014)

    /** A library: the world's imported interfaces and their manifest, without the world itself. */
    private fun generateInterfaces(world: WitWorld) {
        enterWorld(world)
        currentWorldClassName = ClassName(targetPackage, worldObjectName(world.name))
        val inline = currentImportedIfaces.filter { isInlineInterface(it) }
        require(inline.isEmpty()) {
            "--interfaces-only generates package interfaces; the world '${world.name}' imports inline interfaces: " +
                inline.joinToString { "'${inlineInterfaceName(it)}'" }
        }
        val generated = currentImportedIfaces.filter { it !in mappedPackages }
        val resourceFunctions = collectResourceFunctions(currentImportedIfaces, WitWorld(world.name))
        for (ifaceId in generated) emitInterfaceTypes(ifaceId, Direction.IMPORT, resourceFunctions)
        for (ifaceId in generated) hostContract(ifaceId)
        manifest = InterfaceManifest(
            basePackage = targetPackage,
            interfaces = generated.associate { interfaceKey(it) to interfacePackage(it, Direction.IMPORT) }.toSortedMap(),
        )
    }

    /** Where emitted type definitions go: an interface file (top level) or a world object (nested). */
    private class TypeSink(val id: String, val addType: (TypeSpec) -> Unit, val addAlias: (TypeAliasSpec) -> Unit)

    private fun emitInterfaceTypes(ifaceId: Int, direction: Direction, resourceFunctions: Map<Int, List<WitFunction>>) {
        val iface = resolve.interfaces.getOrNull(ifaceId) ?: return
        if (iface.types.isEmpty()) return
        val file = interfaceFile(ifaceId, direction)
        val sink = TypeSink(
            id = "iface:${file.builder.packageName}",
            addType = { file.builder.addType(it) },
            addAlias = { file.builder.addTypeAlias(it) },
        )
        withExportContext(direction == Direction.EXPORT) {
            for ((_, typeIdx) in iface.types) emitTypeDefinitionIfNeeded(typeIdx, sink, resourceFunctions)
        }
    }

    private inline fun <T> withExportContext(export: Boolean, block: () -> T): T {
        val saved = exportContext
        exportContext = export
        try {
            return block()
        } finally {
            exportContext = saved
        }
    }

    // ------------------------------------------------------------------ host imports (ADR-0011)

    /** What the host implements for one imported interface (or for the world's own imports). */
    private class HostContract(val className: ClassName, val addFunction: MemberName, val keys: List<String>)

    private class ImportParam(val name: String, val contractClass: ClassName, val addFunction: MemberName)

    /**
     * The Kotlin interface the host implements for an imported interface, and `fun Linker.add(impl)`
     * that registers it under the interface's fully qualified keys. Generated once per interface;
     * null when the interface has nothing for the host to implement (types only).
     */
    private fun hostContract(ifaceId: Int): HostContract? {
        if (ifaceId in hostContracts) return hostContracts[ifaceId]
        val contract = withExportContext(false) { buildHostContract(ifaceId) }
        hostContracts[ifaceId] = contract
        return contract
    }

    private fun buildHostContract(ifaceId: Int): HostContract? {
        val iface = resolve.interfaces.getOrNull(ifaceId) ?: return null
        val hostResources = iface.types.values.map { resolveRootTypeIndex(it) }
            .filter { resolve.types[it].kind is WitTypeDefKind.Resource && isHostResource(it) }
            .distinct()
        val functions = iface.functions.values.filter { it.kind is WitFunctionKind.Freestanding || isHostResourceFunction(it) }
        if (functions.isEmpty() && hostResources.isEmpty()) return null

        // A mapped interface: the library generated the same contract under the same keys (ADR-0014).
        mappedPackages[ifaceId]?.let { pkg ->
            val keyPrefix = "${interfaceKey(ifaceId)}#"
            val keys = functions.map { "$keyPrefix${it.name}" } + hostResources.map { "$keyPrefix[resource-drop]${resolve.types[it].name}" }
            return HostContract(ClassName(pkg, interfaceTypeName(ifaceId)), MemberName(pkg, "add"), keys)
        }

        val file = interfaceFile(ifaceId, Direction.IMPORT)
        val contractClass = ClassName(file.builder.packageName, interfaceTypeName(ifaceId))
        val contractBuilder = TypeSpec.interfaceBuilder(contractClass)
        iface.docs?.contents?.let { contractBuilder.addKdoc("%L\n\n", it.trim()) }
        contractBuilder.addKdoc("Implemented by the host for `%L`. Register it with `Linker.add` or the world's `imports(...)`.\n", interfaceKey(ifaceId))
        val keyPrefix = "${interfaceKey(ifaceId)}#"
        val keys = mutableListOf<String>()
        val body = CodeBlock.builder()
        for (fn in functions) {
            keys += "$keyPrefix${fn.name}"
            body.add(buildHostImportDefinition(fn, "$keyPrefix${fn.name}", contractBuilder))
        }
        for (res in hostResources) {
            val key = "$keyPrefix[resource-drop]${resolve.types[res].name}"
            keys += key
            body.add(buildHostResourceDropDefinition(key))
        }
        file.builder.addType(contractBuilder.build())
        file.builder.addFunction(buildLinkerAddFunction(contractClass, body.build()))
        return HostContract(contractClass, MemberName(file.builder.packageName, "add"), keys)
    }

    /** The world's own imports: world-level functions and the constructors/statics of world-level resources. */
    private fun worldHostContract(
        world: WitWorld,
        className: ClassName,
        fileBuilder: FileSpec.Builder,
    ): HostContract? {
        val functions = world.imports.values.filterIsInstance<WitWorldItem.FunctionItem>().map { it.function }
            .filter { it.kind is WitFunctionKind.Freestanding || isHostResourceFunction(it) }
        val worldResources = resolve.types.indices.filter { idx ->
            currentWorldId >= 0 && resolve.types[idx].owner?.world == currentWorldId && resolve.types[idx].kind is WitTypeDefKind.Resource
        }
        if (functions.isEmpty() && worldResources.isEmpty()) return null
        val contractBuilder = TypeSpec.interfaceBuilder(className)
        contractBuilder.addKdoc("Implemented by the host for the world's own imports. Register it with `Linker.add` or `imports(...)`.\n")
        val keys = mutableListOf<String>()
        val body = CodeBlock.builder()
        for (fn in functions) {
            keys += fn.name
            body.add(buildHostImportDefinition(fn, fn.name, contractBuilder))
        }
        for (res in worldResources) {
            val key = "[resource-drop]${resolve.types[res].name}"
            keys += key
            body.add(buildHostResourceDropDefinition(key))
        }
        fileBuilder.addType(contractBuilder.build())
        fileBuilder.addFunction(buildLinkerAddFunction(className, body.build()))
        return HostContract(className, MemberName(fileBuilder.packageName, "add"), keys)
    }

    private fun buildLinkerAddFunction(contractClass: ClassName, body: CodeBlock): FunSpec =
        FunSpec.builder("add")
            .receiver(linkerClass)
            .addKdoc("Registers [impl] as the host implementation of [%T].\n", contractClass)
            .addParameter("impl", contractClass)
            .addCode(body)
            .build()

    /**
     * `fun imports(wasiHttpTypes: () -> Types, ...)`: every import is a required parameter, so a
     * missing implementation is a compile error. Each factory runs once per session.
     */
    private fun buildImportsFunction(params: List<ImportParam>, fileBuilder: FileSpec.Builder): FunSpec {
        val builder = FunSpec.builder("imports")
            .addKdoc(
                "Host implementations of this world's imports. Each function is called once per session, " +
                    "so a stateful implementation is not shared between sessions; return the same object to share it.\n",
            )
            .returns(importsFactoryClass)
        val body = CodeBlock.builder()
            .beginControlFlow("return %T", importsFactoryClass)
            .beginControlFlow("%T().apply", linkerClass)
        for (param in params) {
            builder.addParameter(param.name, LambdaTypeName.get(returnType = param.contractClass))
            // Every interface's registration is named `add`; import each under its own alias.
            val alias = "add" + param.name.replaceFirstChar { it.uppercaseChar() }
            fileBuilder.addAliasedImport(param.addFunction, alias)
            body.addStatement("%N(%N())", alias, param.name)
        }
        body.endControlFlow().endControlFlow()
        return builder.addCode(body.build()).build()
    }

    /**
     * `define(key) { args -> ... }` for one host import. Freestanding functions, constructors and
     * statics become abstract members of [contractBuilder]; methods are called on the host object
     * behind `self`.
     */
    private fun buildHostImportDefinition(fn: WitFunction, key: String, contractBuilder: TypeSpec.Builder): CodeBlock {
        val worldClassName = currentWorldClassName
        val decodedArgs = fn.params.mapIndexed { idx, param ->
            buildDecodeExpression(param.type, CodeBlock.of("args[$idx]"), worldClassName)
        }
        val callExpr = when (fn.kind) {
            WitFunctionKind.Freestanding -> {
                contractBuilder.addFunction(buildInterfaceFunctionSpec(fn, worldClassName))
                CodeBlock.of("impl.%N(%L)", NamingConventions.toCamelCase(fn.name), decodedArgs.joinToCode(", "))
            }
            is WitFunctionKind.Method -> {
                val member = NamingConventions.extractResourceMemberName(fn.name)
                CodeBlock.of("%L.%N(%L)", decodedArgs.first(), member, decodedArgs.drop(1).joinToCode(", "))
            }
            else -> {
                val name = hostResourceImportName(fn)
                val spec = FunSpec.builder(name).addModifiers(KModifier.ABSTRACT)
                addFunctionKdoc(spec, fn)
                for (param in fn.params) spec.addParameter(NamingConventions.toCamelCase(param.name), mapTypeRef(param.type, worldClassName))
                spec.returns(
                    if (fn.kind is WitFunctionKind.Constructor) mapTypeDefByIndex(checkNotNull(resourceIdOf(fn)), worldClassName)
                    else mapFunctionReturnType(fn, worldClassName),
                )
                contractBuilder.addFunction(spec.build())
                CodeBlock.of("impl.%N(%L)", name, decodedArgs.joinToCode(", "))
            }
        }
        val argsName = if (fn.params.isEmpty()) "_" else "args"
        return if (fn.results.isEmpty() && fn.kind !is WitFunctionKind.Constructor) {
            CodeBlock.builder()
                .beginControlFlow("define(%S) { $argsName ->", key)
                .addStatement("%L", callExpr)
                .addStatement("%T", jsonNullClass)
                .endControlFlow()
                .build()
        } else {
            CodeBlock.builder().addStatement("define(%S) { $argsName -> %T.toJsonElement(%L) }", key, ffmCodecClass, callExpr).build()
        }
    }

    /** The component dropped its last owning handle to a host resource. */
    private fun buildHostResourceDropDefinition(key: String): CodeBlock =
        CodeBlock.builder()
            .beginControlFlow("define(%S) { args ->", key)
            .addStatement("%T.current().drop(%T.decodeInt(args[0]))", hostResourceTableClass, ffmCodecClass)
            .addStatement("%T", jsonNullClass)
            .endControlFlow()
            .build()

    // ------------------------------------------------------------------ exports

    /** `<Iface>Exports`: implemented by the `Session` of every world that exports the interface (ADR-0012). */
    private fun exportMarkerClassName(ifaceId: Int): ClassName =
        ClassName(interfacePackage(ifaceId, Direction.EXPORT), interfaceTypeName(ifaceId) + "Exports")

    /**
     * An exported interface: in `<base>.exports.<ns>.<pkg>.<iface>`, the marker `<Iface>Exports` and one
     * top-level function per exported function taking it as context, so only a session of a world
     * that exports the interface can call it. Generated once per interface.
     */
    private fun emitExportedInterface(ifaceId: Int) {
        if (!exportedInterfaceObjects.add(ifaceId)) return
        val iface = resolve.interfaces.getOrNull(ifaceId) ?: return
        val file = interfaceFile(ifaceId, Direction.EXPORT)
        val marker = exportMarkerClassName(ifaceId)
        val markerBuilder = TypeSpec.interfaceBuilder(marker).addSuperinterface(witExportsClass)
        iface.docs?.contents?.let { markerBuilder.addKdoc("%L\n\n", it.trim()) }
        markerBuilder.addKdoc(
            "Implemented by the `Session` of every world that exports `%L`; its functions take it as a context parameter.\n",
            interfaceKey(ifaceId),
        )
        file.builder.addType(markerBuilder.build())
        withExportContext(true) {
            for (fn in iface.functions.values) {
                if (fn.kind !is WitFunctionKind.Freestanding) continue
                file.builder.addFunction(
                    buildExportedExtensionFunctionSpec(
                        fn = fn,
                        receiverClassName = null,
                        worldClassName = currentWorldClassName,
                        sessionClassName = marker,
                        invokeTargetPrefix = "${interfaceKey(ifaceId)}#",
                    ),
                )
            }
        }
    }

    /**
     * Names generated at the top level of the target package (ADR-0012). A world-level type or export
     * with one of these names would collide, so it is reported instead of generating broken code.
     */
    private fun checkReservedWorldNames(world: WitWorld) {
        val reservedTypes = setOf(
            "Session", "Imports", "WitSession", "WitExports", "WitOption", "WitResult", "CodePoint", "WasmtimeSession",
            "FfmWasmtimeSession", "JniWasmtimeSession", "InMemoryWasmtimeSession", "BackendWasmtimeSession", "HostResourceBoundSession",
        )
        val reservedFunctions = setOf("imports", "openSession", "withSession", "openPrecompiledSession", "withPrecompiledSession")
        val clashes = mutableListOf<String>()
        resolve.types.forEachIndexed { idx, def ->
            val name = def.name ?: return@forEachIndexed
            if (currentWorldId >= 0 && def.owner?.world == currentWorldId && resolveRootTypeIndex(idx) == idx) {
                val pascal = NamingConventions.toPascalCase(name)
                if (pascal in reservedTypes || Regex("Tuple\\d+").matches(pascal)) clashes += "type '$name'"
            }
        }
        for (item in world.exports.values) {
            val fn = (item as? WitWorldItem.FunctionItem)?.function ?: continue
            if (NamingConventions.toCamelCase(fn.name) in reservedFunctions) clashes += "function '${fn.name}'"
        }
        require(clashes.isEmpty()) {
            "World '${world.name}' declares ${clashes.joinToString()}, which collides with the generated API (ADR-0012)"
        }
    }

    // ------------------------------------------------------------------ resources

    /**
     * Host resources (ADR-0008): resources owned by the world itself, and resources of an interface
     * referenced as the world's import. The export copy of an interface defines guest resources.
     */
    private fun isHostResource(resourceIdx: Int): Boolean {
        val owner = resolve.types.getOrNull(resolveRootTypeIndex(resourceIdx))?.owner ?: return false
        owner.world?.let { return true }
        val ifaceId = owner.`interface` ?: return false
        return directionOf(ifaceId) == Direction.IMPORT
    }

    private fun resourceIdOf(fn: WitFunction): Int? = when (val k = fn.kind) {
        is WitFunctionKind.Constructor -> k.resourceId
        is WitFunctionKind.Method -> k.resourceId
        is WitFunctionKind.Static -> k.resourceId
        WitFunctionKind.Freestanding -> null
    }

    private fun isHostResourceFunction(fn: WitFunction): Boolean = resourceIdOf(fn)?.let { isHostResource(it) } == true

    /** Host contract member name for a host resource's constructor (`newBlob`) or static (`blobMerge`). */
    private fun hostResourceImportName(fn: WitFunction): String {
        val pascal = resolveKotlinTypeSimpleName(checkNotNull(resourceIdOf(fn)))
        return when (fn.kind) {
            is WitFunctionKind.Constructor -> "new$pascal"
            else -> pascal.replaceFirstChar { it.lowercaseChar() } +
                NamingConventions.extractResourceMemberName(fn.name).replaceFirstChar { it.uppercaseChar() }
        }
    }

    /** An interface the host implements for a resource the component imports. */
    private fun buildHostResourceTypeSpec(
        resourcePascalName: String,
        docs: WitDocs?,
        worldClassName: ClassName,
        functions: List<WitFunction>,
    ): TypeSpec {
        val builder = TypeSpec.interfaceBuilder(resourcePascalName).addSuperinterface(witHostResourceClass)
        docs?.contents?.let { builder.addKdoc("%L\n\n", it.trim()) }
        builder.addKdoc(
            "Implemented by the host. Constructors and static functions are members of the interface the host implements; " +
                "[close] runs when the component drops its last owning handle.\n",
        )
        for (fn in functions) {
            if (fn.kind !is WitFunctionKind.Method) continue
            val method = FunSpec.builder(NamingConventions.extractResourceMemberName(fn.name)).addModifiers(KModifier.ABSTRACT)
            addFunctionKdoc(method, fn)
            for (param in fn.params.drop(1)) method.addParameter(NamingConventions.toCamelCase(param.name), mapTypeRef(param.type, worldClassName))
            method.returns(mapFunctionReturnType(fn, worldClassName))
            builder.addFunction(method.build())
        }
        return builder.build()
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
            val aliased = (kind as? WitTypeDefKind.TypeAlias)?.type as? WitTypeRef.Id
            if (aliased != null) {
                val targetDef = resolve.types[aliased.index]
                if (targetDef.name == def.name) {
                    current = aliased.index
                    continue
                }
            }
            break
        }
        return current
    }

    /**
     * The Kotlin simple name of a named type. Types are unique within their interface or world, and
     * each interface has its own package, so no qualification is needed (ADR-0011).
     */
    private fun resolveKotlinTypeSimpleName(typeIdx: Int): String {
        val rootIdx = resolveRootTypeIndex(typeIdx)
        val typeDef = resolve.types.getOrNull(rootIdx)
            ?: throw IllegalArgumentException("Invalid WIT type index: $typeIdx")
        val rawName = typeDef.name
            ?: throw IllegalStateException("Named type at index $rootIdx has null name")
        return NamingConventions.toPascalCase(rawName)
    }

    /**
     * The class of a named type: top level in its interface's package (the import or export copy,
     * depending on the context), or nested in the world object for world-level types (ADR-0011).
     */
    private fun typeClassName(typeIdx: Int): ClassName {
        val rootIdx = resolveRootTypeIndex(typeIdx)
        val pascal = resolveKotlinTypeSimpleName(rootIdx)
        val owner = resolve.types[rootIdx].owner
        val ifaceId = owner?.`interface`
        return when {
            ifaceId != null -> ClassName(interfacePackage(ifaceId, directionOf(ifaceId)), pascal)
            else -> ClassName(targetPackage, pascal)
        }
    }

    /** A named type alias: top level in its interface's file, or in the world's file. */
    private fun typeAliasClassName(typeIdx: Int): ClassName {
        val pascal = NamingConventions.toPascalCase(checkNotNull(resolve.types[typeIdx].name))
        val ifaceId = resolve.types[typeIdx].owner?.`interface`
        return if (ifaceId != null) ClassName(interfacePackage(ifaceId, directionOf(ifaceId)), pascal) else ClassName(targetPackage, pascal)
    }

    /**
     * Whether a value of [typeRef] holds a guest resource, whose wrapper needs the session. Host
     * resources go through the [wasm2kt.runtime.HostResourceTable], so types of imported interfaces
     * never take a session and can be shared with other generations (ADR-0014).
     */
    private fun containsGuestResource(typeRef: WitTypeRef, visited: MutableSet<Int> = mutableSetOf()): Boolean =
        when (typeRef) {
            is WitTypeRef.Primitive -> false
            is WitTypeRef.Id -> {
                if (!visited.add(typeRef.index)) {
                    false
                } else {
                    when (val kind = resolve.types.getOrNull(typeRef.index)?.kind) {
                        is WitTypeDefKind.Resource -> !isHostResource(typeRef.index)
                        is WitTypeDefKind.Handle -> !isHostResource(kind.resourceId)
                        is WitTypeDefKind.TypeAlias -> containsGuestResource(kind.type, visited)
                        is WitTypeDefKind.OptionType -> containsGuestResource(kind.type, visited)
                        is WitTypeDefKind.ResultType ->
                            (kind.ok?.let { containsGuestResource(it, visited) } ?: false) ||
                                (kind.err?.let { containsGuestResource(it, visited) } ?: false)
                        is WitTypeDefKind.ListType -> containsGuestResource(kind.type, visited)
                        is WitTypeDefKind.Tuple -> kind.types.any { containsGuestResource(it, visited) }
                        is WitTypeDefKind.Record -> kind.fields.any { containsGuestResource(it.type, visited) }
                        is WitTypeDefKind.Variant -> kind.cases.any { c -> c.type?.let { containsGuestResource(it, visited) } ?: false }
                        is WitTypeDefKind.FutureType -> kind.type?.let { containsGuestResource(it, visited) } ?: false
                        is WitTypeDefKind.StreamType -> kind.element?.let { containsGuestResource(it, visited) } ?: false
                        else -> false
                    }
                }
            }
        }

    private fun emitTypeDefinitionIfNeeded(
        typeIdx: Int,
        sink: TypeSink,
        resourceFunctions: Map<Int, List<WitFunction>>,
    ) {
        if (!emittedTypeKeys.add("${sink.id}#$typeIdx")) return
        val typeDef = resolve.types.getOrNull(typeIdx) ?: return
        val rawName = typeDef.name ?: return
        val worldClassName = currentWorldClassName

        when (val kind = typeDef.kind) {
            is WitTypeDefKind.Record -> {
                sink.addType(buildRecordTypeSpec(typeClassName(typeIdx), kind.fields, typeDef.docs, worldClassName, witSessionClass))
            }
            is WitTypeDefKind.Enum -> {
                val selfClass = typeClassName(typeIdx)
                sink.addType(buildEnumTypeSpec(selfClass.simpleName, kind.cases, typeDef.docs, selfClass))
            }
            is WitTypeDefKind.Flags -> {
                val selfClass = typeClassName(typeIdx)
                sink.addType(buildFlagsTypeSpec(selfClass.simpleName, kind.flags, typeDef.docs, selfClass))
            }
            is WitTypeDefKind.Variant -> {
                sink.addType(buildVariantTypeSpec(typeClassName(typeIdx), kind.cases, typeDef.docs, worldClassName, witSessionClass))
            }
            is WitTypeDefKind.Resource -> {
                val selfClass = typeClassName(typeIdx)
                if (isHostResource(typeIdx)) {
                    sink.addType(buildHostResourceTypeSpec(selfClass.simpleName, typeDef.docs, worldClassName, resourceFunctions[typeIdx].orEmpty()))
                    return
                }
                val ownerIface = checkNotNull(typeDef.owner?.`interface`) { "Guest resource '$rawName' must belong to an exported interface" }
                sink.addType(
                    buildResourceTypeSpec(
                        resourceClassName = selfClass,
                        keyPrefix = "${interfaceKey(ownerIface)}#",
                        docs = typeDef.docs,
                        worldClassName = worldClassName,
                        sessionClassName = witSessionClass,
                        functions = resourceFunctions[typeIdx].orEmpty(),
                        contextClassName = exportMarkerClassName(ownerIface),
                    ),
                )
            }
            is WitTypeDefKind.TypeAlias -> {
                val aliased = kind.type
                if (aliased is WitTypeRef.Id) {
                    val targetDef = resolve.types.getOrNull(aliased.index)
                    // `use iface.{t}`: the same type under the same name, defined by its owner.
                    if (targetDef?.name == rawName) return
                }
                val aliasClass = typeAliasClassName(typeIdx)
                val aliasSpec = TypeAliasSpec.builder(aliasClass.simpleName, mapTypeRef(kind.type, worldClassName))
                    .apply { typeDef.docs?.contents?.let { addKdoc("%L\n", it.trim()) } }
                    .build()
                sink.addAlias(aliasSpec)
            }
            else -> Unit
        }
    }

    private fun buildRecordTypeSpec(
        recordClassName: ClassName,
        fields: List<WitField>,
        docs: WitDocs?,
        worldClassName: ClassName,
        sessionClassName: ClassName,
    ): TypeSpec {
        val name = recordClassName.simpleName
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
        addContentEqualityIfNeeded(
            builder,
            recordClassName,
            fields.map { NamingConventions.toCamelCase(it.name) to mapTypeRef(it.type, worldClassName) },
        )

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

        val hasResourceFields = fields.any { containsGuestResource(it.type) }
        val sessionExpr = if (hasResourceFields) CodeBlock.of("session") else null
        val fromWitJsonBuilder = FunSpec.builder("fromWitJson")
            .addParameter("elem", jsonElementClass)
            .apply {
                if (hasResourceFields) {
                    addParameter(
                        ParameterSpec.builder("session", sessionClassName.copy(nullable = true))
                            .defaultValue("null")
                            .build(),
                    )
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
        variantClassName: ClassName,
        cases: List<WitVariantCase>,
        docs: WitDocs?,
        worldClassName: ClassName,
        sessionClassName: ClassName,
    ): TypeSpec {
        val name = variantClassName.simpleName
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
                val payloadType = mapTypeRef(checkNotNull(case.type), worldClassName)
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
                addContentEqualityIfNeeded(classBuilder, variantClassName.nestedClass(caseName), listOf("value" to payloadType))
                case.docs?.contents?.let { classBuilder.addKdoc("%L\n", it.trim()) }
                builder.addType(classBuilder.build())
            }
        }

        val hasResourceCases = cases.any { c -> c.type?.let { containsGuestResource(it) } ?: false }
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
                val decodedPayload = buildDecodeExpression(checkNotNull(case.type), payloadElem, worldClassName, sessionExpr = sessionExpr)
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
                                addParameter(
                                    ParameterSpec.builder("session", sessionClassName.copy(nullable = true))
                                        .defaultValue("null")
                                        .build(),
                                )
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
        resourceClassName: ClassName,
        keyPrefix: String,
        docs: WitDocs?,
        worldClassName: ClassName,
        sessionClassName: ClassName,
        functions: List<WitFunction>,
        contextClassName: ClassName,
    ): TypeSpec {
        val resourcePascalName = resourceClassName.simpleName
        val autoCloseableClass = ClassName("java.lang", "AutoCloseable")

        val builder = TypeSpec.classBuilder(resourcePascalName)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addModifiers(KModifier.INTERNAL)
                    .addParameter("session", sessionClassName.copy(nullable = true))
                    .addParameter("handle", INT)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("session", sessionClassName.copy(nullable = true), KModifier.PRIVATE)
                    .initializer("session")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("boundSession", sessionClassName, KModifier.PRIVATE)
                    .getter(
                        FunSpec.getterBuilder()
                            .addStatement(
                                "return checkNotNull(session) { %P }",
                                "Resource $resourcePascalName(handle=\$handle) is not bound to an active client Session",
                            )
                            .build(),
                    )
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
                        .apply { addSessionContext(this, contextClassName, viaMarker = true) }
                        .returns(resourceClassName)
                    addFunctionKdoc(invokeBuilder, fn)
                    val argFormatPlaceholders = mutableListOf<String>()
                    val formatArgs = mutableListOf<Any>("$keyPrefix${fn.name}")
                    for (param in fn.params) {
                        val paramName = NamingConventions.toCamelCase(param.name)
                        invokeBuilder.addParameter(paramName, mapTypeRef(param.type, worldClassName))
                        val (fmt, argVal) = formatArgumentCode(paramName, param.type)
                        argFormatPlaceholders += fmt
                        formatArgs.addAll(argVal)
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
                    addFunctionKdoc(methodBuilder, fn)
                    val userParams = fn.params.dropWhile { it.name == "self" }
                    val argFormatPlaceholders = mutableListOf<String>()
                    val extraFormatArgs = mutableListOf<Any>()
                    for (param in userParams) {
                        val paramName = NamingConventions.toCamelCase(param.name)
                        methodBuilder.addParameter(paramName, mapTypeRef(param.type, worldClassName))
                        val (fmt, argVal) = formatArgumentCode(paramName, param.type)
                        argFormatPlaceholders += fmt
                        extraFormatArgs.addAll(argVal)
                    }
                    methodBuilder.addStatement("check(!isClosed) { %P }", "Resource $resourcePascalName(handle=\$handle) is already closed or moved")
                    val returnType = mapFunctionReturnType(fn, worldClassName)
                    methodBuilder.returns(returnType)
                    val argsJoined = if (argFormatPlaceholders.isEmpty()) "" else ", " + argFormatPlaceholders.joinToString(", ")
                    if (returnType == UNIT) {
                        methodBuilder.addStatement(
                            "boundSession.runtimeSession.invokeMethod(handle, %S, { %T.decodeUnit(it) }$argsJoined)",
                            *(listOf<Any>("$keyPrefix${fn.name}", ffmCodecClass) + extraFormatArgs).toTypedArray(),
                        )
                    } else if (returnsOwnedResource(fn)) {
                        methodBuilder.addStatement(
                            "val resultHandle = boundSession.runtimeSession.invokeMethod(handle, %S, { %T.decodeInt(it) }$argsJoined)",
                            *(listOf<Any>("$keyPrefix${fn.name}", ffmCodecClass) + extraFormatArgs).toTypedArray(),
                        )
                        methodBuilder.addStatement("return %T(boundSession, resultHandle)", returnType)
                    } else {
                        val decoderLambda = buildFunctionReturnDecoderLambda(fn, worldClassName)
                        methodBuilder.addStatement("val session = boundSession")
                        methodBuilder.addStatement(
                            "return session.runtimeSession.invokeMethod(handle, %S, %L$argsJoined)",
                            *(listOf<Any>("$keyPrefix${fn.name}", decoderLambda) + extraFormatArgs).toTypedArray(),
                        )
                    }
                    builder.addFunction(methodBuilder.build())
                }
                is WitFunctionKind.Static -> {
                    val staticName = NamingConventions.extractResourceMemberName(fn.name)
                    val staticBuilder = FunSpec.builder(staticName)
                        .apply { addSessionContext(this, contextClassName, viaMarker = true) }
                    addFunctionKdoc(staticBuilder, fn)
                    val argFormatPlaceholders = mutableListOf<String>()
                    val extraFormatArgs = mutableListOf<Any>()
                    for (param in fn.params) {
                        val paramName = NamingConventions.toCamelCase(param.name)
                        staticBuilder.addParameter(paramName, mapTypeRef(param.type, worldClassName))
                        val (fmt, argVal) = formatArgumentCode(paramName, param.type)
                        argFormatPlaceholders += fmt
                        extraFormatArgs.addAll(argVal)
                    }
                    val returnType = mapFunctionReturnType(fn, worldClassName)
                    staticBuilder.returns(returnType)
                    val argsJoined = if (argFormatPlaceholders.isEmpty()) "" else ", " + argFormatPlaceholders.joinToString(", ")
                    if (returnType == UNIT) {
                        staticBuilder.addStatement(
                            "session.runtimeSession.invoke(%S, { %T.decodeUnit(it) }$argsJoined)",
                            *(listOf<Any>("$keyPrefix${fn.name}", ffmCodecClass) + extraFormatArgs).toTypedArray(),
                        )
                    } else if (returnsOwnedResource(fn)) {
                        staticBuilder.addStatement(
                            "val resultHandle = session.runtimeSession.invoke(%S, { %T.decodeInt(it) }$argsJoined)",
                            *(listOf<Any>("$keyPrefix${fn.name}", ffmCodecClass) + extraFormatArgs).toTypedArray(),
                        )
                        staticBuilder.addStatement("return %T(session, resultHandle)", returnType)
                    } else {
                        val decoderLambda = buildFunctionReturnDecoderLambda(fn, worldClassName)
                        staticBuilder.addStatement(
                            "return session.runtimeSession.invoke(%S, %L$argsJoined)",
                            *(listOf<Any>("$keyPrefix${fn.name}", decoderLambda) + extraFormatArgs).toTypedArray(),
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
                .addStatement("session?.runtimeSession?.dropResource(handle)")
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
        addFunctionKdoc(builder, fn)
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
        receiverClassName: ClassName?,
        worldClassName: ClassName,
        sessionClassName: ClassName,
        invokeTargetPrefix: String,
    ): FunSpec {
        val fnName = NamingConventions.toCamelCase(fn.name)
        val viaMarker = sessionClassName != ClassName(targetPackage, "Session")
        val builder = FunSpec.builder(fnName)
            .apply { if (receiverClassName != null) receiver(receiverClassName) }
            .apply { addSessionContext(this, sessionClassName, viaMarker) }

        addFunctionKdoc(builder, fn)

        val argFormatPlaceholders = mutableListOf<String>()
        val extraFormatArgs = mutableListOf<Any>()
        for (param in fn.params) {
            val paramName = NamingConventions.toCamelCase(param.name)
            builder.addParameter(paramName, mapTypeRef(param.type, worldClassName))
            val (fmt, argVal) = formatArgumentCode(paramName, param.type)
            argFormatPlaceholders += fmt
            extraFormatArgs.addAll(argVal)
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

    /**
     * World functions take the world's `Session`; interface-level functions take the interface's
     * `<Iface>Exports` marker and reach the session through it (ADR-0012).
     */
    private fun addSessionContext(builder: FunSpec.Builder, contextClass: ClassName, viaMarker: Boolean) {
        if (viaMarker) {
            builder.contextParameter("exports", contextClass)
            builder.addStatement("val session = exports.%M", MemberName(targetPackage, "witSession"))
        } else {
            builder.contextParameter("session", contextClass)
        }
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
                "char" -> CodeBlock.of("%T(%T.decodeCodePoint(%L))", codePointClass, ffmCodecClass, elemExpr)
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
                        val typeClass = typeClassName(typeRef.index)
                        if (sessionExpr != null && containsGuestResource(typeRef)) {
                            CodeBlock.of("%T.fromWitJson(%L, %L)", typeClass, elemExpr, sessionExpr)
                        } else {
                            CodeBlock.of("%T.fromWitJson(%L)", typeClass, elemExpr)
                        }
                    }
                    is WitTypeDefKind.TypeAlias -> {
                        buildDecodeExpression(kind.type, elemExpr, worldClassName, depth, sessionExpr)
                    }
                    is WitTypeDefKind.Resource -> {
                        if (isHostResource(typeRef.index)) return hostResourceDecode(typeRef.index, own = true, elemExpr, worldClassName)
                        // Host-side decoding (no client Session) yields an unbound resource wrapper.
                        val resourceClass = typeClassName(typeRef.index)
                        CodeBlock.of("%T(%L, %T.decodeInt(%L))", resourceClass, sessionExpr ?: CodeBlock.of("null"), ffmCodecClass, elemExpr)
                    }
                    is WitTypeDefKind.Handle -> {
                        if (isHostResource(kind.resourceId)) {
                            return hostResourceDecode(kind.resourceId, own = kind.kind == wasm2kt.ast.HandleKind.OWN, elemExpr, worldClassName)
                        }
                        val resourceClass = typeClassName(kind.resourceId)
                        CodeBlock.of("%T(%L, %T.decodeInt(%L))", resourceClass, sessionExpr ?: CodeBlock.of("null"), ffmCodecClass, elemExpr)
                    }
                    is WitTypeDefKind.OptionType -> {
                        val varName = "optElem$depth"
                        val innerDecode = buildDecodeExpression(kind.type, CodeBlock.of(varName), worldClassName, depth + 1, sessionExpr)
                        if (mapTypeRef(kind.type, worldClassName).isNullable) {
                            val witOptionClass = ClassName(targetPackage, "WitOption")
                            CodeBlock.of("%T.fromWitJson(%L) { %L -> %L }", witOptionClass, elemExpr, varName, innerDecode)
                        } else {
                            CodeBlock.of("%T.decodeNullableOption(%L) { %L -> %L }", ffmCodecClass, elemExpr, varName, innerDecode)
                        }
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
                    is WitTypeDefKind.ListType -> if (isPrimitive(kind.type, "u8")) {
                        CodeBlock.of("%T.decodeByteArray(%L)", ffmCodecClass, elemExpr)
                    } else {
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

    /** A host resource coming back from the component: `own` returns it to the host, `borrow` lends it. */
    private fun hostResourceDecode(resourceIdx: Int, own: Boolean, elemExpr: CodeBlock, worldClassName: ClassName): CodeBlock =
        CodeBlock.of(
            "%T.current().%N(%T.decodeInt(%L), %T::class.java)",
            hostResourceTableClass,
            if (own) "take" else "borrow",
            ffmCodecClass,
            elemExpr,
            mapTypeDefByIndex(resourceIdx, worldClassName),
        )

    private fun formatArgumentCode(paramName: String, typeRef: WitTypeRef): Pair<String, List<Any>> {
        if (typeRef is WitTypeRef.Id) {
            val def = resolve.types.getOrNull(typeRef.index)
            val kind = def?.kind
            if (kind is WitTypeDefKind.Handle) {
                if (isHostResource(kind.resourceId)) {
                    // Host objects are moved by the codec; a borrow is only lent for the call.
                    return when (kind.kind) {
                        wasm2kt.ast.HandleKind.OWN -> "%N" to listOf(paramName)
                        wasm2kt.ast.HandleKind.BORROW -> "%T(%N)" to listOf(witBorrowedClass, paramName)
                    }
                }
                return when (kind.kind) {
                    wasm2kt.ast.HandleKind.OWN -> "%N.consumeOwnership()" to listOf(paramName)
                    wasm2kt.ast.HandleKind.BORROW -> "%N.handle" to listOf(paramName)
                }
            }
        }
        return "%N" to listOf(paramName)
    }

    private fun returnsOwnedResource(fn: WitFunction): Boolean {
        if (fn.results.size != 1) return false
        val ref = fn.results.first().type
        if (ref is WitTypeRef.Id) {
            val def = resolve.types.getOrNull(ref.index)
            when (val kind = def?.kind) {
                is WitTypeDefKind.Handle -> return !isHostResource(kind.resourceId)
                is WitTypeDefKind.Resource -> return !isHostResource(ref.index)
                else -> Unit
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

    /** Whether [typeRef] is the WIT primitive [name], looking through anonymous and named aliases. */
    private fun isPrimitive(typeRef: WitTypeRef, name: String): Boolean = when (typeRef) {
        is WitTypeRef.Primitive -> typeRef.name == name
        is WitTypeRef.Id -> (resolve.types.getOrNull(typeRef.index)?.kind as? WitTypeDefKind.TypeAlias)
            ?.let { isPrimitive(it.type, name) } ?: false
    }

    /**
     * `ByteArray` (from `list<u8>`) compares by reference; give classes holding one content-based
     * equals / hashCode / toString so records and variant cases still behave like data classes.
     */
    private fun addContentEqualityIfNeeded(builder: TypeSpec.Builder, className: ClassName, props: List<Pair<String, TypeName>>) {
        val arrayProps = props.filter { it.second.copy(nullable = false) == BYTE_ARRAY }
        if (arrayProps.isEmpty()) return
        val eq = CodeBlock.builder()
            .addStatement("if (this === other) return true")
            .addStatement("if (other !is %T) return false", className)
        for ((name, type) in props) {
            if (type.copy(nullable = false) == BYTE_ARRAY) {
                eq.addStatement("if (!%N.contentEquals(other.%N)) return false", name, name)
            } else {
                eq.addStatement("if (%N != other.%N) return false", name, name)
            }
        }
        eq.addStatement("return true")
        builder.addFunction(
            FunSpec.builder("equals").addModifiers(KModifier.OVERRIDE)
                .addParameter("other", ANY.copy(nullable = true)).returns(BOOLEAN).addCode(eq.build()).build(),
        )
        val hash = CodeBlock.builder().addStatement("var result = 0")
        for ((name, type) in props) {
            val h = if (type.copy(nullable = false) == BYTE_ARRAY) "%N.contentHashCode()" else "%N.hashCode()"
            hash.addStatement("result = 31 * result + $h", name)
        }
        hash.addStatement("return result")
        builder.addFunction(FunSpec.builder("hashCode").addModifiers(KModifier.OVERRIDE).returns(INT).addCode(hash.build()).build())
        val str = CodeBlock.builder().add("return %S", "${className.simpleName}(")
        props.forEachIndexed { i, (name, type) ->
            str.add(" + %S + ", (if (i > 0) ", " else "") + "$name=")
            if (type.copy(nullable = false) == BYTE_ARRAY) str.add("%N.contentToString()", name) else str.add("%N", name)
        }
        str.add(" + %S\n", ")")
        builder.addFunction(
            FunSpec.builder("toString").addModifiers(KModifier.OVERRIDE).returns(STRING).addCode(str.build()).build(),
        )
    }

    /**
     * Adds the WIT docs of [fn] followed by its WIT signature, so the mapping is visible from the
     * generated code (IDE, compiler, AI agents) even without the .wit file (ADR-0006).
     */
    private fun addFunctionKdoc(builder: FunSpec.Builder, fn: WitFunction) {
        fn.docs?.contents?.let { builder.addKdoc("%L\n\n", it.trim()) }
        builder.addKdoc("WIT: `%L`\n", witSignature(fn))
    }

    private fun witSignature(fn: WitFunction): String {
        val name = when (fn.kind) {
            is WitFunctionKind.Constructor -> "constructor"
            else -> fn.name.substringAfterLast('.').removePrefix("[static]").removePrefix("[method]")
        }
        val params = fn.params.joinToString(", ") { "${it.name}: ${witTypeName(it.type)}" }
        val prefix = if (fn.kind is WitFunctionKind.Static) "static " else ""
        val result = when (fn.results.size) {
            0 -> ""
            1 -> " -> ${witTypeName(fn.results.first().type)}"
            else -> " -> (${fn.results.joinToString(", ") { r -> listOfNotNull(r.name, witTypeName(r.type)).joinToString(": ") }})"
        }
        return if (fn.kind is WitFunctionKind.Constructor) "constructor($params)" else "$name: ${prefix}func($params)$result"
    }

    /** The WIT spelling of a type reference: named types by name, anonymous ones structurally. */
    private fun witTypeName(typeRef: WitTypeRef): String = when (typeRef) {
        is WitTypeRef.Primitive -> typeRef.name
        is WitTypeRef.Id -> {
            val def = resolve.types.getOrNull(typeRef.index)
            when {
                def == null -> "?"
                def.name != null -> def.name!!
                else -> when (val kind = def.kind) {
                    is WitTypeDefKind.OptionType -> "option<${witTypeName(kind.type)}>"
                    is WitTypeDefKind.ResultType -> {
                        val ok = kind.ok?.let { witTypeName(it) } ?: "_"
                        val err = kind.err?.let { witTypeName(it) }
                        when {
                            kind.ok == null && err == null -> "result"
                            err == null -> "result<$ok>"
                            else -> "result<$ok, $err>"
                        }
                    }
                    is WitTypeDefKind.ListType -> "list<${witTypeName(kind.type)}>"
                    is WitTypeDefKind.Tuple -> "tuple<${kind.types.joinToString(", ") { witTypeName(it) }}>"
                    is WitTypeDefKind.Handle -> {
                        val res = resolve.types.getOrNull(kind.resourceId)?.name ?: "resource"
                        if (kind.kind == wasm2kt.ast.HandleKind.BORROW) "borrow<$res>" else res
                    }
                    is WitTypeDefKind.FutureType -> kind.type?.let { "future<${witTypeName(it)}>" } ?: "future"
                    is WitTypeDefKind.StreamType -> kind.element?.let { "stream<${witTypeName(it)}>" } ?: "stream"
                    is WitTypeDefKind.TypeAlias -> witTypeName(kind.type)
                    else -> "?"
                }
            }
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
        "char" -> codePointClass
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
            -> typeClassName(index)
            is WitTypeDefKind.TypeAlias -> {
                if (name != null) {
                    // The library's typealias may name its own WitResult etc.; use the definition (ADR-0014).
                    if (isMappedAlias(index)) return mapTypeRef(kind.type, worldClassName)
                    val aliased = kind.type
                    if (aliased is WitTypeRef.Id) {
                        val targetDef = resolve.types.getOrNull(aliased.index)
                        if (targetDef?.name == name) {
                            return mapTypeDefByIndex(aliased.index, worldClassName)
                        }
                    }
                    typeAliasClassName(index)
                } else {
                    mapTypeRef(kind.type, worldClassName)
                }
            }
            is WitTypeDefKind.Handle -> {
                mapTypeDefByIndex(kind.resourceId, worldClassName)
            }
            is WitTypeDefKind.OptionType -> {
                // option<T> is T?, unless T can already be null (option<option<U>> etc.): then WitOption<T>
                // keeps the outer and inner `none` apart (ADR-0006).
                val innerType = mapTypeRef(kind.type, worldClassName)
                if (innerType.isNullable) {
                    ClassName(targetPackage, "WitOption").parameterizedBy(innerType)
                } else {
                    innerType.copy(nullable = true)
                }
            }
            is WitTypeDefKind.ResultType -> {
                val okType = kind.ok?.let { mapTypeRef(it, worldClassName) } ?: UNIT
                val errType = kind.err?.let { mapTypeRef(it, worldClassName) } ?: UNIT
                ClassName(targetPackage, "WitResult").parameterizedBy(okType, errType)
            }
            is WitTypeDefKind.ListType -> {
                if (isPrimitive(kind.type, "u8")) {
                    BYTE_ARRAY
                } else {
                    LIST.parameterizedBy(mapTypeRef(kind.type, worldClassName))
                }
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

        // 2. WitResult<out T, out E> and its helpers
        fileBuilder.addType(buildWitResultTypeSpec())
        buildWitResultHelpers().forEach { fileBuilder.addFunction(it) }
        buildWitResultProperties().forEach { fileBuilder.addProperty(it) }

        // 2b. CodePoint (WIT char)
        fileBuilder.addType(buildCodePointTypeSpec())

        // 3. Any TupleN types used in the WIT definitions
        for (arity in generatedTupleArities.sorted()) {
            fileBuilder.addType(buildTupleTypeSpec(arity))
        }
        // A library of interfaces has no sessions (ADR-0014).
        if (interfacesOnly) return fileBuilder.build()

        // 4. WasmtimeSession runtime bridge interface, FfmWasmtimeSession, JniWasmtimeSession & InMemoryWasmtimeSession
        fileBuilder.addType(buildWasmtimeSessionTypeSpec())
        if (emitWasmtimeSessions) {
            fileBuilder.addType(buildFfmWasmtimeSessionTypeSpec())
            fileBuilder.addType(buildJniWasmtimeSessionTypeSpec())
        }
        fileBuilder.addType(buildInMemoryWasmtimeSessionTypeSpec())
        fileBuilder.addType(buildBackendWasmtimeSessionTypeSpec())
        fileBuilder.addType(buildHostResourceBoundSessionTypeSpec())
        fileBuilder.addType(buildWitSessionTypeSpec())
        fileBuilder.addType(
            TypeSpec.interfaceBuilder(witExportsClass)
                .addKdoc("Base of the `<Iface>Exports` markers, implemented by a world's `Session` for each interface it exports (ADR-0012).\n")
                .build(),
        )
        fileBuilder.addProperty(
            PropertySpec.builder("witSession", witSessionClass, KModifier.INTERNAL)
                .receiver(witExportsClass)
                .getter(FunSpec.getterBuilder().addStatement("return this as %T", witSessionClass).build())
                .build(),
        )

        return fileBuilder.build()
    }

    /**
     * Base class of every world's `Session`. Interface-level code (exported interface calls, guest
     * resources) takes this type so it can be shared by all worlds of one generation (ADR-0011).
     */
    private fun buildWitSessionTypeSpec(): TypeSpec {
        val runtimeSessionClass = ClassName(targetPackage, "WasmtimeSession")
        return TypeSpec.classBuilder(witSessionClass)
            .addModifiers(KModifier.ABSTRACT)
            .addKdoc("A session of any world generated into this package. Close it to release the component instance.\n")
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addModifiers(KModifier.INTERNAL)
                    .addParameter("runtimeSession", runtimeSessionClass)
                    .build(),
            )
            .addProperty(PropertySpec.builder("runtimeSession", runtimeSessionClass, KModifier.INTERNAL).initializer("runtimeSession").build())
            .addSuperinterface(ClassName("java.lang", "AutoCloseable"))
            .addFunction(FunSpec.builder("close").addModifiers(KModifier.OVERRIDE).addStatement("runtimeSession.close()").build())
            .build()
    }

    /** Binds the session's [HostResourceTable] around every call into the component (ADR-0008). */
    private fun buildHostResourceBoundSessionTypeSpec(): TypeSpec {
        val runtimeSessionClass = ClassName(targetPackage, "WasmtimeSession")
        val typeR = TypeVariableName("R")
        val anyNullable = ClassName("kotlin", "Any").copy(nullable = true)
        val decoderType = LambdaTypeName.get(parameters = listOf(ParameterSpec.unnamed(jsonElementClass)), returnType = typeR)
        return TypeSpec.classBuilder("HostResourceBoundSession")
            .addModifiers(KModifier.INTERNAL)
            .addSuperinterface(runtimeSessionClass)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("delegate", runtimeSessionClass)
                    .addParameter("resources", hostResourceTableClass)
                    .build(),
            )
            .addProperty(PropertySpec.builder("delegate", runtimeSessionClass, KModifier.PRIVATE).initializer("delegate").build())
            .addProperty(PropertySpec.builder("resources", hostResourceTableClass, KModifier.PRIVATE).initializer("resources").build())
            .addFunction(
                FunSpec.builder("invoke").addModifiers(KModifier.OVERRIDE).addTypeVariable(typeR)
                    .addParameter("functionName", STRING).addParameter("decoder", decoderType)
                    .addParameter("args", anyNullable, KModifier.VARARG).returns(typeR)
                    .addStatement("return resources.bind { delegate.invoke(functionName, decoder, *args) }")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("newResource").addModifiers(KModifier.OVERRIDE)
                    .addParameter("constructorName", STRING).addParameter("args", anyNullable, KModifier.VARARG).returns(INT)
                    .addStatement("return resources.bind { delegate.newResource(constructorName, *args) }")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("invokeMethod").addModifiers(KModifier.OVERRIDE).addTypeVariable(typeR)
                    .addParameter("handle", INT).addParameter("methodName", STRING).addParameter("decoder", decoderType)
                    .addParameter("args", anyNullable, KModifier.VARARG).returns(typeR)
                    .addStatement("return resources.bind { delegate.invokeMethod(handle, methodName, decoder, *args) }")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("dropResource").addModifiers(KModifier.OVERRIDE).addParameter("handle", INT)
                    .addStatement("resources.bind { delegate.dropResource(handle) }")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("close").addModifiers(KModifier.OVERRIDE)
                    .beginControlFlow("try")
                    .addStatement("delegate.close()")
                    .nextControlFlow("finally")
                    .addStatement("resources.close()")
                    .endControlFlow()
                    .build(),
            )
            .build()
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

    /** `kotlin.Result`-style helpers that keep the WIT error type (ADR-0006). */
    private fun buildWitResultHelpers(): List<FunSpec> {
        val result = ClassName(targetPackage, "WitResult")
        val t = TypeVariableName("T")
        val e = TypeVariableName("E")
        val f = TypeVariableName("F")
        val rv = TypeVariableName("R")
        val receiver = result.parameterizedBy(t, e)
        val exception = ClassName("wasm2kt.runtime", "WitResultException")
        fun fn(name: String, vararg vars: TypeVariableName) =
            FunSpec.builder(name).receiver(receiver).addTypeVariables(listOf(t, e) + vars)
        fun lambda(param: TypeName, ret: TypeName) = LambdaTypeName.get(parameters = listOf(ParameterSpec.unnamed(param)), returnType = ret)
        return listOf(
            fn("getOrNull").returns(t.copy(nullable = true))
                .addKdoc("The `ok` value, or `null` for `err`.\n")
                .addStatement("return (this as? %T.Ok)?.value", result).build(),
            fn("errorOrNull").returns(e.copy(nullable = true))
                .addKdoc("The `err` value, or `null` for `ok`.\n")
                .addStatement("return (this as? %T.Err)?.error", result).build(),
            fn("getOrThrow").returns(t)
                .addKdoc("The `ok` value; throws [%T] carrying the `err` value otherwise.\n", exception)
                .beginControlFlow("return when (this)")
                .addStatement("is %T.Ok -> value", result)
                .addStatement("is %T.Err -> throw %T(error)", result, exception)
                .endControlFlow().build(),
            fn("getOrElse").addModifiers(KModifier.INLINE).returns(t)
                .addParameter("onErr", lambda(e, t))
                .beginControlFlow("return when (this)")
                .addStatement("is %T.Ok -> value", result)
                .addStatement("is %T.Err -> onErr(error)", result)
                .endControlFlow().build(),
            fn("map", rv).addModifiers(KModifier.INLINE).returns(result.parameterizedBy(rv, e))
                .addParameter("transform", lambda(t, rv))
                .beginControlFlow("return when (this)")
                .addStatement("is %T.Ok -> %T.Ok(transform(value))", result, result)
                .addStatement("is %T.Err -> this", result)
                .endControlFlow().build(),
            fn("mapError", f).addModifiers(KModifier.INLINE).returns(result.parameterizedBy(t, f))
                .addParameter("transform", lambda(e, f))
                .beginControlFlow("return when (this)")
                .addStatement("is %T.Ok -> this", result)
                .addStatement("is %T.Err -> %T.Err(transform(error))", result, result)
                .endControlFlow().build(),
            fn("fold", rv).addModifiers(KModifier.INLINE).returns(rv)
                .addParameter("onOk", lambda(t, rv))
                .addParameter("onErr", lambda(e, rv))
                .beginControlFlow("return when (this)")
                .addStatement("is %T.Ok -> onOk(value)", result)
                .addStatement("is %T.Err -> onErr(error)", result)
                .endControlFlow().build(),
        )
    }

    private fun buildWitResultProperties(): List<PropertySpec> {
        val star = ClassName(targetPackage, "WitResult").parameterizedBy(STAR, STAR)
        return listOf(
            PropertySpec.builder("isOk", BOOLEAN).receiver(star)
                .getter(FunSpec.getterBuilder().addStatement("return this is %T.Ok", ClassName(targetPackage, "WitResult")).build()).build(),
            PropertySpec.builder("isErr", BOOLEAN).receiver(star)
                .getter(FunSpec.getterBuilder().addStatement("return this is %T.Err", ClassName(targetPackage, "WitResult")).build()).build(),
        )
    }

    /** WIT `char`: a Unicode scalar value, which Kotlin's 16-bit `Char` cannot always hold (ADR-0006). */
    private fun buildCodePointTypeSpec(): TypeSpec {
        val self = ClassName(targetPackage, "CodePoint")
        val jsonPrimitive = ClassName("kotlinx.serialization.json", "JsonPrimitive")
        return TypeSpec.classBuilder("CodePoint")
            .addKdoc("A WIT `char`: one Unicode scalar value (U+0000..U+10FFFF, excluding surrogates).\n")
            .addAnnotation(ClassName("kotlin.jvm", "JvmInline"))
            .addModifiers(KModifier.VALUE)
            .addSuperinterface(witJsonSerializableClass)
            .primaryConstructor(FunSpec.constructorBuilder().addParameter("value", INT).build())
            .addProperty(PropertySpec.builder("value", INT).initializer("value").build())
            .addInitializerBlock(
                CodeBlock.builder()
                    .addStatement(
                        "require(value in 0..0x10FFFF && value !in 0xD800..0xDFFF) { %P }",
                        "Not a Unicode scalar value: U+\${value.toString(16).uppercase()}",
                    ).build(),
            )
            .addFunction(
                FunSpec.builder("toWitJson").addModifiers(KModifier.OVERRIDE).returns(jsonElementClass)
                    .addStatement("return %T(toString())", jsonPrimitive).build(),
            )
            .addFunction(
                FunSpec.builder("toString").addModifiers(KModifier.OVERRIDE).returns(STRING)
                    .addStatement("return String(Character.toChars(value))").build(),
            )
            .addType(
                TypeSpec.companionObjectBuilder()
                    .addFunction(
                        FunSpec.builder("of").addParameter("char", CHAR).returns(self)
                            .addStatement("return %T(char.code)", self).build(),
                    )
                    .addFunction(
                        FunSpec.builder("of").addParameter("text", STRING).returns(self)
                            .addStatement("require(text.isNotEmpty() && text.codePointCount(0, text.length) == 1) { %P }", "Expected exactly one code point, got \"\$text\"")
                            .addStatement("return %T(text.codePointAt(0))", self).build(),
                    )
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
                    .addParameter("constructorName", STRING)
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
                    .apply {
                        if (emitWasmtimeSessions) {
                            addProperty(
                                PropertySpec.builder("isFfmAvailable", BOOLEAN, KModifier.PRIVATE)
                                    .initializer(
                                        "try { Class.forName(%S); true } catch (_: Throwable) { false }",
                                        "java.lang.foreign.Linker",
                                    )
                                    .build(),
                            )
                        }
                    }
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
                            .apply {
                                if (emitWasmtimeSessions) {
                                    initializer(
                                        "{ wasmPath, imports -> if (isFfmAvailable) %T.open(wasmPath, imports) else %T.open(wasmPath, imports) }",
                                        ffmSessionClass,
                                        jniSessionClass,
                                    )
                                } else {
                                    initializer("{ _, _ -> error(%S) }", NO_PATH_BACKEND_MESSAGE)
                                }
                            }
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
                            .apply {
                                if (emitWasmtimeSessions) {
                                    initializer(
                                        "{ wasmBytes, imports -> if (isFfmAvailable) %T.open(wasmBytes, imports) else %T.open(wasmBytes, imports) }",
                                        ffmSessionClass,
                                        jniSessionClass,
                                    )
                                } else {
                                    initializer("{ _, _ -> error(%S) }", NO_PATH_BACKEND_MESSAGE)
                                }
                            }
                            .build(),
                    )
                    .addProperty(precompiledFactoryProperty("precompiledFactory", "cwasmPath", pathClass, runtimeSessionClass, ffmSessionClass, jniSessionClass))
                    .addProperty(precompiledFactoryProperty("precompiledBytesFactory", "cwasmBytes", byteArrayClass, runtimeSessionClass, ffmSessionClass, jniSessionClass))
                    .apply {
                        if (emitWasmtimeSessions) {
                            addFunction(
                                FunSpec.builder("useJniBackend")
                                    .addStatement("factory = { wasmPath, imports -> %T.open(wasmPath, imports) }", jniSessionClass)
                                    .addStatement("bytesFactory = { wasmBytes, imports -> %T.open(wasmBytes, imports) }", jniSessionClass)
                                    .addStatement("precompiledFactory = { cwasmPath, imports -> %T.openPrecompiled(cwasmPath, imports) }", jniSessionClass)
                                    .addStatement("precompiledBytesFactory = { cwasmBytes, imports -> %T.openPrecompiled(cwasmBytes, imports) }", jniSessionClass)
                                    .build(),
                            )
                            addFunction(
                                FunSpec.builder("useFfmBackend")
                                    .addStatement("factory = { wasmPath, imports -> %T.open(wasmPath, imports) }", ffmSessionClass)
                                    .addStatement("bytesFactory = { wasmBytes, imports -> %T.open(wasmBytes, imports) }", ffmSessionClass)
                                    .addStatement("precompiledFactory = { cwasmPath, imports -> %T.openPrecompiled(cwasmPath, imports) }", ffmSessionClass)
                                    .addStatement("precompiledBytesFactory = { cwasmBytes, imports -> %T.openPrecompiled(cwasmBytes, imports) }", ffmSessionClass)
                                    .build(),
                            )
                        }
                    }
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
                    .addFunction(
                        FunSpec.builder("openPrecompiled")
                            .addKdoc(PRECOMPILED_TRUST_KDOC)
                            .addParameter("cwasmPath", pathClass)
                            .addParameter("imports", anyNullable)
                            .returns(runtimeSessionClass)
                            .addStatement("return precompiledFactory(cwasmPath, imports)")
                            .build(),
                    )
                    .addFunction(
                        FunSpec.builder("openPrecompiled")
                            .addKdoc(PRECOMPILED_TRUST_KDOC)
                            .addParameter("cwasmBytes", byteArrayClass)
                            .addParameter("imports", anyNullable)
                            .returns(runtimeSessionClass)
                            .addStatement("return precompiledBytesFactory(cwasmBytes, imports)")
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    /** `precompiledFactory` / `precompiledBytesFactory` on the generated `WasmtimeSession` companion. */
    private fun precompiledFactoryProperty(
        name: String,
        paramName: String,
        paramType: TypeName,
        runtimeSessionClass: ClassName,
        ffmSessionClass: ClassName,
        jniSessionClass: ClassName,
    ): PropertySpec {
        val anyNullable = ClassName("kotlin", "Any").copy(nullable = true)
        return PropertySpec.builder(
            name,
            LambdaTypeName.get(
                parameters = listOf(
                    ParameterSpec.builder(paramName, paramType).build(),
                    ParameterSpec.builder("imports", anyNullable).build(),
                ),
                returnType = runtimeSessionClass,
            ),
        )
            .mutable(true)
            .apply {
                if (emitWasmtimeSessions) {
                    initializer(
                        "{ $paramName, imports -> if (isFfmAvailable) %T.openPrecompiled($paramName, imports) else %T.openPrecompiled($paramName, imports) }",
                        ffmSessionClass,
                        jniSessionClass,
                    )
                } else {
                    initializer("{ _, _ -> error(%S) }", NO_PATH_BACKEND_MESSAGE)
                }
            }
            .build()
    }

    private fun buildFfmWasmtimeSessionTypeSpec(): TypeSpec {
        val runtimeSessionClass = ClassName(targetPackage, "WasmtimeSession")
        val ffmSessionClass = ClassName(targetPackage, "FfmWasmtimeSession")
        val ffmBridgeClass = ClassName("wasm2kt.runtime", "FfmWasmtimeBridge")
        val pathClass = ClassName("java.nio.file", "Path")
        val byteArrayClass = ClassName("kotlin", "ByteArray")
        val jsonClass = ClassName("kotlinx.serialization.json", "Json")
        val jsonObjectMember = com.squareup.kotlinpoet.MemberName("kotlinx.serialization.json", "jsonObject")
        val jsonPrimitiveMember = com.squareup.kotlinpoet.MemberName("kotlinx.serialization.json", "jsonPrimitive")
        val booleanMember = com.squareup.kotlinpoet.MemberName("kotlinx.serialization.json", "boolean")
        val anyNullable = ClassName("kotlin", "Any").copy(nullable = true)
        val synchronizedAnnotation = ClassName("kotlin.jvm", "Synchronized")
        val typeR = TypeVariableName("R")
        val decoderType = LambdaTypeName.get(
            parameters = listOf(ParameterSpec.unnamed(jsonElementClass)),
            returnType = typeR,
        )

        // NOTE: This generated class intentionally does not reference `java.lang.foreign.*` directly.
        // All FFM types (Arena / MemorySegment) are encapsulated inside FfmWasmtimeBridge so the generated
        // WitSupport.kt also compiles against Android SDKs that lack the FFM API.
        return TypeSpec.classBuilder("FfmWasmtimeSession")
            .addSuperinterface(runtimeSessionClass)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addModifiers(KModifier.PRIVATE)
                    .addParameter("bridge", ffmBridgeClass)
                    .addParameter("sessionPtr", LONG)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("bridge", ffmBridgeClass, KModifier.PRIVATE)
                    .initializer("bridge")
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
                    .addAnnotation(synchronizedAnnotation)
                    .addModifiers(KModifier.OVERRIDE)
                    .addTypeVariable(typeR)
                    .addParameter("functionName", STRING)
                    .addParameter("decoder", decoderType)
                    .addParameter("args", anyNullable, KModifier.VARARG)
                    .returns(typeR)
                    .addStatement("check(!isClosed) { %S }", "FfmWasmtimeSession is already closed")
                    .addStatement(
                        "val argsJson = %T.encodeToString(%T.serializer(), %T(args.map { %T.toJsonElement(it) }))",
                        jsonClass,
                        jsonElementClass,
                        jsonArrayClass,
                        ffmCodecClass,
                    )
                    .addStatement("val respStr = bridge.sessionInvoke(sessionPtr, functionName, argsJson)")
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
                    .addParameter("constructorName", STRING)
                    .addParameter("args", anyNullable, KModifier.VARARG)
                    .returns(INT)
                    .addStatement("return invoke(constructorName, { %T.decodeInt(it) }, *args)", ffmCodecClass)
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
                    .addAnnotation(synchronizedAnnotation)
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("handle", INT)
                    .addStatement("check(!isClosed) { %S }", "FfmWasmtimeSession is already closed")
                    .addStatement("val respStr = bridge.sessionDropResource(sessionPtr, handle)")
                    .addStatement("val respObj = %T.parseToJsonElement(respStr).%M", jsonClass, jsonObjectMember)
                    .beginControlFlow("if (!respObj.getValue(%S).%M.%M)", "ok", jsonPrimitiveMember, booleanMember)
                    .addStatement("val errMsg = respObj[%S]?.%M?.content ?: %S", "error", jsonPrimitiveMember, "Failed to drop resource")
                    .addStatement("throw IllegalStateException(errMsg)")
                    .endControlFlow()
                    .build(),
            )
            .addFunction(
                FunSpec.builder("close")
                    .addAnnotation(synchronizedAnnotation)
                    .addModifiers(KModifier.OVERRIDE)
                    .beginControlFlow("if (!isClosed)")
                    .addStatement("isClosed = true")
                    .addStatement("bridge.sessionClose(sessionPtr)")
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
                            .addStatement("return %T(bridge, bridge.openSession(wasmPath, imports))", ffmSessionClass)
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
                            .addStatement("return %T(bridge, bridge.openSessionBytes(wasmBytes, imports))", ffmSessionClass)
                            .build(),
                    )
                    .addFunction(
                        FunSpec.builder("openPrecompiled")
                            .addKdoc(PRECOMPILED_TRUST_KDOC)
                            .addParameter("cwasmPath", pathClass)
                            .addParameter("imports", anyNullable)
                            .addParameter(ParameterSpec.builder("bridge", ffmBridgeClass).defaultValue("%T.getDefault()", ffmBridgeClass).build())
                            .returns(ffmSessionClass)
                            .addStatement("return %T(bridge, bridge.openPrecompiledSession(cwasmPath, imports))", ffmSessionClass)
                            .build(),
                    )
                    .addFunction(
                        FunSpec.builder("openPrecompiled")
                            .addKdoc(PRECOMPILED_TRUST_KDOC)
                            .addParameter("cwasmBytes", byteArrayClass)
                            .addParameter("imports", anyNullable)
                            .addParameter(ParameterSpec.builder("bridge", ffmBridgeClass).defaultValue("%T.getDefault()", ffmBridgeClass).build())
                            .returns(ffmSessionClass)
                            .addStatement("return %T(bridge, bridge.openPrecompiledSessionBytes(cwasmBytes, imports))", ffmSessionClass)
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
                    .addAnnotation(ClassName("kotlin.jvm", "Synchronized"))
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
                    .addParameter("constructorName", STRING)
                    .addParameter("args", anyNullable, KModifier.VARARG)
                    .returns(INT)
                    .addStatement("return invoke(constructorName, { %T.decodeInt(it) }, *args)", ffmCodecClass)
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
                    .addAnnotation(ClassName("kotlin.jvm", "Synchronized"))
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
                    .addAnnotation(ClassName("kotlin.jvm", "Synchronized"))
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
                    .addFunction(
                        FunSpec.builder("openPrecompiled")
                            .addKdoc(PRECOMPILED_TRUST_KDOC)
                            .addParameter("cwasmPath", pathClass)
                            .addParameter("imports", anyNullable)
                            .returns(jniSessionClass)
                            .addStatement("return %T(%T.openPrecompiledSession(cwasmPath, imports))", jniSessionClass, jniBridgeClass)
                            .build(),
                    )
                    .addFunction(
                        FunSpec.builder("openPrecompiled")
                            .addKdoc(PRECOMPILED_TRUST_KDOC)
                            .addParameter("cwasmBytes", byteArrayClass)
                            .addParameter("imports", anyNullable)
                            .returns(jniSessionClass)
                            .addStatement("return %T(%T.openPrecompiledSessionBytes(cwasmBytes, imports))", jniSessionClass, jniBridgeClass)
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    /**
     * Adapts any [wasm2kt.runtime.ComponentBackend] (such as the Chicory backend) to the generated
     * `WasmtimeSession` contract, using the same JSON value encoding as the Wasmtime bridge.
     */
    private fun buildBackendWasmtimeSessionTypeSpec(): TypeSpec {
        val runtimeSessionClass = ClassName(targetPackage, "WasmtimeSession")
        val backendClass = ClassName("wasm2kt.runtime", "ComponentBackend")
        val anyNullable = ClassName("kotlin", "Any").copy(nullable = true)
        val synchronizedAnnotation = ClassName("kotlin.jvm", "Synchronized")
        val typeR = TypeVariableName("R")
        val decoderType = LambdaTypeName.get(
            parameters = listOf(ParameterSpec.unnamed(jsonElementClass)),
            returnType = typeR,
        )
        val closedMessage = "WasmtimeSession is already closed"
        return TypeSpec.classBuilder("BackendWasmtimeSession")
            .addSuperinterface(runtimeSessionClass)
            .primaryConstructor(FunSpec.constructorBuilder().addParameter("backend", backendClass).build())
            .addProperty(PropertySpec.builder("backend", backendClass, KModifier.PRIVATE).initializer("backend").build())
            .addProperty(
                PropertySpec.builder("isClosed", BOOLEAN)
                    .mutable(true)
                    .setter(FunSpec.setterBuilder().addModifiers(KModifier.PRIVATE).build())
                    .initializer("false")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("invoke")
                    .addAnnotation(synchronizedAnnotation)
                    .addModifiers(KModifier.OVERRIDE)
                    .addTypeVariable(typeR)
                    .addParameter("functionName", STRING)
                    .addParameter("decoder", decoderType)
                    .addParameter("args", anyNullable, KModifier.VARARG)
                    .returns(typeR)
                    .addStatement("check(!isClosed) { %S }", closedMessage)
                    .addStatement(
                        "return decoder(backend.invoke(functionName, %T(args.map { %T.toJsonElement(it) })))",
                        jsonArrayClass,
                        ffmCodecClass,
                    )
                    .build(),
            )
            .addFunction(
                FunSpec.builder("newResource")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("constructorName", STRING)
                    .addParameter("args", anyNullable, KModifier.VARARG)
                    .returns(INT)
                    .addStatement("return invoke(constructorName, { %T.decodeInt(it) }, *args)", ffmCodecClass)
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
                    .addStatement("return invoke(methodName, decoder, handle, *args)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("dropResource")
                    .addAnnotation(synchronizedAnnotation)
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("handle", INT)
                    .addStatement("check(!isClosed) { %S }", closedMessage)
                    .addStatement("backend.dropResource(handle)")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("close")
                    .addAnnotation(synchronizedAnnotation)
                    .addModifiers(KModifier.OVERRIDE)
                    .beginControlFlow("if (!isClosed)")
                    .addStatement("isClosed = true")
                    .addStatement("backend.close()")
                    .endControlFlow()
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
                    .addParameter("constructorName", STRING)
                    .addParameter("constructor", fnHandlerType)
                    .addStatement("resourceConstructors[constructorName] = constructor")
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
                    .addParameter("constructorName", STRING)
                    .addParameter("args", anyNullable, KModifier.VARARG)
                    .returns(INT)
                    .addStatement("check(!isClosed) { %S }", "WasmtimeSession is already closed")
                    .addStatement("val handle = nextHandle++")
                    .addStatement("val argList = args.toList()")
                    .addStatement("val state = resourceConstructors[constructorName]?.invoke(argList) ?: argList")
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
