package wasm2kt.tooling.ir

/**
 * Produces deterministic structured text dumps of [IrModule] and [IrFunction]
 * for debugging, golden tests, and phase-2 compiler verification.
 */
object WasmIrDump {
    fun dump(module: IrModule, includeInstructions: Boolean = true): String = buildString {
        appendLine("module core=${module.moduleIndex}${module.moduleName?.let { " name=\"${escapeString(it)}\"" } ?: ""}")
        dumpTypes(module)
        dumpImports(module)
        dumpTables(module)
        dumpMemories(module)
        dumpGlobals(module)
        dumpExports(module)
        module.startFunctionIndex?.let { appendLine("  start func=$it") }
        dumpElements(module)
        dumpDataSegments(module)
        for (fn in module.definedFunctions) {
            appendFunction(fn, includeInstructions)
        }
    }

    fun dumpFunction(fn: IrFunction, includeInstructions: Boolean = true): String = buildString {
        appendFunction(fn, includeInstructions)
    }

    private fun StringBuilder.dumpTypes(module: IrModule) {
        module.types.forEachIndexed { idx, t ->
            appendLine("  type[$idx] ${formatSig(t.params, t.results)}")
        }
    }

    private fun StringBuilder.dumpImports(module: IrModule) {
        for (imp in module.imports) {
            val m = escapeString(imp.module)
            val n = escapeString(imp.name)
            when (imp) {
                is IrImport.Func -> {
                    val t = module.types[imp.typeIndex]
                    appendLine("  import func[${imp.funcIndex}] \"$m\".\"$n\" type=${imp.typeIndex} ${formatSig(t.params, t.results)}")
                }
                is IrImport.Table -> {
                    appendLine("  import table[${imp.tableIndex}] \"$m\".\"$n\" ${formatTableType(imp.tableType)}")
                }
                is IrImport.Memory -> {
                    appendLine("  import memory[${imp.memoryIndex}] \"$m\".\"$n\" ${formatMemoryType(imp.memoryType)}")
                }
                is IrImport.Global -> {
                    appendLine("  import global[${imp.globalIndex}] \"$m\".\"$n\" ${formatGlobalType(imp.globalType)}")
                }
            }
        }
    }

    private fun StringBuilder.dumpTables(module: IrModule) {
        for (t in module.definedTables) {
            appendLine("  table[${t.index}] ${formatTableType(t.type)}")
        }
    }

    private fun StringBuilder.dumpMemories(module: IrModule) {
        for (m in module.definedMemories) {
            appendLine("  memory[${m.index}] ${formatMemoryType(m.type)}")
        }
    }

    private fun StringBuilder.dumpGlobals(module: IrModule) {
        for (g in module.definedGlobals) {
            appendLine("  global[${g.index}] ${formatGlobalType(g.type)} = (${formatConstExpr(g.initExpr)})")
        }
    }

    private fun StringBuilder.dumpExports(module: IrModule) {
        for (e in module.exports) {
            appendLine("  export \"${escapeString(e.name)}\" ${e.kind.mnemonic}=${e.index}")
        }
    }

    private fun StringBuilder.dumpElements(module: IrModule) {
        for (el in module.elements) {
            when (el) {
                is IrElementSegment.Active -> {
                    appendLine("  elem[${el.index}] active table=${el.tableIndex} offset=(${formatConstExpr(el.offset)}) ${el.elementType.mnemonic} count=${el.items.size}")
                }
                is IrElementSegment.Passive -> {
                    appendLine("  elem[${el.index}] passive ${el.elementType.mnemonic} count=${el.items.size}")
                }
                is IrElementSegment.Declarative -> {
                    appendLine("  elem[${el.index}] declarative ${el.elementType.mnemonic} count=${el.items.size}")
                }
            }
        }
    }

    private fun StringBuilder.dumpDataSegments(module: IrModule) {
        module.dataCount?.let { appendLine("  datacount $it") }
        for (d in module.dataSegments) {
            when (d) {
                is IrDataSegment.Active -> {
                    appendLine("  data[${d.index}] active mem=${d.memoryIndex} offset=(${formatConstExpr(d.offset)}) size=${d.bytes.size}")
                }
                is IrDataSegment.Passive -> {
                    appendLine("  data[${d.index}] passive size=${d.bytes.size}")
                }
            }
        }
    }

    private fun StringBuilder.appendFunction(fn: IrFunction, includeInstructions: Boolean) {
        val namePart = fn.debugName?.let { " \"${escapeString(it)}\"" } ?: ""
        appendLine("  func[${fn.index}]$namePart type=${fn.typeIndex} ${formatSig(fn.type.params, fn.type.results)}")
        if (fn.locals.isNotEmpty()) {
            val localsDesc = fn.locals.joinToString(", ") { "${it.index}:${it.valType.mnemonic}" }
            appendLine("    locals [$localsDesc]")
        }
        if (!includeInstructions) {
            appendLine("    blocks=${fn.blocks.size} insns=${fn.instructions.size}")
            return
        }
        var indent = 2
        for (insn in fn.instructions) {
            val op = insn.op
            if (op is WasmOp.Else || op is WasmOp.End) {
                indent = (indent - 1).coerceAtLeast(2)
            }
            val pad = "  ".repeat(indent)
            val before = formatStack(insn.stackBefore, insn.polymorphicBefore)
            val after = formatStack(insn.stackAfter, insn.polymorphicAfter)
            appendLine("    @0x${insn.wasmOffset.toString(16).padStart(4, '0')} $pad${formatOp(op)}  ; $before -> $after")
            if (op is WasmOp.Block || op is WasmOp.Loop || op is WasmOp.If || op is WasmOp.Else) {
                indent++
            }
        }
    }

    private fun formatOp(op: WasmOp): String = when (op) {
        is WasmOp.Block -> "block #${op.blockId} ${formatSig(op.params, op.results)}"
        is WasmOp.Loop -> "loop #${op.blockId} ${formatSig(op.params, op.results)}"
        is WasmOp.If -> "if #${op.blockId} ${formatSig(op.params, op.results)}"
        is WasmOp.Else -> "else #${op.blockId}"
        is WasmOp.End -> "end #${op.blockId} (${op.kind.mnemonic})"
        is WasmOp.Br -> "br ${formatBranchTarget(op.target)}"
        is WasmOp.BrIf -> "br_if ${formatBranchTarget(op.target)}"
        is WasmOp.BrTable -> {
            val targets = op.targets.joinToString(", ") { formatBranchTarget(it) }
            "br_table [$targets] default=${formatBranchTarget(op.defaultTarget)}"
        }
        is WasmOp.Return -> "return ${op.resultTypes.joinToString(",") { it.mnemonic }}"
        is WasmOp.Call -> "call ${op.funcIndex} ${formatSig(op.funcType.params, op.funcType.results)}"
        is WasmOp.CallIndirect -> "call_indirect type=${op.typeIndex} table=${op.tableIndex}"
        is WasmOp.Drop -> "drop:${op.droppedType.mnemonic}"
        is WasmOp.Select -> if (op.explicitTypes.isEmpty()) {
            "select:${op.resolvedType.mnemonic}"
        } else {
            "select [${op.explicitTypes.joinToString(",") { it.mnemonic }}]"
        }
        is WasmOp.LocalGet -> "local.get ${op.localIndex}:${op.valType.mnemonic}"
        is WasmOp.LocalSet -> "local.set ${op.localIndex}:${op.valType.mnemonic}"
        is WasmOp.LocalTee -> "local.tee ${op.localIndex}:${op.valType.mnemonic}"
        is WasmOp.GlobalGet -> "global.get ${op.globalIndex}:${op.valType.mnemonic}"
        is WasmOp.GlobalSet -> "global.set ${op.globalIndex}:${op.valType.mnemonic}"
        is WasmOp.RefNull -> "ref.null ${op.valType.mnemonic}"
        is WasmOp.RefIsNull -> "ref.is_null:${op.refType.mnemonic}"
        is WasmOp.RefFunc -> "ref.func ${op.funcIndex}"
        is WasmOp.TableGet -> "table.get ${op.tableIndex}"
        is WasmOp.TableSet -> "table.set ${op.tableIndex}"
        is WasmOp.TableInit -> "table.init elem=${op.elemIndex} table=${op.tableIndex}"
        is WasmOp.ElemDrop -> "elem.drop ${op.elemIndex}"
        is WasmOp.TableCopy -> "table.copy dst=${op.dstTableIndex} src=${op.srcTableIndex}"
        is WasmOp.TableGrow -> "table.grow ${op.tableIndex}"
        is WasmOp.TableSize -> "table.size ${op.tableIndex}"
        is WasmOp.TableFill -> "table.fill ${op.tableIndex}"
        is WasmOp.Load -> "${op.kind.mnemonic} mem=${op.memoryIndex} align=${op.align} offset=${op.offset}"
        is WasmOp.Store -> "${op.kind.mnemonic} mem=${op.memoryIndex} align=${op.align} offset=${op.offset}"
        is WasmOp.MemorySize -> "memory.size ${op.memoryIndex}"
        is WasmOp.MemoryGrow -> "memory.grow ${op.memoryIndex}"
        is WasmOp.MemoryInit -> "memory.init data=${op.dataIndex} mem=${op.memoryIndex}"
        is WasmOp.DataDrop -> "data.drop ${op.dataIndex}"
        is WasmOp.MemoryCopy -> "memory.copy dst=${op.dstMemoryIndex} src=${op.srcMemoryIndex}"
        is WasmOp.MemoryFill -> "memory.fill ${op.memoryIndex}"
        is WasmOp.I32Const -> "i32.const ${op.value}"
        is WasmOp.I64Const -> "i64.const ${op.value}"
        is WasmOp.F32Const -> "f32.const bits=0x${op.rawBits.toUInt().toString(16)} (${op.toFloat()})"
        is WasmOp.F64Const -> "f64.const bits=0x${op.rawBits.toULong().toString(16)} (${op.toDouble()})"
        is WasmOp.Unary -> op.op.mnemonic
        is WasmOp.Binary -> op.op.mnemonic
        WasmOp.Unreachable -> "unreachable"
        WasmOp.Nop -> "nop"
    }

    private fun formatBranchTarget(target: IrBranchTarget): String =
        "depth=${target.depth}->#${target.targetBlockId}(${target.targetKind.mnemonic})"

    private fun formatStack(stack: List<IrStackType>, polymorphic: Boolean): String {
        val prefix = if (polymorphic) "..," else ""
        return "[$prefix${stack.joinToString(",") { it.mnemonic }}]"
    }

    private fun formatSig(params: List<IrValType>, results: List<IrValType>): String {
        val p = params.joinToString(",") { it.mnemonic }
        val r = results.joinToString(",") { it.mnemonic }
        return "($p)->($r)"
    }

    private fun formatTableType(t: IrTableType): String =
        "${t.elementType.mnemonic} min=${t.limits.min}${t.limits.max?.let { " max=$it" } ?: ""}"

    private fun formatMemoryType(m: IrMemoryType): String =
        "pages=${m.initialPages}${m.maxPages?.let { " max=$it" } ?: ""}"

    private fun formatGlobalType(g: IrGlobalType): String =
        "${g.mutability.mnemonic} ${g.valueType.mnemonic}"

    private fun formatConstExpr(expr: IrConstExpr): String =
        expr.ops.joinToString(" ") { op ->
            when (op) {
                is IrConstOp.I32Const -> "i32.const ${op.value}"
                is IrConstOp.I64Const -> "i64.const ${op.value}"
                is IrConstOp.F32Const -> "f32.const bits=0x${op.rawBits.toUInt().toString(16)}"
                is IrConstOp.F64Const -> "f64.const bits=0x${op.rawBits.toULong().toString(16)}"
                is IrConstOp.GlobalGet -> "global.get ${op.globalIndex}:${op.valType.mnemonic}"
                is IrConstOp.RefNull -> "ref.null ${op.valType.mnemonic}"
                is IrConstOp.RefFunc -> "ref.func ${op.funcIndex}"
                IrConstOp.I32Add -> "i32.add"
                IrConstOp.I32Sub -> "i32.sub"
                IrConstOp.I32Mul -> "i32.mul"
                IrConstOp.I64Add -> "i64.add"
                IrConstOp.I64Sub -> "i64.sub"
                IrConstOp.I64Mul -> "i64.mul"
            }
        }

    private fun escapeString(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20 || ch.code == 0x7F) {
                    append("\\u")
                    append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    append(ch)
                }
            }
        }
    }
}
