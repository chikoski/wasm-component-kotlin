package wasm2kt.bench

import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.Machine
import com.dylibso.chicory.wasm.Parser
import wasm2kt.runtime.WitWriter
import wasm2kt.runtime.chicory.ChicoryComponent
import java.nio.file.Files
import kotlin.random.Random

/**
 * Steady-state cost of one call, per backend and per layer. Backends:
 * * `chicory-aot`: the shipped path (core modules compiled at build time, generated `<Name>Component`)
 * * `chicory-interp`: the same plan on Chicory's interpreter
 * * `wasmtime-ffm` / `wasmtime-jni`: the native reference backend
 * Layers (Chicory AOT): `core` is a bare core-wasm call, `backend` is `ComponentBackend.invoke` with
 * arguments already written in the binary format (Canonical ABI only, ADR-0015), `dsl` is the generated
 * Kotlin function.
 */
fun main(@Suppress("UNUSED_PARAMETER") args: Array<String>) {
    println(environment())
    val h = Harness()
    val backends = listOf("aot", "aot-p1", "chicory-aot", "chicory-interp", "wasmtime-ffm", "wasmtime-jni")

    // ---- arithmetic: per-call overhead with almost no work or data
    run {
        val s = bench.arithmetic_aot.openSession(bench.arithmetic_aot.ArithmeticAotComponent)
        context(s) { h.run("call", "add(s32,s32)", "aot/dsl") { bench.arithmetic_aot.add(1, 2) } }
        s.close()
    }
    for (b in backends.drop(1)) {
        val s = openArithmetic(b)
        context(s) { h.run("call", "add(s32,s32)", "$b/dsl") { bench.arithmetic.add(1, 2) } }
        s.close()
    }
    run {
        val aotBackend = bench.arithmetic.ArithmeticAotP1Component.instantiate(null)
        val args = WitWriter().apply { int(1); int(2) }
        val aotAdd = aotBackend.resolve("add")
        h.run("call", "add(s32,s32)", "aot-p1/backend") { aotBackend.invoke(aotAdd, args.toReader()) }
        aotBackend.close()
        val backend = bench.arithmetic.ArithmeticComponent.instantiate(null)
        val add = backend.resolve("add")
        h.run("call", "add(s32,s32)", "chicory-aot/backend") { backend.invoke(add, args.toReader()) }
        backend.close()
        val interp = ChicoryComponent.fromDirectory(BenchPaths.decomposed("arithmetic")).instantiate(null)
        val interpAdd = interp.resolve("add")
        h.run("call", "add(s32,s32)", "chicory-interp/backend") { interp.invoke(interpAdd, args.toReader()) }
        interp.close()
        val aot = coreAdd(compiled = true)
        h.run("call", "add(s32,s32)", "chicory-aot/core") { aot.apply(1, 2) }
        val core = coreAdd(compiled = false)
        h.run("call", "add(s32,s32)", "chicory-interp/core") { core.apply(1, 2) }
    }

    // ---- types: strings, bytes and records across the boundary
    val sizes = listOf(16, 1024, 65536)
    val payloads = sizes.associateWith { Random(it).nextBytes(it) }
    val blobAot = bench.types_aot.Blob(name = "sample", data = Random(7).nextBytes(1024), tag = 7u)
    run {
        val s = bench.types_aot.openSession(bench.types_aot.TypesAotComponent)
        context(s) {
            h.run("data", "echo-name(string 5)", "aot/dsl") { bench.types_aot.echoName("hello") }
            for (n in sizes) h.run("data", "echo-bytes(list<u8> $n)", "aot/dsl") { bench.types_aot.echoBytes(payloads.getValue(n)) }
            h.run("data", "echo-blob(record, 1 KiB)", "aot/dsl") { bench.types_aot.echoBlob(blobAot) }
        }
        s.close()
    }
    val blob = bench.types.Blob(name = "sample", data = Random(7).nextBytes(1024), tag = 7u)
    for (b in backends.drop(1)) {
        val s = openTypes(b)
        context(s) {
            h.run("data", "echo-name(string 5)", "$b/dsl") { bench.types.echoName("hello") }
            for (n in sizes) h.run("data", "echo-bytes(list<u8> $n)", "$b/dsl") { bench.types.echoBytes(payloads.getValue(n)) }
            h.run("data", "echo-blob(record, 1 KiB)", "$b/dsl") { bench.types.echoBlob(blob) }
        }
        s.close()
    }
    run {
        val aotBackend = bench.types.TypesAotP1Component.instantiate(null)
        val aotEchoBytes = aotBackend.resolve("echo-bytes")
        for (n in sizes) {
            val args = WitWriter().apply { bytes(payloads.getValue(n)) }
            h.run("data", "echo-bytes(list<u8> $n)", "aot-p1/backend") { aotBackend.invoke(aotEchoBytes, args.toReader()) }
        }
        aotBackend.close()
        val backend = bench.types.TypesComponent.instantiate(null)
        val echoBytes = backend.resolve("echo-bytes")
        for (n in sizes) {
            val args = WitWriter().apply { bytes(payloads.getValue(n)) }
            h.run("data", "echo-bytes(list<u8> $n)", "chicory-aot/backend") { backend.invoke(echoBytes, args.toReader()) }
        }
        backend.close()
        // The value layer alone: what the generated code does around each call.
        for (n in sizes) {
            val bytes = payloads.getValue(n)
            h.run("codec", "list<u8> $n", "wire") { WitWriter().apply { bytes(bytes) }.toReader().bytes() }
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
    run {
        val s = bench.heavy_aot.openSession(bench.heavy_aot.HeavyAotComponent)
        context(s) {
            h.run("compute", "lcg-sum(n=1e6)", "aot/dsl") { bench.heavy_aot.lcgSum(1u, 1_000_000u) }
            h.run("compute", "regex-count(16 B)", "aot/dsl") { bench.heavy_aot.regexCount("[a-z]+ing", "running walking ") }
            h.run("compute", "regex-count(${text.length / 1024} KiB)", "aot/dsl") { bench.heavy_aot.regexCount("[a-z]+ing", text) }
            h.run("compute", "json-roundtrip(${json.length / 1024} KiB)", "aot/dsl") { bench.heavy_aot.jsonRoundtrip(json) }
            h.run("compute", "sort-bytes(64 KiB)", "aot/dsl") { bench.heavy_aot.sortBytes(sortInput) }
        }
        s.close()
    }
    for (b in backends.drop(1)) {
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

    h.write(System.getProperty("wasm2kt.bench.outFile") ?: "calls.tsv")
}

private fun wasmtime(b: String, jni: () -> Unit, ffm: () -> Unit) { if (b == "wasmtime-jni") jni() else ffm() }

internal fun openArithmetic(b: String): bench.arithmetic.Session = when (b) {
    "aot-p1" -> bench.arithmetic.openSession(bench.arithmetic.ArithmeticAotP1Component)
    "chicory-aot" -> bench.arithmetic.openSession(bench.arithmetic.ArithmeticComponent)
    "chicory-interp" -> bench.arithmetic.openSession(ChicoryComponent.fromDirectory(BenchPaths.decomposed("arithmetic")))
    else -> {
        wasmtime(b, { bench.arithmetic.WasmtimeSession.useJniBackend() }, { bench.arithmetic.WasmtimeSession.useFfmBackend() })
        bench.arithmetic.openSession(BenchPaths.fixture("arithmetic"))
    }
}

internal fun openTypes(b: String): bench.types.Session = when (b) {
    "aot-p1" -> bench.types.openSession(bench.types.TypesAotP1Component)
    "chicory-aot" -> bench.types.openSession(bench.types.TypesComponent)
    "chicory-interp" -> bench.types.openSession(ChicoryComponent.fromDirectory(BenchPaths.decomposed("types")))
    else -> {
        wasmtime(b, { bench.types.WasmtimeSession.useJniBackend() }, { bench.types.WasmtimeSession.useFfmBackend() })
        bench.types.openSession(BenchPaths.fixture("types"))
    }
}

internal fun openHeavy(b: String): bench.heavy.Session = when (b) {
    "aot-p1" -> bench.heavy.openSession(bench.heavy.HeavyAotP1Component)
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
