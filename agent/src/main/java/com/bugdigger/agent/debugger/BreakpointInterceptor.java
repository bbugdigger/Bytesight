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

            Thread current = Thread.currentThread();
            BreakpointHit hit = FrameCapture.captureBreakpointHit(
                    bpId, className, methodName, methodSignature, self, arguments, method);

            DebuggerEventBuffer buffer = DebuggerEventBuffer.getInstance();
            buffer.emitBreakpointHit(hit);
            buffer.emitThreadState(current.getId(), current.getName(), ThreadState.THREAD_STATE_SUSPENDED);

            ThreadRegistry.getInstance().parkCurrent();

            buffer.emitThreadState(current.getId(), current.getName(), ThreadState.THREAD_STATE_RUNNING);
        } catch (Throwable t) {
            System.err.println("[Bytesight-Debug] ERROR in onEnter: " + t.getClass().getName() + ": " + t.getMessage());
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

            Thread current = Thread.currentThread();
            BreakpointHit hit = FrameCapture.captureBreakpointHit(
                    bpId, className, methodName, methodSignature, self, arguments, method);

            DebuggerEventBuffer buffer = DebuggerEventBuffer.getInstance();
            buffer.emitBreakpointHit(hit);
            buffer.emitThreadState(current.getId(), current.getName(), ThreadState.THREAD_STATE_SUSPENDED);

            ThreadRegistry.getInstance().parkCurrent();

            buffer.emitThreadState(current.getId(), current.getName(), ThreadState.THREAD_STATE_RUNNING);
        } catch (Throwable t) {
            System.err.println("[Bytesight-Debug] ERROR in onExit: " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace();
        }
    }
}
