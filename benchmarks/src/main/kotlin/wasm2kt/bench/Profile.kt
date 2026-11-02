package wasm2kt.bench

/**
 * Where a heavy call spends its time on Chicory AOT: samples the calling thread's stack and counts
 * samples inside the interpreter (functions that fell back) vs. compiled code vs. the component layer.
 * Usage: `./gradlew -Pwasm2kt.bench :benchmarks:profile -Pwasm2kt.bench.filter=<regex|json|lcg|sort>`.
 */
fun main(@Suppress("UNUSED_PARAMETER") args: Array<String>) {
    val which = System.getProperty("wasm2kt.bench.filter") ?: "regex"
    val text = buildString { val r = kotlin.random.Random(3); repeat(2000) { append(if (r.nextInt(4) == 0) "running " else "walked ") } }
    val json = buildString { append('['); repeat(200) { i -> if (i > 0) append(','); append("""{"id":$i,"name":"item-$i","tags":["a","b"],"score":${i * 0.5},"ok":${i % 2 == 0}}""") }; append(']') }
    val bytes = kotlin.random.Random(11).nextBytes(65536)
    val s = openHeavy("chicory-aot")
    val running = java.util.concurrent.atomic.AtomicBoolean(true)
    val worker = Thread {
        context(s) {
            while (running.get()) Blackhole.consume(
                when (which) {
                    "json" -> bench.heavy.jsonRoundtrip(json)
                    "lcg" -> bench.heavy.lcgSum(1u, 1_000_000u)
                    "sort" -> bench.heavy.sortBytes(bytes)
                    else -> bench.heavy.regexCount("[a-z]+ing", text)
                },
            )
        }
    }
    worker.start()
    Thread.sleep(3000) // warm up
    val counts = linkedMapOf("interpreter" to 0, "compiled wasm" to 0, "component layer (ABI, JSON)" to 0, "other" to 0)
    val top = HashMap<String, Int>()
    repeat(2000) {
        val st = worker.stackTrace
        val key = when {
            st.any { it.className.contains("InterpreterMachine") } -> "interpreter"
            st.firstOrNull()?.className?.contains("Machine") == true || st.take(3).any { it.className.contains("FuncGroup") || it.className.contains("Core0Machine") } -> "compiled wasm"
            st.take(6).any { it.className.startsWith("wasm2kt.") || it.className.startsWith("kotlinx.serialization") } -> "component layer (ABI, JSON)"
            else -> "other"
        }
        counts[key] = counts.getValue(key) + 1
        st.firstOrNull()?.let { top.merge("${it.className}.${it.methodName}", 1, Int::plus) }
        Thread.sleep(2)
    }
    running.set(false)
    worker.join()
    s.close()
    println("profile $which: $counts")
    println("top frames: " + top.entries.sortedByDescending { it.value }.take(8).joinToString { "${it.key}=${it.value}" })
}
