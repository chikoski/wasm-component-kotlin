package wasm2kt.runtime.chicory

import com.dylibso.chicory.runtime.ByteArrayMemory
import com.dylibso.chicory.runtime.Memory
import com.dylibso.chicory.wasm.types.MemoryLimits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasm2kt.runtime.ComponentTrapException
import wasm2kt.runtime.WitWriter
import wasm2kt.testing.JsonBackend
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Canonical ABI rules (#18) that the differential tests against Wasmtime cannot cover, either
 * because Wasmtime deviates from the spec or because they need the host to misbehave mid-call.
 * The cases Wasmtime agrees on live in integration-tests' ChicoryDifferentialTest.
 */
class CanonicalAbiStrictnessTest {
    private fun component(name: String) = TestComponent(name)

    private fun u(vararg v: Long) = JsonArray(v.map { JsonPrimitive(it) })

    private fun j(text: String): JsonElement = Json.parseToJsonElement(text)

    private val noop: (String, JsonArray) -> JsonElement = { _, _ -> JsonNull }

    @Test
    fun `host values must have the JSON type of their WIT type`() {
        // #36: null or a number for a string used to be stored as its text ("null", "5").
        component("types").open().use { c ->
            for (bad in listOf("""{"name":null,"data":[],"tag":null}""", """{"name":5,"data":[],"tag":null}""")) {
                assertThrows<IllegalArgumentException> { c.invoke("echo-blob", JsonArray(listOf(j(bad)))) }
            }
            assertThrows<IllegalArgumentException> { c.invoke("echo-char", JsonArray(listOf(JsonPrimitive(65)))) }
            assertThrows<IllegalArgumentException> { c.invoke("echo-name", JsonArray(listOf(JsonPrimitive(true)))) }
            // Host errors do not poison the instance.
            assertEquals("ok", c.invoke("echo-name", JsonArray(listOf(JsonPrimitive("ok")))).let { r ->
                (r as? kotlinx.serialization.json.JsonObject)?.get("value")?.jsonPrimitive?.content ?: r.jsonPrimitive.content
            })
        }
    }

    @Test
    fun `a trap found while lifting a result poisons the instance`() {
        // The spec's trap_if in lift is a trap like any other. Wasmtime 48 reports it but keeps the
        // instance usable (it only poisons after core wasm traps), so this is checked here only.
        component("abi-edge").open(noop).use { c ->
            assertThrows<ComponentTrapException> { c.invoke("char-from", u(0xD800)) }
            val after = assertThrows<ComponentTrapException> { c.invoke("char-from", u(0x41)) }
            assertTrue("unusable" in after.message!!)
        }
    }

    @Test
    fun `a host import may not re-enter the instance`() {
        lateinit var backend: JsonBackend
        var inner: Throwable? = null
        val reentering: (String, JsonArray) -> JsonElement = { _, _ ->
            inner = runCatching { backend.invoke("char-from", u(0x41)) }.exceptionOrNull()
            JsonNull
        }
        component("abi-edge").open(reentering).use { c ->
            backend = c
            // The host swallowed the trap, but the instance is still poisoned.
            c.invoke("call-ping", JsonArray(emptyList()))
            assertTrue(inner is ComponentTrapException, "re-entry must trap, got $inner")
            assertTrue("reenter" in inner!!.message!!)
            val after = assertThrows<ComponentTrapException> { c.invoke("char-from", u(0x41)) }
            assertTrue("unusable" in after.message!!)
        }
    }

    @Test
    fun `an exception from a host import poisons the instance and keeps its type`() {
        val failing: (String, JsonArray) -> JsonElement = { _, _ -> throw UnsupportedOperationException("boom") }
        component("abi-edge").open(failing).use { c ->
            val e = assertThrows<RuntimeException> { c.invoke("call-ping", JsonArray(emptyList())) }
            assertTrue(generateSequence(e as Throwable) { it.cause }.any { it is UnsupportedOperationException }, "lost the host's exception: $e")
            assertThrows<ComponentTrapException> { c.invoke("char-from", u(0x41)) }
        }
    }

    @Test
    fun `host values that do not fit the WIT type are rejected without poisoning`() {
        // The Wasmtime bridge rejects these too since #34; ChicoryDifferentialTest compares a few.
        // This checks the exception type and that the instance stays usable.
        component("abi-edge").open(noop).use { c ->
            for (bad in listOf("-1", "4294967296", "1.5", "true", "[1]", "null")) {
                assertThrows<IllegalArgumentException>("u32 <- $bad") { c.invoke("char-from", JsonArray(listOf(j(bad)))) }
            }
            assertThrows<IllegalArgumentException> { c.invoke("list-len", JsonArray(listOf(j("[4294967296]")))) }
            assertEquals(0x41, c.invoke("char-from", u(0x41)).jsonPrimitive.content.codePointAt(0))
            assertEquals(2, c.invoke("list-len", JsonArray(listOf(j("[0, 4294967295]")))).jsonPrimitive.int)
        }
        component("types").open().use { c ->
            for (bad in listOf("\"\"", "\"ab\"", "\"\\ud800\"", "65")) {
                assertThrows<IllegalArgumentException>("char <- $bad") { c.invoke("echo-char", JsonArray(listOf(j(bad)))) }
            }
            // A lone surrogate cannot be encoded into any WIT string.
            assertThrows<IllegalArgumentException> { c.invoke("echo-name", JsonArray(listOf(j("\"a\\udc00\"")))) }
            for (bad in listOf("256", "-1")) {
                assertThrows<IllegalArgumentException>("u8 <- $bad") { c.invoke("echo-bytes", JsonArray(listOf(j("[$bad]")))) }
            }
            assertEquals("🎌", c.invoke("echo-char", JsonArray(listOf(JsonPrimitive("🎌")))).jsonPrimitive.content)
        }
    }

    @Test
    fun `lifting u8 and u32 lists and utf8 strings matches payload across bulk copy boundaries`() {
        val mem = ByteArrayMemory(MemoryLimits(4, 4))
        val cx = object : AbiContext {
            override val memory: Memory = mem
            override val encoding: StringEncoding = StringEncoding.UTF8
            override fun realloc(align: Int, size: Int): Int = error("unused")
            override fun lowerOwn(table: Int, id: Int): Int = error("unused")
            override fun lowerBorrow(table: Int, id: Int): Int = error("unused")
            override fun liftOwn(table: Int, handle: Int): Int = error("unused")
            override fun liftBorrow(table: Int, handle: Int): Int = error("unused")
        }
        val lengths = intArrayOf(0, 7, 8, 255, 256, 257, 65535, 65536, 65537, 131072, 131073, 150_000)
        for (len in lengths) {
            val payload = ByteArray(len) { i -> ((i * 31 + len) and 0xFF).toByte() }
            val dataPtr = 11
            mem.writeI32(0, dataPtr)
            mem.writeI32(4, len)
            if (len > 0) mem.write(dataPtr, payload)

            val listWriter = WitWriter()
            Lifter(cx, listWriter).load(WitType.ListOf(WitType.U8), 0)
            assertArrayEquals(payload, listWriter.toReader().bytes())

            val strBytes = ByteArray(len) { i -> ('a'.code + (i % 26)).toByte() }
            if (len > 0) mem.write(dataPtr, strBytes)
            val strWriter = WitWriter()
            Lifter(cx, strWriter).load(WitType.Str, 0)
            assertEquals(strBytes.decodeToString(), strWriter.toReader().string())
        }

        // Raw integer list of multi-byte elements (list<u32>, 100 elements = 400 bytes >= 256).
        val u32Ptr = 12
        val u32Count = 100
        for (i in 0 until u32Count) {
            mem.writeI32(u32Ptr + i * 4, i * 17 - 3)
        }
        mem.writeI32(0, u32Ptr)
        mem.writeI32(4, u32Count)
        val u32Writer = WitWriter()
        Lifter(cx, u32Writer).load(WitType.ListOf(WitType.U32), 0)
        val u32Reader = u32Writer.toReader()
        assertEquals(u32Count, u32Reader.length())
        for (i in 0 until u32Count) {
            assertEquals(i * 17 - 3, u32Reader.int())
        }

        // Invalid UTF-8 in bulk path traps with ComponentTrapException.
        val badUtf8 = ByteArray(300) { 'a'.code.toByte() }
        badUtf8[280] = 0xFF.toByte()
        mem.writeI32(0, 12)
        mem.writeI32(4, badUtf8.size)
        mem.write(12, badUtf8)
        assertThrows<ComponentTrapException> { Lifter(cx, WitWriter()).load(WitType.Str, 0) }

        // Out-of-bounds bulk read traps with ComponentTrapException before touching readBytes.
        mem.writeI32(0, 4 * 65536 - 100)
        mem.writeI32(4, 300)
        assertThrows<ComponentTrapException> { Lifter(cx, WitWriter()).load(WitType.ListOf(WitType.U8), 0) }
    }
}
