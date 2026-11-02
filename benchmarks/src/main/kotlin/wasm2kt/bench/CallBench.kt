package wasm2kt.bench

import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.Machine
import com.dylibso.chicory.wasm.Parser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import wasm2kt.runtime.FfmJsonCodec
import wasm2kt.runtime.chicory.ChicoryComponent
import java.nio.file.Files
import kotlin.random.Random

/**
 * Steady-state cost of one call, per backend and per layer. Backends:
 * * `chicory-aot`: the shipped path (core modules compiled at build time, generated `<Name>Component`)
 * * `chicory-interp`: the same plan on Chicory's interpreter
 * * `wasmtime-ffm` / `wasmtime-jni`: the native reference backend
 * Layers (Chicory AOT): `core` is a bare core-wasm call, `backend` is `ComponentBackend.invoke` with a
 * prepared JSON tree (Canonical ABI + JSON values), `dsl` is the generated Kotlin function.
 */
fun main(@Suppress("UNUSED_PARAMETER") args: Array<String>) {
    println(environment())
    val h = Harness()
    val backends = listOf("chicory-aot", "chicory-interp", "wasmtime-ffm", "wasmtime-jni")

    // ---- arithmetic: per-call overhead with almost no work or data
    for (b in backends) {
        val s = openArithmetic(b)
        context(s) { h.run("call", "add(s32,s32)", "$b/dsl") { bench.arithmetic.add(1, 2) } }
        s.close()
    }
    run {
        val backend = bench.arithmetic.ArithmeticComponent.instantiate(null)
        val args = JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2)))
        h.run("call", "add(s32,s32)", "chicory-aot/backend") { backend.invoke("add", args) }
        backend.close()
        val interp = ChicoryComponent.fromDirectory(BenchPaths.decomposed("arithmetic")).instantiate(null)
        h.run("call", "add(s32,s32)", "chicory-interp/backend") { interp.invoke("add", args) }
        interp.close()
        val aot = coreAdd(compiled = true)
        h.run("call", "add(s32,s32)", "chicory-aot/core") { aot.apply(1, 2) }
        val core = coreAdd(compiled = false)
        h.run("call", "add(s32,s32)", "chicory-interp/core") { core.apply(1, 2) }
    }

    // ---- types: strings, bytes and records across the boundary
    val sizes = listOf(16, 1024, 65536)
    val payloads = sizes.associateWith { Random(it).nextBytes(it) }
    val blob = bench.types.Blob(name = "sample", data = Random(7).nextBytes(1024), tag = 7u)
    for (b in backends) {
        val s = openTypes(b)
        context(s) {
            h.run("data", "echo-name(string 5)", "$b/dsl") { bench.types.echoName("hello") }
            for (n in sizes) h.run("data", "echo-bytes(list<u8> $n)", "$b/dsl") { bench.types.echoBytes(payloads.getValue(n)) }
            h.run("data", "echo-blob(record, 1 KiB)", "$b/dsl") { bench.types.echoBlob(blob) }
        }
        s.close()
    }
    run {
        val backend = bench.types.TypesComponent.instantiate(null)
        for (n in sizes) {
            val args = JsonArray(listOf(FfmJsonCodec.toJsonElement(payloads.getValue(n))))
            h.run("data", "echo-bytes(list<u8> $n)", "chicory-aot/backend") { backend.invoke("echo-bytes", args) }
        }
        backend.close()
        // The JSON value layer alone: what the generated code does around each call.
        for (n in sizes) {
            val bytes = payloads.getValue(n)
            h.run("codec", "list<u8> $n", "json-tree") { FfmJsonCodec.decodeByteArray(FfmJsonCodec.toJsonElement(bytes)) }
            // The Wasmtime bridge also turns the tree into a string and back on each side.
            h.run("codec", "list<u8> $n", "json-tree+string") {
                val text = Json.encodeToString(JsonElement.serializer(), FfmJsonCodec.toJsonElement(bytes))
                FfmJsonCodec.decodeByteArray(Json.parseToJsonElement(text))
            }
        }
    }

    // ---- heavy: real code (Rust std, regex, serde_json) where execution dominates
    val text = buildString { val r = Random(3); repeat(2000) { append(if (r.nextInt(4) == 0) "running " else "walked ") } }
    val json = buildString {
        append('[')
        repeat(200) { i -> if (i > 0) append(','); append("""{"id":$i,"name":"item-$i","tags":["a","b"],"score":${i * 0.5},"ok":${i % 2 == 0}}""") }
        append(']')
    }
    val sortInput = Random(11).nextBytes(65536)
    for (b in backends) {
        val s = openHeavy(b)
        context(s) {
            h.run("compute", "lcg-sum(n=1e6)", "$b/dsl") { bench.heavy.lcgSum(1u, 1_000_000u) }
            // The pattern is compiled on every call: the short text shows that part alone.
            h.run("compute", "regex-count(16 B)", "$b/dsl") { bench.heavy.regexCount("[a-z]+ing", "running walking ") }
            h.run("compute", "regex-count(${text.length / 1024} KiB)", "$b/dsl") { bench.heavy.regexCount("[a-z]+ing", text) }
            h.run("compute", "json-roundtrip(${json.length / 1024} KiB)", "$b/dsl") { bench.heavy.jsonRoundtrip(json) }
            h.run("compute", "sort-bytes(64 KiB)", "$b/dsl") { bench.heavy.sortBytes(sortInput) }
        }
        s.close()
    }

    h.write("calls.tsv")
}

private fun wasmtime(b: String, jni: () -> Unit, ffm: () -> Unit) { if (b == "wasmtime-jni") jni() else ffm() }

internal fun openArithmetic(b: String): bench.arithmetic.Session = when (b) {
    "chicory-aot" -> bench.arithmetic.openSession(bench.arithmetic.ArithmeticComponent)
    "chicory-interp" -> bench.arithmetic.openSession(ChicoryComponent.fromDirectory(BenchPaths.decomposed("arithmetic")))
    else -> {
        wasmtime(b, { bench.arithmetic.WasmtimeSession.useJniBackend() }, { bench.arithmetic.WasmtimeSession.useFfmBackend() })
        bench.arithmetic.openSession(BenchPaths.fixture("arithmetic"))
    }
}

internal fun openTypes(b: String): bench.types.Session = when (b) {
    "chicory-aot" -> bench.types.openSession(bench.types.TypesComponent)
    "chicory-interp" -> bench.types.openSession(ChicoryComponent.fromDirectory(BenchPaths.decomposed("types")))
    else -> {
        wasmtime(b, { bench.types.WasmtimeSession.useJniBackend() }, { bench.types.WasmtimeSession.useFfmBackend() })
        bench.types.openSession(BenchPaths.fixture("types"))
    }
}

internal fun openHeavy(b: String): bench.heavy.Session = when (b) {
    "chicory-aot" -> bench.heavy.openSession(bench.heavy.HeavyComponent)
    "chicory-interp" -> bench.heavy.openSession(ChicoryComponent.fromDirectory(BenchPaths.decomposed("heavy")))
    else -> {
        wasmtime(b, { bench.heavy.WasmtimeSession.useJniBackend() }, { bench.heavy.WasmtimeSession.useFfmBackend() })
        bench.heavy.openSession(BenchPaths.fixture("heavy"))
    }
}

/** A bare core-wasm `add`: no component layer at all. */
private fun coreAdd(compiled: Boolean): com.dylibso.chicory.runtime.ExportFunction {
    val instance = if (compiled) {
        val meta = requireNotNull(Harness::class.java.getResourceAsStream("/bench/arithmetic/wasm2kt/arithmetic/Core0.meta")).readBytes()
        val machine = Class.forName("bench.arithmetic.wasm2kt.arithmetic.Core0Machine").getConstructor(Instance::class.java)
        Instance.builder(Parser.parse(meta)).withMachineFactory { machine.newInstance(it) as Machine }.build()
    } else {
        Instance.builder(Parser.parse(Files.readAllBytes(BenchPaths.decomposed("arithmetic").resolve("core0.wasm")))).build()
    }
    return instance.export("add")
}
