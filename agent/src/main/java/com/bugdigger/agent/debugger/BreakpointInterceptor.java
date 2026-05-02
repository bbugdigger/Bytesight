package com.bugdigger.agent.debugger;

import com.bugdigger.protocol.BreakpointHit;
import com.bugdigger.protocol.ThreadState;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.lang.reflect.Method;

/**
 * ByteBuddy advice class applied to methods with active breakpoints.
 * <p>
 * On method entry the advice emits a {@link BreakpointHit} event and parks the calling
 * thread via {@link ThreadRegistry#parkCurrent()} until a Resume RPC unparks it.
 * On method exit the advice fires for breakpoints whose mode is EXIT or BOTH.
 * <p>
 * This class is inlined into the target class — it must only reference static helpers.
 */
public class BreakpointInterceptor {

    @Advice.OnMethodEnter
    public static void onEnter(
            @Advice.Origin("#t") String className,
            @Advice.Origin("#m") String methodName,
            @Advice.Origin("#s") String methodSignature,
            @Advice.This(optional = true, typing = Assigner.Typing.DYNAMIC) Object self,
            @Advice.AllArguments Object[] arguments,
            @Advice.Origin Method method
    ) {
        try {
            BreakpointManager manager = BreakpointManager.getInstance();
            if (manager == null) return;

            String bpId = manager.findEntryBreakpoint(className, methodName, methodSignature);
            if (bpId == null) return;

            // Conditional + hit-count gating. recordAndEvaluate increments the
            // hit counter unconditionally; the returned decision controls
            // whether to actually suspend.
            BreakpointManager.HitDecision decision = manager.recordAndEvaluate(bpId, self, arguments, method);
            if (!decision.shouldSuspend) return;

            Thread current = Thread.currentThread();
            BreakpointHit hit = FrameCapture.captureBreakpointHit(
                    bpId, className, methodName, methodSignature, self, arguments, method);

            DebuggerEventBuffer buffer = DebuggerEventBuffer.getInstance();
            // Cache frame for any subsequent step request, regardless of whether
            // this hit was itself a step completion.
            StepController stepCtrl = StepController.getInstance();
            if (stepCtrl != null) {
                stepCtrl.noteParkedFrame(current.getId(), className, methodName, methodSignature, 0);
            }
            boolean stepCompleted = stepCtrl != null
                    && stepCtrl.tryCompleteStep(bpId, current.getId(), hit.getTopFrame());
            if (!stepCompleted) {
                buffer.emitBreakpointHit(hit);
            }
            buffer.emitThreadState(current.getId(), current.getName(), ThreadState.THREAD_STATE_SUSPENDED);

            ThreadRegistry.getInstance().parkCurrent();

            buffer.emitThreadState(current.getId(), current.getName(), ThreadState.THREAD_STATE_RUNNING);
        } catch (Throwable t) {
            System.err.println("[Bytesight-Debug] ERROR in onEnter: " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace();
        }
    }

    /**
     * Called from ASM-injected probe code at the start of each interesting source
     * line. The {@code className} is the JLS dotted form, {@code methodName} is the
     * internal name (e.g. {@code <init>}), {@code methodSignature} is the JVM
     * descriptor, {@code line} is the source line number from the LineNumberTable.
     *
     * <p>Symbol must stay public + static — the bytecode injection uses a
     * literal INVOKESTATIC that names this method.
     */
    public static void onLineHit(String className, String methodName, String methodSignature, int line) {
        try {
            BreakpointManager manager = BreakpointManager.getInstance();
            if (manager == null) return;

            String bpId = manager.findLineBreakpoint(className, line);
            if (bpId == null) return;

            // Line probes don't have access to args/this/method directly. The
            // condition evaluator runs with a null context; bare-identifier or
            // this.field references will fail open with a warning. Conditions
            // that only use literals still work (rare but possible).
            BreakpointManager.HitDecision decision = manager.recordAndEvaluate(bpId, null, null, null);
            if (!decision.shouldSuspend) return;

            Thread current = Thread.currentThread();
            BreakpointHit hit = FrameCapture.captureLineHit(
                    bpId, className, methodName, methodSignature, line);

            DebuggerEventBuffer buffer = DebuggerEventBuffer.getInstance();
            StepController stepCtrl = StepController.getInstance();
            if (stepCtrl != null) {
                stepCtrl.noteParkedFrame(current.getId(), className, methodName, methodSignature, line);
            }
            boolean stepCompleted = stepCtrl != null
                    && stepCtrl.tryCompleteStep(bpId, current.getId(), hit.getTopFrame());
            if (!stepCompleted) {
                buffer.emitBreakpointHit(hit);
            }
            buffer.emitThreadState(current.getId(), current.getName(), ThreadState.THREAD_STATE_SUSPENDED);

            ThreadRegistry.getInstance().parkCurrent();

            buffer.emitThreadState(current.getId(), current.getName(), ThreadState.THREAD_STATE_RUNNING);
        } catch (Throwable t) {
            System.err.println("[Bytesight-Debug] ERROR in onLineHit: " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace();
        }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(
            @Advice.Origin("#t") String className,
            @Advice.Origin("#m") String methodName,
            @Advice.Origin("#s") String methodSignature,
            @Advice.This(optional = true, typing = Assigner.Typing.DYNAMIC) Object self,
            @Advice.AllArguments Object[] arguments,
            @Advice.Origin Method method
    ) {
        try {
            BreakpointManager manager = BreakpointManager.getInstance();
            if (manager == null) return;

            String bpId = manager.findExitBreakpoint(className, methodName, methodSignature);
            if (bpId == null) return;

            BreakpointManager.HitDecision decision = manager.recordAndEvaluate(bpId, self, arguments, method);
            if (!decision.shouldSuspend) return;

            Thread current = Thread.currentThread();
            BreakpointHit hit = FrameCapture.captureBreakpointHit(
                    bpId, className, methodName, methodSignature, self, arguments, method);

            DebuggerEventBuffer buffer = DebuggerEventBuffer.getInstance();
            StepController stepCtrl = StepController.getInstance();
            if (stepCtrl != null) {
                stepCtrl.noteParkedFrame(current.getId(), className, methodName, methodSignature, 0);
            }
            boolean stepCompleted = stepCtrl != null
                    && stepCtrl.tryCompleteStep(bpId, current.getId(), hit.getTopFrame());
            if (!stepCompleted) {
                buffer.emitBreakpointHit(hit);
            }
            buffer.emitThreadState(current.getId(), current.getName(), ThreadState.THREAD_STATE_SUSPENDED);

            ThreadRegistry.getInstance().parkCurrent();

            buffer.emitThreadState(current.getId(), current.getName(), ThreadState.THREAD_STATE_RUNNING);
        } catch (Throwable t) {
            System.err.println("[Bytesight-Debug] ERROR in onExit: " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace();
        }
    }
}
