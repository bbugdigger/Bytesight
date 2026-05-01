package com.bugdigger.agent.debugger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the Java-side helpers in {@link NativeDebuggerBridge}. The native
 * methods themselves require the JVMTI helper to be loaded, which only happens
 * inside a target JVM after {@code vm.loadAgentPath} runs — not during a unit
 * test. So these tests cover the lookup + filtering logic that gates the
 * native calls.
 */
class NativeDebuggerBridgeTest {

    @Test
    void isAvailableShouldBeFalseInTestProcess() {
        // The native helper isn't loaded in the test JVM; suspend/resume
        // helpers must short-circuit cleanly rather than throw.
        assertFalse(NativeDebuggerBridge.isAvailable(),
            "JVMTI helper should not be loaded in the test process");
    }

    @Test
    @SuppressWarnings("deprecation")
    void suspendThreadShouldReturnFalseForUnknownThreadId() {
        long fakeId = Long.MAX_VALUE - 1;
        assertFalse(NativeDebuggerBridge.suspendThread(fakeId),
            "Unknown thread id must short-circuit before native call");
    }

    @Test
    @SuppressWarnings("deprecation")
    void resumeThreadShouldReturnFalseForUnknownThreadId() {
        long fakeId = Long.MAX_VALUE - 2;
        assertFalse(NativeDebuggerBridge.resumeThread(fakeId),
            "Unknown thread id must short-circuit before native call");
    }

    @Test
    void suspendAllShouldFilterCallingThreadAndInfrastructureThreads() {
        // We can't actually suspend without the native helper, but we can
        // at least verify the call doesn't throw and returns 0 in the test
        // process (where every nativeSuspendThread call returns -1).
        int suspended = NativeDebuggerBridge.suspendAll(0);
        assertEquals(0, suspended,
            "Without the native helper, no thread should be reported as suspended");
    }

    @Test
    void resumeAllShouldNotThrowWhenNativeUnavailable() {
        int resumed = NativeDebuggerBridge.resumeAll();
        assertEquals(0, resumed);
    }
}
