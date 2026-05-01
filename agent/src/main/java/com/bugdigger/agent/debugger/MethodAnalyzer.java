package com.bugdigger.agent.debugger;

import com.bugdigger.agent.collector.ClassCollector;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ASM-based analyzer that extracts the source-line set and the static
 * {@code INVOKE*} call targets of a single method. Used by
 * {@link StepController} to plan the transient breakpoints needed to
 * implement Step Over / Step Into.
 *
 * <p>Reads class bytes from {@link ClassCollector}, which has already cached
 * them at class-load time.
 */
final class MethodAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(MethodAnalyzer.class);

    private MethodAnalyzer() {}

    static final class MethodInfo {
        final Set<Integer> lines = new LinkedHashSet<>();
        final List<CalleeRef> callees = new ArrayList<>();
        boolean found = false;
    }

    static final class CalleeRef {
        final String ownerClassNameJls;  // "java.lang.String"
        final String methodName;
        final String descriptor;

        CalleeRef(String ownerClassNameJls, String methodName, String descriptor) {
            this.ownerClassNameJls = ownerClassNameJls;
            this.methodName = methodName;
            this.descriptor = descriptor;
        }
    }

    /**
     * Analyzes one method of a class. {@code classNameJls} is the dotted name
     * (e.g. {@code com.example.Foo}); {@code methodName} is the internal name
     * ({@code <init>} for constructors); {@code methodDescriptor} is the JVM
     * descriptor (e.g. {@code (I)V}) — empty matches any overload (first one wins).
     * Returns {@code MethodInfo.found = false} if the class bytes aren't available
     * via {@link ClassCollector} or the method isn't found.
     */
    static MethodInfo analyze(ClassCollector collector,
                              String classNameJls,
                              String methodName,
                              String methodDescriptor) {
        MethodInfo info = new MethodInfo();
        if (collector == null) return info;

        byte[] bytes = collector.getBytecode(classNameJls);
        if (bytes == null) {
            logger.debug("MethodAnalyzer: no bytes for {} in ClassCollector", classNameJls);
            return info;
        }

        // Default reader flags = 0, which means line tables and local-variable
        // tables are visited. We need the line table for stepping.
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (info.found) return null;  // already matched a candidate
                if (!name.equals(methodName)) return null;
                if (methodDescriptor != null && !methodDescriptor.isEmpty()
                        && !descriptor.equals(methodDescriptor)) return null;
                info.found = true;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitLineNumber(int line, Label start) {
                        info.lines.add(line);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mName,
                                                String mDesc, boolean isInterface) {
                        info.callees.add(new CalleeRef(
                                owner.replace('/', '.'), mName, mDesc));
                    }
                };
            }
        }, 0);
        return info;
    }
}
