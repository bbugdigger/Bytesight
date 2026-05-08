package com.bugdigger.bytesight.debugger

import com.bugdigger.protocol.MethodBreakpointMode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DebuggerStateSerializationTest {

    @Test
    fun `round-trips persisted fields and resets runtime fields`() {
        val original = DebuggerState().apply {
            addBreakpoint(
                DebuggerState.UiBreakpoint(
                    id = "bp-1",
                    className = "a.B",
                    methodName = "foo",
                    methodSignature = "()V",
                    displayLine = 42,
                    mode = MethodBreakpointMode.METHOD_BP_ENTRY,
                    enabled = true,
                    condition = "x > 0",
                    skipCount = 3,
                    hitCount = 999,           // runtime — should NOT survive
                    conditionError = "uh oh", // runtime — should NOT survive
                ),
            )
        }

        val text = original.serialize()
        val restored = DebuggerState().apply { restore(text) }
        val bp = restored.breakpoints.value.single()

        assertEquals("bp-1", bp.id)
        assertEquals("a.B", bp.className)
        assertEquals("foo", bp.methodName)
        assertEquals("()V", bp.methodSignature)
        assertEquals(42, bp.displayLine)
        assertEquals(MethodBreakpointMode.METHOD_BP_ENTRY, bp.mode)
        assertEquals(true, bp.enabled)
        assertEquals("x > 0", bp.condition)
        assertEquals(3, bp.skipCount)
        assertEquals(0, bp.hitCount)        // reset
        assertNull(bp.conditionError)       // reset
    }

    @Test
    fun `restore with empty list clears the breakpoint list`() {
        val state = DebuggerState().apply {
            addBreakpoint(
                DebuggerState.UiBreakpoint(
                    id = "x", className = "a.B", methodName = "f", methodSignature = "()V",
                    displayLine = 0, mode = MethodBreakpointMode.METHOD_BP_ENTRY, enabled = true,
                ),
            )
        }
        state.restore("[]")
        assertEquals(emptyList<DebuggerState.UiBreakpoint>(), state.breakpoints.value)
    }
}
