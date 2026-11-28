@file:OptIn(InternalWasm2KtApi::class)

package com.example.wasm2kt.sample

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import wasm2kt.runtime.aot.LinearMemory
import wasm2kt.runtime.aot.WasmNumerics
import wasm2kt.runtime.canonical.InternalWasm2KtApi

/** Exercises AOT NaN bit preservation on ART (arm64-v8a locally, x86_64 on CI). */
@RunWith(AndroidJUnit4::class)
class AotComponentInstrumentedTest {
    @Test
    fun nanBitPatternsPreservedOnArt() {
        val mem = LinearMemory(1)
        val f32Patterns = intArrayOf(
            0x7f800001, // positive sNaN
            0x7fc00000, // canonical qNaN
            0x7fffffff, // max payload qNaN
            0xff800042.toInt(), // negative sNaN with payload
            -1, // negative qNaN all-ones
        )
        for (bits in f32Patterns) {
            // Mirrors WasmBytecodeEmitter's direct Float.intBitsToFloat / floatToRawIntBits emission.
            assertEquals(bits, java.lang.Float.floatToRawIntBits(java.lang.Float.intBitsToFloat(bits)))

            val viaNumerics = WasmNumerics.i32ReinterpretF32(WasmNumerics.f32ReinterpretI32(bits))
            assertEquals("WasmNumerics F32 NaN bits changed for 0x${bits.toUInt().toString(16)}", bits, viaNumerics)

            mem.storeI32(0, 0, bits)
            val loadedAsFloat = mem.loadF32(0, 0)
            mem.storeF32(64, 0, loadedAsFloat)
            val reloadedBits = mem.loadI32(64, 0)
            assertEquals("LinearMemory F32 NaN bits changed for 0x${bits.toUInt().toString(16)}", bits, reloadedBits)
        }

        val f64Patterns = longArrayOf(
            0x7ff0000000000001L, // positive sNaN
            0x7ff8000000000000L, // canonical qNaN
            0x7fffffffffffffffL, // max payload qNaN
            -0x000ffffffffffebeL, // 0xfff0000000000142 negative sNaN
            -1L, // negative qNaN all-ones
        )
        for (bits in f64Patterns) {
            assertEquals(bits, java.lang.Double.doubleToRawLongBits(java.lang.Double.longBitsToDouble(bits)))

            val viaNumerics = WasmNumerics.i64ReinterpretF64(WasmNumerics.f64ReinterpretI64(bits))
            assertEquals("WasmNumerics F64 NaN bits changed for 0x${bits.toULong().toString(16)}", bits, viaNumerics)

            mem.storeI64(128, 0, bits)
            val loadedAsDouble = mem.loadF64(128, 0)
            mem.storeF64(256, 0, loadedAsDouble)
            val reloadedBits = mem.loadI64(256, 0)
            assertEquals("LinearMemory F64 NaN bits changed for 0x${bits.toULong().toString(16)}", bits, reloadedBits)
        }
    }
}
