package com.bugdigger.core.analysis

import org.objectweb.asm.*
import org.objectweb.asm.util.Printer

/**
 * Type of edge between basic blocks.
 */
enum class EdgeType {
    FALL_THROUGH,
    UNCONDITIONAL_JUMP,
    BRANCH_TRUE,
    BRANCH_FALSE,
    EXCEPTION,
    SWITCH_CASE,
    SWITCH_DEFAULT,
}

/**
 * Classification of a basic block for color coding in the UI.
 */
enum class BlockType {
    ENTRY,
    NORMAL,
    LOOP_HEADER,
    CATCH_BLOCK,
    EXIT,
}

/**
 * A directed edge between two basic blocks.
 */
data class CfgEdge(
    val sourceId: String,
    val targetId: String,
    val type: EdgeType,
    val label: String? = null,
)

/**
 * A basic block containing a sequence of instructions with no internal branches.
 */
data class BasicBlock(
    val id: String,
    val instructions: List<Instruction>,
    val blockType: BlockType,
    val startOffset: Int,
    val endOffset: Int,
)

/**
 * The complete control flow graph for a single method.
 */
data class ControlFlowGraph(
    val methodName: String,
    val methodDescriptor: String,
    val blocks: List<BasicBlock>,
    val edges: List<CfgEdge>,
    val entryBlockId: String,
)

/**
 * Builds a [ControlFlowGraph] from raw class bytecode for a specified method.
 *
 * Uses its own ASM pass to resolve label targets to instruction indices,
 * then identifies basic block leaders, splits into blocks, and constructs edges.
 */
class CfgBuilder {

    /**
     * Builds a control flow graph for the specified method.
     *
     * @param bytecode Raw class file bytes
     * @param methodName Name of the method to analyze
     * @param methodDescriptor JVM descriptor of the method (e.g., "(I)V")
     * @return The [ControlFlowGraph] for the method
     * @throws IllegalArgumentException if the method is not found in the class
     */
    fun buildCfg(bytecode: ByteArray, methodName: String, methodDescriptor: String): ControlFlowGraph {
        val collector = CfgMethodCollector(methodName, methodDescriptor)
        val reader = ClassReader(bytecode)
        reader.accept(collector, ClassReader.EXPAND_FRAMES)

        val data = collector.result
            ?: throw IllegalArgumentException("Method $methodName$methodDescriptor not found")

        return buildFromCollectedData(data, methodName, methodDescriptor)
    }

    private fun buildFromCollectedData(
        data: CollectedMethodData,
        methodName: String,
        methodDescriptor: String,
    ): ControlFlowGraph {
        if (data.instructions.isEmpty()) {
            val block = BasicBlock(
                id = "block_0",
                instructions = emptyList(),
                blockType = BlockType.ENTRY,
                startOffset = 0,
                endOffset = 0,
            )
            return ControlFlowGraph(methodName, methodDescriptor, listOf(block), emptyList(), "block_0")
        }

        // Phase 2: Identify leaders
        val leaders = identifyLeaders(data)

        // Phase 3: Split into blocks
        val blocks = splitIntoBlocks(data.instructions, leaders)

        // Phase 4: Build edges
        val edges = buildEdges(blocks, data)

        // Phase 5: Refine block types
        val handlerOffsets = data.tryCatchBlocks.map { it.handlerIndex }.toSet()
        val loopHeaders = findLoopHeaders(blocks, edges)
        val refinedBlocks = blocks.map { block ->
            val type = when {
                block.startOffset == 0 && isExitBlock(block, data) -> BlockType.ENTRY
                block.startOffset == 0 -> BlockType.ENTRY
                block.startOffset in handlerOffsets -> BlockType.CATCH_BLOCK
                block.id in loopHeaders -> BlockType.LOOP_HEADER
                isExitBlock(block, data) -> BlockType.EXIT
                else -> BlockType.NORMAL
            }
            block.copy(blockType = type)
        }

        return ControlFlowGraph(
            methodName = methodName,
            methodDescriptor = methodDescriptor,
            blocks = refinedBlocks,
            edges = edges,
            entryBlockId = "block_0",
        )
    }

    private fun identifyLeaders(data: CollectedMethodData): Set<Int> {
        val leaders = mutableSetOf<Int>()
        leaders.add(0) // Method entry

        for ((instrIdx, meta) in data.jumpMeta) {
            // Jump targets are leaders
            for (targetIdx in meta.targetIndices) {
                if (targetIdx in data.instructions.indices) {
                    leaders.add(targetIdx)
                }
            }
            // Instruction after a jump/branch is a leader (if exists)
            val next = instrIdx + 1
            if (next < data.instructions.size) {
                leaders.add(next)
            }
        }

        // Instructions after return/athrow are leaders
        for ((idx, instr) in data.instructions.withIndex()) {
            if (isTerminalOpcode(instr.opcode)) {
                val next = idx + 1
                if (next < data.instructions.size) {
                    leaders.add(next)
                }
            }
        }

        // Exception handler starts are leaders
        for (tc in data.tryCatchBlocks) {
            if (tc.handlerIndex in data.instructions.indices) {
                leaders.add(tc.handlerIndex)
            }
        }

        return leaders
    }

    private fun splitIntoBlocks(
        instructions: List<Instruction>,
        leaders: Set<Int>,
    ): List<BasicBlock> {
        val sortedLeaders = leaders.sorted()
        return sortedLeaders.mapIndexed { i, leaderIdx ->
            val endIdx = if (i + 1 < sortedLeaders.size) {
                sortedLeaders[i + 1] - 1
            } else {
                instructions.lastIndex
            }
            val blockInstructions = instructions.subList(leaderIdx, endIdx + 1)
            BasicBlock(
                id = "block_$leaderIdx",
                instructions = blockInstructions,
                blockType = BlockType.NORMAL, // refined later
                startOffset = leaderIdx,
                endOffset = endIdx,
            )
        }
    }

    private fun buildEdges(
        blocks: List<BasicBlock>,
        data: CollectedMethodData,
    ): List<CfgEdge> {
        val edges = mutableListOf<CfgEdge>()
        val blockByStart = blocks.associateBy { it.startOffset }

        // Find which block a given instruction index falls into
        fun blockIdForIndex(idx: Int): String? {
            return blocks.lastOrNull { it.startOffset <= idx }?.id
        }

        for (block in blocks) {
            val lastInstrIdx = block.endOffset
            val lastInstr = data.instructions[lastInstrIdx]
            val jumpMeta = data.jumpMeta[lastInstrIdx]

            when {
                // Return or athrow — no outgoing edges
                isTerminalOpcode(lastInstr.opcode) -> {}

                // Unconditional jump (goto)
                lastInstr.opcode == Opcodes.GOTO -> {
                    jumpMeta?.targetIndices?.firstOrNull()?.let { targetIdx ->
                        blockIdForIndex(targetIdx)?.let { targetId ->
                            edges.add(CfgEdge(block.id, targetId, EdgeType.UNCONDITIONAL_JUMP))
                        }
                    }
                }

                // Conditional branches (if*)
                jumpMeta != null && isConditionalBranch(lastInstr.opcode) -> {
                    // True branch: jump target
                    jumpMeta.targetIndices.firstOrNull()?.let { targetIdx ->
                        blockIdForIndex(targetIdx)?.let { targetId ->
                            edges.add(CfgEdge(block.id, targetId, EdgeType.BRANCH_TRUE))
                        }
                    }
                    // False branch: fall-through to next block
                    val nextIdx = lastInstrIdx + 1
                    blockIdForIndex(nextIdx)?.let { targetId ->
                        edges.add(CfgEdge(block.id, targetId, EdgeType.BRANCH_FALSE))
                    }
                }

                // Switch instructions
                jumpMeta != null && (lastInstr.opcode == Opcodes.TABLESWITCH || lastInstr.opcode == Opcodes.LOOKUPSWITCH) -> {
                    val targets = jumpMeta.targetIndices
                    // First target is the default
                    if (targets.isNotEmpty()) {
                        blockIdForIndex(targets[0])?.let { targetId ->
                            edges.add(CfgEdge(block.id, targetId, EdgeType.SWITCH_DEFAULT, "default"))
                        }
                    }
                    // Remaining targets are cases
                    val caseKeys = jumpMeta.switchKeys
                    for (i in 1 until targets.size) {
                        blockIdForIndex(targets[i])?.let { targetId ->
                            val label = caseKeys.getOrNull(i - 1)?.toString()
                            edges.add(CfgEdge(block.id, targetId, EdgeType.SWITCH_CASE, label))
                        }
                    }
                }

                // Non-control-flow last instruction: fall-through
                else -> {
                    val nextIdx = lastInstrIdx + 1
                    if (nextIdx < data.instructions.size) {
                        blockIdForIndex(nextIdx)?.let { targetId ->
                            edges.add(CfgEdge(block.id, targetId, EdgeType.FALL_THROUGH))
                        }
                    }
                }
            }
        }

        // Exception edges
        for (tc in data.tryCatchBlocks) {
            val handlerBlockId = blockIdForIndex(tc.handlerIndex) ?: continue
            for (block in blocks) {
                // A block is covered if it overlaps with [startIndex, endIndex)
                if (block.endOffset >= tc.startIndex && block.startOffset < tc.endIndex) {
                    edges.add(
                        CfgEdge(
                            block.id,
                            handlerBlockId,
                            EdgeType.EXCEPTION,
                            tc.exceptionType,
                        )
                    )
                }
            }
        }

        return edges
    }

    private fun findLoopHeaders(blocks: List<BasicBlock>, edges: List<CfgEdge>): Set<String> {
        val loopHeaders = mutableSetOf<String>()
        val adjacency = edges
            .filter { it.type != EdgeType.EXCEPTION }
            .groupBy { it.sourceId }
            .mapValues { (_, v) -> v.map { it.targetId } }

        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()

        fun dfs(nodeId: String) {
            if (nodeId in visited) return
            if (nodeId in visiting) {
                loopHeaders.add(nodeId)
                return
            }
            visiting.add(nodeId)
            for (succ in adjacency[nodeId] ?: emptyList()) {
                dfs(succ)
            }
            visiting.remove(nodeId)
            visited.add(nodeId)
        }

        if (blocks.isNotEmpty()) {
            dfs(blocks.first().id)
        }
        return loopHeaders
    }

    private fun isExitBlock(block: BasicBlock, data: CollectedMethodData): Boolean {
        val lastInstr = data.instructions.getOrNull(block.endOffset) ?: return false
        return isTerminalOpcode(lastInstr.opcode)
    }

    private fun isTerminalOpcode(opcode: Int): Boolean = opcode in Opcodes.IRETURN..Opcodes.RETURN || opcode == Opcodes.ATHROW

    private fun isConditionalBranch(opcode: Int): Boolean = when (opcode) {
        in Opcodes.IFEQ..Opcodes.IF_ACMPNE,
        Opcodes.IFNULL, Opcodes.IFNONNULL -> true
        else -> false
    }
}

// ========== Internal data structures for ASM pass ==========

private data class JumpMeta(
    val targetIndices: List<Int>,
    val switchKeys: List<Int> = emptyList(),
)

private data class TryCatchInfo(
    val startIndex: Int,
    val endIndex: Int,
    val handlerIndex: Int,
    val exceptionType: String?,
)

private class CollectedMethodData(
    val instructions: List<Instruction>,
    val jumpMeta: Map<Int, JumpMeta>,
    val tryCatchBlocks: List<TryCatchInfo>,
)

/**
 * ClassVisitor that selects the target method and collects CFG-relevant data.
 */
private class CfgMethodCollector(
    private val targetMethodName: String,
    private val targetMethodDescriptor: String,
) : ClassVisitor(Opcodes.ASM9) {

    var result: CollectedMethodData? = null
        private set

    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor? {
        if (name == targetMethodName && descriptor == targetMethodDescriptor) {
            return CfgInstructionCollector { data ->
                result = data
            }
        }
        return null
    }
}

/**
 * MethodVisitor that collects instructions with resolved label-to-index mappings.
 */
private class CfgInstructionCollector(
    private val onComplete: (CollectedMethodData) -> Unit,
) : MethodVisitor(Opcodes.ASM9) {

    private val instructions = mutableListOf<Instruction>()
    private var instrIndex = 0
    private var currentLine: Int? = null

    // Label resolution
    private val labelToIndex = mutableMapOf<Label, Int>()
    private val pendingLabels = mutableListOf<Label>()

    // Jump metadata (stored with label references, resolved at visitEnd)
    private data class UnresolvedJump(
        val instrIndex: Int,
        val targetLabels: List<Label>,
        val switchKeys: List<Int> = emptyList(),
    )
    private val unresolvedJumps = mutableListOf<UnresolvedJump>()

    // Try-catch blocks (stored with label references, resolved at visitEnd)
    private data class UnresolvedTryCatch(
        val start: Label,
        val end: Label,
        val handler: Label,
        val exceptionType: String?,
    )
    private val unresolvedTryCatchBlocks = mutableListOf<UnresolvedTryCatch>()

    override fun visitLabel(label: Label) {
        pendingLabels.add(label)
    }

    override fun visitLineNumber(line: Int, start: Label?) {
        currentLine = line
    }

    private fun resolvePendingLabels() {
        for (label in pendingLabels) {
            labelToIndex[label] = instrIndex
        }
        pendingLabels.clear()
    }

    override fun visitInsn(opcode: Int) {
        resolvePendingLabels()
        addInstruction(opcode, "")
    }

    override fun visitIntInsn(opcode: Int, operand: Int) {
        resolvePendingLabels()
        addInstruction(opcode, operand.toString())
    }

    override fun visitVarInsn(opcode: Int, varIndex: Int) {
        resolvePendingLabels()
        addInstruction(opcode, varIndex.toString())
    }

    override fun visitTypeInsn(opcode: Int, type: String) {
        resolvePendingLabels()
        addInstruction(opcode, type.replace('/', '.'))
    }

    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
        resolvePendingLabels()
        addInstruction(opcode, "${owner.replace('/', '.')}.$name : $descriptor")
    }

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
        resolvePendingLabels()
        addInstruction(opcode, "${owner.replace('/', '.')}.$name$descriptor")
    }

    override fun visitInvokeDynamicInsn(name: String, descriptor: String, bsm: Handle, vararg bsmArgs: Any?) {
        resolvePendingLabels()
        addInstruction(Opcodes.INVOKEDYNAMIC, "$name$descriptor")
    }

    override fun visitJumpInsn(opcode: Int, label: Label) {
        resolvePendingLabels()
        addInstruction(opcode, "") // operands resolved later
        unresolvedJumps.add(UnresolvedJump(instrIndex - 1, listOf(label)))
    }

    override fun visitLdcInsn(value: Any?) {
        resolvePendingLabels()
        val operandStr = when (value) {
            is String -> "\"$value\""
            is Type -> value.className
            else -> value.toString()
        }
        addInstruction(Opcodes.LDC, operandStr)
    }

    override fun visitIincInsn(varIndex: Int, increment: Int) {
        resolvePendingLabels()
        addInstruction(Opcodes.IINC, "$varIndex, $increment")
    }

    override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label, vararg labels: Label) {
        resolvePendingLabels()
        addInstruction(Opcodes.TABLESWITCH, "$min to $max")
        val allLabels = listOf(dflt) + labels.toList()
        val keys = (min..max).toList()
        unresolvedJumps.add(UnresolvedJump(instrIndex - 1, allLabels, keys))
    }

    override fun visitLookupSwitchInsn(dflt: Label, keys: IntArray, labels: Array<out Label>) {
        resolvePendingLabels()
        addInstruction(Opcodes.LOOKUPSWITCH, "")
        val allLabels = listOf(dflt) + labels.toList()
        unresolvedJumps.add(UnresolvedJump(instrIndex - 1, allLabels, keys.toList()))
    }

    override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) {
        resolvePendingLabels()
        addInstruction(Opcodes.MULTIANEWARRAY, "$descriptor dim=$numDimensions")
    }

    override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) {
        unresolvedTryCatchBlocks.add(
            UnresolvedTryCatch(start, end, handler, type?.replace('/', '.'))
        )
    }

    override fun visitEnd() {
        // Resolve pending labels for any trailing labels
        resolvePendingLabels()

        // Resolve jump targets
        val resolvedJumpMeta = mutableMapOf<Int, JumpMeta>()
        for (jump in unresolvedJumps) {
            val targetIndices = jump.targetLabels.map { label ->
                labelToIndex[label] ?: -1
            }.filter { it >= 0 }
            resolvedJumpMeta[jump.instrIndex] = JumpMeta(targetIndices, jump.switchKeys)
        }

        // Resolve try-catch blocks
        val resolvedTryCatch = unresolvedTryCatchBlocks.mapNotNull { tc ->
            val startIdx = labelToIndex[tc.start] ?: return@mapNotNull null
            val endIdx = labelToIndex[tc.end] ?: return@mapNotNull null
            val handlerIdx = labelToIndex[tc.handler] ?: return@mapNotNull null
            TryCatchInfo(startIdx, endIdx, handlerIdx, tc.exceptionType)
        }

        // Update instruction operands for jumps with resolved target block IDs
        val updatedInstructions = instructions.mapIndexed { idx, instr ->
            val meta = resolvedJumpMeta[idx]
            if (meta != null && instr.operands.isEmpty()) {
                val targetStr = meta.targetIndices.joinToString(", ") { "block_$it" }
                instr.copy(operands = targetStr)
            } else {
                instr
            }
        }

        onComplete(
            CollectedMethodData(
                instructions = updatedInstructions,
                jumpMeta = resolvedJumpMeta,
                tryCatchBlocks = resolvedTryCatch,
            )
        )
    }

    private fun addInstruction(opcode: Int, operands: String) {
        val mnemonic = if (opcode in 0 until Printer.OPCODES.size) {
            Printer.OPCODES[opcode]
        } else {
            "UNKNOWN_$opcode"
        }
        instructions.add(
            Instruction(
                offset = instrIndex,
                opcode = opcode,
                mnemonic = mnemonic,
                operands = operands,
                lineNumber = currentLine,
                type = categorizeOpcode(opcode),
            )
        )
        instrIndex++
    }
}
