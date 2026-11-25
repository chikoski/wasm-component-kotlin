package com.example.wasm2kt.sample

import com.example.wasm2kt.sample.counter_aot.CounterAotComponent
import com.example.wasm2kt.sample.counter_aot.exports.example.counter.counter_api.Counter
import com.example.wasm2kt.sample.roundtrip_aot.Action
import com.example.wasm2kt.sample.roundtrip_aot.ItemSummary
import com.example.wasm2kt.sample.roundtrip_aot.Permissions
import com.example.wasm2kt.sample.roundtrip_aot.Priority
import com.example.wasm2kt.sample.roundtrip_aot.RoundtripAotComponent
import com.example.wasm2kt.sample.roundtrip_aot.WitOption
import com.example.wasm2kt.sample.roundtrip_aot.WitResult
import com.example.wasm2kt.sample.roundtrip_aot.checkEven
import com.example.wasm2kt.sample.roundtrip_aot.describeCode
import com.example.wasm2kt.sample.roundtrip_aot.echoU64
import com.example.wasm2kt.sample.roundtrip_aot.example.roundtrip.env_provider.EnvProvider
import com.example.wasm2kt.sample.roundtrip_aot.exports.example.roundtrip.math_api.scale
import com.example.wasm2kt.sample.roundtrip_aot.nestedOption
import com.example.wasm2kt.sample.roundtrip_aot.transformSummary
import com.example.wasm2kt.sample.counter_aot.withSession as withCounterAotSession
import com.example.wasm2kt.sample.roundtrip_aot.imports as roundtripAotImports
import com.example.wasm2kt.sample.roundtrip_aot.withSession as withRoundtripAotSession

/** Calls into the AOT-compiled components; mirrors [ComponentDemo]. */
object AotComponentDemo {
    fun run(): List<String> {
        val imports = roundtripAotImports(
            exampleRoundtripEnvProvider = {
                object : EnvProvider {
                    override fun getMultiplier(): Int = 6
                    override fun formatTag(prefix: String, code: UInt): String = "$prefix#$code"
                }
            },
        )
        val roundtrip = withRoundtripAotSession(RoundtripAotComponent, imports) {
            val summary = transformSummary(
                ItemSummary(count = 10u, perms = Permissions.READ, prio = Priority.LOW),
                Action.AddCount(5u),
            )
            listOf(
                "math-api.scale(7) = ${scale(7)}",
                "describe-code(\"ERR\", 404) = ${describeCode("ERR", 404u)}",
                "transform-summary(add-count 5).count = ${summary.count}",
                "check-even(84) = ${(checkEven(84) as WitResult.Ok).value}",
                "nested-option(none) is none = ${nestedOption(WitOption.None) is WitOption.None}",
                "echo-u64(max) = ${echoU64(ULong.MAX_VALUE)}",
            )
        }
        val counter = withCounterAotSession(CounterAotComponent) {
            Counter(10).use { a ->
                a.increment()
                val b = Counter(31)
                Counter.merge(a, b).use { merged -> listOf("counter merge(11, 31) = ${merged.getValue()}") }
            }
        }
        return roundtrip + counter
    }
}
