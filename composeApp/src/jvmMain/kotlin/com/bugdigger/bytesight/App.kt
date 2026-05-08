package com.bugdigger.bytesight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.bugdigger.bytesight.service.ConnectionRegistry
import com.bugdigger.bytesight.service.ProjectService
import com.bugdigger.bytesight.service.ProjectSession
import com.bugdigger.bytesight.ui.header.AppHeaderBar
import com.bugdigger.bytesight.ui.header.pickBtsSaveAs
import com.bugdigger.bytesight.ui.header.pickBtsToOpen
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import com.bugdigger.bytesight.ui.ai.AIScreen
import com.bugdigger.bytesight.ui.ai.AIViewModel
import com.bugdigger.bytesight.ui.attach.AttachScreen
import com.bugdigger.bytesight.ui.attach.AttachViewModel
import com.bugdigger.bytesight.ui.browser.ClassBrowserScreen
import com.bugdigger.bytesight.ui.browser.ClassBrowserViewModel
import com.bugdigger.bytesight.ui.debugger.DebuggerScreen
import com.bugdigger.bytesight.ui.debugger.DebuggerViewModel
import com.bugdigger.bytesight.ui.diff.BytecodeDiffScreen
import com.bugdigger.bytesight.ui.diff.BytecodeDiffViewModel
import com.bugdigger.bytesight.ui.heap.HeapScreen
import com.bugdigger.bytesight.ui.heap.HeapViewModel
import com.bugdigger.bytesight.ui.hierarchy.HierarchyScreen
import com.bugdigger.bytesight.ui.hierarchy.HierarchyViewModel
import com.bugdigger.bytesight.ui.inspector.InspectorScreen
import com.bugdigger.bytesight.ui.inspector.InspectorViewModel
import com.bugdigger.bytesight.ui.navigation.NavigationState
import com.bugdigger.bytesight.ui.navigation.Screen
import com.bugdigger.bytesight.ui.navigation.Sidebar
import com.bugdigger.bytesight.ui.settings.SettingsScreen
import com.bugdigger.bytesight.ui.settings.SettingsViewModel
import com.bugdigger.bytesight.ui.strings.StringsScreen
import com.bugdigger.bytesight.ui.strings.StringsViewModel
import com.bugdigger.bytesight.ui.theme.BytesightTheme
import com.bugdigger.bytesight.ui.trace.TraceScreen
import com.bugdigger.bytesight.ui.trace.TraceViewModel
import org.koin.compose.koinInject

private val appLogger = LoggerFactory.getLogger("App")

/**
 * Main application composable.
 * Sets up the theme, navigation, and screen routing.
 *
 * Top-level layout is a Column: a thin Compose-native header bar (project
 * status + Open / Save / Save As) above the existing Sidebar/MainContent
 * row. The header replaces the AWT MenuBar so the whole UI inherits the
 * MaterialTheme.
 */
@Composable
fun App() {
    BytesightTheme {
        var navState by remember { mutableStateOf(NavigationState()) }
        val connectionRegistry: ConnectionRegistry = koinInject()
        val projectService: ProjectService = koinInject()
        val projectSession: ProjectSession = koinInject()
        val scope = rememberCoroutineScope()

        val activeSource by connectionRegistry.classSource.collectAsState()
        val currentFile by projectSession.currentFile.collectAsState()

        // Sync source-derived state into navState whenever the source changes.
        // The AttachViewModel and ProjectService both push to the registry;
        // we just observe + reflect.
        LaunchedEffect(activeSource) {
            navState = navState.copy(
                capabilities = activeSource?.capabilities ?: emptySet(),
                isConnected = activeSource != null,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            AppHeaderBar(
                activeSourceName = activeSource?.displayName,
                currentFileName = currentFile?.name,
                onOpen = {
                    pickBtsToOpen()?.let { f ->
                        scope.launch {
                            projectService.load(f).onFailure {
                                appLogger.warn("Open .bts failed", it)
                            }
                        }
                    }
                },
                onSave = {
                    val target = currentFile ?: pickBtsSaveAs()
                    if (target != null) {
                        scope.launch {
                            projectService.saveAs(target, target.nameWithoutExtension)
                                .onFailure { appLogger.warn("Save failed", it) }
                        }
                    }
                },
                onSaveAs = {
                    pickBtsSaveAs(suggestedName = currentFile?.name ?: "project.bts")?.let { f ->
                        scope.launch {
                            projectService.saveAs(f, f.nameWithoutExtension)
                                .onFailure { appLogger.warn("Save As failed", it) }
                        }
                    }
                },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                // Sidebar navigation
                Sidebar(
                    currentScreen = navState.currentScreen,
                    capabilities = navState.capabilities,
                    onNavigate = { screen ->
                        navState = navState.copy(currentScreen = screen)
                    },
                )

                // Main content area
                MainContent(
                    navState = navState,
                    onConnected = { connectionKey ->
                        // ConnectionRegistry was already updated by AttachViewModel —
                        // we just route to Classes here.
                        navState = navState.copy(
                            connectionKey = connectionKey,
                            currentScreen = Screen.CLASS_BROWSER,
                        )
                    },
                    onDisconnected = {
                        navState = navState.copy(
                            connectionKey = null,
                            currentScreen = Screen.ATTACH,
                        )
                    },
                    onNavigateToInspector = { className, methodName, methodSignature ->
                        navState = navState.copy(
                            currentScreen = Screen.INSPECTOR,
                            pendingInspectorClass = className,
                            pendingInspectorMethod = methodName,
                            pendingInspectorMethodSignature = methodSignature,
                        )
                    },
                    onClearPendingInspectorClass = {
                        navState = navState.copy(
                            pendingInspectorClass = null,
                            pendingInspectorMethod = null,
                            pendingInspectorMethodSignature = null,
                        )
                    },
                    onAskAI = { prompt ->
                        navState = navState.copy(
                            currentScreen = Screen.AI,
                            pendingAIPrompt = prompt,
                        )
                    },
                    onClearPendingAIPrompt = {
                        navState = navState.copy(pendingAIPrompt = null)
                    },
                )
            }
        }
    }
}

/**
 * Routes to the appropriate screen based on navigation state.
 */
@Composable
private fun MainContent(
    navState: NavigationState,
    onConnected: (String) -> Unit,
    onDisconnected: () -> Unit,
    onNavigateToInspector: (className: String, methodName: String?, methodSignature: String?) -> Unit,
    onClearPendingInspectorClass: () -> Unit,
    onAskAI: (prompt: String) -> Unit,
    onClearPendingAIPrompt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (navState.currentScreen) {
        Screen.ATTACH -> {
            val viewModel: AttachViewModel = koinInject()
            AttachScreen(
                viewModel = viewModel,
                onConnected = onConnected,
                modifier = modifier,
            )
        }

        Screen.CLASS_BROWSER -> {
            val viewModel: ClassBrowserViewModel = koinInject()
            ClassBrowserScreen(
                viewModel = viewModel,
                onAskAI = onAskAI,
                modifier = modifier,
            )
        }

        Screen.HIERARCHY -> {
            val viewModel: HierarchyViewModel = koinInject()
            HierarchyScreen(
                viewModel = viewModel,
                modifier = modifier,
            )
        }

        Screen.INSPECTOR -> {
            val viewModel: InspectorViewModel = koinInject()
            InspectorScreen(
                viewModel = viewModel,
                pendingClassName = navState.pendingInspectorClass,
                pendingMethodName = navState.pendingInspectorMethod,
                pendingMethodSignature = navState.pendingInspectorMethodSignature,
                onPendingClassConsumed = onClearPendingInspectorClass,
                onAskAI = onAskAI,
                modifier = modifier,
            )
        }

        Screen.STRINGS -> {
            val viewModel: StringsViewModel = koinInject()
            StringsScreen(
                viewModel = viewModel,
                modifier = modifier,
            )
        }

        Screen.TRACE -> {
            val connectionKey = navState.connectionKey
            if (connectionKey != null) {
                val viewModel: TraceViewModel = koinInject()
                TraceScreen(
                    viewModel = viewModel,
                    connectionKey = connectionKey,
                    onAskAI = onAskAI,
                    modifier = modifier,
                )
            }
        }

        Screen.HEAP -> {
            val connectionKey = navState.connectionKey
            if (connectionKey != null) {
                val viewModel: HeapViewModel = koinInject()
                HeapScreen(
                    viewModel = viewModel,
                    connectionKey = connectionKey,
                    // Heap has no method-level context for "Inspect" — open
                    // the class with no preselected method.
                    onNavigateToInspector = { name -> onNavigateToInspector(name, null, null) },
                    modifier = modifier,
                )
            }
        }

        Screen.DEBUGGER -> {
            val connectionKey = navState.connectionKey
            if (connectionKey != null) {
                val viewModel: DebuggerViewModel = koinInject()
                DebuggerScreen(
                    viewModel = viewModel,
                    connectionKey = connectionKey,
                    onNavigateToInspector = onNavigateToInspector,
                    onAskAI = onAskAI,
                    modifier = modifier,
                )
            }
        }

        Screen.BYTECODE_DIFF -> {
            val viewModel: BytecodeDiffViewModel = koinInject()
            BytecodeDiffScreen(
                viewModel = viewModel,
                modifier = modifier,
            )
        }

        Screen.AI -> {
            val viewModel: AIViewModel = koinInject()
            AIScreen(
                viewModel = viewModel,
                pendingPrompt = navState.pendingAIPrompt,
                onPendingPromptConsumed = onClearPendingAIPrompt,
                modifier = modifier,
            )
        }

        Screen.SETTINGS -> {
            val viewModel: SettingsViewModel = koinInject()
            SettingsScreen(
                viewModel = viewModel,
                modifier = modifier,
            )
        }
    }
}
