package com.bugdigger.bytesight.ui.diff

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bugdigger.core.diff.MatchedPair
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * BytecodeDiff tab. Two pickers (left/right), match list ranked by
 * confidence, and a per-pair detail panel showing the per-feature score
 * breakdown plus an "Apply old name → new" button. Side-by-side
 * disassembly is intentionally deferred — gets us the pairing UX cheaply
 * and can grow next.
 */
@Composable
fun BytecodeDiffScreen(
    viewModel: BytecodeDiffViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DiffHeader()

        DiffControls(
            leftLabel = uiState.leftLabel,
            rightLabel = uiState.rightLabel,
            isRunning = uiState.isRunning,
            onPickLeft = { pickProjectFile("Open OLD project")?.let(viewModel::openLeft) },
            onPickRight = { pickProjectFile("Open NEW project")?.let(viewModel::openRight) },
        )

        if (uiState.isRunning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        uiState.error?.let { err ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::clearError) { Text("Dismiss") }
                }
            }
        }

        // Match list (top) + selected-pair detail (bottom)
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MatchListCard(
                pairs = uiState.matchedPairs,
                selectedPair = uiState.selectedPair,
                onSelect = viewModel::selectPair,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )

            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PairDetailCard(
                    pair = uiState.selectedPair,
                    rightRenamesContains = { key -> uiState.rightRenames.containsKey(key) },
                    onApplyRename = { uiState.selectedPair?.let(viewModel::applyOldRename) },
                )
                AddedRemovedCard(
                    addedInNew = uiState.addedInNew,
                    removedFromOld = uiState.removedFromOld,
                    modifier = Modifier.fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun DiffHeader() {
    Column {
        Text(
            text = "Bytecode Diff",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Compare two projects (.bts or .jar). Methods are paired by " +
                "opcode, callees, signature, and string constants. Click a match to " +
                "see the score breakdown and transfer the old project's rename.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DiffControls(
    leftLabel: String,
    rightLabel: String,
    isRunning: Boolean,
    onPickLeft: () -> Unit,
    onPickRight: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(enabled = !isRunning, onClick = onPickLeft) {
                Text("Open OLD…")
            }
            Text(
                text = leftLabel.ifEmpty { "(none)" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "vs",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = rightLabel.ifEmpty { "(none)" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(enabled = !isRunning, onClick = onPickRight) {
                Text("Open NEW…")
            }
        }
    }
}

@Composable
private fun MatchListCard(
    pairs: List<MatchedPair>,
    selectedPair: MatchedPair?,
    onSelect: (MatchedPair?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Matches (${pairs.size})",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            if (pairs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Pick OLD and NEW projects to begin",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(pairs, key = { "${it.old.key}->${it.new.key}" }) { pair ->
                        MatchRow(
                            pair = pair,
                            selected = pair === selectedPair,
                            onClick = { onSelect(if (pair === selectedPair) null else pair) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchRow(pair: MatchedPair, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0f)
        },
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "%.0f%%".format(pair.confidence * 100),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = confidenceColor(pair.confidence),
                modifier = Modifier.width(48.dp),
            )
            Text(
                text = "${pair.old.className.substringAfterLast('.')}.${pair.old.methodName}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = "${pair.new.className.substringAfterLast('.')}.${pair.new.methodName}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun confidenceColor(confidence: Double) = when {
    confidence >= 0.95 -> MaterialTheme.colorScheme.primary
    confidence >= 0.75 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun PairDetailCard(
    pair: MatchedPair?,
    rightRenamesContains: (String) -> Boolean,
    onApplyRename: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (pair == null) {
                Text(
                    text = "Select a match to see its score breakdown.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Card
            }

            Text(
                text = "Confidence: %.1f%%".format(pair.confidence * 100),
                style = MaterialTheme.typography.titleSmall,
                color = confidenceColor(pair.confidence),
            )
            Spacer(Modifier.height(8.dp))
            FeatureLine("Opcodes (cosine)", pair.features.opcodeHistogramCosine)
            FeatureLine("Callees (Jaccard)", pair.features.calleeJaccard)
            FeatureLine("Signature", pair.features.signatureScore)
            FeatureLine("Strings (Jaccard)", pair.features.stringJaccard)

            Spacer(Modifier.height(12.dp))
            Text(
                text = "Old key: ${pair.old.key}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "New key: ${pair.new.key}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            val newKey = "${pair.new.className}#${pair.new.methodName}${pair.new.descriptor}"
            val alreadyApplied = rightRenamesContains(newKey)
            OutlinedButton(
                enabled = !alreadyApplied,
                onClick = onApplyRename,
            ) {
                Text(
                    if (alreadyApplied) "Rename applied"
                    else "Apply old name → new",
                )
            }
        }
    }
}

@Composable
private fun FeatureLine(label: String, value: Double) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "%.2f".format(value),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun AddedRemovedCard(
    addedInNew: List<com.bugdigger.core.diff.MethodFingerprint>,
    removedFromOld: List<com.bugdigger.core.diff.MethodFingerprint>,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Added in new (${addedInNew.size}) · Removed from old (${removedFromOld.size})",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(addedInNew) { fp ->
                    Text(
                        text = "+ ${fp.className.substringAfterLast('.')}.${fp.methodName}",
                        style = MaterialTheme.typography.bodySmall
                            .copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                items(removedFromOld) { fp ->
                    Text(
                        text = "- ${fp.className.substringAfterLast('.')}.${fp.methodName}",
                        style = MaterialTheme.typography.bodySmall
                            .copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun pickProjectFile(title: String): File? {
    val dlg = FileDialog(null as Frame?, title, FileDialog.LOAD)
    dlg.file = "*.bts;*.jar"
    dlg.isVisible = true
    val name = dlg.file ?: return null
    val dir = dlg.directory ?: return null
    return File(dir, name)
}
