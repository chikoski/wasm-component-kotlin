package wasm2kt.runtime.chicory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasm2kt.runtime.ComponentBackend
import wasm2kt.runtime.ComponentTrapException
import wasm2kt.runtime.WitHostImportDispatcher
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Canonical ABI rules (#18) that the differential tests against Wasmtime cannot cover, either
 * because Wasmtime deviates from the spec or because they need the host to misbehave mid-call.
 * The cases Wasmtime agrees on live in integration-tests' ChicoryDifferentialTest.
 */
class CanonicalAbiStrictnessTest {
    private fun component(name: String): ChicoryComponent =
        ChicoryComponent.fromDirectory(Paths.get(requireNotNull(javaClass.getResource("/components/$name/plan.json")).toURI()).parent)

    private fun u(vararg v: Long) = JsonArray(v.map { JsonPrimitive(it) })

    private fun j(text: String): JsonElement = Json.parseToJsonElement(text)

    private val noop = object : WitHostImportDispatcher {
        override fun dispatchHostImport(importSpec: String, args: JsonArray): JsonElement = JsonNull
    }

    @Test
    fun `host values must have the JSON type of their WIT type`() {
        // #36: null or a number for a string used to be stored as its text ("null", "5").
        component("types").instantiate(null).use { c ->
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
        component("abi-edge").instantiate(noop).use { c ->
            assertThrows<ComponentTrapException> { c.invoke("char-from", u(0xD800)) }
            val after = assertThrows<ComponentTrapException> { c.invoke("char-from", u(0x41)) }
            assertTrue("unusable" in after.message!!)
        }
    }

    @Test
    fun `a host import may not re-enter the instance`() {
        lateinit var backend: ComponentBackend
        var inner: Throwable? = null
        val reentering = object : WitHostImportDispatcher {
            override fun dispatchHostImport(importSpec: String, args: JsonArray): JsonElement {
                inner = runCatching { backend.invoke("char-from", u(0x41)) }.exceptionOrNull()
                return JsonNull
            }
        }
        component("abi-edge").instantiate(reentering).use { c ->
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
        val failing = object : WitHostImportDispatcher {
            override fun dispatchHostImport(importSpec: String, args: JsonArray): JsonElement = throw UnsupportedOperationException("boom")
        }
        component("abi-edge").instantiate(failing).use { c ->
            val e = assertThrows<RuntimeException> { c.invoke("call-ping", JsonArray(emptyList())) }
            assertTrue(generateSequence(e as Throwable) { it.cause }.any { it is UnsupportedOperationException }, "lost the host's exception: $e")
            assertThrows<ComponentTrapException> { c.invoke("char-from", u(0x41)) }
        }
    }

    @Test
    fun `host values that do not fit the WIT type are rejected without poisoning`() {
        // The Wasmtime bridge rejects these too since #34; ChicoryDifferentialTest compares a few.
        // This checks the exception type and that the instance stays usable.
        component("abi-edge").instantiate(noop).use { c ->
            for (bad in listOf("-1", "4294967296", "1.5", "true", "[1]", "null")) {
                assertThrows<IllegalArgumentException>("u32 <- $bad") { c.invoke("char-from", JsonArray(listOf(j(bad)))) }
            }
            assertThrows<IllegalArgumentException> { c.invoke("list-len", JsonArray(listOf(j("[4294967296]")))) }
            assertEquals(0x41, c.invoke("char-from", u(0x41)).jsonPrimitive.content.codePointAt(0))
            assertEquals(2, c.invoke("list-len", JsonArray(listOf(j("[0, 4294967295]")))).jsonPrimitive.int)
        }
        component("types").instantiate(null).use { c ->
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
}
