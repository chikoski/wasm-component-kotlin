package wasm2kt.runtime

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class BridgeLibraryTest {
    @Test
    fun `platforms and file names follow os name and arch`() {
        assertEquals("linux-x86_64", BridgeLibrary.platform("Linux", "amd64"))
        assertEquals("linux-aarch64", BridgeLibrary.platform("Linux", "aarch64"))
        assertEquals("macos-aarch64", BridgeLibrary.platform("Mac OS X", "aarch64"))
        assertEquals("macos-x86_64", BridgeLibrary.platform("Mac OS X", "x86_64"))
        assertEquals("windows-x86_64", BridgeLibrary.platform("Windows 11", "amd64"))
        assertEquals("META-INF/wasm2kt/native/linux-x86_64/libwasm2kt_wasmtime_bridge.so", BridgeLibrary.resourcePath("linux-x86_64"))
        assertEquals("META-INF/wasm2kt/native/macos-aarch64/libwasm2kt_wasmtime_bridge.dylib", BridgeLibrary.resourcePath("macos-aarch64"))
        assertEquals("META-INF/wasm2kt/native/windows-x86_64/wasm2kt_wasmtime_bridge.dll", BridgeLibrary.resourcePath("windows-x86_64"))
    }

    @Test
    fun `a bridge on the class path is copied to a file`(@TempDir dir: Path) {
        val bytes = ByteArray(1000) { it.toByte() }
        val resource = dir.resolve(BridgeLibrary.resourcePath("linux-x86_64"))
        Files.createDirectories(resource.parent)
        Files.write(resource, bytes)
        URLClassLoader(arrayOf(dir.toUri().toURL()), null).use { loader ->
            val file = BridgeLibrary.fromClassPath(loader, "linux-x86_64")!!
            assertEquals("libwasm2kt_wasmtime_bridge.so", file.fileName.toString())
            assertNotEquals(resource, file)
            assertContentEquals(bytes, Files.readAllBytes(file))
            // Extracted once per JVM.
            assertEquals(file, BridgeLibrary.fromClassPath(loader, "linux-x86_64"))
            assertNull(BridgeLibrary.fromClassPath(loader, "macos-aarch64"))
        }
    }
}
