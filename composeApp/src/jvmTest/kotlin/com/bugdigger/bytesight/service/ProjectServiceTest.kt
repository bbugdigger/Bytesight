package com.bugdigger.bytesight.service

import com.bugdigger.bytesight.debugger.DebuggerState
import com.bugdigger.bytesight.source.Capability
import com.bugdigger.bytesight.source.FakeClassSource
import com.bugdigger.core.analysis.StaticHierarchyExtractor
import com.bugdigger.protocol.ClassInfo
import com.bugdigger.protocol.MethodBreakpointMode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectServiceTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun makeClass(internal: String): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null)
        cw.visitEnd()
        return cw.toByteArray()
    }

    @Test
    fun `save then load preserves classes, renames, comments, breakpoints`(@TempDir dir: Path) = runTest {
        // Arrange: a populated session
        val registry = ConnectionRegistry()
        val rename = RenameStore().apply { rename("com.example.a", "Renamed") }
        val comment = CommentStore().apply {
            setBlockComment(MethodKey("a.B", "foo", "()V"), "blk_1", "hi")
        }
        val debug = DebuggerState().apply {
            addBreakpoint(
                DebuggerState.UiBreakpoint(
                    id = "x", className = "a.B", methodName = "foo", methodSignature = "()V",
                    displayLine = 5, mode = MethodBreakpointMode.METHOD_BP_ENTRY, enabled = true,
                ),
            )
        }
        val classBytes = mapOf(
            "com.example.Foo" to makeClass("com/example/Foo"),
            "a.B" to makeClass("a/B"),
        )
        val classInfos = listOf(
            ClassInfo.newBuilder().setName("com.example.Foo").build(),
            ClassInfo.newBuilder().setName("a.B").build(),
        )
        registry.setSource(
            FakeClassSource(classInfos, classBytes, capabilities = Capability.ALL),
            connectionKey = "live",
        )

        val service = ProjectService(
            registry, rename, comment, debug,
            StaticHierarchyExtractor(), json,
            bytesightVersion = "test",
        )

        val out = dir.resolve("p.bts").toFile()

        // Act: save
        service.saveAs(out, displayName = "Test").getOrThrow()

        // Wipe stores to simulate a fresh app start.
        rename.clearAll()
        comment.restore("[]", json)
        debug.setBreakpoints(emptyList())
        registry.setSource(null, null)

        // Act: load
        service.load(out).getOrThrow()

        // Assert: stores rehydrated
        assertEquals("Renamed", rename.renameMap.value["com.example.a"])
        assertEquals(
            "hi",
            comment.commentsFor(MethodKey("a.B", "foo", "()V")).blockLevel["blk_1"],
        )
        assertEquals(1, debug.breakpoints.value.size)
        assertEquals("x", debug.breakpoints.value.first().id)

        // Assert: source was installed and is now static-only
        val installed = registry.classSource.value
        assertTrue(installed != null)
        assertEquals(Capability.STATIC_ONLY, installed!!.capabilities)
        val names = installed.listClasses().getOrThrow().map { it.name }.toSet()
        assertEquals(setOf("com.example.Foo", "a.B"), names)

        // Close the loaded source so Windows releases the .bts file lock and
        // the @TempDir cleanup can delete it.
        registry.setSource(null, null)
    }

    @Test
    fun `saveAs fails when no active source`(@TempDir dir: Path) = runTest {
        val service = ProjectService(
            ConnectionRegistry(), RenameStore(), CommentStore(), DebuggerState(),
            StaticHierarchyExtractor(), json, "test",
        )
        val out = dir.resolve("nope.bts").toFile()
        val result = service.saveAs(out, "x")
        assertTrue(result.isFailure)
    }
}
