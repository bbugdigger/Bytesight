package com.bugdigger.bytesight.ui.inspector

import com.bugdigger.bytesight.service.AgentClient
import com.bugdigger.bytesight.service.CommentStore
import com.bugdigger.core.analysis.BasicBlock
import com.bugdigger.core.analysis.BlockType
import com.bugdigger.core.analysis.Instruction
import com.bugdigger.core.analysis.InstructionCategory
import com.bugdigger.core.decompiler.Decompiler
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for InspectorViewModel — block selection, view mode toggle, and
 * the static block-size computation used by the CFG layout engine.
 */
class InspectorViewModelTest {

    private lateinit var viewModel: InspectorViewModel
    private lateinit var mockAgentClient: AgentClient
    private lateinit var mockDecompiler: Decompiler
    private lateinit var commentStore: CommentStore

    @BeforeEach
    fun setup() {
        mockAgentClient = mockk(relaxed = true)
        mockDecompiler = mockk(relaxed = true)
        commentStore = CommentStore()
        viewModel = InspectorViewModel(mockAgentClient, mockDecompiler, commentStore)
    }

    @Nested
    @DisplayName("Initial State")
    inner class InitialState {
        @Test
        fun `should have empty initial state`() {
            val state = viewModel.uiState.value

            assertTrue(state.classes.isEmpty())
            assertNull(state.selectedClassName)
            assertNull(state.selectedMethod)
            assertNull(state.selectedInstruction)
            assertNull(state.cfg)
            assertNull(state.graphLayout)
            assertNull(state.decompiledSource)
            assertNull(state.selectedBlockId)
            assertNull(state.selectedInstructionOffset)
            assertFalse(state.isBlockHeaderSelected)
            assertTrue(state.methodComments.isEmpty)
            assertFalse(state.isLoading)
            assertNull(state.error)
            assertEquals(ViewMode.LINEAR, state.viewMode)
        }
    }

    @Nested
    @DisplayName("Block Selection")
    inner class BlockSelection {
        @Test
        fun `selectBlock sets selectedBlockId and clears instruction selection`() {
            viewModel.selectBlock("block_0")
            val state = viewModel.uiState.value

            assertEquals("block_0", state.selectedBlockId)
            assertNull(state.selectedInstructionOffset)
            assertFalse(state.isBlockHeaderSelected)
        }

        @Test
        fun `selectBlock with null clears selection`() {
            viewModel.selectBlock("block_0")
            viewModel.selectBlock(null)
            val state = viewModel.uiState.value

            assertNull(state.selectedBlockId)
            assertNull(state.selectedInstructionOffset)
            assertFalse(state.isBlockHeaderSelected)
        }

        @Test
        fun `selectBlockHeader marks header as selected`() {
            viewModel.selectBlockHeader("block_0")
            val state = viewModel.uiState.value

            assertEquals("block_0", state.selectedBlockId)
            assertNull(state.selectedInstructionOffset)
            assertTrue(state.isBlockHeaderSelected)
        }

        @Test
        fun `selectCfgInstruction sets both block and instruction offset`() {
            viewModel.selectCfgInstruction("block_0", 5)
            val state = viewModel.uiState.value

            assertEquals("block_0", state.selectedBlockId)
            assertEquals(5, state.selectedInstructionOffset)
            assertFalse(state.isBlockHeaderSelected)
        }

        @Test
        fun `selectCfgInstruction clears block header selection`() {
            viewModel.selectBlockHeader("block_0")
            viewModel.selectCfgInstruction("block_0", 3)
            val state = viewModel.uiState.value

            assertFalse(state.isBlockHeaderSelected)
            assertEquals(3, state.selectedInstructionOffset)
        }
    }

    @Nested
    @DisplayName("View Mode")
    inner class ViewModeToggle {
        @Test
        fun `toggleViewMode flips between LINEAR and CFG`() {
            assertEquals(ViewMode.LINEAR, viewModel.uiState.value.viewMode)

            viewModel.toggleViewMode()
            assertEquals(ViewMode.CFG, viewModel.uiState.value.viewMode)

            viewModel.toggleViewMode()
            assertEquals(ViewMode.LINEAR, viewModel.uiState.value.viewMode)
        }
    }

    @Nested
    @DisplayName("Block Size Computation")
    inner class BlockSizeComputation {

        private fun makeBlock(instructionCount: Int): BasicBlock {
            val instructions = (0 until instructionCount).map { i ->
                Instruction(
                    offset = i,
                    opcode = 0,
                    mnemonic = "NOP",
                    operands = "",
                    lineNumber = null,
                    type = InstructionCategory.OTHER,
                )
            }
            return BasicBlock(
                id = "block_0",
                instructions = instructions,
                blockType = BlockType.NORMAL,
                startOffset = 0,
                endOffset = instructionCount - 1,
            )
        }

        @Test
        fun `density 1 returns dp-based size`() {
            val block = makeBlock(3)
            val (width, height) = InspectorViewModel.computeBlockSize(block, density = 1f)

            // HEADER_HEIGHT_DP(24) + 3*INSTRUCTION_HEIGHT_DP(20) + PADDING_DP(4) = 88
            assertEquals(300f, width)
            assertEquals(88f, height)
        }

        @Test
        fun `density 2 doubles the pixel values`() {
            val block = makeBlock(3)
            val (width1x, height1x) = InspectorViewModel.computeBlockSize(block, density = 1f)
            val (width2x, height2x) = InspectorViewModel.computeBlockSize(block, density = 2f)

            assertEquals(width1x * 2f, width2x)
            assertEquals(height1x * 2f, height2x)
        }

        @Test
        fun `block-level comment adds extra height`() {
            val block = makeBlock(3)
            val (_, withComment) = InspectorViewModel.computeBlockSize(block, hasBlockComment = true, density = 1f)
            val (_, without) = InspectorViewModel.computeBlockSize(block, hasBlockComment = false, density = 1f)

            // BLOCK_COMMENT_HEIGHT_DP (18) at density 1
            assertEquals(without + 18f, withComment)
        }

        @Test
        fun `defaults produce base size without block comment`() {
            val block = makeBlock(5)
            val (_, defaultHeight) = InspectorViewModel.computeBlockSize(block, density = 1f)
            val (_, explicitFalse) = InspectorViewModel.computeBlockSize(block, hasBlockComment = false, density = 1f)

            assertEquals(explicitFalse, defaultHeight)
        }
    }

    @Nested
    @DisplayName("Error Handling")
    inner class ErrorHandling {
        @Test
        fun `clearError leaves error state null`() {
            viewModel.clearError()
            assertNull(viewModel.uiState.value.error)
        }
    }
}
