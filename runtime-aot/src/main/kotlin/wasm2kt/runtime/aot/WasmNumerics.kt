@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.runtime.aot

import wasm2kt.runtime.canonical.InternalWasm2KtApi
import wasm2kt.runtime.canonical.trap

/**
 * Core WebAssembly numeric helpers where JVM bytecode semantics differ from Wasm (zero division,
 * signed `MIN_VALUE / -1`, trapping vs saturating `trunc`, unsigned conversions, float `min`/`max`/
 * `nearest`/`ceil`/`floor`/`trunc`/`sqrt`/`abs`/`neg`/`copysign`, and bit-preserving `reinterpret`).
 */
@InternalWasm2KtApi
object WasmNumerics {
    // ---------------------------------------------------------------- integer division & remainder

    @JvmStatic
    fun i32DivS(a: Int, b: Int): Int {
        if (b == 0) trap("integer divide by zero")
        if (a == Int.MIN_VALUE && b == -1) trap("integer overflow")
        return a / b
    }

    @JvmStatic
    fun i32DivU(a: Int, b: Int): Int {
        if (b == 0) trap("integer divide by zero")
        return Integer.divideUnsigned(a, b)
    }

    @JvmStatic
    fun i32RemS(a: Int, b: Int): Int {
        if (b == 0) trap("integer divide by zero")
        return a % b
    }

    @JvmStatic
    fun i32RemU(a: Int, b: Int): Int {
        if (b == 0) trap("integer divide by zero")
        return Integer.remainderUnsigned(a, b)
    }

    @JvmStatic
    fun i64DivS(a: Long, b: Long): Long {
        if (b == 0L) trap("integer divide by zero")
        if (a == Long.MIN_VALUE && b == -1L) trap("integer overflow")
        return a / b
    }

    @JvmStatic
    fun i64DivU(a: Long, b: Long): Long {
        if (b == 0L) trap("integer divide by zero")
        return java.lang.Long.divideUnsigned(a, b)
    }

    @JvmStatic
    fun i64RemS(a: Long, b: Long): Long {
        if (b == 0L) trap("integer divide by zero")
        return a % b
    }

    @JvmStatic
    fun i64RemU(a: Long, b: Long): Long {
        if (b == 0L) trap("integer divide by zero")
        return java.lang.Long.remainderUnsigned(a, b)
    }

    // ---------------------------------------------------------------- trapping truncations

    @JvmStatic
    fun i32TruncF32S(x: Float): Int {
        if (x.isNaN()) trap("invalid conversion to integer")
        if (x < -2147483648.0f || x >= 2147483648.0f) trap("integer overflow")
        return x.toInt()
    }

    @JvmStatic
    fun i32TruncF32U(x: Float): Int {
        if (x.isNaN()) trap("invalid conversion to integer")
        if (x <= -1.0f || x >= 4294967296.0f) trap("integer overflow")
        return x.toLong().toInt()
    }

    @JvmStatic
    fun i32TruncF64S(x: Double): Int {
        if (x.isNaN()) trap("invalid conversion to integer")
        if (x <= -2147483649.0 || x >= 2147483648.0) trap("integer overflow")
        return x.toInt()
    }

    @JvmStatic
    fun i32TruncF64U(x: Double): Int {
        if (x.isNaN()) trap("invalid conversion to integer")
        if (x <= -1.0 || x >= 4294967296.0) trap("integer overflow")
        return x.toLong().toInt()
    }

    @JvmStatic
    fun i64TruncF32S(x: Float): Long {
        if (x.isNaN()) trap("invalid conversion to integer")
        if (x < -9223372036854775808.0f || x >= 9223372036854775808.0f) trap("integer overflow")
        return x.toLong()
    }

    @JvmStatic
    fun i64TruncF32U(x: Float): Long {
        if (x.isNaN()) trap("invalid conversion to integer")
        if (x <= -1.0f || x >= 18446744073709551616.0f) trap("integer overflow")
        return if (x < 9223372036854775808.0f) {
            x.toLong()
        } else {
            (x - 9223372036854775808.0f).toLong() xor Long.MIN_VALUE
        }
    }

    @JvmStatic
    fun i64TruncF64S(x: Double): Long {
        if (x.isNaN()) trap("invalid conversion to integer")
        if (x < -9223372036854775808.0 || x >= 9223372036854775808.0) trap("integer overflow")
        return x.toLong()
    }

    @JvmStatic
    fun i64TruncF64U(x: Double): Long {
        if (x.isNaN()) trap("invalid conversion to integer")
        if (x <= -1.0 || x >= 18446744073709551616.0) trap("integer overflow")
        return if (x < 9223372036854775808.0) {
            x.toLong()
        } else {
            (x - 9223372036854775808.0).toLong() xor Long.MIN_VALUE
        }
    }

    // ---------------------------------------------------------------- saturating truncations

    @JvmStatic
    fun i32TruncSatF32S(x: Float): Int = x.toInt()

    @JvmStatic
    fun i32TruncSatF32U(x: Float): Int = when {
        x.isNaN() || x <= 0.0f -> 0
        x >= 4294967296.0f -> -1
        else -> x.toLong().toInt()
    }

    @JvmStatic
    fun i32TruncSatF64S(x: Double): Int = x.toInt()

    @JvmStatic
    fun i32TruncSatF64U(x: Double): Int = when {
        x.isNaN() || x <= 0.0 -> 0
        x >= 4294967296.0 -> -1
        else -> x.toLong().toInt()
    }

    @JvmStatic
    fun i64TruncSatF32S(x: Float): Long = x.toLong()

    @JvmStatic
    fun i64TruncSatF32U(x: Float): Long = when {
        x.isNaN() || x <= 0.0f -> 0L
        x >= 18446744073709551616.0f -> -1L
        x < 9223372036854775808.0f -> x.toLong()
        else -> (x - 9223372036854775808.0f).toLong() xor Long.MIN_VALUE
    }

    @JvmStatic
    fun i64TruncSatF64S(x: Double): Long = x.toLong()

    @JvmStatic
    fun i64TruncSatF64U(x: Double): Long = when {
        x.isNaN() || x <= 0.0 -> 0L
        x >= 18446744073709551616.0 -> -1L
        x < 9223372036854775808.0 -> x.toLong()
        else -> (x - 9223372036854775808.0).toLong() xor Long.MIN_VALUE
    }

    // ---------------------------------------------------------------- unsigned conversions & float semantics

    @JvmStatic
    fun f32ConvertI32U(x: Int): Float = (x.toLong() and 0xFFFF_FFFFL).toFloat()

    @JvmStatic
    fun f64ConvertI32U(x: Int): Double = (x.toLong() and 0xFFFF_FFFFL).toDouble()

    @JvmStatic
    fun f32ConvertI64U(x: Long): Float =
        if (x >= 0L) x.toFloat() else ((x ushr 1) or (x and 1L)).toFloat() * 2.0f

    @JvmStatic
    fun f64ConvertI64U(x: Long): Double =
        if (x >= 0L) x.toDouble() else ((x ushr 1) or (x and 1L)).toDouble() * 2.0

    @JvmStatic
    fun f32Abs(x: Float): Float =
        java.lang.Float.intBitsToFloat(java.lang.Float.floatToRawIntBits(x) and 0x7FFF_FFFF)

    @JvmStatic
    fun f32Neg(x: Float): Float =
        java.lang.Float.intBitsToFloat(java.lang.Float.floatToRawIntBits(x) xor Int.MIN_VALUE)

    @JvmStatic
    fun f32Copysign(a: Float, b: Float): Float =
        java.lang.Float.intBitsToFloat(
            (java.lang.Float.floatToRawIntBits(a) and 0x7FFF_FFFF) or
                (java.lang.Float.floatToRawIntBits(b) and Int.MIN_VALUE),
        )

    @JvmStatic
    fun f32Ceil(x: Float): Float =
        if (x.isNaN()) Float.NaN else Math.ceil(x.toDouble()).toFloat()

    @JvmStatic
    fun f32Floor(x: Float): Float =
        if (x.isNaN()) Float.NaN else Math.floor(x.toDouble()).toFloat()

    @JvmStatic
    fun f32Trunc(x: Float): Float = when {
        x.isNaN() -> Float.NaN
        x == 0.0f -> x
        x < 0.0f -> Math.ceil(x.toDouble()).toFloat()
        else -> Math.floor(x.toDouble()).toFloat()
    }

    @JvmStatic
    fun f32Sqrt(x: Float): Float =
        if (x.isNaN()) Float.NaN else Math.sqrt(x.toDouble()).toFloat()

    @JvmStatic
    fun f64Abs(x: Double): Double =
        java.lang.Double.longBitsToDouble(java.lang.Double.doubleToRawLongBits(x) and Long.MAX_VALUE)

    @JvmStatic
    fun f64Neg(x: Double): Double =
        java.lang.Double.longBitsToDouble(java.lang.Double.doubleToRawLongBits(x) xor Long.MIN_VALUE)

    @JvmStatic
    fun f64Copysign(a: Double, b: Double): Double =
        java.lang.Double.longBitsToDouble(
            (java.lang.Double.doubleToRawLongBits(a) and Long.MAX_VALUE) or
                (java.lang.Double.doubleToRawLongBits(b) and Long.MIN_VALUE),
        )

    @JvmStatic
    fun f64Ceil(x: Double): Double =
        if (x.isNaN()) Double.NaN else Math.ceil(x)

    @JvmStatic
    fun f64Floor(x: Double): Double =
        if (x.isNaN()) Double.NaN else Math.floor(x)

    @JvmStatic
    fun f64Trunc(x: Double): Double = when {
        x.isNaN() -> Double.NaN
        x == 0.0 -> x
        x < 0.0 -> Math.ceil(x)
        else -> Math.floor(x)
    }

    @JvmStatic
    fun f64Sqrt(x: Double): Double =
        if (x.isNaN()) Double.NaN else Math.sqrt(x)

    @JvmStatic
    fun f32Min(a: Float, b: Float): Float =
        if (a.isNaN() || b.isNaN()) Float.NaN else Math.min(a, b)

    @JvmStatic
    fun f32Max(a: Float, b: Float): Float =
        if (a.isNaN() || b.isNaN()) Float.NaN else Math.max(a, b)

    @JvmStatic
    fun f64Min(a: Double, b: Double): Double =
        if (a.isNaN() || b.isNaN()) Double.NaN else Math.min(a, b)

    @JvmStatic
    fun f64Max(a: Double, b: Double): Double =
        if (a.isNaN() || b.isNaN()) Double.NaN else Math.max(a, b)

    @JvmStatic
    fun f32Nearest(x: Float): Float =
        if (x.isNaN()) Float.NaN else Math.rint(x.toDouble()).toFloat()

    @JvmStatic
    fun f64Nearest(x: Double): Double =
        if (x.isNaN()) Double.NaN else Math.rint(x)

    @JvmStatic
    fun f32ReinterpretI32(x: Int): Float = java.lang.Float.intBitsToFloat(x)

    @JvmStatic
    fun i32ReinterpretF32(x: Float): Int = java.lang.Float.floatToRawIntBits(x)

    @JvmStatic
    fun f64ReinterpretI64(x: Long): Double = java.lang.Double.longBitsToDouble(x)

    @JvmStatic
    fun i64ReinterpretF64(x: Double): Long = java.lang.Double.doubleToRawLongBits(x)

    // ---------------------------------------------------------------- bitwise & sign extension

    @JvmStatic
    fun i32Rotl(x: Int, shift: Int): Int = Integer.rotateLeft(x, shift)

    @JvmStatic
    fun i32Rotr(x: Int, shift: Int): Int = Integer.rotateRight(x, shift)

    @JvmStatic
    fun i64Rotl(x: Long, shift: Int): Long = java.lang.Long.rotateLeft(x, shift)

    @JvmStatic
    fun i64Rotr(x: Long, shift: Int): Long = java.lang.Long.rotateRight(x, shift)

    @JvmStatic
    fun i32Clz(x: Int): Int = Integer.numberOfLeadingZeros(x)

    @JvmStatic
    fun i32Ctz(x: Int): Int = Integer.numberOfTrailingZeros(x)

    @JvmStatic
    fun i32Popcnt(x: Int): Int = Integer.bitCount(x)

    @JvmStatic
    fun i64Clz(x: Long): Long = java.lang.Long.numberOfLeadingZeros(x).toLong()

    @JvmStatic
    fun i64Ctz(x: Long): Long = java.lang.Long.numberOfTrailingZeros(x).toLong()

    @JvmStatic
    fun i64Popcnt(x: Long): Long = java.lang.Long.bitCount(x).toLong()

    @JvmStatic
    fun i32Extend8S(x: Int): Int = x.toByte().toInt()

    @JvmStatic
    fun i32Extend16S(x: Int): Int = x.toShort().toInt()

    @JvmStatic
    fun i64Extend8S(x: Long): Long = x.toByte().toLong()

    @JvmStatic
    fun i64Extend16S(x: Long): Long = x.toShort().toLong()

    @JvmStatic
    fun i64Extend32S(x: Long): Long = x.toInt().toLong()
}
