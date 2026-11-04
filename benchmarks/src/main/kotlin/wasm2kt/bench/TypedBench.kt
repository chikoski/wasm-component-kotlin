package wasm2kt.bench

import wasm2kt.ast.WitResolve
import wasm2kt.runtime.FfmWasmtimeBridge
import wasm2kt.runtime.WitWriter
import wasm2kt.tooling.TypedBridge
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.random.Random

/**
 * Wasmtime's typed bridge (ADR-0016) against its generic path. Builds a typed bridge for the
 * benchmark components and loads it as this JVM's only bridge; the generic rows go through the same
 * library with sessions opened without a component ID, so both run the same Wasmtime build.
 * * `wasmtime-ffm` / `wasmtime-jni`: generic (`bench.<name>` bindings)
 * * `wasmtime-typed-ffm` / `wasmtime-typed-jni`: typed (`bench.typed.<name>` bindings, `--bridge-id`)
 * * `.../bridge`: `FfmWasmtimeBridge.invoke` with the arguments already written
 */
fun main(@Suppress("UNUSED_PARAMETER") args: Array<String>) {
    val crate = BenchPaths.gen.resolve("typed-bridge")
    TypedBridge.generateCrate(
        listOf("arithmetic", "types", "heavy").map { TypedBridge.Component(it, WitResolve.parse(Files.readString(BenchPaths.decomposed(it).resolve("wit.json")))) },
        crate,
        libraryName = "wasm2kt_typed_bench",
    )
    val started = System.nanoTime()
    val lib = TypedBridge.cargoBuild(crate, targetDir = Paths.get("rust-bridge/target").toAbsolutePath(), libraryName = "wasm2kt_typed_bench")
    println("typed bridge built in ${(System.nanoTime() - started) / 1_000_000} ms: $lib")
    System.setProperty("wasm2kt.bridge.lib", lib.toString())

    println(environment())
    val h = Harness()
    val flavors = listOf("wasmtime-ffm", "wasmtime-jni", "wasmtime-typed-ffm", "wasmtime-typed-jni")
    fun typed(b: String) = "typed" in b
    fun jni(b: String) = b.endsWith("jni")

    for (b in flavors) {
        if (typed(b)) {
            if (jni(b)) bench.typed.arithmetic.WasmtimeSession.useJniBackend() else bench.typed.arithmetic.WasmtimeSession.useFfmBackend()
            val s = bench.typed.arithmetic.openSession(BenchPaths.fixture("arithmetic"))
            context(s) { h.run("call", "add(s32,s32)", "$b/dsl") { bench.typed.arithmetic.add(1, 2) } }
            s.close()
        } else {
            val s = openArithmetic(b)
            context(s) { h.run("call", "add(s32,s32)", "$b/dsl") { bench.arithmetic.add(1, 2) } }
            s.close()
        }
    }

    val sizes = listOf(16, 1024, 65536)
    val payloads = sizes.associateWith { Random(it).nextBytes(it) }
    for (b in flavors) {
        if (typed(b)) {
            if (jni(b)) bench.typed.types.WasmtimeSession.useJniBackend() else bench.typed.types.WasmtimeSession.useFfmBackend()
            val blob = bench.typed.types.Blob(name = "sample", data = Random(7).nextBytes(1024), tag = 7u)
            val s = bench.typed.types.openSession(BenchPaths.fixture("types"))
            context(s) {
                h.run("data", "echo-name(string 5)", "$b/dsl") { bench.typed.types.echoName("hello") }
                for (n in sizes) h.run("data", "echo-bytes(list<u8> $n)", "$b/dsl") { bench.typed.types.echoBytes(payloads.getValue(n)) }
                h.run("data", "echo-blob(record, 1 KiB)", "$b/dsl") { bench.typed.types.echoBlob(blob) }
            }
            s.close()
        } else {
            val blob = bench.types.Blob(name = "sample", data = Random(7).nextBytes(1024), tag = 7u)
            val s = openTypes(b)
            context(s) {
                h.run("data", "echo-name(string 5)", "$b/dsl") { bench.types.echoName("hello") }
                for (n in sizes) h.run("data", "echo-bytes(list<u8> $n)", "$b/dsl") { bench.types.echoBytes(payloads.getValue(n)) }
                h.run("data", "echo-blob(record, 1 KiB)", "$b/dsl") { bench.types.echoBlob(blob) }
            }
            s.close()
        }
    }

    // The bridge layer alone (FFM), generic and typed, with the arguments already written.
    val bridge = FfmWasmtimeBridge.getDefault()
    for ((label, id) in listOf("wasmtime-ffm/bridge" to null, "wasmtime-typed-ffm/bridge" to "types")) {
        val session = bridge.openSession(BenchPaths.fixture("types"), null, id)
        val echo = bridge.resolve(session, "echo-bytes")
        for (n in sizes) {
            val w = WitWriter().apply { bytes(payloads.getValue(n)) }
            h.run("data", "echo-bytes(list<u8> $n)", label) { bridge.invoke(session, echo, w) }
        }
        bridge.sessionClose(session)
    }

    val text = buildString { val r = Random(3); repeat(2000) { append(if (r.nextInt(4) == 0) "running " else "walked ") } }
    val json = buildString {
        append('[')
        repeat(200) { i -> if (i > 0) append(','); append("""{"id":$i,"name":"item-$i","tags":["a","b"],"score":${i * 0.5},"ok":${i % 2 == 0}}""") }
        append(']')
    }
    val sortInput = Random(11).nextBytes(65536)
    for (b in listOf("wasmtime-ffm", "wasmtime-typed-ffm")) {
        if (typed(b)) {
            bench.typed.heavy.WasmtimeSession.useFfmBackend()
            val s = bench.typed.heavy.openSession(BenchPaths.fixture("heavy"))
            context(s) {
                h.run("compute", "regex-count(${text.length / 1024} KiB)", "$b/dsl") { bench.typed.heavy.regexCount("[a-z]+ing", text) }
                h.run("compute", "json-roundtrip(${json.length / 1024} KiB)", "$b/dsl") { bench.typed.heavy.jsonRoundtrip(json) }
                h.run("compute", "sort-bytes(64 KiB)", "$b/dsl") { bench.typed.heavy.sortBytes(sortInput) }
            }
            s.close()
        } else {
            val s = openHeavy(b)
            context(s) {
                h.run("compute", "regex-count(${text.length / 1024} KiB)", "$b/dsl") { bench.heavy.regexCount("[a-z]+ing", text) }
                h.run("compute", "json-roundtrip(${json.length / 1024} KiB)", "$b/dsl") { bench.heavy.jsonRoundtrip(json) }
                h.run("compute", "sort-bytes(64 KiB)", "$b/dsl") { bench.heavy.sortBytes(sortInput) }
            }
            s.close()
        }
    }

    h.write(System.getProperty("wasm2kt.bench.outFile") ?: "typed.tsv")
}
