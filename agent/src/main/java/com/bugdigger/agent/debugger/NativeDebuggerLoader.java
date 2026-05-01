package com.bugdigger.agent.debugger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Loads the native debugger DLL via {@link System#load(String)} so that JNI
 * symbol auto-resolution can bind {@link NativeDebuggerBridge}'s native methods.
 *
 * <p>The DLL path is supplied by the attaching composeApp process via the
 * {@code debuggerDllPath} agent argument. composeApp also calls
 * {@code VirtualMachine.loadAgentPath} on the same path to drive
 * {@code Agent_OnAttach}, where JVMTI capabilities are acquired. Both calls
 * target the identical file so the OS maps it once.
 *
 * <p>If the DLL path is missing or load fails, the agent logs a warning and
 * continues — debugger RPCs will return unavailable, but every other feature
 * keeps working.
 */
public final class NativeDebuggerLoader {
    private static final Logger logger = LoggerFactory.getLogger(NativeDebuggerLoader.class);

    private static volatile boolean attempted = false;
    private static volatile boolean loaded = false;
    private static volatile String lastError = "";

    private NativeDebuggerLoader() {}

    public static synchronized void load(String dllPath) {
        if (attempted) return;
        attempted = true;

        try {
            if (dllPath == null || dllPath.isBlank()) {
                throw new IllegalArgumentException("debuggerDllPath agent arg not set");
            }
            File dll = new File(dllPath);
            if (!dll.isFile()) {
                throw new IllegalStateException("DLL missing at: " + dll.getAbsolutePath());
            }
            System.load(dll.getAbsolutePath());
            loaded = true;
            logger.info("[Bytesight] Debugger native library bound via System.load: {}", dll.getAbsolutePath());
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            logger.warn("[Bytesight] Debugger native library not bound: {}", lastError);
        }
    }

    public static boolean isLoaded() { return loaded; }
    public static String lastError() { return lastError; }
}
