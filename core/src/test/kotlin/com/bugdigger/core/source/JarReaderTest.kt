package com.bugdigger.core.source

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

class JarReaderTest {

    @Test
    fun `enumerates class entries with FQN keys`(@TempDir dir: Path) {
        val jar = dir.resolve("test.jar")
        val foo = makeClassBytes("com/example/Foo")
        val bar = makeClassBytes("com/example/Bar")
        writeJar(jar, mapOf(
            "com/example/Foo.class" to foo,
            "com/example/Bar.class" to bar,
        ))

        val entries = JarReader().read(jar.toFile())

        assertEquals(setOf("com.example.Foo", "com.example.Bar"), entries.keys)
        assertTrue(entries["com.example.Foo"]!!.contentEquals(foo))
    }

    @Test
    fun `skips non-class entries`(@TempDir dir: Path) {
        val jar = dir.resolve("test.jar")
        writeJar(jar, mapOf(
            "com/example/Foo.class" to makeClassBytes("com/example/Foo"),
            "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".toByteArray(),
            "resources/data.txt" to "hello".toByteArray(),
        ))

        val entries = JarReader().read(jar.toFile())

        assertEquals(setOf("com.example.Foo"), entries.keys)
    }

    @Test
    fun `keeps inner classes and skips module-info`(@TempDir dir: Path) {
        val jar = dir.resolve("test.jar")
        writeJar(jar, mapOf(
            "com/example/Foo.class" to makeClassBytes("com/example/Foo"),
            "com/example/Foo\$Inner.class" to makeClassBytes("com/example/Foo\$Inner"),
            "module-info.class" to makeClassBytes("module-info"),
        ))

        val entries = JarReader().read(jar.toFile())

        assertTrue("com.example.Foo" in entries.keys)
        assertTrue("com.example.Foo\$Inner" in entries.keys)
        assertTrue("module-info" !in entries.keys)
    }

    private fun makeClassBytes(internalName: String): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun writeJar(path: Path, entries: Map<String, ByteArray>) {
        path.outputStream().use { os ->
            JarOutputStream(os).use { jar ->
                for ((name, bytes) in entries) {
                    jar.putNextEntry(JarEntry(name))
                    jar.write(bytes)
                    jar.closeEntry()
                }
            }
        }
    }
}
