package com.bugdigger.agent.heap;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Coordinates heap snapshot requests so that concurrent {@code CaptureHeapSnapshot}
 * calls don't overlap. JVMTI heap iteration is an expensive, safepoint-holding
 * operation — serialising through this manager keeps the target JVM responsive.
 */
public final class HeapSnapshotManager {

    private final ReentrantLock captureLock = new ReentrantLock();

    public boolean isAvailable() {
        return HeapNativeLoader.isLoaded();
    }

    public String lastError() {
        return HeapNativeLoader.lastError();
    }

    /** Blocks while any other capture is in progress, then walks the heap. */
    public HeapInspector.NativeSnapshotInfo capture() {
        if (!isAvailable()) {
            return new HeapInspector.NativeSnapshotInfo(0, 0, 0, 0, false, lastError());
        }
        captureLock.lock();
        try {
            return HeapInspector.nativeCaptureSnapshot();
        } finally {
            captureLock.unlock();
        }
    }

    /** Returns a snapshot of the histogram — each row is {className, count, shallowBytes}. */
    public List<Object[]> getHistogram(long snapshotId, String filter) {
        if (!isAvailable()) return List.of();
        Object[][] rows = HeapInspector.nativeGetClassHistogram(
            snapshotId,
            filter == null ? "" : filter
        );
        return rows == null ? List.of() : List.of(rows);
    }

    /** Returns instances — each row is {Long tag, String className, Long shallowBytes, String preview}. */
    public List<Object[]> listInstances(long snapshotId, String className, int limit) {
        if (!isAvailable()) return List.of();
        Object[][] rows = HeapInspector.nativeListInstances(snapshotId, className, limit);
        return rows == null ? List.of() : List.of(rows);
    }

    /** Returns object detail for one tagged object. */
    public HeapInspector.NativeObjectDetail getObject(long snapshotId, long tag) {
        if (!isAvailable()) {
            return new HeapInspector.NativeObjectDetail(tag, "", 0, false, lastError(), null, null);
        }
        return HeapInspector.nativeGetObject(snapshotId, tag);
    }

    /** Searches for values in a snapshot. Each row is {Long tag, String className, String matchedField, String matchedValue}. */
    public List<Object[]> searchValues(long snapshotId, String stringContains,
                                       String fieldClassName, String fieldName, String fieldValue, int limit) {
        if (!isAvailable()) return List.of();
        Object[][] rows = HeapInspector.nativeSearchValues(
            snapshotId,
            stringContains == null ? "" : stringContains,
            fieldClassName == null ? "" : fieldClassName,
            fieldName == null ? "" : fieldName,
            fieldValue == null ? "" : fieldValue,
            limit);
        return rows == null ? List.of() : List.of(rows);
    }

    /** Finds duplicate strings. Each row is {String value, Integer count, Long wastedBytes, Long[] exampleTags}. */
    public List<Object[]> findDuplicateStrings(long snapshotId, int minCount, int minLength, int limitGroups) {
        if (!isAvailable()) return List.of();
        Object[][] rows = HeapInspector.nativeFindDuplicateStrings(snapshotId, minCount, minLength, limitGroups);
        return rows == null ? List.of() : List.of(rows);
    }
}
