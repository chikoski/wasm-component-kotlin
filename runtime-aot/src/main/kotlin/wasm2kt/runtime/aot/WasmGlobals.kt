@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.runtime.aot

import wasm2kt.runtime.canonical.InternalWasm2KtApi

/** Mutable `i32` global cell shared across core modules when imported or exported. */
@InternalWasm2KtApi
class I32Global(@JvmField var value: Int)

/** Mutable `i64` global cell shared across core modules when imported or exported. */
@InternalWasm2KtApi
class I64Global(@JvmField var value: Long)

/** Mutable `f32` global cell shared across core modules when imported or exported. */
@InternalWasm2KtApi
class F32Global(@JvmField var value: Float)

/** Mutable `f64` global cell shared across core modules when imported or exported. */
@InternalWasm2KtApi
class F64Global(@JvmField var value: Double)

/** Mutable `funcref` global cell shared across core modules when imported or exported. */
@InternalWasm2KtApi
class FuncRefGlobal(@JvmField var value: FuncRef?)

/** Mutable `externref` global cell shared across core modules when imported or exported. */
@InternalWasm2KtApi
class ExternRefGlobal(@JvmField var value: Any?)
