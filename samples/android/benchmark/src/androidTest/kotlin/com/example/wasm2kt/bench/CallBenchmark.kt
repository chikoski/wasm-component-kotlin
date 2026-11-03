package com.example.wasm2kt.bench

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import wasm2kt.runtime.FfmJsonCodec
import com.example.wasm2kt.bench.arithmetic.ArithmeticComponent
import com.example.wasm2kt.bench.heavy.HeavyComponent
import com.example.wasm2kt.bench.types.TypesComponent

/**
 * Steady-state per-call costs on ART. Names follow benchmarks/ on the JVM:
 * `<function>_<backend>_<layer>`; `aot` is the shipped path, `interp` Chicory's interpreter.
 */
@RunWith(AndroidJUnit4::class)
class CallBenchmark {
    @get:Rule val rule = BenchmarkRule()

    private inline fun <T> arithmetic(component: wasm2kt.runtime.ComponentBackendFactory, crossinline op: context(com.example.wasm2kt.bench.arithmetic.Session) () -> T) =
        com.example.wasm2kt.bench.arithmetic.withSession(component) { rule.measureRepeated { op() } }

    @Test fun add_aot_dsl() = arithmetic(ArithmeticComponent) { com.example.wasm2kt.bench.arithmetic.add(1, 2) }
    @Test fun add_interp_dsl() = arithmetic(Payloads.interpreted("arithmetic")) { com.example.wasm2kt.bench.arithmetic.add(1, 2) }

    @Test fun add_aot_backend() {
        val backend = ArithmeticComponent.instantiate(null)
        val args = JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2)))
        rule.measureRepeated { backend.invoke("add", args) }
        backend.close()
    }

    @Test fun add_aot_core() { val f = Payloads.coreAdd(compiled = true); rule.measureRepeated { f.apply(1, 2) } }
    @Test fun add_interp_core() { val f = Payloads.coreAdd(compiled = false); rule.measureRepeated { f.apply(1, 2) } }

    private inline fun types(component: wasm2kt.runtime.ComponentBackendFactory, crossinline op: context(com.example.wasm2kt.bench.types.Session) () -> Any?) =
        com.example.wasm2kt.bench.types.withSession(component) { rule.measureRepeated { op() } }

    @Test fun echoName5_aot_dsl() = types(TypesComponent) { com.example.wasm2kt.bench.types.echoName("hello") }
    @Test fun echoBytes16_aot_dsl() = types(TypesComponent) { com.example.wasm2kt.bench.types.echoBytes(Payloads.bytes.getValue(16)) }
    @Test fun echoBytes1024_aot_dsl() = types(TypesComponent) { com.example.wasm2kt.bench.types.echoBytes(Payloads.bytes.getValue(1024)) }
    @Test fun echoBytes65536_aot_dsl() = types(TypesComponent) { com.example.wasm2kt.bench.types.echoBytes(Payloads.bytes.getValue(65536)) }
    @Test fun echoBlob1024_aot_dsl() = types(TypesComponent) { com.example.wasm2kt.bench.types.echoBlob(Payloads.blob) }
    @Test fun echoBytes1024_interp_dsl() = types(Payloads.interpreted("types")) { com.example.wasm2kt.bench.types.echoBytes(Payloads.bytes.getValue(1024)) }

    @Test fun echoBytes1024_aot_backend() {
        val backend = TypesComponent.instantiate(null)
        val args = JsonArray(listOf(FfmJsonCodec.toJsonElement(Payloads.bytes.getValue(1024))))
        rule.measureRepeated { backend.invoke("echo-bytes", args) }
        backend.close()
    }

    @Test fun codecBytes1024_jsonTree() {
        val b = Payloads.bytes.getValue(1024)
        rule.measureRepeated { FfmJsonCodec.decodeByteArray(FfmJsonCodec.toJsonElement(b)) }
    }

    @Test fun codecBytes65536_jsonTree() {
        val b = Payloads.bytes.getValue(65536)
        rule.measureRepeated { FfmJsonCodec.decodeByteArray(FfmJsonCodec.toJsonElement(b)) }
    }

    private inline fun heavy(component: wasm2kt.runtime.ComponentBackendFactory, crossinline op: context(com.example.wasm2kt.bench.heavy.Session) () -> Any?) =
        com.example.wasm2kt.bench.heavy.withSession(component) { rule.measureRepeated { op() } }

    @Test fun lcgSum1e6_aot_dsl() = heavy(HeavyComponent) { com.example.wasm2kt.bench.heavy.lcgSum(1u, 1_000_000u) }
    @Test fun lcgSum1e5_interp_dsl() = heavy(Payloads.interpreted("heavy")) { com.example.wasm2kt.bench.heavy.lcgSum(1u, 100_000u) }
    @Test fun regexCount16B_aot_dsl() = heavy(HeavyComponent) { com.example.wasm2kt.bench.heavy.regexCount("[a-z]+ing", "running walking ") }
    @Test fun regexCount14KiB_aot_dsl() = heavy(HeavyComponent) { com.example.wasm2kt.bench.heavy.regexCount("[a-z]+ing", Payloads.text) }
    @Test fun jsonRoundtrip13KiB_aot_dsl() = heavy(HeavyComponent) { com.example.wasm2kt.bench.heavy.jsonRoundtrip(Payloads.json) }
    @Test fun sortBytes64KiB_aot_dsl() = heavy(HeavyComponent) { com.example.wasm2kt.bench.heavy.sortBytes(Payloads.sortInput) }
}
