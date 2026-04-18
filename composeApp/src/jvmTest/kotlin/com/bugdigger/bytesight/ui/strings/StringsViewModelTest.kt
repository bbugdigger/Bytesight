package com.bugdigger.bytesight.ui.strings

import com.bugdigger.bytesight.service.AgentClient
import com.bugdigger.bytesight.service.RenameStore
import com.bugdigger.core.analysis.ConstantType
import com.bugdigger.core.analysis.StringPattern
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
 * Unit tests for [StringsViewModel].
 */
class StringsViewModelTest {

    private lateinit var viewModel: StringsViewModel
    private lateinit var mockAgentClient: AgentClient

    @BeforeEach
    fun setup() {
        mockAgentClient = mockk(relaxed = true)
        viewModel = StringsViewModel(mockAgentClient, RenameStore())
    }

    @Nested
    @DisplayName("Initial State")
    inner class InitialState {
        @Test
        fun `should have empty initial state`() {
            val state = viewModel.uiState.value
            assertTrue(state.constants.isEmpty())
            assertTrue(state.filteredConstants.isEmpty())
            assertEquals("", state.searchQuery)
            assertEquals(ConstantType.entries.toSet(), state.typeFilter)
            assertNull(state.patternFilter)
            assertFalse(state.isExtracting)
            assertEquals(0f, state.progress)
            assertNull(state.error)
        }
    }

    @Nested
    @DisplayName("Search Filtering")
    inner class SearchFiltering {
        @Test
        fun `setSearchQuery should update search query`() {
            viewModel.setSearchQuery("hello")
            assertEquals("hello", viewModel.uiState.value.searchQuery)
        }

        @Test
        fun `setSearchQuery to empty should clear search`() {
            viewModel.setSearchQuery("test")
            viewModel.setSearchQuery("")
            assertEquals("", viewModel.uiState.value.searchQuery)
        }
    }

    @Nested
    @DisplayName("Type Filtering")
    inner class TypeFiltering {
        @Test
        fun `toggleTypeFilter should remove type from filter`() {
            viewModel.toggleTypeFilter(ConstantType.STRING)
            assertFalse(ConstantType.STRING in viewModel.uiState.value.typeFilter)
        }

        @Test
        fun `toggleTypeFilter should add type back to filter`() {
            viewModel.toggleTypeFilter(ConstantType.STRING)
            viewModel.toggleTypeFilter(ConstantType.STRING)
            assertTrue(ConstantType.STRING in viewModel.uiState.value.typeFilter)
        }
    }

    @Nested
    @DisplayName("Pattern Filtering")
    inner class PatternFiltering {
        @Test
        fun `togglePatternFilter should add pattern to filter`() {
            viewModel.togglePatternFilter(StringPattern.URL)
            val patternFilter = viewModel.uiState.value.patternFilter
            assertFalse(patternFilter == null)
            assertTrue(StringPattern.URL in patternFilter!!)
        }

        @Test
        fun `togglePatternFilter should remove pattern and clear filter when empty`() {
            viewModel.togglePatternFilter(StringPattern.URL)
            viewModel.togglePatternFilter(StringPattern.URL)
            assertNull(viewModel.uiState.value.patternFilter)
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

    @Nested
    @DisplayName("Extract Without Connection")
    inner class ExtractWithoutConnection {
        @Test
        fun `extractAll should return early without connection key`() {
            viewModel.extractAll()
            assertFalse(viewModel.uiState.value.isExtracting)
        }
    }
}
