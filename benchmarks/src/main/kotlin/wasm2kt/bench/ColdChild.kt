package wasm2kt.bench

/** One cold-start measurement (see ColdStart.kt). Prints `PHASE <name> <ms>` lines. */
fun main(args: Array<String>) {
    val (component, backend) = args
    fun ms(start: Long) = (System.nanoTime() - start) / 1e6
    fun phase(name: String, start: Long) = println("PHASE $name ${"%.3f".format(ms(start))}")
    val t0 = System.nanoTime()
    when (component) {
        "arithmetic" -> {
            if (backend == "aot") {
                val s = bench.arithmetic_aot.openSession(bench.arithmetic_aot.ArithmeticAotComponent); phase("open", t0)
                val t1 = System.nanoTime(); context(s) { Blackhole.consume(bench.arithmetic_aot.add(1, 2)) }; phase("first-call", t1)
                phase("total", t0)
                val t2 = System.nanoTime(); context(s) { Blackhole.consume(bench.arithmetic_aot.add(3, 4)) }; phase("second-call", t2)
                s.close()
                val t3 = System.nanoTime(); val s2 = bench.arithmetic_aot.openSession(bench.arithmetic_aot.ArithmeticAotComponent); phase("second-open", t3); s2.close()
            } else {
                val s = openArithmetic(backend); phase("open", t0)
                val t1 = System.nanoTime(); context(s) { Blackhole.consume(bench.arithmetic.add(1, 2)) }; phase("first-call", t1)
                phase("total", t0)
                val t2 = System.nanoTime(); context(s) { Blackhole.consume(bench.arithmetic.add(3, 4)) }; phase("second-call", t2)
                s.close()
                val t3 = System.nanoTime(); val s2 = openArithmetic(backend); phase("second-open", t3); s2.close()
            }
        }
        "heavy" -> {
            if (backend == "aot") {
                val s = bench.heavy_aot.openSession(bench.heavy_aot.HeavyAotComponent); phase("open", t0)
                val t1 = System.nanoTime(); context(s) { Blackhole.consume(bench.heavy_aot.lcgSum(1u, 1000u)) }; phase("first-call", t1)
                phase("total", t0)
                val t2 = System.nanoTime(); context(s) { Blackhole.consume(bench.heavy_aot.lcgSum(2u, 1000u)) }; phase("second-call", t2)
                s.close()
                val t3 = System.nanoTime(); val s2 = bench.heavy_aot.openSession(bench.heavy_aot.HeavyAotComponent); phase("second-open", t3); s2.close()
            } else {
                val s = openHeavy(backend); phase("open", t0)
                val t1 = System.nanoTime(); context(s) { Blackhole.consume(bench.heavy.lcgSum(1u, 1000u)) }; phase("first-call", t1)
                phase("total", t0)
                val t2 = System.nanoTime(); context(s) { Blackhole.consume(bench.heavy.lcgSum(2u, 1000u)) }; phase("second-call", t2)
                s.close()
                val t3 = System.nanoTime(); val s2 = openHeavy(backend); phase("second-open", t3); s2.close()
            }
        }
        else -> error("unknown component $component")
    }
}
