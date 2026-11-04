package wasm2kt.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import wasm2kt.ast.WitResolve
import wasm2kt.generator.InterfaceManifest
import wasm2kt.generator.WitToKotlinPoetConverter
import wasm2kt.tooling.TypedBridge
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** `wasm2kt`: one subcommand per build step, so each fits a task runner (make, just, ...). */
class Wasm2KtCommand : CliktCommand(name = "wasm2kt") {
    init {
        subcommands(GenerateCommand(), BridgeCommand())
    }

    override fun help(context: Context): String =
        "Build steps for WebAssembly components in Kotlin: generate bindings, build a typed Wasmtime bridge."

    override fun run() = Unit

    companion object {
        private val SUBCOMMANDS = setOf("generate", "bridge")

        /** `wasm2kt <input> -o ... -p ...` (without a subcommand) is `wasm2kt generate ...`. */
        fun withDefaultSubcommand(args: List<String>): List<String> {
            val first = args.firstOrNull() ?: return args
            return if (first in SUBCOMMANDS || first == "-h" || first == "--help") args else listOf("generate") + args
        }
    }
}

/** `wasm2kt generate`: Kotlin bindings for a world. */
class GenerateCommand : CliktCommand(name = "generate") {
    override fun help(context: Context): String =
        "Generate type-safe Kotlin host bindings from WebAssembly Components / WIT definitions."

    private val inputPath: Path? by argument(
        name = "input-path",
        help = "Path to .wit file, directory containing WIT packages, .wasm component binary, or JSON AST file",
    ).path(mustExist = true).optional()

    private val outputDir: Path by option(
        "-o",
        "--output",
        help = "Directory where generated .kt files will be placed",
    ).path().required()

    private val packageName: String by option(
        "-p",
        "--package",
        help = "Target Kotlin package name",
    ).required()

    private val worldName: String? by option(
        "-w",
        "--world",
        help = "Specific WIT world name to generate bindings for",
    )

    private val fromJson: Boolean by option(
        "--from-json",
        help = "Indicate input is already a wasm-tools JSON AST",
    ).flag(default = false)

    private val noWasmtime: Boolean by option(
        "--no-wasmtime",
        help = "Omit the Wasmtime (FFM/JNI) session implementations, e.g. for Android builds using the Chicory backend",
    ).flag(default = false)

    private val mappings: List<Pair<String, String>> by option(
        "--with",
        metavar = "INTERFACE=PACKAGE",
        help = "Use the existing bindings in PACKAGE for an imported interface instead of generating it " +
            "(e.g. wasi:filesystem/types@0.3.0=wasm2kt.wasi.wasi.filesystem.types); repeatable (ADR-0014)",
    ).convert { value ->
        val key = value.substringBefore('=', "")
        val pkg = value.substringAfter('=', "")
        if (key.isEmpty() || pkg.isEmpty()) fail("expected INTERFACE=PACKAGE, got '$value'")
        key to pkg
    }.multiple()

    private val libraries: List<Path> by option(
        "--library",
        help = "Jar or class directory of a library generated with --interfaces-only; the interfaces its manifest " +
            "lists are used instead of generated when the world imports them. Repeatable (ADR-0014)",
    ).path(mustExist = true).multiple()

    private val noLibraryInterfaces: Boolean by option(
        "--no-library-interfaces",
        help = "Ignore the manifests of --library entries (only explicit --with mappings apply)",
    ).flag(default = false)

    private val interfacesOnly: Boolean by option(
        "--interfaces-only",
        help = "Generate only the interfaces the world imports, plus a manifest, for a library other bindings map to (ADR-0014)",
    ).flag(default = false)

    private val resourcesOutputDir: Path? by option(
        "--resources-output",
        help = "Directory for the --interfaces-only manifest (META-INF/wasm2kt/interfaces/<package>.json); defaults to --output",
    ).path()

    private val wasmToolsPath: String by option(
        "--wasm-tools-path",
        help = "Override for the wasm-tools binary path",
    ).default("wasm-tools")

    private val bridgeId: String? by option(
        "--bridge-id",
        help = "Open the Wasmtime sessions as this component of a typed bridge (see `wasm2kt bridge`); " +
            "the bridge library must then be a typed bridge that contains it (ADR-0016)",
    )

    override fun run() {
        val jsonAst = if (fromJson) {
            val path = inputPath
            if (path != null) {
                Files.readString(path)
            } else {
                System.`in`.bufferedReader().readText()
            }
        } else {
            val path = inputPath
                ?: throw IllegalArgumentException("<input-path> is required when --from-json is not set.")
            extractWitJsonViaWasmTools(wasmToolsPath, path)
        }

        val resolve = WitResolve.parse(jsonAst)
        val converter = WitToKotlinPoetConverter(
            resolve = resolve,
            targetPackage = packageName,
            targetWorldName = worldName,
            emitWasmtimeSessions = !noWasmtime,
            interfaceMappings = mappings.groupBy({ it.first }, { it.second }).mapValues { (key, pkgs) ->
                require(pkgs.distinct().size == 1) { "--with maps '$key' to several packages: ${pkgs.distinct().joinToString()}" }
                pkgs.first()
            },
            libraryManifests = if (noLibraryInterfaces) emptyList() else InterfaceManifest.readAll(libraries),
            interfacesOnly = interfacesOnly,
            bridgeId = bridgeId,
        )

        val fileSpecs = converter.generate()
        Files.createDirectories(outputDir)
        for (fileSpec in fileSpecs) {
            fileSpec.writeTo(outputDir)
        }
        converter.manifest?.writeTo(resourcesOutputDir ?: outputDir)
    }

    companion object {
        /** The WIT JSON of [path]: a `.json` file as is, otherwise from `wasm-tools component wit --json`. */
        fun witJson(wasmToolsExecutable: String, path: Path): String =
            if (path.toString().endsWith(".json")) Files.readString(path) else extractWitJsonViaWasmTools(wasmToolsExecutable, path)

        fun extractWitJsonViaWasmTools(wasmToolsExecutable: String, targetPath: Path): String {
            val process = ProcessBuilder(
                wasmToolsExecutable,
                "component",
                "wit",
                "--json",
                targetPath.toAbsolutePath().toString(),
            )
                .redirectErrorStream(false)
                .start()

            val stderrFuture = java.util.concurrent.CompletableFuture.supplyAsync {
                process.errorStream.bufferedReader().use { it.readText() }
            }
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = stderrFuture.join()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw IllegalStateException(
                    "wasm-tools exited with code $exitCode:\n$stderr",
                )
            }
            return stdout
        }
    }
}

/**
 * `wasm2kt bridge`: the typed Wasmtime bridge (ADR-0016) of a project's components, as one Cargo crate
 * and one native library. Each component's ID must match its bindings' `wasm2kt generate --bridge-id`.
 */
class BridgeCommand : CliktCommand(name = "bridge") {
    override fun help(context: Context): String =
        "Generate the typed Wasmtime bridge crate for components (and build it with --build)."

    private val outputDir: Path by option("-o", "--output", help = "Directory of the generated Cargo crate").path().required()

    private val components: List<Pair<String, Path>> by option(
        "--component",
        metavar = "ID=PATH",
        help = "A component of the bridge: its ID (the bindings' --bridge-id) and its .wasm, .wit or WIT JSON. Repeatable",
    ).convert { value ->
        val id = value.substringBefore('=', "")
        val path = value.substringAfter('=', "")
        if (id.isEmpty() || path.isEmpty()) fail("expected ID=PATH, got '$value'")
        id to Path.of(path)
    }.multiple(required = true)

    private val worlds: List<Pair<String, String>> by option(
        "--world",
        metavar = "ID=WORLD",
        help = "The world of a component whose WIT defines several. Repeatable",
    ).convert { value ->
        val id = value.substringBefore('=', "")
        val world = value.substringAfter('=', "")
        if (id.isEmpty() || world.isEmpty()) fail("expected ID=WORLD, got '$value'")
        id to world
    }.multiple()

    private val build: Boolean by option("--build", help = "Also run cargo build --release; prints the library's path").flag(default = false)

    private val target: String? by option("--target", help = "Rust target triple to build for (default: the host)")

    private val targetDir: Path? by option("--target-dir", help = "Cargo's target directory (share it between builds to compile Wasmtime once)").path()

    private val cargo: String by option("--cargo", help = "Override for the cargo binary path").default("cargo")

    private val wasmToolsPath: String by option("--wasm-tools-path", help = "Override for the wasm-tools binary path").default("wasm-tools")

    override fun run() {
        val worldOf = worlds.toMap()
        val unknown = worldOf.keys - components.map { it.first }.toSet()
        require(unknown.isEmpty()) { "--world names unknown components: ${unknown.joinToString()}" }
        TypedBridge.generateCrate(
            components.map { (id, path) ->
                TypedBridge.Component(id, WitResolve.parse(GenerateCommand.witJson(wasmToolsPath, path)), worldOf[id])
            },
            outputDir,
        )
        if (build) {
            echo(TypedBridge.cargoBuild(outputDir, targetDir, target, cargo).toAbsolutePath())
        }
    }
}

fun main(args: Array<String>) = Wasm2KtCommand().main(Wasm2KtCommand.withDefaultSubcommand(args.toList()))
