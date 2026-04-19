package com.bugdigger.agent.debugger;

import com.bugdigger.protocol.BreakpointHit;
import com.bugdigger.protocol.DebuggerEvent;
import com.bugdigger.protocol.ThreadState;
import com.bugdigger.protocol.ThreadStateChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Thread-safe buffer for debugger events. Broadcasts to registered listeners and tags
 * every event with a monotonic sequence id (the keystone for future time-travel replay).
 */
public class DebuggerEventBuffer {
    private static final Logger logger = LoggerFactory.getLogger(DebuggerEventBuffer.class);

    private static volatile DebuggerEventBuffer instance;

    private final AtomicLong sequenceCounter = new AtomicLong(0);
    private final List<Consumer<DebuggerEvent>> listeners = new CopyOnWriteArrayList<>();

    private DebuggerEventBuffer() {
    }

    public static DebuggerEventBuffer getInstance() {
        if (instance == null) {
            synchronized (DebuggerEventBuffer.class) {
                if (instance == null) {
                    instance = new DebuggerEventBuffer();
                }
            }
        }
        return instance;
    }

    public Runnable addListener(Consumer<DebuggerEvent> listener) {
        listeners.add(listener);
        logger.info("Added debugger event listener, total: {}", listeners.size());
        return () -> {
            listeners.remove(listener);
            logger.info("Removed debugger event listener, total: {}", listeners.size());
        };
    }

    public boolean hasListeners() {
        return !listeners.isEmpty();
    }

    public void emitBreakpointHit(BreakpointHit hit) {
        DebuggerEvent event = DebuggerEvent.newBuilder()
                .setSequenceId(sequenceCounter.incrementAndGet())
                .setTimestampNs(System.nanoTime())
                .setHit(hit)
                .build();
        broadcast(event);
    }

    public void emitThreadState(long threadId, String threadName, ThreadState state) {
        ThreadStateChanged change = ThreadStateChanged.newBuilder()
                .setThreadId(threadId)
                .setThreadName(threadName == null ? "" : threadName)
                .setState(state)
                .build();
        DebuggerEvent event = DebuggerEvent.newBuilder()
                .setSequenceId(sequenceCounter.incrementAndGet())
                .setTimestampNs(System.nanoTime())
                .setThread(change)
                .build();
        broadcast(event);
    }

    private void broadcast(DebuggerEvent event) {
        for (Consumer<DebuggerEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                logger.warn("Error broadcasting debugger event", e);
            }
        }
    }
}
