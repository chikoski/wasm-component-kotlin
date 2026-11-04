package wasm2kt.tooling

/**
 * Reads tooling's own class path resources without the JVM's jar cache (#63).
 *
 * `getResourceAsStream` reads jar entries through a `JarFile` that the JVM caches per jar and shares
 * between class loaders. The Gradle plugin runs tooling in isolated class loaders (ADR-0004), several
 * at once; when one is closed, it can close the shared `JarFile` while another worker is still reading
 * from it, which fails that read with `java.io.IOException: Stream closed`. An uncached connection
 * opens the jar for this read only.
 */
internal object ToolingResources {
    fun read(name: String): ByteArray? {
        val url = ToolingResources::class.java.getResource(name) ?: return null
        val connection = url.openConnection().apply { useCaches = false }
        return connection.getInputStream().use { it.readBytes() }
    }
}
