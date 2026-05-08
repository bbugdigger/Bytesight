package com.bugdigger.bytesight.ui.navigation

import com.bugdigger.bytesight.source.Capability

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
    BYTECODE_DIFF("Diff", "🔀"),
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
    /** Capabilities of the active source. Empty when no source is installed. */
    val capabilities: Set<Capability> = emptySet(),
    /** When non-null, the Inspector screen should auto-select this class and clear the field. */
    val pendingInspectorClass: String? = null,
    /**
     * Optional method to auto-select after [pendingInspectorClass] resolves.
     * The Debugger Call Stack panel writes both fields when "Inspect" is
     * clicked on a frame so the Inspector lands ready-to-read instead of
     * forcing the user to re-pick the method from the dropdown.
     */
    val pendingInspectorMethod: String? = null,
    /** Method descriptor (JVM signature) for overload disambiguation. */
    val pendingInspectorMethodSignature: String? = null,
    /** When non-null, the AI screen should auto-send this prompt and clear the field. */
    val pendingAIPrompt: String? = null,
)
