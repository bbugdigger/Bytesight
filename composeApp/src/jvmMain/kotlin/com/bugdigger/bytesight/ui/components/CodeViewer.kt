package com.bugdigger.bytesight.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.toArgb
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rsyntaxtextarea.Theme
import org.fife.ui.rtextarea.RTextScrollPane
import java.awt.Color
import java.awt.Font
import javax.swing.BorderFactory

/**
 * A code viewer component using RSyntaxTextArea for syntax highlighting.
 * Uses Swing interop to embed the editor in Compose.
 */
@Composable
fun CodeViewer(
    code: String,
    modifier: Modifier = Modifier,
    language: String = SyntaxConstants.SYNTAX_STYLE_JAVA,
) {
    val backgroundColor = MaterialTheme.colorScheme.surface.toArgb()
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()

    val textArea = remember {
        RSyntaxTextArea().apply {
            syntaxEditingStyle = language
            isCodeFoldingEnabled = true
            isEditable = false
            antiAliasingEnabled = true
            font = Font("JetBrains Mono", Font.PLAIN, 13).let { preferred ->
                // Fallback to monospaced if JetBrains Mono is not available
                if (preferred.family == "JetBrains Mono") preferred
                else Font(Font.MONOSPACED, Font.PLAIN, 13)
            }
            tabSize = 4

            // Apply dark theme
            applyDarkTheme(this, backgroundColor, textColor)
        }
    }

    val scrollPane = remember {
        RTextScrollPane(textArea).apply {
            border = BorderFactory.createEmptyBorder()
            lineNumbersEnabled = true
            isFoldIndicatorEnabled = true

            // Style the gutter (line numbers area)
            gutter.background = Color(backgroundColor).darker()
            gutter.lineNumberColor = Color(textColor).let { Color(it.red, it.green, it.blue, 128) }
            gutter.borderColor = Color(backgroundColor)
        }
    }

    // Update the text when code changes
    DisposableEffect(code) {
        textArea.text = code
        textArea.caretPosition = 0
        onDispose { }
    }

    SwingPanel(
        modifier = modifier.fillMaxSize(),
        factory = { scrollPane },
    )
}

/**
 * Applies a dark theme to the RSyntaxTextArea.
 */
private fun applyDarkTheme(textArea: RSyntaxTextArea, backgroundColor: Int, textColor: Int) {
    try {
        // Try to load the built-in dark theme
        val themeStream = RSyntaxTextArea::class.java.getResourceAsStream(
            "/org/fife/ui/rsyntaxtextarea/themes/dark.xml"
        )
        if (themeStream != null) {
            val theme = Theme.load(themeStream)
            theme.apply(textArea)
            themeStream.close()
        }
    } catch (e: Exception) {
        // Fallback: manually set colors
        textArea.background = Color(backgroundColor)
        textArea.foreground = Color(textColor)
        textArea.currentLineHighlightColor = Color(backgroundColor).brighter()
        textArea.selectionColor = Color(0x3399FF).let { Color(it.red, it.green, it.blue, 80) }
        textArea.caretColor = Color(textColor)
    }
}
