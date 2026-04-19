package com.bugdigger.bytesight.ui.navigation

/**
 * Represents the different screens/destinations in the application.
 */
enum class Screen(val title: String, val icon: String) {
    ATTACH("Attach", "▶"),
    CLASS_BROWSER("Classes", "📦"),
    HIERARCHY("Hierarchy", "🌳"),
    INSPECTOR("Inspector", "🔍"),
    STRINGS("Strings", "📝"),
    TRACE("Trace", "📊"),
    HEAP("Heap", "💾"),
    DEBUGGER("Debugger", "🐞"),
    AI("AI", "✨"),
    SETTINGS("Settings", "⚙"),
}

/**
 * Navigation state for the application.
 */
data class NavigationState(
    val currentScreen: Screen = Screen.ATTACH,
    val isConnected: Boolean = false,
    val connectionKey: String? = null,
    /** When non-null, the Inspector screen should auto-select this class and clear the field. */
    val pendingInspectorClass: String? = null,
    /** When non-null, the AI screen should auto-send this prompt and clear the field. */
    val pendingAIPrompt: String? = null,
)
