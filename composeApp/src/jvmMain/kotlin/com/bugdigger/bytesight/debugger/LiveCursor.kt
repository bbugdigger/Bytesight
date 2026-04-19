package com.bugdigger.bytesight.debugger

import com.bugdigger.bytesight.service.AgentClient
import com.bugdigger.bytesight.service.ConnectionRegistry
import com.bugdigger.protocol.BreakpointHit
import com.bugdigger.protocol.DebuggerEvent
import com.bugdigger.protocol.FrameSnapshot
import com.bugdigger.protocol.ThreadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * v1 [ExecutionCursor] backed by live JVM state streamed from the agent.
 *
 * Subscribes to [AgentClient.streamDebuggerEvents] whenever the app is connected
 * and updates its StateFlows in response to BreakpointHit / ThreadStateChanged
 * events. The view-model observes these flows directly.
 */
class LiveCursor(
    private val agentClient: AgentClient,
    connectionRegistry: ConnectionRegistry,
) : ExecutionCursor {

    private val logger = LoggerFactory.getLogger(LiveCursor::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    private val threadStacks = mutableMapOf<Long, List<FrameSnapshot>>()
    private var streamJob: Job? = null

    init {
        scope.launch {
            connectionRegistry.connectionKey.collect { key ->
                streamJob?.cancel()
                resetState()
                if (key != null) startStreaming(key)
            }
        }
    }

    private fun resetState() {
        threadStacks.clear()
        _threads.value = emptyList()
        _currentThreadId.value = null
        _currentFrame.value = null
        _callStack.value = emptyList()
        _lastHit.value = null
    }

    private fun startStreaming(connectionKey: String) {
        streamJob = scope.launch {
            agentClient.streamDebuggerEvents(connectionKey)
                .onSuccess { flow ->
                    flow.catch { e -> logger.warn("Debugger event stream ended: ${e.message}") }
                        .collect { event -> handleEvent(event) }
                }
                .onFailure { e -> logger.warn("Failed to start debugger event stream: ${e.message}") }
        }
    }

    private fun handleEvent(event: DebuggerEvent) {
        when (event.kindCase) {
            DebuggerEvent.KindCase.HIT -> handleHit(event.hit)
            DebuggerEvent.KindCase.THREAD -> {
                val t = event.thread
                updateThread(t.threadId, t.threadName, t.state)
            }
            else -> Unit
        }
    }

    private fun handleHit(hit: BreakpointHit) {
        _lastHit.value = hit
        threadStacks[hit.threadId] = hit.stackList
        updateThread(hit.threadId, hit.threadName, ThreadState.THREAD_STATE_SUSPENDED)
        if (_currentThreadId.value == null || _currentThreadId.value == hit.threadId) {
            _currentThreadId.value = hit.threadId
            _callStack.value = hit.stackList
            _currentFrame.value = hit.topFrame
        }
    }

    private fun updateThread(id: Long, name: String, state: ThreadState) {
        val cur = _threads.value.toMutableList()
        val existing = cur.indexOfFirst { it.id == id }
        val view = ThreadView(id, name, state)
        if (existing >= 0) cur[existing] = view else cur += view
        _threads.value = cur
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
