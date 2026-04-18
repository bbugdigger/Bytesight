package com.bugdigger.bytesight.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Tag used for annotated string click regions on identifiers. */
private const val IDENTIFIER_TAG = "identifier"

/** Cyan color for renamed symbols. */
private val RenamedColor = Color(0xFF00E5FF)

/** Regex matching Java/Kotlin identifiers. */
private val identifierPattern = Regex("""\b[A-Za-z_]\w*\b""")

/**
 * A pure Compose code viewer with syntax highlighting, line numbers, and optional
 * interactive rename support.
 *
 * When [renamedSymbols] is provided, renamed identifiers are highlighted in cyan.
 * When [onRenameRequest] is provided, the viewer becomes interactive: click an
 * identifier to select it, then press "N" or right-click → "Rename Symbol..." to
 * trigger a rename.
 *
 * @param code The source code to display.
 * @param renamedSymbols Map of original short name → new name for highlighting.
 * @param onRenameRequest Callback invoked with the identifier text when user requests rename.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CodeViewer(
    code: String,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") language: String = "java",
    renamedSymbols: Map<String, String> = emptyMap(),
    onRenameRequest: ((String) -> Unit)? = null,
) {
    val isInteractive = onRenameRequest != null
    val highlighter = remember { JavaSyntaxHighlighter() }
    val lines = remember(code) { code.lines() }
    val highlightedLines = remember(code, renamedSymbols) {
        lines.map { line ->
            addIdentifierAnnotations(highlighter.highlight(line), renamedSymbols)
        }
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

    // Selected identifier state
    var selectedWord by remember { mutableStateOf<String?>(null) }
    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuOffset by remember { mutableStateOf(DpOffset.Zero) }
    val focusRequester = remember { FocusRequester() }

    Box(
        modifier = modifier
            .then(
                if (isInteractive) {
                    Modifier
                        .focusRequester(focusRequester)
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown &&
                                event.key == Key.N &&
                                !event.isCtrlPressed &&
                                selectedWord != null
                            ) {
                                onRenameRequest.invoke(selectedWord!!)
                                true
                            } else false
                        }
                } else Modifier
            ),
    ) {
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(verticalScrollState)
                        .horizontalScroll(horizontalScrollState)
                        .padding(start = 8.dp),
                ) {
                    for (line in highlightedLines) {
                        if (isInteractive) {
                            Box(
                                modifier = Modifier.onPointerEvent(PointerEventType.Press) { event ->
                                    val button = event.button
                                    if (button == PointerButton.Secondary && selectedWord != null) {
                                        showContextMenu = true
                                        val position = event.changes.firstOrNull()?.position
                                        if (position != null) {
                                            contextMenuOffset = DpOffset(position.x.dp, position.y.dp)
                                        }
                                    }
                                },
                            ) {
                                @Suppress("DEPRECATION")
                                ClickableText(
                                    text = applySelectionHighlight(line, selectedWord),
                                    style = codeStyle,
                                    softWrap = false,
                                    maxLines = 1,
                                    onClick = { offset ->
                                        val word = findIdentifierAtOffset(line, offset)
                                        selectedWord = word
                                        if (word != null) {
                                            focusRequester.requestFocus()
                                        }
                                    },
                                )
                            }
                        } else {
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

        // Context menu for rename
        if (showContextMenu && selectedWord != null) {
            DropdownMenu(
                expanded = true,
                onDismissRequest = { showContextMenu = false },
                offset = contextMenuOffset,
            ) {
                DropdownMenuItem(
                    text = { Text("Rename Symbol...  (N)") },
                    onClick = {
                        showContextMenu = false
                        onRenameRequest?.invoke(selectedWord!!)
                    },
                )
            }
        }
    }
}

/**
 * Adds [IDENTIFIER_TAG] annotations to all Java identifiers in the highlighted line,
 * and applies cyan color + underline to identifiers that appear in [renamedSymbols] values.
 */
private fun addIdentifierAnnotations(
    highlighted: AnnotatedString,
    renamedSymbols: Map<String, String>,
): AnnotatedString {
    val text = highlighted.text
    val renamedValues = renamedSymbols.values.toSet()

    return buildAnnotatedString {
        append(highlighted)

        // Add identifier annotations for click detection
        for (match in identifierPattern.findAll(text)) {
            val start = match.range.first
            val end = match.range.last + 1
            addStringAnnotation(IDENTIFIER_TAG, match.value, start, end)

            // Highlight renamed symbols
            if (match.value in renamedValues) {
                addStyle(
                    SpanStyle(
                        color = RenamedColor,
                        fontWeight = FontWeight.Bold,
                        textDecoration = TextDecoration.Underline,
                    ),
                    start,
                    end,
                )
            }
        }
    }
}

/**
 * Finds the identifier annotation at the given character [offset] in an [AnnotatedString].
 */
private fun findIdentifierAtOffset(line: AnnotatedString, offset: Int): String? {
    return line.getStringAnnotations(IDENTIFIER_TAG, offset, offset)
        .firstOrNull()
        ?.item
}

/**
 * Applies a selection highlight (underline + bold) to all occurrences of [selectedWord]
 * in the annotated line.
 */
private fun applySelectionHighlight(
    line: AnnotatedString,
    selectedWord: String?,
): AnnotatedString {
    if (selectedWord == null) return line

    return buildAnnotatedString {
        append(line)
        for (annotation in line.getStringAnnotations(IDENTIFIER_TAG, 0, line.length)) {
            if (annotation.item == selectedWord) {
                addStyle(
                    SpanStyle(
                        background = Color(0x40569CD6), // semi-transparent blue highlight
                    ),
                    annotation.start,
                    annotation.end,
                )
            }
        }
    }
}
