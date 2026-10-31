package wasm2kt.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The FFM upcall hands its response back in a buffer of the exact size (#35); in the bridge the
 * buffer comes from `wasm2kt_alloc_bytes`, here from a test arena.
 */
class HostCallbackDispatcherTest {
    private class Call(arena: Arena, imports: WitHostImportDispatcher) {
        val allocated = mutableListOf<MemorySegment>()
        private val dispatcher = HostCallbackDispatcher(imports) { bytes ->
            arena.allocate(bytes.size.toLong()).also {
                MemorySegment.copy(bytes, 0, it, ValueLayout.JAVA_BYTE, 0L, bytes.size)
                allocated += it
            }
        }
        private val importPtr = FfmWasmtimeBridge.allocateUtf8CString(arena, "env-provider#format-tag")
        private val argsPtr = FfmWasmtimeBridge.allocateUtf8CString(arena, "[]")
        private val outPtr = arena.allocate(ValueLayout.ADDRESS).also { it.set(ValueLayout.ADDRESS, 0L, MemorySegment.NULL) }
        private val outLen = arena.allocate(ValueLayout.JAVA_LONG).also { it.set(ValueLayout.JAVA_LONG, 0L, 0L) }

        fun run(): Pair<Int, String?> {
            val status = dispatcher.onHostCall(importPtr, argsPtr, outPtr, outLen)
            val ptr = outPtr.get(ValueLayout.ADDRESS, 0L)
            if (ptr == MemorySegment.NULL) return status to null
            val len = outLen.get(ValueLayout.JAVA_LONG, 0L).toInt()
            val bytes = ByteArray(len)
            MemorySegment.copy(ptr.reinterpret(len.toLong()), ValueLayout.JAVA_BYTE, 0L, bytes, 0, len)
            return status to String(bytes, StandardCharsets.UTF_8)
        }
    }

    private fun imports(result: () -> JsonElement) = object : WitHostImportDispatcher {
        override fun dispatchHostImport(importSpec: String, args: JsonArray): JsonElement = result()
    }

    @Test
    fun `responses of any size are handed back whole`() {
        val text = "日本語-".repeat(100_000) // 1 MB of UTF-8, far past the old 64 KiB buffer
        Arena.ofConfined().use { arena ->
            val call = Call(arena, imports { JsonPrimitive(text) })
            val (status, response) = call.run()
            assertEquals(0, status)
            assertEquals("\"$text\"", response)
            assertEquals(1, call.allocated.size)
        }
    }

    @Test
    fun `no result leaves the response empty`() {
        Arena.ofConfined().use { arena ->
            val call = Call(arena, imports { JsonNull })
            assertEquals(0 to null, call.run())
            assertTrue(call.allocated.isEmpty())
        }
    }

    @Test
    fun `an exception is reported with its full message`() {
        val message = "boom-".repeat(50_000)
        Arena.ofConfined().use { arena ->
            val (status, response) = Call(arena, imports { throw IllegalStateException(message) }).run()
            assertEquals(1, status)
            assertEquals(message, response)
        }
    }
}
