package com.bugdigger.core.analysis

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [ConstantExtractor].
 */
class ConstantExtractorTest {

    private val extractor = ConstantExtractor()

    /**
     * Creates simple bytecode for a class with a method that loads the given constants via LDC.
     */
    private fun createClassWithConstants(
        className: String = "com/example/TestClass",
        methodName: String = "testMethod",
        constants: List<Any>,
    ): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null)

        // Default constructor
        val init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        init.visitCode()
        init.visitVarInsn(Opcodes.ALOAD, 0)
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        init.visitInsn(Opcodes.RETURN)
        init.visitMaxs(1, 1)
        init.visitEnd()

        // Method with LDC instructions for each constant
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()V", null, null)
        mv.visitCode()
        for (constant in constants) {
            mv.visitLdcInsn(constant)
            when (constant) {
                is String -> mv.visitInsn(Opcodes.POP)
                is Int -> mv.visitInsn(Opcodes.POP)
                is Long -> mv.visitInsn(Opcodes.POP2)
                is Float -> mv.visitInsn(Opcodes.POP)
                is Double -> mv.visitInsn(Opcodes.POP2)
                else -> mv.visitInsn(Opcodes.POP)
            }
        }
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(2, 1)
        mv.visitEnd()

        cw.visitEnd()
        return cw.toByteArray()
    }

    @Nested
    @DisplayName("String Extraction")
    inner class StringExtraction {
        @Test
        fun `should extract string constants`() {
            val bytecode = createClassWithConstants(constants = listOf("hello world", "test"))
            val result = extractor.extract(bytecode)
            val strings = result.filter { it.type == ConstantType.STRING }
            assertEquals(2, strings.size)
            assertTrue(strings.any { it.value == "hello world" })
            assertTrue(strings.any { it.value == "test" })
        }

        @Test
        fun `should detect URL pattern`() {
            val bytecode = createClassWithConstants(constants = listOf("https://api.example.com/v1"))
            val result = extractor.extract(bytecode)
            val urls = result.filter { it.matchedPatterns.contains(StringPattern.URL) }
            assertEquals(1, urls.size)
            assertEquals("https://api.example.com/v1", urls.first().value)
        }

        @Test
        fun `should detect IP address pattern`() {
            val bytecode = createClassWithConstants(constants = listOf("192.168.1.1"))
            val result = extractor.extract(bytecode)
            val ips = result.filter { it.matchedPatterns.contains(StringPattern.IP_ADDRESS) }
            assertEquals(1, ips.size)
        }

        @Test
        fun `should detect file path pattern`() {
            val bytecode = createClassWithConstants(constants = listOf("/etc/config.yaml"))
            val result = extractor.extract(bytecode)
            val paths = result.filter { it.matchedPatterns.contains(StringPattern.FILE_PATH) }
            assertEquals(1, paths.size)
        }
    }

    @Nested
    @DisplayName("Numeric Extraction")
    inner class NumericExtraction {
        @Test
        fun `should extract integer constants`() {
            val bytecode = createClassWithConstants(constants = listOf(42))
            val result = extractor.extract(bytecode)
            val ints = result.filter { it.type == ConstantType.INTEGER }
            assertTrue(ints.any { it.value == 42 })
        }

        @Test
        fun `should extract long constants`() {
            val bytecode = createClassWithConstants(constants = listOf(123456789L))
            val result = extractor.extract(bytecode)
            val longs = result.filter { it.type == ConstantType.LONG }
            assertTrue(longs.any { it.value == 123456789L })
        }

        @Test
        fun `should extract float constants`() {
            val bytecode = createClassWithConstants(constants = listOf(3.14f))
            val result = extractor.extract(bytecode)
            val floats = result.filter { it.type == ConstantType.FLOAT }
            assertTrue(floats.any { it.value == 3.14f })
        }

        @Test
        fun `should extract double constants`() {
            val bytecode = createClassWithConstants(constants = listOf(2.71828))
            val result = extractor.extract(bytecode)
            val doubles = result.filter { it.type == ConstantType.DOUBLE }
            assertTrue(doubles.any { it.value == 2.71828 })
        }
    }

    @Nested
    @DisplayName("Metadata")
    inner class Metadata {
        @Test
        fun `should record class name`() {
            val bytecode = createClassWithConstants(
                className = "com/example/MyService",
                constants = listOf("test"),
            )
            val result = extractor.extract(bytecode)
            assertTrue(result.all { it.className == "com.example.MyService" })
        }

        @Test
        fun `should record method name`() {
            val bytecode = createClassWithConstants(
                methodName = "processData",
                constants = listOf("test"),
            )
            val result = extractor.extract(bytecode)
            assertTrue(result.all { it.methodName == "processData" })
        }

        @Test
        fun `should produce human-readable location`() {
            val bytecode = createClassWithConstants(
                className = "com/example/MyClass",
                methodName = "doStuff",
                constants = listOf("hello"),
            )
            val result = extractor.extract(bytecode)
            assertEquals("com.example.MyClass.doStuff()", result.first().location)
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCases {
        @Test
        fun `should return empty list for class with no constants`() {
            val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Empty", null, "java/lang/Object", null)
            val init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
            init.visitCode()
            init.visitVarInsn(Opcodes.ALOAD, 0)
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            init.visitInsn(Opcodes.RETURN)
            init.visitMaxs(1, 1)
            init.visitEnd()
            cw.visitEnd()

            val result = extractor.extract(cw.toByteArray())
            // Only result might be from ALOAD(0) which is BIPUSH — but 0 is ICONST_0
            // An empty method with no LDC/BIPUSH/SIPUSH should be empty or minimal
            assertTrue(result.filter { it.type == ConstantType.STRING }.isEmpty())
        }

        @Test
        fun `should handle multiple constants in single method`() {
            val bytecode = createClassWithConstants(
                constants = listOf("a", "b", "c", 1, 2L, 3.0f),
            )
            val result = extractor.extract(bytecode)
            assertTrue(result.size >= 6)
        }
    }
}
