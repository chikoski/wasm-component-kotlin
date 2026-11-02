package com.example.arithmetic

import java.nio.file.Paths

fun main() {
    val wasmPath = Paths.get("fixtures/arithmetic.wasm")

    withSession(wasmPath) {
        val sum = add(20, 7)
        val diff = sub(50, 8)
        val prod = mul(-6, -7)
        val quot = div(84, 2)

        println("20 + 7   = $sum")
        println("50 - 8   = $diff")
        println("-6 * -7  = $prod")
        println("84 / 2   = $quot")
    }
}
