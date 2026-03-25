package com.bugdigger.bytesight.ui.hierarchy

import com.bugdigger.protocol.ClassInfo
import com.bugdigger.protocol.classInfo
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [HierarchyBuilder].
 */
class HierarchyBuilderTest {

    private fun makeClassInfo(
        name: String,
        superclass: String = "java.lang.Object",
        interfaces: List<String> = emptyList(),
        isInterface: Boolean = false,
        isEnum: Boolean = false,
    ): ClassInfo = classInfo {
        this.name = name
        this.packageName = name.substringBeforeLast('.', "")
        this.simpleName = name.substringAfterLast('.')
        this.superclass = superclass
        this.interfaces.addAll(interfaces)
        this.isInterface = isInterface
        this.isEnum = isEnum
    }

    @Nested
    @DisplayName("Basic Hierarchy")
    inner class BasicHierarchy {
        @Test
        fun `should create root nodes for top-level classes`() {
            val classes = listOf(
                makeClassInfo("com.example.Base"),
                makeClassInfo("com.example.Child", superclass = "com.example.Base"),
            )
            val roots = HierarchyBuilder.buildHierarchy(classes)
            // Base should be a root with Child as its child
            assertEquals(1, roots.size)
            assertEquals("com.example.Base", roots.first().className)
            assertEquals(1, roots.first().children.size)
            assertEquals("com.example.Child", roots.first().children.first().className)
        }

        @Test
        fun `should handle multiple roots`() {
            val classes = listOf(
                makeClassInfo("com.example.A"),
                makeClassInfo("com.example.B"),
            )
            val roots = HierarchyBuilder.buildHierarchy(classes)
            assertEquals(2, roots.size)
        }

        @Test
        fun `should handle deep hierarchy`() {
            val classes = listOf(
                makeClassInfo("com.example.A"),
                makeClassInfo("com.example.B", superclass = "com.example.A"),
                makeClassInfo("com.example.C", superclass = "com.example.B"),
            )
            val roots = HierarchyBuilder.buildHierarchy(classes)
            assertEquals(1, roots.size)
            assertEquals("com.example.A", roots.first().className)
            val b = roots.first().children.first()
            assertEquals("com.example.B", b.className)
            val c = b.children.first()
            assertEquals("com.example.C", c.className)
        }
    }

    @Nested
    @DisplayName("Interface Hierarchy")
    inner class InterfaceHierarchy {
        @Test
        fun `should group implementors under interface`() {
            val classes = listOf(
                makeClassInfo("com.example.MyInterface", isInterface = true),
                makeClassInfo("com.example.Impl", interfaces = listOf("com.example.MyInterface")),
            )
            val roots = HierarchyBuilder.buildHierarchy(classes)
            // MyInterface should have Impl as a child
            val iface = roots.find { it.className == "com.example.MyInterface" }
            assertTrue(iface != null)
            assertEquals(1, iface.children.size)
            assertEquals("com.example.Impl", iface.children.first().className)
        }
    }

    @Nested
    @DisplayName("Ancestor Chain")
    inner class AncestorChain {
        @Test
        fun `should find ancestor chain`() {
            val classes = listOf(
                makeClassInfo("com.example.A"),
                makeClassInfo("com.example.B", superclass = "com.example.A"),
                makeClassInfo("com.example.C", superclass = "com.example.B"),
            )
            val ancestors = HierarchyBuilder.findAncestors("com.example.C", classes)
            assertEquals(listOf("com.example.B", "com.example.A"), ancestors)
        }

        @Test
        fun `should return empty for root class`() {
            val classes = listOf(makeClassInfo("com.example.A"))
            val ancestors = HierarchyBuilder.findAncestors("com.example.A", classes)
            assertTrue(ancestors.isEmpty())
        }
    }

    @Nested
    @DisplayName("Filter Tree")
    inner class FilterTree {
        @Test
        fun `should filter tree by query`() {
            val classes = listOf(
                makeClassInfo("com.example.Animal"),
                makeClassInfo("com.example.Dog", superclass = "com.example.Animal"),
                makeClassInfo("com.example.Car"),
            )
            val roots = HierarchyBuilder.buildHierarchy(classes)
            val filtered = HierarchyBuilder.filterTree(roots, "Dog")
            // Should include Animal (parent of Dog) and Dog
            assertTrue(filtered.any { it.className == "com.example.Animal" })
            val animal = filtered.first { it.className == "com.example.Animal" }
            assertTrue(animal.children.any { it.className == "com.example.Dog" })
            // Car should be filtered out
            assertTrue(filtered.none { it.className == "com.example.Car" })
        }

        @Test
        fun `empty query should return all`() {
            val classes = listOf(
                makeClassInfo("com.example.A"),
                makeClassInfo("com.example.B"),
            )
            val roots = HierarchyBuilder.buildHierarchy(classes)
            val filtered = HierarchyBuilder.filterTree(roots, "")
            assertEquals(roots.size, filtered.size)
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCases {
        @Test
        fun `should handle empty class list`() {
            val roots = HierarchyBuilder.buildHierarchy(emptyList())
            assertTrue(roots.isEmpty())
        }
    }
}
