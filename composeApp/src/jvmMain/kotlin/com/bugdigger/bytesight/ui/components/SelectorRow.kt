package com.bugdigger.bytesight.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bugdigger.core.analysis.DisassembledMethod
import com.bugdigger.protocol.ClassInfo

/**
 * Shared searchable class + method selector row used by the Bytecode Inspector.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectorRow(
    classes: List<ClassInfo>,
    selectedClassName: String?,
    methods: List<DisassembledMethod>,
    selectedMethod: DisassembledMethod?,
    onSelectClass: (String) -> Unit,
    onSelectMethod: (String, String) -> Unit,
    isLoading: Boolean,
    onDropdownExpandedChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var classExpanded by remember { mutableStateOf(false) }
    var methodExpanded by remember { mutableStateOf(false) }
    var classSearchText by remember { mutableStateOf("") }

    LaunchedEffect(classExpanded, methodExpanded) {
        onDropdownExpandedChange(classExpanded || methodExpanded)
    }

    LaunchedEffect(selectedClassName) {
        classSearchText = selectedClassName ?: ""
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExposedDropdownMenuBox(
            expanded = classExpanded,
            onExpandedChange = { classExpanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = classSearchText,
                onValueChange = { text ->
                    classSearchText = text
                    classExpanded = true
                },
                placeholder = { Text("Type to search classes...") },
                label = { Text("Class") },
                singleLine = true,
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = classExpanded)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable),
            )

            val filteredClasses = remember(classSearchText, classes) {
                if (classSearchText.isBlank() || classSearchText == selectedClassName) {
                    classes.take(100)
                } else {
                    classes.filter {
                        it.name.lowercase().contains(classSearchText.lowercase())
                    }.take(100)
                }
            }

            ExposedDropdownMenu(
                expanded = classExpanded,
                onDismissRequest = {
                    classExpanded = false
                    if (classSearchText != selectedClassName) {
                        classSearchText = selectedClassName ?: ""
                    }
                },
                modifier = Modifier.heightIn(max = 300.dp),
            ) {
                if (filteredClasses.isEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "No classes found",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        onClick = {},
                        enabled = false,
                    )
                } else {
                    filteredClasses.forEach { classInfo ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = classInfo.name.substringAfterLast('.'),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = classInfo.name.substringBeforeLast('.', ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                classSearchText = classInfo.name
                                onSelectClass(classInfo.name)
                                classExpanded = false
                            },
                        )
                    }
                }
            }
        }

        ExposedDropdownMenuBox(
            expanded = methodExpanded,
            onExpandedChange = { if (methods.isNotEmpty()) methodExpanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = selectedMethod?.let { "${it.name}${it.descriptor}" } ?: "",
                onValueChange = {},
                placeholder = { Text("Select a method...") },
                label = { Text("Method") },
                singleLine = true,
                readOnly = true,
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = methodExpanded)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                enabled = methods.isNotEmpty(),
            )

            ExposedDropdownMenu(
                expanded = methodExpanded,
                onDismissRequest = { methodExpanded = false },
                modifier = Modifier.heightIn(max = 300.dp),
            ) {
                methods.forEach { method ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "${method.accessString} ${method.name}${method.descriptor}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            onSelectMethod(method.name, method.descriptor)
                            methodExpanded = false
                        },
                    )
                }
            }
        }

        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}
