package com.bugdigger.bytesight.service

import com.bugdigger.bytesight.source.ClassSource
import com.bugdigger.core.analysis.XrefIndex
import com.bugdigger.core.analysis.XrefIndexer
import com.bugdigger.core.analysis.XrefSite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Caches an [XrefIndex] over the active [ClassSource]. Built lazily on
 * the first xref query, invalidated when the source changes (we observe
 * [ConnectionRegistry.classSource] and drop the cache on every emission).
 *
 * Building is async — [findCallersOf] / [findUsersOf] are `suspend` and
 * return whatever's currently available. [buildStatus] is a [StateFlow]
 * the UI watches to render a progress indicator.
 */
class XrefService(
    private val connectionRegistry: ConnectionRegistry,
    private val indexer: XrefIndexer = XrefIndexer(),
    coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val logger = LoggerFactory.getLogger(XrefService::class.java)

    sealed interface BuildStatus {
        data object Idle : BuildStatus
        data class Building(val progress: Float) : BuildStatus
        data object Ready : BuildStatus
        data class Failed(val reason: String) : BuildStatus
    }

    private val _buildStatus = MutableStateFlow<BuildStatus>(BuildStatus.Idle)
    val buildStatus: StateFlow<BuildStatus> = _buildStatus.asStateFlow()

    /** The source the cached index was built against. Null when no index. */
    @Volatile
    private var indexedSource: ClassSource? = null

    /** Cached index. Null while building or after invalidation. */
    @Volatile
    private var cachedIndex: XrefIndex? = null

    /** Active build job — used to await completion when a query races a build. */
    @Volatile
    private var buildJob: Job? = null

    init {
        // Invalidate the cache whenever the source changes (live attach,
        // open JAR, open .bts, disconnect, etc.).
        coroutineScope.launch {
            connectionRegistry.classSource.collect { source ->
                if (source !== indexedSource) {
                    indexedSource = null
                    cachedIndex = null
                    buildJob?.cancel()
                    buildJob = null
                    _buildStatus.value = BuildStatus.Idle
                }
            }
        }
    }

    /** Callers of [methodKey] (`class#name+desc`). Triggers a build if needed. */
    suspend fun findCallersOf(methodKey: String): List<XrefSite> =
        ensureBuilt()?.methodCallers?.get(methodKey).orEmpty()

    /** Users of [classFqn]. Triggers a build if needed. */
    suspend fun findUsersOf(classFqn: String): List<XrefSite> =
        ensureBuilt()?.classUsers?.get(classFqn).orEmpty()

    /**
     * Returns the cached index if [buildStatus] is [BuildStatus.Ready];
     * otherwise builds it now (suspending until done) against the current
     * source. Concurrent callers all observe the same in-flight build.
     */
    private suspend fun ensureBuilt(): XrefIndex? {
        val cached = cachedIndex
        if (cached != null) return cached

        val source = connectionRegistry.classSource.value ?: return null
        return buildFor(source)
    }

    private suspend fun buildFor(source: ClassSource): XrefIndex? = withContext(Dispatchers.Default) {
        // If another build is in flight against the same source, await it.
        val existing = buildJob
        if (existing != null && existing.isActive && indexedSource === source) {
            existing.join()
            return@withContext cachedIndex
        }

        _buildStatus.value = BuildStatus.Building(progress = 0f)
        runCatching {
            val classBytes = collectBytecode(source)
            indexer.build(classBytes)
        }
            .onSuccess { built ->
                if (connectionRegistry.classSource.value === source) {
                    cachedIndex = built
                    indexedSource = source
                    _buildStatus.value = BuildStatus.Ready
                }
                // If the source changed during the build, drop the result
                // silently — the next query against the new source will
                // trigger a fresh build.
            }
            .onFailure { e ->
                logger.warn("Xref index build failed", e)
                _buildStatus.value = BuildStatus.Failed(e.message ?: "Unknown error")
            }
        cachedIndex
    }

    private suspend fun collectBytecode(source: ClassSource): Map<String, ByteArray> {
        val classes = source.listClasses(includeSystemClasses = false).getOrThrow()
        val total = classes.size
        val out = HashMap<String, ByteArray>(total)
        for ((i, info) in classes.withIndex()) {
            source.getBytecode(info.name).onSuccess { out[info.name] = it }
            // Coarse progress every 16 classes so the UI doesn't recompose
            // on every iteration.
            if (i % 16 == 0 && total > 0) {
                _buildStatus.value = BuildStatus.Building(progress = i.toFloat() / total)
            }
        }
        return out
    }
}
