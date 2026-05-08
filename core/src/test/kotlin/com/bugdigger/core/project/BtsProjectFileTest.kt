package com.bugdigger.core.project

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BtsProjectFileTest {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private fun makeClass(internalName: String): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        cw.visitEnd()
        return cw.toByteArray()
    }

    @Test
    fun `round-trips manifest, classes, and json entries`(@TempDir dir: Path) {
        val out = dir.resolve("project.bts").toFile()
        val manifest = ProjectManifest(
            displayName = "test",
            sourceKind = SourceKind.JAR,
            originalPath = "/path/to/sample.jar",
            createdAt = 12345L,
            bytesightVersion = "0.1.0",
        )
        val classBytes = mapOf(
            "com.example.Foo" to makeClass("com/example/Foo"),
            "com.example.Bar" to makeClass("com/example/Bar"),
        )
        val jsonEntries = mapOf(
            "renames.json" to """{"com.example.a":"UserService"}""",
            "comments.json" to "[]",
        )

        BtsProjectFile.write(out, manifest, classBytes, jsonEntries, json)

        BtsProjectFile.open(out).use { read ->
            assertEquals(manifest, read.readManifest(json))
            assertEquals(setOf("com.example.Foo", "com.example.Bar"), read.listClassEntries())
            assertTrue(read.readClass("com.example.Foo")!!.contentEquals(classBytes["com.example.Foo"]!!))
            assertEquals(jsonEntries["renames.json"], read.readJsonEntry("renames.json"))
            assertEquals(jsonEntries["comments.json"], read.readJsonEntry("comments.json"))
            assertNull(read.readJsonEntry("missing.json"))
        }
    }

    @Test
    fun `write rejects empty class map`(@TempDir dir: Path) {
        val out = dir.resolve("bad.bts").toFile()
        val manifest = ProjectManifest(
            displayName = "v1",
            sourceKind = SourceKind.JAR,
            originalPath = null,
            createdAt = 1L,
            bytesightVersion = "0.0",
        )
        val ex = runCatching {
            BtsProjectFile.write(out, manifest, emptyMap(), emptyMap(), json)
        }.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex is IllegalArgumentException)
    }

    @Test
    fun `readManifest fails on missing manifest`(@TempDir dir: Path) {
        val out = dir.resolve("bad.bts").toFile()
        java.util.zip.ZipOutputStream(out.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("classes/a/B.class"))
            zip.write(makeClass("a/B"))
            zip.closeEntry()
        }

        BtsProjectFile.open(out).use { read ->
            val ex = runCatching { read.readManifest(json) }.exceptionOrNull()
            assertNotNull(ex)
            assertTrue(ex is IllegalStateException)
        }
    }

    @Test
    fun `class entries are stored under classes prefix and read back by FQN`(@TempDir dir: Path) {
        val out = dir.resolve("p.bts").toFile()
        BtsProjectFile.write(out,
            ProjectManifest("p", SourceKind.JAR, null, 0L, "0.0"),
            mapOf("com.example.deep.Nested\$Inner" to makeClass("com/example/deep/Nested\$Inner")),
            emptyMap(), json)

        BtsProjectFile.open(out).use { read ->
            assertTrue("com.example.deep.Nested\$Inner" in read.listClassEntries())
            assertNotNull(read.readClass("com.example.deep.Nested\$Inner"))
        }
    }
}
