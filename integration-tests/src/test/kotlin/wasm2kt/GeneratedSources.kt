package wasm2kt

import java.nio.file.Files
import java.nio.file.Path

/**
 * Every Kotlin file the generator wrote under [outputDir], including the per-interface packages
 * (ADR-0011), plus any harness written next to them. Compiled classes under `classes/` are skipped.
 */
internal fun generatedSources(outputDir: Path): List<String> =
    Files.walk(outputDir).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
            .filter { !outputDir.relativize(it).startsWith("classes") }
            .map { it.toString() }
            .sorted()
            .toList()
    }
