package com.bugdigger.bytesight.source

import com.bugdigger.core.analysis.StaticHierarchyExtractor
import com.bugdigger.core.source.JarReader
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.outputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JarClassSourceTest {

    @Test
    fun `declares STATIC_ONLY capability`(@TempDir dir: Path) {
        val jar = makeTinyJar(dir)
        val source = JarClassSource(jar.toFile(), JarReader(), StaticHierarchyExtractor())
        try {
            assertEquals(Capability.STATIC_ONLY, source.capabilities)
        } finally {
            source.close()
        }
    }

    @Test
    fun `displayName uses jar file name`(@TempDir dir: Path) {
        val jar = makeTinyJar(dir)
        val source = JarClassSource(jar.toFile(), JarReader(), StaticHierarchyExtractor())
        try {
            assertTrue(source.displayName.contains(jar.fileName.toString()))
        } finally {
            source.close()
        }
    }

    @Test
    fun `listClasses returns ClassInfo for every class entry`(@TempDir dir: Path) = runTest {
        val jar = makeTinyJar(dir) // contains a.B and a.C
        val source = JarClassSource(jar.toFile(), JarReader(), StaticHierarchyExtractor())
        try {
            val result = source.listClasses()
            assertTrue(result.isSuccess)
            val names = result.getOrNull()!!.map { it.name }.toSet()
            assertEquals(setOf("a.B", "a.C"), names)
        } finally {
            source.close()
        }
    }

    @Test
    fun `getBytecode returns the raw class bytes`(@TempDir dir: Path) = runTest {
        val jar = makeTinyJar(dir)
        val source = JarClassSource(jar.toFile(), JarReader(), StaticHierarchyExtractor())
        try {
            val bytes = source.getBytecode("a.B").getOrNull()!!
            // First 4 bytes of any .class are the magic number 0xCAFEBABE.
            assertEquals(0xCAFEBABE.toInt(), java.nio.ByteBuffer.wrap(bytes).int)
        } finally {
            source.close()
        }
    }

    @Test
    fun `getBytecode returns failure for unknown class`(@TempDir dir: Path) = runTest {
        val jar = makeTinyJar(dir)
        val source = JarClassSource(jar.toFile(), JarReader(), StaticHierarchyExtractor())
        try {
            val result = source.getBytecode("a.Missing")
            assertTrue(result.isFailure)
        } finally {
            source.close()
        }
    }

    private fun makeTinyJar(dir: Path): Path {
        val jar = dir.resolve("tiny.jar")
        jar.outputStream().use { os ->
            JarOutputStream(os).use { out ->
                listOf("a/B", "a/C").forEach { internalName ->
                    val cw = ClassWriter(0)
                    cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
                    cw.visitEnd()
                    out.putNextEntry(JarEntry("$internalName.class"))
                    out.write(cw.toByteArray())
                    out.closeEntry()
                }
            }
        }
        return jar
    }
}
