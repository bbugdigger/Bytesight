# Multi-source — Step 4: BytecodeDiff — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a new `BYTECODE_DIFF` tab. The user picks two `ClassSource` artifacts (two `.bts` files, or one `.bts` and one `.jar`, etc.). Bytesight pairs methods across the two sources and ranks them by a confidence score. The user sees a side-by-side disassembly and decompiled view of any pair, and can transfer renames from the old project's `RenameStore` into the new one in a single click — solving the obfuscation-survives-update workflow.

**Architecture:** Pure analysis primitives live in `core` (`MethodFingerprint`, `Similarity`, `ProjectDiffer`). They take two `Map<className, ByteArray>` inputs and produce a structured `DiffResult`. The UI in `composeApp` (`BytecodeDiffViewModel`, `BytecodeDiffScreen`) loads two `ClassSource`s via the existing infrastructure (`JarClassSource`/`BtsProjectClassSource` from prior steps), feeds them to `ProjectDiffer`, and renders the results. Rename transfer is a small action that mutates the **new** project's `RenameStore` based on the matched method pair.

**Algorithm (v1, simple multi-feature):** For each pair of methods with the same arity, compute four cheap features:
- `s_op` — cosine similarity of opcode histograms (length 256)
- `s_call` — Jaccard similarity of called-method FQN sets
- `s_sig` — exact-match indicator on return type + parameter types (1.0/0.5/0.0)
- `s_str` — Jaccard on extracted string constants
- `confidence = 0.40·s_op + 0.30·s_call + 0.15·s_sig + 0.15·s_str` (defaults; tunable later)

Greedy match: take highest-confidence pair, lock both, repeat. Threshold 0.4: below = unmatched (likely added or removed). Two-stage: pre-bucket by class name + member-set similarity to keep pairings tractable on large JARs.

**Tech Stack:** Kotlin, ASM 9.7.1 (already in `core`). No new dependencies.

**Prerequisites:** Steps 1, 2, and 3. The diff feeds on `ClassSource`s, and the most useful workflow is two `.bts` files — so Save/Load must exist.

---

## File Structure

**New files (`core`):**
- `core/src/main/kotlin/com/bugdigger/core/diff/MethodFingerprint.kt` — feature struct + extractor
- `core/src/main/kotlin/com/bugdigger/core/diff/Similarity.kt` — cosine, Jaccard helpers
- `core/src/main/kotlin/com/bugdigger/core/diff/ProjectDiffer.kt` — matching algorithm
- `core/src/main/kotlin/com/bugdigger/core/diff/DiffResult.kt` — output data classes
- `core/src/test/kotlin/com/bugdigger/core/diff/MethodFingerprintTest.kt`
- `core/src/test/kotlin/com/bugdigger/core/diff/SimilarityTest.kt`
- `core/src/test/kotlin/com/bugdigger/core/diff/ProjectDifferTest.kt`

**New files (`composeApp`):**
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/diff/BytecodeDiffScreen.kt`
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/diff/BytecodeDiffViewModel.kt`
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/diff/RenameTransfer.kt`
- `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/ui/diff/BytecodeDiffViewModelTest.kt`
- `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/ui/diff/RenameTransferTest.kt`

**Modified files:**
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/navigation/Navigation.kt` — add `BYTECODE_DIFF`
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/navigation/Sidebar.kt` — always-enabled (works without an active source)
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/App.kt` — route to the new screen
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/di/AppModule.kt` — register `ProjectDiffer`, `BytecodeDiffViewModel`

**Reused:**
- `core/.../analysis/BytecodeDisassembler` — opcode list per method
- `core/.../analysis/ConstantExtractor` — string constants per method
- `core/.../decompiler/VineflowerDecompiler` — for the side-by-side decompiled view
- `composeApp/.../source/{JarClassSource, BtsProjectClassSource, ApkClassSource}` — to load both sides

**Test commands:**
- `.\gradlew.bat :core:test --tests "com.bugdigger.core.diff.*"`
- `.\gradlew.bat :composeApp:jvmTest --tests "com.bugdigger.bytesight.ui.diff.*"`

---

## Task 1: Add `BYTECODE_DIFF` to navigation

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/navigation/Navigation.kt`
- Modify: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/navigation/Sidebar.kt`

- [ ] **Step 1: Add the enum entry**

In `Navigation.kt`'s `Screen` enum:

```kotlin
enum class Screen(val title: String, val icon: String) {
    ATTACH("Attach", "▶"),
    CLASS_BROWSER("Classes", "📦"),
    HIERARCHY("Hierarchy", "🌳"),
    INSPECTOR("Inspector", "🔍"),
    STRINGS("Strings", "📝"),
    TRACE("Trace", "📊"),
    HEAP("Heap", "💾"),
    DEBUGGER("Debugger", "🐞"),
    BYTECODE_DIFF("Diff", "🔀"),   // NEW
    AI("AI", "✨"),
    SETTINGS("Settings", "⚙"),
}
```

- [ ] **Step 2: Update `Sidebar.isScreenEnabled`**

Diff works without an active source — it loads its own two `.bts`/`.jar` files internally.

```kotlin
private fun isScreenEnabled(screen: Screen, caps: Set<Capability>): Boolean = when (screen) {
    Screen.ATTACH, Screen.AI, Screen.SETTINGS, Screen.BYTECODE_DIFF -> true
    Screen.CLASS_BROWSER, Screen.HIERARCHY,
    Screen.INSPECTOR, Screen.STRINGS -> Capability.STATIC_ANALYSIS in caps
    Screen.TRACE -> Capability.LIVE_TRACE in caps
    Screen.HEAP -> Capability.LIVE_HEAP in caps
    Screen.DEBUGGER -> Capability.LIVE_DEBUG in caps
}
```

- [ ] **Step 3: Compile**

```bash
.\gradlew.bat :composeApp:compileKotlinJvm
```

(The screen is wired in Task 8.)

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/navigation/
git commit -m "feat(nav): add BYTECODE_DIFF screen entry"
```

---

## Task 2: `MethodFingerprint` data class + extraction (TDD)

**Files:**
- Create: `core/src/test/kotlin/com/bugdigger/core/diff/MethodFingerprintTest.kt`
- Create: `core/src/main/kotlin/com/bugdigger/core/diff/MethodFingerprint.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.bugdigger.core.diff

import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MethodFingerprintTest {

    @Test
    fun `extracts fingerprint per method including opcodes, callees, strings`() {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "a/B", null, "java/lang/Object", null)

        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "foo", "(Ljava/lang/String;)I", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 1)                                              // ALOAD = 25
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false)
        mv.visitLdcInsn("hello")
        mv.visitInsn(Opcodes.POP)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()

        val fps = MethodFingerprint.extractAll(className = "a.B", bytecode = cw.toByteArray())
        val foo = fps.first { it.methodName == "foo" }

        // Opcode histogram populated for the opcodes we used
        assertTrue(foo.opcodeHistogram[Opcodes.ALOAD] >= 1)
        assertTrue(foo.opcodeHistogram[Opcodes.INVOKEVIRTUAL] >= 1)
        assertTrue(foo.opcodeHistogram[Opcodes.LDC] >= 1)
        assertTrue(foo.opcodeHistogram[Opcodes.IRETURN] >= 1)

        // Callees
        assertTrue("java.lang.String#length()I" in foo.calleeFqns)

        // String constants
        assertTrue("hello" in foo.stringConstants)

        // Signature
        assertEquals("int", foo.returnType)
        assertEquals(listOf("java.lang.String"), foo.parameterTypes)
        assertEquals("(Ljava/lang/String;)I", foo.descriptor)
    }
}
```

- [ ] **Step 2: Run — confirm fail**

```bash
.\gradlew.bat :core:test --tests "com.bugdigger.core.diff.MethodFingerprintTest"
```

- [ ] **Step 3: Implement**

Create `core/src/main/kotlin/com/bugdigger/core/diff/MethodFingerprint.kt`:

```kotlin
package com.bugdigger.core.diff

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Lightweight per-method feature vector for bytecode similarity.
 *
 * Designed to be cheap to compute (single ASM pass per class) and small to
 * compare (256-int histogram + ~tens of strings). Adequate for the v1
 * heuristic-based [ProjectDiffer]; a future v2 with CFG/edge similarity
 * would extend this struct (or wrap it).
 */
data class MethodFingerprint(
    val className: String,
    val methodName: String,
    val descriptor: String,
    val returnType: String,
    val parameterTypes: List<String>,
    val opcodeHistogram: IntArray,                   // length 256
    val calleeFqns: Set<String>,                     // "owner#name(desc)"
    val stringConstants: Set<String>,
    val instructionCount: Int,
) {
    val parameterArity: Int get() = parameterTypes.size

    /** Compact key for matching attempts: class+method+descriptor. */
    val key: String get() = "$className#$methodName$descriptor"

    companion object {
        const val OPCODE_HISTOGRAM_SIZE = 256

        fun extractAll(className: String, bytecode: ByteArray): List<MethodFingerprint> {
            val out = mutableListOf<MethodFingerprint>()
            val reader = ClassReader(bytecode)
            reader.accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor = FingerprintCollector(className, name, descriptor, out)
            }, ClassReader.SKIP_FRAMES or ClassReader.SKIP_DEBUG)
            return out
        }
    }
}

private class FingerprintCollector(
    private val className: String,
    private val methodName: String,
    private val descriptor: String,
    private val out: MutableList<MethodFingerprint>,
) : MethodVisitor(Opcodes.ASM9) {

    private val histogram = IntArray(MethodFingerprint.OPCODE_HISTOGRAM_SIZE)
    private val callees = mutableSetOf<String>()
    private val strings = mutableSetOf<String>()
    private var instructions = 0

    override fun visitInsn(opcode: Int) { count(opcode) }
    override fun visitIntInsn(opcode: Int, operand: Int) { count(opcode) }
    override fun visitVarInsn(opcode: Int, varIndex: Int) { count(opcode) }
    override fun visitTypeInsn(opcode: Int, type: String) { count(opcode) }
    override fun visitFieldInsn(opcode: Int, owner: String, name: String, desc: String) { count(opcode) }
    override fun visitJumpInsn(opcode: Int, label: org.objectweb.asm.Label) { count(opcode) }
    override fun visitIincInsn(varIndex: Int, increment: Int) { count(Opcodes.IINC) }
    override fun visitTableSwitchInsn(min: Int, max: Int, dflt: org.objectweb.asm.Label, vararg labels: org.objectweb.asm.Label) {
        count(Opcodes.TABLESWITCH)
    }
    override fun visitLookupSwitchInsn(dflt: org.objectweb.asm.Label, keys: IntArray, labels: Array<out org.objectweb.asm.Label>) {
        count(Opcodes.LOOKUPSWITCH)
    }
    override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) { count(Opcodes.MULTIANEWARRAY) }

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, isInterface: Boolean) {
        count(opcode)
        callees.add("${owner.replace('/', '.')}#$name$desc")
    }

    override fun visitInvokeDynamicInsn(name: String, descriptor: String, bsm: org.objectweb.asm.Handle, vararg bsmArgs: Any?) {
        count(Opcodes.INVOKEDYNAMIC)
        callees.add("indy#$name$descriptor")
    }

    override fun visitLdcInsn(value: Any?) {
        count(Opcodes.LDC)
        if (value is String) strings.add(value)
    }

    override fun visitEnd() {
        out.add(MethodFingerprint(
            className = className,
            methodName = methodName,
            descriptor = descriptor,
            returnType = Type.getReturnType(descriptor).className,
            parameterTypes = Type.getArgumentTypes(descriptor).map { it.className },
            opcodeHistogram = histogram,
            calleeFqns = callees.toSet(),
            stringConstants = strings.toSet(),
            instructionCount = instructions,
        ))
    }

    private fun count(opcode: Int) {
        if (opcode in 0 until MethodFingerprint.OPCODE_HISTOGRAM_SIZE) histogram[opcode]++
        instructions++
    }
}
```

- [ ] **Step 4: Run + commit**

```bash
.\gradlew.bat :core:test --tests "com.bugdigger.core.diff.MethodFingerprintTest"
git add core/src/main/kotlin/com/bugdigger/core/diff/MethodFingerprint.kt core/src/test/kotlin/com/bugdigger/core/diff/MethodFingerprintTest.kt
git commit -m "feat(core/diff): add MethodFingerprint extraction"
```

---

## Task 3: `Similarity` helpers — cosine and Jaccard (TDD)

**Files:**
- Create: `core/src/test/kotlin/com/bugdigger/core/diff/SimilarityTest.kt`
- Create: `core/src/main/kotlin/com/bugdigger/core/diff/Similarity.kt`

- [ ] **Step 1: Test**

```kotlin
package com.bugdigger.core.diff

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimilarityTest {

    @Test
    fun `cosine returns 1 for identical vectors`() {
        val v = intArrayOf(1, 2, 3, 0, 5)
        assertEquals(1.0, Similarity.cosine(v, v), 1e-9)
    }

    @Test
    fun `cosine returns 0 for orthogonal vectors`() {
        assertEquals(0.0, Similarity.cosine(intArrayOf(1, 0, 0), intArrayOf(0, 1, 0)), 1e-9)
    }

    @Test
    fun `cosine returns 0 for either zero vector`() {
        assertEquals(0.0, Similarity.cosine(intArrayOf(0, 0, 0), intArrayOf(1, 1, 1)), 1e-9)
        assertEquals(0.0, Similarity.cosine(intArrayOf(1, 1, 1), intArrayOf(0, 0, 0)), 1e-9)
    }

    @Test
    fun `jaccard returns 1 for identical sets`() {
        val s = setOf("a", "b", "c")
        assertEquals(1.0, Similarity.jaccard(s, s), 1e-9)
    }

    @Test
    fun `jaccard returns 0 for disjoint sets`() {
        assertEquals(0.0, Similarity.jaccard(setOf("a"), setOf("b")), 1e-9)
    }

    @Test
    fun `jaccard returns 1 for two empty sets`() {
        assertEquals(1.0, Similarity.jaccard(emptySet<String>(), emptySet()), 1e-9)
    }

    @Test
    fun `jaccard correctly computes intersection over union`() {
        val a = setOf("x", "y", "z")
        val b = setOf("y", "z", "w")
        // |∩| = 2, |∪| = 4 → 0.5
        assertEquals(0.5, Similarity.jaccard(a, b), 1e-9)
    }
}
```

- [ ] **Step 2: Implement**

Create `core/src/main/kotlin/com/bugdigger/core/diff/Similarity.kt`:

```kotlin
package com.bugdigger.core.diff

import kotlin.math.sqrt

object Similarity {

    /** Cosine similarity between two equal-length integer vectors. Returns 0 if either is zero-magnitude. */
    fun cosine(a: IntArray, b: IntArray): Double {
        require(a.size == b.size) { "vector lengths differ: ${a.size} vs ${b.size}" }
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i].toDouble()
            normA += a[i].toDouble() * a[i].toDouble()
            normB += b[i].toDouble() * b[i].toDouble()
        }
        if (normA == 0.0 || normB == 0.0) return 0.0
        return dot / (sqrt(normA) * sqrt(normB))
    }

    /** Jaccard similarity. Two empty sets return 1.0 (defined as identical "absence"). */
    fun <T> jaccard(a: Set<T>, b: Set<T>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val intersection = a.intersect(b).size.toDouble()
        val union = (a.size + b.size).toDouble() - intersection
        if (union == 0.0) return 0.0
        return intersection / union
    }
}
```

- [ ] **Step 3: Run + commit**

```bash
.\gradlew.bat :core:test --tests "com.bugdigger.core.diff.SimilarityTest"
git add core/src/main/kotlin/com/bugdigger/core/diff/Similarity.kt core/src/test/kotlin/com/bugdigger/core/diff/SimilarityTest.kt
git commit -m "feat(core/diff): add cosine / jaccard similarity helpers"
```

---

## Task 4: `DiffResult` data classes

**Files:**
- Create: `core/src/main/kotlin/com/bugdigger/core/diff/DiffResult.kt`

- [ ] **Step 1: Create**

```kotlin
package com.bugdigger.core.diff

/** Output of [ProjectDiffer.diff]. */
data class DiffResult(
    /** Pairs of (oldFingerprint, newFingerprint, confidence). Sorted by confidence desc. */
    val matched: List<MatchedPair>,
    /** New methods that didn't pair to anything in old. */
    val addedInNew: List<MethodFingerprint>,
    /** Old methods that didn't pair to anything in new. */
    val removedFromOld: List<MethodFingerprint>,
    /** Configured threshold below which pairs are dropped to unmatched. */
    val confidenceThreshold: Double,
)

data class MatchedPair(
    val old: MethodFingerprint,
    val new: MethodFingerprint,
    val confidence: Double,
    /** Per-feature breakdown so the UI can explain "why" with a tooltip. */
    val features: ConfidenceFeatures,
)

data class ConfidenceFeatures(
    val opcodeHistogramCosine: Double,
    val calleeJaccard: Double,
    val signatureScore: Double,
    val stringJaccard: Double,
)

/** Configurable weights. Defaults sum to 1.0 and can be overridden in Settings later. */
data class DiffWeights(
    val opcodes: Double = 0.40,
    val callees: Double = 0.30,
    val signature: Double = 0.15,
    val strings: Double = 0.15,
    val confidenceThreshold: Double = 0.40,
)
```

- [ ] **Step 2: Commit**

```bash
git add core/src/main/kotlin/com/bugdigger/core/diff/DiffResult.kt
git commit -m "feat(core/diff): add DiffResult and DiffWeights"
```

---

## Task 5: `ProjectDiffer` — matching algorithm (TDD)

**Files:**
- Create: `core/src/test/kotlin/com/bugdigger/core/diff/ProjectDifferTest.kt`
- Create: `core/src/main/kotlin/com/bugdigger/core/diff/ProjectDiffer.kt`

- [ ] **Step 1: Test (full algorithmic coverage)**

```kotlin
package com.bugdigger.core.diff

import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectDifferTest {

    @Test
    fun `identical projects produce all-1pt0 matches`() {
        val one = project("a/B" to listOf("foo" to body { ldc("x"); pop(); ret() }))
        val differ = ProjectDiffer()

        val result = differ.diff(one, one)

        assertEquals(1, result.matched.size)
        assertEquals(0, result.addedInNew.size)
        assertEquals(0, result.removedFromOld.size)
        val match = result.matched.first()
        assertTrue(match.confidence >= 0.99)
        assertEquals(match.old.methodName, match.new.methodName)
    }

    @Test
    fun `renamed-only method matches with high confidence`() {
        val old = project("a/B" to listOf(
            "originalName" to body { ldc("hello"); invoke("java/lang/String", "length", "()I"); ret() }
        ))
        val new = project("a/B" to listOf(
            "renamedToWhatever" to body { ldc("hello"); invoke("java/lang/String", "length", "()I"); ret() }
        ))

        val result = ProjectDiffer().diff(old, new)

        // Same body, same callees, same string — should match at high confidence
        assertEquals(1, result.matched.size)
        assertTrue(result.matched.first().confidence >= 0.85,
            "expected ≥0.85, got ${result.matched.first().confidence}")
    }

    @Test
    fun `body-changed method matches but at lower confidence`() {
        val old = project("a/B" to listOf("foo" to body { iconst1(); iconst1(); add(); ret() }))
        val new = project("a/B" to listOf("foo" to body { iconst1(); iconst1(); iconst1(); add(); add(); ret() }))

        val result = ProjectDiffer().diff(old, new)

        assertEquals(1, result.matched.size)
        val match = result.matched.first()
        assertTrue(match.confidence in 0.5..0.95)  // not perfect, not terrible
    }

    @Test
    fun `purely added and removed methods land in their lists`() {
        val old = project("a/B" to listOf("kept" to body { ret() }))
        val new = project("a/B" to listOf(
            "kept" to body { ret() },
            "newOne" to body { iconst1(); pop(); ret() },
        ))

        val result = ProjectDiffer().diff(old, new)

        assertNotNull(result.matched.firstOrNull { it.new.methodName == "kept" })
        assertEquals(setOf("newOne"), result.addedInNew.map { it.methodName }.toSet())
        assertEquals(emptyList<MethodFingerprint>(), result.removedFromOld)
    }

    // === helpers (test-only DSL for building tiny class bytes) ===

    private fun project(vararg classes: Pair<String, List<Pair<String, ByteArray>>>): Map<String, ByteArray> {
        return classes.associate { (cls, methods) ->
            val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, cls, null, "java/lang/Object", null)
            for ((mname, mbytes) in methods) {
                val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, mname, "()V", null, null)
                mv.visitCode()
                Body(mv).run { /* mbytes already applied below */ }
                playback(mv, mbytes)
                mv.visitInsn(Opcodes.RETURN)
                mv.visitMaxs(0, 0)
                mv.visitEnd()
            }
            cw.visitEnd()
            cls.replace('/', '.') to cw.toByteArray()
        }
    }

    private class Body(val mv: org.objectweb.asm.MethodVisitor) {
        // No-op DSL; recorded by playback() below for simplicity.
    }

    // For the DSL we record opcodes+args into a ByteArray via a tiny in-memory tape.
    private fun body(block: Recorder.() -> Unit): ByteArray {
        val r = Recorder()
        r.block()
        return r.tape.toByteArray()
    }

    private class Recorder {
        val tape = mutableListOf<Op>()
        sealed interface Op
        data class Ldc(val s: String) : Op
        data class Inv(val owner: String, val name: String, val desc: String) : Op
        object Pop : Op
        object IConst1 : Op
        object Add : Op
        object Ret : Op

        fun ldc(s: String) { tape.add(Ldc(s)) }
        fun invoke(owner: String, name: String, desc: String) { tape.add(Inv(owner, name, desc)) }
        fun pop() { tape.add(Pop) }
        fun iconst1() { tape.add(IConst1) }
        fun add() { tape.add(Add) }
        fun ret() { tape.add(Ret) }

        fun toByteArray(): ByteArray {
            // pack the tape opaquely; the playback function below decodes it.
            val sb = StringBuilder()
            for (op in tape) sb.append(op).append(';')
            return sb.toString().toByteArray()
        }
    }

    private fun playback(mv: org.objectweb.asm.MethodVisitor, bytes: ByteArray) {
        for (token in String(bytes).split(';')) {
            if (token.isBlank()) continue
            when {
                token.startsWith("Ldc(") -> mv.visitLdcInsn(token.substringAfter('(').trimEnd(')').trim('=', ' ', 's', '"'))
                token.startsWith("Inv(") -> {
                    val parts = token.substringAfter('(').trimEnd(')').split(", ").map { it.substringAfter('=') }
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, parts[0], parts[1], parts[2], false)
                }
                token == "Pop" -> mv.visitInsn(Opcodes.POP)
                token == "IConst1" -> mv.visitInsn(Opcodes.ICONST_1)
                token == "Add" -> mv.visitInsn(Opcodes.IADD)
                token == "Ret" -> {} // RETURN handled by caller
            }
        }
    }
}
```

(The DSL is awkward but keeps the test self-contained without checked-in `.class` fixtures. If the implementer prefers, swap for a small helper that just composes raw `MethodFingerprint` instances directly; the algorithmic coverage is what matters.)

- [ ] **Step 2: Run — confirm fail**

```bash
.\gradlew.bat :core:test --tests "com.bugdigger.core.diff.ProjectDifferTest"
```

- [ ] **Step 3: Implement**

Create `core/src/main/kotlin/com/bugdigger/core/diff/ProjectDiffer.kt`:

```kotlin
package com.bugdigger.core.diff

class ProjectDiffer(private val weights: DiffWeights = DiffWeights()) {

    /**
     * Diff two projects represented as `className → bytecode` maps.
     */
    fun diff(old: Map<String, ByteArray>, new: Map<String, ByteArray>): DiffResult {
        val oldFps = fingerprintAll(old)
        val newFps = fingerprintAll(new)
        return diffFingerprints(oldFps, newFps)
    }

    /**
     * Diff with already-extracted fingerprints. Useful for tests and for
     * caching across multiple comparisons of the same project.
     */
    fun diffFingerprints(
        oldFps: List<MethodFingerprint>,
        newFps: List<MethodFingerprint>,
    ): DiffResult {
        // Stage 1: bucket by parameter arity to keep pairing space small.
        val oldByArity = oldFps.groupBy { it.parameterArity }
        val newByArity = newFps.groupBy { it.parameterArity }

        val candidates = mutableListOf<Triple<MethodFingerprint, MethodFingerprint, Pair<Double, ConfidenceFeatures>>>()
        for ((arity, news) in newByArity) {
            val olds = oldByArity[arity] ?: continue
            for (n in news) {
                for (o in olds) {
                    val (score, feats) = scorePair(o, n)
                    if (score >= weights.confidenceThreshold) {
                        candidates.add(Triple(o, n, score to feats))
                    }
                }
            }
        }

        // Greedy match: highest score first; lock both sides.
        val matched = mutableListOf<MatchedPair>()
        val takenOld = mutableSetOf<String>()
        val takenNew = mutableSetOf<String>()
        candidates.sortByDescending { it.third.first }
        for ((o, n, sf) in candidates) {
            if (o.key in takenOld || n.key in takenNew) continue
            matched.add(MatchedPair(o, n, sf.first, sf.second))
            takenOld.add(o.key)
            takenNew.add(n.key)
        }

        val removed = oldFps.filter { it.key !in takenOld }
        val added = newFps.filter { it.key !in takenNew }

        return DiffResult(
            matched = matched.sortedByDescending { it.confidence },
            addedInNew = added,
            removedFromOld = removed,
            confidenceThreshold = weights.confidenceThreshold,
        )
    }

    private fun fingerprintAll(project: Map<String, ByteArray>): List<MethodFingerprint> =
        project.flatMap { (className, bytes) -> MethodFingerprint.extractAll(className, bytes) }

    private fun scorePair(old: MethodFingerprint, new: MethodFingerprint): Pair<Double, ConfidenceFeatures> {
        val sOp = Similarity.cosine(old.opcodeHistogram, new.opcodeHistogram)
        val sCall = Similarity.jaccard(old.calleeFqns, new.calleeFqns)
        val sSig = signatureScore(old, new)
        val sStr = Similarity.jaccard(old.stringConstants, new.stringConstants)
        val confidence = weights.opcodes * sOp +
            weights.callees * sCall +
            weights.signature * sSig +
            weights.strings * sStr
        return confidence to ConfidenceFeatures(sOp, sCall, sSig, sStr)
    }

    private fun signatureScore(old: MethodFingerprint, new: MethodFingerprint): Double {
        val returnMatch = if (old.returnType == new.returnType) 1.0 else 0.0
        val paramMatch = if (old.parameterTypes == new.parameterTypes) 1.0 else 0.5  // arity already gates
        return 0.5 * returnMatch + 0.5 * paramMatch
    }
}
```

- [ ] **Step 4: Run + commit**

```bash
.\gradlew.bat :core:test --tests "com.bugdigger.core.diff.ProjectDifferTest"
git add core/src/main/kotlin/com/bugdigger/core/diff/ProjectDiffer.kt core/src/test/kotlin/com/bugdigger/core/diff/ProjectDifferTest.kt
git commit -m "feat(core/diff): add ProjectDiffer (greedy multi-feature matching)"
```

---

## Task 6: `BytecodeDiffViewModel` — orchestrate two-source load + diff (TDD)

**Files:**
- Create: `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/ui/diff/BytecodeDiffViewModelTest.kt`
- Create: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/diff/BytecodeDiffViewModel.kt`

- [ ] **Step 1: Test**

```kotlin
package com.bugdigger.bytesight.ui.diff

import com.bugdigger.bytesight.source.Capability
import com.bugdigger.bytesight.source.ClassSource
import com.bugdigger.bytesight.source.FakeClassSource
import com.bugdigger.core.diff.ProjectDiffer
import com.bugdigger.protocol.ClassInfo
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BytecodeDiffViewModelTest {

    @Test
    fun `runs diff and exposes results in state`() = runTest {
        val cls = "com.example.A"
        val classBytes = makeClass("com/example/A")
        val info = listOf(ClassInfo.newBuilder().setName(cls).build())
        val left: ClassSource = FakeClassSource(info, mapOf(cls to classBytes), capabilities = Capability.STATIC_ONLY)
        val right: ClassSource = FakeClassSource(info, mapOf(cls to classBytes), capabilities = Capability.STATIC_ONLY)

        val vm = BytecodeDiffViewModel(differ = ProjectDiffer())
        vm.runDiff(left, right)

        // give the coroutine a tick
        kotlinx.coroutines.test.runCurrent()

        val state = vm.uiState.value
        assertTrue(state.matchedPairs.isNotEmpty())
        assertEquals(0, state.addedInNew.size + state.removedFromOld.size)
    }

    private fun makeClass(internal: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0); mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }
}
```

- [ ] **Step 2: Implement**

Create `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/diff/BytecodeDiffViewModel.kt`:

```kotlin
package com.bugdigger.bytesight.ui.diff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bugdigger.bytesight.source.ClassSource
import com.bugdigger.core.diff.DiffResult
import com.bugdigger.core.diff.MatchedPair
import com.bugdigger.core.diff.MethodFingerprint
import com.bugdigger.core.diff.ProjectDiffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BytecodeDiffUiState(
    val leftLabel: String = "",
    val rightLabel: String = "",
    val isRunning: Boolean = false,
    val matchedPairs: List<MatchedPair> = emptyList(),
    val addedInNew: List<MethodFingerprint> = emptyList(),
    val removedFromOld: List<MethodFingerprint> = emptyList(),
    val selectedPair: MatchedPair? = null,
    val error: String? = null,
)

class BytecodeDiffViewModel(
    private val differ: ProjectDiffer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BytecodeDiffUiState())
    val uiState: StateFlow<BytecodeDiffUiState> = _uiState.asStateFlow()

    /** Side handles kept for the rename-transfer action. */
    private var leftSource: ClassSource? = null
    private var rightSource: ClassSource? = null

    fun runDiff(left: ClassSource, right: ClassSource) {
        leftSource = left
        rightSource = right
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    leftLabel = left.displayName,
                    rightLabel = right.displayName,
                    isRunning = true,
                    error = null,
                )
            }
            runCatching {
                val oldClasses = collectBytecode(left)
                val newClasses = collectBytecode(right)
                differ.diff(oldClasses, newClasses)
            }.onSuccess { result: DiffResult ->
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        matchedPairs = result.matched,
                        addedInNew = result.addedInNew,
                        removedFromOld = result.removedFromOld,
                    )
                }
            }.onFailure { e ->
                _uiState.update { it.copy(isRunning = false, error = "Diff failed: ${e.message}") }
            }
        }
    }

    fun selectPair(pair: MatchedPair?) { _uiState.update { it.copy(selectedPair = pair) } }

    fun leftSource(): ClassSource? = leftSource
    fun rightSource(): ClassSource? = rightSource

    private suspend fun collectBytecode(source: ClassSource): Map<String, ByteArray> {
        val list = source.listClasses(includeSystemClasses = false).getOrThrow()
        val out = mutableMapOf<String, ByteArray>()
        for (info in list) {
            source.getBytecode(info.name).onSuccess { out[info.name] = it }
        }
        return out
    }
}
```

- [ ] **Step 3: Run + commit**

```bash
.\gradlew.bat :composeApp:jvmTest --tests "com.bugdigger.bytesight.ui.diff.BytecodeDiffViewModelTest"
git add composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/diff/BytecodeDiffViewModel.kt composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/ui/diff/BytecodeDiffViewModelTest.kt
git commit -m "feat(diff): add BytecodeDiffViewModel"
```

---

## Task 7: `RenameTransfer` action (TDD)

**Files:**
- Create: `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/ui/diff/RenameTransferTest.kt`
- Create: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/diff/RenameTransfer.kt`

The transfer is conceptually simple: given a matched pair `(old, new)` and an old-side `RenameStore`, find the rename keyed by `old.className#old.methodName(old.descriptor)` and write the same display name to the new-side `RenameStore` under `new.className#new.methodName(new.descriptor)`. Also transfer the **class** rename if one exists.

For v1 we keep this minimal — single-pair transfer, no transitive type rewrites.

- [ ] **Step 1: Test**

```kotlin
package com.bugdigger.bytesight.ui.diff

import com.bugdigger.bytesight.service.RenameStore
import com.bugdigger.core.diff.ConfidenceFeatures
import com.bugdigger.core.diff.MatchedPair
import com.bugdigger.core.diff.MethodFingerprint
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RenameTransferTest {

    @Test
    fun `copies method rename from old to new keyed by new fqn`() {
        val oldStore = RenameStore().apply {
            rename("a.B#orig()V", "doImportantThing")
        }
        val newStore = RenameStore()
        val pair = pair(
            old = fp("a.B", "orig", "()V"),
            new = fp("a.B", "x", "()V"),
        )

        RenameTransfer.applyMethodRename(oldStore, newStore, pair)

        assertEquals("doImportantThing", newStore.renameMap.value["a.B#x()V"])
    }

    @Test
    fun `also copies class rename if one exists`() {
        val oldStore = RenameStore().apply {
            rename("a.B", "PaymentService")
            rename("a.B#orig()V", "doImportantThing")
        }
        val newStore = RenameStore()
        val pair = pair(
            old = fp("a.B", "orig", "()V"),
            new = fp("a.C", "x", "()V"),
        )

        RenameTransfer.applyMethodRename(oldStore, newStore, pair)

        assertEquals("PaymentService", newStore.renameMap.value["a.C"])
        assertEquals("doImportantThing", newStore.renameMap.value["a.C#x()V"])
    }

    @Test
    fun `noop if old has no rename`() {
        val oldStore = RenameStore()
        val newStore = RenameStore()
        RenameTransfer.applyMethodRename(oldStore, newStore, pair(fp("a.B", "f", "()V"), fp("a.B", "g", "()V")))
        assertEquals(emptyMap<String, String>(), newStore.renameMap.value)
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
```

- [ ] **Step 2: Implement**

```kotlin
package com.bugdigger.bytesight.ui.diff

import com.bugdigger.bytesight.service.RenameStore
import com.bugdigger.core.diff.MatchedPair

object RenameTransfer {

    /**
     * Copy the old-side rename for a matched method (and the matched class)
     * into [newStore]. No-op for either copy if the old store has no
     * corresponding entry.
     */
    fun applyMethodRename(oldStore: RenameStore, newStore: RenameStore, pair: MatchedPair) {
        val oldMap = oldStore.renameMap.value

        // Class rename
        oldMap[pair.old.className]?.let { display ->
            newStore.rename(pair.new.className, display)
        }

        // Method rename
        val oldKey = "${pair.old.className}#${pair.old.methodName}${pair.old.descriptor}"
        oldMap[oldKey]?.let { display ->
            val newKey = "${pair.new.className}#${pair.new.methodName}${pair.new.descriptor}"
            newStore.rename(newKey, display)
        }
    }
}
```

- [ ] **Step 3: Run + commit**

```bash
.\gradlew.bat :composeApp:jvmTest --tests "com.bugdigger.bytesight.ui.diff.RenameTransferTest"
git add composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/diff/RenameTransfer.kt composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/ui/diff/RenameTransferTest.kt
git commit -m "feat(diff): add RenameTransfer action"
```

---

## Task 8: `BytecodeDiffScreen` — UI

A practical first-cut layout:

```
+--------------------------------------------------------------+
| [Open old…] [Open new…]   left.bts          right.bts        |
| [Run diff]                                                   |
+--------------------------------------------------------------+
| Matched (sorted by confidence ↓)                             |
|  98%  com.example.Foo#bar      <->  com.example.Foo#bar      |
|  91%  com.example.Foo#originalName  <->  Foo#xa              |
|  ...                                                         |
| Added in new: ...                  Removed from old: ...     |
+--------------------------------------------------------------+
| Selected pair side-by-side:                                  |
| [old disassembly]              [new disassembly]             |
| [old decompiled]               [new decompiled]              |
|                                                              |
| [Apply old name to new]                                      |
+--------------------------------------------------------------+
```

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/diff/BytecodeDiffScreen.kt`
- Modify: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/App.kt`
- Modify: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/di/AppModule.kt`

- [ ] **Step 1: Implement the screen (compact first cut)**

```kotlin
package com.bugdigger.bytesight.ui.diff

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bugdigger.bytesight.source.Capability
import com.bugdigger.bytesight.source.JarClassSource
import com.bugdigger.bytesight.source.BtsProjectClassSource
import com.bugdigger.bytesight.service.RenameStore
import com.bugdigger.core.analysis.StaticHierarchyExtractor
import com.bugdigger.core.diff.MatchedPair
import com.bugdigger.core.source.JarReader
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
fun BytecodeDiffScreen(
    viewModel: BytecodeDiffViewModel,
    renameStore: RenameStore,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val jarReader: JarReader = koinInject()
    val staticExtractor: StaticHierarchyExtractor = koinInject()
    val json: Json = koinInject()

    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // Top bar: pickers + run
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = {
                pickFile("Open old project", "*.bts;*.jar", FileDialog.LOAD)?.let { f ->
                    val source = makeStaticSource(f, jarReader, staticExtractor, json)
                    val right = viewModel.rightSource()
                    if (right != null) viewModel.runDiff(source, right) else viewModel.runDiff(source, source)
                }
            }) { Text("Open old…") }
            OutlinedButton(onClick = {
                pickFile("Open new project", "*.bts;*.jar", FileDialog.LOAD)?.let { f ->
                    val source = makeStaticSource(f, jarReader, staticExtractor, json)
                    val left = viewModel.leftSource() ?: source
                    viewModel.runDiff(left, source)
                }
            }) { Text("Open new…") }
            Text(uiState.leftLabel.takeIf { it.isNotEmpty() } ?: "(no old)")
            Text("vs")
            Text(uiState.rightLabel.takeIf { it.isNotEmpty() } ?: "(no new)")
        }

        if (uiState.isRunning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        uiState.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        // Match list
        Card(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(uiState.matchedPairs) { pair ->
                    MatchRow(
                        pair = pair,
                        selected = pair === uiState.selectedPair,
                        onClick = { viewModel.selectPair(pair) },
                    )
                }
            }
        }

        // Selected pair detail
        uiState.selectedPair?.let { pair ->
            PairDetail(
                pair = pair,
                onApplyRename = {
                    val left = viewModel.leftSource() ?: return@PairDetail
                    val leftStore = (left as? BtsProjectClassSource)?.let {
                        // For v1, use the active app-wide RenameStore as "old".
                        renameStore
                    } ?: renameStore
                    RenameTransfer.applyMethodRename(leftStore, renameStore, pair)
                },
            )
        }
    }
}

@Composable
private fun MatchRow(pair: MatchedPair, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("%.0f%%".format(pair.confidence * 100), modifier = Modifier.width(56.dp))
            Text("${pair.old.className}#${pair.old.methodName}${pair.old.descriptor}", modifier = Modifier.weight(1f))
            Text("→", modifier = Modifier.width(20.dp))
            Text("${pair.new.className}#${pair.new.methodName}${pair.new.descriptor}", modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun PairDetail(pair: MatchedPair, onApplyRename: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Confidence breakdown:", style = MaterialTheme.typography.titleSmall)
            Text("opcodes: %.2f  callees: %.2f  signature: %.2f  strings: %.2f".format(
                pair.features.opcodeHistogramCosine,
                pair.features.calleeJaccard,
                pair.features.signatureScore,
                pair.features.stringJaccard,
            ))
            Button(onClick = onApplyRename) { Text("Apply old name to new") }
        }
    }
}

private fun makeStaticSource(file: File, jarReader: JarReader, ex: StaticHierarchyExtractor, json: Json): com.bugdigger.bytesight.source.ClassSource {
    return when (file.extension.lowercase()) {
        "bts" -> BtsProjectClassSource.open(file, ex, json)
        else -> JarClassSource(file, jarReader, ex)
    }
}

private fun pickFile(title: String, mask: String, mode: Int): File? {
    val dlg = FileDialog(null as Frame?, title, mode)
    dlg.file = mask
    dlg.isVisible = true
    val name = dlg.file ?: return null
    val dir = dlg.directory ?: return null
    return File(dir, name)
}
```

(For v1 the side-by-side disassembly + decompiled view is omitted — the `PairDetail` panel just shows the score breakdown and the rename action. Adding raw disassembly text is a single additional `LazyColumn` against `BytecodeDisassembler.disassemble(bytes)` — easy follow-up, kept out of v1 scope.)

- [ ] **Step 2: Wire into Koin and routing**

In `AppModule.kt`:

```kotlin
import com.bugdigger.core.diff.ProjectDiffer
import com.bugdigger.bytesight.ui.diff.BytecodeDiffViewModel

single { ProjectDiffer() }
singleOf(::BytecodeDiffViewModel)
```

In `App.kt`'s `MainContent`:

```kotlin
Screen.BYTECODE_DIFF -> {
    val viewModel: BytecodeDiffViewModel = koinInject()
    val renameStore: RenameStore = koinInject()
    BytecodeDiffScreen(
        viewModel = viewModel,
        renameStore = renameStore,
        modifier = modifier,
    )
}
```

- [ ] **Step 3: Compile and run**

```bash
.\gradlew.bat :composeApp:jvmTest
.\gradlew.bat :composeApp:run
```

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/diff/BytecodeDiffScreen.kt composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/App.kt composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/di/AppModule.kt
git commit -m "feat(ui/diff): add BytecodeDiff screen + routing"
```

---

## Task 9: End-to-end smoke

- [ ] **Step 1: Build a 2-version test fixture**

Use the existing sample app. Build it once → save as `v1.jar`. Make a tiny edit to one method body (e.g. add a logging call) → build again → `v2.jar`.

- [ ] **Step 2: Run the app**

```bash
.\gradlew.bat :composeApp:run
```

In the Diff tab:
1. Open old → `v1.jar`
2. Open new → `v2.jar`
3. Verify:
   - Unchanged methods score ≥ 95%
   - Edited method scores between 0.5 and 0.95
   - Added/Removed lists are correctly populated
4. Click a high-confidence renamed pair → "Apply old name to new" → check the new project's `RenameStore` has the entry.

- [ ] **Step 3: Tag**

```bash
git tag step-4-bytecodediff-complete
```

---

## Verification

| Invariant | Check |
|---|---|
| Identical inputs ⇒ all-1.0 matches | `ProjectDifferTest::identical` |
| Renamed-only methods score ≥ 0.85 | `ProjectDifferTest::renamed` |
| Body-changed methods land in (0.5, 0.95) | `ProjectDifferTest::body-changed` |
| Added/Removed surface correctly | `ProjectDifferTest::added/removed` |
| Rename transfer preserves displayed name | `RenameTransferTest` |

## What's intentionally not done

- CFG-aware structural matching (planned v2 if v1 heuristics aren't enough on heavy obfuscation)
- Embedding-based similarity (Asm2Vec / SAFE) — out of scope
- Side-by-side decompiled-source view in `PairDetail` — easy follow-up; current PairDetail just shows score breakdown + rename action
- Auto-apply renames at high-confidence threshold — keep it explicit for v1
- Diff weights UI in Settings — defaults work; expose later if users care
- Comment transfer (would need fingerprint to record bytecode-offset → comment mapping; doable, follow-up)
