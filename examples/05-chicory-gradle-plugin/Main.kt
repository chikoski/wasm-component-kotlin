package com.example.chicory

import com.example.chicory.example.roundtrip.env_provider.EnvProvider
import com.example.chicory.exports.example.roundtrip.math_api.scale

fun main() {
    val hostImports = imports(
        exampleRoundtripEnvProvider = {
            object : EnvProvider {
                override fun getMultiplier(): Int = 6
                override fun formatTag(prefix: String, code: UInt): String = "$prefix#$code"
            }
        },
    )

    withSession(RoundtripComponent, hostImports) {
        val scaled = scale(7)
        val tag = describeCode("ERR", 404u)
        val initial = ItemSummary(
            count = 10u,
            perms = Permissions.READ or Permissions.EXECUTE,
            prio = Priority.LOW,
        )
        val updated = transformSummary(initial, Action.AddCount(5u))
        println("scaled = $scaled, tag = $tag, count = ${updated.count}")
    }
}
