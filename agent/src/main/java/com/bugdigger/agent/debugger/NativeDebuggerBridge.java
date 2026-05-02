package com.bugdigger.agent.debugger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JNI bridge to the native JVMTI debugger helper (bytesight_debugger.dll).
 *
 * <p>NOTE: The class name and package must stay stable — the C++ side uses the
 * mangled JNI symbol name {@code Java_com_bugdigger_agent_debugger_NativeDebuggerBridge_*}
 * for each native method. Renaming silently breaks the binding without any
 * compile-time warning.
 *
 * <p>The native helper acquires only what HotSpot allows in JVMTI live phase:
 * {@code can_suspend} (real Pause via {@link #suspendThread(long)} /
 * {@link #suspendAll(long)}) and {@code can_get_line_numbers}. Breakpoints
 * and stepping use ByteBuddy and do not depend on this bridge.
 */
public final class NativeDebuggerBridge {
    private static final Logger logger = LoggerFactory.getLogger(NativeDebuggerBridge.class);

    // JVMTI error codes we treat as success on suspend/resume because they're
    // idempotent ("already in target state"). Spelled out for readability.
    private static final int JVMTI_ERROR_NONE = 0;
    private static final int JVMTI_ERROR_THREAD_NOT_SUSPENDED = 13;
    private static final int JVMTI_ERROR_THREAD_SUSPENDED = 14;

    private NativeDebuggerBridge() {}

    /** Acquires the {@code jvmtiEnv*} and adds capabilities. Called once after {@code System.load}. */
    static native boolean nativeInit();

    /** True if {@link #nativeInit()} succeeded and the debugger natives can be used. */
    static native boolean nativeIsAvailable();

    /** Last error encountered during init, or empty string. Useful for diagnostics. */
    static native String nativeLastError();

    /** Raw JVMTI SuspendThread. Returns the JVMTI error code; 0 = success. */
    static native int nativeSuspendThread(Thread thread);

    /** Raw JVMTI ResumeThread. Returns the JVMTI error code; 0 = success. */
    static native int nativeResumeThread(Thread thread);

    // ===== Java-side helpers ==================================================

    /**
     * True if the native helper is loaded AND its {@code nativeInit} succeeded.
     * Always check this before calling any of the {@code suspend*}/{@code resume*}
     * helpers — they short-circuit when false but a direct native call would throw
     * {@link UnsatisfiedLinkError} if the DLL was never bound.
     */
    public static boolean isAvailable() {
        return NativeDebuggerLoader.isLoaded() && nativeIsAvailable();
    }

    /** Last error from the native helper (load error or AddCapabilities error). */
    public static String lastError() {
        if (!NativeDebuggerLoader.isLoaded()) return NativeDebuggerLoader.lastError();
        return nativeLastError();
    }

    /**
     * Suspends the thread with the given id. Returns true on success or if the
     * thread was already suspended. Returns false if not found or native errors.
     */
    @SuppressWarnings("deprecation")  // Thread.getId() — JDK 17 toolchain
    public static boolean suspendThread(long threadId) {
        if (!NativeDebuggerLoader.isLoaded()) return false;
        Thread t = findThread(threadId);
        if (t == null) return false;
        int err = nativeSuspendThread(t);
        boolean ok = err == JVMTI_ERROR_NONE || err == JVMTI_ERROR_THREAD_SUSPENDED;
        if (!ok) logger.warn("nativeSuspendThread({}) returned JVMTI error {}", t.getName(), err);
        return ok;
    }

    /**
     * Resumes the thread with the given id. Returns true if the thread was
     * resumed; false if not found, not suspended, or native errors.
     */
    @SuppressWarnings("deprecation")
    public static boolean resumeThread(long threadId) {
        if (!NativeDebuggerLoader.isLoaded()) return false;
        Thread t = findThread(threadId);
        if (t == null) return false;
        int err = nativeResumeThread(t);
        if (err == JVMTI_ERROR_NONE) return true;
        if (err == JVMTI_ERROR_THREAD_NOT_SUSPENDED) return false;  // not an error, just nothing to do
        logger.warn("nativeResumeThread({}) returned JVMTI error {}", t.getName(), err);
        return false;
    }

    /**
     * Suspends every live thread except the calling thread, the thread with
     * id {@code excludeThreadId} (use 0 for none), and Bytesight's own
     * infrastructure threads. Returns the count of newly-suspended threads.
     */
    @SuppressWarnings("deprecation")
    public static int suspendAll(long excludeThreadId) {
        if (!NativeDebuggerLoader.isLoaded()) return 0;
        Thread current = Thread.currentThread();
        int suspended = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t == current) continue;
            if (excludeThreadId != 0 && t.getId() == excludeThreadId) continue;
            if (isInfrastructureThread(t)) continue;
            int err = nativeSuspendThread(t);
            if (err == JVMTI_ERROR_NONE) suspended++;
        }
        return suspended;
    }

    /**
     * Resumes every suspended thread. Returns the count of resumed threads
     * (excludes those that were not suspended).
     */
    public static int resumeAll() {
        if (!NativeDebuggerLoader.isLoaded()) return 0;
        int resumed = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            int err = nativeResumeThread(t);
            if (err == JVMTI_ERROR_NONE) resumed++;
        }
        return resumed;
    }

    @SuppressWarnings("deprecation")
    private static Thread findThread(long threadId) {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getId() == threadId) return t;
        }
        return null;
    }

    private static boolean isInfrastructureThread(Thread t) {
        String n = t.getName();
        if (n == null) return false;
        // Skip Bytesight's own threads + JVM bookkeeping threads. These would
        // either deadlock the agent (gRPC handler suspending itself) or break
        // the JVM (Reference Handler stalled => GC pauses balloon).
        return n.startsWith("grpc-")
            || n.startsWith("Bytesight")
            || n.equals("Attach Listener")
            || n.equals("Reference Handler")
            || n.equals("Finalizer")
            || n.equals("Signal Dispatcher")
            || n.equals("Common-Cleaner")
            || n.equals("Notification Thread")
            || n.startsWith("Service Thread")
            || n.startsWith("Compiler Thread");
    }
}
