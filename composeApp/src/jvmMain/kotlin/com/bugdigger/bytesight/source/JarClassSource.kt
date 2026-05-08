package com.bugdigger.bytesight.source

import com.bugdigger.core.analysis.StaticHierarchyExtractor
import com.bugdigger.core.source.JarReader
import com.bugdigger.protocol.ClassInfo
import org.slf4j.LoggerFactory
import java.io.File

/**
 * [ClassSource] backed by a `.jar` file on disk. Reads all class entries on
 * construction and caches them in memory; subsequent calls are pure lookups.
 *
 * Memory cost: roughly equal to the JAR's compressed size (a typical
 * application JAR is a few MB; a translated APK can be 30–60 MB). If we hit
 * larger artifacts we can lazy-load instead, but eager-load matches what the
 * agent does (it caches every loaded class anyway) and keeps `getBytecode`
 * lookup-time constant.
 *
 * Capabilities are [Capability.STATIC_ONLY] — no live agent, so Trace /
 * Heap / Debugger tabs auto-disable through the existing Sidebar gating.
 */
class JarClassSource(
    private val jarFile: File,
    jarReader: JarReader,
    hierarchyExtractor: StaticHierarchyExtractor,
    classLoaderLabel: String = "JarFile(${jarFile.name})",
) : ClassSource {

    private val logger = LoggerFactory.getLogger(JarClassSource::class.java)

    override val capabilities: Set<Capability> = Capability.STATIC_ONLY

    override val displayName: String = jarFile.name

    private val bytecodeMap: Map<String, ByteArray> = jarReader.read(jarFile)

    private val classInfos: List<ClassInfo> by lazy {
        bytecodeMap.entries.mapNotNull { (fqn, bytes) ->
            runCatching {
                val md = hierarchyExtractor.extract(bytes)
                StaticClassInfoMapper.toClassInfo(md, classLoaderLabel)
            }.onFailure { logger.warn("Failed to extract metadata for $fqn: ${it.message}") }
                .getOrNull()
        }
    }

    override suspend fun listClasses(includeSystemClasses: Boolean): Result<List<ClassInfo>> {
        // includeSystemClasses is meaningless for a JAR — the file contains
        // exactly what it contains. We return everything; the UI's
        // search/filter widget handles narrowing.
        return Result.success(classInfos)
    }

    override suspend fun getBytecode(className: String): Result<ByteArray> =
        bytecodeMap[className]?.let { Result.success(it) }
            ?: Result.failure(NoSuchElementException("Class not found: $className"))

    // No resources to close — the JarFile was opened, drained, and closed in JarReader.
}
