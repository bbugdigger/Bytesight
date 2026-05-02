package com.bugdigger.bytesight.debugger

import com.bugdigger.protocol.DebuggerEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bounded ring buffer of [DebuggerEvent] indexed by `sequence_id`.
 *
 * The log captures only what the agent's `DebuggerEventBuffer` already emits —
 * `BreakpointHit`, `StepCompleted`, `ThreadStateChanged` — when [state] is
 * [RecordingState.RECORDING]. While in [RecordingState.IDLE] or
 * [RecordingState.REPLAY] the [record] call is a no-op (so a freshly loaded
 * recording can't be polluted by late incoming events).
 *
 * Events are guaranteed monotonic in `sequence_id` (the agent assigns them
 * monotonically), so the underlying list is naturally sorted and search is
 * O(log n). Eviction drops the oldest entries past [maxEvents].
 *
 * Thread-safety: writes happen from the LiveCursor's IO scope; the StateFlows
 * are mutated under [lock] so concurrent reads via the flow are consistent.
 */
class RecordingLog(private val maxEvents: Int = DEFAULT_MAX_EVENTS) {

    private val lock = Any()
    private val buffer = ArrayDeque<DebuggerEvent>()

    private val _events = MutableStateFlow<List<DebuggerEvent>>(emptyList())
    val events: StateFlow<List<DebuggerEvent>> = _events.asStateFlow()

    private val _state = MutableStateFlow(RecordingState.IDLE)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    fun startRecording() {
        synchronized(lock) { _state.value = RecordingState.RECORDING }
    }

    fun stopRecording() = synchronized(lock) {
        if (_state.value != RecordingState.RECORDING) return@synchronized
        _state.value = if (buffer.isEmpty()) RecordingState.IDLE else RecordingState.REPLAY
    }

    fun clear() = synchronized(lock) {
        buffer.clear()
        _events.value = emptyList()
        _state.value = RecordingState.IDLE
    }

    fun record(event: DebuggerEvent) = synchronized(lock) {
        if (_state.value != RecordingState.RECORDING) return@synchronized
        buffer.addLast(event)
        while (buffer.size > maxEvents) buffer.removeFirst()
        _events.value = buffer.toList()
    }

    /** Replace all events (used by [RecordingFile.loadFrom]). Transitions to REPLAY. */
    fun replaceEvents(events: List<DebuggerEvent>) = synchronized(lock) {
        buffer.clear()
        // Defensive sort; real recordings are already monotonic but loaded files
        // could in principle be hand-edited.
        events.sortedBy { it.sequenceId }.forEach { buffer.addLast(it) }
        while (buffer.size > maxEvents) buffer.removeFirst()
        _events.value = buffer.toList()
        _state.value = RecordingState.REPLAY
    }

    fun firstSequenceId(): Long? = _events.value.firstOrNull()?.sequenceId

    fun lastSequenceId(): Long? = _events.value.lastOrNull()?.sequenceId

    /**
     * Latest event with `sequence_id <= cursor`, or null if none. Useful for
     * "what was happening at this moment" queries when the cursor is anywhere
     * on the timeline (not just at a known event).
     */
    fun eventAt(cursor: Long): DebuggerEvent? {
        val list = _events.value
        if (list.isEmpty()) return null
        // Binary search for largest seq <= cursor.
        var lo = 0
        var hi = list.size - 1
        var best: DebuggerEvent? = null
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val midSeq = list[mid].sequenceId
            if (midSeq <= cursor) {
                best = list[mid]
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return best
    }

    /**
     * Find the previous/next [BreakpointHit] event relative to [currentSeq],
     * optionally filtered by [threadId]. Strict inequality: matches a hit at
     * exactly [currentSeq] are not returned (so calling repeatedly always moves).
     */
    fun findHit(
        currentSeq: Long,
        threadId: Long?,
        direction: HitDirection,
    ): DebuggerEvent? {
        val list = _events.value
        return when (direction) {
            HitDirection.BACKWARD -> list.asReversed().firstOrNull { evt ->
                evt.sequenceId < currentSeq &&
                    evt.kindCase == DebuggerEvent.KindCase.HIT &&
                    (threadId == null || evt.hit.threadId == threadId)
            }
            HitDirection.FORWARD -> list.firstOrNull { evt ->
                evt.sequenceId > currentSeq &&
                    evt.kindCase == DebuggerEvent.KindCase.HIT &&
                    (threadId == null || evt.hit.threadId == threadId)
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_EVENTS: Int = 10_000
    }
}
