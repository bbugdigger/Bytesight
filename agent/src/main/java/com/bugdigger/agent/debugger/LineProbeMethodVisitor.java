package com.bugdigger.agent.debugger;

import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.util.Set;

/**
 * ASM {@link MethodVisitor} that injects an {@code INVOKESTATIC} call to
 * {@link BreakpointInterceptor#onLineHit(String, String, String, int)} at the
 * start of each source line that has an active line breakpoint.
 *
 * <p>For each line emitted via {@code visitLineNumber(line, label)}, if the
 * line is in {@code activeLines} we set a "next instruction needs a probe"
 * flag and emit the probe immediately before the next bytecode instruction
 * the visitor sees. This places the probe at the bytecode offset of the line's
 * first instruction — equivalent to what JVMTI {@code SetBreakpoint} on a
 * {@code (jmethodID, jlocation)} would do.
 *
 * <p>The same source line can appear multiple times in {@code LineNumberTable}
 * (e.g. iterations of a loop body, conditional branches that re-enter the same
 * line). We probe at every occurrence — the breakpoint fires whenever execution
 * reaches that line, regardless of which basic block.
 */
final class LineProbeMethodVisitor extends MethodVisitor {

    private static final String INTERCEPTOR_INTERNAL =
            "com/bugdigger/agent/debugger/BreakpointInterceptor";
    private static final String ON_LINE_HIT_DESCRIPTOR =
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V";

    private final String classNameJls;       // "com.example.Foo"
    private final String methodInternalName; // "bar" or "<init>"
    private final String methodDescriptor;   // "(I)V"
    private final Set<Integer> activeLines;
    private int pendingLine = -1;

    LineProbeMethodVisitor(int api,
                           MethodVisitor mv,
                           String classNameJls,
                           String methodInternalName,
                           String methodDescriptor,
                           Set<Integer> activeLines) {
        super(api, mv);
        this.classNameJls = classNameJls;
        this.methodInternalName = methodInternalName;
        this.methodDescriptor = methodDescriptor;
        this.activeLines = activeLines;
    }

    @Override
    public void visitLineNumber(int line, Label start) {
        super.visitLineNumber(line, start);
        if (activeLines.contains(line)) {
            pendingLine = line;
        }
    }

    private void emitProbeIfPending() {
        if (pendingLine < 0) return;
        int line = pendingLine;
        pendingLine = -1;
        super.visitLdcInsn(classNameJls);
        super.visitLdcInsn(methodInternalName);
        super.visitLdcInsn(methodDescriptor);
        super.visitLdcInsn(line);
        super.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                INTERCEPTOR_INTERNAL,
                "onLineHit",
                ON_LINE_HIT_DESCRIPTOR,
                false);
    }

    // Every instruction-emitting visit method must trigger the probe first.
    // Frame/label/local-variable visits don't emit code, so they pass through.

    @Override public void visitInsn(int opcode) { emitProbeIfPending(); super.visitInsn(opcode); }
    @Override public void visitIntInsn(int opcode, int operand) { emitProbeIfPending(); super.visitIntInsn(opcode, operand); }
    @Override public void visitVarInsn(int opcode, int var) { emitProbeIfPending(); super.visitVarInsn(opcode, var); }
    @Override public void visitTypeInsn(int opcode, String type) { emitProbeIfPending(); super.visitTypeInsn(opcode, type); }
    @Override public void visitFieldInsn(int opcode, String owner, String name, String desc) { emitProbeIfPending(); super.visitFieldInsn(opcode, owner, name, desc); }
    @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean isInterface) { emitProbeIfPending(); super.visitMethodInsn(opcode, owner, name, desc, isInterface); }
    @Override public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) { emitProbeIfPending(); super.visitInvokeDynamicInsn(name, desc, bsm, bsmArgs); }
    @Override public void visitJumpInsn(int opcode, Label label) { emitProbeIfPending(); super.visitJumpInsn(opcode, label); }
    @Override public void visitLdcInsn(Object value) { emitProbeIfPending(); super.visitLdcInsn(value); }
    @Override public void visitIincInsn(int var, int increment) { emitProbeIfPending(); super.visitIincInsn(var, increment); }
    @Override public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) { emitProbeIfPending(); super.visitTableSwitchInsn(min, max, dflt, labels); }
    @Override public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) { emitProbeIfPending(); super.visitLookupSwitchInsn(dflt, keys, labels); }
    @Override public void visitMultiANewArrayInsn(String desc, int numDimensions) { emitProbeIfPending(); super.visitMultiANewArrayInsn(desc, numDimensions); }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        // Probe pushes 4 args before INVOKESTATIC. Bump max stack to ensure
        // it fits even if the original method computed a tighter bound.
        super.visitMaxs(Math.max(maxStack, 4), maxLocals);
    }
}
