package com.bugdigger.bytesight.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle

/**
 * Colors used for Java syntax highlighting, designed for dark themes.
 */
data class SyntaxColors(
    val keyword: Color = Color(0xFF569CD6),       // Blue
    val string: Color = Color(0xFFCE9178),         // Orange-brown
    val comment: Color = Color(0xFF6A9955),        // Green-gray
    val annotation: Color = Color(0xFFDCDCAA),     // Yellow
    val number: Color = Color(0xFFB5CEA8),         // Light green
    val type: Color = Color(0xFF4EC9B0),           // Teal
    val plain: Color = Color(0xFFD4D4D4),          // Light gray
)

/**
 * Regex-based Java syntax highlighter that produces [AnnotatedString] with colored spans.
 *
 * Designed for read-only display of decompiled Java source from Vineflower.
 * Applies token patterns in priority order: block comments > line comments > strings >
 * char literals > annotations > keywords > numbers > type names > plain text.
 */
class JavaSyntaxHighlighter(private val colors: SyntaxColors = SyntaxColors()) {

    private val keywords = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new",
        "package", "private", "protected", "public", "return", "short", "static",
        "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
        "transient", "try", "void", "volatile", "while",
        "true", "false", "null", "var", "yield", "record", "sealed", "permits",
        "non-sealed",
    )

    // Patterns ordered by priority (first match wins)
    private val blockCommentPattern = Regex("""/\*[\s\S]*?\*/""")
    private val lineCommentPattern = Regex("""//[^\n]*""")
    private val stringPattern = Regex(""""(?:[^"\\]|\\.)*"""")
    private val charPattern = Regex("""'(?:[^'\\]|\\.)'""")
    private val annotationPattern = Regex("""@\w+""")
    private val numberPattern = Regex("""\b(?:0[xX][0-9a-fA-F_]+[lL]?|0[bB][01_]+[lL]?|\d[\d_]*\.[\d_]*(?:[eE][+-]?\d+)?[fFdD]?|\d[\d_]*[lLfFdD]?)\b""")
    private val wordPattern = Regex("""\b[A-Za-z_]\w*\b""")

    fun highlight(code: String): AnnotatedString {
        val builder = AnnotatedString.Builder(code)
        // Apply default color to entire string
        builder.addStyle(SpanStyle(color = colors.plain), 0, code.length)

        // Track which character positions are already highlighted
        val highlighted = BooleanArray(code.length)

        fun applyMatches(pattern: Regex, style: SpanStyle) {
            for (match in pattern.findAll(code)) {
                val start = match.range.first
                val end = match.range.last + 1
                if (!highlighted[start]) {
                    builder.addStyle(style, start, end)
                    for (i in start until end) highlighted[i] = true
                }
            }
        }

        // Apply in priority order
        applyMatches(blockCommentPattern, SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic))
        applyMatches(lineCommentPattern, SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic))
        applyMatches(stringPattern, SpanStyle(color = colors.string))
        applyMatches(charPattern, SpanStyle(color = colors.string))
        applyMatches(annotationPattern, SpanStyle(color = colors.annotation))
        applyMatches(numberPattern, SpanStyle(color = colors.number))

        // Keywords and type names need word-level checking
        for (match in wordPattern.findAll(code)) {
            val start = match.range.first
            val end = match.range.last + 1
            if (highlighted[start]) continue

            val word = match.value
            val style = when {
                word in keywords -> SpanStyle(color = colors.keyword)
                word.first().isUpperCase() -> SpanStyle(color = colors.type)
                else -> null
            }
            if (style != null) {
                builder.addStyle(style, start, end)
                for (i in start until end) highlighted[i] = true
            }
        }

        return builder.toAnnotatedString()
    }
}
