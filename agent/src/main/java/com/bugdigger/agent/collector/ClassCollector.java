package com.bugdigger.agent.collector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Collects information about loaded classes and their bytecode.
 * Implements ClassFileTransformer to capture bytecode during class loading.
 */
public class ClassCollector implements ClassFileTransformer {
    private static final Logger logger = LoggerFactory.getLogger(ClassCollector.class);
    
    private final Instrumentation instrumentation;
    
    // Maps class name to its info
    private final Map<String, LoadedClassInfo> loadedClasses = new ConcurrentHashMap<>();
    
    // Maps class name to its bytecode
    private final Map<String, byte[]> classBytecode = new ConcurrentHashMap<>();
    
    // Listeners for class load events
    private final List<ClassLoadListener> listeners = new CopyOnWriteArrayList<>();
    
    public ClassCollector(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }
    
    /**
     * Called by the JVM when a class is being loaded or transformed.
     */
    @Override
    public byte[] transform(ClassLoader loader, 
                            String className, 
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, 
                            byte[] classfileBuffer) {
        
        if (className == null) {
            return null;
        }
        
        String normalizedName = className.replace('/', '.');
        
        try {
            // Store the bytecode
            classBytecode.put(normalizedName, classfileBuffer.clone());
            
            // Create class info (class object might not be available yet during loading)
            LoadedClassInfo info = new LoadedClassInfo(normalizedName, classBeingRedefined);
            loadedClasses.put(normalizedName, info);
            
            // Notify listeners
            for (ClassLoadListener listener : listeners) {
                try {
                    listener.onClassLoad(info, classfileBuffer);
                } catch (Exception e) {
                    logger.warn("Listener threw exception for class {}: {}", normalizedName, e.getMessage());
                }
            }
            
            logger.trace("Captured bytecode for: {} ({} bytes)", normalizedName, classfileBuffer.length);
            
        } catch (Exception e) {
            logger.warn("Failed to capture class {}: {}", normalizedName, e.getMessage());
        }
        
        // Return null to indicate we don't want to modify the bytecode
        return null;
    }
    
    /**
     * Capture information about already loaded classes.
     * Should be called after the agent is attached to capture existing classes.
     */
    public void captureLoadedClasses(Class<?>[] classes) {
        logger.info("Capturing {} already loaded classes", classes.length);
        
        int captured = 0;
        for (Class<?> clazz : classes) {
            if (clazz == null) continue;
            
            String className = clazz.getName();
            
            // Skip if we already have this class
            if (loadedClasses.containsKey(className)) {
                continue;
            }
            
            // Don't try to capture array classes
            if (clazz.isArray()) {
                continue;
            }
            
            LoadedClassInfo info = new LoadedClassInfo(className, clazz);
            loadedClasses.put(className, info);
            
            // Try to get bytecode by retransforming
            // Note: This only works if the class is retransformable
            if (instrumentation.isRetransformClassesSupported() && 
                instrumentation.isModifiableClass(clazz)) {
                try {
                    // This will trigger our transform method with the bytecode
                    instrumentation.retransformClasses(clazz);
                    captured++;
                } catch (Exception e) {
                    // Many system classes can't be retransformed, that's OK
                    logger.trace("Could not retransform {}: {}", className, e.getMessage());
                }
            }
        }
        
        logger.info("Successfully captured bytecode for {} classes", captured);
    }
    
    /**
     * Get all loaded classes.
     */
    public Map<String, LoadedClassInfo> getLoadedClasses() {
        return loadedClasses;
    }
    
    /**
     * Get the bytecode for a specific class.
     */
    public byte[] getBytecode(String className) {
        return classBytecode.get(className);
    }
    
    /**
     * Check if we have the bytecode for a class.
     */
    public boolean hasBytecode(String className) {
        return classBytecode.containsKey(className);
    }
    
    /**
     * Add a listener for class load events.
     */
    public void addClassLoadListener(ClassLoadListener listener) {
        listeners.add(listener);
    }
    
    /**
     * Remove a class load listener.
     */
    public void removeClassLoadListener(ClassLoadListener listener) {
        listeners.remove(listener);
    }
    
    /**
     * Get the number of classes we have bytecode for.
     */
    public int getBytecodeCount() {
        return classBytecode.size();
    }
    
    /**
     * Get the total number of known loaded classes.
     */
    public int getLoadedClassCount() {
        return loadedClasses.size();
    }
}
