package com.bugdigger.bytesight.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bugdigger.bytesight.service.RenameStore
import com.bugdigger.bytesight.service.XrefService
import com.bugdigger.core.analysis.XrefCategory
import com.bugdigger.core.analysis.XrefSite

/**
 * UI-side state for the xref popup. Held by [InspectorViewModel] so the
 * dialog can render either while the service is still indexing the
 * project or once results are ready.
 */
data class XrefDialogState(
    /** Display title (already rename-substituted by the VM). */
    val targetLabel: String,
    val callers: List<XrefSite> = emptyList(),
    val classUsers: List<XrefSite> = emptyList(),
    val buildStatus: XrefService.BuildStatus = XrefService.BuildStatus.Idle,
    /** Active rename map; passed through so row labels show user-assigned names. */
    val renames: Map<String, String> = emptyMap(),
)

/**
 * IDA-style cross-references popup. Two sections — `Callers` (every place
 * the selected method is invoked) and `Class users` (every place the
 * surrounding class is referenced as a type). Click a row → navigate the
 * Inspector to that class+method.
 *
 * Visual style mirrors [RenameDisambiguationDialog].
 */
@Composable
fun XrefDialog(
    state: XrefDialogState,
    onPickSite: (XrefSite) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Cross-references for ${state.targetLabel}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .widthIn(min = 480.dp)
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (val status = state.buildStatus) {
                    is XrefService.BuildStatus.Building -> {
                        Text(
                            text = "Building xref index…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LinearProgressIndicator(
                            progress = { status.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is XrefService.BuildStatus.Failed -> {
                        Text(
                            text = "Index build failed: ${status.reason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    XrefService.BuildStatus.Idle, XrefService.BuildStatus.Ready -> {
                        Section(
                            title = "Callers",
                            sites = state.callers,
                            renames = state.renames,
                            onPick = onPickSite,
                            emptyMessage = "No callers found.",
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                        Section(
                            title = "Class users",
                            sites = state.classUsers,
                            renames = state.renames,
                            onPick = onPickSite,
                            emptyMessage = "No external uses.",
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

@Composable
private fun Section(
    title: String,
    sites: List<XrefSite>,
    renames: Map<String, String>,
    onPick: (XrefSite) -> Unit,
    emptyMessage: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "$title (${sites.size})",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (sites.isEmpty()) {
            Text(
                text = emptyMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(sites) { site ->
                    SiteRow(site = site, renames = renames, onClick = { onPick(site) })
                }
            }
        }
    }
}

@Composable
private fun SiteRow(
    site: XrefSite,
    renames: Map<String, String>,
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
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = site.category.displayLabel(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.widthIn(min = 80.dp),
            )
            val callerClassDisplay = RenameStore.displayShortName(site.callerClassFqn, renames)
            val methodDisplay = if (site.callerMethodName.isEmpty()) {
                "(class-level)"
            } else {
                val methodKey = "${site.callerClassFqn}#${site.callerMethodName}${site.callerMethodDescriptor}"
                val displayName = RenameStore.displayShortName(methodKey, renames)
                "$displayName${prettySignatureFromDescriptor(site.callerMethodDescriptor)}"
            }
            Text(
                text = "$callerClassDisplay.$methodDisplay",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.fillMaxWidth(0f))
        }
    }
    // Hint to the layout system: nothing to expand horizontally beyond row.
    Box {}
}

/** Compact label per category shown in the row badge. */
private fun XrefCategory.displayLabel(): String = when (this) {
    XrefCategory.INVOKE_VIRTUAL -> "INVOKEVIRT"
    XrefCategory.INVOKE_STATIC -> "INVOKESTATIC"
    XrefCategory.INVOKE_SPECIAL -> "INVOKESPEC"
    XrefCategory.INVOKE_INTERFACE -> "INVOKEIFC"
    XrefCategory.INVOKE_DYNAMIC -> "INVOKEDYN"
    XrefCategory.NEW -> "NEW"
    XrefCategory.INSTANCEOF -> "INSTANCEOF"
    XrefCategory.CHECKCAST -> "CHECKCAST"
    XrefCategory.MULTI_ANEW_ARRAY -> "MULTINEW"
    XrefCategory.ANEW_ARRAY -> "ANEWARRAY"
    XrefCategory.FIELD_ACCESS -> "FIELD"
    XrefCategory.FIELD_TYPE -> "FIELD_TYPE"
    XrefCategory.PARAM_TYPE -> "PARAM_TYPE"
    XrefCategory.RETURN_TYPE -> "RETURN_TYPE"
    XrefCategory.SUPERCLASS -> "EXTENDS"
    XrefCategory.INTERFACE -> "IMPLEMENTS"
    XrefCategory.LDC_TYPE -> "LDC_TYPE"
}
