package com.bugdigger.bytesight.ui.components

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A pure Compose code viewer with syntax highlighting and line numbers.
 */
@Composable
fun CodeViewer(
    code: String,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") language: String = "java",
) {
    val highlighter = remember { JavaSyntaxHighlighter() }
    val lines = remember(code) { code.lines() }
    val highlightedLines = remember(code) {
        lines.map { highlighter.highlight(it) }
    }
    val lineCount = lines.size
    val gutterWidth = remember(lineCount) {
        (lineCount.toString().length.coerceAtLeast(3) * 10 + 16).dp
    }

    val codeStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )

    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()

    Box(modifier = modifier) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Line number gutter
            Column(
                modifier = Modifier
                    .width(gutterWidth)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .verticalScroll(verticalScrollState)
                    .padding(end = 8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                for (i in 1..lineCount) {
                    Text(
                        text = "$i",
                        style = codeStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }

            // Thin divider between gutter and code
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
            )

            // Code area
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(verticalScrollState)
                            .horizontalScroll(horizontalScrollState)
                            .padding(start = 8.dp),
                    ) {
                        for (line in highlightedLines) {
                            Text(
                                text = line,
                                style = codeStyle,
                                softWrap = false,
                                maxLines = 1,
                            )
                        }
                    }
                }

                // Vertical scrollbar
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(verticalScrollState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )

                // Horizontal scrollbar
                HorizontalScrollbar(
                    adapter = rememberScrollbarAdapter(horizontalScrollState),
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                )
            }
        }
    }
}
