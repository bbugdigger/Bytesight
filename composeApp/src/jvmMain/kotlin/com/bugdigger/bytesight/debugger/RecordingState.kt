package com.bugdigger.bytesight.debugger

/**
 * Lifecycle state of a [RecordingLog].
 *
 * - [IDLE]: empty log, not capturing.
 * - [RECORDING]: actively appending events streamed from [LiveCursor].
 * - [REPLAY]: events present, capture stopped (either via stopRecording or
 *   loadFrom). The UI can scrub freely; new events are dropped to keep the
 *   loaded history coherent.
 */
enum class RecordingState { IDLE, RECORDING, REPLAY }

/** Direction for [RecordingLog.findHit] queries. */
enum class HitDirection { BACKWARD, FORWARD }

/**
 * Active read state of the Debugger UI's cursor — independent of [RecordingState].
 * Live mode shows current JVM state; Replay anchors the UI to a specific sequence id
 * in the recorded log.
 */
sealed interface CursorMode {
    data object Live : CursorMode
    data class Replay(val sequenceId: Long) : CursorMode
}
