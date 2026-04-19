package com.bugdigger.agent.debugger;

import com.bugdigger.protocol.Breakpoint;
import com.bugdigger.protocol.LineLocation;
import com.bugdigger.protocol.MethodBreakpointMode;
import com.bugdigger.protocol.MethodLocation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Installs and removes debugger breakpoints via ByteBuddy retransformation.
 * <p>
 * Multiple breakpoints can share a single installed transformer when they target the
 * same method. We track entry/exit indices so {@link BreakpointInterceptor}'s advice
 * can look up the active breakpoint id for the class+method it is running inside.
 * <p>
 * v1 supports {@link MethodLocation} only. {@link LineLocation} must be pre-resolved
 * to a method by the client before being sent; the agent rejects raw LineLocation
 * requests to keep the agent free of ASM dependencies.
 */
public class BreakpointManager {
    private static final Logger logger = LoggerFactory.getLogger(BreakpointManager.class);

    private static volatile BreakpointManager instance;

    private final Instrumentation instrumentation;
    private final Map<String, ManagedBreakpoint> breakpoints = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> entryIndex = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> exitIndex = new ConcurrentHashMap<>();
    private final Map<String, InstalledTransformer> installed = new ConcurrentHashMap<>();

    public BreakpointManager(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        instance = this;
        logger.info("BreakpointManager initialized");
    }

    public static BreakpointManager getInstance() {
        return instance;
    }

    public Result install(Breakpoint bp) {
        if (bp.getId() == null || bp.getId().isEmpty()) {
            return Result.failure("Breakpoint id is required");
        }
        if (breakpoints.containsKey(bp.getId())) {
            return Result.failure("Breakpoint '" + bp.getId() + "' already exists");
        }

        MethodTarget target;
        try {
            target = resolveTarget(bp);
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }

        String methodKey = methodKey(target.className, target.methodName, target.methodSignature);
        ManagedBreakpoint managed = new ManagedBreakpoint(bp.getId(), bp, target, methodKey);
        breakpoints.put(managed.id, managed);

        if (target.hitsEntry()) {
            entryIndex.computeIfAbsent(methodKey, k -> new CopyOnWriteArraySet<>()).add(managed.id);
        }
        if (target.hitsExit()) {
            exitIndex.computeIfAbsent(methodKey, k -> new CopyOnWriteArraySet<>()).add(managed.id);
        }

        try {
            ensureInstalled(target);
        } catch (Exception e) {
            logger.error("Failed to install breakpoint '{}'", managed.id, e);
            rollback(managed);
            return Result.failure("Failed to install breakpoint: " + e.getMessage());
        }

        logger.info("Breakpoint '{}' installed on {}#{}{} mode={}",
                managed.id, target.className, target.methodName,
                target.methodSignature.isEmpty() ? "(*)" : target.methodSignature, target.mode);
        return Result.success(managed.id);
    }

    public Result remove(String bpId) {
        ManagedBreakpoint managed = breakpoints.remove(bpId);
        if (managed == null) {
            return Result.failure("Breakpoint '" + bpId + "' not found");
        }

        Set<String> entry = entryIndex.get(managed.methodKey);
        if (entry != null) {
            entry.remove(bpId);
            if (entry.isEmpty()) entryIndex.remove(managed.methodKey);
        }
        Set<String> exit = exitIndex.get(managed.methodKey);
        if (exit != null) {
            exit.remove(bpId);
            if (exit.isEmpty()) exitIndex.remove(managed.methodKey);
        }

        InstalledTransformer inst = installed.get(managed.methodKey);
        if (inst != null) {
            inst.refCount--;
            if (inst.refCount <= 0) {
                installed.remove(managed.methodKey);
                try {
                    inst.transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
                    retransformClass(inst.target.className);
                } catch (Exception e) {
                    logger.warn("Failed to reset transformer for {}", managed.methodKey, e);
                }
            }
        }

        logger.info("Breakpoint '{}' removed", bpId);
        return Result.success(bpId);
    }

    public Collection<Breakpoint> list() {
        List<Breakpoint> out = new ArrayList<>(breakpoints.size());
        for (ManagedBreakpoint m : breakpoints.values()) {
            out.add(m.proto);
        }
        return out;
    }

    /** Called from advice at method entry. Returns the id of the enabled breakpoint that should fire, or null. */
    public String findEntryBreakpoint(String className, String methodName, String methodSignature) {
        return firstEnabledInIndex(entryIndex, className, methodName, methodSignature);
    }

    /** Called from advice at method exit. */
    public String findExitBreakpoint(String className, String methodName, String methodSignature) {
        return firstEnabledInIndex(exitIndex, className, methodName, methodSignature);
    }

    private String firstEnabledInIndex(Map<String, Set<String>> index,
                                       String className, String methodName, String methodSignature) {
        Set<String> candidates = index.get(methodKey(className, methodName, methodSignature));
        if (candidates == null) {
            // wildcard: breakpoint with empty signature matches any overload
            candidates = index.get(methodKey(className, methodName, ""));
        }
        if (candidates == null || candidates.isEmpty()) return null;
        for (String id : candidates) {
            ManagedBreakpoint bp = breakpoints.get(id);
            if (bp != null && bp.proto.getEnabled()) return id;
        }
        return null;
    }

    private void ensureInstalled(MethodTarget target) {
        String key = methodKey(target.className, target.methodName, target.methodSignature);
        InstalledTransformer existing = installed.get(key);
        if (existing != null) {
            existing.refCount++;
            return;
        }

        ElementMatcher.Junction<MethodDescription> methodMatcher = ElementMatchers.named(target.methodName);
        if (!target.methodSignature.isEmpty()) {
            methodMatcher = methodMatcher.and(ElementMatchers.hasDescriptor(target.methodSignature));
        }
        final ElementMatcher.Junction<MethodDescription> finalMatcher = methodMatcher;

        logger.info("Installing breakpoint transformer for {}#{}{}",
                target.className, target.methodName,
                target.methodSignature.isEmpty() ? "(*)" : target.methodSignature);

        ResettableClassFileTransformer transformer = new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(ElementMatchers.named(target.className))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(BreakpointInterceptor.class).on(finalMatcher)))
                .installOn(instrumentation);

        installed.put(key, new InstalledTransformer(transformer, target, 1));
        retransformClass(target.className);
    }

    private void rollback(ManagedBreakpoint managed) {
        breakpoints.remove(managed.id);
        Set<String> e = entryIndex.get(managed.methodKey);
        if (e != null) {
            e.remove(managed.id);
            if (e.isEmpty()) entryIndex.remove(managed.methodKey);
        }
        Set<String> x = exitIndex.get(managed.methodKey);
        if (x != null) {
            x.remove(managed.id);
            if (x.isEmpty()) exitIndex.remove(managed.methodKey);
        }
    }

    private void retransformClass(String className) {
        for (Class<?> c : instrumentation.getAllLoadedClasses()) {
            if (!c.getName().equals(className)) continue;
            if (!instrumentation.isModifiableClass(c)) {
                logger.warn("Class {} is not modifiable", className);
                return;
            }
            try {
                instrumentation.retransformClasses(c);
                logger.info("Retransformed class {}", className);
            } catch (Throwable t) {
                logger.error("Failed to retransform {}", className, t);
            }
            return;
        }
        logger.info("Class {} not yet loaded - breakpoint will apply on load", className);
    }

    private MethodTarget resolveTarget(Breakpoint bp) {
        switch (bp.getLocationCase()) {
            case METHOD:
                MethodLocation m = bp.getMethod();
                if (m.getClassName().isEmpty() || m.getMethodName().isEmpty()) {
                    throw new IllegalArgumentException("MethodLocation requires class_name and method_name");
                }
                return new MethodTarget(m.getClassName(), m.getMethodName(),
                        m.getMethodSignature() == null ? "" : m.getMethodSignature(),
                        m.getMode() == null ? MethodBreakpointMode.METHOD_BP_ENTRY : m.getMode());
            case LINE:
                throw new IllegalArgumentException(
                        "LineLocation not supported by agent in v1 - resolve to MethodLocation client-side");
            case LOCATION_NOT_SET:
            default:
                throw new IllegalArgumentException("Breakpoint has no location set");
        }
    }

    private static String methodKey(String className, String methodName, String methodSignature) {
        return className + "#" + methodName + "#" + (methodSignature == null ? "" : methodSignature);
    }

    private static final class MethodTarget {
        final String className;
        final String methodName;
        final String methodSignature;
        final MethodBreakpointMode mode;

        MethodTarget(String className, String methodName, String methodSignature, MethodBreakpointMode mode) {
            this.className = className;
            this.methodName = methodName;
            this.methodSignature = methodSignature;
            this.mode = mode;
        }

        boolean hitsEntry() {
            return mode == MethodBreakpointMode.METHOD_BP_ENTRY || mode == MethodBreakpointMode.METHOD_BP_BOTH;
        }

        boolean hitsExit() {
            return mode == MethodBreakpointMode.METHOD_BP_EXIT || mode == MethodBreakpointMode.METHOD_BP_BOTH;
        }
    }

    private static final class ManagedBreakpoint {
        final String id;
        final Breakpoint proto;
        final MethodTarget target;
        final String methodKey;

        ManagedBreakpoint(String id, Breakpoint proto, MethodTarget target, String methodKey) {
            this.id = id;
            this.proto = proto;
            this.target = target;
            this.methodKey = methodKey;
        }
    }

    private static final class InstalledTransformer {
        final ResettableClassFileTransformer transformer;
        final MethodTarget target;
        int refCount;

        InstalledTransformer(ResettableClassFileTransformer transformer, MethodTarget target, int refCount) {
            this.transformer = transformer;
            this.target = target;
            this.refCount = refCount;
        }
    }

    public static final class Result {
        private final boolean success;
        private final String breakpointId;
        private final String error;

        private Result(boolean success, String breakpointId, String error) {
            this.success = success;
            this.breakpointId = breakpointId;
            this.error = error;
        }

        public static Result success(String id) {
            return new Result(true, id, null);
        }

        public static Result failure(String err) {
            return new Result(false, null, err);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getBreakpointId() {
            return breakpointId;
        }

        public String getError() {
            return error;
        }
    }
}
