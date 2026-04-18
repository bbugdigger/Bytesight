package com.bugdigger.agent.heap;

/**
 * JNI bridge to the native JVMTI heap helper (bytesight_heap.dll).
 *
 * NOTE: The class name and package must stay stable — the C++ side uses the
 * mangled JNI symbol name {@code Java_com_bugdigger_agent_heap_HeapInspector_*}
 * for each native method. Renaming breaks the binding without any compile-time
 * warning.
 */
public final class HeapInspector {

    private HeapInspector() {}

    /**
     * Returned by {@link #nativeCaptureSnapshot()} — mirrors the proto
     * {@code HeapSnapshotInfo} but stays inside the agent module so the native
     * code does not depend on protobuf-generated classes.
     */
    public static final class NativeSnapshotInfo {
        public final long snapshotId;
        public final long objectCount;
        public final long totalShallowBytes;
        public final long capturedAtMillis;
        public final boolean available;
        public final String error;

        public NativeSnapshotInfo(
            long snapshotId,
            long objectCount,
            long totalShallowBytes,
            long capturedAtMillis,
            boolean available,
            String error
        ) {
            this.snapshotId = snapshotId;
            this.objectCount = objectCount;
            this.totalShallowBytes = totalShallowBytes;
            this.capturedAtMillis = capturedAtMillis;
            this.available = available;
            this.error = error == null ? "" : error;
        }
    }

    /** Acquires the {@code jvmtiEnv*} and adds capabilities. Called once after {@code System.load}. */
    public static native boolean nativeInit();

    /** True if {@link #nativeInit()} succeeded and the heap APIs can be used. */
    public static native boolean nativeIsAvailable();

    /** Walks the heap, tags every object, returns snapshot metadata. */
    public static native NativeSnapshotInfo nativeCaptureSnapshot();

    /**
     * Returns a 2D array. Each row is {@code {String className, Long count, Long shallowBytes}}.
     * {@code filter} is a substring matched against the fully-qualified class name
     * (empty string returns all entries).
     */
    public static native Object[][] nativeGetClassHistogram(long snapshotId, String filter);

    /**
     * Returns a 2D array. Each row is {@code {Long tag, String className, Long shallowBytes, String preview}}.
     */
    public static native Object[][] nativeListInstances(long snapshotId, String className, int limit);

    /**
     * Returns a {@link NativeObjectDetail} with all fields and outgoing references for the
     * object identified by {@code tag} in the given snapshot.
     */
    public static native NativeObjectDetail nativeGetObject(long snapshotId, long tag);

    /**
     * Searches for values in a snapshot. If {@code stringContains} is non-empty, does a
     * substring search across all java.lang.String instances. Otherwise, searches for objects
     * of {@code fieldClassName} where field {@code fieldName} equals {@code fieldValue}.
     *
     * <p>Returns a 2D array. Each row is {@code {Long tag, String className, String matchedField, String matchedValue}}.
     */
    public static native Object[][] nativeSearchValues(
        long snapshotId, String stringContains,
        String fieldClassName, String fieldName, String fieldValue, int limit);

    /**
     * Finds duplicate java.lang.String instances in the snapshot.
     *
     * <p>Returns a 2D array. Each row is {@code {String value, Integer count, Long wastedBytes, Long[] exampleTags}}.
     */
    public static native Object[][] nativeFindDuplicateStrings(
        long snapshotId, int minCount, int minLength, int limitGroups);

    /**
     * Mirrors proto {@code ObjectDetail}. Fields and refs are encoded as 2D arrays
     * to avoid pulling protobuf into the native layer.
     *
     * <p>Fields array: each row is {@code {String name, String declaredType, Integer kind,
     * Long intValue, Double doubleValue, String stringValue, Long refTag, Boolean isStatic, Boolean isNull}}.
     *
     * <p>Refs array: each row is {@code {String fieldName, Long targetTag, String targetClassName}}.
     */
    public static final class NativeObjectDetail {
        public final long tag;
        public final String className;
        public final long shallowBytes;
        public final boolean found;
        public final String error;
        public final Object[][] fields;
        public final Object[][] outgoingRefs;

        public NativeObjectDetail(
            long tag, String className, long shallowBytes,
            boolean found, String error,
            Object[][] fields, Object[][] outgoingRefs
        ) {
            this.tag = tag;
            this.className = className;
            this.shallowBytes = shallowBytes;
            this.found = found;
            this.error = error == null ? "" : error;
            this.fields = fields != null ? fields : new Object[0][];
            this.outgoingRefs = outgoingRefs != null ? outgoingRefs : new Object[0][];
        }
    }
}
