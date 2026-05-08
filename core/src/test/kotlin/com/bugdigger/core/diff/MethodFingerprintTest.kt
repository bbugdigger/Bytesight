package com.bugdigger.core.diff

import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MethodFingerprintTest {

    @Test
    fun `extracts fingerprint per method including opcodes, callees, and strings`() {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "a/B", null, "java/lang/Object", null)

        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "foo", "(Ljava/lang/String;)I", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 1)                                         // ALOAD = 25
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false)
        mv.visitLdcInsn("hello")
        mv.visitInsn(Opcodes.POP)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()

        val fps = MethodFingerprint.extractAll(className = "a.B", bytecode = cw.toByteArray())
        val foo = fps.first { it.methodName == "foo" }

        assertTrue(foo.opcodeHistogram[Opcodes.ALOAD] >= 1)
        assertTrue(foo.opcodeHistogram[Opcodes.INVOKEVIRTUAL] >= 1)
        assertTrue(foo.opcodeHistogram[Opcodes.LDC] >= 1)
        assertTrue(foo.opcodeHistogram[Opcodes.IRETURN] >= 1)

        assertTrue("java.lang.String#length()I" in foo.calleeFqns)
        assertTrue("hello" in foo.stringConstants)

        assertEquals("int", foo.returnType)
        assertEquals(listOf("java.lang.String"), foo.parameterTypes)
        assertEquals("(Ljava/lang/String;)I", foo.descriptor)
    }

    @Test
    fun `equals and hashCode honor histogram contents`() {
        val a = MethodFingerprint(
            className = "a.B", methodName = "f", descriptor = "()V",
            returnType = "void", parameterTypes = emptyList(),
            opcodeHistogram = IntArray(MethodFingerprint.OPCODE_HISTOGRAM_SIZE).also { it[Opcodes.NOP] = 1 },
            calleeFqns = emptySet(), stringConstants = emptySet(), instructionCount = 1,
        )
        val b = a.copy(
            opcodeHistogram = IntArray(MethodFingerprint.OPCODE_HISTOGRAM_SIZE).also { it[Opcodes.NOP] = 1 },
        )
        val c = a.copy(
            opcodeHistogram = IntArray(MethodFingerprint.OPCODE_HISTOGRAM_SIZE).also { it[Opcodes.NOP] = 2 },
        )

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != c)
    }

    @Test
    fun `parameterArity reports parameter count`() {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "a/B", null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "g", "(IJ)V", null, null)
        mv.visitCode()
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()

        val fp = MethodFingerprint.extractAll("a.B", cw.toByteArray()).first { it.methodName == "g" }
        assertEquals(2, fp.parameterArity)
    }
}
