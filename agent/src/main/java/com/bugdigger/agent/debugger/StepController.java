package com.bugdigger.agent.debugger;

import com.bugdigger.agent.collector.ClassCollector;
import com.bugdigger.protocol.Breakpoint;
import com.bugdigger.protocol.FrameSnapshot;
import com.bugdigger.protocol.LineLocation;
import com.bugdigger.protocol.MethodBreakpointMode;
import com.bugdigger.protocol.MethodLocation;
import com.bugdigger.protocol.StepCompleted;
import com.bugdigger.protocol.StepKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implements Step Over / Step Into / Step Out by orchestrating <em>transient</em>
 * breakpoints around a currently-suspended thread, then releasing it via
 * {@link ThreadRegistry#resume(long)}. The first transient that fires is the
 * step destination — its sibling transients are removed, a {@link StepCompleted}
 * event is emitted instead of a {@link com.bugdigger.protocol.BreakpointHit},
 * and the thread parks at the new location.
 *
 * <h3>Approximations vs JVMTI single-step</h3>
 *
 * Without {@code can_generate_single_step_events} (Phase: onload-only on HotSpot,
 * see {@link NativeDebuggerBridge}), real instruction-granular stepping isn't
 * available. Instead:
 * <ul>
 *   <li><b>Step Over</b>: install transient line bps on every other source line
 *       in the current method PLUS a transient method-exit bp; the first to
 *       fire wins.</li>
 *   <li><b>Step Into</b>: same as Step Over, plus transient method-entry bps on
 *       every method statically reachable via {@code INVOKE*} instructions
 *       in the current method's bytecode (extracted via
 *       {@link MethodAnalyzer}). Imperfect for virtual dispatch — the bp fires
 *       on the static-dispatch-target class, not necessarily the actual subclass
 *       that runs. Falls through to Step-Over behavior if no callee fires first.</li>
 *   <li><b>Step Out</b>: install a transient method-exit bp on the current
 *       method. The thread parks at method exit (still inside M's frame); the
 *       user must Resume once more for M to actually return.</li>
 * </ul>
 */
public final class StepController {

    private static final Logger logger = LoggerFactory.getLogger(StepController.class);
    private static volatile StepController instance;

    private final BreakpointManager breakpointManager;
    private final ClassCollector classCollector;

    /** Per-thread in-progress step. */
    private final Map<Long, ActiveStep> activeSteps = new ConcurrentHashMap<>();
    /** Per-bp-id reverse lookup so the interceptor can recognize a step bp in O(1). */
    private final Map<String, ActiveStep> bpIdToStep = new ConcurrentHashMap<>();
    /** Last captured frame info per parked thread. Populated on each bp hit. */
    private final Map<Long, FrameInfo> lastFrame = new ConcurrentHashMap<>();

    public StepController(BreakpointManager breakpointManager, ClassCollector classCollector) {
        this.breakpointManager = breakpointManager;
        this.classCollector = classCollector;
        instance = this;
    }

    public static StepController getInstance() {
        return instance;
    }

    // ===== Called from BreakpointInterceptor before parking ===================

    /**
     * Records the frame the thread is currently parked in. Called from
     * {@link BreakpointInterceptor} on every breakpoint hit (line / entry / exit).
     */
    public void noteParkedFrame(long threadId, String className, String methodName,
                                String methodSignature, int line) {
        lastFrame.put(threadId, new FrameInfo(className, methodName, methodSignature, line));
    }

    /**
     * Called from BreakpointInterceptor after a hit but before park. Returns
     * true if the hit fulfills an in-progress step request: the interceptor
     * should then suppress its normal BreakpointHit emission (we already
     * emitted a StepCompleted) and just park the thread.
     */
    public boolean tryCompleteStep(String bpId, long threadId, FrameSnapshot newFrame) {
        ActiveStep step = bpIdToStep.get(bpId);
        if (step == null) return false;
        if (step.threadId != threadId) {
            // Different thread tripped a transient bp meant for a different step.
            // Don't consume the step; treat as a normal hit.
            return false;
        }

        // Remove all sibling transient bps. removal triggers retransform, which
        // is a noticeable cost; keep step targets tight to limit fan-out.
        for (String tid : step.transientBpIds) {
            bpIdToStep.remove(tid);
            try {
                breakpointManager.remove(tid);
            } catch (Throwable t) {
                logger.warn("StepController: failed to remove transient bp {}: {}", tid, t.toString());
            }
        }
        activeSteps.remove(threadId);

        StepCompleted completed = StepCompleted.newBuilder()
                .setThreadId(threadId)
                .setThreadName(Thread.currentThread().getName())
                .setKind(step.kind)
                .setTopFrame(newFrame)
                .addStack(newFrame)
                .build();
        DebuggerEventBuffer.getInstance().emitStepCompleted(completed);
        return true;
    }

    // ===== Called from the Step RPC handler ===================================

    public Result requestStep(long threadId, StepKind kind) {
        FrameInfo frame = lastFrame.get(threadId);
        if (frame == null) {
            return Result.failure("Thread " + threadId + " is not at a captured breakpoint");
        }
        // Cancel any prior in-flight step on this thread.
        ActiveStep prior = activeSteps.remove(threadId);
        if (prior != null) {
            for (String tid : prior.transientBpIds) {
                bpIdToStep.remove(tid);
                try { breakpointManager.remove(tid); } catch (Throwable ignore) {}
            }
        }

        Set<String> installed = new HashSet<>();
        try {
            switch (kind) {
                case STEP_OVER:
                    installed.addAll(installLineFallback(frame));
                    installed.addAll(installMethodExit(frame));
                    break;
                case STEP_INTO:
                    installed.addAll(installLineFallback(frame));
                    installed.addAll(installMethodExit(frame));
                    installed.addAll(installCalleeEntries(frame));
                    break;
                case STEP_OUT:
                    installed.addAll(installMethodExit(frame));
                    break;
                default:
                    return Result.failure("Unknown StepKind: " + kind);
            }
        } catch (Throwable t) {
            // Roll back partial install.
            for (String tid : installed) {
                bpIdToStep.remove(tid);
                try { breakpointManager.remove(tid); } catch (Throwable ignore) {}
            }
            logger.error("StepController: failed to install step transients", t);
            return Result.failure("Failed to install step transients: " + t.getMessage());
        }

        if (installed.isEmpty()) {
            return Result.failure("No transient breakpoints could be installed for " + kind +
                    " — class bytes unavailable or no candidate locations");
        }

        ActiveStep step = new ActiveStep(threadId, kind, installed);
        activeSteps.put(threadId, step);
        for (String tid : installed) bpIdToStep.put(tid, step);

        // Release the parked thread so it runs into the transients.
        int unparked = ThreadRegistry.getInstance().resume(threadId);
        logger.info("StepController: requested {} for thread {} ({} transients, {} unparked)",
                kind, threadId, installed.size(), unparked);
        return Result.success();
    }

    // ===== Installation helpers ===============================================

    private Set<String> installLineFallback(FrameInfo frame) {
        Set<String> installed = new HashSet<>();
        MethodAnalyzer.MethodInfo info = MethodAnalyzer.analyze(
                classCollector, frame.className, frame.methodName, frame.methodSignature);
        if (!info.found) return installed;

        for (Integer line : info.lines) {
            if (line == frame.line) continue;  // skip the line we're parked on
            String bpId = "step-line-" + UUID.randomUUID();
            Breakpoint bp = Breakpoint.newBuilder()
                    .setId(bpId)
                    .setLine(LineLocation.newBuilder()
                            .setClassName(frame.className)
                            .setLineNumber(line))
                    .setEnabled(true)
                    .build();
            BreakpointManager.Result r = breakpointManager.install(bp);
            if (r.isSuccess()) installed.add(bpId);
        }
        return installed;
    }

    private Set<String> installMethodExit(FrameInfo frame) {
        Set<String> installed = new HashSet<>();
        String bpId = "step-exit-" + UUID.randomUUID();
        Breakpoint bp = Breakpoint.newBuilder()
                .setId(bpId)
                .setMethod(MethodLocation.newBuilder()
                        .setClassName(frame.className)
                        .setMethodName(frame.methodName)
                        .setMethodSignature(frame.methodSignature == null ? "" : frame.methodSignature)
                        .setMode(MethodBreakpointMode.METHOD_BP_EXIT))
                .setEnabled(true)
                .build();
        BreakpointManager.Result r = breakpointManager.install(bp);
        if (r.isSuccess()) installed.add(bpId);
        return installed;
    }

    private Set<String> installCalleeEntries(FrameInfo frame) {
        Set<String> installed = new HashSet<>();
        MethodAnalyzer.MethodInfo info = MethodAnalyzer.analyze(
                classCollector, frame.className, frame.methodName, frame.methodSignature);
        if (!info.found) return installed;

        // Deduplicate to avoid installing the same callee twice (loop body reuse).
        Set<String> seen = new HashSet<>();
        for (MethodAnalyzer.CalleeRef callee : info.callees) {
            String dedupeKey = callee.ownerClassNameJls + "#" + callee.methodName + "#" + callee.descriptor;
            if (!seen.add(dedupeKey)) continue;

            String bpId = "step-callee-" + UUID.randomUUID();
            Breakpoint bp = Breakpoint.newBuilder()
                    .setId(bpId)
                    .setMethod(MethodLocation.newBuilder()
                            .setClassName(callee.ownerClassNameJls)
                            .setMethodName(callee.methodName)
                            .setMethodSignature(callee.descriptor)
                            .setMode(MethodBreakpointMode.METHOD_BP_ENTRY))
                    .setEnabled(true)
                    .build();
            BreakpointManager.Result r = breakpointManager.install(bp);
            if (r.isSuccess()) installed.add(bpId);
        }
        return installed;
    }

    // ===== Helpers ============================================================

    /** Cleanup hook for Resume RPC: clear step state for a thread that's being released. */
    public void clearForThread(long threadId) {
        ActiveStep step = activeSteps.remove(threadId);
        if (step != null) {
            for (String tid : step.transientBpIds) {
                bpIdToStep.remove(tid);
                try { breakpointManager.remove(tid); } catch (Throwable ignore) {}
            }
        }
        lastFrame.remove(threadId);
    }

    private static final class FrameInfo {
        final String className;
        final String methodName;
        final String methodSignature;
        final int line;

        FrameInfo(String className, String methodName, String methodSignature, int line) {
            this.className = className;
            this.methodName = methodName == null ? "" : methodName;
            this.methodSignature = methodSignature == null ? "" : methodSignature;
            this.line = line;
        }
    }

    private static final class ActiveStep {
        final long threadId;
        final StepKind kind;
        final Set<String> transientBpIds;

        ActiveStep(long threadId, StepKind kind, Set<String> transientBpIds) {
            this.threadId = threadId;
            this.kind = kind;
            this.transientBpIds = transientBpIds;
        }
    }

    public static final class Result {
        private final boolean success;
        private final String error;
        private Result(boolean success, String error) { this.success = success; this.error = error; }
        public static Result success() { return new Result(true, null); }
        public static Result failure(String err) { return new Result(false, err); }
        public boolean isSuccess() { return success; }
        public String getError() { return error; }
    }
}
