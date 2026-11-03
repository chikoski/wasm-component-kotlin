package com.example.wasm2kt.bench

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import com.example.wasm2kt.bench.arithmetic.ArithmeticComponent
import com.example.wasm2kt.bench.heavy.HeavyComponent

/**
 * First instantiation and first call in a fresh process (class loading and verification, `.meta`
 * and plan parsing, core instantiation). Run it alone, once per process (`am instrument -e class
 * ...ColdStartProbe#<method>`); bench.yml repeats it and reads `PHASE` lines from logcat.
 */
@RunWith(AndroidJUnit4::class)
class ColdStartProbe {
    private fun ms(start: Long) = (System.nanoTime() - start) / 1e6
    private fun phase(component: String, name: String, start: Long) = Log.i("wasm2kt-bench", "PHASE $component $name ${"%.3f".format(ms(start))}")

    @Test fun arithmetic() {
        val t0 = System.nanoTime()
        val s = com.example.wasm2kt.bench.arithmetic.openSession(ArithmeticComponent); phase("arithmetic", "open", t0)
        val t1 = System.nanoTime(); context(s) { com.example.wasm2kt.bench.arithmetic.add(1, 2) }; phase("arithmetic", "first-call", t1)
        phase("arithmetic", "total", t0)
        val t2 = System.nanoTime(); context(s) { com.example.wasm2kt.bench.arithmetic.add(3, 4) }; phase("arithmetic", "second-call", t2)
        s.close()
        val t3 = System.nanoTime(); com.example.wasm2kt.bench.arithmetic.openSession(ArithmeticComponent).close(); phase("arithmetic", "second-open", t3)
    }

    @Test fun heavy() {
        val t0 = System.nanoTime()
        val s = com.example.wasm2kt.bench.heavy.openSession(HeavyComponent); phase("heavy", "open", t0)
        val t1 = System.nanoTime(); context(s) { com.example.wasm2kt.bench.heavy.lcgSum(1u, 1000u) }; phase("heavy", "first-call", t1)
        phase("heavy", "total", t0)
        val t2 = System.nanoTime(); context(s) { com.example.wasm2kt.bench.heavy.lcgSum(2u, 1000u) }; phase("heavy", "second-call", t2)
        s.close()
        val t3 = System.nanoTime(); com.example.wasm2kt.bench.heavy.openSession(HeavyComponent).close(); phase("heavy", "second-open", t3)
    }
}
