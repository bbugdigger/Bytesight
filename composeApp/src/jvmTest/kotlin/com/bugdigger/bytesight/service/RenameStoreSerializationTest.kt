package com.bugdigger.bytesight.service

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RenameStoreSerializationTest {

    @Test
    fun `serialize then restore preserves all renames`() {
        val original = RenameStore().apply {
            rename("com.example.a", "UserService")
            rename("com.example.b#c()V", "getUser")
            rename("com.example.b#field", "userId")
        }

        val text = original.serialize()
        val restored = RenameStore().apply { restore(text) }

        assertEquals(original.renameMap.value, restored.renameMap.value)
    }

    @Test
    fun `restore with empty json yields empty store`() {
        val store = RenameStore().apply { rename("a", "B") }
        store.restore("{}")
        assertTrue(store.renameMap.value.isEmpty())
    }

    @Test
    fun `restore replaces existing entries`() {
        val store = RenameStore().apply {
            rename("a", "Old")
            rename("b", "B")
        }
        val payload = """{"a":"New","c":"C"}"""
        store.restore(payload)
        assertEquals(mapOf("a" to "New", "c" to "C"), store.renameMap.value)
    }
}
