package wasm2kt.generator

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The manifest a library generated with `--interfaces-only` carries (ADR-0014). */
class InterfaceManifestTest {
    private val manifest = InterfaceManifest(
        basePackage = "wasm2kt.wasi",
        interfaces = mapOf(
            "wasi:io/streams@0.3.0" to "wasm2kt.wasi.wasi.io.streams",
            "wasi:filesystem/types@0.3.0" to "wasm2kt.wasi.wasi.filesystem.types",
        ),
    )

    @Test
    fun `manifests are found in class directories and jars`(@TempDir dir: Path) {
        val classes = dir.resolve("classes")
        assertEquals(classes.resolve("META-INF/wasm2kt/interfaces/wasm2kt.wasi.json"), manifest.writeTo(classes))

        val jar = dir.resolve("other.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(JarEntry(InterfaceManifest.resourcePath("com.example.lib")))
            out.write(InterfaceManifest("com.example.lib", mapOf("example:a/b@1.0.0" to "com.example.lib.example.a.b")).toJson().toByteArray())
            out.closeEntry()
            out.putNextEntry(JarEntry("META-INF/MANIFEST.MF"))
            out.closeEntry()
        }

        val read = InterfaceManifest.readAll(listOf(classes, jar, dir.resolve("missing.jar")))
        assertEquals(listOf("wasm2kt.wasi", "com.example.lib"), read.map { it.basePackage })
        assertEquals(manifest.interfaces, read[0].interfaces)
        assertEquals("other.jar!/META-INF/wasm2kt/interfaces/com.example.lib.json", read[1].source)
    }

    @Test
    fun `unknown formats are rejected`() {
        val e = assertThrows<IllegalArgumentException> {
            InterfaceManifest.parse(manifest.toJson().replace("\"format\": 1", "\"format\": 2"), "lib.jar")
        }
        assertTrue("format 2" in e.message!! && "lib.jar" in e.message!!, e.message)
    }
}
