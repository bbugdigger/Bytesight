package com.bugdigger.agent.debugger;

import com.bugdigger.agent.collector.ClassCollector;
import com.bugdigger.protocol.Breakpoint;
import com.bugdigger.protocol.DebuggerEvent;
import com.bugdigger.protocol.LineLocation;
import com.bugdigger.protocol.StepKind;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the {@link StepController} step-over flow: install a line bp,
 * trigger the worker to hit it, request Step Over, observe a
 * {@link com.bugdigger.protocol.StepCompleted} event arrive (instead of a
 * BreakpointHit) at the next line. The same fixture as the line-bp test is
 * reused so the two tests don't fight over the same instrumented class.
 */
class StepControllerTest {

    private static Instrumentation inst;
    private BreakpointManager manager;
    private ClassCollector collector;
    private StepController step;
    private final List<Runnable> cleanups = new ArrayList<>();

    @BeforeAll
    static void installAgent() {
        try { inst = ByteBuddyAgent.install(); } catch (Throwable t) { inst = null; }
    }

    @BeforeEach
    void setUp() {
        if (inst == null) return;
        manager = new BreakpointManager(inst);
        collector = new ClassCollector(inst);
        // ClassCollector must be registered as a transformer first; otherwise
        // its transform() method (the only path that populates classBytecode)
        // never fires when retransform is triggered by captureLoadedClasses.
        inst.addTransformer(collector, true);
        cleanups.add(() -> inst.removeTransformer(collector));
        collector.captureLoadedClasses(new Class<?>[]{ StepFixture.class });
        step = new StepController(manager, collector);
    }

    @AfterEach
    void tearDown() {
        cleanups.forEach(Runnable::run);
        cleanups.clear();
    }

    @Test
    void requestStepReturnsErrorWhenThreadHasNoCapturedFrame() {
        if (inst == null) return;
        StepController.Result r = step.requestStep(99999L, StepKind.STEP_OVER);
        assertFalse(r.isSuccess());
        assertTrue(r.getError().contains("not at a captured breakpoint"), r.getError());
    }

    @Test
    void stepOverInstallsTransientLineBpsAndCompletesAtNextLine() throws InterruptedException {
        if (inst == null) return;

        String targetClass = StepFixture.class.getName();
        int firstLine = StepFixtureLines.LINE_1;
        int secondLine = StepFixtureLines.LINE_2;

        // 1) Listener that captures BreakpointHit (for first stop) and
        //    StepCompleted (for the post-step landing).
        AtomicReference<DebuggerEvent> firstHit = new AtomicReference<>();
        CountDownLatch hitLatch = new CountDownLatch(1);
        AtomicReference<DebuggerEvent> stepCompleted = new AtomicReference<>();
        CountDownLatch stepLatch = new CountDownLatch(1);
        Runnable unregister = DebuggerEventBuffer.getInstance().addListener(event -> {
            if (event.hasHit() && "stepBp".equals(event.getHit().getBreakpointId())) {
                firstHit.set(event);
                hitLatch.countDown();
            } else if (event.hasStep()) {
                stepCompleted.set(event);
                stepLatch.countDown();
            }
        });
        cleanups.add(unregister);

        // 2) Install a line bp on the FIRST statement of target() so the
        //    worker parks there.
        Breakpoint bp = Breakpoint.newBuilder()
                .setId("stepBp")
                .setLine(LineLocation.newBuilder()
                        .setClassName(targetClass)
                        .setLineNumber(firstLine))
                .setEnabled(true)
                .build();
        BreakpointManager.Result installResult = manager.install(bp);
        assertTrue(installResult.isSuccess(), "install: " + installResult.getError());
        cleanups.add(() -> manager.remove("stepBp"));

        // 3) Run target() on a worker; it parks at firstLine.
        Thread worker = new Thread(StepFixture::target, "step-test-worker");
        worker.setDaemon(true);
        worker.start();
        cleanups.add(() -> {
            ThreadRegistry.getInstance().resume(worker.getId());
            try { worker.join(2000); } catch (InterruptedException ignored) {}
        });

        assertTrue(hitLatch.await(5, TimeUnit.SECONDS),
                "First bp didn't fire — line bp install or probe injection broken");

        // 4) Request Step Over for the worker. This should install transient line
        //    bps on every other line + a method-exit transient, then unpark.
        StepController.Result r = step.requestStep(worker.getId(), StepKind.STEP_OVER);
        assertTrue(r.isSuccess(), "requestStep: " + r.getError());

        // 5) Worker resumes, immediately runs into the transient bp on the next line.
        assertTrue(stepLatch.await(5, TimeUnit.SECONDS),
                "Step Over didn't complete — transient line bp install or routing broken");

        DebuggerEvent ev = stepCompleted.get();
        assertEquals(StepKind.STEP_OVER, ev.getStep().getKind());
        assertEquals(targetClass, ev.getStep().getTopFrame().getClassName());
        // The next line in the fixture is secondLine. We may also legally land on
        // a later line if the JIT or line-table layout is weird — assert it's
        // the second line, which is the only "next" line in target().
        assertEquals(secondLine, ev.getStep().getTopFrame().getLineNumber(),
                "Step Over should land on the next source line of the same method");
    }
}
