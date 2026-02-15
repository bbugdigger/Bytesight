package com.bugdigger.agent.collector;

/**
 * Listener interface for class loading events.
 */
@FunctionalInterface
public interface ClassLoadListener {
    /**
     * Called when a class is loaded or transformed.
     * 
     * @param classInfo Information about the loaded class
     * @param bytecode The class bytecode (may be null if not captured)
     */
    void onClassLoad(LoadedClassInfo classInfo, byte[] bytecode);
}
