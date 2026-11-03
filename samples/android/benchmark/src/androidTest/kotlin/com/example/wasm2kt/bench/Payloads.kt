package com.example.wasm2kt.bench

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.dylibso.chicory.runtime.ExportFunction
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.Machine
import com.dylibso.chicory.wasm.Parser
import wasm2kt.runtime.chicory.ChicoryComponent
import kotlin.random.Random

/** Inputs shared by the benchmarks; the same as benchmarks/ on the JVM. */
object Payloads {
    val sizes = listOf(16, 1024, 65536)
    val bytes: Map<Int, ByteArray> = sizes.associateWith { Random(it).nextBytes(it) }
    val blob = com.example.wasm2kt.bench.types.Blob(name = "sample", data = Random(7).nextBytes(1024), tag = 7u)
    val text: String = buildString { val r = Random(3); repeat(2000) { append(if (r.nextInt(4) == 0) "running " else "walked ") } }
    val json: String = buildString {
        append('[')
        repeat(200) { i -> if (i > 0) append(','); append("""{"id":$i,"name":"item-$i","tags":["a","b"],"score":${i * 0.5},"ok":${i % 2 == 0}}""") }
        append(']')
    }
    val sortInput: ByteArray = Random(11).nextBytes(65536)

    private val testContext: Context get() = InstrumentationRegistry.getInstrumentation().context

    /** The decomposed component on Chicory's interpreter (assets of the test APK). */
    fun interpreted(name: String): ChicoryComponent =
        ChicoryComponent.fromResources { file -> testContext.assets.open("decomposed/$name/$file").use { it.readBytes() } }

    /** A bare core-wasm `add`, AOT-compiled (`compiled`) or interpreted. */
    fun coreAdd(compiled: Boolean): ExportFunction {
        val instance = if (compiled) {
            val meta = requireNotNull(Payloads::class.java.getResourceAsStream("/com/example/wasm2kt/bench/arithmetic/wasm2kt/arithmetic/Core0.meta")).readBytes()
            val machine = Class.forName("com.example.wasm2kt.bench.arithmetic.wasm2kt.arithmetic.Core0Machine").getConstructor(Instance::class.java)
            Instance.builder(Parser.parse(meta)).withMachineFactory { machine.newInstance(it) as Machine }.build()
        } else {
            Instance.builder(Parser.parse(testContext.assets.open("decomposed/arithmetic/core0.wasm").use { it.readBytes() })).build()
        }
        return instance.export("add")
    }
}
