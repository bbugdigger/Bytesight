package com.bugdigger.bytesight.service

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommentStoreTest {

    private lateinit var store: CommentStore
    private val keyFoo = MethodKey("com.example.Foo", "bar", "()V")
    private val keyBaz = MethodKey("com.example.Baz", "qux", "(I)I")

    @BeforeEach
    fun setup() {
        store = CommentStore()
    }

    @DisplayName("Initial state is empty")
    @Test
    fun initialStateIsEmpty() {
        assertTrue(store.state.value.isEmpty())
        assertTrue(store.commentsFor(keyFoo).isEmpty)
    }

    @DisplayName("setInstructionComment stores a comment")
    @Test
    fun setInstructionCommentStoresComment() {
        store.setInstructionComment(keyFoo, 5, "loads the instance")
        assertEquals("loads the instance", store.commentsFor(keyFoo).instructionLevel[5])
    }

    @DisplayName("setBlockComment stores a block-level comment")
    @Test
    fun setBlockCommentStoresBlockComment() {
        store.setBlockComment(keyFoo, "block_0", "Entry point")
        assertEquals("Entry point", store.commentsFor(keyFoo).blockLevel["block_0"])
    }

    @DisplayName("Blank text removes the instruction comment")
    @Test
    fun blankRemovesInstructionComment() {
        store.setInstructionComment(keyFoo, 5, "some comment")
        store.setInstructionComment(keyFoo, 5, "")
        assertNull(store.commentsFor(keyFoo).instructionLevel[5])
    }

    @DisplayName("Blank text removes the block comment")
    @Test
    fun blankRemovesBlockComment() {
        store.setBlockComment(keyFoo, "block_0", "header")
        store.setBlockComment(keyFoo, "block_0", "   ")
        assertNull(store.commentsFor(keyFoo).blockLevel["block_0"])
    }

    @DisplayName("Removing the last comment for a method drops the method entry")
    @Test
    fun removingLastCommentDropsMethod() {
        store.setInstructionComment(keyFoo, 5, "only comment")
        store.setInstructionComment(keyFoo, 5, "")
        assertFalse(store.state.value.containsKey(keyFoo))
    }

    @DisplayName("Comments are scoped per method key")
    @Test
    fun commentsScopedPerMethod() {
        store.setInstructionComment(keyFoo, 0, "foo comment")
        store.setInstructionComment(keyBaz, 0, "baz comment")

        assertEquals("foo comment", store.commentsFor(keyFoo).instructionLevel[0])
        assertEquals("baz comment", store.commentsFor(keyBaz).instructionLevel[0])
    }

    @DisplayName("Existing comment is overwritten")
    @Test
    fun existingCommentIsOverwritten() {
        store.setInstructionComment(keyFoo, 5, "original")
        store.setInstructionComment(keyFoo, 5, "updated")
        assertEquals("updated", store.commentsFor(keyFoo).instructionLevel[5])
    }

    @DisplayName("Block and instruction comments coexist in the same method")
    @Test
    fun blockAndInstructionCommentsCoexist() {
        store.setBlockComment(keyFoo, "block_0", "block header")
        store.setInstructionComment(keyFoo, 3, "instruction")

        val comments = store.commentsFor(keyFoo)
        assertEquals("block header", comments.blockLevel["block_0"])
        assertEquals("instruction", comments.instructionLevel[3])
    }
}
