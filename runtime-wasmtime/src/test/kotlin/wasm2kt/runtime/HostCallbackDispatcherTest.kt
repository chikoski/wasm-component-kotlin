package wasm2kt.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.lang.foreign.Arena
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostCallbackDispatcherTest {
    @Test
    fun `reports a buffer overflow instead of truncating the JSON response`() {
        val largeImports = object : WitHostImportDispatcher {
            override fun dispatchHostImport(importSpec: String, args: JsonArray): JsonElement =
                JsonPrimitive("x".repeat(200))
        }
        Arena.ofConfined().use { arena ->
            val dispatcher = HostCallbackDispatcher(largeImports)
            val importPtr = FfmWasmtimeBridge.allocateUtf8CString(arena, "env-provider#format-tag")
            val argsPtr = FfmWasmtimeBridge.allocateUtf8CString(arena, "[]")
            val tinyCap = 128L
            val outBuf = arena.allocate(tinyCap)
            val status = dispatcher.onHostCall(importPtr, argsPtr, outBuf, tinyCap)
            assertEquals(1, status, "Buffer overflow in HostCallbackDispatcher must return non-zero error status")
            val errMsg = FfmWasmtimeBridge.readNullTerminatedUtf8(outBuf)
            assertTrue(errMsg.contains("exceeds callback buffer capacity"), "Expected overflow message, got: $errMsg")
        }
    }
}
