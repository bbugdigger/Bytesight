package com.bugdigger.agent.debugger;

/**
 * Fixture for {@link StepControllerTest}. Has three sequential statements on
 * three known lines so Step Over can be observed advancing line by line.
 * Update {@link StepFixtureLines} if any statements move.
 */
final class StepFixture {

    private StepFixture() {}

    static int target() {
        int x = 0;                // LINE_1
        x = x + 1;                // LINE_2
        return x * 3;             // LINE_3
    }
}
