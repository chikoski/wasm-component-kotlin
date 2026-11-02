package com.example.wasm2kt.sample

import com.example.wasm2kt.sample.counter.CounterComponent
import com.example.wasm2kt.sample.counter.exports.example.counter.counter_api.Counter
import com.example.wasm2kt.sample.roundtrip.Action
import com.example.wasm2kt.sample.roundtrip.ItemSummary
import com.example.wasm2kt.sample.roundtrip.Permissions
import com.example.wasm2kt.sample.roundtrip.Priority
import com.example.wasm2kt.sample.roundtrip.RoundtripComponent
import com.example.wasm2kt.sample.roundtrip.WitOption
import com.example.wasm2kt.sample.roundtrip.WitResult
import com.example.wasm2kt.sample.roundtrip.checkEven
import com.example.wasm2kt.sample.roundtrip.describeCode
import com.example.wasm2kt.sample.roundtrip.echoU64
import com.example.wasm2kt.sample.roundtrip.example.roundtrip.env_provider.EnvProvider
import com.example.wasm2kt.sample.roundtrip.exports.example.roundtrip.math_api.scale
import com.example.wasm2kt.sample.roundtrip.nestedOption
import com.example.wasm2kt.sample.roundtrip.transformSummary
// Each component's world lives at the top level of its own package (ADR-0012).
import com.example.wasm2kt.sample.counter.withSession as withCounterSession
import com.example.wasm2kt.sample.roundtrip.imports as roundtripImports
import com.example.wasm2kt.sample.roundtrip.withSession as withRoundtripSession

/** Calls into both components; shared by the activity and the tests. */
object ComponentDemo {
    fun run(): List<String> {
        val imports = roundtripImports(
            exampleRoundtripEnvProvider = {
                object : EnvProvider {
                    override fun getMultiplier(): Int = 6
                    override fun formatTag(prefix: String, code: UInt): String = "$prefix#$code"
                }
            },
        )
        val roundtrip = withRoundtripSession(RoundtripComponent, imports) {
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
        val counter = withCounterSession(CounterComponent) {
            Counter(10).use { a ->
                a.increment()
                val b = Counter(31)
                Counter.merge(a, b).use { merged -> listOf("counter merge(11, 31) = ${merged.getValue()}") }
            }
        }
        return roundtrip + counter
    }
}
