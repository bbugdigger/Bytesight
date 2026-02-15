package com.bugdigger.bytesight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bugdigger.bytesight.ui.attach.AttachScreen
import com.bugdigger.bytesight.ui.attach.AttachViewModel
import com.bugdigger.bytesight.ui.browser.ClassBrowserScreen
import com.bugdigger.bytesight.ui.browser.ClassBrowserViewModel
import com.bugdigger.bytesight.ui.navigation.NavigationState
import com.bugdigger.bytesight.ui.navigation.Screen
import com.bugdigger.bytesight.ui.navigation.Sidebar
import com.bugdigger.bytesight.ui.theme.BytesightTheme
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

        Screen.TRACE -> {
            // TODO: Implement TraceScreen
            PlaceholderScreen(
                title = "Trace",
                message = "Method tracing coming soon...",
                modifier = modifier,
            )
        }

        Screen.SETTINGS -> {
            // TODO: Implement SettingsScreen
            PlaceholderScreen(
                title = "Settings",
                message = "Settings coming soon...",
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun PlaceholderScreen(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}