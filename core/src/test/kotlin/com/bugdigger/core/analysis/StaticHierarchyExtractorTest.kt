package com.bugdigger.core.analysis

import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaticHierarchyExtractorTest {

    private val extractor = StaticHierarchyExtractor()

    @Test
    fun `extracts FQN, simpleName, and packageName`() {
        val bytes = makeClass("com/example/Foo", "java/lang/Object", emptyArray())
        val md = extractor.extract(bytes)
        assertEquals("com.example.Foo", md.name)
        assertEquals("Foo", md.simpleName)
        assertEquals("com.example", md.packageName)
    }

    @Test
    fun `extracts superclass and interfaces`() {
        val bytes = makeClass(
            "com/example/MyList",
            superName = "java/util/AbstractList",
            interfaces = arrayOf("java/util/List", "java/io/Serializable"),
        )
        val md = extractor.extract(bytes)
        assertEquals("java.util.AbstractList", md.superName)
        assertEquals(listOf("java.util.List", "java.io.Serializable"), md.interfaces)
    }

    @Test
    fun `detects interface flag`() {
        val iface = makeClass("a/B", "java/lang/Object", emptyArray(),
            accessFlags = Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT)
        val md = extractor.extract(iface)
        assertTrue(md.isInterface)
        assertFalse(md.isEnum)
    }

    @Test
    fun `detects enum flag`() {
        val enumClass = makeClass("a/E", "java/lang/Enum", emptyArray(),
            accessFlags = Opcodes.ACC_ENUM or Opcodes.ACC_PUBLIC)
        val md = extractor.extract(enumClass)
        assertTrue(md.isEnum)
    }

    @Test
    fun `extracts methods with signature, return type, parameter types`() {
        val bytes = makeClassWithMethod(
            internalName = "a/B",
            methodName = "foo",
            descriptor = "(Ljava/lang/String;I)Ljava/util/List;",
        )
        val md = extractor.extract(bytes)
        val foo = md.methods.first { it.name == "foo" }
        assertEquals("(Ljava/lang/String;I)Ljava/util/List;", foo.descriptor)
        assertEquals("java.util.List", foo.returnType)
        assertEquals(listOf("java.lang.String", "int"), foo.parameterTypes)
    }

    @Test
    fun `extracts fields with name and type`() {
        val bytes = makeClassWithField("a/B", "name", "Ljava/lang/String;")
        val md = extractor.extract(bytes)
        val f = md.fields.first { it.name == "name" }
        assertEquals("java.lang.String", f.type)
    }

    @Test
    fun `bridge and synthetic flags propagate on methods`() {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "a/B", null, "java/lang/Object", null)
        cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_BRIDGE or Opcodes.ACC_SYNTHETIC,
            "synthBridge", "()V", null, null,
        ).visitEnd()
        cw.visitEnd()

        val md = extractor.extract(cw.toByteArray())
        val m = md.methods.first { it.name == "synthBridge" }
        assertTrue(m.isSynthetic)
        assertTrue(m.isBridge)
    }

    private fun makeClass(
        internalName: String,
        superName: String = "java/lang/Object",
        interfaces: Array<String> = emptyArray(),
        accessFlags: Int = Opcodes.ACC_PUBLIC,
    ): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V17, accessFlags, internalName, null, superName, interfaces)
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun makeClassWithMethod(internalName: String, methodName: String, descriptor: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, descriptor, null, null)
        mv.visitCode()
        mv.visitInsn(Opcodes.ACONST_NULL)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun makeClassWithField(internalName: String, fieldName: String, descriptor: String): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE, fieldName, descriptor, null, null).visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }
}
