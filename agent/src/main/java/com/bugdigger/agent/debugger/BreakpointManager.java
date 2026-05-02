package com.bugdigger.agent.debugger;

import com.bugdigger.agent.debugger.condition.ConditionEvaluator;
import com.bugdigger.protocol.Breakpoint;
import com.bugdigger.protocol.LineLocation;
import com.bugdigger.protocol.MethodBreakpointMode;
import com.bugdigger.protocol.MethodLocation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.JavaModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Installs and removes debugger breakpoints via ByteBuddy retransformation.
 *
 * <p>Two breakpoint kinds are supported:
 * <ul>
 *   <li><b>Method breakpoints</b> — fire on entry/exit/both via
 *       {@link Advice}-based {@link BreakpointInterceptor#onEnter} /
 *       {@link BreakpointInterceptor#onExit onExit}. Multiple bps on the same
 *       method share one transformer.</li>
 *   <li><b>Line breakpoints</b> — fire when execution reaches a specific source
 *       line, via an ASM-injected {@code INVOKESTATIC} call to
 *       {@link BreakpointInterceptor#onLineHit}. All line bps in a class share
 *       one transformer; the {@link LineProbeMethodVisitor} injects probes for
 *       every active line in every method of that class.</li>
 * </ul>
 *
 * <p>A class can have both kinds active simultaneously — the two transformers
 * compose because they touch disjoint code locations (method entry/exit
 * boundaries vs. mid-method line offsets).
 */
public class BreakpointManager {
    private static final Logger logger = LoggerFactory.getLogger(BreakpointManager.class);

    private static volatile BreakpointManager instance;

    private final Instrumentation instrumentation;
    private final Map<String, ManagedBreakpoint> breakpoints = new ConcurrentHashMap<>();

    // Method-bp indexes — keyed by methodKey (className#methodName#methodSignature)
    private final Map<String, Set<String>> entryIndex = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> exitIndex = new ConcurrentHashMap<>();
    private final Map<String, InstalledMethodTransformer> installedMethod = new ConcurrentHashMap<>();

    // Line-bp index — className -> (line -> bpIds). The inner map's key set is
    // what LineProbeMethodVisitor consults at install time to decide which
    // visitLineNumber events to probe.
    private final Map<String, ConcurrentMap<Integer, Set<String>>> lineIndex = new ConcurrentHashMap<>();
    private final Map<String, InstalledLineTransformer> installedLine = new ConcurrentHashMap<>();

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

        Target target;
        try {
            target = resolveTarget(bp);
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }

        ManagedBreakpoint managed = new ManagedBreakpoint(bp.getId(), bp, target);
        breakpoints.put(managed.id, managed);

        try {
            if (target instanceof MethodTarget) {
                installMethodBreakpoint(managed, (MethodTarget) target);
            } else if (target instanceof LineTarget) {
                installLineBreakpoint(managed, (LineTarget) target);
            } else {
                throw new IllegalStateException("Unknown target type: " + target);
            }
        } catch (Exception e) {
            logger.error("Failed to install breakpoint '{}'", managed.id, e);
            breakpoints.remove(managed.id);
            return Result.failure("Failed to install breakpoint: " + e.getMessage());
        }

        return Result.success(managed.id);
    }

    public Result remove(String bpId) {
        ManagedBreakpoint managed = breakpoints.remove(bpId);
        if (managed == null) {
            return Result.failure("Breakpoint '" + bpId + "' not found");
        }

        if (managed.target instanceof MethodTarget) {
            removeMethodBreakpoint(managed, (MethodTarget) managed.target);
        } else if (managed.target instanceof LineTarget) {
            removeLineBreakpoint(managed, (LineTarget) managed.target);
        }

        logger.info("Breakpoint '{}' removed", bpId);
        return Result.success(bpId);
    }

    public Collection<Breakpoint> list() {
        List<Breakpoint> out = new ArrayList<>(breakpoints.size());
        for (ManagedBreakpoint m : breakpoints.values()) {
            out.add(snapshotProto(m));
        }
        return out;
    }

    /**
     * Updates the condition / skip_count / enabled flag of an existing bp
     * without remove + reinstall. Location and id stay the same. Other fields
     * on the supplied {@link Breakpoint} are ignored.
     */
    public Result update(Breakpoint updated) {
        ManagedBreakpoint existing = breakpoints.get(updated.getId());
        if (existing == null) return Result.failure("Breakpoint '" + updated.getId() + "' not found");

        // Build a new proto preserving the original location, applying the new mutable fields.
        Breakpoint.Builder b = existing.proto.toBuilder()
                .setEnabled(updated.getEnabled())
                .setCondition(updated.getCondition() == null ? "" : updated.getCondition())
                .setSkipCount(Math.max(0, updated.getSkipCount()));
        existing.proto = b.build();
        return Result.success(existing.id);
    }

    /**
     * Called from {@link BreakpointInterceptor} on every advice/probe firing
     * that resolved to a bp. Increments the hit counter, evaluates any
     * condition against ({@code self}, {@code arguments}), and returns the
     * outcome. The interceptor uses the returned {@link HitDecision} to decide
     * whether to actually emit + park.
     *
     * <p>For line bps, {@code self}/{@code arguments}/{@code method} are null —
     * conditions referencing those identifiers will hit the unknown-identifier
     * path and fail open (suspend with a warning).
     */
    public HitDecision recordAndEvaluate(String bpId, Object self, Object[] arguments, Method method) {
        ManagedBreakpoint bp = breakpoints.get(bpId);
        if (bp == null) return HitDecision.suspend(null);
        int newHits = bp.hits.incrementAndGet();
        // Mirror the live count back into the proto so subsequent list() / update()
        // round-trips reflect it. toBuilder() is cheap and hits is a plain int.
        bp.proto = bp.proto.toBuilder().setHitCount(newHits).build();

        int skip = bp.proto.getSkipCount();
        if (skip > 0 && newHits <= skip) {
            return HitDecision.skip();
        }
        String cond = bp.proto.getCondition();
        if (cond == null || cond.isEmpty()) {
            return HitDecision.suspend(null);
        }
        ConditionEvaluator.Context ctx = new ConditionEvaluator.Context(self, arguments, method);
        ConditionEvaluator.Result r = ConditionEvaluator.evaluate(cond, ctx);
        if (!r.shouldFire()) return HitDecision.skip();
        return HitDecision.suspend(r.hasError() ? r.error() : null);
    }

    /** Snapshot the proto with the live hit count for list/get returns. */
    private static Breakpoint snapshotProto(ManagedBreakpoint m) {
        return m.proto.toBuilder().setHitCount(m.hits.get()).build();
    }

    /** Outcome of {@link #recordAndEvaluate}. */
    public static final class HitDecision {
        public final boolean shouldSuspend;
        public final String warning;  // non-null when condition had an error and we're failing open
        private HitDecision(boolean shouldSuspend, String warning) {
            this.shouldSuspend = shouldSuspend;
            this.warning = warning;
        }
        public static HitDecision suspend(String warning) { return new HitDecision(true, warning); }
        public static HitDecision skip() { return new HitDecision(false, null); }
    }

    // ===== Method breakpoints =================================================

    private void installMethodBreakpoint(ManagedBreakpoint managed, MethodTarget target) {
        String methodKey = methodKey(target.className, target.methodName, target.methodSignature);
        managed.methodKey = methodKey;

        if (target.hitsEntry()) {
            entryIndex.computeIfAbsent(methodKey, k -> new CopyOnWriteArraySet<>()).add(managed.id);
        }
        if (target.hitsExit()) {
            exitIndex.computeIfAbsent(methodKey, k -> new CopyOnWriteArraySet<>()).add(managed.id);
        }

        ensureMethodInstalled(target);
        logger.info("Method breakpoint '{}' installed on {}#{}{} mode={}",
                managed.id, target.className, target.methodName,
                target.methodSignature.isEmpty() ? "(*)" : target.methodSignature, target.mode);
    }

    private void removeMethodBreakpoint(ManagedBreakpoint managed, MethodTarget target) {
        String methodKey = managed.methodKey;
        Set<String> entry = entryIndex.get(methodKey);
        if (entry != null) {
            entry.remove(managed.id);
            if (entry.isEmpty()) entryIndex.remove(methodKey);
        }
        Set<String> exit = exitIndex.get(methodKey);
        if (exit != null) {
            exit.remove(managed.id);
            if (exit.isEmpty()) exitIndex.remove(methodKey);
        }

        InstalledMethodTransformer inst = installedMethod.get(methodKey);
        if (inst != null) {
            inst.refCount--;
            if (inst.refCount <= 0) {
                installedMethod.remove(methodKey);
                try {
                    inst.transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
                    retransformClass(target.className);
                } catch (Exception e) {
                    logger.warn("Failed to reset method transformer for {}", methodKey, e);
                }
            }
        }
    }

    private void ensureMethodInstalled(MethodTarget target) {
        String key = methodKey(target.className, target.methodName, target.methodSignature);
        InstalledMethodTransformer existing = installedMethod.get(key);
        if (existing != null) {
            existing.refCount++;
            return;
        }

        ElementMatcher.Junction<MethodDescription> methodMatcher = ElementMatchers.named(target.methodName);
        if (!target.methodSignature.isEmpty()) {
            methodMatcher = methodMatcher.and(ElementMatchers.hasDescriptor(target.methodSignature));
        }
        final ElementMatcher.Junction<MethodDescription> finalMatcher = methodMatcher;

        logger.info("Installing method bp transformer for {}#{}{}",
                target.className, target.methodName,
                target.methodSignature.isEmpty() ? "(*)" : target.methodSignature);

        ResettableClassFileTransformer transformer = new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new LoggingListener("method:" + target.className + "#" + target.methodName))
                .type(ElementMatchers.named(target.className))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(BreakpointInterceptor.class).on(finalMatcher)))
                .installOn(instrumentation);

        installedMethod.put(key, new InstalledMethodTransformer(transformer, target, 1));
        retransformClass(target.className);
    }

    // ===== Line breakpoints ===================================================

    private void installLineBreakpoint(ManagedBreakpoint managed, LineTarget target) {
        ConcurrentMap<Integer, Set<String>> classLines = lineIndex.computeIfAbsent(
                target.className, k -> new ConcurrentHashMap<>());
        classLines.computeIfAbsent(target.line, k -> new CopyOnWriteArraySet<>()).add(managed.id);

        ensureLineInstalled(target);
        logger.info("Line breakpoint '{}' installed on {}:{}", managed.id, target.className, target.line);
    }

    private void removeLineBreakpoint(ManagedBreakpoint managed, LineTarget target) {
        ConcurrentMap<Integer, Set<String>> classLines = lineIndex.get(target.className);
        if (classLines != null) {
            Set<String> bpsAtLine = classLines.get(target.line);
            if (bpsAtLine != null) {
                bpsAtLine.remove(managed.id);
                if (bpsAtLine.isEmpty()) classLines.remove(target.line);
            }
            if (classLines.isEmpty()) lineIndex.remove(target.className);
        }

        InstalledLineTransformer inst = installedLine.get(target.className);
        if (inst != null) {
            inst.refCount--;
            if (inst.refCount <= 0) {
                installedLine.remove(target.className);
                try {
                    inst.transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
                    retransformClass(target.className);
                } catch (Exception e) {
                    logger.warn("Failed to reset line transformer for {}", target.className, e);
                }
            }
        }
    }

    private void ensureLineInstalled(LineTarget target) {
        InstalledLineTransformer existing = installedLine.get(target.className);
        if (existing != null) {
            existing.refCount++;
            // No retransform needed: probes are pre-injected on every line
            // when the first bp installs the transformer, so a new line bp is
            // just an index update — in-progress frames see it immediately.
            return;
        }

        logger.info("Installing line bp transformer for {}", target.className);

        final String className = target.className;
        ResettableClassFileTransformer transformer = new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new LoggingListener("line:" + className))
                .type(ElementMatchers.named(className))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(new LineProbeAsmWrapper(className)))
                .installOn(instrumentation);

        installedLine.put(className, new InstalledLineTransformer(transformer, className, 1));
        retransformClass(className);
    }

    // ===== Lookup APIs called from advice / injected probes ===================

    /** Called from advice at method entry. Returns the id of the enabled bp that should fire, or null. */
    public String findEntryBreakpoint(String className, String methodName, String methodSignature) {
        return firstEnabledInIndex(entryIndex, className, methodName, methodSignature);
    }

    /** Called from advice at method exit. */
    public String findExitBreakpoint(String className, String methodName, String methodSignature) {
        return firstEnabledInIndex(exitIndex, className, methodName, methodSignature);
    }

    /** Called from the ASM-injected probe at the start of a source line. */
    public String findLineBreakpoint(String className, int line) {
        ConcurrentMap<Integer, Set<String>> classLines = lineIndex.get(className);
        if (classLines == null) return null;
        Set<String> bpIds = classLines.get(line);
        if (bpIds == null || bpIds.isEmpty()) return null;
        for (String id : bpIds) {
            ManagedBreakpoint bp = breakpoints.get(id);
            if (bp != null && bp.proto.getEnabled()) return id;
        }
        return null;
    }

    /** Returns the set of source lines with active bps for a class. Used by LineProbeMethodVisitor. */
    Set<Integer> activeLinesFor(String className) {
        ConcurrentMap<Integer, Set<String>> classLines = lineIndex.get(className);
        return classLines == null ? Collections.emptySet() : classLines.keySet();
    }

    private String firstEnabledInIndex(Map<String, Set<String>> index,
                                       String className, String methodName, String methodSignature) {
        Set<String> candidates = index.get(methodKey(className, methodName, methodSignature));
        if (candidates == null) {
            candidates = index.get(methodKey(className, methodName, ""));
        }
        if (candidates == null) {
            String prefix = className + "#" + methodName + "#";
            for (Map.Entry<String, Set<String>> e : index.entrySet()) {
                if (e.getKey().startsWith(prefix)) {
                    candidates = e.getValue();
                    break;
                }
            }
        }
        if (candidates == null || candidates.isEmpty()) return null;
        for (String id : candidates) {
            ManagedBreakpoint bp = breakpoints.get(id);
            if (bp != null && bp.proto.getEnabled()) return id;
        }
        return null;
    }

    // ===== Helpers ============================================================

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

    private Target resolveTarget(Breakpoint bp) {
        switch (bp.getLocationCase()) {
            case METHOD: {
                MethodLocation m = bp.getMethod();
                if (m.getClassName().isEmpty() || m.getMethodName().isEmpty()) {
                    throw new IllegalArgumentException("MethodLocation requires class_name and method_name");
                }
                return new MethodTarget(m.getClassName(), m.getMethodName(),
                        m.getMethodSignature() == null ? "" : m.getMethodSignature(),
                        m.getMode() == null ? MethodBreakpointMode.METHOD_BP_ENTRY : m.getMode());
            }
            case LINE: {
                LineLocation l = bp.getLine();
                if (l.getClassName().isEmpty()) {
                    throw new IllegalArgumentException("LineLocation requires class_name");
                }
                if (l.getLineNumber() <= 0) {
                    throw new IllegalArgumentException("LineLocation.line_number must be > 0");
                }
                return new LineTarget(l.getClassName(), l.getLineNumber());
            }
            case LOCATION_NOT_SET:
            default:
                throw new IllegalArgumentException("Breakpoint has no location set");
        }
    }

    private static String methodKey(String className, String methodName, String methodSignature) {
        return className + "#" + methodName + "#" + (methodSignature == null ? "" : methodSignature);
    }

    // ===== Target types =======================================================

    private interface Target {
        String className();
    }

    private static final class MethodTarget implements Target {
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

        @Override public String className() { return className; }
        boolean hitsEntry() { return mode == MethodBreakpointMode.METHOD_BP_ENTRY || mode == MethodBreakpointMode.METHOD_BP_BOTH; }
        boolean hitsExit() { return mode == MethodBreakpointMode.METHOD_BP_EXIT || mode == MethodBreakpointMode.METHOD_BP_BOTH; }
    }

    private static final class LineTarget implements Target {
        final String className;
        final int line;

        LineTarget(String className, int line) {
            this.className = className;
            this.line = line;
        }

        @Override public String className() { return className; }
    }

    // ===== State holders ======================================================

    private static final class ManagedBreakpoint {
        final String id;
        // Mutable so update() and recordAndEvaluate() can refresh the
        // condition / skip_count / hit_count fields without rebuilding state.
        volatile Breakpoint proto;
        final Target target;
        final AtomicInteger hits = new AtomicInteger(0);
        String methodKey;  // populated only for MethodTarget bps

        ManagedBreakpoint(String id, Breakpoint proto, Target target) {
            this.id = id;
            this.proto = proto;
            this.target = target;
        }
    }

    private static final class InstalledMethodTransformer {
        final ResettableClassFileTransformer transformer;
        final MethodTarget target;
        int refCount;

        InstalledMethodTransformer(ResettableClassFileTransformer transformer, MethodTarget target, int refCount) {
            this.transformer = transformer;
            this.target = target;
            this.refCount = refCount;
        }
    }

    private static final class InstalledLineTransformer {
        final ResettableClassFileTransformer transformer;
        final String className;
        int refCount;

        InstalledLineTransformer(ResettableClassFileTransformer transformer, String className, int refCount) {
            this.transformer = transformer;
            this.className = className;
            this.refCount = refCount;
        }
    }

    // ===== Result =============================================================

    public static final class Result {
        private final boolean success;
        private final String breakpointId;
        private final String error;

        private Result(boolean success, String breakpointId, String error) {
            this.success = success;
            this.breakpointId = breakpointId;
            this.error = error;
        }

        public static Result success(String id) { return new Result(true, id, null); }
        public static Result failure(String err) { return new Result(false, null, err); }

        public boolean isSuccess() { return success; }
        public String getBreakpointId() { return breakpointId; }
        public String getError() { return error; }
    }

    // ===== ASM wrapper that produces LineProbeMethodVisitor ===================

    private static final class LineProbeAsmWrapper implements AsmVisitorWrapper {
        private final String classNameJls;

        LineProbeAsmWrapper(String classNameJls) {
            this.classNameJls = classNameJls;
        }

        @Override public int mergeWriter(int flags) { return flags; }
        @Override public int mergeReader(int flags) { return flags; }

        @Override
        public ClassVisitor wrap(TypeDescription instrumentedType,
                                 ClassVisitor classVisitor,
                                 Implementation.Context implementationContext,
                                 TypePool typePool,
                                 net.bytebuddy.description.field.FieldList<net.bytebuddy.description.field.FieldDescription.InDefinedShape> fields,
                                 net.bytebuddy.description.method.MethodList<?> methods,
                                 int writerFlags,
                                 int readerFlags) {
            return new ClassVisitor(Opcodes.ASM9, classVisitor) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (mv == null) return null;
                    return new LineProbeMethodVisitor(
                            Opcodes.ASM9, mv, classNameJls, name, descriptor);
                }
            };
        }
    }

    // ===== Logging listener ===================================================

    private static final class LoggingListener implements AgentBuilder.Listener {
        private final String tag;
        LoggingListener(String tag) { this.tag = tag; }

        @Override public void onDiscovery(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {}

        @Override
        public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader,
                                     JavaModule module, boolean loaded, DynamicType dynamicType) {
            logger.info("BP[{}] transformed {} (loaded={})", tag, typeDescription.getName(), loaded);
        }

        @Override public void onIgnored(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module, boolean loaded) {}

        @Override
        public void onError(String typeName, ClassLoader classLoader, JavaModule module,
                            boolean loaded, Throwable throwable) {
            logger.error("BP[{}] error transforming {}: {}", tag, typeName, throwable.getMessage(), throwable);
        }

        @Override public void onComplete(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {}
    }
}
