package wasm2kt.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
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
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class Wasm2KtCommand : CliktCommand(name = "wasm2kt") {
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
        )

        val fileSpecs = converter.generate()
        Files.createDirectories(outputDir)
        for (fileSpec in fileSpecs) {
            fileSpec.writeTo(outputDir)
        }
        converter.manifest?.writeTo(resourcesOutputDir ?: outputDir)
    }

    companion object {
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

fun main(args: Array<String>) = Wasm2KtCommand().main(args)
