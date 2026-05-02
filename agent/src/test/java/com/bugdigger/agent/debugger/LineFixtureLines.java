package com.bugdigger.agent.debugger;

/**
 * Hard-coded line numbers from {@link LineFixture}. Kept separate so the
 * fixture file's content + line numbering can be reasoned about in isolation.
 */
final class LineFixtureLines {
    /** The {@code x = x + 1;} statement inside {@code LineFixture.target()}. */
    static final int X_PLUS_1_LINE = 14;

    private LineFixtureLines() {}
}
