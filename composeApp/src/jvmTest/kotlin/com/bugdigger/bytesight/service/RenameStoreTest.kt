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
    @DisplayName("Display Name Helpers")
    inner class DisplayNameHelpers {

        @Test
        fun `displayShortName returns rename when present`() {
            val renames = mapOf("o.j" to "Product")
            assertEquals("Product", RenameStore.displayShortName("o.j", renames))
        }

        @Test
        fun `displayShortName falls back to shortName when no rename`() {
            assertEquals("j", RenameStore.displayShortName("o.j", emptyMap()))
            assertEquals("MyClass", RenameStore.displayShortName("com.example.MyClass", emptyMap()))
        }

        @Test
        fun `displayShortName is FQN-keyed, not short-name-keyed`() {
            // Two classes with the same simple name; only one renamed.
            // The lookup must not bleed over.
            val renames = mapOf("a.X" to "Foo")
            assertEquals("Foo", RenameStore.displayShortName("a.X", renames))
            assertEquals("X", RenameStore.displayShortName("b.X", renames))
        }

        @Test
        fun `displayClassFqn preserves package and substitutes simple name`() {
            val renames = mapOf("o.j" to "Product")
            assertEquals("o.Product", RenameStore.displayClassFqn("o.j", renames))
        }

        @Test
        fun `displayClassFqn handles classes with no package`() {
            val renames = mapOf("Top" to "Renamed")
            assertEquals("Renamed", RenameStore.displayClassFqn("Top", renames))
        }

        @Test
        fun `displayClassFqn returns original FQN when no rename`() {
            assertEquals("com.example.Foo", RenameStore.displayClassFqn("com.example.Foo", emptyMap()))
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
