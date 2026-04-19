package com.bugdigger.agent.debugger;

import com.bugdigger.protocol.BreakpointHit;
import com.bugdigger.protocol.FrameSnapshot;
import com.bugdigger.protocol.Variable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.Arrays;

/**
 * Builds a {@link BreakpointHit} proto payload from the runtime state visible at a
 * parked breakpoint frame: {@code this} fields, method arguments, and the current
 * Java stack trace.
 */
public final class FrameCapture {
    private static final Logger logger = LoggerFactory.getLogger(FrameCapture.class);

    private FrameCapture() {
    }

    public static BreakpointHit captureBreakpointHit(String breakpointId,
                                                     String className,
                                                     String methodName,
                                                     String methodSignature,
                                                     Object self,
                                                     Object[] arguments,
                                                     Method method) {
        Thread current = Thread.currentThread();
        StackTraceElement[] stackTrace = current.getStackTrace();

        FrameSnapshot topFrame = buildTopFrame(className, methodName, methodSignature,
                self, arguments, method, stackTrace);

        BreakpointHit.Builder hitBuilder = BreakpointHit.newBuilder()
                .setBreakpointId(breakpointId == null ? "" : breakpointId)
                .setThreadId(current.getId())
                .setThreadName(current.getName())
                .setTopFrame(topFrame)
                .addStack(topFrame);

        // Append caller frames (line-info only; we do not unwind locals for them in v1).
        boolean seenTop = false;
        for (StackTraceElement e : stackTrace) {
            if (isOwnStackFrame(e)) continue;
            if (!seenTop) {
                if (e.getClassName().equals(className) && e.getMethodName().equals(methodName)) {
                    seenTop = true;
                }
                continue;
            }
            hitBuilder.addStack(FrameSnapshot.newBuilder()
                    .setClassName(e.getClassName())
                    .setMethodName(e.getMethodName())
                    .setLineNumber(Math.max(0, e.getLineNumber()))
                    .build());
        }

        return hitBuilder.build();
    }

    private static boolean isOwnStackFrame(StackTraceElement e) {
        String cls = e.getClassName();
        return cls.equals("java.lang.Thread")
                || cls.startsWith("com.bugdigger.agent.debugger.");
    }

    private static FrameSnapshot buildTopFrame(String className,
                                               String methodName,
                                               String methodSignature,
                                               Object self,
                                               Object[] arguments,
                                               Method method,
                                               StackTraceElement[] stackTrace) {
        FrameSnapshot.Builder frame = FrameSnapshot.newBuilder()
                .setClassName(className)
                .setMethodName(methodName)
                .setSignature(methodSignature == null ? "" : methodSignature);

        int line = findFrameLine(className, methodName, stackTrace);
        if (line > 0) frame.setLineNumber(line);

        if (arguments != null && method != null) {
            Parameter[] params = method.getParameters();
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < arguments.length; i++) {
                String name = (i < params.length && params[i].isNamePresent())
                        ? params[i].getName()
                        : "arg" + i;
                Class<?> type = i < types.length ? types[i] : Object.class;
                frame.addArguments(toVariable(name, type, arguments[i]));
            }
        }

        if (self != null) {
            for (Field f : self.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(self);
                    frame.addThisFields(toVariable(f.getName(), f.getType(), val));
                } catch (Throwable t) {
                    frame.addThisFields(Variable.newBuilder()
                            .setName(f.getName())
                            .setTypeName(f.getType().getName())
                            .setDisplayValue("<inaccessible: " + t.getClass().getSimpleName() + ">")
                            .build());
                }
            }
        }

        return frame.build();
    }

    private static int findFrameLine(String className, String methodName, StackTraceElement[] stackTrace) {
        for (StackTraceElement e : stackTrace) {
            if (isOwnStackFrame(e)) continue;
            if (e.getClassName().equals(className) && e.getMethodName().equals(methodName)) {
                return Math.max(0, e.getLineNumber());
            }
        }
        return 0;
    }

    private static Variable toVariable(String name, Class<?> type, Object value) {
        Variable.Builder b = Variable.newBuilder()
                .setName(name == null ? "" : name)
                .setTypeName(type == null ? "java.lang.Object" : type.getName());
        if (value == null) {
            b.setIsNull(true);
            b.setDisplayValue("null");
            return b.build();
        }
        b.setIsNull(false);
        b.setDisplayValue(renderValue(value, type));
        return b.build();
    }

    private static String renderValue(Object value, Class<?> type) {
        try {
            if (type != null && type.isArray()) return renderArray(value);
            if (value instanceof String) {
                String s = (String) value;
                if (s.length() > 200) s = s.substring(0, 200) + "...";
                return "\"" + s + "\"";
            }
            if (value instanceof Character) return "'" + value + "'";
            if (value instanceof Number || value instanceof Boolean) return value.toString();
            String str = value.toString();
            if (str == null) return "null";
            if (str.length() > 200) str = str.substring(0, 200) + "...";
            return str;
        } catch (Throwable t) {
            return "<error: " + t.getClass().getSimpleName() + ">";
        }
    }

    private static String renderArray(Object array) {
        if (array instanceof byte[]) return "byte[" + ((byte[]) array).length + "]";
        if (array instanceof int[]) {
            int[] a = (int[]) array;
            return a.length <= 10 ? Arrays.toString(a) : "int[" + a.length + "]";
        }
        if (array instanceof long[]) {
            long[] a = (long[]) array;
            return a.length <= 10 ? Arrays.toString(a) : "long[" + a.length + "]";
        }
        if (array instanceof double[]) {
            double[] a = (double[]) array;
            return a.length <= 10 ? Arrays.toString(a) : "double[" + a.length + "]";
        }
        if (array instanceof boolean[]) {
            boolean[] a = (boolean[]) array;
            return a.length <= 10 ? Arrays.toString(a) : "boolean[" + a.length + "]";
        }
        if (array instanceof Object[]) {
            Object[] a = (Object[]) array;
            return a.length <= 10 ? Arrays.toString(a) : a.getClass().getComponentType().getSimpleName() + "[" + a.length + "]";
        }
        return array.getClass().getSimpleName();
    }
}
