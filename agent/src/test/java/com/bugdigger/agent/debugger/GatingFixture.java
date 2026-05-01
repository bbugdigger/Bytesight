package com.bugdigger.agent.debugger;

/** Test fixture for {@link BreakpointGatingTest}. */
final class GatingFixture {
    private GatingFixture() {}

    static int invoke() { return 1; }

    static int invokeWithArg(int arg0) { return arg0 * 2; }
}
