package com.bugdigger.bytesight.source

import com.bugdigger.core.analysis.StaticHierarchyExtractor
import com.bugdigger.core.project.BtsProjectFile
import com.bugdigger.core.project.ProjectManifest
import com.bugdigger.protocol.ClassInfo
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/**
 * [ClassSource] backed by an open `.bts` project file. Capabilities are
 * always [Capability.STATIC_ONLY] — even if the project was originally
 * captured from a live agent, replaying the live RPCs against a snapshot
 * is out of scope.
 */
class BtsProjectClassSource private constructor(
    private val projectFile: BtsProjectFile,
    private val manifest: ProjectManifest,
    hierarchyExtractor: StaticHierarchyExtractor,
) : ClassSource {

    private val logger = LoggerFactory.getLogger(BtsProjectClassSource::class.java)

    override val capabilities: Set<Capability> = Capability.STATIC_ONLY

    override val displayName: String = "${manifest.displayName} (${projectFile.file.name})"

    private val classNames: List<String> = projectFile.listClassEntries().sorted()

    private val classInfos: List<ClassInfo> by lazy {
        classNames.mapNotNull { fqn ->
            val bytes = projectFile.readClass(fqn) ?: return@mapNotNull null
            runCatching {
                val md = hierarchyExtractor.extract(bytes)
                StaticClassInfoMapper.toClassInfo(md, classLoaderName = "BtsProject(${projectFile.file.name})")
            }.onFailure { logger.warn("Failed to extract metadata for $fqn: ${it.message}") }
                .getOrNull()
        }
    }

    /** The opened project file — used by ProjectService for restore-on-load behavior. */
    fun underlyingProjectFile(): BtsProjectFile = projectFile

    /** Manifest as read on open. */
    fun manifest(): ProjectManifest = manifest

    override suspend fun listClasses(includeSystemClasses: Boolean): Result<List<ClassInfo>> =
        Result.success(classInfos)

    override suspend fun getBytecode(className: String): Result<ByteArray> =
        projectFile.readClass(className)?.let { Result.success(it) }
            ?: Result.failure(NoSuchElementException("Class not found: $className"))

    override fun close() {
        projectFile.close()
    }

    companion object {
        fun open(file: File, hierarchyExtractor: StaticHierarchyExtractor, json: Json): BtsProjectClassSource {
            val projectFile = BtsProjectFile.open(file)
            val manifest = projectFile.readManifest(json)
            return BtsProjectClassSource(projectFile, manifest, hierarchyExtractor)
        }
    }
}
