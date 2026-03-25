package com.bugdigger.bytesight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.bugdigger.bytesight.ui.attach.AttachScreen
import com.bugdigger.bytesight.ui.attach.AttachViewModel
import com.bugdigger.bytesight.ui.browser.ClassBrowserScreen
import com.bugdigger.bytesight.ui.browser.ClassBrowserViewModel
import com.bugdigger.bytesight.ui.hierarchy.HierarchyScreen
import com.bugdigger.bytesight.ui.hierarchy.HierarchyViewModel
import com.bugdigger.bytesight.ui.inspector.InspectorScreen
import com.bugdigger.bytesight.ui.inspector.InspectorViewModel
import com.bugdigger.bytesight.ui.navigation.NavigationState
import com.bugdigger.bytesight.ui.navigation.Screen
import com.bugdigger.bytesight.ui.navigation.Sidebar
import com.bugdigger.bytesight.ui.settings.SettingsScreen
import com.bugdigger.bytesight.ui.settings.SettingsViewModel
import com.bugdigger.bytesight.ui.strings.StringsScreen
import com.bugdigger.bytesight.ui.strings.StringsViewModel
import com.bugdigger.bytesight.ui.theme.BytesightTheme
import com.bugdigger.bytesight.ui.trace.TraceScreen
import com.bugdigger.bytesight.ui.trace.TraceViewModel
import org.koin.compose.koinInject

/**
 * Main application composable.
 * Sets up the theme, navigation, and screen routing.
 */
@Composable
fun App() {
    BytesightTheme {
        var navState by remember { mutableStateOf(NavigationState()) }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            // Sidebar navigation
            Sidebar(
                currentScreen = navState.currentScreen,
                isConnected = navState.isConnected,
                onNavigate = { screen ->
                    navState = navState.copy(currentScreen = screen)
                },
            )

            // Main content area
            MainContent(
                navState = navState,
                onConnected = { connectionKey ->
                    navState = navState.copy(
                        isConnected = true,
                        connectionKey = connectionKey,
                        currentScreen = Screen.CLASS_BROWSER,
                    )
                },
                onDisconnected = {
                    navState = navState.copy(
                        isConnected = false,
                        connectionKey = null,
                        currentScreen = Screen.ATTACH,
                    )
                },
            )
        }
    }
}

/**
 * Routes to the appropriate screen based on navigation state.
 */
@Composable
private fun MainContent(
    navState: NavigationState,
    onConnected: (String) -> Unit,
    onDisconnected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (navState.currentScreen) {
        Screen.ATTACH -> {
            val viewModel: AttachViewModel = koinInject()
            AttachScreen(
                viewModel = viewModel,
                onConnected = onConnected,
                modifier = modifier,
            )
        }

        Screen.CLASS_BROWSER -> {
            val connectionKey = navState.connectionKey
            if (connectionKey != null) {
                val viewModel: ClassBrowserViewModel = koinInject()
                ClassBrowserScreen(
                    viewModel = viewModel,
                    connectionKey = connectionKey,
                    modifier = modifier,
                )
            }
        }

        Screen.HIERARCHY -> {
            val connectionKey = navState.connectionKey
            if (connectionKey != null) {
                val viewModel: HierarchyViewModel = koinInject()
                HierarchyScreen(
                    viewModel = viewModel,
                    connectionKey = connectionKey,
                    modifier = modifier,
                )
            }
        }

        Screen.INSPECTOR -> {
            val connectionKey = navState.connectionKey
            if (connectionKey != null) {
                val viewModel: InspectorViewModel = koinInject()
                InspectorScreen(
                    viewModel = viewModel,
                    connectionKey = connectionKey,
                    modifier = modifier,
                )
            }
        }

        Screen.STRINGS -> {
            val connectionKey = navState.connectionKey
            if (connectionKey != null) {
                val viewModel: StringsViewModel = koinInject()
                StringsScreen(
                    viewModel = viewModel,
                    connectionKey = connectionKey,
                    modifier = modifier,
                )
            }
        }

        Screen.TRACE -> {
            val connectionKey = navState.connectionKey
            if (connectionKey != null) {
                val viewModel: TraceViewModel = koinInject()
                TraceScreen(
                    viewModel = viewModel,
                    connectionKey = connectionKey,
                    modifier = modifier,
                )
            }
        }

        Screen.SETTINGS -> {
            val viewModel: SettingsViewModel = koinInject()
            SettingsScreen(
                viewModel = viewModel,
                modifier = modifier,
            )
        }
    }
}