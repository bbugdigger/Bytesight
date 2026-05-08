package com.bugdigger.bytesight.service

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CommentStoreSerializationTest {

    @Test
    fun `round-trips block-level and instruction-level comments`() {
        val key1 = MethodKey("a.B", "foo", "(I)V")
        val key2 = MethodKey("a.B", "bar", "()V")

        val original = CommentStore().apply {
            setBlockComment(key1, "block_3", "tricky branch")
            setInstructionComment(key1, 42, "magic ldc")
            setInstructionComment(key2, 0, "entry")
        }

        val text = original.serialize()
        val restored = CommentStore().apply { restore(text) }

        assertEquals(original.commentsFor(key1), restored.commentsFor(key1))
        assertEquals(original.commentsFor(key2), restored.commentsFor(key2))
        assertEquals(original.state.value.size, restored.state.value.size)
    }

    @Test
    fun `restore with empty list clears the store`() {
        val key = MethodKey("a.B", "foo", "()V")
        val store = CommentStore().apply { setBlockComment(key, "x", "hi") }
        store.restore("[]")
        assertEquals(emptyMap<MethodKey, MethodComments>(), store.state.value)
    }
}
