package com.bugdigger.core.diff

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Lightweight per-method feature vector for bytecode similarity.
 *
 * Designed to be cheap to compute (single ASM pass per class) and small to
 * compare (256-int histogram + a few sets of strings). Adequate for the v1
 * heuristic-based [ProjectDiffer]; a future v2 with CFG/edge similarity
 * would extend this struct (or wrap it).
 */
data class MethodFingerprint(
    val className: String,
    val methodName: String,
    val descriptor: String,
    val returnType: String,
    val parameterTypes: List<String>,
    /** length [OPCODE_HISTOGRAM_SIZE]; counts opcode occurrences in the method body. */
    val opcodeHistogram: IntArray,
    /** Method calls observed: `"owner#name(desc)"`. */
    val calleeFqns: Set<String>,
    /** String constants loaded via LDC. */
    val stringConstants: Set<String>,
    val instructionCount: Int,
) {
    val parameterArity: Int get() = parameterTypes.size

    /** Compact key used by the matcher to lock pairs. */
    val key: String get() = "$className#$methodName$descriptor"

    // IntArray equality is identity-based by default; override so two
    // fingerprints with equal contents compare equal.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MethodFingerprint) return false
        if (className != other.className) return false
        if (methodName != other.methodName) return false
        if (descriptor != other.descriptor) return false
        if (!opcodeHistogram.contentEquals(other.opcodeHistogram)) return false
        if (calleeFqns != other.calleeFqns) return false
        if (stringConstants != other.stringConstants) return false
        return true
    }

    override fun hashCode(): Int {
        var result = className.hashCode()
        result = 31 * result + methodName.hashCode()
        result = 31 * result + descriptor.hashCode()
        result = 31 * result + opcodeHistogram.contentHashCode()
        return result
    }

    companion object {
        const val OPCODE_HISTOGRAM_SIZE = 256

        /** Extract one fingerprint per method declared on the given class. */
        fun extractAll(className: String, bytecode: ByteArray): List<MethodFingerprint> {
            val out = mutableListOf<MethodFingerprint>()
            val reader = ClassReader(bytecode)
            reader.accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor = FingerprintCollector(className, name, descriptor, out)
            }, ClassReader.SKIP_FRAMES or ClassReader.SKIP_DEBUG)
            return out
        }
    }
}

private class FingerprintCollector(
    private val className: String,
    private val methodName: String,
    private val descriptor: String,
    private val out: MutableList<MethodFingerprint>,
) : MethodVisitor(Opcodes.ASM9) {

    private val histogram = IntArray(MethodFingerprint.OPCODE_HISTOGRAM_SIZE)
    private val callees = mutableSetOf<String>()
    private val strings = mutableSetOf<String>()
    private var instructions = 0

    override fun visitInsn(opcode: Int) { count(opcode) }
    override fun visitIntInsn(opcode: Int, operand: Int) { count(opcode) }
    override fun visitVarInsn(opcode: Int, varIndex: Int) { count(opcode) }
    override fun visitTypeInsn(opcode: Int, type: String) { count(opcode) }
    override fun visitFieldInsn(opcode: Int, owner: String, name: String, desc: String) { count(opcode) }
    override fun visitJumpInsn(opcode: Int, label: Label) { count(opcode) }
    override fun visitIincInsn(varIndex: Int, increment: Int) { count(Opcodes.IINC) }
    override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label, vararg labels: Label) {
        count(Opcodes.TABLESWITCH)
    }
    override fun visitLookupSwitchInsn(dflt: Label, keys: IntArray, labels: Array<out Label>) {
        count(Opcodes.LOOKUPSWITCH)
    }
    override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) {
        count(Opcodes.MULTIANEWARRAY)
    }

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, isInterface: Boolean) {
        count(opcode)
        callees.add("${owner.replace('/', '.')}#$name$desc")
    }

    override fun visitInvokeDynamicInsn(name: String, descriptor: String, bsm: Handle, vararg bsmArgs: Any?) {
        count(Opcodes.INVOKEDYNAMIC)
        callees.add("indy#$name$descriptor")
    }

    override fun visitLdcInsn(value: Any?) {
        count(Opcodes.LDC)
        if (value is String) strings.add(value)
    }

    override fun visitEnd() {
        out.add(
            MethodFingerprint(
                className = className,
                methodName = methodName,
                descriptor = descriptor,
                returnType = Type.getReturnType(descriptor).className,
                parameterTypes = Type.getArgumentTypes(descriptor).map { it.className },
                opcodeHistogram = histogram,
                calleeFqns = callees.toSet(),
                stringConstants = strings.toSet(),
                instructionCount = instructions,
            ),
        )
    }

    private fun count(opcode: Int) {
        if (opcode in 0 until MethodFingerprint.OPCODE_HISTOGRAM_SIZE) histogram[opcode]++
        instructions++
    }
}
