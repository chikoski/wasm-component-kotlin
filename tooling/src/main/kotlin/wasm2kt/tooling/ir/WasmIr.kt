package wasm2kt.tooling.ir

/**
 * Scalar and reference value types supported by the Core Wasm typed IR.
 */
enum class IrValType(
    val mnemonic: String,
    val signatureCode: Char,
) {
    I32("i32", 'I'),
    I64("i64", 'J'),
    F32("f32", 'F'),
    F64("f64", 'D'),
    FUNCREF("funcref", 'R'),
    EXTERNREF("externref", 'E'),
}

/**
 * Stack element type carried in [IrTypedInsn.stackBefore] and [IrTypedInsn.stackAfter].
 */
sealed interface IrStackType {
    val mnemonic: String

    /** Concrete known value type. */
    data class Concrete(val valType: IrValType) : IrStackType {
        override val mnemonic: String get() = valType.mnemonic
    }

    /** Polymorphic bottom value produced inside unreachable stack regions. */
    data object Unknown : IrStackType {
        override val mnemonic: String get() = "?"
    }
}

/**
 * Mutability of a Core Wasm global variable.
 */
enum class IrMutability(val mnemonic: String) {
    CONST("const"),
    VAR("var"),
}

/**
 * Structural function signature in the Core Wasm type section.
 */
data class IrFuncType(
    val params: List<IrValType>,
    val results: List<IrValType>,
) {
    /**
     * Compact textual key encoding parameter and result value kinds using [IrValType.signatureCode]
     * (not a raw JVM method descriptor; used for signature matching and diagnostics).
     */
    val compactSignature: String = buildString {
        append('(')
        for (p in params) append(p.signatureCode)
        append(')')
        when (results.size) {
            0 -> append('V')
            1 -> append(results[0].signatureCode)
            else -> for (r in results) append(r.signatureCode)
        }
    }
}

/**
 * Kind of structured Core Wasm control block.
 */
enum class IrBlockKind(val mnemonic: String) {
    FUNCTION("func"),
    BLOCK("block"),
    LOOP("loop"),
    IF("if"),
}

/**
 * Syntactic blocktype immediate encoded on `block`, `loop`, and `if`.
 */
sealed interface IrBlockType {
    data object Empty : IrBlockType
    data class Value(val type: IrValType) : IrBlockType
    data class TypeRef(val typeIndex: Int) : IrBlockType
}

/**
 * Control-block descriptor with resolved signature and instruction bounds.
 */
data class IrBlockInfo(
    val id: Int,
    val kind: IrBlockKind,
    val blockType: IrBlockType,
    val params: List<IrValType>,
    val results: List<IrValType>,
    val branchLabelTypes: List<IrValType>,
    val startInsnIndex: Int,
    val elseInsnIndex: Int?,
    val endInsnIndex: Int,
)

/**
 * Resolved branch target referenced by `br`, `br_if`, and `br_table`.
 */
data class IrBranchTarget(
    val depth: Int,
    val targetBlockId: Int,
    val targetKind: IrBlockKind,
    val labelTypes: List<IrValType>,
    val unwindHeight: Int,
)

/**
 * Limits for a table or linear memory (32-bit address space).
 */
data class IrLimits(
    val min: Long,
    val max: Long?,
)

/**
 * Descriptor of a Core Wasm table.
 */
data class IrTableType(
    val elementType: IrValType,
    val limits: IrLimits,
)

/**
 * Descriptor of a Core Wasm 32-bit linear memory.
 */
data class IrMemoryType(
    val initialPages: Int,
    val maxPages: Int?,
)

/**
 * Descriptor of a Core Wasm global.
 */
data class IrGlobalType(
    val valueType: IrValType,
    val mutability: IrMutability,
)

/**
 * Imported entity occupying the head of its respective module index space.
 */
sealed interface IrImport {
    val module: String
    val name: String

    data class Func(
        override val module: String,
        override val name: String,
        val typeIndex: Int,
        val funcIndex: Int,
    ) : IrImport

    data class Table(
        override val module: String,
        override val name: String,
        val tableType: IrTableType,
        val tableIndex: Int,
    ) : IrImport

    data class Memory(
        override val module: String,
        override val name: String,
        val memoryType: IrMemoryType,
        val memoryIndex: Int,
    ) : IrImport

    data class Global(
        override val module: String,
        override val name: String,
        val globalType: IrGlobalType,
        val globalIndex: Int,
    ) : IrImport
}

/**
 * External kind of an exported module entity.
 */
enum class IrExportKind(val mnemonic: String) {
    FUNCTION("func"),
    TABLE("table"),
    MEMORY("memory"),
    GLOBAL("global"),
}

/**
 * Exported entity descriptor.
 */
data class IrExport(
    val name: String,
    val kind: IrExportKind,
    val index: Int,
)

/**
 * Single constant-expression operator supported by MVP, reference-types, and extended-const.
 */
sealed interface IrConstOp {
    data class I32Const(val value: Int) : IrConstOp
    data class I64Const(val value: Long) : IrConstOp
    data class F32Const(val rawBits: Int) : IrConstOp
    data class F64Const(val rawBits: Long) : IrConstOp
    data class GlobalGet(val globalIndex: Int, val valType: IrValType) : IrConstOp
    data class RefNull(val valType: IrValType) : IrConstOp
    data class RefFunc(val funcIndex: Int) : IrConstOp
    data object I32Add : IrConstOp
    data object I32Sub : IrConstOp
    data object I32Mul : IrConstOp
    data object I64Add : IrConstOp
    data object I64Sub : IrConstOp
    data object I64Mul : IrConstOp
}

/**
 * Typed constant initializer expression used in globals, active data/element offsets, and element items.
 */
data class IrConstExpr(
    val ops: List<IrConstOp>,
    val resultType: IrValType,
)

/**
 * Defined Core Wasm global.
 */
data class IrGlobal(
    val index: Int,
    val type: IrGlobalType,
    val initExpr: IrConstExpr,
    val debugName: String? = null,
)

/**
 * Defined Core Wasm table.
 */
data class IrTable(
    val index: Int,
    val type: IrTableType,
    val debugName: String? = null,
)

/**
 * Defined Core Wasm linear memory.
 */
data class IrMemory(
    val index: Int,
    val type: IrMemoryType,
    val debugName: String? = null,
)

/**
 * Core Wasm element segment (active, passive, or declarative).
 */
sealed interface IrElementSegment {
    val index: Int
    val elementType: IrValType
    val items: List<IrConstExpr>

    data class Active(
        override val index: Int,
        val tableIndex: Int,
        val offset: IrConstExpr,
        override val elementType: IrValType,
        override val items: List<IrConstExpr>,
    ) : IrElementSegment

    data class Passive(
        override val index: Int,
        override val elementType: IrValType,
        override val items: List<IrConstExpr>,
    ) : IrElementSegment

    data class Declarative(
        override val index: Int,
        override val elementType: IrValType,
        override val items: List<IrConstExpr>,
    ) : IrElementSegment
}

/**
 * Core Wasm linear-memory data segment (active or passive).
 */
sealed interface IrDataSegment {
    val index: Int
    val bytes: ByteArray

    data class Active(
        override val index: Int,
        val memoryIndex: Int,
        val offset: IrConstExpr,
        override val bytes: ByteArray,
    ) : IrDataSegment {
        override fun equals(other: Any?): Boolean =
            other is Active &&
                index == other.index &&
                memoryIndex == other.memoryIndex &&
                offset == other.offset &&
                bytes.contentEquals(other.bytes)

        override fun hashCode(): Int {
            var result = index
            result = 31 * result + memoryIndex
            result = 31 * result + offset.hashCode()
            result = 31 * result + bytes.contentHashCode()
            return result
        }
    }

    data class Passive(
        override val index: Int,
        override val bytes: ByteArray,
    ) : IrDataSegment {
        override fun equals(other: Any?): Boolean =
            other is Passive &&
                index == other.index &&
                bytes.contentEquals(other.bytes)

        override fun hashCode(): Int {
            var result = index
            result = 31 * result + bytes.contentHashCode()
            return result
        }
    }
}

/**
 * Parameter or local slot inside a Core Wasm function.
 */
data class IrLocal(
    val index: Int,
    val valType: IrValType,
    val isParam: Boolean,
    val debugName: String? = null,
)

/**
 * Instruction paired with its Wasm byte offset and resolved operand stack types before/after execution.
 */
data class IrTypedInsn(
    val wasmOffset: Int,
    val op: WasmOp,
    val stackBefore: List<IrStackType>,
    val stackAfter: List<IrStackType>,
    val polymorphicBefore: Boolean,
    val polymorphicAfter: Boolean,
    val frameBaseHeight: Int,
)

/**
 * Defined Core Wasm function with resolved locals, blocks, and typed instruction sequence.
 */
data class IrFunction(
    val index: Int,
    val typeIndex: Int,
    val type: IrFuncType,
    val params: List<IrLocal>,
    val locals: List<IrLocal>,
    val blocks: List<IrBlockInfo>,
    val instructions: List<IrTypedInsn>,
    val debugName: String? = null,
) {
    val allLocals: List<IrLocal> = params + locals
}

/**
 * Parser-independent typed IR for a single Core Wasm module.
 */
data class IrModule(
    val moduleIndex: Int,
    val moduleName: String?,
    val types: List<IrFuncType>,
    val imports: List<IrImport>,
    val definedFunctions: List<IrFunction>,
    val definedTables: List<IrTable>,
    val definedMemories: List<IrMemory>,
    val definedGlobals: List<IrGlobal>,
    val exports: List<IrExport>,
    val startFunctionIndex: Int?,
    val elements: List<IrElementSegment>,
    val dataSegments: List<IrDataSegment>,
    val dataCount: Int?,
) {
    val importedFunctions: List<IrImport.Func> = imports.filterIsInstance<IrImport.Func>()
    val importedTables: List<IrImport.Table> = imports.filterIsInstance<IrImport.Table>()
    val importedMemories: List<IrImport.Memory> = imports.filterIsInstance<IrImport.Memory>()
    val importedGlobals: List<IrImport.Global> = imports.filterIsInstance<IrImport.Global>()

    private val functionTypesByIndex: List<IrFuncType> = buildList {
        for (imp in importedFunctions) add(types[imp.typeIndex])
        for (fn in definedFunctions) add(fn.type)
    }

    private val tableTypesByIndex: List<IrTableType> = buildList {
        for (imp in importedTables) add(imp.tableType)
        for (t in definedTables) add(t.type)
    }

    private val memoryTypesByIndex: List<IrMemoryType> = buildList {
        for (imp in importedMemories) add(imp.memoryType)
        for (m in definedMemories) add(m.type)
    }

    private val globalTypesByIndex: List<IrGlobalType> = buildList {
        for (imp in importedGlobals) add(imp.globalType)
        for (g in definedGlobals) add(g.type)
    }

    val totalFunctionCount: Int get() = functionTypesByIndex.size
    val totalTableCount: Int get() = tableTypesByIndex.size
    val totalMemoryCount: Int get() = memoryTypesByIndex.size
    val totalGlobalCount: Int get() = globalTypesByIndex.size

    fun functionTypeOrNull(funcIndex: Int): IrFuncType? = functionTypesByIndex.getOrNull(funcIndex)
    fun tableTypeOrNull(tableIndex: Int): IrTableType? = tableTypesByIndex.getOrNull(tableIndex)
    fun memoryTypeOrNull(memoryIndex: Int): IrMemoryType? = memoryTypesByIndex.getOrNull(memoryIndex)
    fun globalTypeOrNull(globalIndex: Int): IrGlobalType? = globalTypesByIndex.getOrNull(globalIndex)
}
