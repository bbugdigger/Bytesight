package com.bugdigger.bytesight.service

import com.bugdigger.ai.BytesightAgentServices
import com.bugdigger.ai.ClassDetail
import com.bugdigger.ai.ClassSummary
import com.bugdigger.ai.FieldSummary
import com.bugdigger.ai.HeapHistogramRow
import com.bugdigger.ai.MethodSummary
import com.bugdigger.ai.StringMatch
import com.bugdigger.ai.TraceSummary
import com.bugdigger.core.analysis.ConstantExtractor
import com.bugdigger.core.analysis.ConstantType
import com.bugdigger.core.decompiler.DecompilationResult
import com.bugdigger.core.decompiler.Decompiler
import com.bugdigger.protocol.ClassInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.slf4j.LoggerFactory
import java.lang.reflect.Modifier

/**
 * Wires the [BytesightAgentServices] interface (used by the `ai` module) to the real
 * composeApp services: [AgentClient] for gRPC calls, [Decompiler] for source recovery,
 * [RenameStore] for user-visible renames, and [ConnectionRegistry] for the current
 * connection context.
 */
class BytesightAgentServicesImpl(
    private val agentClient: AgentClient,
    private val decompiler: Decompiler,
    private val renameStore: RenameStore,
    private val connectionRegistry: ConnectionRegistry,
) : BytesightAgentServices {

    private val logger = LoggerFactory.getLogger(BytesightAgentServicesImpl::class.java)

    private val constantExtractor = ConstantExtractor()
    private val _traceBuffer = ArrayDeque<TraceSummary>()
    private val maxTraceBuffer = 500

    /** Flow the Trace screen can push events into so they show up in [getRecentTraces]. */
    private val _traceIngest = MutableSharedFlow<TraceSummary>(extraBufferCapacity = 256)
    val traceIngest = _traceIngest.asSharedFlow()

    /** Called by the Trace screen VM when a new event arrives so the agent can see it. */
    fun pushTrace(event: TraceSummary) {
        synchronized(_traceBuffer) {
            _traceBuffer.addLast(event)
            while (_traceBuffer.size > maxTraceBuffer) _traceBuffer.removeFirst()
        }
    }

    override fun isConnected(): Boolean = connectionRegistry.connectionKey.value != null

    override suspend fun listClasses(
        filter: String,
        includeSystem: Boolean,
        limit: Int,
    ): List<ClassSummary> {
        val key = connectionRegistry.connectionKey.value ?: return emptyList()
        return agentClient.listClasses(key, filter, includeSystem).fold(
            onSuccess = { list ->
                list.asSequence()
                    .take(limit)
                    .map { it.toSummary() }
                    .toList()
            },
            onFailure = { e ->
                logger.warn("listClasses failed: ${e.message}")
                emptyList()
            },
        )
    }

    override suspend fun decompileClass(className: String): String {
        val key = connectionRegistry.connectionKey.value ?: return "Not connected."
        val bytecode = agentClient.getClassBytecode(key, className).getOrNull()
            ?: return "Class not found: $className"
        val bytes = bytecode.bytecode.toByteArray()
        val result = runCatching { decompiler.decompile(className, bytes) }
            .getOrElse { return "Decompilation failed: ${it.message}" }
        val raw = when (result) {
            is DecompilationResult.Success -> result.sourceCode
            is DecompilationResult.Failure -> return "Decompilation failed: ${result.error}"
        }
        return renameStore.applyToSource(raw)
    }

    override suspend fun getClassInfo(className: String): ClassDetail? {
        val key = connectionRegistry.connectionKey.value ?: return null
        // The agent already exposes methods/fields/super/interfaces in ClassInfo, so skip
        // a separate bytecode round-trip + ASM parse and read from the proto directly.
        val list = agentClient.listClasses(key, className, true).getOrNull() ?: return null
        val info = list.firstOrNull { it.name == className } ?: return null
        return ClassDetail(
            name = info.name,
            superName = info.superclass.ifBlank { null },
            interfaces = info.interfacesList,
            methods = info.methodsList.map {
                MethodSummary(
                    name = it.name,
                    descriptor = it.signature,
                    isStatic = Modifier.isStatic(it.modifiers),
                )
            },
            fields = info.fieldsList.map {
                FieldSummary(
                    name = it.name,
                    descriptor = it.type,
                    isStatic = Modifier.isStatic(it.modifiers),
                )
            },
        )
    }

    override suspend fun searchStrings(query: String, limit: Int): List<StringMatch> {
        val key = connectionRegistry.connectionKey.value ?: return emptyList()
        val classes = agentClient.listClasses(key, "", false).getOrNull() ?: return emptyList()
        val needle = query.lowercase()
        val matches = mutableListOf<StringMatch>()
        for (c in classes) {
            if (matches.size >= limit) break
            val bytes = agentClient.getClassBytecode(key, c.name).getOrNull()?.bytecode?.toByteArray()
                ?: continue
            val constants = runCatching { constantExtractor.extract(bytes) }.getOrNull() ?: continue
            for (k in constants) {
                if (k.type != ConstantType.STRING) continue
                val s = k.value as? String ?: continue
                if (s.lowercase().contains(needle)) {
                    matches.add(StringMatch(className = c.name, value = s))
                    if (matches.size >= limit) break
                }
            }
        }
        return matches
    }

    override suspend fun getRecentTraces(limit: Int): List<TraceSummary> {
        synchronized(_traceBuffer) {
            val size = _traceBuffer.size
            val from = (size - limit).coerceAtLeast(0)
            return _traceBuffer.toList().subList(from, size)
        }
    }

    override fun renameSymbol(originalFqn: String, newName: String) {
        if (newName.isBlank()) return
        renameStore.rename(originalFqn, newName)
    }

    override fun batchRename(renames: Map<String, String>) {
        for ((fqn, newName) in renames) {
            if (fqn.isBlank() || newName.isBlank()) continue
            renameStore.rename(fqn, newName)
        }
    }

    override fun getRenames(): Map<String, String> = renameStore.renameMap.value

    override suspend fun decompileMultiple(classNames: List<String>): Map<String, String> {
        val result = LinkedHashMap<String, String>(classNames.size)
        for (name in classNames.distinct()) {
            if (name.isBlank()) continue
            result[name] = decompileClass(name)
        }
        return result
    }

    override suspend fun getHeapHistogram(nameFilter: String, limit: Int): List<HeapHistogramRow> {
        val key = connectionRegistry.connectionKey.value ?: return emptyList()
        val snapshotId = connectionRegistry.snapshotId.value ?: return emptyList()
        return agentClient.getClassHistogram(key, snapshotId, nameFilter).fold(
            onSuccess = { rows ->
                rows.asSequence()
                    .sortedByDescending { it.shallowBytes }
                    .take(limit)
                    .map { HeapHistogramRow(it.className, it.instanceCount, it.shallowBytes) }
                    .toList()
            },
            onFailure = { e ->
                logger.warn("getHeapHistogram failed: ${e.message}")
                emptyList()
            },
        )
    }

    private fun ClassInfo.toSummary() = ClassSummary(
        name = name,
        isInterface = isInterface,
        isAbstract = Modifier.isAbstract(modifiers),
    )
}
