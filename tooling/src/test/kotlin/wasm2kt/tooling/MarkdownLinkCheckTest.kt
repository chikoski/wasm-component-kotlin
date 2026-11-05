package wasm2kt.tooling

import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.test.assertTrue

/**
 * Verifies that all relative links in repository Markdown files resolve to existing files or directories.
 */
class MarkdownLinkCheckTest {
    private val rootDir: Path = Paths.get(System.getProperty("wasm2kt.rootDir")).toAbsolutePath().normalize()

    private val excludedDirNames = setOf(".git", ".gradle", ".kotlin", "build", "target", "node_modules")

    private val topLevelFiles = listOf(
        "README.md",
        "CONTRIBUTING.md",
        "CHANGELOG.md",
        "CLAUDE.md",
        "AGENTS.md",
    )

    private val scannedSubtrees = listOf(
        "docs",
        "examples",
        "samples",
        "fixtures",
        "decomposer",
        "rust-bridge",
        "gradle-plugin",
    )

    // Matches inline Markdown links/images: [text](target) or ![alt](target)
    private val inlineLinkRegex = Regex("""!?\[[^\]]*]\(([^)]+)\)""")

    // Matches reference-style link definitions: [label]: target
    private val refLinkRegex = Regex("""^\s*\[[^\]]+]:\s*(\S+)""")

    @Test
    fun `all relative links in markdown documentation resolve to existing files`() {
        val markdownFiles = collectMarkdownFiles()
        assertTrue(markdownFiles.isNotEmpty(), "Expected to find Markdown files under $rootDir")

        val brokenLinks = mutableListOf<String>()

        for (mdFile in markdownFiles) {
            val relFile = rootDir.relativize(mdFile)
            val lines = Files.readAllLines(mdFile)
            var inFence = false
            var fenceMarker = ""

            for ((index, rawLine) in lines.withIndex()) {
                val trimmed = rawLine.trimStart()
                if (!inFence && (trimmed.startsWith("```") || trimmed.startsWith("~~~"))) {
                    inFence = true
                    fenceMarker = trimmed.take(3)
                    continue
                } else if (inFence) {
                    if (trimmed.startsWith(fenceMarker)) {
                        inFence = false
                        fenceMarker = ""
                    }
                    continue
                }

                val lineWithoutInlineCode = stripInlineCode(rawLine)
                val targets = mutableListOf<String>()
                for (match in inlineLinkRegex.findAll(lineWithoutInlineCode)) {
                    targets += match.groupValues[1]
                }
                refLinkRegex.find(lineWithoutInlineCode)?.let { match ->
                    targets += match.groupValues[1]
                }

                for (rawTarget in targets) {
                    val target = cleanLinkTarget(rawTarget)
                    if (target.isEmpty() || isExternalOrAnchorOnly(target)) continue

                    val pathPart = target.substringBefore('#').substringBefore('?').trim()
                    if (pathPart.isEmpty()) continue

                    val decodedPath = runCatching { URI.create(pathPart).path ?: pathPart }.getOrDefault(pathPart)
                    val resolved = if (decodedPath.startsWith("/")) {
                        rootDir.resolve(decodedPath.removePrefix("/")).normalize()
                    } else {
                        mdFile.parent.resolve(decodedPath).normalize()
                    }

                    if (!Files.exists(resolved)) {
                        brokenLinks += "$relFile:${index + 1} -> '$rawTarget' (resolved to ${rootDir.relativize(resolved)})"
                    }
                }
            }
        }

        assertTrue(
            brokenLinks.isEmpty(),
            "Found broken relative Markdown links:\n" + brokenLinks.joinToString("\n") { "  - $it" },
        )
    }

    private fun collectMarkdownFiles(): List<Path> {
        val result = mutableListOf<Path>()
        for (name in topLevelFiles) {
            val file = rootDir.resolve(name)
            if (file.isRegularFile()) result.add(file)
        }
        for (sub in scannedSubtrees) {
            val dir = rootDir.resolve(sub)
            if (!Files.isDirectory(dir)) continue
            Files.walk(dir).use { stream ->
                stream
                    .filter { path ->
                        path.isRegularFile() &&
                            path.extension.equals("md", ignoreCase = true) &&
                            rootDir.relativize(path).none { part -> part.toString() in excludedDirNames }
                    }
                    .sorted()
                    .forEach { result.add(it) }
            }
        }
        return result
    }

    private fun stripInlineCode(line: String): String {
        val sb = StringBuilder(line.length)
        var i = 0
        while (i < line.length) {
            if (line[i] == '`') {
                var ticks = 0
                while (i + ticks < line.length && line[i + ticks] == '`') ticks++
                val closing = line.indexOf("`".repeat(ticks), i + ticks)
                if (closing != -1) {
                    i = closing + ticks
                    continue
                }
            }
            sb.append(line[i])
            i++
        }
        return sb.toString()
    }

    private fun cleanLinkTarget(raw: String): String {
        var s = raw.trim()
        // Strip optional title: path "title" or path 'title'
        val spaceIdx = s.indexOfFirst { it.isWhitespace() }
        if (spaceIdx != -1) {
            s = s.substring(0, spaceIdx).trim()
        }
        if (s.startsWith("<") && s.endsWith(">")) {
            s = s.substring(1, s.length - 1).trim()
        }
        return s
    }

    private fun isExternalOrAnchorOnly(target: String): Boolean =
        target.startsWith("#") ||
            target.startsWith("http://") ||
            target.startsWith("https://") ||
            target.startsWith("mailto:") ||
            target.startsWith("conversation://") ||
            target.startsWith("file://")
}
