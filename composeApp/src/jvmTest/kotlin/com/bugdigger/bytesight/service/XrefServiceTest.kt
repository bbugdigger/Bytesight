package com.bugdigger.bytesight.service

import com.bugdigger.bytesight.source.Capability
import com.bugdigger.bytesight.source.FakeClassSource
import com.bugdigger.core.analysis.XrefIndexer
import com.bugdigger.protocol.ClassInfo
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XrefServiceTest {

    @Test
    fun `findCallersOf returns sites against the active source`() = runBlocking {
        val a = bareCallerClass("a/B", calleeOwner = "a/C", calleeName = "target")
        val c = bareTargetClass("a/C", "target")
        val registry = ConnectionRegistry().apply {
            setSource(
                FakeClassSource(
                    classes = listOf(
                        ClassInfo.newBuilder().setName("a.B").build(),
                        ClassInfo.newBuilder().setName("a.C").build(),
                    ),
                    bytecode = mapOf("a.B" to a, "a.C" to c),
                    capabilities = Capability.ALL,
                ),
                connectionKey = "test",
            )
        }
        val service = XrefService(registry, XrefIndexer())

        val callers = service.findCallersOf("a.C#target()V")

        assertEquals(1, callers.size)
        assertEquals("a.B", callers[0].callerClassFqn)
        assertEquals("m1", callers[0].callerMethodName)
    }

    @Test
    fun `findUsersOf returns class users against the active source`() = runBlocking {
        // a.B has a field of type o.Target.
        val cw = ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "a/B", null, "java/lang/Object", null)
            visitField(Opcodes.ACC_PRIVATE, "ref", "Lo/Target;", null, null).visitEnd()
            visitEnd()
        }
        val registry = ConnectionRegistry().apply {
            setSource(
                FakeClassSource(
                    classes = listOf(ClassInfo.newBuilder().setName("a.B").build()),
                    bytecode = mapOf("a.B" to cw.toByteArray()),
                    capabilities = Capability.ALL,
                ),
                connectionKey = "test",
            )
        }
        val service = XrefService(registry, XrefIndexer())

        val users = service.findUsersOf("o.Target")

        assertEquals(1, users.size)
        assertEquals("a.B", users[0].callerClassFqn)
    }

    @Test
    fun `cache is reused across queries against the same source`() = runBlocking {
        // Track how many times listClasses gets called on the source. The
        // service should walk it once on first query, then reuse the cache.
        val a = bareCallerClass("a/B", calleeOwner = "a/C", calleeName = "target")
        val c = bareTargetClass("a/C", "target")
        var listCallCount = 0
        val source = FakeClassSource(
            classes = listOf(
                ClassInfo.newBuilder().setName("a.B").build(),
                ClassInfo.newBuilder().setName("a.C").build(),
            ),
            bytecode = mapOf("a.B" to a, "a.C" to c),
            capabilities = Capability.ALL,
            onListClasses = { listCallCount++ },
        )
        val registry = ConnectionRegistry().apply {
            setSource(source, connectionKey = "test")
        }
        val service = XrefService(registry, XrefIndexer())

        service.findCallersOf("a.C#target()V")
        service.findUsersOf("a.C")
        service.findCallersOf("a.C#target()V")

        assertEquals(1, listCallCount, "expected exactly one listClasses() call across three queries")
    }

    @Test
    fun `source change invalidates the cache`() = runBlocking {
        val a = bareCallerClass("a/B", calleeOwner = "a/C", calleeName = "target")
        val c = bareTargetClass("a/C", "target")
        val registry = ConnectionRegistry().apply {
            setSource(
                FakeClassSource(
                    classes = listOf(
                        ClassInfo.newBuilder().setName("a.B").build(),
                        ClassInfo.newBuilder().setName("a.C").build(),
                    ),
                    bytecode = mapOf("a.B" to a, "a.C" to c),
                    capabilities = Capability.ALL,
                ),
                connectionKey = "test",
            )
        }
        val service = XrefService(registry, XrefIndexer())

        service.findCallersOf("a.C#target()V")  // build #1
        assertEquals(XrefService.BuildStatus.Ready, service.buildStatus.value)

        // Switch sources — observer should reset status to Idle.
        registry.setSource(null, null)
        // Give the observer coroutine a moment to react.
        kotlinx.coroutines.delay(50)
        assertEquals(XrefService.BuildStatus.Idle, service.buildStatus.value)
    }

    @Test
    fun `empty source yields empty results without errors`() = runBlocking {
        val registry = ConnectionRegistry().apply {
            setSource(
                FakeClassSource(
                    classes = emptyList(),
                    bytecode = emptyMap(),
                    capabilities = Capability.ALL,
                ),
                connectionKey = "test",
            )
        }
        val service = XrefService(registry, XrefIndexer())

        assertTrue(service.findCallersOf("anything").isEmpty())
        assertTrue(service.findUsersOf("anything").isEmpty())
    }

    // ===== ASM helpers =====

    private fun bareCallerClass(
        internalName: String,
        calleeOwner: String,
        calleeName: String,
    ): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "m1", "()V", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, calleeOwner, calleeName, "()V", false)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun bareTargetClass(internalName: String, methodName: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()V", null, null)
        mv.visitCode()
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }
}
