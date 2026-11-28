package wasm2kt.bench

import java.nio.file.Files
import java.util.concurrent.TimeUnit

private const val ASYNC_COLD_RUNS = 9

fun main(@Suppress("UNUSED_PARAMETER") args: Array<String>) {
    println(environment())
    val java = ProcessHandle.current().info().command().orElse("java")
    val cp = System.getProperty("java.class.path")
    val props = listOf("wasm2kt.bench.gen", "wasm2kt.bench.out").map { "-D$it=${System.getProperty(it)}" }
    val cases = listOf("async-pipeline", "wasi-03").flatMap { c -> listOf("aot", "chicory-aot").map { c to it } }
    val tsv = StringBuilder("component\tbackend\tphase\tmedian_ms\tmin_ms\tmax_ms\truns\n")
    for ((component, backend) in cases) {
        val phases = linkedMapOf<String, MutableList<Double>>()
        repeat(ASYNC_COLD_RUNS) {
            val p = ProcessBuilder(
                listOf(java, "-Xms256m", "-Xmx1g") + props +
                    listOf("-cp", cp, "wasm2kt.bench.AsyncColdChildKt", component, backend),
            ).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            check(p.waitFor(5, TimeUnit.MINUTES) && p.exitValue() == 0) { "async child failed: $out" }
            for (line in out.lines().filter { it.startsWith("PHASE ") }) {
                val (_, phase, ms) = line.split(' ')
                phases.getOrPut(phase) { mutableListOf() } += ms.toDouble()
            }
        }
        for ((phase, values) in phases) {
            val sorted = values.sorted()
            val row = listOf(component, backend, phase, "%.2f".format(sorted[sorted.size / 2]), "%.2f".format(sorted.first()), "%.2f".format(sorted.last()), sorted.size)
            tsv.append(row.joinToString("\t")).append('\n')
            println(row.joinToString("  "))
        }
    }
    Files.createDirectories(BenchPaths.out)
    Files.writeString(BenchPaths.out.resolve("async-cold-start.tsv"), tsv)
}
