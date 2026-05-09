package com.bugdigger.bytesight.service

import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RenameRemapperTest {

    @Test
    fun `mapType renames a class internal name and preserves package`() {
        val r = RenameRemapper(mapOf("o.j" to "Product"))
        assertEquals("o/Product", r.map("o/j"))
    }

    @Test
    fun `mapType returns input unchanged when no rename`() {
        val r = RenameRemapper(mapOf("o.j" to "Product"))
        assertEquals("java/lang/String", r.map("java/lang/String"))
        assertEquals("o/k", r.map("o/k"))
    }

    @Test
    fun `mapFieldName disambiguates two same-named fields by descriptor`() {
        // The user's case: class o.j has two fields named "a", different types.
        // Renaming the Map field should not touch the other.
        val r = RenameRemapper(
            mapOf(
                RenameStore.fieldKey("o.j", "a", "Ljava/util/Map;") to "products",
            ),
        )
        assertEquals("products", r.mapFieldName("o/j", "a", "Ljava/util/Map;"))
        assertEquals("a", r.mapFieldName("o/j", "a", "Lo/l;"))
    }

    @Test
    fun `mapMethodName disambiguates overloads by descriptor`() {
        val r = RenameRemapper(
            mapOf(
                RenameStore.methodKey("o.j", "a", "(Ljava/lang/String;DLo/a;)Lo/b;") to "createProduct",
            ),
        )
        assertEquals(
            "createProduct",
            r.mapMethodName("o/j", "a", "(Ljava/lang/String;DLo/a;)Lo/b;"),
        )
        // The 1-arg overload is a different method — must NOT be renamed.
        assertEquals("a", r.mapMethodName("o/j", "a", "(Ljava/lang/String;)Lo/b;"))
    }

    @Test
    fun `mapFieldName falls back to descriptor-less key when precise key absent`() {
        // Backward compat: renames written before the format extension still apply.
        val r = RenameRemapper(mapOf("o.j#legacyField" to "newName"))
        assertEquals("newName", r.mapFieldName("o/j", "legacyField", "Ljava/util/Map;"))
        assertEquals("newName", r.mapFieldName("o/j", "legacyField", "Lo/l;"))
    }

    @Test
    fun `end to end - bytecode rename leaves the unrelated same-named field untouched`() {
        // Build a class with the user's pathological shape: two fields named
        // "a", different types. Apply ClassRemapper with a rename for ONLY
        // the Map field. Verify the resulting bytecode has the rename
        // applied to one field and not the other.
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "o/j", null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL, "a", "Ljava/util/Map;", null, null).visitEnd()
        cw.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "a", "Lo/l;", null, null).visitEnd()
        cw.visitEnd()
        val original = cw.toByteArray()

        val remapper = RenameRemapper(
            mapOf(RenameStore.fieldKey("o.j", "a", "Ljava/util/Map;") to "products"),
        )

        val outWriter = ClassWriter(0)
        ClassReader(original).accept(ClassRemapper(outWriter, remapper), 0)
        val remapped = outWriter.toByteArray()

        // Inspect resulting fields.
        val seen = mutableListOf<Pair<String, String>>()
        ClassReader(remapped).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitField(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                value: Any?,
            ): FieldVisitor? {
                seen.add(name to descriptor)
                return null
            }

            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor? = null
        }, 0)

        // Map field is renamed to "products"; the other "a" field is untouched.
        assertTrue("products" to "Ljava/util/Map;" in seen, "expected products: Map, got $seen")
        assertTrue("a" to "Lo/l;" in seen, "expected a: l (untouched), got $seen")
    }
}
