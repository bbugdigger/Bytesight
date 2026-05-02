package com.bugdigger.bytesight.ui.debugger

import com.bugdigger.bytesight.debugger.CursorMode
import com.bugdigger.bytesight.debugger.DebuggerState
import com.bugdigger.bytesight.debugger.RecordingFile
import com.bugdigger.bytesight.debugger.RecordingLog
import com.bugdigger.bytesight.debugger.RecordingState
import com.bugdigger.bytesight.debugger.ReplayCursor
import com.bugdigger.bytesight.debugger.LiveCursor
import com.bugdigger.bytesight.service.AgentClient
import com.bugdigger.bytesight.service.ConnectionRegistry
import com.bugdigger.protocol.DebuggerEvent
import com.bugdigger.protocol.breakpointHit
import com.bugdigger.protocol.debuggerEvent
import com.bugdigger.protocol.frameSnapshot
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertIs

/**
 * Tests for the time-travel additions to DebuggerViewModel: cursor-mode
 * routing, recording control, save/load, and prev/next hit navigation.
 *
 * Other DebuggerViewModel behavior (breakpoint install/remove, stepping)
 * is exercised indirectly via integration; this suite focuses on TTD.
 */
class DebuggerViewModelReplayTest {

    private lateinit var recordingLog: RecordingLog
    private lateinit var replayCursor: ReplayCursor
    private lateinit var liveCursor: LiveCursor
    private lateinit var viewModel: DebuggerViewModel
    private lateinit var agentClient: AgentClient
    private lateinit var connectionRegistry: ConnectionRegistry
    private lateinit var debuggerState: DebuggerState

    @BeforeEach
    fun setup() {
        agentClient = mockk(relaxed = true)
        connectionRegistry = mockk(relaxed = true)
        debuggerState = DebuggerState()
        recordingLog = RecordingLog()
        replayCursor = ReplayCursor(recordingLog, initialSequenceId = 0L)
        liveCursor = LiveCursor(agentClient, connectionRegistry, recordingLog)
        viewModel = DebuggerViewModel(
            agentClient = agentClient,
            liveCursor = liveCursor,
            replayCursor = replayCursor,
            recordingLog = recordingLog,
            debuggerState = debuggerState,
        )
    }

    private fun hit(seq: Long, threadId: Long, name: String = "main"): DebuggerEvent =
        debuggerEvent {
            this.sequenceId = seq
            this.timestampNs = seq * 1000L
            this.hit = breakpointHit {
                this.breakpointId = "bp"
                this.threadId = threadId
                this.threadName = name
                this.topFrame = frameSnapshot { className = "Foo"; methodName = "bar"; lineNumber = seq.toInt() }
                this.stack.add(frameSnapshot { className = "Foo"; methodName = "bar"; lineNumber = seq.toInt() })
            }
        }

    @Test
    fun `default cursor mode is Live`() {
        assertEquals(CursorMode.Live, viewModel.cursorMode.value)
    }

    @Test
    fun `startRecording transitions the log to RECORDING`() {
        viewModel.startRecording()
        assertEquals(RecordingState.RECORDING, recordingLog.state.value)
    }

    @Test
    fun `stopRecording with events transitions to REPLAY`() {
        viewModel.startRecording()
        recordingLog.record(hit(seq = 1L, threadId = 1L))
        viewModel.stopRecording()
        assertEquals(RecordingState.REPLAY, recordingLog.state.value)
    }

    @Test
    fun `seekTo enters Replay mode and updates ReplayCursor`() {
        viewModel.startRecording()
        recordingLog.record(hit(seq = 5L, threadId = 1L, name = "main"))

        viewModel.seekTo(5L)

        val mode = viewModel.cursorMode.value
        assertIs<CursorMode.Replay>(mode)
        assertEquals(5L, mode.sequenceId)
        assertEquals(5L, replayCursor.currentSequenceId.value)
    }

    @Test
    fun `resumeLive returns to Live mode`() {
        viewModel.startRecording()
        recordingLog.record(hit(seq = 1L, threadId = 1L))
        viewModel.seekTo(1L)
        assertIs<CursorMode.Replay>(viewModel.cursorMode.value)

        viewModel.resumeLive()
        assertEquals(CursorMode.Live, viewModel.cursorMode.value)
    }

    @Test
    fun `prevHit moves the playhead to the previous bp hit on the focused thread`() {
        viewModel.startRecording()
        recordingLog.record(hit(seq = 1L, threadId = 1L))
        recordingLog.record(hit(seq = 5L, threadId = 1L))
        recordingLog.record(hit(seq = 10L, threadId = 1L))

        viewModel.seekTo(10L)
        viewModel.prevHit()
        assertEquals(5L, replayCursor.currentSequenceId.value)
        viewModel.prevHit()
        assertEquals(1L, replayCursor.currentSequenceId.value)
    }

    @Test
    fun `nextHit moves the playhead forward to the next bp hit`() {
        viewModel.startRecording()
        recordingLog.record(hit(seq = 1L, threadId = 1L))
        recordingLog.record(hit(seq = 5L, threadId = 1L))

        viewModel.seekTo(1L)
        viewModel.nextHit()
        assertEquals(5L, replayCursor.currentSequenceId.value)
    }

    @Test
    fun `prevHit at first hit is a no-op`() {
        viewModel.startRecording()
        recordingLog.record(hit(seq = 1L, threadId = 1L))
        viewModel.seekTo(1L)

        viewModel.prevHit()
        assertEquals(1L, replayCursor.currentSequenceId.value)
        assertIs<CursorMode.Replay>(viewModel.cursorMode.value)
    }

    @Test
    fun `nextHit while in Live mode jumps to the first recorded hit`() {
        viewModel.startRecording()
        recordingLog.record(hit(seq = 7L, threadId = 1L))

        // Currently Live; nextHit treats the playhead as "before the start".
        viewModel.nextHit()
        assertEquals(7L, replayCursor.currentSequenceId.value)
        assertIs<CursorMode.Replay>(viewModel.cursorMode.value)
    }

    @Test
    fun `saveRecording then loadRecording round-trips events`(@TempDir dir: Path) {
        viewModel.startRecording()
        recordingLog.record(hit(seq = 1L, threadId = 1L))
        recordingLog.record(hit(seq = 2L, threadId = 1L))

        val path = dir.resolve("session.btsrec")
        viewModel.saveRecording(path)

        // Sanity-check via RecordingFile directly.
        val reloaded = RecordingFile.loadFrom(path)
        assertEquals(2, reloaded.size)

        // Now exercise the view-model load path on a fresh log.
        recordingLog.clear()
        assertEquals(RecordingState.IDLE, recordingLog.state.value)
        viewModel.loadRecording(path)
        assertEquals(RecordingState.REPLAY, recordingLog.state.value)
        assertEquals(2, recordingLog.events.value.size)
    }
}
