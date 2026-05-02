package com.bugdigger.agent.debugger;

/**
 * Test fixture used by {@link BreakpointManagerLineBpTest}. The line numbers
 * in this file are referenced by {@link LineFixtureLines}; if you renumber
 * statements, update {@code X_PLUS_1_LINE} below.
 */
final class LineFixture {

    private LineFixture() {}

    static int target() {
        int x = 0;                // line X_PLUS_1_LINE - 1
        x = x + 1;                // <-- LineFixtureLines.X_PLUS_1_LINE — bp probe goes here
        return x * 2;
    }
}
