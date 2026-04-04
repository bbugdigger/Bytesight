package com.bugdigger.bytesight.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A reusable Canvas-based graph renderer with pan and zoom.
 *
 * Renders nodes as positioned composables and edges as lines with arrowheads on a Canvas layer.
 * Supports mouse drag for panning and scroll wheel for zooming.
 *
 * @param layout The computed graph layout with positioned nodes and edges
 * @param onNodeClick Callback when a node is clicked
 * @param selectedNodeId ID of the currently selected node (for highlighting)
 * @param nodeContent Composable lambda to render each node's content
 * @param edgeColor Function mapping edge data to a color
 * @param edgeDashPattern Optional function for dashed edge patterns
 * @param modifier Modifier for the graph container
 */
@Composable
fun <T, E> GraphView(
    layout: GraphLayout<T, E>,
    onNodeClick: (LayoutNode<T>) -> Unit = {},
    selectedNodeId: String? = null,
    nodeContent: @Composable (LayoutNode<T>) -> Unit,
    edgeColor: (E) -> Color,
    edgeDashPattern: ((E) -> PathEffect?)? = null,
    modifier: Modifier = Modifier,
) {
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var scale by remember { mutableStateOf(1f) }

    val density = LocalDensity.current

    Box(
        modifier = modifier
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offsetX += dragAmount.x
                    offsetY += dragAmount.y
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            val scrollDelta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            val zoomFactor = if (scrollDelta > 0) 0.9f else 1.1f
                            val newScale = (scale * zoomFactor).coerceIn(0.2f, 3f)

                            // Zoom towards cursor position
                            val cursorPos = event.changes.firstOrNull()?.position ?: Offset.Zero
                            offsetX = cursorPos.x - (cursorPos.x - offsetX) * (newScale / scale)
                            offsetY = cursorPos.y - (cursorPos.y - offsetY) * (newScale / scale)

                            scale = newScale
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            },
    ) {
        // Transform container for pan/zoom
        Box(
            modifier = Modifier.graphicsLayer {
                translationX = offsetX
                translationY = offsetY
                scaleX = scale
                scaleY = scale
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
            },
        ) {
            // Edge canvas layer (behind nodes)
            Canvas(
                modifier = Modifier.size(
                    with(density) { layout.totalWidth.toDp() },
                    with(density) { layout.totalHeight.toDp() },
                ),
            ) {
                for (edge in layout.edges) {
                    val color = edgeColor(edge.data)
                    val dashPattern = edgeDashPattern?.invoke(edge.data)
                    drawEdge(edge.points, color, dashPattern)
                }
            }

            // Node layer: positioned composables
            for (node in layout.nodes) {
                Box(
                    modifier = Modifier
                        .offset(
                            with(density) { node.x.toDp() },
                            with(density) { node.y.toDp() },
                        )
                        .size(
                            with(density) { node.width.toDp() },
                            with(density) { node.height.toDp() },
                        )
                        .pointerInput(node.id) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Press) {
                                        onNodeClick(node)
                                    }
                                }
                            }
                        },
                ) {
                    nodeContent(node)
                }
            }
        }
    }
}

private fun DrawScope.drawEdge(
    points: List<Offset>,
    color: Color,
    dashPattern: PathEffect?,
) {
    if (points.size < 2) return

    val stroke = Stroke(
        width = 2f,
        cap = StrokeCap.Round,
        pathEffect = dashPattern,
    )

    // Draw the path
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            lineTo(points[i].x, points[i].y)
        }
    }
    drawPath(path, color, style = stroke)

    // Draw arrowhead at the last segment
    val lastPoint = points.last()
    val prevPoint = points[points.size - 2]
    drawArrowhead(prevPoint, lastPoint, color)
}

private fun DrawScope.drawArrowhead(from: Offset, to: Offset, color: Color) {
    val arrowLength = 10f
    val arrowAngle = Math.toRadians(25.0)

    val angle = atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())

    val x1 = to.x - arrowLength * cos(angle - arrowAngle).toFloat()
    val y1 = to.y - arrowLength * sin(angle - arrowAngle).toFloat()
    val x2 = to.x - arrowLength * cos(angle + arrowAngle).toFloat()
    val y2 = to.y - arrowLength * sin(angle + arrowAngle).toFloat()

    val path = Path().apply {
        moveTo(to.x, to.y)
        lineTo(x1, y1)
        lineTo(x2, y2)
        close()
    }
    drawPath(path, color)
}
