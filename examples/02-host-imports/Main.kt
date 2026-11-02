package com.example.live

import java.nio.file.Paths

fun main() {
    val wasmPath = Paths.get("fixtures/live_calc.wasm")
    val auditLog = mutableListOf<String>()

    // World-level imports are implemented through LiveCalcWorld.Imports and passed with imports(...).
    val hostImports = LiveCalcWorld.imports(
        world = {
            object : LiveCalcWorld.Imports {
                override fun recordCalc(a: Int, b: Int) {
                    auditLog += "Guest requested calculation with ($a, $b)"
                }
            }
        },
    )

    LiveCalcWorld.withSession(wasmPath, hostImports) {
        val sum = LiveCalcWorld.add(15, 27)
        val product = LiveCalcWorld.multiply(6, 7)

        println("sum = $sum, product = $product")
    }

    auditLog.forEach(::println)
}
