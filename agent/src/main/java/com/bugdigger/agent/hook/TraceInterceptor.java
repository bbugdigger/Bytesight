package com.bugdigger.agent.hook;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.lang.reflect.Method;

/**
 * ByteBuddy advice class for method tracing.
 * <p>
 * This class is used by ByteBuddy to inject tracing code at method entry and exit points.
 * The advice methods are inlined into the target method, so they must be static and
 * cannot reference instance fields. All state is passed through local variables.
 * <p>
 * Important: This class must be on the bootstrap classpath or agent classpath
 * for the advice to work correctly across class loaders.
 */
public class TraceInterceptor {

    /**
     * Advice executed at method entry.
     * 
     * @param className The fully qualified class name
     * @param methodName The method name
     * @param methodSignature The method signature
     * @param arguments All method arguments
     * @param method The method being called (for parameter names)
     * @return Array containing [callId, startTimeNanos] for use in exit advice
     */
    @Advice.OnMethodEnter
    public static Object[] onEnter(
            @Advice.Origin("#t") String className,
            @Advice.Origin("#m") String methodName,
            @Advice.Origin("#s") String methodSignature,
            @Advice.AllArguments Object[] arguments,
            @Advice.Origin Method method
    ) {
        try {
            // Log that we entered the advice
            System.out.println("[Bytesight-Trace] ENTER: " + className + "." + methodName + methodSignature);
            
            long startTimeNanos = System.nanoTime();
            
            // Extract parameter names and types from the method
            Class<?>[] parameterTypes = method.getParameterTypes();
            String[] parameterNames = getParameterNames(method);
            
            // Record the entry event and get the call ID
            String callId = TraceEventBuffer.getInstance().recordEntry(
                    className,
                    methodName,
                    methodSignature,
                    arguments,
                    parameterNames,
                    parameterTypes
            );
            
            System.out.println("[Bytesight-Trace] ENTER recorded, callId=" + callId);
            
            // Return state to be used in exit advice
            return new Object[]{callId, startTimeNanos, className, methodName, methodSignature, method.getReturnType()};
        } catch (Throwable t) {
            // Log errors instead of silently ignoring
            System.err.println("[Bytesight-Trace] ERROR in onEnter: " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace();
            return null;
        }
    }

    /**
     * Advice executed at method exit (normal return).
     * 
     * @param enterState State from onEnter containing callId and startTimeNanos
     * @param returnValue The method's return value
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(
            @Advice.Enter Object[] enterState,
            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returnValue,
            @Advice.Thrown Throwable thrown
    ) {
        if (enterState == null) {
            System.out.println("[Bytesight-Trace] EXIT: enterState is null, skipping");
            return;
        }
        
        try {
            String callId = (String) enterState[0];
            long startTimeNanos = (Long) enterState[1];
            String className = (String) enterState[2];
            String methodName = (String) enterState[3];
            String methodSignature = (String) enterState[4];
            Class<?> returnType = (Class<?>) enterState[5];
            
            System.out.println("[Bytesight-Trace] EXIT: " + className + "." + methodName + 
                    (thrown != null ? " (exception: " + thrown.getClass().getSimpleName() + ")" : ""));
            
            TraceEventBuffer buffer = TraceEventBuffer.getInstance();
            
            if (thrown != null) {
                // Exception was thrown
                buffer.recordException(
                        callId,
                        className,
                        methodName,
                        methodSignature,
                        thrown,
                        startTimeNanos
                );
            } else {
                // Normal return
                buffer.recordExit(
                        callId,
                        className,
                        methodName,
                        methodSignature,
                        returnValue,
                        returnType,
                        startTimeNanos
                );
            }
            
            System.out.println("[Bytesight-Trace] EXIT recorded for callId=" + callId);
        } catch (Throwable t) {
            // Log errors instead of silently ignoring
            System.err.println("[Bytesight-Trace] ERROR in onExit: " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace();
        }
    }

    /**
     * Extract parameter names from a method.
     * Parameter names are only available if compiled with -parameters flag,
     * otherwise we fall back to "arg0", "arg1", etc.
     * <p>
     * NOTE: This method must be public because ByteBuddy advice is inlined into
     * the target class, which needs to be able to call this method.
     */
    public static String[] getParameterNames(Method method) {
        java.lang.reflect.Parameter[] parameters = method.getParameters();
        String[] names = new String[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            // isNamePresent() returns true only if compiled with -parameters
            if (parameters[i].isNamePresent()) {
                names[i] = parameters[i].getName();
            } else {
                names[i] = "arg" + i;
            }
        }
        return names;
    }
}
