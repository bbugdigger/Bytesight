package com.bugdigger.agent.heap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Extracts the native JVMTI helper from the agent JAR and loads it via
 * {@link System#load(String)}. Call {@link #load()} once from the agent's
 * initialization; subsequent calls are no-ops.
 *
 * <p>If loading fails, the agent logs a warning and continues — heap RPCs will
 * return an error response, but every other feature keeps working.
 */
public final class HeapNativeLoader {
    private static final Logger logger = LoggerFactory.getLogger(HeapNativeLoader.class);

    private static volatile boolean attempted = false;
    private static volatile boolean loaded = false;
    private static volatile String lastError = "";

    private HeapNativeLoader() {}

    public static synchronized void load() {
        if (attempted) return;
        attempted = true;

        try {
            final String osArch = resolvePlatformDir();
            final String libName = libraryFileName();
            final String resource = "/native/" + osArch + "/" + libName;

            Path tmp = Files.createTempFile("bytesight_heap_", suffixFor(libName));
            tmp.toFile().deleteOnExit();

            try (InputStream in = HeapNativeLoader.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("Native library not bundled: " + resource);
                }
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }

            System.load(tmp.toAbsolutePath().toString());

            if (!HeapInspector.nativeInit()) {
                throw new IllegalStateException("nativeInit() returned false — JVMTI capabilities refused");
            }

            loaded = true;
            logger.info("[Bytesight] Heap native helper loaded ({})", resource);
        } catch (IOException | RuntimeException | UnsatisfiedLinkError e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            logger.warn("[Bytesight] Heap native helper unavailable: {}", lastError);
        }
    }

    public static boolean isLoaded() { return loaded; }
    public static String lastError() { return lastError; }

    private static String resolvePlatformDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (os.contains("win") && (arch.contains("amd64") || arch.contains("x86_64") || arch.contains("x64"))) {
            return "win-x64";
        }
        // Other platforms aren't bundled yet; callers see an error.
        throw new IllegalStateException("Unsupported platform: os=" + os + " arch=" + arch);
    }

    private static String libraryFileName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return "bytesight_heap.dll";
        if (os.contains("mac")) return "libbytesight_heap.dylib";
        return "libbytesight_heap.so";
    }

    private static String suffixFor(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? ".lib" : fileName.substring(dot);
    }
}
