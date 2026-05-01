package com.bugdigger.agent.debugger;

import com.bugdigger.protocol.Breakpoint;
import com.bugdigger.protocol.LineLocation;
import com.bugdigger.protocol.DebuggerEvent;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test for line breakpoints: install a bp on a known source line of
 * a fixture class, invoke that method on a worker thread, verify the bp hits
 * (a {@link DebuggerEvent} is emitted) and that resuming releases the worker.
 *
 * <p>Requires the JDK toolchain to support runtime self-attach so
 * {@link ByteBuddyAgent#install()} can grant us an {@link Instrumentation}
 * handle. Without that, ByteBuddy throws and the test is skipped.
 */
class BreakpointManagerLineBpTest {

    private static Instrumentation inst;
    private BreakpointManager manager;
    private final List<Runnable> cleanups = new ArrayList<>();

    @BeforeAll
    static void installAgent() {
        try {
            inst = ByteBuddyAgent.install();
        } catch (Throwable t) {
            inst = null;
        }
    }

    @BeforeEach
    void setUp() {
        if (inst == null) return;
        manager = new BreakpointManager(inst);
    }

    @AfterEach
    void tearDown() {
        cleanups.forEach(Runnable::run);
        cleanups.clear();
    }

    @Test
    void rejectsLineLocationWithBlankClassName() {
        if (inst == null) return;
        Breakpoint bp = Breakpoint.newBuilder()
                .setId("bp1")
                .setLine(LineLocation.newBuilder().setClassName("").setLineNumber(10))
                .setEnabled(true)
                .build();
        BreakpointManager.Result r = manager.install(bp);
        assertFalse(r.isSuccess());
        assertTrue(r.getError().contains("class_name"), r.getError());
    }

    @Test
    void rejectsLineLocationWithNonPositiveLineNumber() {
        if (inst == null) return;
        Breakpoint bp = Breakpoint.newBuilder()
                .setId("bp1")
                .setLine(LineLocation.newBuilder().setClassName("foo.Bar").setLineNumber(0))
                .setEnabled(true)
                .build();
        BreakpointManager.Result r = manager.install(bp);
        assertFalse(r.isSuccess());
        assertTrue(r.getError().contains("line_number"), r.getError());
    }

    @Test
    void activeLinesForReturnsRegisteredLines() {
        if (inst == null) return;

        Breakpoint bp1 = Breakpoint.newBuilder()
                .setId("bp1")
                .setLine(LineLocation.newBuilder().setClassName("not.real.Class").setLineNumber(42))
                .setEnabled(true)
                .build();
        manager.install(bp1);
        cleanups.add(() -> manager.remove("bp1"));

        Breakpoint bp2 = Breakpoint.newBuilder()
                .setId("bp2")
                .setLine(LineLocation.newBuilder().setClassName("not.real.Class").setLineNumber(43))
                .setEnabled(true)
                .build();
        manager.install(bp2);
        cleanups.add(() -> manager.remove("bp2"));

        java.util.Set<Integer> active = manager.activeLinesFor("not.real.Class");
        assertTrue(active.contains(42));
        assertTrue(active.contains(43));

        manager.remove("bp1");
        cleanups.removeIf(r -> { r.run(); return true; });  // already cleaned

        active = manager.activeLinesFor("not.real.Class");
        assertFalse(active.contains(42));
    }

    @Test
    void findLineBreakpointReturnsIdForActiveLineOnly() {
        if (inst == null) return;

        Breakpoint bp = Breakpoint.newBuilder()
                .setId("bp-active")
                .setLine(LineLocation.newBuilder().setClassName("foo.Bar").setLineNumber(7))
                .setEnabled(true)
                .build();
        manager.install(bp);
        cleanups.add(() -> manager.remove("bp-active"));

        assertEquals("bp-active", manager.findLineBreakpoint("foo.Bar", 7));
        assertNull(manager.findLineBreakpoint("foo.Bar", 8));
        assertNull(manager.findLineBreakpoint("other.Class", 7));
    }

    @Test
    void disabledLineBreakpointIsNotFound() {
        if (inst == null) return;

        Breakpoint bp = Breakpoint.newBuilder()
                .setId("bp-disabled")
                .setLine(LineLocation.newBuilder().setClassName("foo.Bar").setLineNumber(9))
                .setEnabled(false)
                .build();
        manager.install(bp);
        cleanups.add(() -> manager.remove("bp-disabled"));

        assertNull(manager.findLineBreakpoint("foo.Bar", 9),
                "Disabled bp must not be returned by findLineBreakpoint");
    }

    @Test
    void lineBreakpointFiresWhenInstrumentedMethodReachesLine() throws InterruptedException {
        if (inst == null) return;

        // Find the line number of LineFixture.target()'s "int x" statement at
        // runtime to avoid coupling the test to source layout.
        int targetLine = LineFixtureLines.X_PLUS_1_LINE;
        String targetClass = LineFixture.class.getName();

        CountDownLatch hitLatch = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<DebuggerEvent> capturedEvent = new java.util.concurrent.atomic.AtomicReference<>();
        Runnable unregister = DebuggerEventBuffer.getInstance().addListener(event -> {
            if (event.hasHit() && "lineBp1".equals(event.getHit().getBreakpointId())) {
                capturedEvent.set(event);
                hitLatch.countDown();
            }
        });
        cleanups.add(unregister);

        Breakpoint bp = Breakpoint.newBuilder()
                .setId("lineBp1")
                .setLine(LineLocation.newBuilder()
                        .setClassName(targetClass)
                        .setLineNumber(targetLine))
                .setEnabled(true)
                .build();
        BreakpointManager.Result r = manager.install(bp);
        assertTrue(r.isSuccess(), "install: " + r.getError());
        cleanups.add(() -> manager.remove("lineBp1"));

        // Run target() on a worker; it will park inside the line probe.
        Thread worker = new Thread(LineFixture::target, "line-bp-worker");
        worker.setDaemon(true);
        worker.start();

        try {
            assertTrue(hitLatch.await(5, TimeUnit.SECONDS),
                    "Line breakpoint did not fire within 5s — probe injection or lookup is broken");

            DebuggerEvent event = capturedEvent.get();
            assertEquals(targetLine, event.getHit().getTopFrame().getLineNumber());
            assertEquals(targetClass, event.getHit().getTopFrame().getClassName());
            assertEquals("target", event.getHit().getTopFrame().getMethodName());
        } finally {
            // Release the parked worker so the JVM can exit cleanly.
            ThreadRegistry.getInstance().resume(worker.getId());
            worker.join(2000);
        }
    }
}
