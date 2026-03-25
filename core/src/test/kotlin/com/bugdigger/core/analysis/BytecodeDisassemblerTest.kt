package com.bugdigger.core.analysis

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [BytecodeDisassembler].
 */
class BytecodeDisassemblerTest {

    private val disassembler = BytecodeDisassembler()

    /**
     * Creates a simple class with a method that adds two ints.
     */
    private fun createSimpleClass(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Simple", null, "java/lang/Object", null)

        // Constructor
        val init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        init.visitCode()
        init.visitVarInsn(Opcodes.ALOAD, 0)
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        init.visitInsn(Opcodes.RETURN)
        init.visitMaxs(1, 1)
        init.visitEnd()

        // add(int, int) -> int
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "add", "(II)I", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ILOAD, 1)
        mv.visitVarInsn(Opcodes.ILOAD, 2)
        mv.visitInsn(Opcodes.IADD)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitMaxs(2, 3)
        mv.visitEnd()

        cw.visitEnd()
        return cw.toByteArray()
    }

    /**
     * Creates a class with a method containing an if-else branch.
     */
    private fun createClassWithBranch(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Branchy", null, "java/lang/Object", null)

        val init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        init.visitCode()
        init.visitVarInsn(Opcodes.ALOAD, 0)
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        init.visitInsn(Opcodes.RETURN)
        init.visitMaxs(1, 1)
        init.visitEnd()

        // isPositive(int) -> boolean
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "isPositive", "(I)Z", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ILOAD, 1)
        val labelFalse = Label()
        mv.visitJumpInsn(Opcodes.IFLE, labelFalse)
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitLabel(labelFalse)
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitMaxs(1, 2)
        mv.visitEnd()

        cw.visitEnd()
        return cw.toByteArray()
    }

    /**
     * Creates a class with a field.
     */
    private fun createClassWithField(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/WithField", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE, "counter", "I", null, null)

        val init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        init.visitCode()
        init.visitVarInsn(Opcodes.ALOAD, 0)
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        init.visitInsn(Opcodes.RETURN)
        init.visitMaxs(1, 1)
        init.visitEnd()

        cw.visitEnd()
        return cw.toByteArray()
    }

    @Nested
    @DisplayName("Class Metadata")
    inner class ClassMetadata {
        @Test
        fun `should extract class name`() {
            val result = disassembler.disassemble(createSimpleClass())
            assertEquals("com.example.Simple", result.className)
        }

        @Test
        fun `should extract superclass`() {
            val result = disassembler.disassemble(createSimpleClass())
            assertEquals("java.lang.Object", result.superName)
        }

        @Test
        fun `should extract class version`() {
            val result = disassembler.disassemble(createSimpleClass())
            assertEquals(61, result.majorVersion)  // Java 17 = class version 61
            assertTrue(result.javaVersion.contains("17"))
        }
    }

    @Nested
    @DisplayName("Method Disassembly")
    inner class MethodDisassembly {
        @Test
        fun `should find all methods`() {
            val result = disassembler.disassemble(createSimpleClass())
            assertEquals(2, result.methods.size) // <init> + add
            assertTrue(result.methods.any { it.name == "<init>" })
            assertTrue(result.methods.any { it.name == "add" })
        }

        @Test
        fun `should disassemble add method instructions`() {
            val result = disassembler.disassemble(createSimpleClass())
            val addMethod = result.methods.first { it.name == "add" }

            // Should contain ILOAD, ILOAD, IADD, IRETURN
            assertTrue(addMethod.instructions.any { it.mnemonic == "ILOAD" })
            assertTrue(addMethod.instructions.any { it.mnemonic == "IADD" })
            assertTrue(addMethod.instructions.any { it.mnemonic == "IRETURN" })
        }

        @Test
        fun `should record method descriptor`() {
            val result = disassembler.disassemble(createSimpleClass())
            val addMethod = result.methods.first { it.name == "add" }
            assertEquals("(II)I", addMethod.descriptor)
        }

        @Test
        fun `should record maxStack and maxLocals`() {
            val result = disassembler.disassemble(createSimpleClass())
            val addMethod = result.methods.first { it.name == "add" }
            assertEquals(2, addMethod.maxStack)
            assertEquals(3, addMethod.maxLocals)
        }
    }

    @Nested
    @DisplayName("Instruction Categories")
    inner class InstructionCategories {
        @Test
        fun `should categorize load instructions`() {
            val result = disassembler.disassemble(createSimpleClass())
            val addMethod = result.methods.first { it.name == "add" }
            val loads = addMethod.instructions.filter { it.mnemonic == "ILOAD" }
            assertTrue(loads.all { it.type == InstructionCategory.LOAD_STORE })
        }

        @Test
        fun `should categorize arithmetic instructions`() {
            val result = disassembler.disassemble(createSimpleClass())
            val addMethod = result.methods.first { it.name == "add" }
            val iadd = addMethod.instructions.first { it.mnemonic == "IADD" }
            assertEquals(InstructionCategory.ARITHMETIC, iadd.type)
        }

        @Test
        fun `should categorize control flow instructions`() {
            val result = disassembler.disassemble(createClassWithBranch())
            val method = result.methods.first { it.name == "isPositive" }
            val jumps = method.instructions.filter { it.mnemonic == "IFLE" }
            assertTrue(jumps.all { it.type == InstructionCategory.CONTROL_FLOW })
        }

        @Test
        fun `should categorize return as control flow`() {
            val result = disassembler.disassemble(createSimpleClass())
            val addMethod = result.methods.first { it.name == "add" }
            val ret = addMethod.instructions.first { it.mnemonic == "IRETURN" }
            assertEquals(InstructionCategory.CONTROL_FLOW, ret.type)
        }
    }

    @Nested
    @DisplayName("Fields")
    inner class Fields {
        @Test
        fun `should extract fields`() {
            val result = disassembler.disassemble(createClassWithField())
            assertEquals(1, result.fields.size)
            val field = result.fields.first()
            assertEquals("counter", field.name)
            assertEquals("I", field.descriptor)
        }

        @Test
        fun `should report access modifiers for fields`() {
            val result = disassembler.disassemble(createClassWithField())
            val field = result.fields.first()
            assertTrue(field.accessString.contains("private"))
        }
    }

    @Nested
    @DisplayName("Access Modifiers")
    inner class AccessModifiers {
        @Test
        fun `should report public access for methods`() {
            val result = disassembler.disassemble(createSimpleClass())
            val addMethod = result.methods.first { it.name == "add" }
            assertTrue(addMethod.accessString.contains("public"))
        }
    }
}
