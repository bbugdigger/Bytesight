package com.bugdigger.agent.hook;

import com.bugdigger.protocol.ArgumentValue;
import com.bugdigger.protocol.MethodTraceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Thread-safe buffer for trace events that broadcasts to registered listeners.
 * Used to collect method trace events from instrumented code and stream them
 * to gRPC subscribers.
 */
public class TraceEventBuffer {
    private static final Logger logger = LoggerFactory.getLogger(TraceEventBuffer.class);
    
    // Singleton instance for access from ByteBuddy advice (which runs in target class context)
    private static volatile TraceEventBuffer instance;
    
    private final List<Consumer<MethodTraceEvent>> listeners = new CopyOnWriteArrayList<>();
    
    // Thread-local call stack tracking for depth and parent-child relationships
    private final ThreadLocal<CallStack> callStacks = ThreadLocal.withInitial(CallStack::new);
    
    private TraceEventBuffer() {}
    
    public static TraceEventBuffer getInstance() {
        if (instance == null) {
            synchronized (TraceEventBuffer.class) {
                if (instance == null) {
                    instance = new TraceEventBuffer();
                }
            }
        }
        return instance;
    }
    
    /**
     * Registers a listener for trace events.
     * @return A handle to unregister the listener
     */
    public Runnable addListener(Consumer<MethodTraceEvent> listener) {
        listeners.add(listener);
        logger.info("Added trace event listener, total listeners: {}", listeners.size());
        System.out.println("[Bytesight-Buffer] Added listener, total: " + listeners.size());
        return () -> {
            listeners.remove(listener);
            logger.info("Removed trace event listener, total listeners: {}", listeners.size());
            System.out.println("[Bytesight-Buffer] Removed listener, total: " + listeners.size());
        };
    }
    
    /**
     * Records a method entry event.
     * Called from ByteBuddy advice on method entry.
     */
    public String recordEntry(String className, String methodName, String methodSignature,
                              Object[] arguments, String[] parameterNames, Class<?>[] parameterTypes) {
        System.out.println("[Bytesight-Buffer] recordEntry called for " + className + "." + methodName + ", listeners=" + listeners.size());
        
        if (listeners.isEmpty()) {
            System.out.println("[Bytesight-Buffer] No listeners, returning null");
            return null;
        }
        
        String callId = UUID.randomUUID().toString();
        CallStack stack = callStacks.get();
        String parentCallId = stack.peek();
        int depth = stack.depth();
        stack.push(callId);
        
        MethodTraceEvent.Builder builder = MethodTraceEvent.newBuilder()
            .setCallId(callId)
            .setTimestamp(System.currentTimeMillis())
            .setThreadId(Thread.currentThread().getId())
            .setThreadName(Thread.currentThread().getName())
            .setClassName(className)
            .setMethodName(methodName)
            .setMethodSignature(methodSignature)
            .setEventType(MethodTraceEvent.TraceEventType.ENTRY)
            .setDepth(depth);
        
        if (parentCallId != null) {
            builder.setParentCallId(parentCallId);
        }
        
        // Add argument values
        if (arguments != null && parameterTypes != null) {
            for (int i = 0; i < arguments.length && i < parameterTypes.length; i++) {
                String name = (parameterNames != null && i < parameterNames.length) 
                    ? parameterNames[i] : "arg" + i;
                builder.addArguments(toArgumentValue(name, parameterTypes[i], arguments[i]));
            }
        }
        
        broadcast(builder.build());
        return callId;
    }
    
    /**
     * Records a method exit event.
     * Called from ByteBuddy advice on method return.
     */
    public void recordExit(String callId, String className, String methodName, String methodSignature,
                           Object returnValue, Class<?> returnType, long startTimeNanos) {
        if (listeners.isEmpty() || callId == null) {
            callStacks.get().pop();
            return;
        }
        
        CallStack stack = callStacks.get();
        stack.pop();
        int depth = stack.depth();
        String parentCallId = stack.peek();
        
        long durationNanos = System.nanoTime() - startTimeNanos;
        
        MethodTraceEvent.Builder builder = MethodTraceEvent.newBuilder()
            .setCallId(callId)
            .setTimestamp(System.currentTimeMillis())
            .setThreadId(Thread.currentThread().getId())
            .setThreadName(Thread.currentThread().getName())
            .setClassName(className)
            .setMethodName(methodName)
            .setMethodSignature(methodSignature)
            .setEventType(MethodTraceEvent.TraceEventType.EXIT)
            .setDepth(depth)
            .setDurationNanos(durationNanos);
        
        if (parentCallId != null) {
            builder.setParentCallId(parentCallId);
        }
        
        // Add return value (if not void)
        if (returnType != null && returnType != void.class) {
            builder.setReturnValue(toArgumentValue("return", returnType, returnValue));
        }
        
        broadcast(builder.build());
    }
    
    /**
     * Records a method exception event.
     * Called from ByteBuddy advice when an exception is thrown.
     */
    public void recordException(String callId, String className, String methodName, String methodSignature,
                                Throwable exception, long startTimeNanos) {
        if (listeners.isEmpty() || callId == null) {
            callStacks.get().pop();
            return;
        }
        
        CallStack stack = callStacks.get();
        stack.pop();
        int depth = stack.depth();
        String parentCallId = stack.peek();
        
        long durationNanos = System.nanoTime() - startTimeNanos;
        
        MethodTraceEvent.Builder builder = MethodTraceEvent.newBuilder()
            .setCallId(callId)
            .setTimestamp(System.currentTimeMillis())
            .setThreadId(Thread.currentThread().getId())
            .setThreadName(Thread.currentThread().getName())
            .setClassName(className)
            .setMethodName(methodName)
            .setMethodSignature(methodSignature)
            .setEventType(MethodTraceEvent.TraceEventType.EXCEPTION)
            .setDepth(depth)
            .setDurationNanos(durationNanos)
            .setExceptionClass(exception.getClass().getName())
            .setExceptionMessage(exception.getMessage() != null ? exception.getMessage() : "");
        
        if (parentCallId != null) {
            builder.setParentCallId(parentCallId);
        }
        
        // Capture stack trace
        StringBuilder stackTrace = new StringBuilder();
        for (StackTraceElement element : exception.getStackTrace()) {
            stackTrace.append(element.toString()).append("\n");
        }
        builder.setStackTrace(stackTrace.toString());
        
        broadcast(builder.build());
    }
    
    private void broadcast(MethodTraceEvent event) {
        for (Consumer<MethodTraceEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                logger.warn("Error broadcasting trace event to listener", e);
            }
        }
    }
    
    private ArgumentValue toArgumentValue(String name, Class<?> type, Object value) {
        ArgumentValue.Builder builder = ArgumentValue.newBuilder()
            .setName(name)
            .setType(type.getName());
        
        if (value == null) {
            builder.setIsNull(true);
            builder.setValue("null");
        } else {
            builder.setIsNull(false);
            String stringValue = formatValue(value, type);
            // Truncate very long values
            if (stringValue.length() > 1000) {
                stringValue = stringValue.substring(0, 1000) + "...";
                builder.setTruncated(true);
            }
            builder.setValue(stringValue);
        }
        
        return builder.build();
    }
    
    private String formatValue(Object value, Class<?> type) {
        if (value == null) {
            return "null";
        }
        
        try {
            if (type.isArray()) {
                return formatArrayValue(value);
            }
            
            if (value instanceof String) {
                return "\"" + value + "\"";
            }
            
            if (value instanceof Character) {
                return "'" + value + "'";
            }
            
            return value.toString();
        } catch (Exception e) {
            return "<error: " + e.getMessage() + ">";
        }
    }
    
    private String formatArrayValue(Object array) {
        if (array instanceof byte[]) {
            byte[] arr = (byte[]) array;
            return "byte[" + arr.length + "]";
        }
        if (array instanceof int[]) {
            int[] arr = (int[]) array;
            if (arr.length <= 10) {
                return java.util.Arrays.toString(arr);
            }
            return "int[" + arr.length + "]";
        }
        if (array instanceof long[]) {
            long[] arr = (long[]) array;
            if (arr.length <= 10) {
                return java.util.Arrays.toString(arr);
            }
            return "long[" + arr.length + "]";
        }
        if (array instanceof double[]) {
            double[] arr = (double[]) array;
            if (arr.length <= 10) {
                return java.util.Arrays.toString(arr);
            }
            return "double[" + arr.length + "]";
        }
        if (array instanceof Object[]) {
            Object[] arr = (Object[]) array;
            if (arr.length <= 10) {
                return java.util.Arrays.toString(arr);
            }
            return arr.getClass().getComponentType().getSimpleName() + "[" + arr.length + "]";
        }
        
        return array.getClass().getSimpleName();
    }
    
    /**
     * Simple call stack tracker for maintaining call hierarchy per thread.
     */
    private static class CallStack {
        private final java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        
        void push(String callId) {
            stack.push(callId);
        }
        
        String pop() {
            return stack.isEmpty() ? null : stack.pop();
        }
        
        String peek() {
            return stack.peek();
        }
        
        int depth() {
            return stack.size();
        }
    }
}
