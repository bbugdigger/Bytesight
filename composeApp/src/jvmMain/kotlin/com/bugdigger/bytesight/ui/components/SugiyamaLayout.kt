package com.bugdigger.bytesight.ui.components

import androidx.compose.ui.geometry.Offset

/**
 * A positioned node in the final graph layout.
 */
data class LayoutNode<T>(
    val id: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val data: T,
)

/**
 * A positioned edge in the final graph layout, with waypoints for routing.
 */
data class LayoutEdge<E>(
    val sourceId: String,
    val targetId: String,
    val points: List<Offset>,
    val data: E,
)

/**
 * Complete layout result for graph rendering.
 */
data class GraphLayout<T, E>(
    val nodes: List<LayoutNode<T>>,
    val edges: List<LayoutEdge<E>>,
    val totalWidth: Float,
    val totalHeight: Float,
)

/**
 * Layered graph layout using a simplified Sugiyama algorithm.
 *
 * Produces a top-to-bottom layout suitable for directed graphs like CFGs and call graphs.
 * Handles cycles (back-edges in loops) by temporarily reversing them during layout.
 *
 * @param horizontalSpacing Space between nodes in the same layer
 * @param verticalSpacing Space between layers
 */
class SugiyamaLayout(
    private val horizontalSpacing: Float = 40f,
    private val verticalSpacing: Float = 60f,
) {
    /**
     * Computes a layered layout for the given graph.
     *
     * @param nodes List of (id, data) pairs
     * @param edges List of (sourceId, targetId, edgeData) triples
     * @param entryId Optional entry node ID (placed at the top); if null, uses the first node
     * @param nodeSize Function computing (width, height) for a given node's data
     * @return A [GraphLayout] with positioned nodes and routed edges
     */
    fun <T, E> layout(
        nodes: List<Pair<String, T>>,
        edges: List<Triple<String, String, E>>,
        entryId: String? = null,
        nodeSize: (T) -> Pair<Float, Float>,
    ): GraphLayout<T, E> {
        if (nodes.isEmpty()) {
            return GraphLayout(emptyList(), emptyList(), 0f, 0f)
        }

        val nodeIds = nodes.map { it.first }.toSet()
        val nodeDataMap = nodes.toMap()
        val nodeSizes = nodes.associate { (id, data) -> id to nodeSize(data) }

        // Filter edges to only include nodes in the graph
        val validEdges = edges.filter { it.first in nodeIds && it.second in nodeIds }

        // Step 1: Remove cycles by reversing back-edges
        val adjacency = validEdges.groupBy { it.first }.mapValues { (_, v) -> v.map { it.second } }
        val reversedEdges = findBackEdges(adjacency, entryId ?: nodes.first().first, nodeIds)
        val dagEdges = validEdges.map { (s, t, d) ->
            if (Pair(s, t) in reversedEdges) Triple(t, s, d) else Triple(s, t, d)
        }
        val dagAdjacency = dagEdges.groupBy { it.first }.mapValues { (_, v) -> v.map { it.second } }

        // Step 2: Assign layers
        val layers = assignLayers(dagAdjacency, entryId ?: nodes.first().first, nodeIds)

        // Step 3: Order nodes within layers + crossing minimization
        val layerLists = buildLayerLists(layers, dagAdjacency, nodeIds)
        minimizeCrossings(layerLists, dagAdjacency, nodeIds)

        // Step 4: Assign coordinates
        val layerHeights = computeLayerHeights(layerLists, nodeSizes)
        val positions = assignCoordinates(layerLists, nodeSizes, layerHeights)

        // Compute total dimensions
        val totalWidth = positions.values.maxOfOrNull { (x, _) ->
            x + (nodeSizes[positions.keys.first { k -> positions[k]?.first == x }]?.first ?: 0f)
        }?.let { it + horizontalSpacing } ?: 0f
        val totalHeight = positions.values.maxOfOrNull { (_, y) ->
            y
        }?.let { maxY ->
            val bottomNodeId = positions.entries.maxByOrNull { it.value.second }?.key
            maxY + (nodeSizes[bottomNodeId]?.second ?: 0f) + verticalSpacing
        } ?: 0f

        // Build layout nodes
        val layoutNodes = nodes.map { (id, data) ->
            val (x, y) = positions[id] ?: Pair(0f, 0f)
            val (w, h) = nodeSizes[id] ?: Pair(100f, 50f)
            LayoutNode(id, x, y, w, h, data)
        }

        // Build layout edges (route through node centers)
        val layoutEdges = validEdges.map { (sourceId, targetId, edgeData) ->
            val sourcePos = positions[sourceId] ?: Pair(0f, 0f)
            val sourceSize = nodeSizes[sourceId] ?: Pair(100f, 50f)
            val targetPos = positions[targetId] ?: Pair(0f, 0f)
            val targetSize = nodeSizes[targetId] ?: Pair(100f, 50f)

            val startX = sourcePos.first + sourceSize.first / 2
            val startY = sourcePos.second + sourceSize.second
            val endX = targetPos.first + targetSize.first / 2
            val endY = targetPos.second

            val points = buildEdgePoints(startX, startY, endX, endY, sourcePos, targetPos, sourceSize, targetSize)

            LayoutEdge(sourceId, targetId, points, edgeData)
        }

        return GraphLayout(layoutNodes, layoutEdges, totalWidth, totalHeight)
    }

    private fun findBackEdges(
        adjacency: Map<String, List<String>>,
        entryId: String,
        allNodes: Set<String>,
    ): Set<Pair<String, String>> {
        val backEdges = mutableSetOf<Pair<String, String>>()
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()

        fun dfs(node: String) {
            if (node in visited) return
            if (node in visiting) return // Already detected as cycle target
            visiting.add(node)
            for (succ in adjacency[node] ?: emptyList()) {
                if (succ in visiting) {
                    backEdges.add(Pair(node, succ))
                } else if (succ !in visited) {
                    dfs(succ)
                }
            }
            visiting.remove(node)
            visited.add(node)
        }

        dfs(entryId)
        // Visit any unreachable nodes
        for (node in allNodes) {
            if (node !in visited) dfs(node)
        }

        return backEdges
    }

    private fun assignLayers(
        dagAdjacency: Map<String, List<String>>,
        entryId: String,
        allNodes: Set<String>,
    ): Map<String, Int> {
        // BFS-based layer assignment from entry
        val layers = mutableMapOf<String, Int>()
        val reverseAdj = mutableMapOf<String, MutableList<String>>()
        for ((src, targets) in dagAdjacency) {
            for (t in targets) {
                reverseAdj.getOrPut(t) { mutableListOf() }.add(src)
            }
        }

        // Topological order via Kahn's algorithm
        val inDegree = mutableMapOf<String, Int>()
        for (node in allNodes) inDegree[node] = 0
        for ((_, targets) in dagAdjacency) {
            for (t in targets) {
                inDegree[t] = (inDegree[t] ?: 0) + 1
            }
        }

        val queue = ArrayDeque<String>()
        // Ensure entry node is processed first
        if ((inDegree[entryId] ?: 0) == 0) {
            queue.add(entryId)
        }
        for (node in allNodes) {
            if ((inDegree[node] ?: 0) == 0 && node != entryId) {
                queue.add(node)
            }
        }

        layers[entryId] = 0

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val nodeLayer = layers[node] ?: 0

            for (succ in dagAdjacency[node] ?: emptyList()) {
                val newLayer = nodeLayer + 1
                if (newLayer > (layers[succ] ?: 0)) {
                    layers[succ] = newLayer
                }
                inDegree[succ] = (inDegree[succ] ?: 1) - 1
                if (inDegree[succ] == 0) {
                    queue.add(succ)
                }
            }
        }

        // Assign unreached nodes to layer 0
        for (node in allNodes) {
            if (node !in layers) layers[node] = 0
        }

        return layers
    }

    private fun buildLayerLists(
        layers: Map<String, Int>,
        dagAdjacency: Map<String, List<String>>,
        allNodes: Set<String>,
    ): MutableMap<Int, MutableList<String>> {
        val layerLists = mutableMapOf<Int, MutableList<String>>()
        for ((node, layer) in layers) {
            layerLists.getOrPut(layer) { mutableListOf() }.add(node)
        }
        return layerLists
    }

    private fun minimizeCrossings(
        layerLists: MutableMap<Int, MutableList<String>>,
        dagAdjacency: Map<String, List<String>>,
        allNodes: Set<String>,
    ) {
        val reverseAdj = mutableMapOf<String, MutableList<String>>()
        for ((src, targets) in dagAdjacency) {
            for (t in targets) {
                reverseAdj.getOrPut(t) { mutableListOf() }.add(src)
            }
        }

        val maxLayer = layerLists.keys.maxOrNull() ?: return

        // Barycenter heuristic: 4 passes alternating direction
        repeat(4) { pass ->
            if (pass % 2 == 0) {
                // Top-down
                for (layer in 1..maxLayer) {
                    val nodes = layerLists[layer] ?: continue
                    val upperPositions = layerLists[layer - 1]?.withIndex()
                        ?.associate { (idx, id) -> id to idx.toFloat() } ?: continue

                    nodes.sortBy { node ->
                        val preds = reverseAdj[node]?.filter { it in upperPositions } ?: emptyList()
                        if (preds.isEmpty()) Float.MAX_VALUE
                        else preds.map { upperPositions[it] ?: 0f }.average().toFloat()
                    }
                }
            } else {
                // Bottom-up
                for (layer in (maxLayer - 1) downTo 0) {
                    val nodes = layerLists[layer] ?: continue
                    val lowerPositions = layerLists[layer + 1]?.withIndex()
                        ?.associate { (idx, id) -> id to idx.toFloat() } ?: continue

                    nodes.sortBy { node ->
                        val succs = dagAdjacency[node]?.filter { it in lowerPositions } ?: emptyList()
                        if (succs.isEmpty()) Float.MAX_VALUE
                        else succs.map { lowerPositions[it] ?: 0f }.average().toFloat()
                    }
                }
            }
        }
    }

    private fun computeLayerHeights(
        layerLists: Map<Int, List<String>>,
        nodeSizes: Map<String, Pair<Float, Float>>,
    ): Map<Int, Float> {
        return layerLists.mapValues { (_, nodes) ->
            nodes.maxOfOrNull { nodeSizes[it]?.second ?: 50f } ?: 50f
        }
    }

    private fun assignCoordinates(
        layerLists: Map<Int, List<String>>,
        nodeSizes: Map<String, Pair<Float, Float>>,
        layerHeights: Map<Int, Float>,
    ): Map<String, Pair<Float, Float>> {
        val positions = mutableMapOf<String, Pair<Float, Float>>()
        val maxLayer = layerLists.keys.maxOrNull() ?: return positions

        var currentY = verticalSpacing

        for (layer in 0..maxLayer) {
            val nodes = layerLists[layer] ?: continue
            val layerHeight = layerHeights[layer] ?: 50f

            // Compute total width of this layer
            val totalWidth = nodes.sumOf { (nodeSizes[it]?.first ?: 100f).toDouble() } +
                (nodes.size - 1).coerceAtLeast(0) * horizontalSpacing

            // Center nodes
            var currentX = horizontalSpacing

            for (node in nodes) {
                val (w, h) = nodeSizes[node] ?: Pair(100f, 50f)
                // Vertically center within the layer
                val y = currentY + (layerHeight - h) / 2
                positions[node] = Pair(currentX, y)
                currentX += w + horizontalSpacing
            }

            currentY += layerHeight + verticalSpacing
        }

        return positions
    }

    private fun buildEdgePoints(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        sourcePos: Pair<Float, Float>,
        targetPos: Pair<Float, Float>,
        sourceSize: Pair<Float, Float>,
        targetSize: Pair<Float, Float>,
    ): List<Offset> {
        // For back-edges (target above source), route around the side
        if (endY <= startY) {
            val sideOffset = maxOf(sourceSize.first, targetSize.first) / 2 + horizontalSpacing
            val rightX = maxOf(sourcePos.first + sourceSize.first, targetPos.first + targetSize.first) + sideOffset / 2
            return listOf(
                Offset(startX, startY),
                Offset(rightX, startY),
                Offset(rightX, endY),
                Offset(endX, endY),
            )
        }

        // Normal downward edge: straight or with a slight bend
        return listOf(
            Offset(startX, startY),
            Offset(endX, endY),
        )
    }
}
