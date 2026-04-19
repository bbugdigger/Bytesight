package com.bugdigger.bytesight.debugger

import com.bugdigger.protocol.BreakpointHit
import com.bugdigger.protocol.FrameSnapshot
import com.bugdigger.protocol.ThreadState
import kotlinx.coroutines.flow.StateFlow

/**
 * Read-only view of "current program state" seen by the Debugger UI.
 *
 * v1: backed by [LiveCursor], which reads live JVM state via the agent.
 * v4+ (time-travel): a ReplayCursor will implement this same interface against a
 * recorded event log, so the UI needs no changes when TTD ships. This is the
 * single most important architectural constraint in v1 — the UI **never** talks
 * to AgentClient for debugger state, only to the cursor.
 */
interface ExecutionCursor {
    val threads: StateFlow<List<ThreadView>>
    val currentThreadId: StateFlow<Long?>
    val currentFrame: StateFlow<FrameSnapshot?>
    val callStack: StateFlow<List<FrameSnapshot>>
    val lastHit: StateFlow<BreakpointHit?>

    fun selectThread(threadId: Long)
    fun selectFrame(depth: Int)
}

data class ThreadView(
    val id: Long,
    val name: String,
    val state: ThreadState,
)
