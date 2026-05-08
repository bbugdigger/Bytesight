package com.bugdigger.bytesight.ui.header

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Application-wide top bar. Lives inside the Compose tree (not the OS menu
 * strip) so it inherits the dark MaterialTheme. Always visible across tabs.
 *
 * Layout:
 * ```
 * | Bytesight  |  <project label>             [📂] [💾] [⋮] |
 * ```
 *
 * The project label is the file name when a `.bts` is loaded or has been
 * Save-As'd, otherwise the active source's `displayName` (e.g. `JVM @ ...`,
 * `sample.jar`), otherwise `"(no session)"`. Save / Save As are no-ops
 * when no source is active — left enabled for discoverability; the
 * service layer surfaces a clean error if invoked without a source.
 */
@Composable
fun AppHeaderBar(
    activeSourceName: String?,
    currentFileName: String?,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 2.dp,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Bytesight",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )

                // Vertical separator
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(20.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )

                Text(
                    text = currentFileName ?: activeSourceName ?: "(no session)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                HeaderIconButton(glyph = "📂", onClick = onOpen)
                HeaderIconButton(glyph = "💾", onClick = onSave)

                // Overflow menu
                var showOverflow by remember { mutableStateOf(false) }
                Box {
                    HeaderIconButton(glyph = "⋮", onClick = { showOverflow = true })
                    DropdownMenu(
                        expanded = showOverflow,
                        onDismissRequest = { showOverflow = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Save As…") },
                            onClick = {
                                showOverflow = false
                                onSaveAs()
                            },
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.align(Alignment.BottomCenter),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )
        }
    }
}

@Composable
private fun HeaderIconButton(
    glyph: String,
    onClick: () -> Unit,
) {
    // Plain IconButton with text glyph keeps the visual style consistent
    // with the existing Sidebar (which also uses emoji glyphs). A future
    // pass can swap in Material icons project-wide.
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** AWT FileDialog wrapper for picking a `.bts` to open. */
fun pickBtsToOpen(): File? = pickFile("Open Project", "*.bts", FileDialog.LOAD)

/**
 * AWT FileDialog wrapper for picking a Save destination. Auto-appends the
 * `.bts` extension if the user typed a bare name.
 */
fun pickBtsSaveAs(suggestedName: String = "project.bts"): File? {
    val raw = pickFile("Save Project As", suggestedName, FileDialog.SAVE) ?: return null
    return if (raw.extension.equals("bts", ignoreCase = true)) raw
    else File(raw.parentFile, "${raw.name}.bts")
}

private fun pickFile(title: String, defaultName: String, mode: Int): File? {
    val dlg = FileDialog(null as Frame?, title, mode)
    dlg.file = defaultName
    dlg.isVisible = true
    val name = dlg.file ?: return null
    val dir = dlg.directory ?: return null
    return File(dir, name)
}
