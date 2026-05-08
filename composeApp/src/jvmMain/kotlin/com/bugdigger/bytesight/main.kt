package com.bugdigger.bytesight

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.bugdigger.bytesight.di.appModule
import com.bugdigger.bytesight.service.ProjectService
import com.bugdigger.bytesight.ui.menubar.MainMenuBar
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("Main")

fun main() = application {
    val windowState = rememberWindowState(
        size = DpSize(1280.dp, 800.dp),
    )

    Window(
        onCloseRequest = ::exitApplication,
        title = "Bytesight - JVM Reverse Engineering",
        state = windowState,
    ) {
        KoinApplication(
            application = {
                modules(appModule)
            },
        ) {
            // MenuBar must live inside KoinApplication so koinInject() resolves.
            MainMenuBarHost()
            App()
        }
    }
}

/**
 * Resolves [ProjectService] from Koin and wires the application's File menu.
 * Errors raised by save/load are logged for now — the in-app `ErrorBanner`
 * pattern from the existing screens can be used later for user-visible toasts.
 */
@Composable
private fun FrameWindowScope.MainMenuBarHost() {
    val projectService: ProjectService = koinInject()
    val scope = rememberCoroutineScope()
    val currentFile = remember { mutableStateOf<File?>(null) }

    MainMenuBar(
        projectService = projectService,
        scope = scope,
        currentFile = currentFile,
        onError = { msg -> logger.warn("File menu: {}", msg) },
    )
}
