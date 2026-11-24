@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.runtime.aot

import wasm2kt.runtime.canonical.InternalWasm2KtApi

/**
 * Reusable primitive/reference spill holder owned by a compiled core module instance when pre-split
 * helper methods or wide parameter lists exceed the JVM 255-slot parameter ceiling or return
 * multiple live-out variables.
 *
 * Callers write overflow inputs immediately before invoking a helper and read live-outs/results
 * immediately upon return, avoiding per-call `long[]` allocations.
 */
@InternalWasm2KtApi
class AotSpillFrame(
    i32Capacity: Int,
    i64Capacity: Int,
    f32Capacity: Int,
    f64Capacity: Int,
    refCapacity: Int,
) {
    @JvmField
    val i32: IntArray = if (i32Capacity > 0) IntArray(i32Capacity) else EMPTY_INT

    @JvmField
    val i64: LongArray = if (i64Capacity > 0) LongArray(i64Capacity) else EMPTY_LONG

    @JvmField
    val f32: FloatArray = if (f32Capacity > 0) FloatArray(f32Capacity) else EMPTY_FLOAT

    @JvmField
    val f64: DoubleArray = if (f64Capacity > 0) DoubleArray(f64Capacity) else EMPTY_DOUBLE

    @JvmField
    val refs: Array<Any?> = if (refCapacity > 0) arrayOfNulls<Any?>(refCapacity) else EMPTY_REF

    private companion object {
        val EMPTY_INT = IntArray(0)
        val EMPTY_LONG = LongArray(0)
        val EMPTY_FLOAT = FloatArray(0)
        val EMPTY_DOUBLE = DoubleArray(0)
        val EMPTY_REF = emptyArray<Any?>()
    }
}
