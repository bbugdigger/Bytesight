package com.bugdigger.bytesight.ui.diff

import com.bugdigger.core.analysis.StaticHierarchyExtractor
import com.bugdigger.core.diff.ProjectDiffer
import com.bugdigger.core.project.BtsProjectFile
import com.bugdigger.core.project.ProjectManifest
import com.bugdigger.core.project.SourceKind
import com.bugdigger.core.source.JarReader
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * VM tests use real time and real dispatchers — the VM internally fans out to
 * [kotlinx.coroutines.Dispatchers.IO] / [kotlinx.coroutines.Dispatchers.Default],
 * which `runTest` does not control, so a virtual-clock test would race past the
 * actual disk + ASM work. We poll the StateFlow with a generous timeout instead.
 */
class BytecodeDiffViewModelTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `runs diff on two equal jars and exposes 1pt0 matches`(@TempDir dir: Path) {
        val classBytes = mapOf("a.B" to bareClass("a/B"))
        val left = makeJar(dir.resolve("v1.jar").toFile(), classBytes)
        val right = makeJar(dir.resolve("v2.jar").toFile(), classBytes)

        val vm = newVm()
        try {
            vm.openLeft(left)
            vm.openRight(right)
            waitForMatches(vm)

            val state = vm.uiState.value
            assertTrue(state.matchedPairs.isNotEmpty(), "expected at least one match for identical jars")
            assertTrue(state.matchedPairs.first().confidence >= 0.99)
            assertEquals(0, state.addedInNew.size)
            assertEquals(0, state.removedFromOld.size)
        } finally {
            vm.dispose()
        }
    }

    @Test
    fun `loads left renames from a bts file`(@TempDir dir: Path) {
        val out = dir.resolve("p.bts").toFile()
        BtsProjectFile.write(
            out,
            ProjectManifest("p", SourceKind.BTS, null, 0L, "0.0"),
            mapOf("a.B" to bareClass("a/B")),
            mapOf("renames.json" to """{"a.B":"PaymentService"}"""),
            json,
        )

        val vm = newVm()
        try {
            vm.openLeft(out)
            waitForLeftRenames(vm)
            assertEquals("PaymentService", vm.uiState.value.leftRenames["a.B"])
        } finally {
            vm.dispose()
        }
    }

    @Test
    fun `applyOldRename mutates rightRenames`(@TempDir dir: Path) {
        val left = dir.resolve("old.bts").toFile()
        BtsProjectFile.write(
            left,
            ProjectManifest("old", SourceKind.BTS, null, 0L, "0.0"),
            mapOf("a.B" to bareClass("a/B")),
            mapOf("renames.json" to """{"a.B#foo()V":"doImportantThing"}"""),
            json,
        )
        val right = makeJar(
            dir.resolve("new.jar").toFile(),
            mapOf("a.B" to bareClass("a/B")),
        )

        val vm = newVm()
        try {
            vm.openLeft(left)
            vm.openRight(right)
            waitForMatches(vm)

            val pair = vm.uiState.value.matchedPairs
                .firstOrNull { it.old.methodName == "foo" }
            assertNotNull(pair, "expected the foo()V method to match across both projects")
            vm.applyOldRename(pair!!)
            assertEquals("doImportantThing", vm.uiState.value.rightRenames["a.B#foo()V"])
        } finally {
            vm.dispose()
        }
    }

    private fun newVm(): BytecodeDiffViewModel = BytecodeDiffViewModel(
        differ = ProjectDiffer(),
        jarReader = JarReader(),
        hierarchyExtractor = StaticHierarchyExtractor(),
        json = json,
    )

    /**
     * Wait until the VM has produced a result. We can't rely on `isRunning`
     * alone because `openLeft/openRight` launch coroutines lazily — there's
     * a window where `isRunning` is still `false` from the prior state
     * before the launched job dispatches. We poll for any settled output
     * (a label, renames, matched pairs, or an error) and require
     * `isRunning == false` at the end.
     */
    private fun waitUntilSettled(
        vm: BytecodeDiffViewModel,
        condition: (BytecodeDiffUiState) -> Boolean,
        timeoutMs: Long = 5_000L,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = vm.uiState.value
            if (!s.isRunning && condition(s)) return
            Thread.sleep(20)
        }
        error("VM didn't settle after ${timeoutMs}ms; uiState=${vm.uiState.value}")
    }

    private fun waitForMatches(vm: BytecodeDiffViewModel) =
        waitUntilSettled(vm, condition = { it.matchedPairs.isNotEmpty() || it.error != null })

    private fun waitForLeftRenames(vm: BytecodeDiffViewModel) =
        waitUntilSettled(vm, condition = { it.leftLabel.isNotEmpty() || it.error != null })

    private fun bareClass(internal: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "foo", "()V", null, null)
        mv.visitCode()
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun makeJar(file: java.io.File, classes: Map<String, ByteArray>): java.io.File {
        java.util.jar.JarOutputStream(file.outputStream()).use { jar ->
            for ((fqn, bytes) in classes) {
                val entryName = fqn.replace('.', '/') + ".class"
                jar.putNextEntry(java.util.jar.JarEntry(entryName))
                jar.write(bytes)
                jar.closeEntry()
            }
        }
        return file
    }
}
