package com.bugdigger.core.analysis

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CfgBuilderTest {

    private val cfgBuilder = CfgBuilder()

    // ========== Bytecode generators ==========

    private fun createSimpleClass(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Simple", null, "java/lang/Object", null)

        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "add", "(II)I", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ILOAD, 0)
        mv.visitVarInsn(Opcodes.ILOAD, 1)
        mv.visitInsn(Opcodes.IADD)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitMaxs(2, 2)
        mv.visitEnd()

        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun createBranchClass(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Branch", null, "java/lang/Object", null)

        // isPositive(int) -> boolean: if (x > 0) return true else return false
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "isPositive", "(I)Z", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ILOAD, 0)
        val labelFalse = Label()
        mv.visitJumpInsn(Opcodes.IFLE, labelFalse)
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitLabel(labelFalse)
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitMaxs(1, 1)
        mv.visitEnd()

        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun createLoopClass(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Loop", null, "java/lang/Object", null)

        // sum(int n) -> int: int s=0; for(int i=0; i<n; i++) s+=i; return s;
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "sum", "(I)I", null, null)
        mv.visitCode()
        mv.visitInsn(Opcodes.ICONST_0)       // s = 0
        mv.visitVarInsn(Opcodes.ISTORE, 1)
        mv.visitInsn(Opcodes.ICONST_0)       // i = 0
        mv.visitVarInsn(Opcodes.ISTORE, 2)

        val loopStart = Label()
        val loopEnd = Label()

        mv.visitLabel(loopStart)
        mv.visitFrame(Opcodes.F_APPEND, 2, arrayOf(Opcodes.INTEGER, Opcodes.INTEGER), 0, null)
        mv.visitVarInsn(Opcodes.ILOAD, 2)    // i
        mv.visitVarInsn(Opcodes.ILOAD, 0)    // n
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, loopEnd)  // if i >= n goto end

        mv.visitVarInsn(Opcodes.ILOAD, 1)    // s
        mv.visitVarInsn(Opcodes.ILOAD, 2)    // i
        mv.visitInsn(Opcodes.IADD)
        mv.visitVarInsn(Opcodes.ISTORE, 1)   // s += i

        mv.visitIincInsn(2, 1)               // i++
        mv.visitJumpInsn(Opcodes.GOTO, loopStart)

        mv.visitLabel(loopEnd)
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
        mv.visitVarInsn(Opcodes.ILOAD, 1)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitMaxs(2, 3)
        mv.visitEnd()

        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun createTryCatchClass(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/TryCatch", null, "java/lang/Object", null)

        // safe(int) -> int: try { return 100/x; } catch (ArithmeticException e) { return -1; }
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "safe", "(I)I", null, null)
        mv.visitCode()

        val tryStart = Label()
        val tryEnd = Label()
        val catchStart = Label()
        val afterCatch = Label()

        mv.visitTryCatchBlock(tryStart, tryEnd, catchStart, "java/lang/ArithmeticException")

        mv.visitLabel(tryStart)
        mv.visitIntInsn(Opcodes.BIPUSH, 100)
        mv.visitVarInsn(Opcodes.ILOAD, 0)
        mv.visitInsn(Opcodes.IDIV)
        mv.visitLabel(tryEnd)
        mv.visitInsn(Opcodes.IRETURN)

        mv.visitLabel(catchStart)
        mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, arrayOf("java/lang/ArithmeticException"))
        mv.visitVarInsn(Opcodes.ASTORE, 1)   // store exception
        mv.visitInsn(Opcodes.ICONST_M1)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitMaxs(2, 2)
        mv.visitEnd()

        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun createSwitchClass(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Switch", null, "java/lang/Object", null)

        // choose(int) -> int: switch(x) { case 1: return 10; case 2: return 20; default: return 0; }
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "choose", "(I)I", null, null)
        mv.visitCode()

        val case1 = Label()
        val case2 = Label()
        val defaultCase = Label()

        mv.visitVarInsn(Opcodes.ILOAD, 0)
        mv.visitLookupSwitchInsn(defaultCase, intArrayOf(1, 2), arrayOf(case1, case2))

        mv.visitLabel(case1)
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
        mv.visitIntInsn(Opcodes.BIPUSH, 10)
        mv.visitInsn(Opcodes.IRETURN)

        mv.visitLabel(case2)
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
        mv.visitIntInsn(Opcodes.BIPUSH, 20)
        mv.visitInsn(Opcodes.IRETURN)

        mv.visitLabel(defaultCase)
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitMaxs(1, 1)
        mv.visitEnd()

        cw.visitEnd()
        return cw.toByteArray()
    }

    // ========== Tests ==========

    @Nested
    @DisplayName("Linear method")
    inner class LinearTests {
        @Test
        fun `linear method produces single block`() {
            val cfg = cfgBuilder.buildCfg(createSimpleClass(), "add", "(II)I")

            assertEquals(1, cfg.blocks.size)
            assertEquals("block_0", cfg.entryBlockId)
            assertEquals(BlockType.ENTRY, cfg.blocks[0].blockType)
            assertTrue(cfg.edges.none { it.type != EdgeType.EXCEPTION })
        }

        @Test
        fun `linear block contains all instructions`() {
            val cfg = cfgBuilder.buildCfg(createSimpleClass(), "add", "(II)I")

            val block = cfg.blocks[0]
            assertEquals(4, block.instructions.size)
            assertEquals("ILOAD", block.instructions[0].mnemonic)
            assertEquals("IRETURN", block.instructions.last().mnemonic)
        }
    }

    @Nested
    @DisplayName("If-else branch")
    inner class BranchTests {
        @Test
        fun `if-else produces three blocks`() {
            val cfg = cfgBuilder.buildCfg(createBranchClass(), "isPositive", "(I)Z")

            assertEquals(3, cfg.blocks.size, "Expected 3 blocks: entry, true-branch, false-branch")
        }

        @Test
        fun `entry block has branch-true and branch-false edges`() {
            val cfg = cfgBuilder.buildCfg(createBranchClass(), "isPositive", "(I)Z")

            val entryEdges = cfg.edges.filter { it.sourceId == cfg.entryBlockId }
            val edgeTypes = entryEdges.map { it.type }.toSet()
            assertTrue(EdgeType.BRANCH_TRUE in edgeTypes, "Expected BRANCH_TRUE edge from entry")
            assertTrue(EdgeType.BRANCH_FALSE in edgeTypes, "Expected BRANCH_FALSE edge from entry")
        }

        @Test
        fun `both branches are exit blocks`() {
            val cfg = cfgBuilder.buildCfg(createBranchClass(), "isPositive", "(I)Z")

            val nonEntryBlocks = cfg.blocks.filter { it.id != cfg.entryBlockId }
            assertTrue(nonEntryBlocks.all { it.blockType == BlockType.EXIT },
                "Both branch targets should be EXIT blocks")
        }
    }

    @Nested
    @DisplayName("Loop")
    inner class LoopTests {
        @Test
        fun `loop produces loop header block`() {
            val cfg = cfgBuilder.buildCfg(createLoopClass(), "sum", "(I)I")

            val loopHeaders = cfg.blocks.filter { it.blockType == BlockType.LOOP_HEADER }
            assertTrue(loopHeaders.isNotEmpty(), "Expected at least one LOOP_HEADER block")
        }

        @Test
        fun `loop has back-edge`() {
            val cfg = cfgBuilder.buildCfg(createLoopClass(), "sum", "(I)I")

            val loopHeader = cfg.blocks.first { it.blockType == BlockType.LOOP_HEADER }
            // There should be an edge pointing back to the loop header
            val backEdges = cfg.edges.filter { it.targetId == loopHeader.id }
            assertTrue(backEdges.any { it.type == EdgeType.UNCONDITIONAL_JUMP },
                "Expected a GOTO back-edge to the loop header")
        }
    }

    @Nested
    @DisplayName("Try-catch")
    inner class TryCatchTests {
        @Test
        fun `try-catch produces catch block`() {
            val cfg = cfgBuilder.buildCfg(createTryCatchClass(), "safe", "(I)I")

            val catchBlocks = cfg.blocks.filter { it.blockType == BlockType.CATCH_BLOCK }
            assertTrue(catchBlocks.isNotEmpty(), "Expected at least one CATCH_BLOCK")
        }

        @Test
        fun `exception edges point to catch block`() {
            val cfg = cfgBuilder.buildCfg(createTryCatchClass(), "safe", "(I)I")

            val exceptionEdges = cfg.edges.filter { it.type == EdgeType.EXCEPTION }
            assertTrue(exceptionEdges.isNotEmpty(), "Expected EXCEPTION edges")

            val catchBlockIds = cfg.blocks.filter { it.blockType == BlockType.CATCH_BLOCK }.map { it.id }.toSet()
            assertTrue(exceptionEdges.all { it.targetId in catchBlockIds },
                "All EXCEPTION edges should point to CATCH_BLOCK")
        }

        @Test
        fun `exception edge has exception type label`() {
            val cfg = cfgBuilder.buildCfg(createTryCatchClass(), "safe", "(I)I")

            val exceptionEdges = cfg.edges.filter { it.type == EdgeType.EXCEPTION }
            assertTrue(exceptionEdges.any { it.label == "java.lang.ArithmeticException" },
                "Expected exception edge with ArithmeticException label")
        }
    }

    @Nested
    @DisplayName("Switch")
    inner class SwitchTests {
        @Test
        fun `switch produces case and default edges`() {
            val cfg = cfgBuilder.buildCfg(createSwitchClass(), "choose", "(I)I")

            val switchCaseEdges = cfg.edges.filter { it.type == EdgeType.SWITCH_CASE }
            val switchDefaultEdges = cfg.edges.filter { it.type == EdgeType.SWITCH_DEFAULT }
            assertEquals(2, switchCaseEdges.size, "Expected 2 SWITCH_CASE edges")
            assertEquals(1, switchDefaultEdges.size, "Expected 1 SWITCH_DEFAULT edge")
        }
    }

    @Nested
    @DisplayName("Stable IDs")
    inner class StableIdTests {
        @Test
        fun `building CFG twice produces same block IDs`() {
            val bytecode = createBranchClass()
            val cfg1 = cfgBuilder.buildCfg(bytecode, "isPositive", "(I)Z")
            val cfg2 = cfgBuilder.buildCfg(bytecode, "isPositive", "(I)Z")

            assertEquals(cfg1.blocks.map { it.id }, cfg2.blocks.map { it.id })
            assertEquals(cfg1.entryBlockId, cfg2.entryBlockId)
        }

        @Test
        fun `block IDs follow block_N pattern`() {
            val cfg = cfgBuilder.buildCfg(createBranchClass(), "isPositive", "(I)Z")

            assertTrue(cfg.blocks.all { it.id.matches(Regex("block_\\d+")) },
                "All block IDs should match block_N pattern")
        }
    }
}
