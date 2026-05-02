package com.bugdigger.bytesight.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Application sidebar using NavigationRail.
 * Provides navigation between main application screens.
 */
@Composable
fun Sidebar(
    currentScreen: Screen,
    isConnected: Boolean,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationRail(
        modifier = modifier.fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Spacer(Modifier.height(12.dp))

        // Connection status indicator
        ConnectionIndicator(isConnected = isConnected)

        Spacer(Modifier.height(24.dp))

        // Main navigation items
        Screen.entries.forEach { screen ->
            val enabled = when (screen) {
                Screen.ATTACH -> true
                Screen.CLASS_BROWSER, Screen.TRACE,
                Screen.HIERARCHY, Screen.INSPECTOR, Screen.STRINGS, Screen.HEAP,
                Screen.DEBUGGER -> isConnected
                Screen.AI -> true
                Screen.SETTINGS -> true
            }

            NavigationRailItem(
                selected = currentScreen == screen,
                onClick = { if (enabled) onNavigate(screen) },
                enabled = enabled,
                icon = {
                    Text(
                        text = screen.icon,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                label = { Text(screen.title) },
                alwaysShowLabel = true,
            )
        }

        Spacer(Modifier.weight(1f))
    }
}

/**
 * Small indicator showing connection status.
 */
@Composable
private fun ConnectionIndicator(
    isConnected: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = if (isConnected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }

    val text = if (isConnected) "Connected" else "Not connected"

    Column(
        modifier = modifier.padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = color.copy(alpha = 0.2f),
            contentColor = color,
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
