package com.bugdigger.bytesight.ui.hierarchy

import com.bugdigger.bytesight.service.AgentClient
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
 * Unit tests for [HierarchyViewModel].
 */
class HierarchyViewModelTest {

    private lateinit var viewModel: HierarchyViewModel
    private lateinit var mockAgentClient: AgentClient

    @BeforeEach
    fun setup() {
        mockAgentClient = mockk(relaxed = true)
        viewModel = HierarchyViewModel(mockAgentClient)
    }

    @Nested
    @DisplayName("Initial State")
    inner class InitialState {
        @Test
        fun `should have empty initial state`() {
            val state = viewModel.uiState.value
            assertTrue(state.allRoots.isEmpty())
            assertTrue(state.filteredRoots.isEmpty())
            assertNull(state.selectedClass)
            assertTrue(state.selectedAncestors.isEmpty())
            assertNull(state.selectedClassInfo)
            assertEquals("", state.searchQuery)
            assertTrue(state.showInterfaces)
            assertTrue(state.showClasses)
            assertTrue(state.expandedNodes.isEmpty())
            assertFalse(state.isLoading)
            assertNull(state.error)
        }
    }

    @Nested
    @DisplayName("Search")
    inner class Search {
        @Test
        fun `setSearchQuery should update search query`() {
            viewModel.setSearchQuery("com.example")
            assertEquals("com.example", viewModel.uiState.value.searchQuery)
        }
    }

    @Nested
    @DisplayName("Visibility Toggles")
    inner class VisibilityToggles {
        @Test
        fun `setShowInterfaces should update state`() {
            viewModel.setShowInterfaces(false)
            assertFalse(viewModel.uiState.value.showInterfaces)

            viewModel.setShowInterfaces(true)
            assertTrue(viewModel.uiState.value.showInterfaces)
        }

        @Test
        fun `setShowClasses should update state`() {
            viewModel.setShowClasses(false)
            assertFalse(viewModel.uiState.value.showClasses)

            viewModel.setShowClasses(true)
            assertTrue(viewModel.uiState.value.showClasses)
        }
    }

    @Nested
    @DisplayName("Node Expansion")
    inner class NodeExpansion {
        @Test
        fun `toggleExpanded should add node to expanded set`() {
            viewModel.toggleExpanded("com.example.MyClass")
            assertTrue("com.example.MyClass" in viewModel.uiState.value.expandedNodes)
        }

        @Test
        fun `toggleExpanded twice should remove node from expanded set`() {
            viewModel.toggleExpanded("com.example.MyClass")
            viewModel.toggleExpanded("com.example.MyClass")
            assertFalse("com.example.MyClass" in viewModel.uiState.value.expandedNodes)
        }
    }

    @Nested
    @DisplayName("Selection")
    inner class Selection {
        @Test
        fun `selectClass should update selected class`() {
            viewModel.selectClass("com.example.MyClass")
            assertEquals("com.example.MyClass", viewModel.uiState.value.selectedClass)
        }

        @Test
        fun `selectClass with null should clear selection`() {
            viewModel.selectClass("com.example.MyClass")
            viewModel.selectClass(null)
            assertNull(viewModel.uiState.value.selectedClass)
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
