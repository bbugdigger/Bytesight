package com.bugdigger.bytesight.ui.navigation

/**
 * Represents the different screens/destinations in the application.
 */
enum class Screen(val title: String, val icon: String) {
    ATTACH("Attach", "▶"),
    CLASS_BROWSER("Classes", "📦"),
    TRACE("Trace", "📊"),
    SETTINGS("Settings", "⚙"),
}

/**
 * Navigation state for the application.
 */
data class NavigationState(
    val currentScreen: Screen = Screen.ATTACH,
    val isConnected: Boolean = false,
    val connectionKey: String? = null,
)
