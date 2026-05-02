package com.bugdigger.bytesight.debugger

import com.bugdigger.protocol.DebuggerEvent
import com.bugdigger.protocol.ThreadState
import com.bugdigger.protocol.StepKind
import com.bugdigger.protocol.breakpointHit
import com.bugdigger.protocol.debuggerEvent
import com.bugdigger.protocol.frameSnapshot
import com.bugdigger.protocol.stepCompleted
import com.bugdigger.protocol.threadStateChanged
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ReplayCursor]. Verifies that seeking to a sequence id
 * materializes the correct historical snapshot from the [RecordingLog].
 */
class ReplayCursorTest {

    private fun frame(cls: String, method: String, line: Int = 0) = frameSnapshot {
        this.className = cls
        this.methodName = method
        this.lineNumber = line
    }

    private fun hitEvent(
        seq: Long,
        threadId: Long,
        threadName: String = "t-$threadId",
        topClass: String = "Foo",
        topMethod: String = "bar",
        topLine: Int = 10,
        stackDepth: Int = 1,
    ): DebuggerEvent = debuggerEvent {
        this.sequenceId = seq
        this.timestampNs = seq * 1000L
        this.hit = breakpointHit {
            this.breakpointId = "bp-$seq"
            this.threadId = threadId
            this.threadName = threadName
            this.topFrame = frame(topClass, topMethod, topLine)
            // Real agent emissions have stack[0] == topFrame; deeper indices are callers.
            this.stack.add(frame(topClass, topMethod, topLine))
            for (i in 1 until stackDepth) {
                this.stack.add(frame(topClass, "$topMethod-caller-$i", topLine + i))
            }
        }
    }

    private fun stepEvent(seq: Long, threadId: Long, line: Int): DebuggerEvent = debuggerEvent {
        this.sequenceId = seq
        this.timestampNs = seq * 1000L
        this.step = stepCompleted {
            this.threadId = threadId
            this.threadName = "t-$threadId"
            this.kind = StepKind.STEP_OVER
            this.topFrame = frame("Foo", "bar", line)
            this.stack.add(frame("Foo", "bar-0", line))
        }
    }

    private fun threadEvent(seq: Long, threadId: Long, name: String, state: ThreadState): DebuggerEvent =
        debuggerEvent {
            this.sequenceId = seq
            this.timestampNs = seq * 1000L
            this.thread = threadStateChanged {
                this.threadId = threadId
                this.threadName = name
                this.state = state
            }
        }

    private fun newLogWith(events: List<DebuggerEvent>): RecordingLog =
        RecordingLog().also { it.replaceEvents(events) }

    @Nested
    @DisplayName("seekTo")
    inner class SeekTo {
        @Test
        fun `seek to a hit's sequence id materializes its frame and stack`() {
            val log = newLogWith(listOf(
                hitEvent(seq = 1L, threadId = 1L, topMethod = "first", topLine = 5, stackDepth = 2),
                hitEvent(seq = 5L, threadId = 1L, topMethod = "second", topLine = 12, stackDepth = 3),
            ))
            val cursor = ReplayCursor(log, initialSequenceId = 5L)

            assertEquals(5L, cursor.currentSequenceId.value)
            assertEquals("second", cursor.currentFrame.value?.methodName)
            assertEquals(12, cursor.currentFrame.value?.lineNumber)
            assertEquals(3, cursor.callStack.value.size)
            assertEquals("bp-5", cursor.lastHit.value?.breakpointId)
        }

        @Test
        fun `seek before any event yields empty state`() {
            val log = newLogWith(listOf(hitEvent(seq = 5L, threadId = 1L)))
            val cursor = ReplayCursor(log, initialSequenceId = 1L)

            assertNull(cursor.currentFrame.value)
            assertTrue(cursor.callStack.value.isEmpty())
            assertNull(cursor.lastHit.value)
        }

        @Test
        fun `seek between hits shows the latest hit's snapshot`() {
            val log = newLogWith(listOf(
                hitEvent(seq = 1L, threadId = 1L, topMethod = "first"),
                hitEvent(seq = 10L, threadId = 1L, topMethod = "second"),
            ))
            val cursor = ReplayCursor(log, initialSequenceId = 5L)

            // At seq=5, the latest captured frame is from seq=1.
            assertEquals("first", cursor.currentFrame.value?.methodName)
        }

        @Test
        fun `seekTo can move forward and backward freely`() {
            val log = newLogWith(listOf(
                hitEvent(seq = 1L, threadId = 1L, topMethod = "a"),
                hitEvent(seq = 5L, threadId = 1L, topMethod = "b"),
                hitEvent(seq = 10L, threadId = 1L, topMethod = "c"),
            ))
            val cursor = ReplayCursor(log, initialSequenceId = 5L)
            assertEquals("b", cursor.currentFrame.value?.methodName)

            cursor.seekTo(10L)
            assertEquals("c", cursor.currentFrame.value?.methodName)

            cursor.seekTo(1L)
            assertEquals("a", cursor.currentFrame.value?.methodName)
        }

        @Test
        fun `step events also update the current frame`() {
            val log = newLogWith(listOf(
                hitEvent(seq = 1L, threadId = 1L, topLine = 10),
                stepEvent(seq = 2L, threadId = 1L, line = 11),
            ))
            val cursor = ReplayCursor(log, initialSequenceId = 2L)

            assertEquals(11, cursor.currentFrame.value?.lineNumber)
        }
    }

    @Nested
    @DisplayName("Threads view")
    inner class ThreadsView {
        @Test
        fun `threads list contains all threads seen up to cursor`() {
            val log = newLogWith(listOf(
                hitEvent(seq = 1L, threadId = 1L, threadName = "main"),
                hitEvent(seq = 2L, threadId = 2L, threadName = "worker"),
            ))
            val cursor = ReplayCursor(log, initialSequenceId = 2L)

            val ids = cursor.threads.value.map { it.id }.toSet()
            assertEquals(setOf(1L, 2L), ids)
            assertTrue(cursor.threads.value.any { it.name == "worker" })
        }

        @Test
        fun `threads observed strictly after cursor are not visible`() {
            val log = newLogWith(listOf(
                hitEvent(seq = 1L, threadId = 1L, threadName = "main"),
                hitEvent(seq = 5L, threadId = 2L, threadName = "worker"),
            ))
            val cursor = ReplayCursor(log, initialSequenceId = 1L)

            val ids = cursor.threads.value.map { it.id }.toSet()
            assertEquals(setOf(1L), ids)
        }

        @Test
        fun `currentThreadId defaults to the thread of latest event at cursor`() {
            val log = newLogWith(listOf(
                hitEvent(seq = 1L, threadId = 1L),
                hitEvent(seq = 5L, threadId = 2L),
            ))
            val cursor = ReplayCursor(log, initialSequenceId = 5L)
            assertEquals(2L, cursor.currentThreadId.value)
        }

        @Test
        fun `selectThread switches to that thread's latest hit's stack`() {
            val log = newLogWith(listOf(
                hitEvent(seq = 1L, threadId = 1L, topMethod = "main_a"),
                hitEvent(seq = 5L, threadId = 2L, topMethod = "worker_b", stackDepth = 2),
                hitEvent(seq = 10L, threadId = 1L, topMethod = "main_c"),
            ))
            val cursor = ReplayCursor(log, initialSequenceId = 10L)

            // Default focus is thread 1 (latest event), showing main_c
            assertEquals("main_c", cursor.currentFrame.value?.methodName)

            cursor.selectThread(2L)
            assertEquals(2L, cursor.currentThreadId.value)
            assertEquals("worker_b", cursor.currentFrame.value?.methodName)
            assertEquals(2, cursor.callStack.value.size)
        }
    }

    @Nested
    @DisplayName("selectFrame")
    inner class SelectFrame {
        @Test
        fun `selectFrame indexes into the current call stack`() {
            val log = newLogWith(listOf(hitEvent(seq = 1L, threadId = 1L, stackDepth = 3)))
            val cursor = ReplayCursor(log, initialSequenceId = 1L)

            // depth 0 = top, depth 1 = caller, depth 2 = caller's caller
            cursor.selectFrame(2)
            val frame = cursor.currentFrame.value
            assertNotNull(frame)
            assertTrue(frame.methodName.endsWith("-2"))
        }

        @Test
        fun `selectFrame out of range yields null`() {
            val log = newLogWith(listOf(hitEvent(seq = 1L, threadId = 1L, stackDepth = 1)))
            val cursor = ReplayCursor(log, initialSequenceId = 1L)
            cursor.selectFrame(99)
            assertNull(cursor.currentFrame.value)
        }
    }

    @Nested
    @DisplayName("Thread state events")
    inner class ThreadStateEvents {
        @Test
        fun `ThreadStateChanged registers a thread without producing a hit`() {
            val log = newLogWith(listOf(
                threadEvent(seq = 1L, threadId = 1L, name = "main", state = ThreadState.THREAD_STATE_RUNNING),
            ))
            val cursor = ReplayCursor(log, initialSequenceId = 1L)

            assertEquals(1, cursor.threads.value.size)
            assertEquals("main", cursor.threads.value.first().name)
            // No frames captured for a thread state event alone.
            assertNull(cursor.currentFrame.value)
        }
    }
}
