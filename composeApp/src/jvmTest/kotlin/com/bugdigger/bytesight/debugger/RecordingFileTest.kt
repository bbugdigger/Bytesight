package com.bugdigger.bytesight.debugger

import com.bugdigger.protocol.DebuggerEvent
import com.bugdigger.protocol.StepKind
import com.bugdigger.protocol.ThreadState
import com.bugdigger.protocol.breakpointHit
import com.bugdigger.protocol.debuggerEvent
import com.bugdigger.protocol.frameSnapshot
import com.bugdigger.protocol.stepCompleted
import com.bugdigger.protocol.threadStateChanged
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trip tests for [RecordingFile]. The .btsrec file is a length-prefixed
 * stream of [DebuggerEvent] messages — anything in the proto must survive
 * save/load identically.
 */
class RecordingFileTest {

    @Test
    fun `empty list writes a zero-byte file and loads as empty`(@TempDir dir: Path) {
        val path = dir.resolve("empty.btsrec")
        RecordingFile.saveTo(path, emptyList())
        assertTrue(path.exists())
        assertEquals(0L, path.fileSize())
        assertEquals(emptyList<DebuggerEvent>(), RecordingFile.loadFrom(path))
    }

    @Test
    fun `single breakpoint hit round-trips identically`(@TempDir dir: Path) {
        val original = debuggerEvent {
            this.sequenceId = 42L
            this.timestampNs = 1_700_000_000_000L
            this.hit = breakpointHit {
                this.breakpointId = "bp-foo"
                this.threadId = 1L
                this.threadName = "main"
                this.topFrame = frameSnapshot {
                    this.className = "com.example.Foo"
                    this.methodName = "bar"
                    this.lineNumber = 17
                }
                this.stack.add(frameSnapshot {
                    this.className = "com.example.Foo"; this.methodName = "bar"; this.lineNumber = 17
                })
            }
        }
        val path = dir.resolve("one.btsrec")
        RecordingFile.saveTo(path, listOf(original))
        val loaded = RecordingFile.loadFrom(path)

        assertEquals(1, loaded.size)
        assertEquals(original, loaded.single())
    }

    @Test
    fun `mixed event types preserve their oneof kind`(@TempDir dir: Path) {
        val events = listOf(
            debuggerEvent {
                this.sequenceId = 1L
                this.hit = breakpointHit {
                    this.breakpointId = "bp"
                    this.threadId = 1L
                    this.threadName = "main"
                    this.topFrame = frameSnapshot { className = "C"; methodName = "m" }
                }
            },
            debuggerEvent {
                this.sequenceId = 2L
                this.step = stepCompleted {
                    this.threadId = 1L
                    this.threadName = "main"
                    this.kind = StepKind.STEP_INTO
                    this.topFrame = frameSnapshot { className = "C"; methodName = "n" }
                }
            },
            debuggerEvent {
                this.sequenceId = 3L
                this.thread = threadStateChanged {
                    this.threadId = 2L
                    this.threadName = "worker"
                    this.state = ThreadState.THREAD_STATE_RUNNING
                }
            },
        )
        val path = dir.resolve("mixed.btsrec")
        RecordingFile.saveTo(path, events)
        val loaded = RecordingFile.loadFrom(path)

        assertEquals(events, loaded)
        assertEquals(DebuggerEvent.KindCase.HIT, loaded[0].kindCase)
        assertEquals(DebuggerEvent.KindCase.STEP, loaded[1].kindCase)
        assertEquals(DebuggerEvent.KindCase.THREAD, loaded[2].kindCase)
    }
}
