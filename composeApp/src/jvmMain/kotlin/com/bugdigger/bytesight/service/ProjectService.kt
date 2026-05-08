package com.bugdigger.bytesight.service

import com.bugdigger.bytesight.debugger.DebuggerState
import com.bugdigger.bytesight.source.AgentClassSource
import com.bugdigger.bytesight.source.BtsProjectClassSource
import com.bugdigger.bytesight.source.ClassSource
import com.bugdigger.bytesight.source.JarClassSource
import com.bugdigger.core.analysis.StaticHierarchyExtractor
import com.bugdigger.core.project.BtsProjectFile
import com.bugdigger.core.project.ProjectManifest
import com.bugdigger.core.project.SourceKind
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Save & load of `.bts` project files. All disk I/O is wrapped in [Result].
 *
 * Save flow: snapshot every class from the active [ClassSource], dump
 * rename/comment/breakpoint JSON, write a [BtsProjectFile] atomically.
 *
 * Load flow: open the .bts, restore stores from JSON entries (best-effort —
 * missing entries leave that store empty), install a [BtsProjectClassSource]
 * on the registry. The previously-active source is closed by
 * [ConnectionRegistry.setSource].
 */
class ProjectService(
    private val connectionRegistry: ConnectionRegistry,
    private val projectSession: ProjectSession,
    private val renameStore: RenameStore,
    private val commentStore: CommentStore,
    private val debuggerState: DebuggerState,
    private val hierarchyExtractor: StaticHierarchyExtractor,
    private val json: Json,
    private val bytesightVersion: String,
) {

    private val logger = LoggerFactory.getLogger(ProjectService::class.java)

    /** Save the current session as a `.bts` file at [destination]. */
    suspend fun saveAs(destination: File, displayName: String): Result<Unit> = runCatching {
        val source = connectionRegistry.classSource.value
            ?: error("No active source to save")

        // Snapshot every class from the active source.
        val list = source.listClasses(includeSystemClasses = false).getOrThrow()
        val classes = mutableMapOf<String, ByteArray>()
        for (info in list) {
            source.getBytecode(info.name)
                .onSuccess { classes[info.name] = it }
                .onFailure { logger.warn("Skipping ${info.name}: ${it.message}") }
        }
        require(classes.isNotEmpty()) { "Nothing to save — no class bytecode collected" }

        val manifest = ProjectManifest(
            displayName = displayName,
            sourceKind = inferSourceKind(source),
            originalPath = source.displayName,
            createdAt = System.currentTimeMillis(),
            bytesightVersion = bytesightVersion,
            sidecars = emptyList(),
        )

        val jsonEntries = mapOf(
            "renames.json" to renameStore.serialize(json),
            "comments.json" to commentStore.serialize(json),
            "breakpoints.json" to debuggerState.serialize(json),
        )

        BtsProjectFile.write(destination, manifest, classes, jsonEntries, json)
        projectSession.setCurrentFile(destination)
        logger.info("Saved project: $destination (${classes.size} classes)")
    }

    /** Load a `.bts` file and install it as the active source. */
    suspend fun load(file: File): Result<Unit> = runCatching {
        val source = BtsProjectClassSource.open(file, hierarchyExtractor, json)

        // Restore stores. Each is best-effort: a missing/corrupt entry resets that store.
        runCatching {
            val text = source.underlyingProjectFile().readJsonEntry("renames.json")
            if (text != null) renameStore.restore(text, json) else renameStore.clearAll()
        }.onFailure {
            logger.warn("Failed to restore renames: ${it.message}")
            renameStore.clearAll()
        }

        runCatching {
            val text = source.underlyingProjectFile().readJsonEntry("comments.json")
            if (text != null) commentStore.restore(text, json) else commentStore.restore("[]", json)
        }.onFailure {
            logger.warn("Failed to restore comments: ${it.message}")
            commentStore.restore("[]", json)
        }

        runCatching {
            val text = source.underlyingProjectFile().readJsonEntry("breakpoints.json")
            if (text != null) debuggerState.restore(text, json) else debuggerState.setBreakpoints(emptyList())
        }.onFailure {
            logger.warn("Failed to restore breakpoints: ${it.message}")
            debuggerState.setBreakpoints(emptyList())
        }

        connectionRegistry.setSource(source, connectionKey = null)
        projectSession.setCurrentFile(file)
        logger.info("Loaded project: $file")
    }

    private fun inferSourceKind(source: ClassSource): SourceKind = when (source) {
        is AgentClassSource -> SourceKind.LIVE_SNAPSHOT
        is BtsProjectClassSource -> SourceKind.BTS
        is JarClassSource -> SourceKind.JAR
        // ApkClassSource will land in a follow-up; no branch needed today.
        else -> SourceKind.JAR
    }
}
