package com.bugdigger.agent.collector;

/**
 * Holds information about a loaded class.
 */
public class LoadedClassInfo {
    private final String className;
    private final Class<?> loadedClass;
    private final long loadedAt;
    
    public LoadedClassInfo(String className, Class<?> loadedClass) {
        this.className = className;
        this.loadedClass = loadedClass;
        this.loadedAt = System.currentTimeMillis();
    }
    
    public String getClassName() {
        return className;
    }
    
    public Class<?> getLoadedClass() {
        return loadedClass;
    }
    
    public long getLoadedAt() {
        return loadedAt;
    }
}
