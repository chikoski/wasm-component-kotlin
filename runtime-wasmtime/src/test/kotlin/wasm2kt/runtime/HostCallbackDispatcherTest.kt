package wasm2kt.runtime

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
    private class Call(arena: Arena, imports: WitHostImportDispatcher, args: ByteArray = ByteArray(0)) {
        val allocated = mutableListOf<MemorySegment>()
        private val dispatcher = HostCallbackDispatcher(imports) { bytes, size ->
            arena.allocate(size.toLong()).also {
                MemorySegment.copy(bytes, 0, it, ValueLayout.JAVA_BYTE, 0L, size)
                allocated += it
            }
        }
        private val keyPtr = FfmWasmtimeBridge.allocateUtf8CString(arena, "env-provider#format-tag")
        private val argsPtr = FfmWasmtimeBridge.allocateBytes(arena, args)
        private val argsLen = args.size.toLong()
        private val outPtr = arena.allocate(ValueLayout.ADDRESS).also { it.set(ValueLayout.ADDRESS, 0L, MemorySegment.NULL) }
        private val outLen = arena.allocate(ValueLayout.JAVA_LONG).also { it.set(ValueLayout.JAVA_LONG, 0L, 0L) }

        fun run(index: Int = 0): Pair<Int, ByteArray?> {
            outPtr.set(ValueLayout.ADDRESS, 0L, MemorySegment.NULL)
            val status = dispatcher.onHostCall(index, keyPtr, argsPtr, argsLen, outPtr, outLen)
            val ptr = outPtr.get(ValueLayout.ADDRESS, 0L)
            if (ptr == MemorySegment.NULL) return status to null
            val len = outLen.get(ValueLayout.JAVA_LONG, 0L).toInt()
            val bytes = ByteArray(len)
            MemorySegment.copy(ptr.reinterpret(len.toLong()), ValueLayout.JAVA_BYTE, 0L, bytes, 0, len)
            return status to bytes
        }
    }

    private fun imports(function: HostFunction) = Linker().apply { define("env-provider#format-tag", function) }

    @Test
    fun `responses of any size are handed back whole`() {
        val text = "日本語-".repeat(100_000) // 1 MB of UTF-8, far past the old 64 KiB buffer
        Arena.ofConfined().use { arena ->
            val call = Call(arena, imports { _, results -> results.string(text) })
            val (status, response) = call.run()
            assertEquals(0, status)
            assertEquals(text, WitReader(response!!).string())
            assertEquals(1, call.allocated.size)
        }
    }

    @Test
    fun `arguments are passed to the host function`() {
        Arena.ofConfined().use { arena ->
            val args = WitWriter().apply { int(40); string("x") }.toByteArray()
            val call = Call(arena, imports { a, results -> results.string("${a.int() + 2}${a.string()}") }, args)
            assertEquals("42x", WitReader(call.run().second!!).string())
        }
    }

    @Test
    fun `no result leaves the response empty`() {
        Arena.ofConfined().use { arena ->
            val call = Call(arena, imports { _, _ -> })
            assertEquals(0, call.run().first)
            assertTrue(call.allocated.isEmpty())
        }
    }

    @Test
    fun `an exception is reported with its full message`() {
        val message = "boom-".repeat(50_000)
        Arena.ofConfined().use { arena ->
            val (status, response) = Call(arena, imports { _, _ -> throw IllegalStateException(message) }).run()
            assertEquals(1, status)
            assertEquals(message, String(response!!, StandardCharsets.UTF_8))
        }
    }

    @Test
    fun `an unknown import is an error naming its key`() {
        Arena.ofConfined().use { arena ->
            val (status, response) = Call(arena, Linker()).run()
            assertEquals(1, status)
            assertEquals("Unknown host import: env-provider#format-tag", String(response!!, StandardCharsets.UTF_8))
        }
    }
}
