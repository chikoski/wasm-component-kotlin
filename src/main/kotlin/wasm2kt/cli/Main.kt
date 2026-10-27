package wasm2kt.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import wasm2kt.ast.WitResolve
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
        )

        val fileSpecs = converter.generate()
        Files.createDirectories(outputDir)
        for (fileSpec in fileSpecs) {
            fileSpec.writeTo(outputDir)
        }
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

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
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
