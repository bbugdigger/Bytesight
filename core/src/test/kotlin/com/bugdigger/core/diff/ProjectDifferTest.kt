package com.bugdigger.core.diff

import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end tests over real ASM-built bytecode. Exercises both the
 * fingerprint extractor and the differ together — the integration is what
 * matters in practice; isolating the two would mostly test plumbing.
 */
class ProjectDifferTest {

    @Test
    fun `identical projects produce all-1pt0 matches`() {
        val project = mapOf(
            "a.B" to classWith("a/B", method("foo") { ldc("hello"); pop(); ret() }),
        )

        val result = ProjectDiffer().diff(project, project)

        assertEquals(1, result.matched.size)
        assertEquals(0, result.addedInNew.size)
        assertEquals(0, result.removedFromOld.size)
        val match = result.matched.first()
        assertTrue(match.confidence >= 0.99, "expected >= 0.99, got ${match.confidence}")
        assertEquals(match.old.methodName, match.new.methodName)
    }

    @Test
    fun `renamed-only method matches with high confidence`() {
        // Same body (same opcodes, same callees, same string), only the
        // method name differs. Confidence should stay very high.
        val old = mapOf(
            "a.B" to classWith(
                "a/B",
                method("originalName") {
                    ldc("hello")
                    invoke("java/lang/String", "length", "()I")
                    pop()
                    ret()
                },
            ),
        )
        val new = mapOf(
            "a.B" to classWith(
                "a/B",
                method("renamedToWhatever") {
                    ldc("hello")
                    invoke("java/lang/String", "length", "()I")
                    pop()
                    ret()
                },
            ),
        )

        val result = ProjectDiffer().diff(old, new)

        assertEquals(1, result.matched.size)
        val match = result.matched.first()
        assertTrue(
            match.confidence >= 0.85,
            "expected >= 0.85, got ${match.confidence}",
        )
        assertEquals("originalName", match.old.methodName)
        assertEquals("renamedToWhatever", match.new.methodName)
    }

    @Test
    fun `body-changed method matches but at lower confidence`() {
        // Same name + signature, but the body differs both in opcode shape
        // (extra arithmetic) and in the string constant. With the v1 weights
        // string-Jaccard alone drops the score by 0.15 below the same-body
        // ceiling, plus the histogram cosine slips a bit.
        val old = mapOf(
            "a.B" to classWith(
                "a/B",
                method("foo") { ldc("OLD"); pop(); iconst1(); pop(); ret() },
            ),
        )
        val new = mapOf(
            "a.B" to classWith(
                "a/B",
                method("foo") {
                    ldc("NEW")
                    pop()
                    iconst1(); iconst1(); add()
                    pop()
                    ret()
                },
            ),
        )

        val result = ProjectDiffer().diff(old, new)

        assertEquals(1, result.matched.size)
        val match = result.matched.first()
        assertTrue(
            match.confidence in 0.5..0.95,
            "expected confidence in (0.5, 0.95), got ${match.confidence}",
        )
    }

    @Test
    fun `purely added and removed methods land in their lists`() {
        val old = mapOf("a.B" to classWith("a/B", method("kept") { ret() }))
        val new = mapOf(
            "a.B" to classWith(
                "a/B",
                method("kept") { ret() },
                method("newOne") { iconst1(); pop(); ret() },
            ),
        )

        val result = ProjectDiffer().diff(old, new)

        assertEquals(setOf("kept"), result.matched.map { it.new.methodName }.toSet())
        assertEquals(setOf("newOne"), result.addedInNew.map { it.methodName }.toSet())
        assertEquals(emptyList<MethodFingerprint>(), result.removedFromOld)
    }

    // ===== ASM helpers =====

    private fun classWith(internalName: String, vararg methods: MethodSpec): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        for (spec in methods) {
            val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, spec.name, "()V", null, null)
            mv.visitCode()
            spec.body.invoke(MethodBody(mv))
            mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun method(name: String, body: MethodBody.() -> Unit): MethodSpec =
        MethodSpec(name, body)

    private data class MethodSpec(val name: String, val body: MethodBody.() -> Unit)

    private class MethodBody(private val mv: org.objectweb.asm.MethodVisitor) {
        fun ldc(s: String) { mv.visitLdcInsn(s) }
        fun invoke(owner: String, name: String, desc: String) {
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, name, desc, false)
            // INVOKEVIRTUAL of length():I leaves an int on stack; pop it so
            // body shape stays balanced. Tests are not running this code so
            // strict stack balance isn't required, but keep it close to real.
            mv.visitInsn(Opcodes.POP)
        }
        fun pop() { mv.visitInsn(Opcodes.POP) }
        fun iconst1() { mv.visitInsn(Opcodes.ICONST_1) }
        fun add() { mv.visitInsn(Opcodes.IADD) }
        fun ret() { /* RETURN added by classWith() */ }
    }
}
