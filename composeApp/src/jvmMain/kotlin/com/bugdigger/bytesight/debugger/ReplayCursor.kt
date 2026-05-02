package com.bugdigger.bytesight.debugger

import com.bugdigger.protocol.BreakpointHit
import com.bugdigger.protocol.DebuggerEvent
import com.bugdigger.protocol.FrameSnapshot
import com.bugdigger.protocol.ThreadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [ExecutionCursor] backed by a [RecordingLog] read at a specific
 * `sequenceId` (the playhead). The same UI panels that read from
 * [LiveCursor] re-render against historical snapshots when the
 * [DebuggerViewModel] swaps cursors.
 *
 * Snapshots are computed eagerly on [seekTo]: walk events in id order up to
 * the cursor, accumulating per-thread state. Cost is O(n) in the events ≤
 * cursor; for the v1 default 10k cap this is fast enough that we don't bother
 * caching, and it keeps the implementation obvious.
 */
class ReplayCursor(
    private val log: RecordingLog,
    initialSequenceId: Long,
) : ExecutionCursor {

    private val _threads = MutableStateFlow<List<ThreadView>>(emptyList())
    override val threads: StateFlow<List<ThreadView>> = _threads.asStateFlow()

    private val _currentThreadId = MutableStateFlow<Long?>(null)
    override val currentThreadId: StateFlow<Long?> = _currentThreadId.asStateFlow()

    private val _currentFrame = MutableStateFlow<FrameSnapshot?>(null)
    override val currentFrame: StateFlow<FrameSnapshot?> = _currentFrame.asStateFlow()

    private val _callStack = MutableStateFlow<List<FrameSnapshot>>(emptyList())
    override val callStack: StateFlow<List<FrameSnapshot>> = _callStack.asStateFlow()

    private val _lastHit = MutableStateFlow<BreakpointHit?>(null)
    override val lastHit: StateFlow<BreakpointHit?> = _lastHit.asStateFlow()

    private val _currentSequenceId = MutableStateFlow(initialSequenceId)
    val currentSequenceId: StateFlow<Long> = _currentSequenceId.asStateFlow()

    /** Per-thread accumulated state at the current playhead. */
    private val threadStacks = mutableMapOf<Long, List<FrameSnapshot>>()
    private val threadInfo = mutableMapOf<Long, ThreadView>()
    private var latestEventThreadId: Long? = null
    private var latestHit: BreakpointHit? = null

    init {
        seekTo(initialSequenceId)
    }

    fun seekTo(sequenceId: Long) {
        _currentSequenceId.value = sequenceId
        threadStacks.clear()
        threadInfo.clear()
        latestEventThreadId = null
        latestHit = null

        for (evt in log.events.value) {
            if (evt.sequenceId > sequenceId) break
            applyEvent(evt)
        }

        _threads.value = threadInfo.values.toList().sortedBy { it.id }
        _lastHit.value = latestHit

        val focus = latestEventThreadId
        _currentThreadId.value = focus
        val stack = focus?.let { threadStacks[it] }.orEmpty()
        _callStack.value = stack
        _currentFrame.value = stack.firstOrNull()
    }

    private fun applyEvent(evt: DebuggerEvent) {
        when (evt.kindCase) {
            DebuggerEvent.KindCase.HIT -> {
                val h = evt.hit
                threadStacks[h.threadId] = h.stackList
                threadInfo[h.threadId] =
                    ThreadView(h.threadId, h.threadName, ThreadState.THREAD_STATE_SUSPENDED)
                latestEventThreadId = h.threadId
                latestHit = h
            }
            DebuggerEvent.KindCase.STEP -> {
                val s = evt.step
                threadStacks[s.threadId] = s.stackList
                threadInfo[s.threadId] =
                    ThreadView(s.threadId, s.threadName, ThreadState.THREAD_STATE_SUSPENDED)
                latestEventThreadId = s.threadId
            }
            DebuggerEvent.KindCase.THREAD -> {
                val t = evt.thread
                val existing = threadInfo[t.threadId]
                threadInfo[t.threadId] = ThreadView(
                    t.threadId,
                    t.threadName.ifEmpty { existing?.name ?: "thread-${t.threadId}" },
                    t.state,
                )
            }
            else -> Unit
        }
    }

    override fun selectThread(threadId: Long) {
        _currentThreadId.value = threadId
        val stack = threadStacks[threadId].orEmpty()
        _callStack.value = stack
        _currentFrame.value = stack.firstOrNull()
    }

    override fun selectFrame(depth: Int) {
        val stack = _callStack.value
        _currentFrame.value = if (depth in stack.indices) stack[depth] else null
    }
}
