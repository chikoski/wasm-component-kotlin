package com.example.wasm2kt.sample

import com.example.wasm2kt.sample.counter.CounterComponent
import com.example.wasm2kt.sample.roundtrip.RootWorld
import com.example.wasm2kt.sample.roundtrip.RoundtripComponent
import com.example.wasm2kt.sample.roundtrip.WitOption
import com.example.wasm2kt.sample.roundtrip.WitResult
import com.example.wasm2kt.sample.roundtrip.checkEven
import com.example.wasm2kt.sample.roundtrip.describeCode
import com.example.wasm2kt.sample.roundtrip.echoU64
import com.example.wasm2kt.sample.roundtrip.nestedOption
import com.example.wasm2kt.sample.roundtrip.scale
import com.example.wasm2kt.sample.roundtrip.transformSummary
import com.example.wasm2kt.sample.counter.RootWorld as CounterWorld

/** Calls into both components; shared by the activity and the tests. */
object ComponentDemo {
    fun run(): List<String> {
        val imports = object : RootWorld.Imports {
            override val envProvider = object : RootWorld.EnvProvider {
                override fun getMultiplier(): Int = 6
                override fun formatTag(prefix: String, code: UInt): String = "$prefix#$code"
            }
        }
        val roundtrip = RootWorld.withSession(RoundtripComponent, imports) {
            val summary = RootWorld.transformSummary(
                RootWorld.ItemSummary(count = 10u, perms = RootWorld.Permissions.READ, prio = RootWorld.Priority.LOW),
                RootWorld.Action.AddCount(5u),
            )
            listOf(
                "math-api.scale(7) = ${with(RootWorld.MathApi) { scale(7) }}",
                "describe-code(\"ERR\", 404) = ${RootWorld.describeCode("ERR", 404u)}",
                "transform-summary(add-count 5).count = ${summary.count}",
                "check-even(84) = ${(RootWorld.checkEven(84) as WitResult.Ok).value}",
                "nested-option(none) is none = ${RootWorld.nestedOption(WitOption.None) is WitOption.None}",
                "echo-u64(max) = ${RootWorld.echoU64(ULong.MAX_VALUE)}",
            )
        }
        val counter = CounterWorld.withSession(CounterComponent) {
            CounterWorld.Counter(10).use { a ->
                a.increment()
                val b = CounterWorld.Counter(31)
                CounterWorld.Counter.merge(a, b).use { merged -> listOf("counter merge(11, 31) = ${merged.getValue()}") }
            }
        }
        return roundtrip + counter
    }
}
