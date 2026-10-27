package wasm2kt.generator

object NamingConventions {
    private fun splitWords(witName: String): List<String> =
        witName
            .trim()
            .removePrefix("[constructor]")
            .removePrefix("[method]")
            .removePrefix("[static]")
            .split('-', '_', '.', '/')
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
}
