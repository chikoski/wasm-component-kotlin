package wasm2kt.bench

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Where the prepared components live (see Prepare.kt) and where results go. */
object BenchPaths {
    val gen: Path = Paths.get(System.getProperty("wasm2kt.bench.gen") ?: "benchmarks/build/bench-gen")
    val out: Path = Paths.get(System.getProperty("wasm2kt.bench.out") ?: "benchmarks/build/results")
    fun fixture(name: String): Path = Paths.get("fixtures/$name.wasm").toAbsolutePath()
    fun decomposed(name: String): Path = gen.resolve("decomposed/$name")
}

/** Keeps results alive so the JIT cannot drop the measured work. */
object Blackhole {
    @Volatile private var sink: Int = 0
    fun consume(v: Any?) {
        sink = sink xor (when (v) {
            null -> 0
            is ByteArray -> v.size
            is String -> v.length
            else -> v.hashCode()
        })
    }
}

class Measurement(val group: String, val name: String, val backend: String, val roundsNsPerOp: List<Double>, val ops: Long) {
    val median: Double get() = roundsNsPerOp.sorted()[roundsNsPerOp.size / 2]
    val min: Double get() = roundsNsPerOp.minOrNull() ?: Double.NaN
    val max: Double get() = roundsNsPerOp.maxOrNull() ?: Double.NaN
}

/**
 * A small fixed-time harness: warm up for [warmupMs], then [rounds] rounds of at least [roundMs]
 * each; one result per round in ns/op. Good enough to compare backends that differ by large factors;
 * see docs/performance.md for the caveats.
 */
class Harness(
    private val warmupMs: Long = System.getProperty("wasm2kt.bench.warmupMs")?.toLong() ?: 2000,
    private val roundMs: Long = System.getProperty("wasm2kt.bench.roundMs")?.toLong() ?: 700,
    private val rounds: Int = System.getProperty("wasm2kt.bench.rounds")?.toInt() ?: 5,
) {
    private val filter = System.getProperty("wasm2kt.bench.filter")?.let { Regex(it) }
    val results = mutableListOf<Measurement>()

    fun run(group: String, name: String, backend: String, op: () -> Any?) {
        val id = "$group/$name/$backend"
        if (filter != null && !filter.containsMatchIn(id)) return
        val warmEnd = System.nanoTime() + warmupMs * 1_000_000
        var warm = 0
        while (System.nanoTime() < warmEnd || warm < 3) { Blackhole.consume(op()); warm++ }
        val perRound = mutableListOf<Double>()
        var total = 0L
        repeat(rounds) {
            var n = 0L
            val start = System.nanoTime()
            val end = start + roundMs * 1_000_000
            var now: Long
            do {
                Blackhole.consume(op()); n++
                now = System.nanoTime()
            } while (now < end)
            perRound += (now - start).toDouble() / n
            total += n
        }
        val m = Measurement(group, name, backend, perRound, total)
        results += m
        println(String.format("%-14s %-28s %-22s %14s /op  (min %s, max %s, %d ops)", group, name, backend, fmt(m.median), fmt(m.min), fmt(m.max), total))
    }

    fun write(file: String) {
        Files.createDirectories(BenchPaths.out)
        val tsv = StringBuilder("group\tname\tbackend\tmedian_ns\tmin_ns\tmax_ns\tops\n")
        for (m in results) tsv.append("${m.group}\t${m.name}\t${m.backend}\t${"%.1f".format(m.median)}\t${"%.1f".format(m.min)}\t${"%.1f".format(m.max)}\t${m.ops}\n")
        Files.writeString(BenchPaths.out.resolve(file), tsv)
        println("wrote ${BenchPaths.out.resolve(file)}")
    }

    companion object {
        fun fmt(ns: Double): String = when {
            ns >= 1e6 -> "%.2f ms".format(ns / 1e6)
            ns >= 1e3 -> "%.2f us".format(ns / 1e3)
            else -> "%.0f ns".format(ns)
        }
    }
}

fun environment(): String {
    val cpu = runCatching {
        Files.readAllLines(Paths.get("/proc/cpuinfo")).firstOrNull { it.startsWith("model name") }?.substringAfter(':')?.trim()
    }.getOrNull() ?: System.getProperty("os.arch")
    return "JVM ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}, ${Runtime.getRuntime().availableProcessors()} CPUs ($cpu), " +
        "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
}
