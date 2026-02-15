package com.bugdigger.agent.hook;

import com.bugdigger.protocol.MethodTraceEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TraceEventBuffer.
 */
class TraceEventBufferTest {

    private TraceEventBuffer buffer;

    @BeforeEach
    void setUp() {
        buffer = TraceEventBuffer.getInstance();
    }

    @Test
    void getInstance_returnsSameInstance() {
        TraceEventBuffer instance1 = TraceEventBuffer.getInstance();
        TraceEventBuffer instance2 = TraceEventBuffer.getInstance();
        assertSame(instance1, instance2);
    }

    @Test
    void addListener_receivesEvents() throws InterruptedException {
        List<MethodTraceEvent> receivedEvents = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        Runnable unregister = buffer.addListener(event -> {
            receivedEvents.add(event);
            latch.countDown();
        });

        try {
            // Record an entry event
            String callId = buffer.recordEntry(
                    "com.example.TestClass",
                    "testMethod",
                    "(Ljava/lang/String;)V",
                    new Object[]{"test"},
                    new String[]{"arg0"},
                    new Class<?>[]{String.class}
            );

            assertTrue(latch.await(1, TimeUnit.SECONDS), "Should receive event");
            assertEquals(1, receivedEvents.size());

            MethodTraceEvent event = receivedEvents.get(0);
            assertEquals("com.example.TestClass", event.getClassName());
            assertEquals("testMethod", event.getMethodName());
            assertEquals(MethodTraceEvent.TraceEventType.ENTRY, event.getEventType());
            assertEquals(callId, event.getCallId());
        } finally {
            unregister.run();
        }
    }

    @Test
    void unregister_stopsReceivingEvents() throws InterruptedException {
        List<MethodTraceEvent> receivedEvents = new ArrayList<>();

        Runnable unregister = buffer.addListener(receivedEvents::add);
        unregister.run();

        // Record an event after unregistering
        buffer.recordEntry(
                "com.example.TestClass",
                "testMethod",
                "(Ljava/lang/String;)V",
                new Object[]{"test"},
                new String[]{"arg0"},
                new Class<?>[]{String.class}
        );

        // Give some time for any events to arrive
        Thread.sleep(100);
        assertTrue(receivedEvents.isEmpty(), "Should not receive events after unregistering");
    }

    @Test
    void recordEntry_generatesUniqueCallIds() {
        List<String> callIds = new ArrayList<>();

        Runnable unregister = buffer.addListener(event -> callIds.add(event.getCallId()));

        try {
            for (int i = 0; i < 10; i++) {
                buffer.recordEntry(
                        "com.example.TestClass",
                        "testMethod",
                        "()V",
                        null,
                        null,
                        null
                );
            }

            assertEquals(10, callIds.size());
            assertEquals(10, callIds.stream().distinct().count(), "All call IDs should be unique");
        } finally {
            unregister.run();
        }
    }

    @Test
    void recordExit_includesDuration() throws InterruptedException {
        List<MethodTraceEvent> exitEvents = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        Runnable unregister = buffer.addListener(event -> {
            if (event.getEventType() == MethodTraceEvent.TraceEventType.EXIT) {
                exitEvents.add(event);
                latch.countDown();
            }
        });

        try {
            long startTime = System.nanoTime();

            String callId = buffer.recordEntry(
                    "com.example.TestClass",
                    "testMethod",
                    "()Ljava/lang/String;",
                    null,
                    null,
                    null
            );

            // Simulate some work
            Thread.sleep(10);

            buffer.recordExit(
                    callId,
                    "com.example.TestClass",
                    "testMethod",
                    "()Ljava/lang/String;",
                    "result",
                    String.class,
                    startTime
            );

            assertTrue(latch.await(1, TimeUnit.SECONDS), "Should receive exit event");
            assertEquals(1, exitEvents.size());

            MethodTraceEvent event = exitEvents.get(0);
            assertTrue(event.getDurationNanos() > 0, "Duration should be positive");
            assertEquals("java.lang.String", event.getReturnValue().getType());
            assertEquals("\"result\"", event.getReturnValue().getValue());
        } finally {
            unregister.run();
        }
    }

    @Test
    void recordException_capturesExceptionInfo() throws InterruptedException {
        List<MethodTraceEvent> exceptionEvents = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        Runnable unregister = buffer.addListener(event -> {
            if (event.getEventType() == MethodTraceEvent.TraceEventType.EXCEPTION) {
                exceptionEvents.add(event);
                latch.countDown();
            }
        });

        try {
            long startTime = System.nanoTime();

            String callId = buffer.recordEntry(
                    "com.example.TestClass",
                    "testMethod",
                    "()V",
                    null,
                    null,
                    null
            );

            RuntimeException testException = new RuntimeException("Test error");
            buffer.recordException(
                    callId,
                    "com.example.TestClass",
                    "testMethod",
                    "()V",
                    testException,
                    startTime
            );

            assertTrue(latch.await(1, TimeUnit.SECONDS), "Should receive exception event");
            assertEquals(1, exceptionEvents.size());

            MethodTraceEvent event = exceptionEvents.get(0);
            assertEquals("java.lang.RuntimeException", event.getExceptionClass());
            assertEquals("Test error", event.getExceptionMessage());
            assertTrue(event.getStackTrace().contains("TraceEventBufferTest"));
        } finally {
            unregister.run();
        }
    }
}
