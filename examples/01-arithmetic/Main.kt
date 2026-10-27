package com.example.arithmetic

import java.nio.file.Paths

fun main() {
    val wasmPath = Paths.get("src/test/resources/fixtures/arithmetic.wasm")

    ArithmeticWorld.withSession(wasmPath) {
        val sum = ArithmeticWorld.add(20, 7)
        val diff = ArithmeticWorld.sub(50, 8)
        val prod = ArithmeticWorld.mul(-6, -7)
        val quot = ArithmeticWorld.div(84, 2)

        println("20 + 7   = $sum")
        println("50 - 8   = $diff")
        println("-6 * -7  = $prod")
        println("84 / 2   = $quot")
    }
}
