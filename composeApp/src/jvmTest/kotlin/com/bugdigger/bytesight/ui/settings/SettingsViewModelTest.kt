package com.bugdigger.bytesight.ui.settings

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Unit tests for SettingsViewModel.
 */
class SettingsViewModelTest {

    private lateinit var viewModel: SettingsViewModel

    @BeforeEach
    fun setup() {
        viewModel = SettingsViewModel()
    }

    @Nested
    @DisplayName("Initial State")
    inner class InitialState {
        @Test
        fun `should have default values on initialization`() {
            val state = viewModel.uiState.value

            // Decompiler settings
            assertTrue(state.showLineNumbers)
            assertFalse(state.showBytecodeComments)
            assertTrue(state.simplifyLambdas)

            // Trace settings
            assertEquals(1000, state.maxTraceEvents)
            assertTrue(state.traceArguments)
            assertTrue(state.traceReturnValues)
            assertTrue(state.traceExceptions)
            assertTrue(state.traceTiming)

            // Connection settings
            assertEquals(5050, state.defaultPort)
            assertEquals(5000, state.connectionTimeoutMs)

            // UI settings
            assertEquals(13, state.fontSize)
        }
    }

    @Nested
    @DisplayName("Decompiler Settings")
    inner class DecompilerSettings {
        @Test
        fun `setShowLineNumbers should update state`() {
            viewModel.setShowLineNumbers(false)
            assertFalse(viewModel.uiState.value.showLineNumbers)

            viewModel.setShowLineNumbers(true)
            assertTrue(viewModel.uiState.value.showLineNumbers)
        }

        @Test
        fun `setShowBytecodeComments should update state`() {
            viewModel.setShowBytecodeComments(true)
            assertTrue(viewModel.uiState.value.showBytecodeComments)

            viewModel.setShowBytecodeComments(false)
            assertFalse(viewModel.uiState.value.showBytecodeComments)
        }

        @Test
        fun `setSimplifyLambdas should update state`() {
            viewModel.setSimplifyLambdas(false)
            assertFalse(viewModel.uiState.value.simplifyLambdas)
        }
    }

    @Nested
    @DisplayName("Trace Settings")
    inner class TraceSettings {
        @Test
        fun `setMaxTraceEvents should update state within valid range`() {
            viewModel.setMaxTraceEvents(5000)
            assertEquals(5000, viewModel.uiState.value.maxTraceEvents)
        }

        @Test
        fun `setMaxTraceEvents should coerce value to minimum`() {
            viewModel.setMaxTraceEvents(50)
            assertEquals(100, viewModel.uiState.value.maxTraceEvents)
        }

        @Test
        fun `setMaxTraceEvents should coerce value to maximum`() {
            viewModel.setMaxTraceEvents(20000)
            assertEquals(10000, viewModel.uiState.value.maxTraceEvents)
        }

        @Test
        fun `setTraceArguments should update state`() {
            viewModel.setTraceArguments(false)
            assertFalse(viewModel.uiState.value.traceArguments)
        }

        @Test
        fun `setTraceReturnValues should update state`() {
            viewModel.setTraceReturnValues(false)
            assertFalse(viewModel.uiState.value.traceReturnValues)
        }

        @Test
        fun `setTraceExceptions should update state`() {
            viewModel.setTraceExceptions(false)
            assertFalse(viewModel.uiState.value.traceExceptions)
        }

        @Test
        fun `setTraceTiming should update state`() {
            viewModel.setTraceTiming(false)
            assertFalse(viewModel.uiState.value.traceTiming)
        }
    }

    @Nested
    @DisplayName("Connection Settings")
    inner class ConnectionSettings {
        @Test
        fun `setDefaultPort should update state within valid range`() {
            viewModel.setDefaultPort(8080)
            assertEquals(8080, viewModel.uiState.value.defaultPort)
        }

        @Test
        fun `setDefaultPort should coerce value to minimum`() {
            viewModel.setDefaultPort(100)
            assertEquals(1024, viewModel.uiState.value.defaultPort)
        }

        @Test
        fun `setDefaultPort should coerce value to maximum`() {
            viewModel.setDefaultPort(70000)
            assertEquals(65535, viewModel.uiState.value.defaultPort)
        }

        @Test
        fun `setConnectionTimeout should update state within valid range`() {
            viewModel.setConnectionTimeout(10000)
            assertEquals(10000, viewModel.uiState.value.connectionTimeoutMs)
        }

        @Test
        fun `setConnectionTimeout should coerce value to minimum`() {
            viewModel.setConnectionTimeout(500)
            assertEquals(1000, viewModel.uiState.value.connectionTimeoutMs)
        }

        @Test
        fun `setConnectionTimeout should coerce value to maximum`() {
            viewModel.setConnectionTimeout(60000)
            assertEquals(30000, viewModel.uiState.value.connectionTimeoutMs)
        }
    }

    @Nested
    @DisplayName("UI Settings")
    inner class UISettings {
        @Test
        fun `setFontSize should update state within valid range`() {
            viewModel.setFontSize(16)
            assertEquals(16, viewModel.uiState.value.fontSize)
        }

        @Test
        fun `setFontSize should coerce value to minimum`() {
            viewModel.setFontSize(5)
            assertEquals(10, viewModel.uiState.value.fontSize)
        }

        @Test
        fun `setFontSize should coerce value to maximum`() {
            viewModel.setFontSize(30)
            assertEquals(24, viewModel.uiState.value.fontSize)
        }
    }

    @Nested
    @DisplayName("Reset to Defaults")
    inner class ResetToDefaults {
        @Test
        fun `resetToDefaults should restore all values to defaults`() {
            // Change all values
            viewModel.setShowLineNumbers(false)
            viewModel.setShowBytecodeComments(true)
            viewModel.setSimplifyLambdas(false)
            viewModel.setMaxTraceEvents(5000)
            viewModel.setTraceArguments(false)
            viewModel.setDefaultPort(8080)
            viewModel.setConnectionTimeout(15000)
            viewModel.setFontSize(18)

            // Reset
            viewModel.resetToDefaults()

            // Verify defaults are restored
            val state = viewModel.uiState.value
            assertTrue(state.showLineNumbers)
            assertFalse(state.showBytecodeComments)
            assertTrue(state.simplifyLambdas)
            assertEquals(1000, state.maxTraceEvents)
            assertTrue(state.traceArguments)
            assertEquals(5050, state.defaultPort)
            assertEquals(5000, state.connectionTimeoutMs)
            assertEquals(13, state.fontSize)
        }
    }
}
