package wasm2kt.generator

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.CHAR
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.ARRAY
import com.squareup.kotlinpoet.WildcardTypeName
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
import wasm2kt.ast.WitParam
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

/** KDoc of the generated `writeTo` / `read` of WIT types (ADR-0015). */
private const val WRITE_TO_KDOC =
    "Writes this value in the binary format bindings and backends exchange (ADR-0015). Used by generated code.\n"
private const val READ_KDOC =
    "Reads a value written in the binary format bindings and backends exchange (ADR-0015). Used by generated code.\n"

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
    /**
     * The component's ID in a typed Wasmtime bridge (ADR-0016): the Wasmtime sessions open with it, so
     * the bridge library must be a typed bridge that contains it. `null` uses the generic path.
     */
    private val bridgeId: String? = null,
) {
    private val generatedTupleArities = mutableSetOf<Int>()

    /** `, "<bridgeId>"` for the bridges' `open*` calls, or nothing. */
    private val bridgeIdArg: CodeBlock = bridgeId?.let { CodeBlock.of(", %S", it) } ?: CodeBlock.of("")

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

    /** Monotonic counter so temporary locals in generated `writeTo` / encoders never shadow or collide. */
    private var localVarCounter: Int = 0

    private val witWriterClass = ClassName("wasm2kt.runtime", "WitWriter")
    private val witReaderClass = ClassName("wasm2kt.runtime", "WitReader")
    private val componentBackendClass = ClassName("wasm2kt.runtime", "ComponentBackend")
    private val flowClass = ClassName("kotlinx.coroutines.flow", "Flow")
    private val deferredClass = ClassName("kotlinx.coroutines", "Deferred")
    private val codePointClass = ClassName(targetPackage, "CodePoint")

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
     * `define(key) { args, results -> ... }` for one host import. Freestanding functions, constructors
     * and statics become abstract members of [contractBuilder]; methods are called on the host object
     * behind `self`.
     */
    private fun buildHostImportDefinition(fn: WitFunction, key: String, contractBuilder: TypeSpec.Builder): CodeBlock {
        val worldClassName = currentWorldClassName
        val b = CodeBlock.builder()
        val argsName = if (fn.params.isEmpty()) "_" else "__args"
        localVarCounter = 0
        b.beginControlFlow("define(%S) { $argsName, __results ->", key)
        fn.params.forEachIndexed { i, param -> b.addStatement("val __a$i = %L", buildRead(param.type, "__args")) }
        val args = fn.params.indices.map { CodeBlock.of("__a$it") }
        val callExpr = when (fn.kind) {
            is WitFunctionKind.Freestanding -> {
                contractBuilder.addFunction(buildInterfaceFunctionSpec(fn, worldClassName))
                CodeBlock.of("impl.%N(%L)", NamingConventions.toCamelCase(fn.name), args.joinToCode(", "))
            }
            is WitFunctionKind.Method -> {
                val member = NamingConventions.extractResourceMemberName(fn.name)
                CodeBlock.of("%L.%N(%L)", args.first(), member, args.drop(1).joinToCode(", "))
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
                CodeBlock.of("impl.%N(%L)", name, args.joinToCode(", "))
            }
        }
        when {
            // A constructor's result is the new object, moved to the component.
            fn.kind is WitFunctionKind.Constructor -> b.addStatement("__results.ownHostResource(%L)", callExpr)
            fn.results.isEmpty() -> b.addStatement("%L", callExpr)
            fn.results.size == 1 -> {
                b.addStatement("val __ret = %L", callExpr)
                b.add(buildWrite(fn.results.first().type, CodeBlock.of("__ret"), "__results"))
            }
            else -> {
                b.addStatement("val __ret = %L", callExpr)
                fn.results.forEachIndexed { i, res -> b.add(buildWrite(res.type, CodeBlock.of("__ret.f$i"), "__results")) }
            }
        }
        b.endControlFlow()
        return b.build()
    }

    /** The component dropped its last owning handle to a host resource. */
    private fun buildHostResourceDropDefinition(key: String): CodeBlock =
        CodeBlock.builder()
            .addStatement("define(%S) { __args, _ -> %T.current().drop(__args.int()) }", key, hostResourceTableClass)
            .build()

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
        is WitFunctionKind.Freestanding -> null
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
                is WitFunctionKind.Freestanding -> null
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

        val writeTo = FunSpec.builder("writeTo")
            .addKdoc(WRITE_TO_KDOC)
            .addParameter("writer", witWriterClass)
        localVarCounter = 0
        for (field in fields) {
            writeTo.addCode(buildWrite(field.type, CodeBlock.of("this.%N", NamingConventions.toCamelCase(field.name)), "writer"))
        }
        builder.addFunction(writeTo.build())

        val hasResourceFields = fields.any { containsGuestResource(it.type) }
        val sessionExpr = if (hasResourceFields) CodeBlock.of("session") else null
        val read = FunSpec.builder("read")
            .addKdoc(READ_KDOC)
            .addParameter("reader", witReaderClass)
            .apply { if (hasResourceFields) addParameter(ParameterSpec.builder("session", sessionClassName.copy(nullable = true)).defaultValue("null").build()) }
            .returns(recordClassName)
        if (fields.isEmpty()) {
            read.addStatement("return %T()", recordClassName)
        } else {
            val ctorArgs = fields.map { field ->
                CodeBlock.of("%N = %L", NamingConventions.toCamelCase(field.name), buildRead(field.type, "reader", sessionExpr = sessionExpr))
            }
            read.addStatement("return %T(\n⇥%L⇤)", recordClassName, ctorArgs.joinToCode(",\n", suffix = ",\n"))
        }
        builder.addType(TypeSpec.companionObjectBuilder().addFunction(read.build()).build())
        return builder.build()
    }

    private fun buildEnumTypeSpec(
        name: String,
        cases: List<WitEnumCase>,
        docs: WitDocs?,
        selfClassName: ClassName,
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
        builder.addFunction(
            FunSpec.builder("writeTo")
                .addKdoc(WRITE_TO_KDOC)
                .addParameter("writer", witWriterClass)
                .addStatement("%L", discriminantWrite(cases.size, "writer", "ordinal"))
                .build(),
        )
        builder.addType(
            TypeSpec.companionObjectBuilder()
                .addFunction(
                    FunSpec.builder("read")
                        .addKdoc(READ_KDOC)
                        .addParameter("reader", witReaderClass)
                        .returns(selfClassName)
                        .addStatement("val d = ${discriminantRead(cases.size, "reader")}")
                        .addStatement("return entries.getOrNull(d) ?: throw IllegalStateException(%P)", "Invalid $name discriminant \$d")
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

        // Canonical ABI widths: u8 / u16 / u32 words (ADR-0015).
        val (writeCode, readExpr) = when {
            flags.size <= 8 -> "writer.byte(raw.toInt())" to "(reader.byte().toUInt() and 0xFFu)"
            flags.size <= 16 -> "writer.short(raw.toInt())" to "(reader.short().toUInt() and 0xFFFFu)"
            flags.size <= 32 -> "writer.int(raw.toInt())" to "reader.int().toUInt()"
            else -> "writer.int(raw.toInt())\nwriter.int((raw shr 32).toInt())" to
                "((reader.int().toULong() and 0xFFFF_FFFFuL) or (reader.int().toULong() shl 32))"
        }
        companionBuilder.addFunction(
            FunSpec.builder("read")
                .addKdoc(READ_KDOC)
                .addParameter("reader", witReaderClass)
                .returns(selfClassName)
                .addStatement("return %T($readExpr)", selfClassName)
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
            FunSpec.builder("writeTo")
                .addKdoc(WRITE_TO_KDOC)
                .addParameter("writer", witWriterClass)
                .addCode(writeCode.split("\n").joinToString("") { "$it\n" })
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
        docs?.contents?.let { builder.addKdoc("%L\n", it.trim()) }

        val writeTo = CodeBlock.builder().beginControlFlow("when (this)")
        localVarCounter = 0
        cases.forEachIndexed { index, case ->
            val caseName = NamingConventions.toPascalCase(case.name)
            if (case.type == null) {
                val objBuilder = TypeSpec.objectBuilder(caseName)
                    .addModifiers(KModifier.DATA)
                    .addSuperinterface(variantClassName)
                case.docs?.contents?.let { objBuilder.addKdoc("%L\n", it.trim()) }
                builder.addType(objBuilder.build())
                writeTo.addStatement("is %N -> %L", caseName, discriminantWrite(cases.size, "writer", "$index"))
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
                addContentEqualityIfNeeded(classBuilder, variantClassName.nestedClass(caseName), listOf("value" to payloadType))
                case.docs?.contents?.let { classBuilder.addKdoc("%L\n", it.trim()) }
                builder.addType(classBuilder.build())
                writeTo.beginControlFlow("is %N ->", caseName)
                writeTo.addStatement("%L", discriminantWrite(cases.size, "writer", "$index"))
                writeTo.add(buildWrite(checkNotNull(case.type), CodeBlock.of("this.value"), "writer"))
                writeTo.endControlFlow()
            }
        }
        writeTo.endControlFlow()
        builder.addFunction(
            FunSpec.builder("writeTo")
                .addKdoc(WRITE_TO_KDOC)
                .addParameter("writer", witWriterClass)
                .addCode(writeTo.build())
                .build(),
        )

        val hasResourceCases = cases.any { c -> c.type?.let { containsGuestResource(it) } ?: false }
        val sessionExpr = if (hasResourceCases) CodeBlock.of("session") else null
        val read = CodeBlock.builder()
            .beginControlFlow("return when (val d = ${discriminantRead(cases.size, "reader")})")
        cases.forEachIndexed { index, case ->
            val caseName = NamingConventions.toPascalCase(case.name)
            if (case.type == null) {
                read.addStatement("$index -> %N", caseName)
            } else {
                read.addStatement("$index -> %N(%L)", caseName, buildRead(checkNotNull(case.type), "reader", sessionExpr = sessionExpr))
            }
        }
        read.addStatement("else -> throw IllegalStateException(%P)", "Invalid $name discriminant \$d")
        read.endControlFlow()
        builder.addType(
            TypeSpec.companionObjectBuilder()
                .addFunction(
                    FunSpec.builder("read")
                        .addKdoc(READ_KDOC)
                        .addParameter("reader", witReaderClass)
                        .apply {
                            if (hasResourceCases) {
                                addParameter(ParameterSpec.builder("session", sessionClassName.copy(nullable = true)).defaultValue("null").build())
                            }
                        }
                        .returns(variantClassName)
                        .addCode(read.build())
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
            .addFunction(
                FunSpec.builder("handleFor")
                    .addKdoc(
                        "The handle to pass to a call on [target] (null: not known, e.g. inside a record). Fails, before " +
                            "the call reaches the component, if this resource is closed or moved, or belongs to another " +
                            "session: handles are numbered per session, so another session would take it for one of its own (#76).\n",
                    )
                    .addModifiers(KModifier.INTERNAL)
                    .addParameter("target", ANY.copy(nullable = true))
                    .returns(INT)
                    .addStatement("check(!isClosed) { %P }", "Resource $resourcePascalName(handle=\$handle) is already closed or moved")
                    .addStatement("check(target == null || session === target) { %P }", "Resource $resourcePascalName(handle=\$handle) belongs to another session")
                    .addStatement("return handle")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("markMoved")
                    .addKdoc("Gives up ownership after a call that took this resource as `own` returned (#76).\n")
                    .addModifiers(KModifier.INTERNAL)
                    .addStatement("isClosed = true")
                    .build(),
            )

        docs?.contents?.let { builder.addKdoc("%L\n", it.trim()) }

        val companionBuilder = TypeSpec.companionObjectBuilder()

        for (fn in functions) {
            val key = "$keyPrefix${fn.name}"
            when (fn.kind) {
                is WitFunctionKind.Constructor -> {
                    val invokeBuilder = FunSpec.builder("invoke")
                        .addModifiers(KModifier.OPERATOR)
                        .apply { addSessionContext(this, contextClassName, viaMarker = true) }
                        .returns(resourceClassName)
                    addFunctionKdoc(invokeBuilder, fn)
                    for (param in fn.params) invokeBuilder.addParameter(NamingConventions.toCamelCase(param.name), mapTypeRef(param.type, worldClassName))
                    val call = buildCallParts(fn, fn.params, selfHandle = false, sessionExpr = CodeBlock.of("session"))
                    invokeBuilder.addCode(call.prelude)
                    invokeBuilder.addStatement("val handle = session.runtimeSession.newResource(%S, %L, %L)%L", key, call.args, call.encode, call.after)
                    invokeBuilder.addStatement("return %T(session, handle)", resourceClassName)
                    companionBuilder.addFunction(invokeBuilder.build())
                }
                is WitFunctionKind.Method -> {
                    val methodBuilder = FunSpec.builder(NamingConventions.extractResourceMemberName(fn.name))
                    addFunctionKdoc(methodBuilder, fn)
                    if (fn.isAsync) methodBuilder.addModifiers(KModifier.SUSPEND)
                    val userParams = fn.params.drop(1)
                    for (param in userParams) methodBuilder.addParameter(NamingConventions.toCamelCase(param.name), mapTypeRef(param.type, worldClassName))
                    methodBuilder.returns(mapFunctionReturnType(fn, worldClassName))
                    methodBuilder.addStatement("check(!isClosed) { %P }", "Resource $resourcePascalName(handle=\$handle) is already closed or moved")
                    methodBuilder.addStatement("val session = boundSession")
                    val call = buildCallParts(fn, userParams, selfHandle = true, sessionExpr = CodeBlock.of("session"))
                    methodBuilder.addCode(call.prelude)
                    val target = if (returnsOwnedResource(fn)) "val resultHandle =" else "return"
                    methodBuilder.addStatement(
                        "$target session.runtimeSession.invokeMethod(handle, %S, %L, %L, %L)%L",
                        key, call.args, call.encode, call.decode, call.after,
                    )
                    if (returnsOwnedResource(fn)) methodBuilder.addStatement("return %T(session, resultHandle)", mapFunctionReturnType(fn, worldClassName))
                    builder.addFunction(methodBuilder.build())
                }
                is WitFunctionKind.Static -> {
                    val staticBuilder = FunSpec.builder(NamingConventions.extractResourceMemberName(fn.name))
                        .apply { addSessionContext(this, contextClassName, viaMarker = true) }
                    addFunctionKdoc(staticBuilder, fn)
                    if (fn.isAsync) staticBuilder.addModifiers(KModifier.SUSPEND)
                    for (param in fn.params) staticBuilder.addParameter(NamingConventions.toCamelCase(param.name), mapTypeRef(param.type, worldClassName))
                    staticBuilder.returns(mapFunctionReturnType(fn, worldClassName))
                    val call = buildCallParts(fn, fn.params, selfHandle = false, sessionExpr = CodeBlock.of("session"))
                    staticBuilder.addCode(call.prelude)
                    if (returnsOwnedResource(fn)) {
                        staticBuilder.addStatement("val resultHandle = session.runtimeSession.invoke(%S, %L, %L, %L)%L", key, call.args, call.encode, call.decode, call.after)
                        staticBuilder.addStatement("return %T(session, resultHandle)", mapFunctionReturnType(fn, worldClassName))
                    } else {
                        staticBuilder.addStatement("return session.runtimeSession.invoke(%S, %L, %L, %L)%L", key, call.args, call.encode, call.decode, call.after)
                    }
                    companionBuilder.addFunction(staticBuilder.build())
                }
                is WitFunctionKind.Freestanding -> Unit
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
        // An `async func` is a `suspend fun` (ADR-0006). The call itself still blocks the thread
        // until the component returns (ADR-0018).
        if (fn.isAsync) builder.addModifiers(KModifier.SUSPEND)
        for (param in fn.params) builder.addParameter(NamingConventions.toCamelCase(param.name), mapTypeRef(param.type, worldClassName))
        val returnType = mapFunctionReturnType(fn, worldClassName)
        builder.returns(returnType)

        val call = buildCallParts(fn, fn.params, selfHandle = false, sessionExpr = CodeBlock.of("session"))
        builder.addCode(call.prelude)
        val key = "$invokeTargetPrefix${fn.name}"
        if (returnsOwnedResource(fn)) {
            builder.addStatement("val resultHandle = session.runtimeSession.invoke(%S, %L, %L, %L)%L", key, call.args, call.encode, call.decode, call.after)
            builder.addStatement("return %T(session, resultHandle)", returnType)
        } else {
            builder.addStatement("return session.runtimeSession.invoke(%S, %L, %L, %L)%L", key, call.args, call.encode, call.decode, call.after)
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

    // ------------------------------------------------------------------ binary format (ADR-0015)

    /** Discriminants are as wide as in the Canonical ABI: `u8`, `u16` or `u32` by case count. */
    private fun discriminantWrite(count: Int, w: String, expr: String): CodeBlock = when {
        count <= 256 -> CodeBlock.of("$w.byte($expr)")
        count <= 65536 -> CodeBlock.of("$w.short($expr)")
        else -> CodeBlock.of("$w.int($expr)")
    }

    private fun discriminantRead(count: Int, r: String): String = when {
        count <= 256 -> "($r.byte().toInt() and 0xFF)"
        count <= 65536 -> "($r.short().toInt() and 0xFFFF)"
        else -> "$r.int()"
    }

    /** Statements writing [value] (of WIT type [typeRef]) to the [WitWriter] named [w]. */
    private fun buildWrite(typeRef: WitTypeRef, value: CodeBlock, w: String, depth: Int = 0): CodeBlock = when (typeRef) {
        is WitTypeRef.Primitive -> CodeBlock.builder().addStatement("%L", primitiveWrite(typeRef.name, value, w)).build()
        is WitTypeRef.Id -> buildWriteId(typeRef.index, value, w, depth)
    }

    private fun primitiveWrite(name: String, v: CodeBlock, w: String): CodeBlock = when (name) {
        "bool" -> CodeBlock.of("$w.bool(%L)", v)
        "u8", "s8" -> CodeBlock.of("$w.byte((%L).toInt())", v)
        "u16", "s16" -> CodeBlock.of("$w.short((%L).toInt())", v)
        "u32" -> CodeBlock.of("$w.int((%L).toInt())", v)
        "s32" -> CodeBlock.of("$w.int(%L)", v)
        "u64" -> CodeBlock.of("$w.long((%L).toLong())", v)
        "s64" -> CodeBlock.of("$w.long(%L)", v)
        "f32", "float32" -> CodeBlock.of("$w.float(%L)", v)
        "f64", "float64" -> CodeBlock.of("$w.double(%L)", v)
        "char" -> CodeBlock.of("$w.codePoint((%L).value)", v)
        "string" -> CodeBlock.of("$w.string(%L)", v)
        "_" -> CodeBlock.of("Unit")
        else -> throw IllegalArgumentException("Unknown WIT primitive type: $name")
    }

    private fun buildWriteId(index: Int, value: CodeBlock, w: String, depth: Int): CodeBlock {
        val def = resolve.types.getOrNull(index) ?: throw IllegalArgumentException("Invalid WIT type index: $index")
        val b = CodeBlock.builder()
        when (val kind = def.kind) {
            is WitTypeDefKind.Record, is WitTypeDefKind.Enum, is WitTypeDefKind.Flags, is WitTypeDefKind.Variant ->
                b.addStatement("(%L).writeTo($w)", value)
            is WitTypeDefKind.TypeAlias -> return buildWrite(kind.type, value, w, depth)
            is WitTypeDefKind.Resource -> b.add(resourceWrite(index, own = true, value, w))
            is WitTypeDefKind.Handle -> b.add(resourceWrite(kind.resourceId, own = kind.kind == wasm2kt.ast.HandleKind.OWN, value, w))
            is WitTypeDefKind.OptionType -> {
                val id = localVarCounter++
                val v = "__o$id"
                b.addStatement("val $v = %L", value)
                if (mapTypeRef(kind.type, currentWorldClassName).isNullable) {
                    val opt = ClassName(targetPackage, "WitOption")
                    b.beginControlFlow("if ($v is %T.Some)", opt).addStatement("$w.byte(1)")
                    b.add(buildWrite(kind.type, CodeBlock.of("$v.value"), w, depth + 1))
                } else {
                    b.beginControlFlow("if ($v != null)").addStatement("$w.byte(1)")
                    b.add(buildWrite(kind.type, CodeBlock.of(v), w, depth + 1))
                }
                b.nextControlFlow("else").addStatement("$w.byte(0)").endControlFlow()
            }
            is WitTypeDefKind.ResultType -> {
                val id = localVarCounter++
                val v = "__res$id"
                val res = ClassName(targetPackage, "WitResult")
                b.addStatement("val $v = %L", value)
                b.beginControlFlow("when ($v)")
                b.beginControlFlow("is %T.Ok ->", res).addStatement("$w.byte(0)")
                kind.ok?.let { b.add(buildWrite(it, CodeBlock.of("$v.value"), w, depth + 1)) }
                b.endControlFlow()
                b.beginControlFlow("is %T.Err ->", res).addStatement("$w.byte(1)")
                kind.err?.let { b.add(buildWrite(it, CodeBlock.of("$v.error"), w, depth + 1)) }
                b.endControlFlow()
                b.endControlFlow()
            }
            is WitTypeDefKind.ListType -> if (isPrimitive(kind.type, "u8")) {
                b.addStatement("$w.bytes(%L)", value)
            } else {
                val id = localVarCounter++
                val v = "__l$id"
                val e = "__e$id"
                b.addStatement("val $v = %L", value)
                b.addStatement("$w.length($v.size)")
                b.beginControlFlow("for ($e in $v)")
                b.add(buildWrite(kind.type, CodeBlock.of(e), w, depth + 1))
                b.endControlFlow()
            }
            is WitTypeDefKind.Tuple -> {
                generatedTupleArities += kind.types.size
                val id = localVarCounter++
                val v = "__t$id"
                b.addStatement("val $v = %L", value)
                kind.types.forEachIndexed { i, t -> b.add(buildWrite(t, CodeBlock.of("$v.f$i"), w, depth + 1)) }
            }
            is WitTypeDefKind.FutureType, is WitTypeDefKind.StreamType ->
                b.addStatement(
                    "throw %T(%S)", UnsupportedOperationException::class,
                    "Only parameters of exported functions can be futures and streams, not values nested in other types (#16)",
                )
        }
        return b.build()
    }

    /** A host resource is moved (`own`) or lent (`borrow`) through the table; a guest resource is its handle. */
    private fun resourceWrite(resourceIdx: Int, own: Boolean, value: CodeBlock, w: String): CodeBlock =
        if (isHostResource(resourceIdx)) {
            CodeBlock.builder().addStatement(if (own) "$w.ownHostResource(%L)" else "$w.borrowHostResource(%L)", value).build()
        } else {
            CodeBlock.builder().addStatement("$w.int((%L).handleFor(null))", value).build()
        }

    /**
     * An expression reading a value of WIT type [typeRef] from the [WitReader] named [r]. Guest
     * resources are bound to [sessionExpr] (unbound when null, e.g. in host imports).
     */
    private fun buildRead(typeRef: WitTypeRef, r: String, depth: Int = 0, sessionExpr: CodeBlock? = null): CodeBlock = when (typeRef) {
        is WitTypeRef.Primitive -> when (typeRef.name) {
            "bool" -> CodeBlock.of("$r.bool()")
            "u8" -> CodeBlock.of("$r.byte().toUByte()")
            "s8" -> CodeBlock.of("$r.byte()")
            "u16" -> CodeBlock.of("$r.short().toUShort()")
            "s16" -> CodeBlock.of("$r.short()")
            "u32" -> CodeBlock.of("$r.int().toUInt()")
            "s32" -> CodeBlock.of("$r.int()")
            "u64" -> CodeBlock.of("$r.long().toULong()")
            "s64" -> CodeBlock.of("$r.long()")
            "f32", "float32" -> CodeBlock.of("$r.float()")
            "f64", "float64" -> CodeBlock.of("$r.double()")
            "char" -> CodeBlock.of("%T($r.codePoint())", codePointClass)
            "string" -> CodeBlock.of("$r.string()")
            "_" -> CodeBlock.of("Unit")
            else -> throw IllegalArgumentException("Unknown WIT primitive type: ${typeRef.name}")
        }
        is WitTypeRef.Id -> buildReadId(typeRef.index, r, depth, sessionExpr)
    }

    private fun buildReadId(index: Int, r: String, depth: Int, sessionExpr: CodeBlock?): CodeBlock {
        val def = resolve.types.getOrNull(index) ?: throw IllegalArgumentException("Invalid WIT type index: $index")
        return when (val kind = def.kind) {
            is WitTypeDefKind.Record, is WitTypeDefKind.Enum, is WitTypeDefKind.Flags, is WitTypeDefKind.Variant -> {
                val cls = typeClassName(index)
                if (sessionExpr != null && containsGuestResource(WitTypeRef.Id(index))) {
                    CodeBlock.of("%T.read($r, %L)", cls, sessionExpr)
                } else {
                    CodeBlock.of("%T.read($r)", cls)
                }
            }
            is WitTypeDefKind.TypeAlias -> buildRead(kind.type, r, depth, sessionExpr)
            is WitTypeDefKind.Resource -> resourceRead(index, own = true, r, sessionExpr)
            is WitTypeDefKind.Handle -> resourceRead(kind.resourceId, own = kind.kind == wasm2kt.ast.HandleKind.OWN, r, sessionExpr)
            is WitTypeDefKind.OptionType -> {
                val inner = buildRead(kind.type, r, depth + 1, sessionExpr)
                if (mapTypeRef(kind.type, currentWorldClassName).isNullable) {
                    val opt = ClassName(targetPackage, "WitOption")
                    CodeBlock.of("(if ($r.bool()) %T.Some(%L) else %T.None)", opt, inner, opt)
                } else {
                    CodeBlock.of("(if ($r.bool()) %L else null)", inner)
                }
            }
            is WitTypeDefKind.ResultType -> {
                val res = ClassName(targetPackage, "WitResult")
                val ok = kind.ok?.let { buildRead(it, r, depth + 1, sessionExpr) } ?: CodeBlock.of("Unit")
                val err = kind.err?.let { buildRead(it, r, depth + 1, sessionExpr) } ?: CodeBlock.of("Unit")
                CodeBlock.of("(if ($r.bool()) %T.Err(%L) else %T.Ok(%L))", res, err, res, ok)
            }
            is WitTypeDefKind.ListType -> if (isPrimitive(kind.type, "u8")) {
                CodeBlock.of("$r.bytes()")
            } else {
                CodeBlock.of("List($r.length()) { %L }", buildRead(kind.type, r, depth + 1, sessionExpr))
            }
            is WitTypeDefKind.Tuple -> {
                generatedTupleArities += kind.types.size
                val tupleClass = ClassName(targetPackage, "Tuple${kind.types.size}")
                CodeBlock.of("%T(%L)", tupleClass, kind.types.map { buildRead(it, r, depth + 1, sessionExpr) }.joinToCode(", "))
            }
            is WitTypeDefKind.FutureType ->
                if (sessionExpr == null) {
                    CodeBlock.of("throw %T(%S)", UnsupportedOperationException::class, "Host imports cannot take or return futures yet (#16)")
                } else {
                    usesAsyncValues = true
                    val inner = kind.type?.let { buildRead(it, "__fr", depth + 1, sessionExpr) } ?: CodeBlock.of("Unit")
                    CodeBlock.of("%M(%L.runtimeSession, $r.int()) { __fr -> %L }", MemberName(targetPackage, "witFuture"), sessionExpr, inner)
                }
            is WitTypeDefKind.StreamType ->
                if (sessionExpr == null) {
                    CodeBlock.of("throw %T(%S)", UnsupportedOperationException::class, "Host imports cannot take or return streams yet (#16)")
                } else {
                    usesAsyncValues = true
                    val element = kind.element
                    val chunk = when {
                        element == null -> CodeBlock.of("List(__sr.length()) { Unit }")
                        isPrimitive(element, "u8") -> CodeBlock.of("listOf(__sr.bytes())")
                        else -> CodeBlock.of("List(__sr.length()) { %L }", buildRead(element, "__sr", depth + 1, sessionExpr))
                    }
                    CodeBlock.of("%M(%L.runtimeSession, $r.int()) { __sr -> %L }", MemberName(targetPackage, "witStream"), sessionExpr, chunk)
                }
        }
    }

    /** A host resource comes back through the table (`own` returns it to the host); a guest resource is wrapped. */
    private fun resourceRead(resourceIdx: Int, own: Boolean, r: String, sessionExpr: CodeBlock?): CodeBlock =
        if (isHostResource(resourceIdx)) {
            CodeBlock.of(
                "%T.current().%N($r.int(), %T::class.java)",
                hostResourceTableClass,
                if (own) "take" else "borrow",
                mapTypeDefByIndex(resourceIdx, currentWorldClassName),
            )
        } else {
            CodeBlock.of("%T(%L, $r.int())", typeClassName(resourceIdx), sessionExpr ?: CodeBlock.of("null"))
        }

    /**
     * The parts of one call into the component (ADR-0015): statements to run first, the arguments as
     * Kotlin values (what `InMemoryWasmtimeSession` receives), the lambda writing them, and the lambda
     * reading the results.
     */
    /**
     * [after] is appended to the call expression: `.also { ... }` giving up the guest resources passed
     * as `own` once the call returned, or nothing. A call that fails keeps them usable: the backends put
     * their handles back (#76).
     */
    private class CallParts(val prelude: CodeBlock, val args: CodeBlock, val encode: CodeBlock, val decode: CodeBlock, val after: CodeBlock)

    /**
     * [params] are the WIT parameters after `self`; [selfHandle] writes the resource's own handle
     * first (methods). A top-level `own` of a guest resource gives up the Kotlin object's ownership.
     */
    private fun buildCallParts(fn: WitFunction, params: List<WitParam>, selfHandle: Boolean, sessionExpr: CodeBlock?): CallParts {
        val prelude = CodeBlock.builder()
        val argValues = mutableListOf<CodeBlock>()
        val encode = CodeBlock.builder()
        val moved = mutableListOf<CodeBlock>()
        val target = sessionExpr ?: CodeBlock.of("null")
        localVarCounter = 0
        if (selfHandle) encode.addStatement("__w.int(handle)")
        params.forEachIndexed { i, param ->
            val name = NamingConventions.toCamelCase(param.name)
            val handle = (param.type as? WitTypeRef.Id)?.let { resolve.types.getOrNull(it.index)?.kind as? WitTypeDefKind.Handle }
            when {
                handle != null && !isHostResource(handle.resourceId) -> {
                    prelude.addStatement("val __handle$i = %N.handleFor(%L)", name, target)
                    argValues += CodeBlock.of("__handle$i")
                    encode.addStatement("__w.int(__handle$i)")
                    if (handle.kind == wasm2kt.ast.HandleKind.OWN) moved += CodeBlock.of("%N.markMoved()", name)
                }
                handle != null && handle.kind == wasm2kt.ast.HandleKind.BORROW -> {
                    argValues += CodeBlock.of("%T(%N)", witBorrowedClass, name)
                    encode.addStatement("__w.borrowHostResource(%N)", name)
                }
                asyncValueKind(param.type) != null && sessionExpr != null -> {
                    // A stream is collected and a future awaited before the call (#16): the
                    // component reads the values from the host's end while the call runs.
                    usesAsyncValues = true
                    argValues += CodeBlock.of("%N", name)
                    when (val kind = asyncValueKind(param.type)) {
                        is WitTypeDefKind.StreamType -> {
                            val element = kind.element
                            val values = "__values$i"
                            when {
                                element == null -> prelude.addStatement("val $values = %N.%M().let { it.size to %T(0) }", name, MemberName("kotlinx.coroutines.flow", "toList"), BYTE_ARRAY)
                                isPrimitive(element, "u8") -> prelude.addStatement("val $values = %M(%N)", MemberName(targetPackage, "witCollectBytes"), name)
                                else -> {
                                    prelude.addStatement("val __list$i = %N.%M()", name, MemberName("kotlinx.coroutines.flow", "toList"))
                                    prelude.addStatement("val __sw$i = %T()", witWriterClass)
                                    prelude.beginControlFlow("for (__e in __list$i)")
                                    prelude.add(buildWrite(element, CodeBlock.of("__e"), "__sw$i"))
                                    prelude.endControlFlow()
                                    prelude.addStatement("val $values = __list$i.size to __sw$i.toByteArray()")
                                }
                            }
                            encode.addStatement("__w.int(%L.runtimeSession.withBackend { it.newStream($values.second, $values.first) })", target)
                        }
                        is WitTypeDefKind.FutureType -> {
                            val value = "__value$i"
                            if (fn.isAsync) {
                                prelude.addStatement("val $value = %N.await()", name)
                            } else {
                                prelude.addStatement("check(%N.isCompleted) { %S }", name, "${param.name}: pass a completed Deferred to a function that is not async")
                                prelude.addStatement("@OptIn(%T::class) val $value = %N.getCompleted()", ClassName("kotlinx.coroutines", "ExperimentalCoroutinesApi"), name)
                            }
                            prelude.addStatement("val __fw$i = %T()", witWriterClass)
                            kind.type?.let { prelude.add(buildWrite(it, CodeBlock.of(value), "__fw$i")) }
                            encode.addStatement("__w.int(%L.runtimeSession.withBackend { it.newFuture(__fw$i.toByteArray()) })", target)
                        }
                        else -> error("unreachable")
                    }
                }
                else -> {
                    argValues += CodeBlock.of("%N", name)
                    encode.add(buildWrite(param.type, CodeBlock.of("%N", name), "__w"))
                }
            }
        }
        val args = if (argValues.isEmpty()) CodeBlock.of("emptyArray<Any?>()") else CodeBlock.of("arrayOf<Any?>(%L)", argValues.joinToCode(", "))
        // The encoder is a local lambda: statements cannot be nested inside the call's statement.
        val encodeLambda = if (encode.isEmpty()) {
            CodeBlock.of("{ }")
        } else {
            prelude.beginControlFlow("val __encode: %T = { __w ->", encodeLambdaType).add(encode.build()).endControlFlow()
            CodeBlock.of("__encode")
        }
        val decode = when {
            // An owned guest resource comes back as its handle; the caller wraps it, so that
            // InMemoryWasmtimeSession can return plain handles.
            returnsOwnedResource(fn) -> CodeBlock.of("{ __r -> __r.int() }")
            fn.results.isEmpty() -> CodeBlock.of("{ }")
            fn.results.size == 1 -> CodeBlock.of("{ __r -> %L }", buildRead(fn.results.first().type, "__r", sessionExpr = sessionExpr))
            else -> {
                generatedTupleArities += fn.results.size
                val tupleClass = ClassName(targetPackage, "Tuple${fn.results.size}")
                CodeBlock.of("{ __r -> %T(%L) }", tupleClass, fn.results.map { buildRead(it.type, "__r", sessionExpr = sessionExpr) }.joinToCode(", "))
            }
        }
        val after = if (moved.isEmpty()) CodeBlock.of("") else CodeBlock.of(".also { %L }", moved.joinToCode("; "))
        return CallParts(prelude.build(), args, encodeLambda, decode, after)
    }


    /** The stream or future type [ref] names, if it is one (through type aliases). */
    private fun asyncValueKind(ref: WitTypeRef): WitTypeDefKind? {
        val id = ref as? WitTypeRef.Id ?: return null
        return when (val kind = resolve.types.getOrNull(id.index)?.kind) {
            is WitTypeDefKind.StreamType, is WitTypeDefKind.FutureType -> kind
            is WitTypeDefKind.TypeAlias -> asyncValueKind(kind.type)
            else -> null
        }
    }

    /** Whether generated code reads or writes streams or futures: the support file then gets their helpers. */
    private var usesAsyncValues = false

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
            // ADR-0006: `future<T>` is a `Deferred<T>`; `stream<T>` a `Flow<T>` collected once
            // (`stream<u8>` a `Flow<ByteArray>` of the chunks as they arrive).
            is WitTypeDefKind.FutureType -> {
                val innerType = kind.type?.let { mapTypeRef(it, worldClassName) } ?: UNIT
                deferredClass.parameterizedBy(innerType)
            }
            is WitTypeDefKind.StreamType -> {
                val element = kind.element
                val innerType = when {
                    element == null -> UNIT
                    isPrimitive(element, "u8") -> BYTE_ARRAY
                    else -> mapTypeRef(element, worldClassName)
                }
                flowClass.parameterizedBy(innerType)
            }
        }
    }

    /**
     * Streams and futures the component returns, and streams the host passes (#16, ADR-0006): a
     * future is a lazy [kotlinx.coroutines.Deferred] that reads the value when awaited; a stream a
     * [kotlinx.coroutines.flow.Flow] that reads chunks as it is collected, once.
     */
    private fun addAsyncValueHelpers(fileBuilder: FileSpec.Builder) {
        val session = ClassName(targetPackage, "WasmtimeSession")
        val t = TypeVariableName("T")
        val coroutines = "kotlinx.coroutines"
        fileBuilder.addFunction(
            FunSpec.builder("witFuture")
                .addKdoc("The future [handle] the component returned; awaiting it runs the component until it writes the value.\n")
                .addModifiers(KModifier.INTERNAL)
                .addAnnotation(AnnotationSpec.builder(ClassName("kotlin", "OptIn")).addMember("%T::class", ClassName(coroutines, "DelicateCoroutinesApi")).build())
                .addTypeVariable(t)
                .addParameter("session", session)
                .addParameter("handle", INT)
                .addParameter("decode", LambdaTypeName.get(parameters = listOf(ParameterSpec.unnamed(witReaderClass)), returnType = t))
                .returns(deferredClass.parameterizedBy(t))
                .beginControlFlow(
                    "return %T.%M(%T.Unconfined, start = %T.LAZY)",
                    ClassName(coroutines, "GlobalScope"), MemberName(coroutines, "async"), ClassName(coroutines, "Dispatchers"), ClassName(coroutines, "CoroutineStart"),
                )
                .addStatement("val r = session.withBackend { it.readFuture(handle) } ?: throw %T(%S)", IllegalStateException::class, "The component dropped the future without writing a value")
                .addStatement("decode(r)")
                .endControlFlow()
                .build(),
        )
        fileBuilder.addFunction(
            FunSpec.builder("witStream")
                .addKdoc("The stream [handle] the component returned, read in chunks as it is collected; it can be collected once.\n")
                .addModifiers(KModifier.INTERNAL)
                .addTypeVariable(t)
                .addParameter("session", session)
                .addParameter("handle", INT)
                .addParameter("decode", LambdaTypeName.get(parameters = listOf(ParameterSpec.unnamed(witReaderClass)), returnType = LIST.parameterizedBy(t)))
                .returns(flowClass.parameterizedBy(t))
                .addStatement("val collected = %T(false)", ClassName("java.util.concurrent.atomic", "AtomicBoolean"))
                .beginControlFlow("return %M", MemberName("kotlinx.coroutines.flow", "flow"))
                .addStatement("check(collected.compareAndSet(false, true)) { %S }", "A stream returned by the component can be collected only once")
                .addStatement("var ended = false")
                .beginControlFlow("try")
                .beginControlFlow("while (true)")
                .addStatement("val r = session.withBackend { it.readStream(handle, 64) }")
                .beginControlFlow("if (r == null)")
                .addStatement("ended = true")
                .addStatement("break")
                .endControlFlow()
                .addStatement("for (v in decode(r)) emit(v)")
                .endControlFlow()
                .nextControlFlow("finally")
                // Stopped early: tell the component, which sees its writes fail with DROPPED.
                .addStatement("if (!ended) runCatching { session.withBackend { it.dropReadable(handle) } }")
                .endControlFlow()
                .endControlFlow()
                .build(),
        )
        fileBuilder.addFunction(
            FunSpec.builder("witCollectBytes")
                .addKdoc("Collects a `stream<u8>` the host passes: (byte count, bytes).\n")
                .addModifiers(KModifier.INTERNAL, KModifier.SUSPEND)
                .addParameter("flow", flowClass.parameterizedBy(BYTE_ARRAY))
                .returns(ClassName("kotlin", "Pair").parameterizedBy(INT, BYTE_ARRAY))
                .addStatement("val out = %T()", ClassName("java.io", "ByteArrayOutputStream"))
                .addStatement("flow.collect { out.write(it) }")
                .addStatement("val bytes = out.toByteArray()")
                .addStatement("return bytes.size to bytes")
                .build(),
        )
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
        if (usesAsyncValues) addAsyncValueHelpers(fileBuilder)

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

    /**
     * Binds the session's [HostResourceTable] around every call into the component (ADR-0008). Its
     * methods are serialized: the table tracks the lends of the call in progress, so binding it from
     * two threads at once would mix up their lends (#75).
     */
    private fun buildHostResourceBoundSessionTypeSpec(): TypeSpec {
        val runtimeSessionClass = ClassName(targetPackage, "WasmtimeSession")
        val typeR = TypeVariableName("R")
        val anyNullable = ClassName("kotlin", "Any").copy(nullable = true)
        val argsType = ARRAY.parameterizedBy(WildcardTypeName.producerOf(anyNullable))
        val decodeType = LambdaTypeName.get(parameters = listOf(ParameterSpec.unnamed(witReaderClass)), returnType = typeR)
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
                FunSpec.builder("invoke").addModifiers(KModifier.OVERRIDE).addAnnotation(ClassName("kotlin.jvm", "Synchronized")).addTypeVariable(typeR)
                    .addParameter("functionName", STRING).addParameter("args", argsType)
                    .addParameter("encode", encodeLambdaType).addParameter("decode", decodeType).returns(typeR)
                    .addStatement("return resources.bind { delegate.invoke(functionName, args, encode, decode) }")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("newResource").addModifiers(KModifier.OVERRIDE).addAnnotation(ClassName("kotlin.jvm", "Synchronized"))
                    .addParameter("constructorName", STRING).addParameter("args", argsType)
                    .addParameter("encode", encodeLambdaType).returns(INT)
                    .addStatement("return resources.bind { delegate.newResource(constructorName, args, encode) }")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("invokeMethod").addModifiers(KModifier.OVERRIDE).addAnnotation(ClassName("kotlin.jvm", "Synchronized")).addTypeVariable(typeR)
                    .addParameter("handle", INT).addParameter("methodName", STRING).addParameter("args", argsType)
                    .addParameter("encode", encodeLambdaType).addParameter("decode", decodeType).returns(typeR)
                    .addStatement("return resources.bind { delegate.invokeMethod(handle, methodName, args, encode, decode) }")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("dropResource").addModifiers(KModifier.OVERRIDE).addAnnotation(ClassName("kotlin.jvm", "Synchronized")).addParameter("handle", INT)
                    .addStatement("resources.bind { delegate.dropResource(handle) }")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("withBackend").addModifiers(KModifier.OVERRIDE).addAnnotation(ClassName("kotlin.jvm", "Synchronized")).addTypeVariable(typeR)
                    .addParameter("block", LambdaTypeName.get(parameters = listOf(ParameterSpec.unnamed(componentBackendClass)), returnType = typeR))
                    .returns(typeR)
                    .addStatement("return resources.bind { delegate.withBackend(block) }")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("close").addModifiers(KModifier.OVERRIDE).addAnnotation(ClassName("kotlin.jvm", "Synchronized"))
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
        return TypeSpec.classBuilder("CodePoint")
            .addKdoc("A WIT `char`: one Unicode scalar value (U+0000..U+10FFFF, excluding surrogates).\n")
            .addAnnotation(ClassName("kotlin.jvm", "JvmInline"))
            .addModifiers(KModifier.VALUE)
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
        return builder.build()
    }

    private val encodeLambdaType: LambdaTypeName
        get() = LambdaTypeName.get(parameters = listOf(ParameterSpec.unnamed(witWriterClass)), returnType = UNIT)

    /** `fun <R> [name](functionName: String, args: Array<out Any?>, encode: (WitWriter) -> Unit, decode: (WitReader) -> R): R` */
    private fun callFunctionSpec(name: String, typeR: TypeVariableName, anyNullable: TypeName): FunSpec.Builder =
        FunSpec.builder(name)
            .addTypeVariable(typeR)
            .addParameter("functionName", STRING)
            .addParameter("args", ARRAY.parameterizedBy(WildcardTypeName.producerOf(anyNullable)))
            .addParameter("encode", encodeLambdaType)
            .addParameter("decode", LambdaTypeName.get(parameters = listOf(ParameterSpec.unnamed(witReaderClass)), returnType = typeR))
            .returns(typeR)

    private fun buildWasmtimeSessionTypeSpec(): TypeSpec {
        val autoCloseableClass = ClassName("java.lang", "AutoCloseable")
        val pathClass = ClassName("java.nio.file", "Path")
        val byteArrayClass = ClassName("kotlin", "ByteArray")
        val runtimeSessionClass = ClassName(targetPackage, "WasmtimeSession")
        val ffmSessionClass = ClassName(targetPackage, "FfmWasmtimeSession")
        val jniSessionClass = ClassName(targetPackage, "JniWasmtimeSession")
        val typeR = TypeVariableName("R")
        val anyNullable = ClassName("kotlin", "Any").copy(nullable = true)

        return TypeSpec.interfaceBuilder("WasmtimeSession")
            .addKdoc(
                "One instance of a component. Generated bindings pass every call three ways: the arguments as Kotlin " +
                    "values ([InMemoryWasmtimeSession] uses them), [encode] writing them in the binary format, and " +
                    "[decode] reading the results (ADR-0015).\n",
            )
            .addSuperinterface(autoCloseableClass)
            .addFunction(
                FunSpec.builder("withBackend")
                    .addKdoc("Runs [block] on the component's backend, serialized with the session's calls: streams and futures (#16) use it.\n")
                    .addTypeVariable(typeR)
                    .addParameter("block", LambdaTypeName.get(parameters = listOf(ParameterSpec.unnamed(componentBackendClass)), returnType = typeR))
                    .returns(typeR)
                    .addStatement("throw %T(%S)", UnsupportedOperationException::class, "Streams and futures need the Chicory backend (ADR-0005)")
                    .build(),
            )
            .addFunction(
                callFunctionSpec("invoke", typeR, anyNullable)
                    .addModifiers(KModifier.ABSTRACT)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("newResource")
                    .addModifiers(KModifier.ABSTRACT)
                    .addParameter("constructorName", STRING)
                    .addParameter("args", ARRAY.parameterizedBy(WildcardTypeName.producerOf(anyNullable)))
                    .addParameter("encode", encodeLambdaType)
                    .returns(INT)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("invokeMethod")
                    .addModifiers(KModifier.ABSTRACT)
                    .addTypeVariable(typeR)
                    .addParameter("handle", INT)
                    .addParameter("methodName", STRING)
                    .addParameter("args", ARRAY.parameterizedBy(WildcardTypeName.producerOf(anyNullable)))
                    .addParameter("encode", encodeLambdaType)
                    .addParameter("decode", LambdaTypeName.get(parameters = listOf(ParameterSpec.unnamed(witReaderClass)), returnType = typeR))
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

    /**
     * `invoke`, `newResource`, `invokeMethod` and `dropResource` of a session backed by a native bridge
     * ([bridge] is the bridge expression, `%T` standing for [bridgeClass]). Function names are
     * resolved once per session (ADR-0015).
     */
    private fun nativeSessionCallFunctions(bridge: String, bridgeClass: ClassName?, sessionName: String): List<FunSpec> {
        val typeR = TypeVariableName("R")
        val anyNullable = ClassName("kotlin", "Any").copy(nullable = true)
        val synchronizedAnnotation = ClassName("kotlin.jvm", "Synchronized")
        val bridgeArgs = listOfNotNull(bridgeClass).toTypedArray<Any>()
        val invoke = callFunctionSpec("invoke", typeR, anyNullable)
            .addAnnotation(synchronizedAnnotation)
            .addModifiers(KModifier.OVERRIDE)
            .addStatement("check(!isClosed) { %S }", "$sessionName is already closed")
            .addStatement("writer.reset()")
            .addStatement("encode(writer)")
            .addStatement("val index = functionIndices.getOrPut(functionName) { $bridge.resolve(sessionPtr, functionName) }", *bridgeArgs)
            .addStatement("return decode($bridge.invoke(sessionPtr, index, writer))", *bridgeArgs)
            .build()
        val newResource = FunSpec.builder("newResource")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("constructorName", STRING)
            .addParameter("args", ARRAY.parameterizedBy(WildcardTypeName.producerOf(anyNullable)))
            .addParameter("encode", encodeLambdaType)
            .returns(INT)
            .addStatement("return invoke(constructorName, args, encode) { it.int() }")
            .build()
        val invokeMethod = FunSpec.builder("invokeMethod")
            .addModifiers(KModifier.OVERRIDE)
            .addTypeVariable(typeR)
            .addParameter("handle", INT)
            .addParameter("methodName", STRING)
            .addParameter("args", ARRAY.parameterizedBy(WildcardTypeName.producerOf(anyNullable)))
            .addParameter("encode", encodeLambdaType)
            .addParameter("decode", LambdaTypeName.get(parameters = listOf(ParameterSpec.unnamed(witReaderClass)), returnType = typeR))
            .returns(typeR)
            .addKdoc("[encode] writes the handle first, as the method's `self`.\n")
            .addStatement("return invoke(methodName, args, encode, decode)")
            .build()
        val dropResource = FunSpec.builder("dropResource")
            .addAnnotation(synchronizedAnnotation)
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("handle", INT)
            .addStatement("check(!isClosed) { %S }", "$sessionName is already closed")
            .addStatement("$bridge.dropResource(sessionPtr, handle)", *bridgeArgs)
            .build()
        return listOf(invoke, newResource, invokeMethod, dropResource)
    }

    private fun buildFfmWasmtimeSessionTypeSpec(): TypeSpec {
        val runtimeSessionClass = ClassName(targetPackage, "WasmtimeSession")
        val ffmSessionClass = ClassName(targetPackage, "FfmWasmtimeSession")
        val ffmBridgeClass = ClassName("wasm2kt.runtime", "FfmWasmtimeBridge")
        val pathClass = ClassName("java.nio.file", "Path")
        val byteArrayClass = ClassName("kotlin", "ByteArray")
        val anyNullable = ClassName("kotlin", "Any").copy(nullable = true)
        val synchronizedAnnotation = ClassName("kotlin.jvm", "Synchronized")
        val typeR = TypeVariableName("R")

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
            .addProperty(
                PropertySpec.builder("functionIndices", ClassName("kotlin.collections", "HashMap").parameterizedBy(STRING, INT), KModifier.PRIVATE)
                    .initializer("HashMap()")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("writer", witWriterClass, KModifier.PRIVATE)
                    .initializer("%T()", witWriterClass)
                    .build(),
            )
            .addFunctions(nativeSessionCallFunctions("bridge", null, "FfmWasmtimeSession"))
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
                            .addStatement("return %T(bridge, bridge.openSession(wasmPath, imports%L))", ffmSessionClass, bridgeIdArg)
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
                            .addStatement("return %T(bridge, bridge.openSessionBytes(wasmBytes, imports%L))", ffmSessionClass, bridgeIdArg)
                            .build(),
                    )
                    .addFunction(
                        FunSpec.builder("openPrecompiled")
                            .addKdoc(PRECOMPILED_TRUST_KDOC)
                            .addParameter("cwasmPath", pathClass)
                            .addParameter("imports", anyNullable)
                            .addParameter(ParameterSpec.builder("bridge", ffmBridgeClass).defaultValue("%T.getDefault()", ffmBridgeClass).build())
                            .returns(ffmSessionClass)
                            .addStatement("return %T(bridge, bridge.openPrecompiledSession(cwasmPath, imports%L))", ffmSessionClass, bridgeIdArg)
                            .build(),
                    )
                    .addFunction(
                        FunSpec.builder("openPrecompiled")
                            .addKdoc(PRECOMPILED_TRUST_KDOC)
                            .addParameter("cwasmBytes", byteArrayClass)
                            .addParameter("imports", anyNullable)
                            .addParameter(ParameterSpec.builder("bridge", ffmBridgeClass).defaultValue("%T.getDefault()", ffmBridgeClass).build())
                            .returns(ffmSessionClass)
                            .addStatement("return %T(bridge, bridge.openPrecompiledSessionBytes(cwasmBytes, imports%L))", ffmSessionClass, bridgeIdArg)
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
        val anyNullable = ClassName("kotlin", "Any").copy(nullable = true)
        val typeR = TypeVariableName("R")

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
            .addProperty(
                PropertySpec.builder("functionIndices", ClassName("kotlin.collections", "HashMap").parameterizedBy(STRING, INT), KModifier.PRIVATE)
                    .initializer("HashMap()")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("writer", witWriterClass, KModifier.PRIVATE)
                    .initializer("%T()", witWriterClass)
                    .build(),
            )
            .addFunctions(nativeSessionCallFunctions("%T", jniBridgeClass, "JniWasmtimeSession"))
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
                            .addStatement("return %T(%T.openSession(wasmPath, imports%L))", jniSessionClass, jniBridgeClass, bridgeIdArg)
                            .build(),
                    )
                    .addFunction(
                        FunSpec.builder("open")
                            .addParameter("wasmBytes", byteArrayClass)
                            .addParameter("imports", anyNullable)
                            .returns(jniSessionClass)
                            .addStatement("return %T(%T.openSessionBytes(wasmBytes, imports%L))", jniSessionClass, jniBridgeClass, bridgeIdArg)
                            .build(),
                    )
                    .addFunction(
                        FunSpec.builder("openPrecompiled")
                            .addKdoc(PRECOMPILED_TRUST_KDOC)
                            .addParameter("cwasmPath", pathClass)
                            .addParameter("imports", anyNullable)
                            .returns(jniSessionClass)
                            .addStatement("return %T(%T.openPrecompiledSession(cwasmPath, imports%L))", jniSessionClass, jniBridgeClass, bridgeIdArg)
                            .build(),
                    )
                    .addFunction(
                        FunSpec.builder("openPrecompiled")
                            .addKdoc(PRECOMPILED_TRUST_KDOC)
                            .addParameter("cwasmBytes", byteArrayClass)
                            .addParameter("imports", anyNullable)
                            .returns(jniSessionClass)
                            .addStatement("return %T(%T.openPrecompiledSessionBytes(cwasmBytes, imports%L))", jniSessionClass, jniBridgeClass, bridgeIdArg)
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
            .addProperty(
                PropertySpec.builder("functionIndices", ClassName("kotlin.collections", "HashMap").parameterizedBy(STRING, INT), KModifier.PRIVATE)
                    .initializer("HashMap()")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("writer", witWriterClass, KModifier.PRIVATE)
                    .initializer("%T()", witWriterClass)
                    .build(),
            )
            .addFunction(
                callFunctionSpec("invoke", typeR, anyNullable)
                    .addAnnotation(synchronizedAnnotation)
                    .addModifiers(KModifier.OVERRIDE)
                    .addStatement("check(!isClosed) { %S }", closedMessage)
                    .addStatement("writer.reset()")
                    .addStatement("encode(writer)")
                    .addStatement("val index = functionIndices.getOrPut(functionName) { backend.resolve(functionName) }")
                    .addStatement("return decode(backend.invoke(index, writer.toReader()))")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("newResource")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("constructorName", STRING)
                    .addParameter("args", ARRAY.parameterizedBy(WildcardTypeName.producerOf(anyNullable)))
                    .addParameter("encode", encodeLambdaType)
                    .returns(INT)
                    .addStatement("return invoke(constructorName, args, encode) { it.int() }")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("invokeMethod")
                    .addModifiers(KModifier.OVERRIDE)
                    .addTypeVariable(typeR)
                    .addParameter("handle", INT)
                    .addParameter("methodName", STRING)
                    .addParameter("args", ARRAY.parameterizedBy(WildcardTypeName.producerOf(anyNullable)))
                    .addParameter("encode", encodeLambdaType)
                    .addParameter("decode", LambdaTypeName.get(parameters = listOf(ParameterSpec.unnamed(witReaderClass)), returnType = typeR))
                    .returns(typeR)
                    .addStatement("return invoke(methodName, args, encode, decode)")
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
                FunSpec.builder("withBackend")
                    .addAnnotation(synchronizedAnnotation)
                    .addModifiers(KModifier.OVERRIDE)
                    .addTypeVariable(typeR)
                    .addParameter("block", LambdaTypeName.get(parameters = listOf(ParameterSpec.unnamed(backendClass)), returnType = typeR))
                    .returns(typeR)
                    .addStatement("check(!isClosed) { %S }", closedMessage)
                    .addStatement("return block(backend)")
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
                    .addParameter("args", ARRAY.parameterizedBy(WildcardTypeName.producerOf(anyNullable)))
                    .addParameter("encode", encodeLambdaType)
                    .addParameter("decode", LambdaTypeName.get(parameters = listOf(ParameterSpec.unnamed(witReaderClass)), returnType = typeR))
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
                    .addParameter("args", ARRAY.parameterizedBy(WildcardTypeName.producerOf(anyNullable)))
                    .addParameter("encode", encodeLambdaType)
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
                    .addParameter("args", ARRAY.parameterizedBy(WildcardTypeName.producerOf(anyNullable)))
                    .addParameter("encode", encodeLambdaType)
                    .addParameter("decode", LambdaTypeName.get(parameters = listOf(ParameterSpec.unnamed(witReaderClass)), returnType = typeR))
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
