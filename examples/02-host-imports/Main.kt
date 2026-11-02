package com.example.live

import java.nio.file.Paths

fun main() {
    val wasmPath = Paths.get("fixtures/live_calc.wasm")
    val auditLog = mutableListOf<String>()

    // World-level imports are implemented through Imports and passed with imports(...).
    val hostImports = imports(
        world = {
            object : Imports {
                override fun recordCalc(a: Int, b: Int) {
                    auditLog += "Guest requested calculation with ($a, $b)"
                }
            }
        },
    )

    withSession(wasmPath, hostImports) {
        val sum = add(15, 27)
        val product = multiply(6, 7)

        println("sum = $sum, product = $product")
    }

    auditLog.forEach(::println)
}
