package com.bugdigger.core.analysis

import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XrefIndexerTest {

    private val indexer = XrefIndexer()

    @Test
    fun `methodCallers records intra-class calls`() {
        // class a.B { void m1() { m2(); } void m2() {} }
        val bytes = classWith(
            internalName = "a/B",
            methods = listOf(
                method("m1") {
                    aload(0)                                 // this
                    invokeVirtual("a/B", "m2", "()V")
                    ret()
                },
                method("m2") { ret() },
            ),
        )

        val index = indexer.build(mapOf("a.B" to bytes))

        val callers = index.methodCallers["a.B#m2()V"] ?: error("expected callers list for m2")
        assertEquals(1, callers.size)
        assertEquals("a.B", callers[0].callerClassFqn)
        assertEquals("m1", callers[0].callerMethodName)
        assertEquals("()V", callers[0].callerMethodDescriptor)
        assertEquals(XrefCategory.INVOKE_VIRTUAL, callers[0].category)
    }

    @Test
    fun `methodCallers records cross-class calls`() {
        val a = classWith(
            "a/B",
            methods = listOf(
                method("m1") {
                    aload(0)
                    invokeVirtual("a/C", "target", "()V")
                    ret()
                },
            ),
        )
        val c = classWith("a/C", methods = listOf(method("target") { ret() }))

        val index = indexer.build(mapOf("a.B" to a, "a.C" to c))

        val callers = index.methodCallers["a.C#target()V"] ?: error("expected callers")
        assertEquals(1, callers.size)
        assertEquals("a.B", callers[0].callerClassFqn)
        assertEquals("m1", callers[0].callerMethodName)
    }

    @Test
    fun `method overloads keep separate caller lists`() {
        val bytes = classWith(
            "a/B",
            methods = listOf(
                method("caller") {
                    aload(0)
                    invokeVirtual("a/B", "f", "()V")
                    aload(0)
                    iconst1()
                    invokeVirtual("a/B", "f", "(I)V")
                    ret()
                },
                method("f") { ret() },
                method("f", descriptor = "(I)V") { ret() },
            ),
        )

        val index = indexer.build(mapOf("a.B" to bytes))

        val zero = index.methodCallers["a.B#f()V"] ?: error("expected callers for f()V")
        val one = index.methodCallers["a.B#f(I)V"] ?: error("expected callers for f(I)V")
        assertEquals(1, zero.size)
        assertEquals(1, one.size)
    }

    @Test
    fun `classUsers records FIELD_TYPE entries`() {
        val bytes = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "a/B", null, "java/lang/Object", null)
            visitField(Opcodes.ACC_PRIVATE, "ref", "Lo/j;", null, null).visitEnd()
            visitEnd()
        }.toByteArray()

        val index = indexer.build(mapOf("a.B" to bytes))

        val users = index.classUsers["o.j"] ?: error("expected o.j users")
        assertTrue(users.any { it.category == XrefCategory.FIELD_TYPE && it.callerClassFqn == "a.B" })
    }

    @Test
    fun `classUsers records SUPERCLASS and INTERFACE entries`() {
        val bytes = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "a/B", null, "o/Parent", arrayOf("o/I", "o/J"))
            visitEnd()
        }.toByteArray()

        val index = indexer.build(mapOf("a.B" to bytes))

        assertTrue(
            index.classUsers["o.Parent"]?.any { it.category == XrefCategory.SUPERCLASS } == true,
        )
        assertTrue(
            index.classUsers["o.I"]?.any { it.category == XrefCategory.INTERFACE } == true,
        )
        assertTrue(
            index.classUsers["o.J"]?.any { it.category == XrefCategory.INTERFACE } == true,
        )
    }

    @Test
    fun `classUsers records NEW and CHECKCAST inside method bodies`() {
        val bytes = classWith(
            "a/B",
            methods = listOf(
                method("m") {
                    new("o/Target")
                    pop()
                    aload(0)
                    checkCast("o/Target")
                    pop()
                    ret()
                },
            ),
        )

        val index = indexer.build(mapOf("a.B" to bytes))

        val users = index.classUsers["o.Target"] ?: error("expected o.Target users")
        assertTrue(users.any { it.category == XrefCategory.NEW })
        assertTrue(users.any { it.category == XrefCategory.CHECKCAST })
    }

    @Test
    fun `JDK and Bytesight runtime classes are excluded from classUsers`() {
        // a.B has a String field plus an io.grpc-style classloader leak.
        val bytes = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "a/B", null, "java/lang/Object", null)
            visitField(Opcodes.ACC_PRIVATE, "s", "Ljava/lang/String;", null, null).visitEnd()
            visitField(Opcodes.ACC_PRIVATE, "c", "Lio/grpc/Channel;", null, null).visitEnd()
            visitEnd()
        }.toByteArray()

        val index = indexer.build(mapOf("a.B" to bytes))

        assertTrue("java.lang.String" !in index.classUsers, "JDK String should be filtered")
        assertTrue("io.grpc.Channel" !in index.classUsers, "io.grpc should be filtered")
    }

    @Test
    fun `empty input produces empty index`() {
        val index = indexer.build(emptyMap())
        assertEquals(emptyMap<String, List<XrefSite>>(), index.methodCallers)
        assertEquals(emptyMap<String, List<XrefSite>>(), index.classUsers)
    }

    // ===== ASM helpers (parallel to ProjectDifferTest's MethodBody) =====

    private data class MethodSpec(val name: String, val descriptor: String, val body: MethodBody.() -> Unit)

    private fun method(name: String, descriptor: String = "()V", body: MethodBody.() -> Unit): MethodSpec =
        MethodSpec(name, descriptor, body)

    private fun classWith(internalName: String, methods: List<MethodSpec>): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        for (spec in methods) {
            val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, spec.name, spec.descriptor, null, null)
            mv.visitCode()
            spec.body.invoke(MethodBody(mv))
            // Always emit a return (callers responsible for type-correctness; they
            // generally end with ret() which is a no-op here).
            mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private class MethodBody(private val mv: org.objectweb.asm.MethodVisitor) {
        fun aload(idx: Int) { mv.visitVarInsn(Opcodes.ALOAD, idx) }
        fun iconst1() { mv.visitInsn(Opcodes.ICONST_1) }
        fun pop() { mv.visitInsn(Opcodes.POP) }
        fun ret() { /* RETURN added by classWith() */ }
        fun new(internalName: String) { mv.visitTypeInsn(Opcodes.NEW, internalName) }
        fun checkCast(internalName: String) { mv.visitTypeInsn(Opcodes.CHECKCAST, internalName) }
        fun invokeVirtual(owner: String, name: String, descriptor: String) {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, name, descriptor, false)
        }
    }
}
