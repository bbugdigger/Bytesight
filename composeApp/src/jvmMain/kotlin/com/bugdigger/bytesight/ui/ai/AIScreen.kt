package com.bugdigger.bytesight.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Chat-style UI for conversing with the Bytesight AI agent. Pre-populated prompts from
 * other screens are supported through [pendingPrompt] — the screen auto-sends it and
 * then calls [onPendingPromptConsumed] so the caller can clear it.
 */
@Composable
fun AIScreen(
    viewModel: AIViewModel,
    pendingPrompt: String? = null,
    onPendingPromptConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(pendingPrompt) {
        if (!pendingPrompt.isNullOrBlank()) {
            viewModel.sendPrompt(pendingPrompt)
            onPendingPromptConsumed()
        }
    }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        AIHeader(
            isConfigured = uiState.isConfigured,
            onClear = viewModel::clearChat,
        )

        Spacer(Modifier.height(12.dp))

        if (!uiState.isConfigured) {
            NotConfiguredBanner()
            Spacer(Modifier.height(12.dp))
        }

        uiState.error?.let { err ->
            ErrorBanner(error = err, onDismiss = viewModel::clearError)
            Spacer(Modifier.height(12.dp))
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (uiState.messages.isEmpty()) {
                item { EmptyChatPlaceholder() }
            }
            items(uiState.messages, key = { it.id }) { msg ->
                MessageBubble(msg)
            }
            if (uiState.isThinking) {
                item { ThinkingIndicator() }
            }
        }

        Spacer(Modifier.height(8.dp))

        InputBar(
            input = uiState.input,
            isThinking = uiState.isThinking,
            isEnabled = uiState.isConfigured,
            onInputChange = viewModel::setInput,
            onSend = { viewModel.sendMessage() },
        )
    }
}

@Composable
private fun AIHeader(isConfigured: Boolean, onClear: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "AI Assistant",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        if (isConfigured) {
            OutlinedButton(onClick = onClear) { Text("Clear chat") }
        }
    }
}

@Composable
private fun NotConfiguredBanner() {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "AI agent is not configured. Open Settings to pick a provider and model " +
                "(and add an API key if the provider requires one).",
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun ErrorBanner(error: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp),
        ) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun EmptyChatPlaceholder() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Ask the agent anything about the attached JVM.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Examples:",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "• List all non-system classes",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "• Decompile com.example.Foo and explain it",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "• Find strings containing 'password'",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == ChatRole.USER
    val background = when (msg.role) {
        ChatRole.USER -> MaterialTheme.colorScheme.primaryContainer
        ChatRole.AGENT -> MaterialTheme.colorScheme.surfaceVariant
        ChatRole.SYSTEM -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val textColor = when (msg.role) {
        ChatRole.USER -> MaterialTheme.colorScheme.onPrimaryContainer
        ChatRole.AGENT -> MaterialTheme.colorScheme.onSurfaceVariant
        ChatRole.SYSTEM -> MaterialTheme.colorScheme.onTertiaryContainer
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        if (isUser) Spacer(Modifier.weight(1f))
        Surface(
            color = background,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 720.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = when (msg.role) {
                        ChatRole.USER -> "You"
                        ChatRole.AGENT -> "Agent"
                        ChatRole.SYSTEM -> "System"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = msg.text,
                    color = textColor,
                    fontFamily = if (msg.role == ChatRole.AGENT) FontFamily.Monospace else FontFamily.Default,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (!isUser) Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ThinkingIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Thinking…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InputBar(
    input: String,
    isThinking: Boolean,
    isEnabled: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            enabled = isEnabled && !isThinking,
            placeholder = {
                Text(
                    text = "Ask the agent…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            maxLines = 6,
            modifier = Modifier.weight(1f),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onSend,
            enabled = isEnabled && !isThinking && input.isNotBlank(),
            modifier = Modifier.height(56.dp),
        ) {
            Text("Send")
        }
    }
}
