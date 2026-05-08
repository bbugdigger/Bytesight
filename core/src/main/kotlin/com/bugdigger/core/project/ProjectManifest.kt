package com.bugdigger.core.project

import kotlinx.serialization.Serializable

/**
 * Project file container schema. Bumped when the on-disk format changes
 * incompatibly. Old versions can be migrated, never silently overwritten.
 */
const val PROJECT_FORMAT_VERSION = 1

/**
 * Top-level metadata stored as `manifest.json` inside the .bts container.
 * Keep this small — it's read on every project open.
 */
@Serializable
data class ProjectManifest(
    val displayName: String,
    /** Where the bytes originally came from. Cosmetic — used for the title bar. */
    val sourceKind: SourceKind,
    /** Path to the original JAR/APK or "live PID 1234". Cosmetic; not used to re-attach. */
    val originalPath: String? = null,
    val createdAt: Long = 0L,
    val bytesightVersion: String = "0.0.0",
    /** Names of optional sidecar files (.btstrace, .btsheap) that shipped alongside. */
    val sidecars: List<String> = emptyList(),
    val formatVersion: Int = PROJECT_FORMAT_VERSION,
)

@Serializable
enum class SourceKind {
    /** Snapshot of a once-live agent attach. */
    LIVE_SNAPSHOT,
    JAR,
    APK,
    /** A loaded .bts re-saved (so we don't lose origin info). */
    BTS,
}
