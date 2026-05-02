package com.bugdigger.bytesight.debugger

import com.bugdigger.protocol.DebuggerEvent
import com.bugdigger.protocol.breakpointHit
import com.bugdigger.protocol.debuggerEvent
import com.bugdigger.protocol.frameSnapshot
import com.bugdigger.protocol.stepCompleted
import com.bugdigger.protocol.threadStateChanged
import com.bugdigger.protocol.ThreadState
import com.bugdigger.protocol.StepKind
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Unit tests for [RecordingLog]. Verifies ring-buffer eviction, indexing,
 * directional hit search, and per-thread filtering.
 */
class RecordingLogTest {

    private fun hit(seq: Long, threadId: Long, threadName: String = "t-$threadId"): DebuggerEvent =
        debuggerEvent {
            this.sequenceId = seq
            this.timestampNs = seq * 1000L
            this.hit = breakpointHit {
                this.breakpointId = "bp-1"
                this.threadId = threadId
                this.threadName = threadName
                this.topFrame = frameSnapshot {
                    this.className = "Foo"
                    this.methodName = "bar"
                }
            }
        }

    private fun step(seq: Long, threadId: Long): DebuggerEvent = debuggerEvent {
        this.sequenceId = seq
        this.timestampNs = seq * 1000L
        this.step = stepCompleted {
            this.threadId = threadId
            this.threadName = "t-$threadId"
            this.kind = StepKind.STEP_OVER
            this.topFrame = frameSnapshot { this.className = "Foo"; this.methodName = "bar" }
        }
    }

    private fun threadEvt(seq: Long, threadId: Long, state: ThreadState): DebuggerEvent =
        debuggerEvent {
            this.sequenceId = seq
            this.timestampNs = seq * 1000L
            this.thread = threadStateChanged {
                this.threadId = threadId
                this.threadName = "t-$threadId"
                this.state = state
            }
        }

    @Nested
    @DisplayName("Lifecycle")
    inner class Lifecycle {
        @Test
        fun `new log starts idle and empty`() {
            val log = RecordingLog()
            assertEquals(RecordingState.IDLE, log.state.value)
            assertTrue(log.events.value.isEmpty())
            assertNull(log.firstSequenceId())
            assertNull(log.lastSequenceId())
        }

        @Test
        fun `startRecording transitions to RECORDING`() {
            val log = RecordingLog()
            log.startRecording()
            assertEquals(RecordingState.RECORDING, log.state.value)
        }

        @Test
        fun `record while IDLE is dropped`() {
            val log = RecordingLog()
            log.record(hit(seq = 1L, threadId = 1L))
            assertTrue(log.events.value.isEmpty())
        }

        @Test
        fun `record while RECORDING appends`() {
            val log = RecordingLog()
            log.startRecording()
            log.record(hit(seq = 1L, threadId = 1L))
            log.record(hit(seq = 2L, threadId = 1L))
            assertEquals(2, log.events.value.size)
            assertEquals(1L, log.firstSequenceId())
            assertEquals(2L, log.lastSequenceId())
        }

        @Test
        fun `stopRecording transitions to REPLAY when events exist`() {
            val log = RecordingLog()
            log.startRecording()
            log.record(hit(seq = 1L, threadId = 1L))
            log.stopRecording()
            assertEquals(RecordingState.REPLAY, log.state.value)
            assertEquals(1, log.events.value.size)
        }

        @Test
        fun `stopRecording with no events returns to IDLE`() {
            val log = RecordingLog()
            log.startRecording()
            log.stopRecording()
            assertEquals(RecordingState.IDLE, log.state.value)
        }

        @Test
        fun `clear empties and returns to IDLE`() {
            val log = RecordingLog()
            log.startRecording()
            log.record(hit(seq = 1L, threadId = 1L))
            log.clear()
            assertEquals(RecordingState.IDLE, log.state.value)
            assertTrue(log.events.value.isEmpty())
        }

        @Test
        fun `replaceEvents enters REPLAY state`() {
            val log = RecordingLog()
            log.replaceEvents(listOf(hit(1L, 1L), hit(2L, 1L)))
            assertEquals(RecordingState.REPLAY, log.state.value)
            assertEquals(2, log.events.value.size)
        }
    }

    @Nested
    @DisplayName("Ring buffer eviction")
    inner class RingBuffer {
        @Test
        fun `evicts oldest events past capacity`() {
            val log = RecordingLog(maxEvents = 3)
            log.startRecording()
            log.record(hit(1L, 1L))
            log.record(hit(2L, 1L))
            log.record(hit(3L, 1L))
            log.record(hit(4L, 1L))
            assertEquals(3, log.events.value.size)
            assertEquals(2L, log.firstSequenceId())
            assertEquals(4L, log.lastSequenceId())
        }
    }

    @Nested
    @DisplayName("findHit — direction and filtering")
    inner class FindHit {
        @Test
        fun `BACKWARD returns largest seq strictly less than current`() {
            val log = RecordingLog()
            log.startRecording()
            log.record(hit(1L, 1L))
            log.record(hit(5L, 1L))
            log.record(hit(10L, 1L))

            val result = log.findHit(currentSeq = 7L, threadId = null, direction = HitDirection.BACKWARD)
            assertNotNull(result)
            assertEquals(5L, result.sequenceId)
        }

        @Test
        fun `FORWARD returns smallest seq strictly greater than current`() {
            val log = RecordingLog()
            log.startRecording()
            log.record(hit(1L, 1L))
            log.record(hit(5L, 1L))
            log.record(hit(10L, 1L))

            val result = log.findHit(currentSeq = 5L, threadId = null, direction = HitDirection.FORWARD)
            assertNotNull(result)
            assertEquals(10L, result.sequenceId)
        }

        @Test
        fun `BACKWARD at first event returns null`() {
            val log = RecordingLog()
            log.startRecording()
            log.record(hit(1L, 1L))
            assertNull(log.findHit(currentSeq = 1L, threadId = null, direction = HitDirection.BACKWARD))
        }

        @Test
        fun `FORWARD at last event returns null`() {
            val log = RecordingLog()
            log.startRecording()
            log.record(hit(10L, 1L))
            assertNull(log.findHit(currentSeq = 10L, threadId = null, direction = HitDirection.FORWARD))
        }

        @Test
        fun `threadId filter excludes hits on other threads`() {
            val log = RecordingLog()
            log.startRecording()
            log.record(hit(1L, threadId = 1L))
            log.record(hit(2L, threadId = 2L))
            log.record(hit(3L, threadId = 1L))
            log.record(hit(4L, threadId = 2L))

            // BACKWARD from seq=4 on thread 1 should land at seq=3 (skipping seq=4 thread 2)
            val r = log.findHit(currentSeq = 4L, threadId = 1L, direction = HitDirection.BACKWARD)
            assertNotNull(r)
            assertEquals(3L, r.sequenceId)
            assertEquals(1L, r.hit.threadId)
        }

        @Test
        fun `non-hit events are ignored even when targeted`() {
            val log = RecordingLog()
            log.startRecording()
            log.record(hit(1L, 1L))
            log.record(step(2L, 1L))
            log.record(threadEvt(3L, 1L, ThreadState.THREAD_STATE_RUNNING))
            log.record(hit(4L, 1L))

            // BACKWARD from 4 should skip the step + thread events and return seq=1
            val r = log.findHit(currentSeq = 4L, threadId = null, direction = HitDirection.BACKWARD)
            assertNotNull(r)
            assertEquals(1L, r.sequenceId)
        }
    }

    @Nested
    @DisplayName("eventAt")
    inner class EventAt {
        @Test
        fun `eventAt returns latest event at-or-before the cursor`() {
            val log = RecordingLog()
            log.startRecording()
            log.record(hit(1L, 1L))
            log.record(hit(5L, 1L))

            assertEquals(1L, log.eventAt(3L)?.sequenceId)
            assertEquals(5L, log.eventAt(5L)?.sequenceId)
            assertEquals(5L, log.eventAt(99L)?.sequenceId)
            assertNull(log.eventAt(0L))
        }
    }
}
