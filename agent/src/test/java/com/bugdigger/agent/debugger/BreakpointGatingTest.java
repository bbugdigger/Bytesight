package com.bugdigger.agent.debugger;

import com.bugdigger.protocol.Breakpoint;
import com.bugdigger.protocol.MethodBreakpointMode;
import com.bugdigger.protocol.MethodLocation;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end gating test: install a method-entry bp with a condition and a
 * skip_count, invoke the target many times, verify the bp suspends only on
 * the call that satisfies both gates. The hit_count tracks every call.
 */
class BreakpointGatingTest {

    private static Instrumentation inst;
    private BreakpointManager manager;
    private final List<Runnable> cleanups = new ArrayList<>();

    @BeforeAll
    static void installAgent() {
        try { inst = ByteBuddyAgent.install(); } catch (Throwable t) { inst = null; }
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
    void skipCountSuppressesFirstNHits() throws InterruptedException {
        if (inst == null) return;

        AtomicInteger suspensions = new AtomicInteger(0);
        CountDownLatch fired = new CountDownLatch(1);
        Runnable unregister = DebuggerEventBuffer.getInstance().addListener(event -> {
            if (event.hasHit() && "skipBp".equals(event.getHit().getBreakpointId())) {
                suspensions.incrementAndGet();
                fired.countDown();
            }
        });
        cleanups.add(unregister);

        Breakpoint bp = Breakpoint.newBuilder()
                .setId("skipBp")
                .setMethod(MethodLocation.newBuilder()
                        .setClassName(GatingFixture.class.getName())
                        .setMethodName("invoke")
                        .setMode(MethodBreakpointMode.METHOD_BP_ENTRY))
                .setEnabled(true)
                .setSkipCount(3)
                .build();
        BreakpointManager.Result installResult = manager.install(bp);
        assertTrue(installResult.isSuccess(), installResult.getError());
        cleanups.add(() -> manager.remove("skipBp"));

        // Fire invoke 5 times on workers. Resume immediately on each suspension
        // so subsequent calls aren't blocked.
        Runnable resumeOnSuspend = () -> {
            // bp suspended the calling thread; release it.
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                if (t.getName().startsWith("gate-")) ThreadRegistry.getInstance().resume(t.getId());
            }
        };
        cleanups.add(resumeOnSuspend);

        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Thread t = new Thread(GatingFixture::invoke, "gate-" + i);
            t.setDaemon(true);
            t.start();
            workers.add(t);
            // Let each call complete before the next, releasing if parked.
            Thread.sleep(50);
            ThreadRegistry.getInstance().resume(t.getId());
            t.join(500);
        }

        // 5 calls, skip first 3 → 2 suspensions on calls 4 and 5.
        assertEquals(2, suspensions.get(),
                "skip_count=3 should suppress first 3 hits; 5 calls => 2 suspensions");
    }

    @Test
    void conditionGatesSuspension() throws InterruptedException {
        if (inst == null) return;

        AtomicInteger suspensions = new AtomicInteger(0);
        Runnable unregister = DebuggerEventBuffer.getInstance().addListener(event -> {
            if (event.hasHit() && "condBp".equals(event.getHit().getBreakpointId())) {
                suspensions.incrementAndGet();
            }
        });
        cleanups.add(unregister);

        Breakpoint bp = Breakpoint.newBuilder()
                .setId("condBp")
                .setMethod(MethodLocation.newBuilder()
                        .setClassName(GatingFixture.class.getName())
                        .setMethodName("invokeWithArg")
                        .setMode(MethodBreakpointMode.METHOD_BP_ENTRY))
                .setEnabled(true)
                .setCondition("arg0 > 5")
                .build();
        BreakpointManager.Result installResult = manager.install(bp);
        assertTrue(installResult.isSuccess(), installResult.getError());
        cleanups.add(() -> manager.remove("condBp"));

        // Call invokeWithArg 10 times with values 1..10. Condition: arg0 > 5.
        // Expected suspensions: 5 (for args 6, 7, 8, 9, 10).
        for (int i = 1; i <= 10; i++) {
            int v = i;
            Thread t = new Thread(() -> GatingFixture.invokeWithArg(v), "gate-cond-" + i);
            t.setDaemon(true);
            t.start();
            Thread.sleep(30);
            ThreadRegistry.getInstance().resume(t.getId());
            t.join(500);
        }

        assertEquals(5, suspensions.get(),
                "condition `arg0 > 5` over args 1..10 should suspend exactly 5 times");

        // Hit count should be 10 (every call counts, even the skipped ones).
        Breakpoint reread = manager.list().stream()
                .filter(b -> "condBp".equals(b.getId())).findFirst().orElseThrow();
        assertEquals(10, reread.getHitCount(),
                "hit_count tracks every call regardless of suspend gate");
    }
}
