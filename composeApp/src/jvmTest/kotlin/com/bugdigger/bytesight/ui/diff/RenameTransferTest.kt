package com.bugdigger.bytesight.ui.diff

import com.bugdigger.core.diff.ConfidenceFeatures
import com.bugdigger.core.diff.MatchedPair
import com.bugdigger.core.diff.MethodFingerprint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RenameTransferTest {

    @Test
    fun `copies method rename from old to new keyed by new fqn`() {
        val old = mapOf("a.B#orig()V" to "doImportantThing")
        val pair = pair(
            old = fp("a.B", "orig", "()V"),
            new = fp("a.B", "x", "()V"),
        )

        val updated = RenameTransfer.applyMethodRename(old, emptyMap(), pair)

        assertEquals("doImportantThing", updated["a.B#x()V"])
    }

    @Test
    fun `also copies class rename when class FQN differs across versions`() {
        val old = mapOf(
            "a.B" to "PaymentService",
            "a.B#orig()V" to "doImportantThing",
        )
        val pair = pair(
            old = fp("a.B", "orig", "()V"),
            new = fp("a.C", "x", "()V"),
        )

        val updated = RenameTransfer.applyMethodRename(old, emptyMap(), pair)

        assertEquals("PaymentService", updated["a.C"])
        assertEquals("doImportantThing", updated["a.C#x()V"])
    }

    @Test
    fun `noop when old has no rename for this match`() {
        val pair = pair(fp("a.B", "f", "()V"), fp("a.B", "g", "()V"))
        val updated = RenameTransfer.applyMethodRename(emptyMap(), emptyMap(), pair)
        assertEquals(emptyMap<String, String>(), updated)
    }

    @Test
    fun `existing entries on the new side are preserved`() {
        val old = mapOf("a.B#orig()V" to "doImportantThing")
        val newExisting = mapOf("a.B#unrelated()V" to "alreadyHere")
        val pair = pair(fp("a.B", "orig", "()V"), fp("a.B", "x", "()V"))

        val updated = RenameTransfer.applyMethodRename(old, newExisting, pair)

        assertEquals("alreadyHere", updated["a.B#unrelated()V"])
        assertEquals("doImportantThing", updated["a.B#x()V"])
    }

    private fun fp(cls: String, name: String, desc: String) = MethodFingerprint(
        className = cls, methodName = name, descriptor = desc,
        returnType = "void", parameterTypes = emptyList(),
        opcodeHistogram = IntArray(MethodFingerprint.OPCODE_HISTOGRAM_SIZE),
        calleeFqns = emptySet(), stringConstants = emptySet(), instructionCount = 0,
    )

    private fun pair(old: MethodFingerprint, new: MethodFingerprint) = MatchedPair(
        old = old, new = new, confidence = 1.0,
        features = ConfidenceFeatures(1.0, 1.0, 1.0, 1.0),
    )
}
