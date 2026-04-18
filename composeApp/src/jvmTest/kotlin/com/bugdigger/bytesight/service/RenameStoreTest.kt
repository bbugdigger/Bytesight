package com.bugdigger.bytesight.service

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenameStoreTest {

    private lateinit var store: RenameStore

    @BeforeEach
    fun setup() {
        store = RenameStore()
    }

    @Nested
    @DisplayName("Basic Operations")
    inner class BasicOperations {

        @Test
        @DisplayName("initial state has empty rename map")
        fun `initial state has empty rename map`() {
            assertTrue(store.renameMap.value.isEmpty())
        }

        @Test
        @DisplayName("rename stores a mapping")
        fun `rename stores a mapping`() {
            store.rename("com.example.a", "UserService")
            assertEquals("UserService", store.renameMap.value["com.example.a"])
        }

        @Test
        @DisplayName("rename overwrites existing mapping")
        fun `rename overwrites existing mapping`() {
            store.rename("com.example.a", "UserService")
            store.rename("com.example.a", "AccountService")
            assertEquals("AccountService", store.renameMap.value["com.example.a"])
        }

        @Test
        @DisplayName("removeRename removes the mapping")
        fun `removeRename removes the mapping`() {
            store.rename("com.example.a", "UserService")
            store.removeRename("com.example.a")
            assertFalse(store.renameMap.value.containsKey("com.example.a"))
        }

        @Test
        @DisplayName("clearAll removes all mappings")
        fun `clearAll removes all mappings`() {
            store.rename("com.example.a", "UserService")
            store.rename("com.example.b", "OrderService")
            store.clearAll()
            assertTrue(store.renameMap.value.isEmpty())
        }

        @Test
        @DisplayName("multiple renames coexist")
        fun `multiple renames coexist`() {
            store.rename("com.example.a", "UserService")
            store.rename("com.example.b#c()V", "getUser")
            assertEquals(2, store.renameMap.value.size)
            assertEquals("UserService", store.renameMap.value["com.example.a"])
            assertEquals("getUser", store.renameMap.value["com.example.b#c()V"])
        }
    }

    @Nested
    @DisplayName("Short Name Extraction")
    inner class ShortNameExtraction {

        @Test
        @DisplayName("extracts class short name from FQN")
        fun `extracts class short name from FQN`() {
            assertEquals("Foo", RenameStore.shortName("com.example.Foo"))
        }

        @Test
        @DisplayName("extracts method name from FQN with descriptor")
        fun `extracts method name from FQN with descriptor`() {
            assertEquals("bar", RenameStore.shortName("com.example.Foo#bar(Ljava/lang/String;)V"))
        }

        @Test
        @DisplayName("extracts field name from FQN")
        fun `extracts field name from FQN`() {
            assertEquals("myField", RenameStore.shortName("com.example.Foo#myField"))
        }

        @Test
        @DisplayName("handles single-letter obfuscated names")
        fun `handles single-letter obfuscated names`() {
            assertEquals("a", RenameStore.shortName("com.example.a"))
            assertEquals("b", RenameStore.shortName("com.example.Foo#b()V"))
        }
    }

    @Nested
    @DisplayName("Apply to Source")
    inner class ApplyToSource {

        @Test
        @DisplayName("returns source unchanged when no renames exist")
        fun `returns source unchanged when no renames exist`() {
            val source = "public class Foo { void bar() {} }"
            assertEquals(source, store.applyToSource(source))
        }

        @Test
        @DisplayName("replaces class name in source")
        fun `replaces class name in source`() {
            store.rename("com.example.a", "UserService")
            val source = "public class a {\n    private a instance;\n}"
            val expected = "public class UserService {\n    private UserService instance;\n}"
            assertEquals(expected, store.applyToSource(source))
        }

        @Test
        @DisplayName("replaces method name in source")
        fun `replaces method name in source`() {
            store.rename("com.example.Foo#b()V", "getUser")
            val source = "public void b() {\n    this.b();\n}"
            val expected = "public void getUser() {\n    this.getUser();\n}"
            assertEquals(expected, store.applyToSource(source))
        }

        @Test
        @DisplayName("does not replace partial word matches")
        fun `does not replace partial word matches`() {
            store.rename("com.example.a", "User")
            val source = "abstract class a { int abc = 1; }"
            val expected = "abstract class User { int abc = 1; }"
            assertEquals(expected, store.applyToSource(source))
        }

        @Test
        @DisplayName("applies multiple renames")
        fun `applies multiple renames`() {
            store.rename("com.example.a", "UserService")
            store.rename("com.example.a#b()V", "getUser")
            val source = "class a {\n    void b() {}\n}"
            val expected = "class UserService {\n    void getUser() {}\n}"
            assertEquals(expected, store.applyToSource(source))
        }

        @Test
        @DisplayName("shortNameMap returns short-name to new-name mapping")
        fun `shortNameMap returns short-name to new-name mapping`() {
            store.rename("com.example.a", "UserService")
            store.rename("com.example.Foo#b()V", "getUser")
            val map = store.shortNameMap()
            assertEquals("UserService", map["a"])
            assertEquals("getUser", map["b"])
        }
    }
}
