package com.bugdigger.bytesight.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bugdigger.bytesight.ui.inspector.PendingRename
import com.bugdigger.bytesight.ui.inspector.RenameCandidate

/**
 * Disambiguation picker shown when a rename's short name maps to more
 * than one symbol in the current class — e.g. two fields named `a` of
 * different types (a JVM-legal pattern obfuscators love), or a field
 * named `a` plus a method named `a` plus a class type `a`.
 *
 * The user picks the specific symbol; the ViewModel constructs a precise
 * key (descriptor included for fields & methods) and applies the rename.
 */
@Composable
fun RenameDisambiguationDialog(
    pending: PendingRename,
    onPick: (RenameCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Rename `${pending.shortName}` to `${pending.newName}`",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(
                modifier = Modifier.widthIn(min = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Multiple symbols in this class match — pick one:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                pending.candidates.forEach { candidate ->
                    CandidateRow(candidate = candidate, onClick = { onPick(candidate) })
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

@Composable
private fun CandidateRow(
    candidate: RenameCandidate,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(6.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = candidate.kindLabel(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = candidate.detailLabel(),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun RenameCandidate.kindLabel(): String = when (this) {
    is RenameCandidate.ClassRef -> "CLASS"
    is RenameCandidate.Field -> "FIELD"
    is RenameCandidate.Method -> "METHOD"
}

/**
 * Display detail per candidate. Shows enough info to disambiguate without
 * dumping the raw JVM descriptor — we humanize types into simple names so
 * the dialog reads naturally.
 */
private fun RenameCandidate.detailLabel(): String = when (this) {
    is RenameCandidate.ClassRef -> classFqn
    is RenameCandidate.Field -> "$name : ${prettyTypeFromDescriptor(descriptor)}"
    is RenameCandidate.Method -> "$name${prettySignatureFromDescriptor(descriptor)}"
}

/** `Ljava/util/Map;` → `Map`, `[Ljava/lang/String;` → `String[]`, `I` → `int`, etc. */
internal fun prettyTypeFromDescriptor(desc: String): String = when {
    desc.startsWith("[") -> "${prettyTypeFromDescriptor(desc.substring(1))}[]"
    desc.startsWith("L") && desc.endsWith(";") ->
        desc.substring(1, desc.length - 1).substringAfterLast('/')
    desc == "I" -> "int"
    desc == "J" -> "long"
    desc == "Z" -> "boolean"
    desc == "B" -> "byte"
    desc == "C" -> "char"
    desc == "S" -> "short"
    desc == "F" -> "float"
    desc == "D" -> "double"
    desc == "V" -> "void"
    else -> desc
}

/** `(Ljava/lang/String;DLo/a;)Lo/b;` → `(String, double, a): b` */
internal fun prettySignatureFromDescriptor(desc: String): String {
    val open = desc.indexOf('(')
    val close = desc.indexOf(')')
    if (open != 0 || close < 0) return desc
    val argsRaw = desc.substring(1, close)
    val ret = desc.substring(close + 1)
    val args = mutableListOf<String>()
    var i = 0
    while (i < argsRaw.length) {
        var end = i
        while (end < argsRaw.length && argsRaw[end] == '[') end++
        end = if (argsRaw[end] == 'L') argsRaw.indexOf(';', end) + 1 else end + 1
        args.add(prettyTypeFromDescriptor(argsRaw.substring(i, end)))
        i = end
    }
    return "(${args.joinToString(", ")}): ${prettyTypeFromDescriptor(ret)}"
}
