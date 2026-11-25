@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasm2kt.runtime.ComponentBackendFactory
import wasm2kt.runtime.ComponentTrapException
import wasm2kt.runtime.WitWriter
import wasm2kt.runtime.aot.LinearMemory
import wasm2kt.runtime.canonical.AbiContext
import wasm2kt.runtime.canonical.CanonicalMemory
import wasm2kt.runtime.canonical.InternalWasm2KtApi
import wasm2kt.runtime.canonical.Lifter
import wasm2kt.runtime.canonical.StringEncoding
import wasm2kt.runtime.canonical.WitType
import wasm2kt.testing.JsonBackend
import wasm2kt.testing.JsonHostImports
import wasm2kt.testing.PlanTypes
import wasm2kt.tooling.ComponentCodegen
import wasm2kt.tooling.Decomposer
import wasm2kt.tooling.aot.AotComponentCodegen
import wasm2kt.tooling.aot.AotComponentCompiler
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Canonical ABI validation and synchronous resource lifetime strictness tests running on the
 * Phase 1 AOT backend (`AotComponentBackend` + generated static component wiring).
 */
class AotCanonicalAbiStrictnessTest {
    private val noop: JsonImports = { _, _ -> JsonNull }

    private fun u(vararg v: Long) = JsonArray(v.map { JsonPrimitive(it) })

    private fun j(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun openBackend(name: String, imports: JsonImports? = null): JsonBackend {
        val decomposed = Paths.get("runtime-chicory/src/test/resources/components/$name").toAbsolutePath()
        val types = PlanTypes.fromDirectory(decomposed)
        val factory = compiledFactory(name, decomposed)
        return JsonBackend(factory.instantiate(imports?.let { JsonHostImports(types, it) }), types)
    }

    @Test
    fun `host values must have the JSON type of their WIT type`() {
        openBackend("types").use { c ->
            for (bad in listOf("""{"name":null,"data":[],"tag":null}""", """{"name":5,"data":[],"tag":null}""")) {
                assertThrows<IllegalArgumentException> { c.invoke("echo-blob", JsonArray(listOf(j(bad)))) }
            }
            assertThrows<IllegalArgumentException> { c.invoke("echo-char", JsonArray(listOf(JsonPrimitive(65)))) }
            assertThrows<IllegalArgumentException> { c.invoke("echo-name", JsonArray(listOf(JsonPrimitive(true)))) }
            assertEquals(
                "ok",
                c.invoke("echo-name", JsonArray(listOf(JsonPrimitive("ok")))).let { r ->
                    (r as? JsonObject)?.get("value")?.jsonPrimitive?.content ?: r.jsonPrimitive.content
                },
            )
        }
    }

    @Test
    fun `a trap found while lifting a result poisons the instance`() {
        openBackend("abi-edge", noop).use { c ->
            assertThrows<ComponentTrapException> { c.invoke("char-from", u(0xD800)) }
            val after = assertThrows<ComponentTrapException> { c.invoke("char-from", u(0x41)) }
            assertTrue("unusable" in after.message!!)
        }
    }

    @Test
    fun `a host import may not re-enter the instance`() {
        lateinit var backend: JsonBackend
        var inner: Throwable? = null
        val reentering: JsonImports = { _, _ ->
            inner = runCatching { backend.invoke("char-from", u(0x41)) }.exceptionOrNull()
            JsonNull
        }
        openBackend("abi-edge", reentering).use { c ->
            backend = c
            c.invoke("call-ping", JsonArray(emptyList()))
            assertTrue(inner is ComponentTrapException, "re-entry must trap, got $inner")
            assertTrue("reenter" in inner!!.message!!)
            val after = assertThrows<ComponentTrapException> { c.invoke("char-from", u(0x41)) }
            assertTrue("unusable" in after.message!!)
        }
    }

    @Test
    fun `an exception from a host import poisons the instance and keeps its type`() {
        val failing: JsonImports = { _, _ -> throw UnsupportedOperationException("boom") }
        openBackend("abi-edge", failing).use { c ->
            val e = assertThrows<RuntimeException> { c.invoke("call-ping", JsonArray(emptyList())) }
            assertTrue(generateSequence(e as Throwable) { it.cause }.any { it is UnsupportedOperationException }, "lost the host's exception: $e")
            assertThrows<ComponentTrapException> { c.invoke("char-from", u(0x41)) }
        }
    }

    @Test
    fun `host values that do not fit the WIT type are rejected without poisoning`() {
        openBackend("abi-edge", noop).use { c ->
            for (bad in listOf("-1", "4294967296", "1.5", "true", "[1]", "null")) {
                assertThrows<IllegalArgumentException>("u32 <- $bad") { c.invoke("char-from", JsonArray(listOf(j(bad)))) }
            }
            assertThrows<IllegalArgumentException> { c.invoke("list-len", JsonArray(listOf(j("[4294967296]")))) }
            assertEquals(0x41, c.invoke("char-from", u(0x41)).jsonPrimitive.content.codePointAt(0))
            assertEquals(2, c.invoke("list-len", JsonArray(listOf(j("[0, 4294967295]")))).jsonPrimitive.int)
        }
        openBackend("types").use { c ->
            for (bad in listOf("\"\"", "\"ab\"", "\"\\ud800\"", "65")) {
                assertThrows<IllegalArgumentException>("char <- $bad") { c.invoke("echo-char", JsonArray(listOf(j(bad)))) }
            }
            assertThrows<IllegalArgumentException> { c.invoke("echo-name", JsonArray(listOf(j("\"a\\udc00\"")))) }
            for (bad in listOf("256", "-1")) {
                assertThrows<IllegalArgumentException>("u8 <- $bad") { c.invoke("echo-bytes", JsonArray(listOf(j("[$bad]")))) }
            }
            assertEquals("🎌", c.invoke("echo-char", JsonArray(listOf(JsonPrimitive("🎌")))).jsonPrimitive.content)
        }
    }

    @Test
    fun `composed sync-call resource transfer and borrowed handle lending are strictly enforced`() {
        val composedDecomp = workRoot.resolve("composed-res-decomposed")
        if (!Files.exists(composedDecomp.resolve("plan.json"))) {
            val wasm = Paths.get(requireNotNull(javaClass.getResource("/fixtures/composed-res.wasm")).toURI())
            Decomposer.decompose(wasm, composedDecomp)
        }
        val composedTypes = PlanTypes.fromDirectory(composedDecomp)
        val composedFactory = compiledFactory("composed-res", composedDecomp)
        JsonBackend(composedFactory.instantiate(null), composedTypes).use { c ->
            assertEquals(1, c.invoke("g", JsonArray(emptyList())).jsonPrimitive.int)
            assertEquals(1, c.invoke("g", JsonArray(emptyList())).jsonPrimitive.int)
        }

        // Borrowing a component-defined resource during a host import callback prevents dropping it mid-call.
        lateinit var backend: JsonBackend
        var counterHandle = -1
        var dropDuringBorrowError: Throwable? = null
        val hostImports: JsonImports = { spec, _ ->
            when {
                spec.endsWith("#log") -> {
                    dropDuringBorrowError = runCatching { backend.dropResource(counterHandle) }.exceptionOrNull()
                    JsonNull
                }
                else -> JsonNull
            }
        }
        openBackend("host-res", hostImports).use { b ->
            backend = b
            counterHandle = b.invoke("example:host-res/guest-api@0.1.0#[constructor]counter", JsonArray(listOf(JsonPrimitive(5)))).jsonPrimitive.int
            b.invoke("example:host-res/guest-api@0.1.0#[method]counter.poke", JsonArray(listOf(JsonPrimitive(counterHandle))))
            assertTrue(dropDuringBorrowError is IllegalStateException, "Expected IllegalStateException dropping lent resource, got $dropDuringBorrowError")
            assertEquals(6, b.invoke("example:host-res/guest-api@0.1.0#[method]counter.value", JsonArray(listOf(JsonPrimitive(counterHandle)))).jsonPrimitive.int)
            b.dropResource(counterHandle)
        }
    }

    @Test
    fun `LinearMemory lifting u8 and u32 lists and utf8 strings matches payload across bulk copy boundaries`() {
        val mem = LinearMemory(initialPages = 4, maxPages = 4)
        val cx = object : AbiContext {
            override val memory: CanonicalMemory = mem
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
            mem.writeInt(0, dataPtr)
            mem.writeInt(4, len)
            if (len > 0) mem.writeBytes(dataPtr, payload)

            val listWriter = WitWriter()
            Lifter(cx, listWriter).load(WitType.ListOf(WitType.U8), 0)
            assertArrayEquals(payload, listWriter.toReader().bytes())

            val strBytes = ByteArray(len) { i -> ('a'.code + (i % 26)).toByte() }
            if (len > 0) mem.writeBytes(dataPtr, strBytes)
            val strWriter = WitWriter()
            Lifter(cx, strWriter).load(WitType.Str, 0)
            assertEquals(strBytes.decodeToString(), strWriter.toReader().string())
        }

        // Raw integer list of multi-byte elements (list<u32>, 100 elements = 400 bytes >= 256).
        val u32Ptr = 12
        val u32Count = 100
        for (i in 0 until u32Count) {
            mem.writeInt(u32Ptr + i * 4, i * 17 - 3)
        }
        mem.writeInt(0, u32Ptr)
        mem.writeInt(4, u32Count)
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
        mem.writeInt(0, 12)
        mem.writeInt(4, badUtf8.size)
        mem.writeBytes(12, badUtf8)
        assertThrows<ComponentTrapException> { Lifter(cx, WitWriter()).load(WitType.Str, 0) }

        // Out-of-bounds bulk read traps with ComponentTrapException.
        mem.writeInt(0, 4 * 65536 - 100)
        mem.writeInt(4, 300)
        assertThrows<ComponentTrapException> { Lifter(cx, WitWriter()).load(WitType.ListOf(WitType.U8), 0) }
    }

    private companion object {
        val workRoot: Path = Paths.get("integration-tests/build/aot-strictness").toAbsolutePath()
        val factoryCache = ConcurrentHashMap<String, ComponentBackendFactory>()

        fun compiledFactory(name: String, decomposedDir: Path): ComponentBackendFactory =
            factoryCache.computeIfAbsent(name) {
                val base = workRoot.resolve(name)
                val classesDir = base.resolve("classes")
                val resourcesDir = base.resolve("resources")
                val kotlinDir = base.resolve("kotlin")
                val compiledKtDir = base.resolve("compiled-kotlin")
                base.toFile().deleteRecursively()

                val pkg = "wasm2kt.aot.strict." + name.lowercase().replace(Regex("[^a-z0-9]+"), "_")
                val internalPkg = "$pkg.wasm2kt"
                val compiled = AotComponentCompiler.compile(decomposedDir, classesDir, resourcesDir, internalPkg)
                AotComponentCodegen.generateComponentOnly(kotlinDir, name, pkg, compiled, internalPkg)

                val sources = Files.walk(kotlinDir).use { s ->
                    s.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.map { it.toString() }.toList()
                }
                Files.createDirectories(compiledKtDir)
                val cp = listOf(System.getProperty("java.class.path"), classesDir.toString()).joinToString(File.pathSeparator)
                val exit = K2JVMCompiler().exec(
                    System.err,
                    "-Xcontext-parameters", "-jvm-target", "17", "-no-stdlib",
                    "-classpath", cp, "-d", compiledKtDir.toString(),
                    *sources.toTypedArray(),
                )
                assertEquals(ExitCode.OK, exit, "Generated AOT strictness component '$name' must compile")

                val urls = arrayOf(
                    compiledKtDir.toUri().toURL(),
                    classesDir.toUri().toURL(),
                    resourcesDir.toUri().toURL(),
                )
                val loader = URLClassLoader(urls, AotCanonicalAbiStrictnessTest::class.java.classLoader)
                val factoryClassName = "$pkg.${ComponentCodegen.componentObjectName(name)}"
                loader.loadClass(factoryClassName).getField("INSTANCE").get(null) as ComponentBackendFactory
            }
    }
}
