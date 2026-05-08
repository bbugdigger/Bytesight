package com.bugdigger.bytesight.ui.menubar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import com.bugdigger.bytesight.service.ProjectService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

private val logger = LoggerFactory.getLogger("MainMenuBar")

/**
 * File menu installed on the application Window. Open / Save / Save As round-
 * trip the active session through `.bts` files via [ProjectService].
 *
 * [currentFile] tracks "what does Save target?" — set on a successful Open
 * or Save As, cleared on session reset.
 */
@Composable
fun FrameWindowScope.MainMenuBar(
    projectService: ProjectService,
    scope: CoroutineScope,
    currentFile: MutableState<File?>,
    onError: (String) -> Unit,
) {
    MenuBar {
        Menu("File", mnemonic = 'F') {
            Item("Open .bts…", mnemonic = 'O', onClick = {
                pickFile("Open Project", "*.bts", FileDialog.LOAD)?.let { f ->
                    scope.launch {
                        projectService.load(f)
                            .onSuccess { currentFile.value = f }
                            .onFailure {
                                logger.warn("Open failed", it)
                                onError("Open failed: ${it.message}")
                            }
                    }
                }
            })
            Item("Save", mnemonic = 'S', onClick = {
                val target = currentFile.value
                if (target != null) {
                    scope.launch {
                        projectService.saveAs(target, target.nameWithoutExtension)
                            .onFailure {
                                logger.warn("Save failed", it)
                                onError("Save failed: ${it.message}")
                            }
                    }
                } else {
                    pickFile("Save Project As", "project.bts", FileDialog.SAVE)?.let { f ->
                        val withExt = ensureBtsExtension(f)
                        scope.launch {
                            projectService.saveAs(withExt, withExt.nameWithoutExtension)
                                .onSuccess { currentFile.value = withExt }
                                .onFailure {
                                    logger.warn("Save failed", it)
                                    onError("Save failed: ${it.message}")
                                }
                        }
                    }
                }
            })
            Item("Save As…", onClick = {
                pickFile("Save Project As", "project.bts", FileDialog.SAVE)?.let { f ->
                    val withExt = ensureBtsExtension(f)
                    scope.launch {
                        projectService.saveAs(withExt, withExt.nameWithoutExtension)
                            .onSuccess { currentFile.value = withExt }
                            .onFailure {
                                logger.warn("Save failed", it)
                                onError("Save failed: ${it.message}")
                            }
                    }
                }
            })
        }
    }
}

private fun ensureBtsExtension(f: File): File =
    if (f.extension.equals("bts", ignoreCase = true)) f
    else File(f.parentFile, "${f.name}.bts")

private fun pickFile(title: String, defaultName: String, mode: Int): File? {
    val dlg = FileDialog(null as Frame?, title, mode)
    dlg.file = defaultName
    dlg.isVisible = true
    val name = dlg.file ?: return null
    val dir = dlg.directory ?: return null
    return File(dir, name)
}
