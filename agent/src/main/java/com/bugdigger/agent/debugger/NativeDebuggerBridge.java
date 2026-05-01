package com.bugdigger.agent.debugger;

/**
 * JNI bridge to the native JVMTI debugger helper (bytesight_debugger.dll).
 *
 * <p>NOTE: The class name and package must stay stable — the C++ side uses the
 * mangled JNI symbol name {@code Java_com_bugdigger_agent_debugger_NativeDebuggerBridge_*}
 * for each native method. Renaming silently breaks the binding without any
 * compile-time warning.
 *
 * <p>Step 1: only init / availability / error reporting. Subsequent steps add
 * breakpoint, stepping, and locals natives.
 */
public final class NativeDebuggerBridge {

    private NativeDebuggerBridge() {}

    /** Acquires the {@code jvmtiEnv*} and adds capabilities. Called once after {@code System.load}. */
    public static native boolean nativeInit();

    /** True if {@link #nativeInit()} succeeded and the debugger natives can be used. */
    public static native boolean nativeIsAvailable();

    /** Last error encountered during init, or empty string. Useful for diagnostics. */
    public static native String nativeLastError();
}
