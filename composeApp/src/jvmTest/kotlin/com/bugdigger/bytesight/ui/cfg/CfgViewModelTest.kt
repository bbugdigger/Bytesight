package com.bugdigger.bytesight.ui.cfg

import com.bugdigger.bytesight.service.AgentClient
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
 * Unit tests for CfgViewModel, focused on comment management and block sizing.
 */
class CfgViewModelTest {

    private lateinit var viewModel: CfgViewModel
    private lateinit var mockAgentClient: AgentClient
    private lateinit var mockDecompiler: Decompiler

    @BeforeEach
    fun setup() {
        mockAgentClient = mockk(relaxed = true)
        mockDecompiler = mockk(relaxed = true)
        viewModel = CfgViewModel(mockAgentClient, mockDecompiler)
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
            assertNull(state.cfg)
            assertNull(state.graphLayout)
            assertNull(state.decompiledSource)
            assertNull(state.selectedBlockId)
            assertNull(state.selectedInstructionOffset)
            assertFalse(state.isBlockHeaderSelected)
            assertTrue(state.comments.isEmpty())
            assertFalse(state.isLoading)
            assertNull(state.error)
        }
    }

    @Nested
    @DisplayName("Block Selection")
    inner class BlockSelection {
        @Test
        fun `selectBlock should set selectedBlockId and clear instruction selection`() {
            viewModel.selectBlock("block_0")
            val state = viewModel.uiState.value

            assertEquals("block_0", state.selectedBlockId)
            assertNull(state.selectedInstructionOffset)
            assertFalse(state.isBlockHeaderSelected)
        }

        @Test
        fun `selectBlock with null should clear selection`() {
            viewModel.selectBlock("block_0")
            viewModel.selectBlock(null)
            val state = viewModel.uiState.value

            assertNull(state.selectedBlockId)
            assertNull(state.selectedInstructionOffset)
            assertFalse(state.isBlockHeaderSelected)
        }

        @Test
        fun `selectBlockHeader should set block header selected`() {
            viewModel.selectBlockHeader("block_0")
            val state = viewModel.uiState.value

            assertEquals("block_0", state.selectedBlockId)
            assertNull(state.selectedInstructionOffset)
            assertTrue(state.isBlockHeaderSelected)
        }

        @Test
        fun `selectInstruction should set both block and instruction`() {
            viewModel.selectInstruction("block_0", 5)
            val state = viewModel.uiState.value

            assertEquals("block_0", state.selectedBlockId)
            assertEquals(5, state.selectedInstructionOffset)
            assertFalse(state.isBlockHeaderSelected)
        }

        @Test
        fun `selectInstruction should clear block header selection`() {
            viewModel.selectBlockHeader("block_0")
            viewModel.selectInstruction("block_0", 3)
            val state = viewModel.uiState.value

            assertFalse(state.isBlockHeaderSelected)
            assertEquals(3, state.selectedInstructionOffset)
        }
    }

    @Nested
    @DisplayName("Comment Management")
    inner class CommentManagement {
        @Test
        fun `addComment should store instruction comment`() {
            viewModel.addComment("block_0", 5, "loads the instance")
            val state = viewModel.uiState.value

            assertEquals("loads the instance", state.comments["block_0"]?.get(5))
        }

        @Test
        fun `addComment should store block-level comment with null offset`() {
            viewModel.addComment("block_0", null, "Entry point of method")
            val state = viewModel.uiState.value

            assertEquals("Entry point of method", state.comments["block_0"]?.get(null))
        }

        @Test
        fun `addComment with blank text should remove the comment`() {
            viewModel.addComment("block_0", 5, "initial comment")
            viewModel.addComment("block_0", 5, "")
            val state = viewModel.uiState.value

            assertFalse(state.comments.containsKey("block_0"))
        }

        @Test
        fun `addComment should allow multiple comments per block`() {
            viewModel.addComment("block_0", 0, "first instruction")
            viewModel.addComment("block_0", 3, "third instruction")
            viewModel.addComment("block_0", null, "block description")
            val state = viewModel.uiState.value

            val blockComments = state.comments["block_0"]!!
            assertEquals(3, blockComments.size)
            assertEquals("first instruction", blockComments[0])
            assertEquals("third instruction", blockComments[3])
            assertEquals("block description", blockComments[null])
        }

        @Test
        fun `addComment should allow comments on different blocks`() {
            viewModel.addComment("block_0", 0, "comment on block 0")
            viewModel.addComment("block_1", 5, "comment on block 1")
            val state = viewModel.uiState.value

            assertEquals(2, state.comments.size)
            assertEquals("comment on block 0", state.comments["block_0"]?.get(0))
            assertEquals("comment on block 1", state.comments["block_1"]?.get(5))
        }

        @Test
        fun `addComment should overwrite existing comment`() {
            viewModel.addComment("block_0", 5, "original")
            viewModel.addComment("block_0", 5, "updated")
            val state = viewModel.uiState.value

            assertEquals("updated", state.comments["block_0"]?.get(5))
        }

        @Test
        fun `removing last comment from block should remove block entry`() {
            viewModel.addComment("block_0", 5, "only comment")
            viewModel.addComment("block_0", 5, "   ") // blank
            val state = viewModel.uiState.value

            assertFalse(state.comments.containsKey("block_0"))
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
        fun `computeBlockSize without comments should return base size`() {
            val block = makeBlock(3)
            val (width, height) = CfgViewModel.computeBlockSize(block)

            // HEADER_HEIGHT(28) + 3*INSTRUCTION_HEIGHT(20) + PADDING(12) = 100
            assertEquals(300f, width)
            assertEquals(100f, height)
        }

        @Test
        fun `computeBlockSize with block-level comment should add extra height`() {
            val block = makeBlock(3)
            val comments = mapOf<Int?, String>(null to "block comment")
            val (_, heightWithComment) = CfgViewModel.computeBlockSize(block, comments)
            val (_, heightWithout) = CfgViewModel.computeBlockSize(block)

            // Block comment adds BLOCK_COMMENT_HEIGHT (18)
            assertEquals(heightWithout + 18f, heightWithComment)
        }

        @Test
        fun `computeBlockSize with instruction comments should not change height`() {
            val block = makeBlock(3)
            val comments = mapOf<Int?, String>(0 to "comment on first", 2 to "comment on last")
            val (_, heightWithComment) = CfgViewModel.computeBlockSize(block, comments)
            val (_, heightWithout) = CfgViewModel.computeBlockSize(block)

            // Instruction comments are inline, no extra height
            assertEquals(heightWithout, heightWithComment)
        }

        @Test
        fun `computeBlockSize with null comments map should return base size`() {
            val block = makeBlock(5)
            val (_, heightNull) = CfgViewModel.computeBlockSize(block, null)
            val (_, heightNoArg) = CfgViewModel.computeBlockSize(block)

            assertEquals(heightNoArg, heightNull)
        }
    }

    @Nested
    @DisplayName("Error Handling")
    inner class ErrorHandling {
        @Test
        fun `clearError should clear error state`() {
            viewModel.clearError()
            assertNull(viewModel.uiState.value.error)
        }
    }
}
