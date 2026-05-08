package com.bugdigger.bytesight.source

import com.bugdigger.core.analysis.StaticClassMetadata
import com.bugdigger.core.analysis.StaticFieldMetadata
import com.bugdigger.core.analysis.StaticMethodMetadata
import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class StaticClassInfoMapperTest {

    @Test
    fun `maps top-level fields onto ClassInfo`() {
        val md = StaticClassMetadata(
            name = "com.example.Foo",
            packageName = "com.example",
            simpleName = "Foo",
            superName = "java.lang.Object",
            interfaces = listOf("java.io.Serializable"),
            modifiers = Opcodes.ACC_PUBLIC,
            isInterface = false,
            isEnum = false,
            isAnnotation = false,
            isSynthetic = false,
            methods = emptyList(),
            fields = emptyList(),
        )

        val info = StaticClassInfoMapper.toClassInfo(md, classLoaderName = "JarFile")

        assertEquals("com.example.Foo", info.name)
        assertEquals("com.example", info.packageName)
        assertEquals("Foo", info.simpleName)
        assertEquals("java.lang.Object", info.superclass)
        assertEquals(listOf("java.io.Serializable"), info.interfacesList)
        assertEquals("JarFile", info.classLoader)
        assertFalse(info.isInterface)
        assertFalse(info.isEnum)
    }

    @Test
    fun `maps methods to MethodInfo with descriptor in signature field`() {
        val md = StaticClassMetadata(
            name = "a.B", packageName = "a", simpleName = "B",
            superName = "java.lang.Object", interfaces = emptyList(),
            modifiers = Opcodes.ACC_PUBLIC,
            isInterface = false, isEnum = false, isAnnotation = false, isSynthetic = false,
            methods = listOf(
                StaticMethodMetadata(
                    name = "foo", descriptor = "(I)V",
                    returnType = "void", parameterTypes = listOf("int"),
                    modifiers = Opcodes.ACC_PUBLIC,
                    isSynthetic = false, isBridge = false,
                ),
            ),
            fields = emptyList(),
        )

        val info = StaticClassInfoMapper.toClassInfo(md, "JarFile")
        val foo = info.methodsList.single()

        assertEquals("foo", foo.name)
        assertEquals("(I)V", foo.signature)
        assertEquals("void", foo.returnType)
        assertEquals(listOf("int"), foo.parameterTypesList)
    }

    @Test
    fun `maps fields to FieldInfo`() {
        val md = StaticClassMetadata(
            name = "a.B", packageName = "a", simpleName = "B",
            superName = "java.lang.Object", interfaces = emptyList(),
            modifiers = Opcodes.ACC_PUBLIC,
            isInterface = false, isEnum = false, isAnnotation = false, isSynthetic = false,
            methods = emptyList(),
            fields = listOf(
                StaticFieldMetadata(
                    name = "count", descriptor = "I", type = "int",
                    modifiers = Opcodes.ACC_PRIVATE, isSynthetic = false,
                ),
            ),
        )

        val info = StaticClassInfoMapper.toClassInfo(md, "JarFile")
        val f = info.fieldsList.single()

        assertEquals("count", f.name)
        assertEquals("int", f.type)
    }

    @Test
    fun `superName=null becomes empty string in ClassInfo`() {
        val md = StaticClassMetadata(
            name = "java.lang.Object", packageName = "java.lang", simpleName = "Object",
            superName = null,
            interfaces = emptyList(),
            modifiers = Opcodes.ACC_PUBLIC,
            isInterface = false, isEnum = false, isAnnotation = false, isSynthetic = false,
            methods = emptyList(), fields = emptyList(),
        )

        val info = StaticClassInfoMapper.toClassInfo(md, "JarFile")

        assertEquals("", info.superclass)
    }
}
