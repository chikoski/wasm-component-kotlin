package wasm2kt.tooling.ir

/**
 * Typed Core Wasm instructions covering MVP, sign-extension, non-trapping float-to-int,
 * bulk-memory, reference-types, multi-value, multi-memory, extended-const, and mutable-globals.
 */
sealed interface WasmOp {
    val mnemonic: String

    // --- Control flow ---
    data object Unreachable : WasmOp {
        override val mnemonic: String get() = "unreachable"
    }

    data object Nop : WasmOp {
        override val mnemonic: String get() = "nop"
    }

    data class Block(
        val blockId: Int,
        val blockType: IrBlockType,
        val params: List<IrValType>,
        val results: List<IrValType>,
    ) : WasmOp {
        override val mnemonic: String get() = "block"
    }

    data class Loop(
        val blockId: Int,
        val blockType: IrBlockType,
        val params: List<IrValType>,
        val results: List<IrValType>,
    ) : WasmOp {
        override val mnemonic: String get() = "loop"
    }

    data class If(
        val blockId: Int,
        val blockType: IrBlockType,
        val params: List<IrValType>,
        val results: List<IrValType>,
    ) : WasmOp {
        override val mnemonic: String get() = "if"
    }

    data class Else(val blockId: Int) : WasmOp {
        override val mnemonic: String get() = "else"
    }

    data class End(
        val blockId: Int,
        val kind: IrBlockKind,
    ) : WasmOp {
        override val mnemonic: String get() = "end"
    }

    data class Br(val target: IrBranchTarget) : WasmOp {
        override val mnemonic: String get() = "br"
    }

    data class BrIf(val target: IrBranchTarget) : WasmOp {
        override val mnemonic: String get() = "br_if"
    }

    data class BrTable(
        val targets: List<IrBranchTarget>,
        val defaultTarget: IrBranchTarget,
    ) : WasmOp {
        override val mnemonic: String get() = "br_table"
    }

    data class Return(val resultTypes: List<IrValType>) : WasmOp {
        override val mnemonic: String get() = "return"
    }

    data class Call(
        val funcIndex: Int,
        val funcType: IrFuncType,
    ) : WasmOp {
        override val mnemonic: String get() = "call"
    }

    data class CallIndirect(
        val typeIndex: Int,
        val tableIndex: Int,
        val funcType: IrFuncType,
    ) : WasmOp {
        override val mnemonic: String get() = "call_indirect"
    }

    // --- Parametric ---
    data class Drop(val droppedType: IrStackType) : WasmOp {
        override val mnemonic: String get() = "drop"
    }

    data class Select(
        val explicitTypes: List<IrValType>,
        val resolvedType: IrStackType,
    ) : WasmOp {
        override val mnemonic: String get() = if (explicitTypes.isEmpty()) "select" else "select_t"
    }

    // --- Variables & References ---
    data class LocalGet(val localIndex: Int, val valType: IrValType) : WasmOp {
        override val mnemonic: String get() = "local.get"
    }

    data class LocalSet(val localIndex: Int, val valType: IrValType) : WasmOp {
        override val mnemonic: String get() = "local.set"
    }

    data class LocalTee(val localIndex: Int, val valType: IrValType) : WasmOp {
        override val mnemonic: String get() = "local.tee"
    }

    data class GlobalGet(val globalIndex: Int, val valType: IrValType) : WasmOp {
        override val mnemonic: String get() = "global.get"
    }

    data class GlobalSet(val globalIndex: Int, val valType: IrValType) : WasmOp {
        override val mnemonic: String get() = "global.set"
    }

    data class RefNull(val valType: IrValType) : WasmOp {
        override val mnemonic: String get() = "ref.null"
    }

    data class RefIsNull(val refType: IrStackType) : WasmOp {
        override val mnemonic: String get() = "ref.is_null"
    }

    data class RefFunc(val funcIndex: Int) : WasmOp {
        override val mnemonic: String get() = "ref.func"
    }

    // --- Table instructions ---
    data class TableGet(val tableIndex: Int, val elemType: IrValType) : WasmOp {
        override val mnemonic: String get() = "table.get"
    }

    data class TableSet(val tableIndex: Int, val elemType: IrValType) : WasmOp {
        override val mnemonic: String get() = "table.set"
    }

    data class TableInit(val elemIndex: Int, val tableIndex: Int, val elemType: IrValType) : WasmOp {
        override val mnemonic: String get() = "table.init"
    }

    data class ElemDrop(val elemIndex: Int) : WasmOp {
        override val mnemonic: String get() = "elem.drop"
    }

    data class TableCopy(val dstTableIndex: Int, val srcTableIndex: Int) : WasmOp {
        override val mnemonic: String get() = "table.copy"
    }

    data class TableGrow(val tableIndex: Int, val elemType: IrValType) : WasmOp {
        override val mnemonic: String get() = "table.grow"
    }

    data class TableSize(val tableIndex: Int) : WasmOp {
        override val mnemonic: String get() = "table.size"
    }

    data class TableFill(val tableIndex: Int, val elemType: IrValType) : WasmOp {
        override val mnemonic: String get() = "table.fill"
    }

    // --- Memory instructions ---
    data class Load(
        val kind: LoadKind,
        val memoryIndex: Int,
        val align: Int,
        val offset: Long,
    ) : WasmOp {
        override val mnemonic: String get() = kind.mnemonic
    }

    data class Store(
        val kind: StoreKind,
        val memoryIndex: Int,
        val align: Int,
        val offset: Long,
    ) : WasmOp {
        override val mnemonic: String get() = kind.mnemonic
    }

    data class MemorySize(val memoryIndex: Int) : WasmOp {
        override val mnemonic: String get() = "memory.size"
    }

    data class MemoryGrow(val memoryIndex: Int) : WasmOp {
        override val mnemonic: String get() = "memory.grow"
    }

    data class MemoryInit(val dataIndex: Int, val memoryIndex: Int) : WasmOp {
        override val mnemonic: String get() = "memory.init"
    }

    data class DataDrop(val dataIndex: Int) : WasmOp {
        override val mnemonic: String get() = "data.drop"
    }

    data class MemoryCopy(val dstMemoryIndex: Int, val srcMemoryIndex: Int) : WasmOp {
        override val mnemonic: String get() = "memory.copy"
    }

    data class MemoryFill(val memoryIndex: Int) : WasmOp {
        override val mnemonic: String get() = "memory.fill"
    }

    // --- Constants ---
    data class I32Const(val value: Int) : WasmOp {
        override val mnemonic: String get() = "i32.const"
    }

    data class I64Const(val value: Long) : WasmOp {
        override val mnemonic: String get() = "i64.const"
    }

    data class F32Const(val rawBits: Int) : WasmOp {
        override val mnemonic: String get() = "f32.const"
        fun toFloat(): Float = Float.fromBits(rawBits)
    }

    data class F64Const(val rawBits: Long) : WasmOp {
        override val mnemonic: String get() = "f64.const"
        fun toDouble(): Double = Double.fromBits(rawBits)
    }

    // --- Numeric unary / binary ---
    data class Unary(val op: UnaryOp) : WasmOp {
        override val mnemonic: String get() = op.mnemonic
    }

    data class Binary(val op: BinaryOp) : WasmOp {
        override val mnemonic: String get() = op.mnemonic
    }
}

/**
 * Linear memory load variant with result type and mnemonic.
 */
enum class LoadKind(
    val mnemonic: String,
    val resultType: IrValType,
) {
    I32("i32.load", IrValType.I32),
    I64("i64.load", IrValType.I64),
    F32("f32.load", IrValType.F32),
    F64("f64.load", IrValType.F64),
    I32_8S("i32.load8_s", IrValType.I32),
    I32_8U("i32.load8_u", IrValType.I32),
    I32_16S("i32.load16_s", IrValType.I32),
    I32_16U("i32.load16_u", IrValType.I32),
    I64_8S("i64.load8_s", IrValType.I64),
    I64_8U("i64.load8_u", IrValType.I64),
    I64_16S("i64.load16_s", IrValType.I64),
    I64_16U("i64.load16_u", IrValType.I64),
    I64_32S("i64.load32_s", IrValType.I64),
    I64_32U("i64.load32_u", IrValType.I64),
}

/**
 * Linear memory store variant with stored value type and mnemonic.
 */
enum class StoreKind(
    val mnemonic: String,
    val valueType: IrValType,
) {
    I32("i32.store", IrValType.I32),
    I64("i64.store", IrValType.I64),
    F32("f32.store", IrValType.F32),
    F64("f64.store", IrValType.F64),
    I32_8("i32.store8", IrValType.I32),
    I32_16("i32.store16", IrValType.I32),
    I64_8("i64.store8", IrValType.I64),
    I64_16("i64.store16", IrValType.I64),
    I64_32("i64.store32", IrValType.I64),
}

/**
 * Numeric unary, conversion, sign-extension, saturating truncation, and reinterpret operators.
 */
enum class UnaryOp(
    val mnemonic: String,
    val inputType: IrValType,
    val resultType: IrValType,
) {
    I32_EQZ("i32.eqz", IrValType.I32, IrValType.I32),
    I64_EQZ("i64.eqz", IrValType.I64, IrValType.I32),

    I32_CLZ("i32.clz", IrValType.I32, IrValType.I32),
    I32_CTZ("i32.ctz", IrValType.I32, IrValType.I32),
    I32_POPCNT("i32.popcnt", IrValType.I32, IrValType.I32),

    I64_CLZ("i64.clz", IrValType.I64, IrValType.I64),
    I64_CTZ("i64.ctz", IrValType.I64, IrValType.I64),
    I64_POPCNT("i64.popcnt", IrValType.I64, IrValType.I64),

    F32_ABS("f32.abs", IrValType.F32, IrValType.F32),
    F32_NEG("f32.neg", IrValType.F32, IrValType.F32),
    F32_CEIL("f32.ceil", IrValType.F32, IrValType.F32),
    F32_FLOOR("f32.floor", IrValType.F32, IrValType.F32),
    F32_TRUNC("f32.trunc", IrValType.F32, IrValType.F32),
    F32_NEAREST("f32.nearest", IrValType.F32, IrValType.F32),
    F32_SQRT("f32.sqrt", IrValType.F32, IrValType.F32),

    F64_ABS("f64.abs", IrValType.F64, IrValType.F64),
    F64_NEG("f64.neg", IrValType.F64, IrValType.F64),
    F64_CEIL("f64.ceil", IrValType.F64, IrValType.F64),
    F64_FLOOR("f64.floor", IrValType.F64, IrValType.F64),
    F64_TRUNC("f64.trunc", IrValType.F64, IrValType.F64),
    F64_NEAREST("f64.nearest", IrValType.F64, IrValType.F64),
    F64_SQRT("f64.sqrt", IrValType.F64, IrValType.F64),

    I32_WRAP_I64("i32.wrap_i64", IrValType.I64, IrValType.I32),
    I32_TRUNC_F32_S("i32.trunc_f32_s", IrValType.F32, IrValType.I32),
    I32_TRUNC_F32_U("i32.trunc_f32_u", IrValType.F32, IrValType.I32),
    I32_TRUNC_F64_S("i32.trunc_f64_s", IrValType.F64, IrValType.I32),
    I32_TRUNC_F64_U("i32.trunc_f64_u", IrValType.F64, IrValType.I32),

    I64_EXTEND_I32_S("i64.extend_i32_s", IrValType.I32, IrValType.I64),
    I64_EXTEND_I32_U("i64.extend_i32_u", IrValType.I32, IrValType.I64),
    I64_TRUNC_F32_S("i64.trunc_f32_s", IrValType.F32, IrValType.I64),
    I64_TRUNC_F32_U("i64.trunc_f32_u", IrValType.F32, IrValType.I64),
    I64_TRUNC_F64_S("i64.trunc_f64_s", IrValType.F64, IrValType.I64),
    I64_TRUNC_F64_U("i64.trunc_f64_u", IrValType.F64, IrValType.I64),

    F32_CONVERT_I32_S("f32.convert_i32_s", IrValType.I32, IrValType.F32),
    F32_CONVERT_I32_U("f32.convert_i32_u", IrValType.I32, IrValType.F32),
    F32_CONVERT_I64_S("f32.convert_i64_s", IrValType.I64, IrValType.F32),
    F32_CONVERT_I64_U("f32.convert_i64_u", IrValType.I64, IrValType.F32),
    F32_DEMOTE_F64("f32.demote_f64", IrValType.F64, IrValType.F32),

    F64_CONVERT_I32_S("f64.convert_i32_s", IrValType.I32, IrValType.F64),
    F64_CONVERT_I32_U("f64.convert_i32_u", IrValType.I32, IrValType.F64),
    F64_CONVERT_I64_S("f64.convert_i64_s", IrValType.I64, IrValType.F64),
    F64_CONVERT_I64_U("f64.convert_i64_u", IrValType.I64, IrValType.F64),
    F64_PROMOTE_F32("f64.promote_f32", IrValType.F32, IrValType.F64),

    I32_REINTERPRET_F32("i32.reinterpret_f32", IrValType.F32, IrValType.I32),
    I64_REINTERPRET_F64("i64.reinterpret_f64", IrValType.F64, IrValType.I64),
    F32_REINTERPRET_I32("f32.reinterpret_i32", IrValType.I32, IrValType.F32),
    F64_REINTERPRET_I64("f64.reinterpret_i64", IrValType.I64, IrValType.F64),

    I32_EXTEND_8_S("i32.extend8_s", IrValType.I32, IrValType.I32),
    I32_EXTEND_16_S("i32.extend16_s", IrValType.I32, IrValType.I32),
    I64_EXTEND_8_S("i64.extend8_s", IrValType.I64, IrValType.I64),
    I64_EXTEND_16_S("i64.extend16_s", IrValType.I64, IrValType.I64),
    I64_EXTEND_32_S("i64.extend32_s", IrValType.I64, IrValType.I64),

    I32_TRUNC_SAT_F32_S("i32.trunc_sat_f32_s", IrValType.F32, IrValType.I32),
    I32_TRUNC_SAT_F32_U("i32.trunc_sat_f32_u", IrValType.F32, IrValType.I32),
    I32_TRUNC_SAT_F64_S("i32.trunc_sat_f64_s", IrValType.F64, IrValType.I32),
    I32_TRUNC_SAT_F64_U("i32.trunc_sat_f64_u", IrValType.F64, IrValType.I32),
    I64_TRUNC_SAT_F32_S("i64.trunc_sat_f32_s", IrValType.F32, IrValType.I64),
    I64_TRUNC_SAT_F32_U("i64.trunc_sat_f32_u", IrValType.F32, IrValType.I64),
    I64_TRUNC_SAT_F64_S("i64.trunc_sat_f64_s", IrValType.F64, IrValType.I64),
    I64_TRUNC_SAT_F64_U("i64.trunc_sat_f64_u", IrValType.F64, IrValType.I64),
}

/**
 * Numeric binary arithmetic, bitwise, comparison, and IEEE-754 operators.
 */
enum class BinaryOp(
    val mnemonic: String,
    val operandType: IrValType,
    val resultType: IrValType,
) {
    I32_EQ("i32.eq", IrValType.I32, IrValType.I32),
    I32_NE("i32.ne", IrValType.I32, IrValType.I32),
    I32_LT_S("i32.lt_s", IrValType.I32, IrValType.I32),
    I32_LT_U("i32.lt_u", IrValType.I32, IrValType.I32),
    I32_GT_S("i32.gt_s", IrValType.I32, IrValType.I32),
    I32_GT_U("i32.gt_u", IrValType.I32, IrValType.I32),
    I32_LE_S("i32.le_s", IrValType.I32, IrValType.I32),
    I32_LE_U("i32.le_u", IrValType.I32, IrValType.I32),
    I32_GE_S("i32.ge_s", IrValType.I32, IrValType.I32),
    I32_GE_U("i32.ge_u", IrValType.I32, IrValType.I32),

    I64_EQ("i64.eq", IrValType.I64, IrValType.I32),
    I64_NE("i64.ne", IrValType.I64, IrValType.I32),
    I64_LT_S("i64.lt_s", IrValType.I64, IrValType.I32),
    I64_LT_U("i64.lt_u", IrValType.I64, IrValType.I32),
    I64_GT_S("i64.gt_s", IrValType.I64, IrValType.I32),
    I64_GT_U("i64.gt_u", IrValType.I64, IrValType.I32),
    I64_LE_S("i64.le_s", IrValType.I64, IrValType.I32),
    I64_LE_U("i64.le_u", IrValType.I64, IrValType.I32),
    I64_GE_S("i64.ge_s", IrValType.I64, IrValType.I32),
    I64_GE_U("i64.ge_u", IrValType.I64, IrValType.I32),

    F32_EQ("f32.eq", IrValType.F32, IrValType.I32),
    F32_NE("f32.ne", IrValType.F32, IrValType.I32),
    F32_LT("f32.lt", IrValType.F32, IrValType.I32),
    F32_GT("f32.gt", IrValType.F32, IrValType.I32),
    F32_LE("f32.le", IrValType.F32, IrValType.I32),
    F32_GE("f32.ge", IrValType.F32, IrValType.I32),

    F64_EQ("f64.eq", IrValType.F64, IrValType.I32),
    F64_NE("f64.ne", IrValType.F64, IrValType.I32),
    F64_LT("f64.lt", IrValType.F64, IrValType.I32),
    F64_GT("f64.gt", IrValType.F64, IrValType.I32),
    F64_LE("f64.le", IrValType.F64, IrValType.I32),
    F64_GE("f64.ge", IrValType.F64, IrValType.I32),

    I32_ADD("i32.add", IrValType.I32, IrValType.I32),
    I32_SUB("i32.sub", IrValType.I32, IrValType.I32),
    I32_MUL("i32.mul", IrValType.I32, IrValType.I32),
    I32_DIV_S("i32.div_s", IrValType.I32, IrValType.I32),
    I32_DIV_U("i32.div_u", IrValType.I32, IrValType.I32),
    I32_REM_S("i32.rem_s", IrValType.I32, IrValType.I32),
    I32_REM_U("i32.rem_u", IrValType.I32, IrValType.I32),
    I32_AND("i32.and", IrValType.I32, IrValType.I32),
    I32_OR("i32.or", IrValType.I32, IrValType.I32),
    I32_XOR("i32.xor", IrValType.I32, IrValType.I32),
    I32_SHL("i32.shl", IrValType.I32, IrValType.I32),
    I32_SHR_S("i32.shr_s", IrValType.I32, IrValType.I32),
    I32_SHR_U("i32.shr_u", IrValType.I32, IrValType.I32),
    I32_ROTL("i32.rotl", IrValType.I32, IrValType.I32),
    I32_ROTR("i32.rotr", IrValType.I32, IrValType.I32),

    I64_ADD("i64.add", IrValType.I64, IrValType.I64),
    I64_SUB("i64.sub", IrValType.I64, IrValType.I64),
    I64_MUL("i64.mul", IrValType.I64, IrValType.I64),
    I64_DIV_S("i64.div_s", IrValType.I64, IrValType.I64),
    I64_DIV_U("i64.div_u", IrValType.I64, IrValType.I64),
    I64_REM_S("i64.rem_s", IrValType.I64, IrValType.I64),
    I64_REM_U("i64.rem_u", IrValType.I64, IrValType.I64),
    I64_AND("i64.and", IrValType.I64, IrValType.I64),
    I64_OR("i64.or", IrValType.I64, IrValType.I64),
    I64_XOR("i64.xor", IrValType.I64, IrValType.I64),
    I64_SHL("i64.shl", IrValType.I64, IrValType.I64),
    I64_SHR_S("i64.shr_s", IrValType.I64, IrValType.I64),
    I64_SHR_U("i64.shr_u", IrValType.I64, IrValType.I64),
    I64_ROTL("i64.rotl", IrValType.I64, IrValType.I64),
    I64_ROTR("i64.rotr", IrValType.I64, IrValType.I64),

    F32_ADD("f32.add", IrValType.F32, IrValType.F32),
    F32_SUB("f32.sub", IrValType.F32, IrValType.F32),
    F32_MUL("f32.mul", IrValType.F32, IrValType.F32),
    F32_DIV("f32.div", IrValType.F32, IrValType.F32),
    F32_MIN("f32.min", IrValType.F32, IrValType.F32),
    F32_MAX("f32.max", IrValType.F32, IrValType.F32),
    F32_COPYSIGN("f32.copysign", IrValType.F32, IrValType.F32),

    F64_ADD("f64.add", IrValType.F64, IrValType.F64),
    F64_SUB("f64.sub", IrValType.F64, IrValType.F64),
    F64_MUL("f64.mul", IrValType.F64, IrValType.F64),
    F64_DIV("f64.div", IrValType.F64, IrValType.F64),
    F64_MIN("f64.min", IrValType.F64, IrValType.F64),
    F64_MAX("f64.max", IrValType.F64, IrValType.F64),
    F64_COPYSIGN("f64.copysign", IrValType.F64, IrValType.F64),
}
