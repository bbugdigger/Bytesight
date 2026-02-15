package com.bugdigger.agent.hook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HookManager.HookResult.
 */
class HookResultTest {

    @Test
    void success_createsSuccessfulResult() {
        HookManager.HookResult result = HookManager.HookResult.success("hook-123");

        assertTrue(result.isSuccess());
        assertEquals("hook-123", result.getHookId());
        assertNull(result.getError());
    }

    @Test
    void failure_createsFailedResult() {
        HookManager.HookResult result = HookManager.HookResult.failure("Something went wrong");

        assertFalse(result.isSuccess());
        assertNull(result.getHookId());
        assertEquals("Something went wrong", result.getError());
    }
}
