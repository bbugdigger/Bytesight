package com.bugdigger.bytesight.ui.hierarchy

import com.bugdigger.protocol.ClassInfo

/**
 * A node in the class hierarchy tree.
 */
data class HierarchyNode(
    val className: String,
    val classInfo: ClassInfo?,
    val children: MutableList<HierarchyNode> = mutableListOf(),
    val isInterface: Boolean = false,
    val isEnum: Boolean = false,
)

/**
 * Builds a class hierarchy tree from a flat list of [ClassInfo] objects.
 *
 * The hierarchy is based on the `superclass` and `interfaces` fields in [ClassInfo].
 * Classes whose superclass is not in the loaded set become root nodes.
 */
object HierarchyBuilder {

    /**
     * Builds the hierarchy tree.
     *
     * @param classes List of loaded class information
     * @return Root nodes of the hierarchy (classes whose parent is not in the loaded set)
     */
    fun buildHierarchy(classes: List<ClassInfo>): List<HierarchyNode> {
        // Index all classes by fully qualified name
        val classMap = classes.associateBy { it.name }

        // Create nodes for all classes
        val nodeMap = mutableMapOf<String, HierarchyNode>()
        for (classInfo in classes) {
            nodeMap[classInfo.name] = HierarchyNode(
                className = classInfo.name,
                classInfo = classInfo,
                isInterface = classInfo.isInterface,
                isEnum = classInfo.isEnum,
            )
        }

        // Track which nodes are children (have a parent in the set)
        val childSet = mutableSetOf<String>()

        // Build parent-child relationships
        for (classInfo in classes) {
            val childNode = nodeMap[classInfo.name] ?: continue

            // Superclass relationship
            val superName = classInfo.superclass
            if (superName.isNotBlank() && superName != "java.lang.Object") {
                val parentNode = nodeMap.getOrPut(superName) {
                    HierarchyNode(
                        className = superName,
                        classInfo = classMap[superName],
                    )
                }
                parentNode.children.add(childNode)
                childSet.add(classInfo.name)
            }

            // Interface relationships
            for (iface in classInfo.interfacesList) {
                if (iface.isNotBlank()) {
                    val ifaceNode = nodeMap.getOrPut(iface) {
                        HierarchyNode(
                            className = iface,
                            classInfo = classMap[iface],
                            isInterface = true,
                        )
                    }
                    ifaceNode.children.add(childNode)
                    childSet.add(classInfo.name)
                }
            }
        }

        // Roots are nodes that are not children of any other loaded class
        val roots = nodeMap.values
            .filter { it.className !in childSet }
            .sortedBy { it.className }

        return roots
    }

    /**
     * Finds the ancestor chain from the given class up to the root.
     */
    fun findAncestors(className: String, classes: List<ClassInfo>): List<String> {
        val classMap = classes.associateBy { it.name }
        val ancestors = mutableListOf<String>()
        var current = className

        while (true) {
            val info = classMap[current] ?: break
            val superName = info.superclass
            if (superName.isBlank() || superName == current) break
            // Only include ancestors that are in the loaded class set
            if (superName in classMap) {
                ancestors.add(superName)
            }
            current = superName
        }

        return ancestors
    }

    /**
     * Filters the tree to only show branches containing the search query.
     */
    fun filterTree(roots: List<HierarchyNode>, query: String): List<HierarchyNode> {
        if (query.isBlank()) return roots
        val lowerQuery = query.lowercase()
        return roots.mapNotNull { filterNode(it, lowerQuery) }
    }

    private fun filterNode(node: HierarchyNode, query: String): HierarchyNode? {
        val matchesSelf = node.className.lowercase().contains(query)
        val filteredChildren = node.children.mapNotNull { filterNode(it, query) }

        return if (matchesSelf || filteredChildren.isNotEmpty()) {
            node.copy(children = filteredChildren.toMutableList())
        } else {
            null
        }
    }
}
