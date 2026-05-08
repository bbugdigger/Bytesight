package com.bugdigger.bytesight.source

import com.bugdigger.core.analysis.StaticHierarchyExtractor
import com.bugdigger.core.project.BtsProjectFile
import com.bugdigger.core.project.ProjectManifest
import com.bugdigger.core.project.SourceKind
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BtsProjectClassSourceTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun makeClass(internalName: String): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        cw.visitEnd()
        return cw.toByteArray()
    }

    @Test
    fun `lists classes and returns bytecode`(@TempDir dir: Path) = runTest {
        val bts = dir.resolve("p.bts").toFile()
        val foo = makeClass("com/example/Foo")
        BtsProjectFile.write(
            bts,
            ProjectManifest("p", SourceKind.JAR, null, 0L, "0.0"),
            mapOf("com.example.Foo" to foo),
            emptyMap(),
            json,
        )

        BtsProjectClassSource.open(bts, StaticHierarchyExtractor(), json).use { source ->
            assertEquals(Capability.STATIC_ONLY, source.capabilities)
            assertTrue(source.displayName.contains("p"))

            val list = source.listClasses().getOrNull()!!
            assertEquals(setOf("com.example.Foo"), list.map { it.name }.toSet())

            assertTrue(source.getBytecode("com.example.Foo").getOrNull()!!.contentEquals(foo))
        }
    }

    @Test
    fun `getBytecode returns failure for unknown class`(@TempDir dir: Path) = runTest {
        val bts = dir.resolve("p.bts").toFile()
        BtsProjectFile.write(
            bts,
            ProjectManifest("p", SourceKind.JAR, null, 0L, "0.0"),
            mapOf("a.B" to makeClass("a/B")),
            emptyMap(),
            json,
        )

        BtsProjectClassSource.open(bts, StaticHierarchyExtractor(), json).use { source ->
            val result = source.getBytecode("a.Missing")
            assertTrue(result.isFailure)
        }
    }

    private inline fun BtsProjectClassSource.use(block: (BtsProjectClassSource) -> Unit) {
        try { block(this) } finally { close() }
    }
}
