package com.bugdigger.agent.hook;

import com.bugdigger.protocol.HookInfo;
import com.bugdigger.protocol.HookType;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.Instrumentation;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages method hooks using ByteBuddy.
 * <p>
 * This class handles adding and removing hooks on methods in the target JVM.
 * Each hook instruments a specific method to capture entry, exit, and exception events.
 */
public class HookManager {
    private static final Logger logger = LoggerFactory.getLogger(HookManager.class);

    private final Instrumentation instrumentation;
    private final Map<String, ManagedHook> activeHooks = new ConcurrentHashMap<>();

    public HookManager(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        logger.info("HookManager initialized");
    }

    /**
     * Adds a hook to the specified method.
     *
     * @param hookId          Unique identifier for the hook
     * @param className       Fully qualified class name (e.g., "com.example.MyClass")
     * @param methodName      Method name to hook
     * @param methodSignature Optional method signature for overloads (null for any overload)
     * @param hookType        Type of hook to install
     * @return Result of the hook operation
     */
    public HookResult addHook(String hookId, String className, String methodName,
                              String methodSignature, HookType hookType) {
        if (activeHooks.containsKey(hookId)) {
            return HookResult.failure("Hook with ID '" + hookId + "' already exists");
        }

        logger.info("Adding hook '{}' for {}.{}{}", hookId, className, methodName,
                methodSignature != null ? methodSignature : "");
        System.out.println("[Bytesight] Adding hook '" + hookId + "' for " + className + "." + methodName);

        try {
            // Build method matcher
            ElementMatcher.Junction<MethodDescription> methodMatcher = ElementMatchers.named(methodName);
            if (methodSignature != null && !methodSignature.isEmpty()) {
                logger.info("Using method signature filter: {}", methodSignature);
                methodMatcher = methodMatcher.and(ElementMatchers.hasDescriptor(methodSignature));
            }

            // Store the final matcher for use in lambda
            final ElementMatcher.Junction<MethodDescription> finalMethodMatcher = methodMatcher;

            logger.info("Installing ByteBuddy transformer for class: {}", className);
            
            // Build and install the transformer
            ResettableClassFileTransformer transformer = new AgentBuilder.Default()
                    .disableClassFormatChanges()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(new LoggingListener(hookId))
                    .type(ElementMatchers.named(className))
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) -> {
                        logger.info("Transform callback for {} (classLoader={})", typeDescription.getName(), classLoader);
                        System.out.println("[Bytesight] Transforming " + typeDescription.getName());
                        return builder.visit(Advice.to(TraceInterceptor.class).on(finalMethodMatcher));
                    })
                    .installOn(instrumentation);

            logger.info("Transformer installed, now storing hook");

            // Create and store the managed hook
            ManagedHook managedHook = new ManagedHook(
                    hookId, className, methodName, methodSignature,
                    hookType, transformer, System.currentTimeMillis()
            );
            activeHooks.put(hookId, managedHook);

            // Force retransformation of the target class if already loaded
            retransformClass(className);

            logger.info("Hook '{}' installed successfully", hookId);
            System.out.println("[Bytesight] Hook '" + hookId + "' installed successfully");
            return HookResult.success(hookId);

        } catch (Exception e) {
            logger.error("Failed to add hook '{}': {}", hookId, e.getMessage(), e);
            System.err.println("[Bytesight] Failed to add hook '" + hookId + "': " + e.getMessage());
            e.printStackTrace();
            return HookResult.failure("Failed to add hook: " + e.getMessage());
        }
    }

    /**
     * Removes a hook by ID.
     *
     * @param hookId The hook ID to remove
     * @return Result of the operation
     */
    public HookResult removeHook(String hookId) {
        ManagedHook hook = activeHooks.remove(hookId);
        if (hook == null) {
            return HookResult.failure("Hook with ID '" + hookId + "' not found");
        }

        logger.info("Removing hook '{}' for {}.{}", hookId, hook.className, hook.methodName);

        try {
            // Reset the transformer to remove instrumentation
            hook.transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);

            // Retransform the class to remove our changes
            retransformClass(hook.className);

            logger.info("Hook '{}' removed successfully", hookId);
            return HookResult.success(hookId);

        } catch (Exception e) {
            logger.error("Failed to remove hook '{}': {}", hookId, e.getMessage(), e);
            // Put the hook back since removal failed
            activeHooks.put(hookId, hook);
            return HookResult.failure("Failed to remove hook: " + e.getMessage());
        }
    }

    /**
     * Lists all active hooks.
     *
     * @return Collection of hook info for all active hooks
     */
    public Collection<HookInfo> listHooks() {
        return activeHooks.values().stream()
                .map(ManagedHook::toHookInfo)
                .toList();
    }

    /**
     * Gets a specific hook by ID.
     *
     * @param hookId The hook ID
     * @return The hook info, or null if not found
     */
    public HookInfo getHook(String hookId) {
        ManagedHook hook = activeHooks.get(hookId);
        return hook != null ? hook.toHookInfo() : null;
    }

    /**
     * Increments the hit count for a hook.
     * Called by TraceInterceptor when the hooked method is invoked.
     */
    public void recordHit(String hookId) {
        ManagedHook hook = activeHooks.get(hookId);
        if (hook != null) {
            hook.hitCount.incrementAndGet();
        }
    }

    /**
     * Force retransformation of a class if it's already loaded.
     */
    private void retransformClass(String className) {
        logger.info("Looking for class '{}' in {} loaded classes", className, instrumentation.getAllLoadedClasses().length);
        boolean found = false;
        
        for (Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
            if (loadedClass.getName().equals(className)) {
                found = true;
                logger.info("Found class '{}', isModifiable={}", className, instrumentation.isModifiableClass(loadedClass));
                
                if (instrumentation.isModifiableClass(loadedClass)) {
                    try {
                        logger.info("Retransforming class: {}", className);
                        instrumentation.retransformClasses(loadedClass);
                        logger.info("Successfully retransformed class: {}", className);
                    } catch (Exception e) {
                        logger.error("Failed to retransform class {}: {}", className, e.getMessage(), e);
                        System.err.println("[Bytesight] Failed to retransform " + className + ": " + e.getMessage());
                        e.printStackTrace();
                    }
                } else {
                    logger.warn("Class {} is not modifiable", className);
                    System.out.println("[Bytesight] WARNING: Class " + className + " is not modifiable");
                }
                break;
            }
        }
        
        if (!found) {
            logger.info("Class '{}' not yet loaded - hook will apply when class is loaded", className);
            System.out.println("[Bytesight] Class '" + className + "' not yet loaded - hook will apply when loaded");
        }
    }

    /**
     * Internal representation of an active hook.
     */
    private static class ManagedHook {
        final String id;
        final String className;
        final String methodName;
        final String methodSignature;
        final HookType hookType;
        final ResettableClassFileTransformer transformer;
        final long createdAt;
        final AtomicLong hitCount = new AtomicLong(0);

        ManagedHook(String id, String className, String methodName, String methodSignature,
                    HookType hookType, ResettableClassFileTransformer transformer, long createdAt) {
            this.id = id;
            this.className = className;
            this.methodName = methodName;
            this.methodSignature = methodSignature;
            this.hookType = hookType;
            this.transformer = transformer;
            this.createdAt = createdAt;
        }

        HookInfo toHookInfo() {
            HookInfo.Builder builder = HookInfo.newBuilder()
                    .setId(id)
                    .setClassName(className)
                    .setMethodName(methodName)
                    .setHookType(hookType)
                    .setActive(true)
                    .setHitCount(hitCount.get())
                    .setCreatedAt(createdAt);

            if (methodSignature != null) {
                builder.setMethodSignature(methodSignature);
            }

            return builder.build();
        }
    }

    /**
     * Result of a hook operation.
     */
    public static class HookResult {
        private final boolean success;
        private final String hookId;
        private final String error;

        private HookResult(boolean success, String hookId, String error) {
            this.success = success;
            this.hookId = hookId;
            this.error = error;
        }

        public static HookResult success(String hookId) {
            return new HookResult(true, hookId, null);
        }

        public static HookResult failure(String error) {
            return new HookResult(false, null, error);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getHookId() {
            return hookId;
        }

        public String getError() {
            return error;
        }
    }

    /**
     * Listener for ByteBuddy agent events for debugging.
     */
    private static class LoggingListener implements AgentBuilder.Listener {
        private final String hookId;

        LoggingListener(String hookId) {
            this.hookId = hookId;
        }

        @Override
        public void onDiscovery(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
            // Log discovery for our target class pattern
            logger.trace("Hook '{}': Discovered {} (loaded={})", hookId, typeName, loaded);
        }

        @Override
        public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader,
                                     JavaModule module, boolean loaded, DynamicType dynamicType) {
            logger.info("Hook '{}': Successfully transformed {} (loaded={}, classLoader={})", 
                    hookId, typeDescription.getName(), loaded, classLoader);
            System.out.println("[Bytesight] Hook '" + hookId + "': Successfully transformed " + 
                    typeDescription.getName() + " (loaded=" + loaded + ")");
        }

        @Override
        public void onIgnored(TypeDescription typeDescription, ClassLoader classLoader,
                              JavaModule module, boolean loaded) {
            // Expected for non-matching types - only log at trace level
            logger.trace("Hook '{}': Ignored {} (loaded={})", hookId, typeDescription.getName(), loaded);
        }

        @Override
        public void onError(String typeName, ClassLoader classLoader, JavaModule module,
                            boolean loaded, Throwable throwable) {
            logger.error("Hook '{}': Error transforming {}: {}", hookId, typeName, throwable.getMessage(), throwable);
            System.err.println("[Bytesight] Hook '" + hookId + "': ERROR transforming " + typeName + ": " + throwable.getMessage());
            throwable.printStackTrace();
        }

        @Override
        public void onComplete(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
            logger.trace("Hook '{}': Completed processing {}", hookId, typeName);
        }
    }
}
