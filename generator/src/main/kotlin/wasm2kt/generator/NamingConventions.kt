package wasm2kt.generator

object NamingConventions {
    private fun splitWords(witName: String): List<String> =
        witName
            .trim()
            .removePrefix("%")
            .removePrefix("[constructor]")
            .removePrefix("[method]")
            .removePrefix("[static]")
            .split('-', '_', '.', '/')
            .map { it.removePrefix("%") }
            .filter { it.isNotEmpty() }

    /**
     * Converts `kebab-case` (e.g. `http-types`, `user-id`) to `PascalCase` (`HttpTypes`, `UserId`).
     */
    fun toPascalCase(witName: String): String =
        splitWords(witName).joinToString("") { word ->
            word.lowercase().replaceFirstChar { it.uppercaseChar() }
        }

    /**
     * Converts `kebab-case` (e.g. `get-user-id`) to `camelCase` (`getUserId`).
     */
    fun toCamelCase(witName: String): String {
        val words = splitWords(witName)
        if (words.isEmpty()) return witName
        return words.first().lowercase() +
            words.drop(1).joinToString("") { word ->
                word.lowercase().replaceFirstChar { it.uppercaseChar() }
            }
    }

    /**
     * Converts `kebab-case` (e.g. `not-found`) to `UPPER_SNAKE_CASE` (`NOT_FOUND`).
     */
    fun toUpperSnakeCase(witName: String): String =
        splitWords(witName).joinToString("_") { it.uppercase() }

    /**
     * Extracts the short method/static function name from WIT's `[method]resource.func-name` or `[static]resource.func-name`.
     */
    fun extractResourceMemberName(rawName: String): String {
        val afterDot = rawName.substringAfter('.', rawName)
        return toCamelCase(afterDot)
    }

    /** Kotlin hard keywords, which cannot be package segments without backticks. */
    private val HARD_KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
        "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
        "typeof", "val", "var", "when", "while",
    )

    /**
     * Converts a WIT namespace, package or interface name to a Kotlin package segment (ADR-0011):
     * lowercase with `-` replaced by `_` (`outgoing-handler` -> `outgoing_handler`), and a trailing `_`
     * for Kotlin hard keywords (`is` -> `is_`). WIT names never contain `_`, so the mapping is one-to-one.
     */
    fun toPackageSegment(witName: String): String {
        val segment = witName.trim().removePrefix("%").lowercase().replace('-', '_')
        return if (segment in HARD_KEYWORDS) "${segment}_" else segment
    }
}
