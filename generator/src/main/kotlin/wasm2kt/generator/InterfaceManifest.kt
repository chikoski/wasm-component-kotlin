package wasm2kt.generator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * The interfaces a library generated with `--interfaces-only` provides (ADR-0014): each fully
 * qualified interface name, with its version, and the Kotlin package of its bindings. Stored in the
 * library's jar at [resourcePath], where the generator of other bindings finds it.
 */
class InterfaceManifest(
    /** The base package the library was generated into; also names the manifest file. */
    val basePackage: String,
    /** `wasi:filesystem/types@0.3.0` to `wasm2kt.wasi.wasi.filesystem.types`. */
    val interfaces: Map<String, String>,
    /** Where the manifest was read from, for error messages. */
    val source: String = basePackage,
) {
    fun toJson(): String {
        val json = JsonObject(
            mapOf(
                "format" to JsonPrimitive(FORMAT),
                "package" to JsonPrimitive(basePackage),
                "interfaces" to JsonObject(interfaces.toSortedMap().mapValues { JsonPrimitive(it.value) }),
            ),
        )
        return PRETTY.encodeToString(JsonObject.serializer(), json) + "\n"
    }

    /** Writes the manifest under [resourcesDir] at [resourcePath] and returns the file. */
    fun writeTo(resourcesDir: Path): Path {
        val file = resourcesDir.resolve(resourcePath(basePackage))
        Files.createDirectories(file.parent)
        Files.writeString(file, toJson())
        return file
    }

    override fun toString(): String = "InterfaceManifest($basePackage, $interfaces)"

    companion object {
        const val FORMAT = 1
        const val DIRECTORY = "META-INF/wasm2kt/interfaces"

        private val PRETTY = Json { prettyPrint = true }

        /** One file per library, named after its base package, so merged jars (an APK) keep them all. */
        fun resourcePath(basePackage: String): String = "$DIRECTORY/$basePackage.json"

        fun parse(json: String, source: String): InterfaceManifest {
            val obj = try {
                Json.parseToJsonElement(json).jsonObject
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Malformed wasm2kt interface manifest $source: ${e.message}", e)
            }
            val format = obj["format"]?.jsonPrimitive?.int
            require(format == FORMAT) {
                "wasm2kt interface manifest $source has format $format; this wasm2kt reads format $FORMAT (regenerate the library with a matching wasm2kt)"
            }
            val base = requireNotNull(obj["package"]?.jsonPrimitive?.content) { "wasm2kt interface manifest $source has no package" }
            val interfaces = requireNotNull(obj["interfaces"] as? JsonObject) { "wasm2kt interface manifest $source has no interfaces" }
                .mapValues { it.value.jsonPrimitive.content }
            return InterfaceManifest(base, interfaces, source)
        }

        /** Every manifest in the given jars and class directories; other files and missing paths are skipped. */
        fun readAll(classpath: Iterable<Path>): List<InterfaceManifest> = classpath.flatMap { entry ->
            when {
                Files.isDirectory(entry) -> {
                    val dir = entry.resolve(DIRECTORY)
                    if (!Files.isDirectory(dir)) {
                        emptyList()
                    } else {
                        Files.list(dir).use { files ->
                            files.filter { it.fileName.toString().endsWith(".json") }.sorted().toList()
                        }.map { parse(Files.readString(it), it.toString()) }
                    }
                }
                Files.isRegularFile(entry) && entry.fileName.toString().endsWith(".jar") -> ZipFile(entry.toFile()).use { zip ->
                    zip.entries().asSequence()
                        .filter { !it.isDirectory && it.name.startsWith("$DIRECTORY/") && it.name.endsWith(".json") }
                        .sortedBy { it.name }
                        .map { e -> parse(zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) }, "${entry.fileName}!/${e.name}") }
                        .toList()
                }
                else -> emptyList()
            }
        }
    }
}
