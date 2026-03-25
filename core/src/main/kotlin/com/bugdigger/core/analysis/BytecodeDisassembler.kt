package com.bugdigger.core.analysis

import org.objectweb.asm.*
import org.objectweb.asm.util.Printer

/**
 * Represents a single JVM bytecode instruction.
 */
data class Instruction(
    val offset: Int,
    val opcode: Int,
    val mnemonic: String,
    val operands: String,
    val lineNumber: Int?,
    val type: InstructionCategory,
)

/**
 * Category of a bytecode instruction for color coding in the UI.
 */
enum class InstructionCategory {
    CONTROL_FLOW,   // goto, if*, tableswitch, lookupswitch, return, athrow
    METHOD_CALL,    // invoke*
    FIELD_ACCESS,   // getfield, putfield, getstatic, putstatic
    LOAD_STORE,     // aload, iload, astore, istore, etc.
    STACK,          // dup, pop, swap
    CONSTANT,       // ldc, bipush, sipush, iconst, etc.
    ARITHMETIC,     // iadd, isub, imul, etc.
    TYPE_CHECK,     // checkcast, instanceof
    ARRAY,          // newarray, anewarray, arraylength, *aload, *astore
    OTHER,
}

/**
 * Represents a disassembled method with its instructions and metadata.
 */
data class DisassembledMethod(
    val name: String,
    val descriptor: String,
    val accessFlags: Int,
    val instructions: List<Instruction>,
    val maxStack: Int,
    val maxLocals: Int,
    val tryCatchBlocks: List<TryCatchBlockInfo>,
    val localVariables: List<LocalVariableInfo>,
) {
    /** Human-readable access modifier string. */
    val accessString: String
        get() = buildAccessString(accessFlags)
}

/**
 * Represents a try-catch block.
 */
data class TryCatchBlockInfo(
    val startOffset: Int,
    val endOffset: Int,
    val handlerOffset: Int,
    val exceptionType: String?,
)

/**
 * Represents a local variable entry.
 */
data class LocalVariableInfo(
    val name: String,
    val descriptor: String,
    val index: Int,
    val startOffset: Int,
    val endOffset: Int,
)

/**
 * Represents a field summary.
 */
data class FieldSummary(
    val name: String,
    val descriptor: String,
    val accessFlags: Int,
) {
    val accessString: String
        get() = buildAccessString(accessFlags)
}

/**
 * Result of disassembling a complete class file.
 */
data class DisassembledClass(
    val className: String,
    val majorVersion: Int,
    val minorVersion: Int,
    val accessFlags: Int,
    val superName: String?,
    val interfaces: List<String>,
    val methods: List<DisassembledMethod>,
    val fields: List<FieldSummary>,
) {
    /** Human-readable JVM version string (e.g., "Java 17"). */
    val javaVersion: String
        get() = when {
            majorVersion >= 45 -> "Java ${majorVersion - 44}"
            else -> "Class version $majorVersion.$minorVersion"
        }
}

/**
 * Disassembles JVM bytecode into a structured representation of instructions per method.
 *
 * Uses ASM [ClassReader] with a custom [MethodVisitor] that records each
 * instruction with its opcode, mnemonic, operands, and source line number.
 */
class BytecodeDisassembler {

    /**
     * Disassembles the given class bytecode.
     *
     * @param bytecode Raw class file bytes
     * @return A [DisassembledClass] containing all methods and their instructions
     */
    fun disassemble(bytecode: ByteArray): DisassembledClass {
        val reader = ClassReader(bytecode)
        val methods = mutableListOf<DisassembledMethod>()
        val fields = mutableListOf<FieldSummary>()

        var className = ""
        var majorVersion = 0
        var minorVersion = 0
        var classAccessFlags = 0
        var superName: String? = null
        var interfaces: List<String> = emptyList()

        reader.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(
                version: Int,
                access: Int,
                name: String,
                signature: String?,
                superNameParam: String?,
                interfacesParam: Array<out String>?,
            ) {
                className = name.replace('/', '.')
                majorVersion = version and 0xFFFF
                minorVersion = (version ushr 16) and 0xFFFF
                classAccessFlags = access
                superName = superNameParam?.replace('/', '.')
                interfaces = interfacesParam?.map { it.replace('/', '.') } ?: emptyList()
            }

            override fun visitField(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                value: Any?,
            ): FieldVisitor? {
                fields.add(FieldSummary(name, descriptor, access))
                return null
            }

            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                return InstructionCollectorVisitor(name, descriptor, access, methods)
            }
        }, ClassReader.EXPAND_FRAMES)

        return DisassembledClass(
            className = className,
            majorVersion = majorVersion,
            minorVersion = minorVersion,
            accessFlags = classAccessFlags,
            superName = superName,
            interfaces = interfaces,
            methods = methods,
            fields = fields,
        )
    }
}

// ========== Internal Helpers ==========

/**
 * ASM MethodVisitor that collects instructions into a structured list.
 */
private class InstructionCollectorVisitor(
    private val methodName: String,
    private val methodDescriptor: String,
    private val methodAccess: Int,
    private val methodsList: MutableList<DisassembledMethod>,
) : MethodVisitor(Opcodes.ASM9) {

    private val instructions = mutableListOf<Instruction>()
    private val tryCatchBlocks = mutableListOf<TryCatchBlockInfo>()
    private val localVariables = mutableListOf<LocalVariableInfo>()
    private var currentLine: Int? = null
    private var offset = 0
    private var maxStack = 0
    private var maxLocals = 0

    override fun visitLineNumber(line: Int, start: Label?) {
        currentLine = line
    }

    override fun visitInsn(opcode: Int) {
        addInstruction(opcode, "")
    }

    override fun visitIntInsn(opcode: Int, operand: Int) {
        addInstruction(opcode, operand.toString())
    }

    override fun visitVarInsn(opcode: Int, varIndex: Int) {
        addInstruction(opcode, varIndex.toString())
    }

    override fun visitTypeInsn(opcode: Int, type: String) {
        addInstruction(opcode, type.replace('/', '.'))
    }

    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
        addInstruction(opcode, "${owner.replace('/', '.')}.$name : $descriptor")
    }

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
        addInstruction(opcode, "${owner.replace('/', '.')}.$name$descriptor")
    }

    override fun visitInvokeDynamicInsn(name: String, descriptor: String, bsm: Handle, vararg bsmArgs: Any?) {
        addInstruction(Opcodes.INVOKEDYNAMIC, "$name$descriptor")
    }

    override fun visitJumpInsn(opcode: Int, label: Label) {
        addInstruction(opcode, label.toString())
    }

    override fun visitLdcInsn(value: Any?) {
        val operandStr = when (value) {
            is String -> "\"$value\""
            is Type -> value.className
            else -> value.toString()
        }
        addInstruction(Opcodes.LDC, operandStr)
    }

    override fun visitIincInsn(varIndex: Int, increment: Int) {
        addInstruction(Opcodes.IINC, "$varIndex, $increment")
    }

    override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label, vararg labels: Label) {
        addInstruction(Opcodes.TABLESWITCH, "$min to $max, default: $dflt")
    }

    override fun visitLookupSwitchInsn(dflt: Label, keys: IntArray, labels: Array<out Label>) {
        val cases = keys.zip(labels.toList()).joinToString(", ") { (k, l) -> "$k: $l" }
        addInstruction(Opcodes.LOOKUPSWITCH, "default: $dflt, $cases")
    }

    override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) {
        addInstruction(Opcodes.MULTIANEWARRAY, "$descriptor dim=$numDimensions")
    }

    override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) {
        tryCatchBlocks.add(
            TryCatchBlockInfo(
                startOffset = start.hashCode(),
                endOffset = end.hashCode(),
                handlerOffset = handler.hashCode(),
                exceptionType = type?.replace('/', '.'),
            )
        )
    }

    override fun visitLocalVariable(
        name: String,
        descriptor: String,
        signature: String?,
        start: Label,
        end: Label,
        index: Int,
    ) {
        localVariables.add(
            LocalVariableInfo(
                name = name,
                descriptor = descriptor,
                index = index,
                startOffset = start.hashCode(),
                endOffset = end.hashCode(),
            )
        )
    }

    override fun visitMaxs(maxStack: Int, maxLocals: Int) {
        this.maxStack = maxStack
        this.maxLocals = maxLocals
    }

    override fun visitEnd() {
        methodsList.add(
            DisassembledMethod(
                name = methodName,
                descriptor = methodDescriptor,
                accessFlags = methodAccess,
                instructions = instructions.toList(),
                maxStack = maxStack,
                maxLocals = maxLocals,
                tryCatchBlocks = tryCatchBlocks.toList(),
                localVariables = localVariables.toList(),
            )
        )
    }

    private fun addInstruction(opcode: Int, operands: String) {
        val mnemonic = if (opcode in 0 until Printer.OPCODES.size) {
            Printer.OPCODES[opcode]
        } else {
            "UNKNOWN_$opcode"
        }

        instructions.add(
            Instruction(
                offset = offset++,
                opcode = opcode,
                mnemonic = mnemonic,
                operands = operands,
                lineNumber = currentLine,
                type = categorizeOpcode(opcode),
            )
        )
    }
}

/**
 * Categorizes an opcode into an [InstructionCategory] for UI color coding.
 */
private fun categorizeOpcode(opcode: Int): InstructionCategory = when (opcode) {
    // Control flow
    in Opcodes.IFEQ..Opcodes.IF_ACMPNE,
    Opcodes.GOTO, Opcodes.JSR, Opcodes.RET,
    Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH,
    in Opcodes.IRETURN..Opcodes.RETURN,
    Opcodes.ATHROW, Opcodes.IFNULL, Opcodes.IFNONNULL -> InstructionCategory.CONTROL_FLOW

    // Method calls
    in Opcodes.INVOKEVIRTUAL..Opcodes.INVOKEDYNAMIC -> InstructionCategory.METHOD_CALL

    // Field access
    in Opcodes.GETSTATIC..Opcodes.PUTFIELD -> InstructionCategory.FIELD_ACCESS

    // Load/store
    in Opcodes.ILOAD..Opcodes.ALOAD,
    in Opcodes.ISTORE..Opcodes.ASTORE -> InstructionCategory.LOAD_STORE

    // Stack manipulation
    Opcodes.POP, Opcodes.POP2,
    in Opcodes.DUP..Opcodes.DUP2_X2,
    Opcodes.SWAP -> InstructionCategory.STACK

    // Constants
    in Opcodes.ACONST_NULL..Opcodes.DCONST_1,
    Opcodes.BIPUSH, Opcodes.SIPUSH,
    Opcodes.LDC -> InstructionCategory.CONSTANT

    // Arithmetic
    in Opcodes.IADD..Opcodes.LXOR,
    Opcodes.IINC,
    in Opcodes.I2L..Opcodes.I2S,
    in Opcodes.LCMP..Opcodes.DCMPG -> InstructionCategory.ARITHMETIC

    // Type checks
    Opcodes.CHECKCAST, Opcodes.INSTANCEOF -> InstructionCategory.TYPE_CHECK

    // Array operations
    Opcodes.NEWARRAY, Opcodes.ANEWARRAY, Opcodes.ARRAYLENGTH,
    Opcodes.MULTIANEWARRAY,
    in Opcodes.IALOAD..Opcodes.SALOAD,
    in Opcodes.IASTORE..Opcodes.SASTORE -> InstructionCategory.ARRAY

    else -> InstructionCategory.OTHER
}

/**
 * Builds a human-readable access modifier string from JVM access flags.
 */
private fun buildAccessString(accessFlags: Int): String = buildList {
    if (accessFlags and Opcodes.ACC_PUBLIC != 0) add("public")
    if (accessFlags and Opcodes.ACC_PRIVATE != 0) add("private")
    if (accessFlags and Opcodes.ACC_PROTECTED != 0) add("protected")
    if (accessFlags and Opcodes.ACC_STATIC != 0) add("static")
    if (accessFlags and Opcodes.ACC_FINAL != 0) add("final")
    if (accessFlags and Opcodes.ACC_SYNCHRONIZED != 0) add("synchronized")
    if (accessFlags and Opcodes.ACC_ABSTRACT != 0) add("abstract")
    if (accessFlags and Opcodes.ACC_NATIVE != 0) add("native")
}.joinToString(" ")
