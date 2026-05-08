package com.bugdigger.bytesight.source

/**
 * What a [ClassSource] can do beyond static class inspection.
 *
 * STATIC_ANALYSIS is implicit (every source supports it). The other entries
 * are runtime-only — they require a live agent attached to a JVM.
 *
 * Used by the Sidebar to gate Trace / Heap / Debugger tabs and by the
 * routing in [com.bugdigger.bytesight.App] to skip rendering screens whose
 * required capabilities are absent.
 */
enum class Capability {
    /** Listing classes, fetching bytecode, decompiling, hierarchy, strings. Always present. */
    STATIC_ANALYSIS,

    /** Method tracing via the agent's hook RPCs. */
    LIVE_TRACE,

    /** Heap snapshot capture and exploration. */
    LIVE_HEAP,

    /** Breakpoints, stepping, debugger event subscription. */
    LIVE_DEBUG,
    ;

    companion object {
        /** All four capabilities — the set declared by an attached live agent today. */
        val ALL: Set<Capability> = entries.toSet()

        /** Just static analysis — declared by future JAR/APK/.bts sources. */
        val STATIC_ONLY: Set<Capability> = setOf(STATIC_ANALYSIS)
    }
}
