package com.example.counter

import java.nio.file.Paths

fun main() {
    val wasmPath = Paths.get("fixtures/counter.wasm")

    CounterWorldWorld.withSession(wasmPath) {
        CounterWorldWorld.Counter(10).use { c1 ->
            c1.increment()
            c1.increment()
            c1.add(5)
            println("c1 initial value = ${c1.getValue()}")

            val c2 = CounterWorldWorld.Counter(25)
            c2.increment()

            CounterWorldWorld.Counter.merge(c1, c2).use { merged ->
                println("c1 after merge   = ${c1.getValue()}")
                println("c2 isClosed      = ${c2.isClosed}")
                println("merged value     = ${merged.getValue()}")
            }
        }
    }
}
