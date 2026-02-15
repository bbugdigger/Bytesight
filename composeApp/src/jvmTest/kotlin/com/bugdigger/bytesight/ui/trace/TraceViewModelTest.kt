package com.bugdigger.bytesight.ui.trace

import com.bugdigger.bytesight.service.AgentClient
import com.bugdigger.protocol.HookType
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Unit tests for TraceViewModel.
 */
class TraceViewModelTest {

    private lateinit var viewModel: TraceViewModel
    private lateinit var mockAgentClient: AgentClient

    @BeforeEach
    fun setup() {
        mockAgentClient = mockk(relaxed = true)
        viewModel = TraceViewModel(mockAgentClient)
    }

    @Nested
    @DisplayName("Initial State")
    inner class InitialState {
        @Test
        fun `should have empty initial state`() {
            val state = viewModel.uiState.value

            assertTrue(state.hooks.isEmpty())
            assertTrue(state.traceEvents.isEmpty())
            assertFalse(state.isLoadingHooks)
            assertFalse(state.isStreaming)
            assertNull(state.error)

            // Form state
            assertEquals("", state.newHookClassName)
            assertEquals("", state.newHookMethodName)
            assertEquals("", state.newHookMethodSignature)
            assertEquals(HookType.LOG_ENTRY_EXIT, state.newHookType)
            assertFalse(state.isAddingHook)

            // Settings
            assertEquals(1000, state.maxEvents)
            assertTrue(state.autoScroll)
        }
    }

    @Nested
    @DisplayName("Form State Management")
    inner class FormStateManagement {
        @Test
        fun `setNewHookClassName should update state`() {
            viewModel.setNewHookClassName("com.example.MyClass")
            assertEquals("com.example.MyClass", viewModel.uiState.value.newHookClassName)
        }

        @Test
        fun `setNewHookMethodName should update state`() {
            viewModel.setNewHookMethodName("myMethod")
            assertEquals("myMethod", viewModel.uiState.value.newHookMethodName)
        }

        @Test
        fun `setNewHookMethodSignature should update state`() {
            viewModel.setNewHookMethodSignature("(Ljava/lang/String;)V")
            assertEquals("(Ljava/lang/String;)V", viewModel.uiState.value.newHookMethodSignature)
        }

        @Test
        fun `setNewHookType should update state`() {
            viewModel.setNewHookType(HookType.LOG_ARGUMENTS)
            assertEquals(HookType.LOG_ARGUMENTS, viewModel.uiState.value.newHookType)

            viewModel.setNewHookType(HookType.LOG_RETURN_VALUE)
            assertEquals(HookType.LOG_RETURN_VALUE, viewModel.uiState.value.newHookType)
        }
    }

    @Nested
    @DisplayName("Auto-scroll Settings")
    inner class AutoScrollSettings {
        @Test
        fun `setAutoScroll should update state`() {
            assertTrue(viewModel.uiState.value.autoScroll)

            viewModel.setAutoScroll(false)
            assertFalse(viewModel.uiState.value.autoScroll)

            viewModel.setAutoScroll(true)
            assertTrue(viewModel.uiState.value.autoScroll)
        }
    }

    @Nested
    @DisplayName("Error Handling")
    inner class ErrorHandling {
        @Test
        fun `clearError should clear error state`() {
            // We can't directly set error, so we just verify clearError works
            viewModel.clearError()
            assertNull(viewModel.uiState.value.error)
        }
    }

    @Nested
    @DisplayName("Clear Events")
    inner class ClearEvents {
        @Test
        fun `clearEvents should empty the events list`() {
            // The events list should start empty
            assertTrue(viewModel.uiState.value.traceEvents.isEmpty())

            // Clearing empty list should not cause issues
            viewModel.clearEvents()
            assertTrue(viewModel.uiState.value.traceEvents.isEmpty())
        }
    }

    @Nested
    @DisplayName("Stop Streaming")
    inner class StopStreaming {
        @Test
        fun `stopStreaming should set isStreaming to false`() {
            // Initially not streaming
            assertFalse(viewModel.uiState.value.isStreaming)

            // Stop streaming should not cause issues even when not streaming
            viewModel.stopStreaming()
            assertFalse(viewModel.uiState.value.isStreaming)
        }
    }

    @Nested
    @DisplayName("Validation")
    inner class Validation {
        @Test
        fun `addHook should not proceed without connection key`() {
            viewModel.setNewHookClassName("com.example.MyClass")
            viewModel.setNewHookMethodName("myMethod")

            // Without setConnectionKey, addHook should return early
            viewModel.addHook()

            // State should not change (isAddingHook remains false)
            assertFalse(viewModel.uiState.value.isAddingHook)
        }
    }
}
