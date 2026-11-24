@file:OptIn(InternalWasm2KtApi::class)

package wasm2kt.runtime.aot

import wasm2kt.runtime.canonical.InternalWasm2KtApi

/**
 * Static bytecode helpers whose parameter order matches the Wasm operand stack order with the
 * target memory/table/global appended last, eliminating operand-shuffling scratch locals in emitted
 * JVM bytecode.
 */
@InternalWasm2KtApi
object AotOps {
    // ---------------------------------------------------------------- memory loads (offset != 0 and == 0)

    @JvmStatic fun loadI32(addr: Int, mem: LinearMemory): Int = mem.loadI32(addr, 0)
    @JvmStatic fun loadI32(addr: Int, mem: LinearMemory, offset: Int): Int = mem.loadI32(addr, offset)

    @JvmStatic fun loadI64(addr: Int, mem: LinearMemory): Long = mem.loadI64(addr, 0)
    @JvmStatic fun loadI64(addr: Int, mem: LinearMemory, offset: Int): Long = mem.loadI64(addr, offset)

    @JvmStatic fun loadF32(addr: Int, mem: LinearMemory): Float = mem.loadF32(addr, 0)
    @JvmStatic fun loadF32(addr: Int, mem: LinearMemory, offset: Int): Float = mem.loadF32(addr, offset)

    @JvmStatic fun loadF64(addr: Int, mem: LinearMemory): Double = mem.loadF64(addr, 0)
    @JvmStatic fun loadF64(addr: Int, mem: LinearMemory, offset: Int): Double = mem.loadF64(addr, offset)

    @JvmStatic fun loadI32_8s(addr: Int, mem: LinearMemory): Int = mem.loadI32_8s(addr, 0)
    @JvmStatic fun loadI32_8s(addr: Int, mem: LinearMemory, offset: Int): Int = mem.loadI32_8s(addr, offset)

    @JvmStatic fun loadI32_8u(addr: Int, mem: LinearMemory): Int = mem.loadI32_8u(addr, 0)
    @JvmStatic fun loadI32_8u(addr: Int, mem: LinearMemory, offset: Int): Int = mem.loadI32_8u(addr, offset)

    @JvmStatic fun loadI32_16s(addr: Int, mem: LinearMemory): Int = mem.loadI32_16s(addr, 0)
    @JvmStatic fun loadI32_16s(addr: Int, mem: LinearMemory, offset: Int): Int = mem.loadI32_16s(addr, offset)

    @JvmStatic fun loadI32_16u(addr: Int, mem: LinearMemory): Int = mem.loadI32_16u(addr, 0)
    @JvmStatic fun loadI32_16u(addr: Int, mem: LinearMemory, offset: Int): Int = mem.loadI32_16u(addr, offset)

    @JvmStatic fun loadI64_8s(addr: Int, mem: LinearMemory): Long = mem.loadI64_8s(addr, 0)
    @JvmStatic fun loadI64_8s(addr: Int, mem: LinearMemory, offset: Int): Long = mem.loadI64_8s(addr, offset)

    @JvmStatic fun loadI64_8u(addr: Int, mem: LinearMemory): Long = mem.loadI64_8u(addr, 0)
    @JvmStatic fun loadI64_8u(addr: Int, mem: LinearMemory, offset: Int): Long = mem.loadI64_8u(addr, offset)

    @JvmStatic fun loadI64_16s(addr: Int, mem: LinearMemory): Long = mem.loadI64_16s(addr, 0)
    @JvmStatic fun loadI64_16s(addr: Int, mem: LinearMemory, offset: Int): Long = mem.loadI64_16s(addr, offset)

    @JvmStatic fun loadI64_16u(addr: Int, mem: LinearMemory): Long = mem.loadI64_16u(addr, 0)
    @JvmStatic fun loadI64_16u(addr: Int, mem: LinearMemory, offset: Int): Long = mem.loadI64_16u(addr, offset)

    @JvmStatic fun loadI64_32s(addr: Int, mem: LinearMemory): Long = mem.loadI64_32s(addr, 0)
    @JvmStatic fun loadI64_32s(addr: Int, mem: LinearMemory, offset: Int): Long = mem.loadI64_32s(addr, offset)

    @JvmStatic fun loadI64_32u(addr: Int, mem: LinearMemory): Long = mem.loadI64_32u(addr, 0)
    @JvmStatic fun loadI64_32u(addr: Int, mem: LinearMemory, offset: Int): Long = mem.loadI64_32u(addr, offset)

    // ---------------------------------------------------------------- memory stores

    @JvmStatic fun storeI32(addr: Int, value: Int, mem: LinearMemory) = mem.storeI32(addr, 0, value)
    @JvmStatic fun storeI32(addr: Int, value: Int, mem: LinearMemory, offset: Int) = mem.storeI32(addr, offset, value)

    @JvmStatic fun storeI64(addr: Int, value: Long, mem: LinearMemory) = mem.storeI64(addr, 0, value)
    @JvmStatic fun storeI64(addr: Int, value: Long, mem: LinearMemory, offset: Int) = mem.storeI64(addr, offset, value)

    @JvmStatic fun storeF32(addr: Int, value: Float, mem: LinearMemory) = mem.storeF32(addr, 0, value)
    @JvmStatic fun storeF32(addr: Int, value: Float, mem: LinearMemory, offset: Int) = mem.storeF32(addr, offset, value)

    @JvmStatic fun storeF64(addr: Int, value: Double, mem: LinearMemory) = mem.storeF64(addr, 0, value)
    @JvmStatic fun storeF64(addr: Int, value: Double, mem: LinearMemory, offset: Int) = mem.storeF64(addr, offset, value)

    @JvmStatic fun storeI32_8(addr: Int, value: Int, mem: LinearMemory) = mem.storeI32_8(addr, 0, value)
    @JvmStatic fun storeI32_8(addr: Int, value: Int, mem: LinearMemory, offset: Int) = mem.storeI32_8(addr, offset, value)

    @JvmStatic fun storeI32_16(addr: Int, value: Int, mem: LinearMemory) = mem.storeI32_16(addr, 0, value)
    @JvmStatic fun storeI32_16(addr: Int, value: Int, mem: LinearMemory, offset: Int) = mem.storeI32_16(addr, offset, value)

    @JvmStatic fun storeI64_8(addr: Int, value: Long, mem: LinearMemory) = mem.storeI64_8(addr, 0, value)
    @JvmStatic fun storeI64_8(addr: Int, value: Long, mem: LinearMemory, offset: Int) = mem.storeI64_8(addr, offset, value)

    @JvmStatic fun storeI64_16(addr: Int, value: Long, mem: LinearMemory) = mem.storeI64_16(addr, 0, value)
    @JvmStatic fun storeI64_16(addr: Int, value: Long, mem: LinearMemory, offset: Int) = mem.storeI64_16(addr, offset, value)

    @JvmStatic fun storeI64_32(addr: Int, value: Long, mem: LinearMemory) = mem.storeI64_32(addr, 0, value)
    @JvmStatic fun storeI64_32(addr: Int, value: Long, mem: LinearMemory, offset: Int) = mem.storeI64_32(addr, offset, value)

    // ---------------------------------------------------------------- bulk memory & memory grow

    @JvmStatic
    fun memoryGrow(delta: Int, mem: LinearMemory): Int = mem.grow(delta)

    @JvmStatic
    fun memoryCopy(dst: Int, src: Int, len: Int, dstMem: LinearMemory, srcMem: LinearMemory) {
        if (dstMem === srcMem) {
            dstMem.copy(dst, src, len)
        } else {
            dstMem.copyFrom(srcMem, dst, src, len)
        }
    }

    @JvmStatic
    fun memoryFill(dst: Int, value: Int, len: Int, mem: LinearMemory) {
        mem.fill(dst, value, len)
    }

    @JvmStatic
    fun memoryInit(dst: Int, src: Int, len: Int, store: DataSegmentStore, segmentIndex: Int, mem: LinearMemory) {
        store.init(mem, segmentIndex, dst, src, len)
    }

    // ---------------------------------------------------------------- tables

    @JvmStatic
    fun tableGetFuncRef(index: Int, table: WasmTable<FuncRef>): FuncRef? = table.get(index)

    @JvmStatic
    fun tableGetExternRef(index: Int, table: WasmTable<Any>): Any? = table.get(index)

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun tableSet(index: Int, value: Any?, table: WasmTable<*>) {
        (table as WasmTable<Any>).set(index, value)
    }

    /** Wasm stack order puts [initValue] before [delta]; [WasmTable.grow] takes `(delta, fillValue)`. */
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun tableGrow(initValue: Any?, delta: Int, table: WasmTable<*>): Int =
        (table as WasmTable<Any>).grow(delta, initValue)

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun tableFill(dst: Int, value: Any?, len: Int, table: WasmTable<*>) {
        (table as WasmTable<Any>).fill(dst, value, len)
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun tableCopy(dst: Int, src: Int, len: Int, dstTable: WasmTable<*>, srcTable: WasmTable<*>) {
        (dstTable as WasmTable<Any>).copyFrom(srcTable as WasmTable<Any>, dst, src, len)
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun tableInit(dst: Int, src: Int, len: Int, store: ElemSegmentStore<*>, segmentIndex: Int, table: WasmTable<*>) {
        (store as ElemSegmentStore<Any>).init(table as WasmTable<Any>, segmentIndex, dst, src, len)
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun callIndirectLookup(index: Int, expectedSignature: String, table: WasmTable<FuncRef>): FuncRef =
        table.getChecked(index, expectedSignature)

    // ---------------------------------------------------------------- shared globals

    @JvmStatic fun setI32Global(value: Int, global: I32Global) { global.value = value }
    @JvmStatic fun setI64Global(value: Long, global: I64Global) { global.value = value }
    @JvmStatic fun setF32Global(value: Float, global: F32Global) { global.value = value }
    @JvmStatic fun setF64Global(value: Double, global: F64Global) { global.value = value }
    @JvmStatic fun setFuncRefGlobal(value: FuncRef?, global: FuncRefGlobal) { global.value = value }
    @JvmStatic fun setExternRefGlobal(value: Any?, global: ExternRefGlobal) { global.value = value }
}
